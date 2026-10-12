# Better Nametags

Fabric client mod for Minecraft 1.21.11, with nametag options like Lunar Client's Nametag mod:

| Option | Command | Default |
| --- | --- | --- |
| Show your own nametag in third person (F5) | `/nametags own true\|false` | on |
| Text shadow | `/nametags shadow true\|false` | on |
| Background darkness (0 = no background) | `/nametags background 0-100` or `default` | Minecraft's setting |
| Size | `/nametags scale 0.25-4` | 1 (normal) |

`/nametags` shows the current settings and `/nametags reset` restores the defaults. Settings
are saved in `config/nametags.json`. You can also bind "Toggle Own Nametag" in Controls.

Client-side only: it changes how nametags look to you, and works on any server.
