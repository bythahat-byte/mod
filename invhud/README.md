# Inventory HUD

Fabric client mod for Minecraft 1.21.11. Shows your main inventory (the 27 slots above the
hotbar) on screen, plus armor and offhand, with item counts and durability bars.

| Setting | Command | Default |
| --- | --- | --- |
| Show / hide | `/invhud on`, `/invhud off` (or bind "Toggle Inventory HUD") | on |
| Corner | `/invhud position top-left\|top-right\|bottom-left\|bottom-right` | top-left |
| Size | `/invhud scale 0.25-3` | 0.75 |
| Background darkness | `/invhud background 0-100` | 40 |
| Armor + offhand | `/invhud armor true\|false` | shown |
| Hide while a menu is open | `/invhud hideinmenus true\|false` | yes |

`/invhud` shows current settings, `/invhud reset` restores defaults. Saved in `config/invhud.json`.
Hidden with F1 like the rest of the HUD.
