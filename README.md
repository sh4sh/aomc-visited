# AOMC Visited

A Fabric mod for the About Oliver Minecraft server. This mod records where you've been and 
automatically prepares Xaero exports that you can upload to the community webmap at https://map.diorite.xyz

Requires Xaero World Map. Built and tested on Xaero World Map v1.47.1, but it may work on other versions!

## How to use

1. Open the world map to see areas the mod has recorded: green outline around areas the export will include,
   green fill on areas that are new or changed.
2. When you're done, type **`/visited export`**. It exports every dimension you've been in (the game may
   freeze for a few seconds) and opens the exports folder.
3. Upload each dimension's images to its corresponding map on the webmap.
4. Click **[Mark as uploaded]** in chat, so your next export only contains new areas. This works
   after restarting the game too.

Turn Xaero's cave mode off before exporting. If you have a resource pack on, the mod offers to turn it off
for the export and back on afterwards, since they can change the map's colours.

If you don't export before logging off, the mod keeps what you mapped, so you can continue with the last session.
If you come back after more than six hours, it reminds you that the map data may be out of date, and you
can choose to continue with the last session or clear the data to start a new session.

## Commands

| Command | What it does |
|---|---|
| `/visited` | How many chunks are recorded in each dimension |
| `/visited export [overworld\|the_nether\|the_end]` | Export what you've mapped, cleaned and ready to upload |
| `/visited uploaded` | Mark the latest export as uploaded (same as the button) |
| `/visited on\|off` | Turn the mod on or off: when off, nothing is recorded or shown |
| `/visited overlay on\|off` | Show or hide the green overlay on Xaero's map |
| `/visited clear` | Forget what's recorded in your current dimension (asks to confirm) |
| `/visited clear all` | Forget everything recorded on this server (asks to confirm) |

## Building

`./gradlew build` with Java 25. See `contributing.md` for development notes.
