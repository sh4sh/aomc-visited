# AOMC Visited

A Fabric mod for the About Oliver Minecraft server. This mod records where you've been and 
automatically prepares Xaero exports that you can upload to the community webmap at https://map.diorite.xyz

Requires Xaero World Map. Built and tested on Xaero World Map v1.47.1, but it may work on other versions!

## How to use

1. Open the world map to see areas the mod has recorded: green outline around areas the export will include,
   green fill on areas that are new or changed.
2. When you're done, type **`/visited upload`**. It exports every dimension you've been in (the game may
   freeze for a few seconds), uploads the images to the webmap, and **marks them as uploaded** once the
   webmap has processed them, so your next upload only contains new areas.

To check the images before uploading, use **`/visited export`** instead: it opens the exports folder, and
the chat message has an **[Upload]** button per dimension. You can also upload the images yourself on the
webmap, then click **[Mark as uploaded]**.

Turn Xaero's cave mode off before exporting. If you have a resource pack on, the mod offers to turn it off
for the export and back on afterwards, since they can change the map's colours.

If you don't export before logging off, the mod keeps what you mapped, so you can continue with the last session.
If you come back after more than six hours, it reminds you that the map data may be out of date, and you
can choose to continue with the last session or clear the data to start a new session.

## Commands

| Command | What it does |
|---|---|
| `/visited` | How many chunks are recorded in each dimension |
| `/visited upload [overworld\|the_nether\|the_end]` | Export, upload to the webmap, and mark as uploaded (asks to confirm) |
| `/visited export [overworld\|the_nether\|the_end]` | Export what you've mapped, cleaned and ready to upload |
| `/visited mark-uploaded` | Mark the latest export as uploaded (same as the button) |
| `/visited on\|off` | Turn the mod on or off: when off, nothing is recorded or shown |
| `/visited overlay on\|off` | Show or hide the green overlay on Xaero's map |
| `/visited clear` | Forget what's recorded in your current dimension (asks to confirm) |
| `/visited clear all` | Forget everything recorded on this server (asks to confirm) |

## Building

`./gradlew build` with Java 25. See `contributing.md` for development notes.
