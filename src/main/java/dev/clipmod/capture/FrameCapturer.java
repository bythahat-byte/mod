package dev.clipmod.capture;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

import java.nio.ByteBuffer;

/**
 * Reads the finished frame back from the GPU right before the buffers are swapped.
 * Uses pixel buffer objects and fences so the render thread never waits on the GPU:
 * a frame is requested now and copied out a frame or two later once it is ready.
 */
public final class FrameCapturer {
	private static final int SLOTS = 3;

	private final FrameEncoder encoder;
	private final Slot[] slots = new Slot[SLOTS];
	private int nextSlot;
	private long nextCaptureAt;

	public FrameCapturer(FrameEncoder encoder) {
		this.encoder = encoder;
		for (int i = 0; i < SLOTS; i++) {
			slots[i] = new Slot();
		}
	}

	/** Called on the render thread with the default framebuffer holding the completed frame. */
	public void onFrameEnd(int width, int height, int fps, int maxHeight, float quality) {
		collectFinished(maxHeight, quality);

		if (width <= 0 || height <= 0) {
			return; // minimized
		}
		long now = System.nanoTime();
		long interval = 1_000_000_000L / fps;
		if (now < nextCaptureAt) {
			return;
		}
		// stay on the fps grid, but don't try to "catch up" after a stall
		nextCaptureAt = now - nextCaptureAt > interval ? now + interval : nextCaptureAt + interval;

		Slot slot = slots[nextSlot];
		if (slot.fence != 0) {
			return; // all slots busy, GPU is far behind; skip this frame
		}
		nextSlot = (nextSlot + 1) % SLOTS;
		slot.request(width, height, now);
	}

	private void collectFinished(int maxHeight, float quality) {
		// collect in submission order so frames reach the encoder chronologically
		for (int i = 0; i < SLOTS; i++) {
			Slot slot = slots[(nextSlot + i) % SLOTS];
			if (slot.fence == 0) {
				continue;
			}
			int status = GL32.glClientWaitSync(slot.fence, 0, 0);
			if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) {
				if (status == GL32.GL_WAIT_FAILED) {
					slot.discard();
				}
				continue;
			}
			slot.readOut(encoder, maxHeight, quality);
		}
	}

	/** Releases GL objects and pending frames (e.g. when recording is turned off). */
	public void reset() {
		for (Slot slot : slots) {
			slot.discard();
			slot.deleteBuffer();
		}
		nextSlot = 0;
		nextCaptureAt = 0;
	}

	private static final class Slot {
		int pbo;
		int pboSize;
		long fence;
		int width;
		int height;
		long timestamp;

		void request(int width, int height, long timestamp) {
			int size = width * height * 4;
			int prevPack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
			int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
			int prevAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
			int prevRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
			int prevSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
			int prevSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
			try {
				if (pbo == 0) {
					pbo = GL15.glGenBuffers();
				}
				GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
				if (pboSize != size) {
					GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, size, GL15.GL_STREAM_READ);
					pboSize = size;
				}
				GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
				GL11.glReadBuffer(GL11.GL_BACK);
				GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
				GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
				GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
				GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
				GL11.glReadPixels(0, 0, width, height, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, 0L);
				fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
				this.width = width;
				this.height = height;
				this.timestamp = timestamp;
			} finally {
				GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, prevAlignment);
				GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, prevRowLength);
				GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, prevSkipRows);
				GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, prevSkipPixels);
				GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
				GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPack);
			}
		}

		void readOut(FrameEncoder encoder, int maxHeight, float quality) {
			GL32.glDeleteSync(fence);
			fence = 0;
			int size = width * height * 4;
			byte[] pixels = encoder.borrow(size);
			if (pixels == null) {
				return; // encoders are behind, drop this frame
			}
			int prevPack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
			boolean ok = false;
			try {
				GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
				ByteBuffer mapped = GL30.glMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0, size, GL30.GL_MAP_READ_BIT);
				if (mapped != null) {
					mapped.get(pixels, 0, size);
					GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
					ok = true;
				}
			} finally {
				GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPack);
			}
			if (ok) {
				encoder.submit(pixels, width, height, timestamp, maxHeight, quality);
			} else {
				encoder.giveBack(pixels);
			}
		}

		void discard() {
			if (fence != 0) {
				GL32.glDeleteSync(fence);
				fence = 0;
			}
		}

		void deleteBuffer() {
			if (pbo != 0) {
				GL15.glDeleteBuffers(pbo);
				pbo = 0;
				pboSize = 0;
			}
		}
	}
}
