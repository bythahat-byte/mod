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

	/** @param audio 48 kHz 16-bit stereo PCM lined up with the first frame, or null for a silent clip */
	public static Result write(List<EncodedFrame> frames, int fps, Path folder, boolean wantMp4, String ffmpegPath, byte[] audio) throws IOException {
		if (frames.isEmpty()) {
			throw new IOException("No frames recorded yet");
		}
		List<byte[]> jpegs = new ArrayList<>(frames.size());
		for (EncodedFrame f : frames) {
			jpegs.add(f.jpeg());
		}
		EncodedFrame first = frames.get(0);
		return writeVideo("clip", folder, wantMp4, ffmpegPath,
				avi -> MjpegAviWriter.write(avi, jpegs, first.width(), first.height(), fps),
				audio == null ? null : wav -> dev.clipmod.audio.WavWriter.write(wav, audio), 0);
	}

	/**
	 * Names a finished recording (already a playable .avi, see {@link dev.clipmod.capture.Recorder})
	 * and converts it to .mp4 if possible. The .avi is kept if anything goes wrong.
	 */
	public static Result saveRecording(Path finishedAvi, Path folder, boolean wantMp4, String ffmpegPath,
			Path finishedWav, double audioOffsetSeconds) throws IOException {
		return writeVideo("recording", folder, wantMp4, ffmpegPath,
				avi -> Files.move(finishedAvi, avi),
				finishedWav == null || !Files.exists(finishedWav) ? null : wav -> Files.move(finishedWav, wav), audioOffsetSeconds);
	}

	private interface FileWriter {
		void write(Path file) throws IOException;
	}

	/** @param audioOffsetSeconds how much later than the first frame the audio starts (negative = earlier) */
	private static Result writeVideo(String prefix, Path folder, boolean wantMp4, String ffmpegPath, FileWriter writer,
			FileWriter audioWriter, double audioOffsetSeconds) throws IOException {
		Files.createDirectories(folder);
		String base = uniqueBaseName(folder, prefix);
		Path avi = folder.resolve(base + ".avi");
		try {
			writer.write(avi);
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(avi); // don't leave a broken partial file taking up space
			throw e;
		}

		Path wav = null;
		String audioNote = null;
		if (audioWriter != null) {
			wav = folder.resolve(base + ".wav");
			try {
				audioWriter.write(wav);
			} catch (IOException | RuntimeException e) {
				dev.clipmod.ClipMod.LOGGER.warn("Couldn't save the clip's audio", e);
				Files.deleteIfExists(wav);
				wav = null;
				audioNote = "couldn't save the audio (" + e.getMessage() + ")";
			}
		}
		String audioBeside = wav != null ? " (the sound is in the .wav next to it)" : "";

		if (!wantMp4) {
			return new Result(avi, false, audioNote != null ? audioNote : wav != null ? "the sound is in the .wav next to it" : null);
		}
		String ffmpeg = findFfmpeg(ffmpegPath);
		if (ffmpeg == null) {
			return new Result(avi, false, "ffmpeg not found, saved as .avi" + audioBeside + " (install ffmpeg for .mp4)");
		}
		Path mp4 = folder.resolve(base + ".mp4");
		try {
			convert(ffmpeg, avi, wav, audioOffsetSeconds, mp4);
			Files.deleteIfExists(avi);
			if (wav != null) {
				Files.deleteIfExists(wav);
			}
			return new Result(mp4, true, audioNote);
		} catch (IOException e) {
			Files.deleteIfExists(mp4);
			String reason = e.getMessage() != null && e.getMessage().contains("No space left") ? "disk is full" : e.getMessage();
			return new Result(avi, false, "mp4 conversion failed (" + reason + "), kept the .avi" + audioBeside);
		}
	}

	private static String uniqueBaseName(Path folder, String prefix) {
		String base = prefix + "_" + LocalDateTime.now().format(NAME_FORMAT);
		String name = base;
		for (int i = 2; Files.exists(folder.resolve(name + ".avi")) || Files.exists(folder.resolve(name + ".mp4"))
				|| Files.exists(folder.resolve(name + ".wav")); i++) {
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

	private static void convert(String ffmpeg, Path in, Path wav, double audioOffsetSeconds, Path out) throws IOException {
		Path log = Files.createTempFile("clipmod-ffmpeg", ".log");
		try {
			List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", in.toString()));
			if (wav != null) {
				String offset = String.format(java.util.Locale.ROOT, "%.3f", Math.abs(audioOffsetSeconds));
				if (audioOffsetSeconds > 0.0005) {
					cmd.addAll(List.of("-itsoffset", offset)); // audio starts after the first frame
				} else if (audioOffsetSeconds < -0.0005) {
					cmd.addAll(List.of("-ss", offset)); // audio starts before it: skip the extra
				}
				cmd.addAll(List.of("-i", wav.toString(), "-map", "0:v:0", "-map", "1:a:0"));
			}
			cmd.addAll(List.of("-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p"));
			if (wav != null) {
				// pad/trim the sound to exactly the video's length
				cmd.addAll(List.of("-c:a", "aac", "-b:a", "192k", "-af", "apad", "-shortest"));
			}
			cmd.addAll(List.of("-movflags", "+faststart", out.toString()));
			Process p = new ProcessBuilder(cmd)
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
