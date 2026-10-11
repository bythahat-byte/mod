package dev.clipmod.capture;

import dev.clipmod.video.MjpegAviWriter;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A long recording, written straight into an AVI file as frames are encoded. Saving only
 * appends the index and fills in the header, so it needs no extra disk space, and the file
 * is a playable video even if a later step (like the mp4 conversion) fails.
 * <p>
 * Encoder threads can finish frames slightly out of order, so frames are held briefly and
 * written in timestamp order. To keep a constant frame rate, the index may point at the same
 * frame more than once instead of storing duplicates.
 */
public final class Recorder {
	/** Stop before the AVI hits its 4 GB limit. */
	private static final long MAX_BYTES = 3_800_000_000L;
	/** How long frames wait for earlier ones that are still being encoded. */
	private static final long REORDER_NANOS = 500_000_000L;
	private static final int AVIIF_KEYFRAME = 0x10;

	private final Object lock = new Object();
	private final Path file;
	private final OutputStream out;
	private final TreeMap<Long, EncodedFrame> pending = new TreeMap<>();
	private final int width;
	private final int height;
	private final int fps;
	private final long startedAt = System.nanoTime();

	private long[] timestamps = new long[1024];
	/** Offset of each frame chunk relative to the "movi" fourcc. */
	private long[] offsets = new long[1024];
	private int[] lengths = new int[1024];
	private int count;
	private int maxFrameSize;
	/** Bytes of frame chunks written after the header. */
	private long written;
	private long lastWritten = Long.MIN_VALUE;
	private volatile boolean full;
	private boolean closed;
	private boolean primed;
	private IOException error;

	public Recorder(Path file, int width, int height, int fps) throws IOException {
		this.file = file;
		this.width = width;
		this.height = height;
		this.fps = fps;
		Files.createDirectories(file.getParent());
		this.out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 20);
		// placeholder header, rewritten with the real sizes when the recording is finished
		out.write(MjpegAviWriter.header(width, height, fps, 0, 0, 4, 0));
	}

	public Path file() {
		return file;
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

	/** True once the recording hit the size limit or a write failed; it should be stopped. */
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
			byte[] jpeg = f.jpeg();
			long chunk = 8 + ((jpeg.length + 1L) & ~1L);
			if (written + chunk > MAX_BYTES) {
				full = true;
				pending.clear();
				return;
			}
			try {
				writeChunkHeader(jpeg.length);
				out.write(jpeg);
				if ((jpeg.length & 1) != 0) {
					out.write(0);
				}
			} catch (IOException ex) {
				// most likely the disk is full; what's written so far is still saved
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
			offsets[count] = 4 + written;
			lengths[count] = jpeg.length;
			count++;
			maxFrameSize = Math.max(maxFrameSize, jpeg.length);
			written += chunk;
			lastWritten = f.timestampNanos();
		}
	}

	private void writeChunkHeader(int size) throws IOException {
		out.write('0');
		out.write('0');
		out.write('d');
		out.write('c');
		out.write(size & 0xFF);
		out.write((size >>> 8) & 0xFF);
		out.write((size >>> 16) & 0xFF);
		out.write((size >>> 24) & 0xFF);
	}

	/** Why the recording stopped early, or null. */
	public IOException error() {
		return error;
	}

	/**
	 * Stops accepting frames and turns the file into a finished video: appends an index that
	 * plays the frames back at a constant frame rate, then fills in the header.
	 *
	 * @return the length of the video in seconds
	 */
	public double finish() throws IOException {
		synchronized (lock) {
			if (closed) {
				throw new IOException("Recording already finished");
			}
			flush(Long.MAX_VALUE);
			closed = true;
			try {
				out.close();
			} catch (IOException e) {
				// buffered data may be lost; trim to the frames we know made it
				if (error == null) {
					error = e;
				}
			}
			if (count == 0) {
				Files.deleteIfExists(file);
				throw new IOException("Nothing was recorded" + (error != null ? " (" + error.getMessage() + ")" : ""));
			}

			try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
				// drop frames that never fully reached the disk (e.g. disk full mid-write)
				long available = raf.length() - MjpegAviWriter.HEADER_SIZE;
				while (count > 0 && offsets[count - 1] - 4 + 8 + lengths[count - 1] > available) {
					count--;
				}
				if (count == 0) {
					throw new IOException("Nothing was recorded" + (error != null ? " (" + error.getMessage() + ")" : ""));
				}
				long moviPayload = offsets[count - 1] + 8 + ((lengths[count - 1] + 1L) & ~1L);

				long interval = 1_000_000_000L / fps;
				long first = timestamps[0];
				int outCount = (int) Math.min(Integer.MAX_VALUE / 16 - 1, (timestamps[count - 1] - first) / interval + 1);
				ByteBuffer index = ByteBuffer.allocate(8 + 16 * outCount).order(ByteOrder.LITTLE_ENDIAN);
				index.put((byte) 'i').put((byte) 'd').put((byte) 'x').put((byte) '1');
				index.putInt(16 * outCount);
				int j = 0;
				for (int i = 0; i < outCount; i++) {
					long t = first + i * interval + interval / 2;
					while (j + 1 < count && timestamps[j + 1] <= t) {
						j++;
					}
					index.put((byte) '0').put((byte) '0').put((byte) 'd').put((byte) 'c');
					index.putInt(AVIIF_KEYFRAME);
					index.putInt((int) offsets[j]);
					index.putInt(lengths[j]);
				}

				raf.setLength(MjpegAviWriter.HEADER_SIZE + moviPayload - 4);
				raf.seek(raf.length());
				raf.write(index.array());
				raf.seek(0);
				raf.write(MjpegAviWriter.header(width, height, fps, outCount, maxFrameSize, moviPayload, 16L * outCount));
				return outCount / (double) fps;
			}
		}
	}

	/** Stops writing without finishing the file (used if the game shuts down mid-recording). */
	public void abandon() {
		synchronized (lock) {
			closed = true;
			try {
				out.close();
			} catch (IOException ignored) {
			}
		}
	}
}
