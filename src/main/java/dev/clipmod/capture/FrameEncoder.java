package dev.clipmod.capture;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Turns raw BGRA pixels read back from the GPU into JPEG frames on background
 * threads, so the render thread only has to do a memcpy.
 */
public final class FrameEncoder {
	private static final int MAX_POOLED_BUFFERS = 6;

	private final ReplayBuffer buffer;
	private final ExecutorService executor;
	private final ArrayBlockingQueue<byte[]> pool = new ArrayBlockingQueue<>(MAX_POOLED_BUFFERS);
	private final AtomicInteger allocated = new AtomicInteger();
	private final ThreadLocal<Worker> workers = ThreadLocal.withInitial(Worker::new);
	private volatile int bufferSize = -1;
	private final AtomicInteger dropped = new AtomicInteger();

	public FrameEncoder(ReplayBuffer buffer) {
		this.buffer = buffer;
		// frames arrive already scaled, so JPEG encoding is cheap; keep cores free for the game
		int threads = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 4));
		AtomicInteger n = new AtomicInteger();
		this.executor = Executors.newFixedThreadPool(threads, r -> {
			Thread t = new Thread(r, "ClipMod Encoder #" + n.incrementAndGet());
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		});
	}

	/**
	 * Borrows a byte array big enough for a {@code size}-byte frame, or returns null if
	 * the encoders are falling behind (the frame should then be skipped).
	 */
	public byte[] borrow(int size) {
		if (size != bufferSize) {
			synchronized (this) {
				if (size != bufferSize) {
					pool.clear();
					allocated.set(0);
					bufferSize = size;
				}
			}
		}
		byte[] b = pool.poll();
		if (b != null) {
			return b;
		}
		if (allocated.incrementAndGet() <= MAX_POOLED_BUFFERS) {
			return new byte[size];
		}
		allocated.decrementAndGet();
		dropped.incrementAndGet();
		return null;
	}

	/** Returns a borrowed array that was not submitted. */
	public void giveBack(byte[] b) {
		if (b.length == bufferSize) {
			pool.offer(b);
		}
	}

	public int droppedFrames() {
		return dropped.get();
	}

	/**
	 * Queues a top-down BGRA frame (already scaled on the GPU) for encoding.
	 * Ownership of {@code pixels} passes to the encoder.
	 */
	public void submit(byte[] pixels, int width, int height, long timestamp, float quality) {
		executor.execute(() -> {
			try {
				Worker w = workers.get();
				byte[] jpeg = w.encode(pixels, width, height, quality);
				buffer.add(new EncodedFrame(timestamp, width, height, jpeg));
			} catch (Throwable t) {
				dev.clipmod.ClipMod.LOGGER.error("Failed to encode frame", t);
			} finally {
				giveBack(pixels);
			}
		});
	}

	/** Output size for a given input size: scaled to {@code maxHeight}, even dimensions (needed by H.264). */
	public static int[] outputSize(int width, int height, int maxHeight) {
		int outH = height;
		int outW = width;
		if (maxHeight > 0 && height > maxHeight) {
			outH = maxHeight;
			outW = (int) Math.round(width * (maxHeight / (double) height));
		}
		return new int[] {Math.max(2, outW & ~1), Math.max(2, outH & ~1)};
	}

	/** Per-thread reusable image and JPEG writer. */
	private static final class Worker {
		private BufferedImage image;
		private ImageWriter writer;
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(256 * 1024);

		byte[] encode(byte[] bgra, int width, int height, float quality) throws Exception {
			if (image == null || image.getWidth() != width || image.getHeight() != height) {
				image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			}
			int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
			// BGRA bytes read as little-endian ints are 0xAARRGGBB; TYPE_INT_RGB ignores alpha
			ByteBuffer.wrap(bgra, 0, width * height * 4).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels, 0, width * height);

			if (writer == null) {
				writer = ImageIO.getImageWritersByFormatName("jpeg").next();
			}
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);
			bytes.reset();
			try (ImageOutputStream ios = ImageIO.createImageOutputStream(bytes)) {
				writer.setOutput(ios);
				writer.write(null, new IIOImage(image, null, null), param);
			} finally {
				writer.reset();
			}
			return bytes.toByteArray();
		}
	}
}
