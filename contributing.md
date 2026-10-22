## Functionality

- **Recording** (`XaeroMapWriterMixin`, `VisitedChunks`): records chunks that Xaero's World Map writes on its 
  map, per server and dimension (only dimensions named overworld, Nether and End are supported), marked "changed"
  if its map data was updated. Saved once per minute and when leaving the server.
- **Exporting** (`/visited export [overworld|the_nether|the_end] [without-packs|not-for-webmap]`, `XaeroExport`):
  runs Xaero's export for each dimension with records, in a few compact areas (`ExportAreas`). Trims the export by
  blacking out chunks not in records (`TileMasker`), and gathers the images in one folder. Refuses while in
  cave mode. Offers to turn resource packs off for the export, since they may change Xaero's colours.
- **Uploading** (`/visited upload [dimension]`, or `/visited upload <n>` from an export's [Upload] button,
  `WebmapUpload`): uploads each image, one after another, to `https://map.diorite.xyz/api/map/upload/<dim>`
  (the webmap calls the Nether `nether`), then runs `/api/map/process` and reads its streamed progress. Only
  if every image was accepted and processing finished without an error naming one of them is the export
  marked as uploaded. Nothing is sent unless the player asks. Exports made with resource packs on
  (`not-for-webmap`) are never offered for upload. A unique prefix on uploaded filenames could tell our
  images apart in the shared processing log, if errors ever get mixed up.
- **Mark as uploaded** (`/visited mark-uploaded <n>`, a button on the export message): forgets the chunks that
  export contained, except ones Xaero has changed since. Works after a restart (`exports.txt`).
  Similar to `/visited clear`, which removes all old exports/records (not just the marked upload) in a given
  dimension.
- **Old map data**: the record is kept until the player exports it or clears it. On joining after more than
  six hours offline (`OUT_OF_DATE_MS`), a reminder warns that the data may be out of date and offers
  [Export it now] and [Clear old session] (`/visited clear all`).
- **Map overlay** (`VisitedHighlighter`, `/visited overlay on|off`): green fill on new or changed areas, an
  outline around everything the next export keeps.

## Files the mod writes

Under `<game dir>/aomc_visited/`:

- `server/<address>/<namespace>/<dimension>.txt`: a record, one chunk `x,z` per line, `x,z,c` if changed.
- `server/<address>/exports.txt`: the latest export of each dimension, until it's marked as uploaded.
- `settings.properties`: whether the mod is on (`/visited on|off`) and whether the overlay is shown in Xaero.

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

