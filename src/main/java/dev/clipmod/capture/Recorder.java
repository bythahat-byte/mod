package dev.clipmod.capture;

import dev.clipmod.video.MjpegAviWriter;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A long recording. Frames are streamed to a temporary file as they are encoded, so the
 * recording can be much longer than the in-memory replay buffer. Encoder threads can finish
 * frames slightly out of order, so frames are held briefly and written in timestamp order.
 */
public final class Recorder {
	/** Stop before the AVI hits its 4 GB limit. */
	private static final long MAX_BYTES = 3_800_000_000L;
	/** How long frames wait for earlier ones that are still being encoded. */
	private static final long REORDER_NANOS = 500_000_000L;

	private final Object lock = new Object();
	private final Path dataFile;
	private final OutputStream out;
	private final TreeMap<Long, EncodedFrame> pending = new TreeMap<>();
	private final int width;
	private final int height;
	private final int fps;
	private final long startedAt = System.nanoTime();

	private long[] timestamps = new long[1024];
	private long[] offsets = new long[1024];
	private int[] lengths = new int[1024];
	private int count;
	private long written;
	private long lastWritten = Long.MIN_VALUE;
	private volatile boolean full;
	private boolean closed;
	private boolean primed;
	private IOException error;

	public Recorder(Path dataFile, int width, int height, int fps) throws IOException {
		this.dataFile = dataFile;
		this.width = width;
		this.height = height;
		this.fps = fps;
		Files.createDirectories(dataFile.getParent());
		this.out = new BufferedOutputStream(Files.newOutputStream(dataFile), 1 << 20);
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public int fps() {
		return fps;
	}

	/** Wall-clock time since recording started (not counting the replay-buffer lead-in). */
	public double elapsedSeconds() {
		return (System.nanoTime() - startedAt) / 1e9;
	}

	public long bytesWritten() {
		return written;
	}

	/** True once the recording hit the size limit; it should be stopped. */
	public boolean isFull() {
		return full;
	}

	/**
	 * Adds the replay buffer's frames so the recording starts with what just happened.
	 * Live frames that arrive before this are held back, so none of the lead-in is lost.
	 */
	public void prime(List<EncodedFrame> frames) {
		synchronized (lock) {
			for (EncodedFrame f : frames) {
				if (f.width() == width && f.height() == height) {
					pending.put(f.timestampNanos(), f);
				}
			}
			primed = true;
			if (!pending.isEmpty()) {
				flush(pending.lastKey() - REORDER_NANOS);
			}
		}
	}

	/** Called by the encoder threads for every new frame. */
	public void accept(EncodedFrame frame) {
		if (frame.width() != width || frame.height() != height) {
			return;
		}
		synchronized (lock) {
			if (closed || full) {
				return;
			}
			pending.put(frame.timestampNanos(), frame);
			if (primed) {
				flush(frame.timestampNanos() - REORDER_NANOS);
			}
		}
	}

	private void flush(long upTo) {
		Map.Entry<Long, EncodedFrame> e;
		while ((e = pending.firstEntry()) != null && e.getKey() <= upTo) {
			pending.pollFirstEntry();
			EncodedFrame f = e.getValue();
			if (f.timestampNanos() <= lastWritten) {
				continue; // duplicate (frame was in the replay buffer and also passed to us live)
			}
			if (written + f.jpeg().length > MAX_BYTES) {
				full = true;
				pending.clear();
				return;
			}
			try {
				out.write(f.jpeg());
			} catch (IOException ex) {
				error = ex;
				full = true;
				pending.clear();
				return;
			}
			if (count == timestamps.length) {
				timestamps = Arrays.copyOf(timestamps, count * 2);
				offsets = Arrays.copyOf(offsets, count * 2);
				lengths = Arrays.copyOf(lengths, count * 2);
			}
			timestamps[count] = f.timestampNanos();
			offsets[count] = written;
			lengths[count] = f.jpeg().length;
			count++;
			written += f.jpeg().length;
			lastWritten = f.timestampNanos();
		}
	}

	/** Stops accepting frames and returns the finished recording, resampled to a constant frame rate. */
	public Finished finish() throws IOException {
		synchronized (lock) {
			if (!closed) {
				flush(Long.MAX_VALUE);
				closed = true;
				out.close();
			}
			if (error != null) {
				throw error;
			}
			if (count == 0) {
				throw new IOException("Nothing was recorded");
			}
			long interval = 1_000_000_000L / fps;
			long first = timestamps[0];
			long end = timestamps[count - 1];
			int outCount = (int) Math.min(Integer.MAX_VALUE - 8, (end - first) / interval + 1);
			int[] order = new int[outCount];
			int j = 0;
			for (int i = 0; i < outCount; i++) {
				long t = first + i * interval + interval / 2;
				while (j + 1 < count && timestamps[j + 1] <= t) {
					j++;
				}
				order[i] = j;
			}
			return new Finished(dataFile, offsets, lengths, order, width, height, fps);
		}
	}

	/** Deletes the temporary data file. */
	public void discard() {
		synchronized (lock) {
			closed = true;
			try {
				out.close();
			} catch (IOException ignored) {
			}
		}
		try {
			Files.deleteIfExists(dataFile);
		} catch (IOException ignored) {
		}
	}

	/** A finished recording backed by the temporary data file. */
	public static final class Finished implements MjpegAviWriter.FrameSource, AutoCloseable {
		private final Path dataFile;
		private final long[] offsets;
		private final int[] lengths;
		private final int[] order;
		public final int width;
		public final int height;
		public final int fps;
		private RandomAccessFile file;
		private byte[] scratch = new byte[256 * 1024];

		Finished(Path dataFile, long[] offsets, int[] lengths, int[] order, int width, int height, int fps) {
			this.dataFile = dataFile;
			this.offsets = offsets;
			this.lengths = lengths;
			this.order = order;
			this.width = width;
			this.height = height;
			this.fps = fps;
		}

		public double durationSeconds() {
			return order.length / (double) fps;
		}

		@Override
		public int count() {
			return order.length;
		}

		@Override
		public int size(int index) {
			return lengths[order[index]];
		}

		@Override
		public void writeTo(int index, OutputStream out) throws IOException {
			if (file == null) {
				file = new RandomAccessFile(dataFile.toFile(), "r");
			}
			int frame = order[index];
			int length = lengths[frame];
			if (scratch.length < length) {
				scratch = new byte[length];
			}
			file.seek(offsets[frame]);
			file.readFully(scratch, 0, length);
			out.write(scratch, 0, length);
		}

		/** Closes and deletes the temporary data file. */
		@Override
		public void close() throws IOException {
			if (file != null) {
				file.close();
				file = null;
			}
			Files.deleteIfExists(dataFile);
		}
	}
}
