## Functionality

- **Recording** (`XaeroMapWriterMixin`, `VisitedChunks`): records chunks that Xaero's World Map writes on its 
  map, per server and dimension (only dimensions named overworld, Nether and End are supported), marked "changed"
  if its map data was updated. Saved once per minute and on game exit.
- **Exporting** (`/visited export [overworld|the_nether|the_end] [anyway|without-packs]`, `XaeroExport`):
  runs Xaero's export for each dimension with records, in a few compact areas (`ExportAreas`). Trims the export by
  blacking out chunks not in records (`TileMasker`), and gathers the images in one folder. Refuses while in
  cave mode. Offers to turn resource packs off for the export, since they may change Xaero's colours.
- **Mark as uploaded** (`/visited uploaded <n>`, a button on the export message): forgets the chunks that
  export contained, except ones Xaero has changed since. Works after a restart (`exports.txt`).
  Same thing as `/visited clear`.
- **Forgetting old areas**: if the player was offline for more than an hour (`FORGET_AFTER_MS`), the record is
  forgotten on next login, but can still be restored with [Restore] for a day. Otherwise, a reminder offers
  [Export it now] and [Start over] (`/visited clear all`). Restores are meant to have more friction, to
  discourage users from exporting old map data they don't remember. It is kind of convoluted though, open
  to suggestions on how this should be designed
- **Map overlay** (`VisitedHighlighter`, `/visited show on|off`): green fill on new or changed areas, an
  outline around everything the next export keeps.

## Files the mod writes

Under `<game dir>/aomc_visited/`:

- `server/<address>/<namespace>/<dimension>.txt`: a record, one chunk `x,z` per line, `x,z,c` if changed.
- `server/<address>/exports.txt`: exports records that aren't marked as uploaded.
- `server/<address>/forgotten/`: records over 1 hour old, must be manually restored.
- `settings.properties`: whether the overlay is shown in Xaero.

## Building

- Minecraft 26.3, Fabric, Java 25 (set `JAVA_HOME` if Gradle picks an older one).
- `./gradlew build` makes `build/libs/aomc-visited-<version>.jar`. Gradle fetches Xaero's World Map
  from Modrinth's Maven repository (`xaero_worldmap_version` in `gradle.properties`) and compiles against it.
- **Bump `version` in `gradle.properties` for every build handed out for testing**, so the jar name and
  Fabric's mod list show which build is installed.
- `./gradlew runClient` needs a desktop session.

## Branches, CI and releases

- Work on a feature branch and open a pull request.
- CI (`.github/workflows/build.yml`) builds every pull request and every push to `main`, and attaches the
  jar to the run as an artifact.
- Releases: increment version in `gradle.properties`, push to `main`, CI tags the
  commit `v<version>` and publishes a GitHub release with the jar.

