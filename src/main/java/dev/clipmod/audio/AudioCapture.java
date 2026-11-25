package dev.clipmod.audio;

import dev.clipmod.ClipMod;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTLoopback;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Game audio capture. Minecraft's OpenAL output is switched to a "loopback" device that mixes into
 * memory instead of the speakers. A pump thread pulls the mixed sound out in 10 ms chunks, plays it
 * through the speakers with Java Sound (so you still hear everything) and keeps it for clips.
 * If anything needed is missing, {@link #openLoopback} returns 0 and the game uses its normal device.
 */
public final class AudioCapture {
	public static final int RATE = 48000;
	public static final int BYTES_PER_FRAME = 4; // 16-bit stereo
	private static final int CHUNK_FRAMES = 480; // 10 ms
	/** Speaker buffer: about 60 ms, small enough that sound doesn't lag behind the game. */
	private static final int LINE_BUFFER_BYTES = RATE * BYTES_PER_FRAME * 60 / 1000;

	private static final AudioBuffer BUFFER = new AudioBuffer();
	private static volatile SourceDataLine line;
	private static volatile Thread pump;
	private static volatile boolean running;
	private static volatile String failure;

	private AudioCapture() {
	}

	public static AudioBuffer buffer() {
		return BUFFER;
	}

	/** True while game audio is being captured. */
	public static boolean isActive() {
		return running || sysRunning;
	}

	/** True when recording the whole Mac's sound from an input device (e.g. BlackHole) instead of just the game. */
	public static boolean isSystemActive() {
		return sysRunning;
	}

	// ---------------------------------------------------------------- system audio (all sounds, via a loopback input device)

	private static volatile TargetDataLineHolder sys;
	private static volatile boolean sysRunning;
	private static volatile String sysDeviceName;

	private record TargetDataLineHolder(javax.sound.sampled.TargetDataLine line, Thread thread) {
	}

	/** Names of the sound inputs Java can record from (BlackHole shows up here once installed). */
	public static java.util.List<String> inputDevices() {
		AudioFormat format = new AudioFormat(RATE, 16, 2, true, false);
		DataLine.Info info = new DataLine.Info(javax.sound.sampled.TargetDataLine.class, format);
		java.util.List<String> names = new java.util.ArrayList<>();
		for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
			try {
				if (AudioSystem.getMixer(mi).isLineSupported(info)) {
					names.add(mi.getName());
				}
			} catch (Throwable ignored) {
			}
		}
		return names;
	}

	public static String systemDeviceName() {
		return sysDeviceName;
	}

	/**
	 * Starts recording from an input device that carries all of the Mac's sound.
	 *
	 * @param wanted device name (or part of it); empty = the first one called BlackHole, Loopback or Soundflower
	 * @return null on success, otherwise why it couldn't start
	 */
	public static String startSystem(String wanted) {
		stopSystem();
		AudioFormat format = new AudioFormat(RATE, 16, 2, true, false);
		DataLine.Info info = new DataLine.Info(javax.sound.sampled.TargetDataLine.class, format);
		Mixer.Info chosen = null;
		String w = wanted == null ? "" : wanted.trim().toLowerCase(Locale.ROOT);
		for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
			String name = mi.getName().toLowerCase(Locale.ROOT);
			boolean match = w.isEmpty()
					? name.contains("blackhole") || name.contains("loopback") || name.contains("soundflower")
					: name.contains(w);
			try {
				if (match && AudioSystem.getMixer(mi).isLineSupported(info)) {
					chosen = mi;
					break;
				}
			} catch (Throwable ignored) {
			}
		}
		if (chosen == null) {
			failure = w.isEmpty() ? "no BlackHole input found (install BlackHole 2ch)" : "no input called \"" + wanted + "\"";
			return failure;
		}
		try {
			javax.sound.sampled.TargetDataLine line = (javax.sound.sampled.TargetDataLine) AudioSystem.getMixer(chosen).getLine(info);
			line.open(format, RATE * BYTES_PER_FRAME / 10);
			line.start();
			BUFFER.clear();
			sysRunning = true;
			sysDeviceName = chosen.getName();
			Thread t = new Thread(() -> systemPump(line), "ClipMod System Audio");
			t.setDaemon(true);
			t.setPriority(Thread.MAX_PRIORITY - 1);
			sys = new TargetDataLineHolder(line, t);
			t.start();
			failure = null;
			ClipMod.LOGGER.info("Clip Mod: recording system audio from {}", chosen.getName());
			return null;
		} catch (Throwable t) {
			ClipMod.LOGGER.warn("Clip Mod: couldn't record from {}", chosen.getName(), t);
			sysRunning = false;
			failure = "couldn't open " + chosen.getName() + ": " + t.getMessage();
			return failure;
		}
	}

	private static void systemPump(javax.sound.sampled.TargetDataLine line) {
		try {
			while (sysRunning) {
				byte[] chunk = new byte[CHUNK_FRAMES * BYTES_PER_FRAME];
				int n = line.read(chunk, 0, chunk.length); // blocks until 10 ms of sound has arrived
				if (n <= 0) {
					continue;
				}
				BUFFER.add(System.nanoTime(), n == chunk.length ? chunk : java.util.Arrays.copyOf(chunk, n));
			}
		} catch (Throwable t) {
			ClipMod.LOGGER.error("Clip Mod: system audio recording stopped", t);
			failure = "system audio stopped: " + t.getMessage();
		} finally {
			sysRunning = false;
		}
	}

	public static void stopSystem() {
		sysRunning = false;
		TargetDataLineHolder h = sys;
		sys = null;
		if (h != null) {
			h.line().stop();
			h.line().close();
			try {
				h.thread().join(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/** Why capture isn't running, or null. */
	public static String failure() {
		return failure;
	}

	/**
	 * Opens the speaker output and an OpenAL loopback device for the sound engine to use.
	 *
	 * @param deviceName the sound device chosen in Minecraft's options, or null for the default
	 * @return the loopback device, or 0 to fall back to the normal device
	 */
	public static long openLoopback(String deviceName) {
		if (!ClipMod.audioEnabled()) {
			failure = "turned off";
			return 0;
		}
		if (ClipMod.systemAudio()) {
			return 0; // recording all sounds from BlackHole instead; the game plays sound normally
		}
		failure = null;
		SourceDataLine l = openLine(deviceName);
		if (l == null) {
			return 0;
		}
		long device = 0;
		try {
			if (!ALC10.alcIsExtensionPresent(0L, "ALC_SOFT_loopback")) {
				failure = "OpenAL loopback not available";
				l.close();
				return 0;
			}
			device = SOFTLoopback.alcLoopbackOpenDeviceSOFT((ByteBuffer) null);
			if (device == 0 || !SOFTLoopback.alcIsRenderFormatSupportedSOFT(device, RATE, SOFTLoopback.ALC_STEREO_SOFT, SOFTLoopback.ALC_SHORT_SOFT)) {
				failure = "OpenAL loopback can't render 48 kHz stereo";
				if (device != 0) {
					ALC10.alcCloseDevice(device);
				}
				l.close();
				return 0;
			}
		} catch (Throwable t) {
			ClipMod.LOGGER.warn("Clip Mod: game audio capture unavailable", t);
			failure = "OpenAL loopback failed: " + t.getMessage();
			if (device != 0) {
				ALC10.alcCloseDevice(device);
			}
			l.close();
			return 0;
		}
		line = l;
		return device;
	}

	/** Context attributes the loopback device needs: the format it renders in. */
	public static int[] contextAttributes() {
		return new int[] {
				SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT, SOFTLoopback.ALC_STEREO_SOFT,
				SOFTLoopback.ALC_FORMAT_TYPE_SOFT, SOFTLoopback.ALC_SHORT_SOFT,
				ALC10.ALC_FREQUENCY, RATE};
	}

	private static SourceDataLine openLine(String deviceName) {
		AudioFormat format = new AudioFormat(RATE, 16, 2, true, false);
		DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
		SourceDataLine l = null;
		try {
			if (deviceName != null && !deviceName.isBlank()) {
				String wanted = deviceName.replaceFirst("^OpenAL Soft on ", "").toLowerCase(Locale.ROOT);
				for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
					String name = mi.getName().toLowerCase(Locale.ROOT);
					if ((name.equals(wanted) || name.contains(wanted) || wanted.contains(name)) && AudioSystem.getMixer(mi).isLineSupported(info)) {
						l = (SourceDataLine) AudioSystem.getMixer(mi).getLine(info);
						break;
					}
				}
			}
			if (l == null) {
				l = (SourceDataLine) AudioSystem.getLine(info);
			}
			l.open(format, LINE_BUFFER_BYTES);
			l.start();
			return l;
		} catch (Throwable t) {
			ClipMod.LOGGER.warn("Clip Mod: couldn't open the speakers for game audio capture", t);
			failure = "couldn't open the speakers: " + t.getMessage();
			if (l != null) {
				l.close();
			}
			return null;
		}
	}

	/** Starts pulling sound out of the loopback device (after the sound engine has set it up). */
	public static void start(long device) {
		Thread old = pump;
		if (old != null) { // shouldn't happen (close() stops it), but never run two pumps
			running = false;
			try {
				old.join(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		BUFFER.clear();
		running = true;
		Thread t = new Thread(() -> pump(device), "ClipMod Audio");
		t.setDaemon(true);
		t.setPriority(Thread.MAX_PRIORITY - 1);
		pump = t;
		t.start();
		ClipMod.LOGGER.info("Clip Mod: capturing game audio");
	}

	private static void pump(long device) {
		ByteBuffer native_ = MemoryUtil.memAlloc(CHUNK_FRAMES * BYTES_PER_FRAME);
		try {
			while (running) {
				SOFTLoopback.alcRenderSamplesSOFT(device, native_, CHUNK_FRAMES);
				byte[] chunk = new byte[CHUNK_FRAMES * BYTES_PER_FRAME];
				native_.get(0, chunk);
				long now = System.nanoTime();
				SourceDataLine l = line;
				if (l == null) {
					break;
				}
				l.write(chunk, 0, chunk.length); // blocks until the speakers need more, which keeps us in real time
				BUFFER.add(now, chunk);
			}
		} catch (Throwable t) {
			ClipMod.LOGGER.error("Clip Mod: game audio capture stopped", t);
			failure = "stopped: " + t.getMessage();
		} finally {
			MemoryUtil.memFree(native_);
			running = false;
		}
	}

	/** Stops the pump and closes the speakers (before the sound engine closes the device). */
	public static void stop() {
		running = false;
		Thread t = pump;
		pump = null;
		if (t != null && t != Thread.currentThread()) {
			try {
				t.join(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		SourceDataLine l = line;
		line = null;
		if (l != null) {
			l.stop();
			l.close();
		}
	}
}
