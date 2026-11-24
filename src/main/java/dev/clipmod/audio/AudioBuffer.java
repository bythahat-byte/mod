package dev.clipmod.audio;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.function.Consumer;

/** The last few seconds of captured game audio, as timestamped 16-bit stereo PCM chunks. */
public final class AudioBuffer {
	private record Chunk(long nanos, byte[] data) {
	}

	private final ArrayDeque<Chunk> chunks = new ArrayDeque<>();
	private long maxAgeNanos = 33_000_000_000L;
	private Consumer<byte[]> stream;

	/** Keep a little more than the video buffer so a full-length clip always has sound. */
	public synchronized void setLengthSeconds(int seconds) {
		maxAgeNanos = (seconds + 3) * 1_000_000_000L;
	}

	public synchronized void clear() {
		chunks.clear();
	}

	synchronized void add(long nanos, byte[] data) {
		chunks.addLast(new Chunk(nanos, data));
		if (stream != null) {
			stream.accept(data);
		}
		while (!chunks.isEmpty() && nanos - chunks.peekFirst().nanos() > maxAgeNanos) {
			chunks.pollFirst();
		}
	}

	/** Exactly {@code durationNanos} of sound starting at {@code startNanos}; silence where nothing was captured. */
	public synchronized byte[] extract(long startNanos, long durationNanos) {
		byte[] out = new byte[frames(durationNanos) * AudioCapture.BYTES_PER_FRAME];
		copyFrom(startNanos, out);
		return out;
	}

	/**
	 * For long recordings: hands {@code sink} everything from {@code startNanos} up to now, then every
	 * new chunk as it arrives, until {@link #endStream()}. Done under one lock so nothing is lost or doubled.
	 */
	public synchronized void beginStream(long startNanos, Consumer<byte[]> sink) {
		if (!chunks.isEmpty()) {
			Chunk last = chunks.peekLast();
			long end = last.nanos() + nanos(last.data().length / AudioCapture.BYTES_PER_FRAME);
			if (end > startNanos) {
				byte[] lead = new byte[frames(end - startNanos) * AudioCapture.BYTES_PER_FRAME];
				copyFrom(startNanos, lead);
				sink.accept(lead);
			}
		}
		stream = sink;
	}

	public synchronized void endStream() {
		stream = null;
	}

	/** Chunks are laid end to end (the pump runs in real time), anchored at the first one that overlaps the start. */
	private void copyFrom(long startNanos, byte[] out) {
		Iterator<Chunk> it = chunks.iterator();
		Chunk c = null;
		while (it.hasNext()) {
			Chunk next = it.next();
			if (next.nanos() + nanos(next.data().length / AudioCapture.BYTES_PER_FRAME) > startNanos) {
				c = next;
				break;
			}
		}
		if (c == null) {
			return; // nothing that recent: all silence
		}
		int pos = 0;
		int skip = 0;
		if (c.nanos() > startNanos) {
			pos = Math.min(out.length, frames(c.nanos() - startNanos) * AudioCapture.BYTES_PER_FRAME); // silence before the first sound
		} else {
			skip = frames(startNanos - c.nanos()) * AudioCapture.BYTES_PER_FRAME;
		}
		while (c != null && pos < out.length) {
			int n = Math.min(c.data().length - skip, out.length - pos);
			if (n > 0) {
				System.arraycopy(c.data(), skip, out, pos, n);
				pos += n;
			}
			skip = 0;
			c = it.hasNext() ? it.next() : null;
		}
	}

	private static int frames(long nanos) {
		return (int) Math.max(0, nanos * AudioCapture.RATE / 1_000_000_000L);
	}

	private static long nanos(int frames) {
		return frames * 1_000_000_000L / AudioCapture.RATE;
	}
}
