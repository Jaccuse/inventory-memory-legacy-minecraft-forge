# Slot Memory

A client-side Forge mod for Minecraft 1.8.9 that remembers where each item belongs in your inventory and puts it back there.

## Features

- Save your current inventory layout with one key.
- Sort your inventory back into that layout when you need it, or turn on automatic sorting.
- Assign or unassign single slots by hovering over an item.
- Each server and singleplayer world keeps its own layout.
- Items match by item ID, damage value and NBT. Durability and enchantments are ignored, so a worn or enchanted tool still matches.
- Client-side only. Servers don't need it installed.

> **Caution:** Sorting works by sending inventory clicks for you, and automatic mode does this without a keypress. Some servers forbid inventory automation and their anti-cheat may flag it. Check a server's rules before using it there, especially automatic mode.

## Requirements

- Minecraft 1.8.9 with Forge 11.15.1.2318 or a compatible 1.8.9 build
- JDK 8 to build from source

## Controls

All keys can be rebound under **Controls → Slot Memory**.

| Default key | Action |
|---|---|
| Numpad 7 | Save the current inventory layout |
| Numpad 8 | Sort the inventory now |
| Numpad 6 | Turn automatic sorting on or off |
| Numpad 9 | Assign or unassign the hovered item's slot (inventory must be open) |

Layouts are saved in `.minecraft/config/slotmemory/`.

## Building

You need JDK 8. ForgeGradle 2.1 does not run on newer JDKs.

```sh
./gradlew setupDecompWorkspace
./gradlew build
```

The mod jar is written to `build/libs/`.

## Development history

This mod was originally created by Jack with GPT 5.5, without [Claude](https://claude.com/claude-code). Later, Claude was used to clean up and update the project, then publish it to GitHub.

## License

[MIT](LICENSE)
