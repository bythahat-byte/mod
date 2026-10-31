# Boat Sniper

Fabric client mod for Minecraft 1.21.11, for the boat-cannon sniper (stacked boats fling you at
huge speed and your arrows inherit it). An arrow starts with your aim times the bow speed plus
your own movement, so at launch speed it flies far from the crosshair.

- **Shot marker**: a red crosshair showing where the arrow will really go. Put it on the target.
- **Speed HUD**: your speed and whether a shot now would go FORWARD or BACKWARD.
- **Forward release** (off by default): hold a fully drawn bow and it releases on a tick where
  you're moving forward, so shots never fly backwards.

Commands: `/sniper on|off`, `/sniper marker true|false`, `/sniper forwardrelease true|false`,
`/sniper minspeed <blocks per second>` (default 20; the HUD only shows above it).

Set Options → Accessibility → FOV Effects to 0% so the marker lines up at speed.
Forward release automates the shot; only use it where the server rules allow.
