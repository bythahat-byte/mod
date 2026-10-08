package dev.clipmod.video;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes a list of JPEG frames into a Motion-JPEG AVI file. Pure Java, no native
 * dependencies, and plays in VLC, Windows Media Player, mpv, ffmpeg, etc.
 */
public final class MjpegAviWriter {
	private static final int AVIF_HASINDEX = 0x10;
	private static final int AVIIF_KEYFRAME = 0x10;

	private MjpegAviWriter() {
	}

	/** The frames of a video, which may live in memory or in a file. */
	public interface FrameSource {
		int count();

		int size(int index);

		void writeTo(int index, OutputStream out) throws IOException;
	}

	public static void write(Path file, List<byte[]> jpegFrames, int width, int height, int fps) throws IOException {
		write(file, new FrameSource() {
			@Override
			public int count() {
				return jpegFrames.size();
			}

			@Override
			public int size(int index) {
				return jpegFrames.get(index).length;
			}

			@Override
			public void writeTo(int index, OutputStream out) throws IOException {
				out.write(jpegFrames.get(index));
			}
		}, width, height, fps);
	}

	public static void write(Path file, FrameSource frames, int width, int height, int fps) throws IOException {
		int frameCount = frames.count();
		long moviPayload = 4; // "movi"
		int maxFrameSize = 0;
		for (int i = 0; i < frameCount; i++) {
			int size = frames.size(i);
			moviPayload += 8 + padded(size);
			maxFrameSize = Math.max(maxFrameSize, size);
		}
		long idx1Payload = 16L * frameCount;

		long hdrlPayload = 4 + (8 + 56) + (8 + strlPayload());
		long riffPayload = 4 + (8 + hdrlPayload) + (8 + moviPayload) + (8 + idx1Payload);
		if (riffPayload > 0xFFFFFFFFL) {
			throw new IOException("Video is too large for an AVI file (>4 GB); lower the quality, fps or resolution");
		}

		try (OutputStream raw = Files.newOutputStream(file);
				LittleEndianOut out = new LittleEndianOut(new BufferedOutputStream(raw, 1 << 20))) {
			out.fourcc("RIFF");
			out.u32(riffPayload);
			out.fourcc("AVI ");

			// --- header list ---
			out.fourcc("LIST");
			out.u32(hdrlPayload);
			out.fourcc("hdrl");

			out.fourcc("avih");
			out.u32(56);
			out.u32(1_000_000L / fps); // dwMicroSecPerFrame
			out.u32((long) maxFrameSize * fps); // dwMaxBytesPerSec
			out.u32(0); // dwPaddingGranularity
			out.u32(AVIF_HASINDEX); // dwFlags
			out.u32(frameCount); // dwTotalFrames
			out.u32(0); // dwInitialFrames
			out.u32(1); // dwStreams
			out.u32(maxFrameSize); // dwSuggestedBufferSize
			out.u32(width);
			out.u32(height);
			out.u32(0);
			out.u32(0);
			out.u32(0);
			out.u32(0); // dwReserved[4]

			out.fourcc("LIST");
			out.u32(strlPayload());
			out.fourcc("strl");

			out.fourcc("strh");
			out.u32(56);
			out.fourcc("vids"); // fccType
			out.fourcc("MJPG"); // fccHandler
			out.u32(0); // dwFlags
			out.u16(0); // wPriority
			out.u16(0); // wLanguage
			out.u32(0); // dwInitialFrames
			out.u32(1); // dwScale
			out.u32(fps); // dwRate
			out.u32(0); // dwStart
			out.u32(frameCount); // dwLength
			out.u32(maxFrameSize); // dwSuggestedBufferSize
			out.u32(0xFFFFFFFFL); // dwQuality (-1 = default)
			out.u32(0); // dwSampleSize
			out.u16(0);
			out.u16(0);
			out.u16(width);
			out.u16(height); // rcFrame

			out.fourcc("strf");
			out.u32(40); // BITMAPINFOHEADER
			out.u32(40); // biSize
			out.u32(width);
			out.u32(height);
			out.u16(1); // biPlanes
			out.u16(24); // biBitCount
			out.fourcc("MJPG"); // biCompression
			out.u32((long) width * height * 3); // biSizeImage
			out.u32(0);
			out.u32(0);
			out.u32(0);
			out.u32(0);

			// --- frame data ---
			out.fourcc("LIST");
			out.u32(moviPayload);
			out.fourcc("movi");
			for (int i = 0; i < frameCount; i++) {
				int size = frames.size(i);
				out.fourcc("00dc");
				out.u32(size);
				frames.writeTo(i, out.out);
				if ((size & 1) != 0) {
					out.u8(0);
				}
			}

			// --- index (offsets are relative to the "movi" fourcc) ---
			out.fourcc("idx1");
			out.u32(idx1Payload);
			long offset = 4;
			for (int i = 0; i < frameCount; i++) {
				int size = frames.size(i);
				out.fourcc("00dc");
				out.u32(AVIIF_KEYFRAME);
				out.u32(offset);
				out.u32(size);
				offset += 8 + padded(size);
			}
		}
	}

	private static long strlPayload() {
		return 4 + (8 + 56) + (8 + 40);
	}

	private static long padded(int length) {
		return (length + 1L) & ~1L;
	}

	private static final class LittleEndianOut implements AutoCloseable {
		final OutputStream out;

		LittleEndianOut(OutputStream out) {
			this.out = out;
		}

		void u8(int v) throws IOException {
			out.write(v);
		}

		void u16(int v) throws IOException {
			out.write(v & 0xFF);
			out.write((v >>> 8) & 0xFF);
		}

		void u32(long v) throws IOException {
			out.write((int) (v & 0xFF));
			out.write((int) ((v >>> 8) & 0xFF));
			out.write((int) ((v >>> 16) & 0xFF));
			out.write((int) ((v >>> 24) & 0xFF));
		}

		void fourcc(String s) throws IOException {
			for (int i = 0; i < 4; i++) {
				out.write(s.charAt(i));
			}
		}

		@Override
		public void close() throws IOException {
			out.close();
		}
	}
}
