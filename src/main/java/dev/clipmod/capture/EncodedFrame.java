package dev.clipmod.capture;

/** One captured frame, already compressed to JPEG. */
public record EncodedFrame(long timestampNanos, int width, int height, byte[] jpeg) {
}
