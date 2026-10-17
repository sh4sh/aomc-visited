# Working on AOMC Visited

A client-side Fabric mod for the About Oliver Minecraft server (AOMC). It records areas mapped by
Xaero's World Map, and exports just those areas for the community webmap (https://map.diorite.xyz),
removing everything else.

## How it works

- **Recording** (`XaeroMapWriterMixin`, `VisitedChunks`): every chunk Xaero's World Map writes on its normal
  (not cave-mode) map, per server and dimension (overworld, Nether and End only), marked "changed" if its
  map data differed. Saved every minute and on leaving.
- **Exporting** (`/visited export [overworld|the_nether|the_end] [anyway|without-packs]`, `XaeroExport`):
  runs Xaero's own export for each dimension in a few compact areas (`ExportAreas`), blacks out every
  chunk not recorded (`TileMasker`) and gathers the images in one folder. Refuses while Xaero's map is in
  cave mode. Offers to turn resource packs off for the export, since they may change Xaero's colours.
- **Mark as uploaded** (`/visited uploaded <n>`, a button on the export message): forgets the chunks that
  export contained, except ones Xaero has changed since. Works after a restart (`exports.txt`).
- **Forgetting old areas**: if the player was away more than an hour (`FORGET_AFTER_MS`), the record is
  forgotten on joining, and can be restored with [Restore] for a day. Otherwise a reminder offers
  [Export it now] and [Start over] (`/visited clear all`). Restores are meant to have more friction, to
  discourage users from exporting old map data they don't remember. It is kind of convoluted though, open
  to suggestions!
- **Map overlay** (`VisitedHighlighter`, `/visited show on|off`): green fill on new or changed areas, an
  outline around everything the next export keeps.

## Files the mod writes

Under `<game dir>/aomc_visited/`:

- `server/<address>/<namespace>/<dimension>.txt`: a record, one chunk `x,z` per line, `x,z,c` if changed.
- `server/<address>/exports.txt`: exports not yet marked as uploaded.
- `server/<address>/forgotten/`: records forgotten on joining.
- `settings.properties`: whether the overlay is shown.

## Building

- Minecraft 26.3, Fabric, Java 25 (set `JAVA_HOME` if Gradle picks an older one). 26.x is unobfuscated:
  use Mojang class and method names.
- `./gradlew build` makes `build/libs/aomc-visited-<version>.jar`. Gradle fetches Xaero's World Map
  from Modrinth's Maven repository (`xaero_worldmap_version` in `gradle.properties`) and compiles against
  it without bundling it. Xaero's mods are All Rights Reserved: never commit or publish their jars.
- **Bump `version` in `gradle.properties` for every build handed out for testing**, so the jar name and
  Fabric's mod list show which build is installed.
- `./gradlew runClient` needs a desktop session.

## Branches, CI and releases

- Work on a feature branch and open a pull request; `main` only gets changes that were tested in game.
  Merge once the Build check passes.
- CI (`.github/workflows/build.yml`) builds every pull request and every push to `main`, and attaches the
  jar to the run as an artifact.
- Releases are automatic: when a push to `main` builds a `version` that has no release yet, CI tags the
  commit `v<version>` and publishes a GitHub release with the jar. So bumping `version` and merging it
  releases it.
