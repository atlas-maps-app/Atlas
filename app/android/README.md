# Atlas on Android

The app as a Kotlin and Jetpack Compose project. Everything runs from the command line on this Mac and on GitHub Actions; no Android Studio.

## Versions

Checked against the official release pages and Maven metadata on 13 October 2026; they are listed in `gradle/libs.versions.toml`.

- Android Gradle Plugin 9.4.0 with its built-in Kotlin, and Kotlin 2.4.21 for the Compose compiler, the JVM modules and serialization.
- Gradle 9.8.1 and JDK 17. Gradle comes with the wrapper (`./gradlew`), which fetches its own distribution.
- Compose BOM 2026.09.00, Navigation 3 1.2.0, DataStore, WorkManager, Media3 session, the Car App library.
- OkHttp 5.5.0, kotlinx-serialization, and androidx.sqlite's bundled SQLite 2.7.1. SQLite 3.50 comes with FTS5, R*Tree and JSON, and the same driver runs on the phone and in JVM tests.
- `compileSdk` and `targetSdk` are 37 (Android 17); `minSdk` is 29.
- SDK: `~/Library/Android/sdk` with platforms 35 to 37, build tools 35 and 36, the emulator and an Android 15 arm64 system image. `local.properties` points at it and stays out of git.

## Modules

| Module | What it is |
|---|---|
| `core` | Geometry and the shared model: LatLon, Mercator and tiles, polylines and snapping, Earcut, packed R-tree, shapes and route corridors, Plus Codes, UTM/MGRS, the coordinate parser, sun and moon, quantities in four unit systems; Place, Route, Step, Fix |
| `tiles` | The published packs' catalogue and downloads (region packs and place stores on GitHub, verified by SHA-256, resumed by range), and the protobuf and JSON readers the packer shares. Map tiles are retired: the map and the features read Atlas's own region packs |
| `render` | Atlas's map engine: `render/*.py` ported 1:1 to Kotlin (byte-identical SVG), the `.atlas` region packs, and the live glue (`render.live`: the map camera in the engine's terms, tolerance bands, the live moment from the forecast, the packs library) |
| `rendertiles` | Plain Kotlin: render tiles, stage P of the vector engine. The port's own loaded scene written as format-3 render packs (columnar layers in zstd chunks, keep codes, the renders' derived values), the packer's `render-tiles` command, and a reference reader in its test fixtures |
| `anchor` | Plain Kotlin: the SVG model and parser the engine's renders are read into (`app.atlas.mapview.svg`), and the GPU's anchors, renders lifted into world space; shared by the phone and the packer |
| `mapview` | The engine on Android's Canvas: its SVG (parsed by `anchor`) drawn exactly (`svg`), exact frames off the main thread warped under gestures, gestures, markers, routes, location puck |
| `mapcore` | Android library: the map engine's core (`ENGINE.md` at the repo's root), C++23 in `cpp/` that CMake builds with NDK r30 into `libatlasmap.so` (arm64-v8a, x86_64) and on this Mac and Linux for its tests (doctest on lavapipe under the validation layer, sanitizers, fuzz targets, clang-tidy); Slang shaders compiled to SPIR-V and checked for exactness at build time; the toolchain pinned in `tools.properties`. Its Kotlin façade (`app.atlas.mapcore`): `MapCore`, the only way in, with its start options, the camera buffer and frame source the core asks at each vsync, VK bench's frame records and how the app's last processes ended for the crash guard. `rules/` holds the rule table, every feature the renders draw on record |
| `mapcorecheck` | Tests only, on the JVM: the core's host build (`libatlasmap_host`, built by its `coreHost` task) loaded through JNI and held to the Kotlin side. The façade under threads racing its close; pymath and its random streams bit for bit against the port's, and the numerics check line; the light and the theme block against the port's, and the layouts Kotlin writes for the core (the theme block, the camera state) against the core's own; the tile reader's canonical dump of every record of the bundled render packs equal to the writer's, rings at every ladder tolerance equal to `simplify`, and damaged tiles refused or read alike by the core and the reference reader; and the likeness run, the core's candidates judged against `likeness`'s references |
| `likeness` | Tests only, on Robolectric's Skia: the likeness harness's oracle (`ENGINE.md`, Checks). The port's exact references of each case in `mapcorecheck`'s case files at its size and density, with each pixel's OSM owner and the label lists, the feature ladder, the stub's planted frames and a phone's VK check asset. `mapcorecheck` draws the candidates with the core on lavapipe and judges them by `thresholds.json`; CI caches the references and keeps the report |
| `places` | OSM knowledge: id-tagging-schema presets in 19 languages, NSI brands with logos, Maki/Temaki/Lucide symbols, facts, opening hours, categories, tag filters |
| `providers` | Online sources: Overpass, Mangrove, ratings and trends, chargers, EV models, Open-Meteo, Wikidata, Photon, Nominatim, OSM notes, OpenAddresses (PMTiles and vector tiles read by their bytes) |
| `search` | The offline place store (FTS5 + R*Tree), the query language with natural language, A near B, the Overpass compiler, region import |
| `route` | Routing: BRouter in process with a profile per mode, truck and boat limit checks, steps and hazards, segment downloads, the EV planner, the water router |
| `brouter` | BRouter's routing core, vendored as Java source (MIT, commit 2989810) |
| `guide` | Turn by turn and eyes free: the navigator, announcement timing per mode, phrasing (osrm-text-instructions), landmarks, callouts, earcons and haptics, Ears only, a simulator |
| `packer` | A command-line tool: our own OSM PBF reader, the place-store builder for the offline packs, and the `.atlas` region packs (format 1 for a box; format 2, a tile pyramid, for a whole Geofabrik extract) |
| `app` | The app: services, design kit, navigation, the screens in `ui/<package>`, the navigation service and the voice |

