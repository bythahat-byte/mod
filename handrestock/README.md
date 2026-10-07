# Hand Restock

Fabric client mod for Minecraft 1.21.11. When the stack in your hand runs out, the next
matching stack from your inventory is moved into that hotbar slot automatically.

Works with items that leave something behind when used up:

| Used up | Leaves | Restocks with |
| --- | --- | --- |
| Milk bucket (drink) | Bucket | Next milk bucket |
| Water / lava / powder snow bucket (place) | Bucket | Next bucket of the same kind |
| Potion, honey bottle | Glass bottle | Next potion of the **same type** |
| Mushroom / rabbit / beetroot / suspicious stew | Bowl | Next stew of the same kind |

The empty bucket, bottle or bowl goes to the slot the new item came from. It also refills
blocks, food, and tools that break (any tool of the same kind).

Bind "Toggle Hand Restock" in Options → Controls → Key Binds to turn it on and off.
Works in singleplayer and on servers (it uses normal inventory clicks). Main hand only.

Build: `../gradlew -p handrestock build` → `build/libs/handrestock-<version>.jar`.
