package dev.clipmod.video;

import dev.clipmod.capture.EncodedFrame;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Writes a clip to disk as .avi (always works) and optionally converts it to .mp4 with ffmpeg. */
public final class ClipWriter {
	private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");
	private static final ConcurrentHashMap<String, Boolean> FFMPEG_AVAILABLE = new ConcurrentHashMap<>();

	private ClipWriter() {
	}

	public record Result(Path file, boolean convertedToMp4, String note) {
	}

	public static Result write(List<EncodedFrame> frames, int fps, Path folder, boolean wantMp4, String ffmpegPath) throws IOException {
		if (frames.isEmpty()) {
			throw new IOException("No frames recorded yet");
		}
		Files.createDirectories(folder);
		String base = uniqueBaseName(folder);
		Path avi = folder.resolve(base + ".avi");

		List<byte[]> jpegs = new ArrayList<>(frames.size());
		for (EncodedFrame f : frames) {
			jpegs.add(f.jpeg());
		}
		EncodedFrame first = frames.get(0);
		MjpegAviWriter.write(avi, jpegs, first.width(), first.height(), fps);

		if (!wantMp4) {
			return new Result(avi, false, null);
		}
		String ffmpeg = resolveFfmpeg(ffmpegPath);
		if (!isFfmpegAvailable(ffmpeg)) {
			return new Result(avi, false, "ffmpeg not found, saved as .avi (install ffmpeg for .mp4)");
		}
		Path mp4 = folder.resolve(base + ".mp4");
		try {
			convert(ffmpeg, avi, mp4);
			Files.deleteIfExists(avi);
			return new Result(mp4, true, null);
		} catch (IOException e) {
			Files.deleteIfExists(mp4);
			return new Result(avi, false, "mp4 conversion failed (" + e.getMessage() + "), kept .avi");
		}
	}

	private static String uniqueBaseName(Path folder) {
		String base = "clip_" + LocalDateTime.now().format(NAME_FORMAT);
		String name = base;
		for (int i = 2; Files.exists(folder.resolve(name + ".avi")) || Files.exists(folder.resolve(name + ".mp4")); i++) {
			name = base + "_" + i;
		}
		return name;
	}

	/** Result of an earlier availability check, or null if it has not run yet. */
	public static Boolean cachedFfmpegAvailability(String ffmpeg) {
		return FFMPEG_AVAILABLE.get(ffmpeg);
	}

	public static String resolveFfmpeg(String ffmpegPath) {
		return ffmpegPath == null || ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath;
	}

	public static boolean isFfmpegAvailable(String ffmpeg) {
		return FFMPEG_AVAILABLE.computeIfAbsent(ffmpeg, exe -> {
			try {
				Process p = new ProcessBuilder(exe, "-version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
				if (!p.waitFor(10, TimeUnit.SECONDS)) {
					p.destroyForcibly();
					return false;
				}
				return p.exitValue() == 0;
			} catch (IOException e) {
				return false;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		});
	}

	private static void convert(String ffmpeg, Path in, Path out) throws IOException {
		Path log = Files.createTempFile("clipmod-ffmpeg", ".log");
		try {
			Process p = new ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
					"-i", in.toString(),
					"-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
					"-pix_fmt", "yuv420p", "-movflags", "+faststart",
					out.toString())
					.redirectErrorStream(true)
					.redirectOutput(log.toFile())
					.start();
			if (!p.waitFor(10, TimeUnit.MINUTES)) {
				p.destroyForcibly();
				throw new IOException("ffmpeg timed out");
			}
			if (p.exitValue() != 0) {
				String output = Files.readString(log).strip();
				throw new IOException("ffmpeg exit code " + p.exitValue() + (output.isEmpty() ? "" : ": " + output.lines().reduce((a, b) -> b).orElse("")));
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted");
		} finally {
			Files.deleteIfExists(log);
		}
	}
}
