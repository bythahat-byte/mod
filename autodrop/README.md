# Auto Drop

Fabric client mod for Minecraft 1.21.11. Opens your inventory, drops the whole stack in each chosen
slot, and closes it again.

```
/autodrop add 36        drop slot 36 (bottom-right of the main inventory)
/autodrop remove 36
/autodrop now           drop now (or bind "Drop Chosen Slots" in Controls)
/autodrop every 60      also drop automatically every 60 seconds
/autodrop off           stop the automatic drops
/autodrop openinventory false   drop without showing the inventory screen
/autodrop               show settings
```

Slots: 1-9 hotbar (left to right), 10-18 top row, 19-27 middle row, 28-36 bottom row of the main
inventory. Saved in `config/autodrop.json`. It waits if a chest or other menu is open.
