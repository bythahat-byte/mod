# Clip Mod

Instant replay for Minecraft 1.21.11 (Fabric, client-side). The mod keeps the last 30 seconds of
gameplay in memory, and you can save it as a video at any time, like Shadowplay or the
OBS replay buffer, but built into the game.

## Usage

| Action | How |
| --- | --- |
| Save the last 30 seconds | Press **F8** or run `/clip` |
| Save the last N seconds | `/clip 10` |
| Pause/resume the replay buffer | `/clip off` / `/clip on` (or bind "Toggle Replay Buffer" in Controls) |
| See what's buffered and memory use | `/clip status` |
| Open the clips folder | `/clip folder`, then click the link |
| Change settings | `/clip set length <seconds>`, `/clip set fps <1-60>`, `/clip set resolution <height, 0 = native>`, `/clip set quality <0.1-1.0>`, `/clip set mp4 <true/false>` |
| List commands | `/clip help` |

Clips go to `.minecraft/clips/`. After saving, the chat message is a link you can click to open the clip.
You can rebind the keys in Options → Controls → Key Binds → Clip Mod.

### MP4 vs AVI

The mod writes clips itself as Motion-JPEG `.avi`, which plays in VLC, Windows Media Player,
mpv and most editors. If [ffmpeg](https://ffmpeg.org/download.html) is installed and on your
PATH, clips are converted to a small H.264 `.mp4` automatically, so they're ready to upload to
Discord or YouTube. You can also point the mod at ffmpeg with `ffmpegPath` in `config/clipmod.json`.

On Windows: `winget install ffmpeg`, then restart Minecraft.

## Settings (`config/clipmod.json`)

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Record the replay buffer in the background |
| `bufferSeconds` | `30` | Seconds kept in memory, and the default clip length |
| `fps` | `30` | Capture frame rate |
| `maxHeight` | `720` | Clips are scaled down to this height (0 = native resolution) |
| `quality` | `0.85` | JPEG quality of buffered frames |
| `convertToMp4` | `true` | Convert to mp4 with ffmpeg when available |
| `ffmpegPath` | `""` | ffmpeg executable (empty = search PATH) |
| `outputFolder` | `""` | Where clips are saved (empty = `.minecraft/clips`) |

Memory use: at 720p/30fps a 30-second buffer is about 50–100 MB. 1080p or 60 fps roughly
doubles that per step. Check it with `/clip status`.

## How it works

- Right before each buffer swap, the finished frame (world, HUD and menus) is copied from the
  GPU into a pixel buffer object. A fence makes the copy asynchronous, so the render thread
  never waits on the GPU.
- Background threads flip, scale and JPEG-compress each frame into a rolling in-memory buffer.
- When you save, the frames are resampled to a constant frame rate, written as an MJPEG AVI,
  and converted to MP4 with ffmpeg if it's installed.

**No audio:** clips are video only.

## Building

Requires Java 21.

```
./gradlew build
```

The mod jar is `build/libs/clipmod-<version>.jar`. Put it in your `mods` folder along with
[Fabric API](https://modrinth.com/mod/fabric-api). It targets Minecraft **1.21.11**. To target
another version, change the versions in `gradle.properties`.

GitHub Actions builds the jar on every push. Download it from the run's **Artifacts** section.
