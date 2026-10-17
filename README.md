# AOMC Visited

A Fabric mod for the About Oliver Minecraft server. It remembers where you've been and exports just that
ground from Xaero's World Map, ready to upload to the community webmap at https://map.diorite.xyz.

Everything else in the export is blacked out, and the webmap ignores black. So you only upload what you
saw recently, never old map data that might overwrite someone else's newer upload.

## Install

Put the jar in your `mods` folder, next to **Fabric API** and **Xaero's World Map**, for Minecraft 26.3.
It runs on your computer only and doesn't send anything anywhere.

## Use

1. Play. The mod quietly records every chunk Xaero's World Map maps in the overworld, Nether and End.
   Open the world map to see it: green outline around what the next export keeps, green fill on ground
   that's new or changed.
2. When you're done, type **`/visited export`**. It exports every dimension you've been in (the game may
   freeze for a few seconds) and opens the folder. To export just one, add `overworld`, `the_nether` or
   `the_end`.
3. Upload each dimension's images to its map on the webmap.
4. Click **[Mark as uploaded]** in chat, so your next export only has ground you mapped since. This works
   after restarting the game too.

Turn Xaero's cave mode off before exporting. If you have a resource pack on, the mod offers to turn it off
for the export and back on afterwards, since packs change the map's colours.

## If you leave before uploading

Your record is kept, so you can carry on where you left off and export later. Only time offline counts:
if you're away for more than an hour, the record is forgotten when you join, since that ground may be out
of date by then. You can still bring it back with [Restore] in chat until a day after you left. If the
game crashes, your record is kept for up to a day.

## Commands

| Command | What it does |
|---|---|
| `/visited` | How many chunks are recorded in each dimension |
| `/visited export [overworld\|the_nether\|the_end]` | Export what you've mapped, cleaned and ready to upload |
| `/visited uploaded` | Mark the latest export as uploaded (same as the button) |
| `/visited show on\|off` | Show or hide the green overlay on Xaero's map |
| `/visited clear` | Forget what's recorded in your current dimension (asks to confirm) |
| `/visited clear all` | Forget everything recorded on this server (asks to confirm) |
| `/visited restore` | Bring back ground that was forgotten after an hour away (within a day) |

## Building

`./gradlew build` with Java 25.
