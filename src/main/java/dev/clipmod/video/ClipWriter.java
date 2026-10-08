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
		List<byte[]> jpegs = new ArrayList<>(frames.size());
		for (EncodedFrame f : frames) {
			jpegs.add(f.jpeg());
		}
		EncodedFrame first = frames.get(0);
		return writeVideo("clip", folder, wantMp4, ffmpegPath,
				avi -> MjpegAviWriter.write(avi, jpegs, first.width(), first.height(), fps));
	}

	/** Saves a finished long recording and deletes its temporary data. */
	public static Result writeRecording(dev.clipmod.capture.Recorder.Finished recording, Path folder, boolean wantMp4, String ffmpegPath) throws IOException {
		try (recording) {
			return writeVideo("recording", folder, wantMp4, ffmpegPath,
					avi -> MjpegAviWriter.write(avi, recording, recording.width, recording.height, recording.fps));
		}
	}

	private interface AviWriter {
		void write(Path avi) throws IOException;
	}

	private static Result writeVideo(String prefix, Path folder, boolean wantMp4, String ffmpegPath, AviWriter writer) throws IOException {
		Files.createDirectories(folder);
		String base = uniqueBaseName(folder, prefix);
		Path avi = folder.resolve(base + ".avi");
		writer.write(avi);

		if (!wantMp4) {
			return new Result(avi, false, null);
		}
		String ffmpeg = findFfmpeg(ffmpegPath);
		if (ffmpeg == null) {
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

	private static String uniqueBaseName(Path folder, String prefix) {
		String base = prefix + "_" + LocalDateTime.now().format(NAME_FORMAT);
		String name = base;
		for (int i = 2; Files.exists(folder.resolve(name + ".avi")) || Files.exists(folder.resolve(name + ".mp4")); i++) {
			name = base + "_" + i;
		}
		return name;
	}

	/**
	 * Where to look when no ffmpeg path is configured. Apps started from the macOS Dock/Finder
	 * don't get the shell PATH, so Homebrew/MacPorts installs need to be checked explicitly.
	 */
	private static final List<String> FFMPEG_CANDIDATES = List.of(
			"ffmpeg",
			"/opt/homebrew/bin/ffmpeg", // Homebrew, Apple Silicon
			"/usr/local/bin/ffmpeg", // Homebrew, Intel Mac
			"/opt/local/bin/ffmpeg", // MacPorts
			"/usr/bin/ffmpeg");

	/** Result of an earlier {@link #findFfmpeg} call: the executable, "" if none was found, or null if not checked yet. */
	public static String cachedFfmpeg(String ffmpegPath) {
		if (ffmpegPath != null && !ffmpegPath.isBlank()) {
			Boolean ok = FFMPEG_AVAILABLE.get(ffmpegPath);
			return ok == null ? null : ok ? ffmpegPath : "";
		}
		for (String candidate : FFMPEG_CANDIDATES) {
			Boolean ok = FFMPEG_AVAILABLE.get(candidate);
			if (ok == null) {
				return null;
			}
			if (ok) {
				return candidate;
			}
		}
		return "";
	}

	/** The ffmpeg executable to use, or null if none works. Blocks while checking. */
	public static String findFfmpeg(String ffmpegPath) {
		if (ffmpegPath != null && !ffmpegPath.isBlank()) {
			return isFfmpegAvailable(ffmpegPath) ? ffmpegPath : null;
		}
		for (String candidate : FFMPEG_CANDIDATES) {
			if (isFfmpegAvailable(candidate)) {
				return candidate;
			}
		}
		return null;
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
			if (!p.waitFor(2, TimeUnit.HOURS)) {
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
