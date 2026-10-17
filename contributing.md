# Working on AOMC Visited

A client-side Fabric mod for the About Oliver Minecraft server (AOMC). It records areas mapped by 
Xaero's World Map, and exports just that area for the community webmap (https://map.diorite.xyz),
removing everything else. `README.md` is for players; this file is for working on the mod.

## How it works

- **Recording** (`XaeroMapWriterMixin`, `VisitedChunks`): every chunk Xaero's World Map writes, per server,
  dimension and Xaero layer, marked "changed" if its map data differed. Only the overworld, Nether and End
  are recorded (AOMC has a creative dimension). Saved every minute and on leaving, as text under
  `<game dir>/aomc_visited/server/<address>/`.
- **Exporting** (`/visited export [overworld|the_nether|the_end] [anyway|without-packs]`, `XaeroExport`):
  runs Xaero's own export for each dimension with recorded areas, switching Xaero's map to it, in a few
  compact areas (`ExportAreas`). Then blacks out every chunk not recorded (`TileMasker`) and gathers the
  images in one folder. Refuses in cave mode. While a resource pack is on, offers to turn packs off for
  the export (they may change Xaero's colours).
- **Mark as uploaded** (`/visited uploaded <n>`, a button on the export message): forgets the chunks that
  export contained, except ones Xaero has changed since. Exports not yet marked are saved
  (`exports.txt`), so this works after a restart.
- **Forgetting old areas**: keeping the record across sessions is for crashes and short breaks, not to
  encourage uploading old areas. On joining, if the player was away more than an hour
  (`FORGET_AFTER_MS`), the record is forgotten. Only time away counts, the same for every dimension: each
  save stamps every record file, and the newest stamp is when they were last here. Forgotten records move
  to `forgotten/` and can be restored with [Restore] on the "Forgot..." message (only there, with a hover
  warning) until a day after the player was last here. Otherwise the player gets a reminder that the
  record is still there, saying nothing needs doing, with [Export it now] and [Start over]
  (`/visited clear all`).
- **Crashes**: a `playing` marker exists while on the server. Found on joining, the game crashed, and
  records up to a day old are kept (crash loops are short but can pass the hour).
- **Map overlay** (`VisitedHighlighter`, `/visited show on|off`): green fill on new or changed areas, an
  outline around everything the next export keeps.
- Chat messages share one look (`Chat`): the mod's name first, one colour per kind of message, buttons
  centred on their own line, progress above the hotbar.

## Files the mod writes

Under `<game dir>/aomc_visited/`:

- `server/<address>/<namespace>/<dimension>.txt`: a record, one chunk `x,z` per line, `x,z,c` if changed.
  Cave layers are `<dimension>/cave_<layer>.txt`.
- `server/<address>/exports.txt`: exports not yet marked as uploaded, minus chunks changed since.
- `server/<address>/forgotten/`: records forgotten on joining, same layout; their file time is when the
  player was last here.
- `server/<address>/playing`: the crash marker.
- `settings.properties`: whether the overlay is shown.

## Building

- `./gradlew build` makes `build/libs/aomc-visited-<version>.jar`. It compiles against Xaero's World Map
  without bundling it: put the jar at `~/xaeroworldmap-fabric-26.3-1.47.0.jar`, or pass
  `-Pxaero_worldmap_jar=<path>`.
- **Bump `version` in `gradle.properties` for every build handed out for testing**, so the jar name and
  Fabric's mod list show which build is installed.
- `./gradlew runClient` needs a desktop session.

## Branches, CI and releases

- Work on a feature branch and open a pull request; `main` only gets changes that were tested in game.
  Merging needs the Build check to pass.
- CI (`.github/workflows/build.yml`) builds every pull request and every push to `main`, and attaches the
  jar to the run as an artifact. It downloads Xaero's World Map from Modrinth, pinned by checksum: when
  `xaero_worldmap_version` changes, update the URL and checksum there too.
- To release, bump `version` in `gradle.properties`, merge, then tag that commit on `main` and push the
  tag (`git tag v<version>`, `git push origin v<version>`). CI checks the tag matches `version`, builds,
  and publishes a GitHub release with the jar.
