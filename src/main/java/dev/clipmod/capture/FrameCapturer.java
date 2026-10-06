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
 * <p>
 * The frame is first scaled down (and flipped upright) on the GPU into a small offscreen
 * framebuffer, so only the clip-sized image crosses to the CPU, not the full window (which
 * on a Retina/4K screen is 5-8x more data). The readback goes into pixel buffer objects
 * guarded by fences, so the render thread never waits on the GPU: a frame is requested
 * now and copied out a frame or two later once it is ready.
 */
public final class FrameCapturer {
	private static final int SLOTS = 3;

	private final FrameEncoder encoder;
	private final Slot[] slots = new Slot[SLOTS];
	private int nextSlot;
	private long nextCaptureAt;

	// offscreen target the window is scaled into
	private int fbo;
	private int renderbuffer;
	private int fboWidth;
	private int fboHeight;

	public FrameCapturer(FrameEncoder encoder) {
		this.encoder = encoder;
		for (int i = 0; i < SLOTS; i++) {
			slots[i] = new Slot();
		}
	}

	/** Called on the render thread with the default framebuffer holding the completed frame. */
	public void onFrameEnd(int width, int height, int fps, int maxHeight, float quality) {
		collectFinished(quality);

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

		int[] out = FrameEncoder.outputSize(width, height, maxHeight);
		request(slot, width, height, out[0], out[1], now);
	}

	private void collectFinished(float quality) {
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
			slot.readOut(encoder, quality);
		}
	}

	private void request(Slot slot, int width, int height, int outWidth, int outHeight, long timestamp) {
		int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		int prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		int prevRenderbuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
		int prevPack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
		boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
		int prevAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
		int prevRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
		int prevSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
		int prevSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
		try {
			ensureFbo(outWidth, outHeight);
			if (scissor) {
				GL11.glDisable(GL11.GL_SCISSOR_TEST); // the scissor box would clip the blit
			}

			// scale + flip upright in one GPU blit: window (bottom-up) -> fbo rows top-down
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
			GL11.glReadBuffer(GL11.GL_BACK);
			GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
			GL30.glBlitFramebuffer(0, 0, width, height, 0, outHeight, outWidth, 0,
					GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);

			// async readback of the small image into this slot's PBO
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
			GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
			int size = outWidth * outHeight * 4;
			if (slot.pbo == 0) {
				slot.pbo = GL15.glGenBuffers();
			}
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.pbo);
			if (slot.pboSize != size) {
				GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, size, GL15.GL_STREAM_READ);
				slot.pboSize = size;
			}
			GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
			GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
			GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
			GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
			GL11.glReadPixels(0, 0, outWidth, outHeight, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, 0L);
			slot.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
			slot.width = outWidth;
			slot.height = outHeight;
			slot.timestamp = timestamp;
		} finally {
			GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, prevAlignment);
			GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, prevRowLength);
			GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, prevSkipRows);
			GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, prevSkipPixels);
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, prevPack);
			if (scissor) {
				GL11.glEnable(GL11.GL_SCISSOR_TEST);
			}
			GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, prevRenderbuffer);
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
			GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
		}
	}

	private void ensureFbo(int width, int height) {
		if (fbo != 0 && fboWidth == width && fboHeight == height) {
			return;
		}
		if (fbo == 0) {
			fbo = GL30.glGenFramebuffers();
			renderbuffer = GL30.glGenRenderbuffers();
		}
		GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, renderbuffer);
		GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, width, height);
		GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
		GL30.glFramebufferRenderbuffer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, renderbuffer);
		int status = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
		if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
			throw new IllegalStateException("Capture framebuffer incomplete: 0x" + Integer.toHexString(status));
		}
		fboWidth = width;
		fboHeight = height;
	}

	/** Releases GL objects and pending frames (e.g. when recording is turned off). */
	public void reset() {
		for (Slot slot : slots) {
			slot.discard();
			slot.deleteBuffer();
		}
		if (fbo != 0) {
			GL30.glDeleteFramebuffers(fbo);
			GL30.glDeleteRenderbuffers(renderbuffer);
			fbo = 0;
			renderbuffer = 0;
			fboWidth = 0;
			fboHeight = 0;
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

		void readOut(FrameEncoder encoder, float quality) {
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
				encoder.submit(pixels, width, height, timestamp, quality);
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
