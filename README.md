# BlockShuffle

A Block Shuffle mini-game plugin for Minecraft Paper servers.

## Requirements

- Paper `26.1.2` (Minecraft 1.21.x)
- Java 21+

## Installation

Drop the jar into your server's `plugins/` folder and restart.

## Usage

Run `/blockshuffle` (requires `blockshuffle.admin` permission, default: op) to open the game menu.

| Menu Item | Mode |
|---|---|
| Grass Block | Standard BlockShuffle |
| Stone | Simple BlockShuffle (easy materials) |
| Netherrack | Nether BlockShuffle |
| Lime Wool | Colour BlockShuffle |
| Book | Custom BlockShuffle (user-defined list) |

Run `/blockshuffle stop` to end a game in progress.

## Configuration

Edit `plugins/BlockShuffle/settings.yml` after first run to customise material lists and messages.

## Changelog

### v0.3



### v0.2

- Added various GUI elememts.
- Added player's currently tasked block above the hotbar.
- Added indicator above hotbar when a player's block is found.
- Added scoreboard that tracks current players, how many points they have, how many blocks they have found, and whether or not they have found their current block.

### v0.1

## Changes from original (1.18 → 26.1.2)

- Bumped API dependency to `io.papermc.paper:paper-api:26.1.2`
- Updated `api-version` in `plugin.yml` to `26.1.2`
- Replaced deprecated `ChatColor` / `Bukkit.broadcastMessage()` with Paper's Adventure API (`Component`, `NamedTextColor`)
- Removed dependency on `org.apache.commons.lang.WordUtils` — replaced with plain Java string formatting
- Inventory title now uses `Component` (required for modern Paper)
- Fixed boss bar progress clamping to always stay in `[0.0, 1.0]`
- Fixed several `null` guard checks for offline players
- Gradle wrapper updated from 7.1.1 → 8.13; Java toolchain set to 21
- Repository switched from Spigot nexus to `repo.papermc.io`