## Build and test

```
./gradlew test testDebugUnitTest            # every module's tests, on the JVM
./gradlew assembleDebug                     # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell am start -n app.atlas/.MainActivity
```

- **On this Mac, through the heavy-step queue.** A Claude session runs Gradle as `/Volumes/Download/atlas/bin/heavy.sh <session-name> "/Volumes/Download/work 2/tmp/gw.sh" --continue <tasks…>` (the Steward's rule, 10 November). heavy.sh runs one heavy step at a time across the Mac: it starts a step only with 5 GiB of room on the main disk (free space plus unused swap) and stops it under 3 GiB free there, or under 2 GiB on Download. gw.sh keeps Gradle's home, caches and temp files on the Download volume, with two workers and one build at a time.
- **Tests run on real data.** Most modules test against the repo's `data/ams/raw.json` (Overpass's answer for the canal ring) and the app's bundled region pack made from it (`app/src/main/assets/packs/amsterdam.atlas`).
- **Screenshots.** Robolectric's native graphics draws real Canvas and Compose pixels on the JVM. The map and screen tests leave PNGs under each module's `build/outputs/roborazzi`.
- **Robolectric runs SDK 34.** SDK 35 and later need JDK 21, which this Mac lacks.
- **Test temp files stay in the build folder.** Every test task's JVM keeps its temp files in `build/tmp/<task>/jvm` (its `java.io.tmpdir`: Java ignores `TMPDIR` on macOS), emptied before the task and removed after it (the root `build.gradle.kts`), so Robolectric's 200–400 MB a run stays off the main disk (10 November, the owner's yes). A test's own folders come from `TempFiles` and go when its JVM exits, not sooner: the real set-up in `ServicesTest` wires the process's map engine to its folder's pack, and the screen tests after it draw their map with that engine.
- **About & licences follows the licence files.** `app/src/main/kotlin/app/atlas/ui/mine/art/mine_licences.py` makes `res/raw/mine_licences.json` from the modules' `LICENSES.md` tables, and About lists each thing the app carries once, in a user's words: its name (a table's first column, or a Family column), its credit (a Credit column, or a Copyright column) and its licence's name (a Licence column). Where it is, its source and notes stay in the other columns, for whoever keeps the file. A `<!-- not on About: why -->` line just above a table keeps that table off the list: what only the tests read, what the credits card above the list already shows (the providers' tables), Atlas's own work, and the methods its own code implements. A thing two files list (Geist, Lucide, Earcut) is one line, in the first card that shows it, with each credit; a card shows its fonts (a table headed Font, or with a Family column) first. The JSON keeps a hash of each whole file: `MineStateTest` fails with "run mine_licences.py" while a file has changed since, and also while a line names a path, a file or a test fixture, holds notes where a licence's name belongs, or lists a thing twice. The app's test task takes the `LICENSES.md` files as inputs, so editing one runs the tests again (10 November: the list had fallen 52 lines behind, then read as notes to developers). `MineScreenshotTest` shoots the whole list, a screen at a time (`mine_about_2`, `_3`, …).
- **Online tests are opt-in.** Tests that need the internet run only with `-Datlas.online=true`.
- **Routing data.** Routing tests use BRouter's `E0_N50.rd5` from `../../cache/brouter/segments4/` when it is there.
- **No map in the APK.** The map is one pack per country: the phone takes each place's pack from its country's tiles on GitHub, or builds it from OpenStreetMap's data. `:app:generateFactsTiles` writes the APK's only tiles, the Westerkerk's living map (from the bundled pack) as a format-3 render pack in its `tiles/` folder, for VK facts' hash lines alone; no map lists it. `:mapcorecheck:bundledTiles` writes the pack tests' set (the Westerkerk and the canal ring, from `packs-renders`' `grachten.atlas`, fetched once into `cache/` and checked against its SHA-256, in the port's cut, with their index), and `:app:benchTiles` the canal ring CI's emulator draws at the Westerkerk home (`ci/smoke.sh` puts it in `/data/local/tmp/atlas`, which debug and bench builds list). All are built, never committed; `packer render-tiles --bundled` does the same by hand. Each carries `EngineVersion.ID` and `StyleVersion.ID`. `EngineVersion.ID` follows the engine's own outputs: when a port change alters them, `EngineVersionTest` fails with the new `OUTPUTS` block to paste into `render/live/EngineVersion.kt`.

## On GitHub

- `.github/workflows/android.yml` builds and tests every push. It keeps the APK, the reports and the screenshots as artifacts, then opens the APK on an Android 15 emulator.
- The emulator run follows `ci/smoke.sh`: one screenshot per screen (MainActivity's `atlas.screen` extra), plus frame timings and any crash.
- `release.yml` publishes a signed APK for a `v*` tag.
- `packs.yml` builds the offline packs (a region pack and a place store per region in `packs/regions.json`) and publishes them as release assets.

The emulator cannot run from a Claude session on this Mac, because the Hypervisor framework reports unsupported. Use GitHub's, run `emulator -avd atlas` from a terminal of your own, or connect a phone over USB.
