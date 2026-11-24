package dev.clipmod.audio;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes 48 kHz 16-bit stereo PCM to a .wav file, either all at once or streamed (for long recordings). */
public final class WavWriter implements Closeable {
	private final Path path;
	private final OutputStream out;
	private long dataBytes;
	private boolean closed;
	private boolean failed;

	public WavWriter(Path path) throws IOException {
		this.path = path;
		Files.createDirectories(path.getParent());
		this.out = new BufferedOutputStream(Files.newOutputStream(path), 1 << 20);
		out.write(header(0)); // sizes are filled in on close
	}

	public Path path() {
		return path;
	}

	/** Called from the audio thread; never throws, so a disk problem can't stop the game's sound. */
	public synchronized void append(byte[] pcm) {
		if (closed || failed) {
			return;
		}
		try {
			out.write(pcm);
			dataBytes += pcm.length;
		} catch (IOException e) {
			failed = true;
		}
	}

	@Override
	public synchronized void close() throws IOException {
		if (closed) {
			return;
		}
		closed = true;
		out.close();
		try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
			raf.seek(0);
			raf.write(header(dataBytes));
		}
	}

	public static void write(Path path, byte[] pcm) throws IOException {
		try (WavWriter w = new WavWriter(path)) {
			w.append(pcm);
		}
	}

	private static byte[] header(long dataBytes) {
		long data = Math.min(dataBytes, 0xFFFFFFFFL - 36);
		ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
		b.put(new byte[] {'R', 'I', 'F', 'F'}).putInt((int) (36 + data)).put(new byte[] {'W', 'A', 'V', 'E'});
		b.put(new byte[] {'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) 2)
				.putInt(AudioCapture.RATE).putInt(AudioCapture.RATE * AudioCapture.BYTES_PER_FRAME)
				.putShort((short) AudioCapture.BYTES_PER_FRAME).putShort((short) 16);
		b.put(new byte[] {'d', 'a', 't', 'a'}).putInt((int) data);
		return b.array();
	}
}
