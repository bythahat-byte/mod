package dev.clipmod.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rolling window of the most recent encoded frames. Thread-safe: encoder threads add
 * frames while the saver thread takes snapshots.
 */
public final class ReplayBuffer {
	private final ConcurrentSkipListMap<Long, EncodedFrame> frames = new ConcurrentSkipListMap<>();
	private final AtomicLong totalBytes = new AtomicLong();
	private volatile long maxAgeNanos;
	private volatile java.util.function.Consumer<EncodedFrame> listener;

	public ReplayBuffer(int seconds) {
		setLengthSeconds(seconds);
	}

	public void setLengthSeconds(int seconds) {
		// keep one extra second so a full-length clip is always available
		this.maxAgeNanos = (seconds + 1) * 1_000_000_000L;
	}

	/** Also receives every new frame (used for long recordings), or null. */
	public void setListener(java.util.function.Consumer<EncodedFrame> listener) {
		this.listener = listener;
	}

	public void add(EncodedFrame frame) {
		java.util.function.Consumer<EncodedFrame> l = listener;
		if (l != null) {
			l.accept(frame);
		}
		EncodedFrame previous = frames.put(frame.timestampNanos(), frame);
		totalBytes.addAndGet(frame.jpeg().length - (previous == null ? 0 : previous.jpeg().length));
		trim(frame.timestampNanos());
	}

	private void trim(long newest) {
		Map.Entry<Long, EncodedFrame> last = frames.lastEntry();
		if (last != null) {
			newest = Math.max(newest, last.getKey());
		}
		long cutoff = newest - maxAgeNanos;
		Map.Entry<Long, EncodedFrame> oldest;
		while ((oldest = frames.firstEntry()) != null && oldest.getKey() < cutoff) {
			if (frames.remove(oldest.getKey(), oldest.getValue())) {
				totalBytes.addAndGet(-oldest.getValue().jpeg().length);
			}
		}
	}

	public void clear() {
		frames.clear();
		totalBytes.set(0);
	}

	public long bytes() {
		return Math.max(0, totalBytes.get());
	}

	public int frameCount() {
		return frames.size();
	}

	/** Seconds of footage currently held. */
	public double durationSeconds() {
		if (frames.isEmpty()) {
			return 0;
		}
		return (frames.lastKey() - frames.firstKey()) / 1e9;
	}

	/** Size of the newest frame as {width, height}, or null if empty. */
	public int[] newestSize() {
		Map.Entry<Long, EncodedFrame> e = frames.lastEntry();
		return e == null ? null : new int[] {e.getValue().width(), e.getValue().height()};
	}

	/** The last {@code seconds} of frames of the given size, oldest first, not resampled. */
	public List<EncodedFrame> rawFrames(double seconds, int width, int height) {
		List<EncodedFrame> out = new ArrayList<>();
		Map.Entry<Long, EncodedFrame> last = frames.lastEntry();
		if (last == null) {
			return out;
		}
		long start = last.getKey() - (long) (seconds * 1e9);
		for (EncodedFrame f : frames.tailMap(start, true).values()) {
			if (f.width() == width && f.height() == height) {
				out.add(f);
			}
		}
		return out;
	}

	/**
	 * Returns the last {@code seconds} of footage resampled to a constant frame rate,
	 * which is what video containers expect. Frames whose resolution differs from the
	 * newest frame (window was resized) are dropped.
	 */
	public List<EncodedFrame> snapshot(double seconds, int fps) {
		NavigableMap<Long, EncodedFrame> copy = new java.util.TreeMap<>(frames);
		List<EncodedFrame> out = new ArrayList<>();
		if (copy.isEmpty()) {
			return out;
		}
		EncodedFrame newest = copy.lastEntry().getValue();
		long end = newest.timestampNanos();
		long start = end - (long) (seconds * 1e9);

		// drop frames with a different size than the newest one
		NavigableMap<Long, EncodedFrame> window = new java.util.TreeMap<>();
		for (EncodedFrame f : copy.tailMap(start, true).values()) {
			if (f.width() == newest.width() && f.height() == newest.height()) {
				window.put(f.timestampNanos(), f);
			}
		}
		if (window.isEmpty()) {
			return out;
		}

		long first = window.firstKey();
		long interval = 1_000_000_000L / fps;
		long count = (end - first) / interval + 1;
		for (long i = 0; i < count; i++) {
			long t = first + i * interval;
			Map.Entry<Long, EncodedFrame> e = window.floorEntry(t + interval / 2);
			if (e == null) {
				e = window.firstEntry();
			}
			out.add(e.getValue());
		}
		return out;
	}
}
