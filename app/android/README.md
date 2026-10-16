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
| `tiles` | Vector tiles: our own MVT decoder, PMTiles, MBTiles, OpenFreeMap online with a cache, overzoom, offline regions, the packs catalog |
| `render` | Atlas's map engine: `render/*.py` ported 1:1 to Kotlin (byte-identical SVG), the `.atlas` region packs, and the live glue (`render.live`: the map camera in the engine's terms, tolerance bands, the live moment from the forecast, the packs library) |
| `anchor` | Plain Kotlin: the SVG model and parser the engine's renders are read into (`app.atlas.mapview.svg`), and the GPU's anchors, renders lifted into world space; shared by the phone and the packer |
| `mapview` | The engine on Android's Canvas: its SVG (parsed by `anchor`) drawn exactly (`svg`), exact frames off the main thread warped under gestures, gestures, markers, routes, location puck |
| `places` | OSM knowledge: id-tagging-schema presets in 19 languages, NSI brands with logos, Maki/Temaki/Lucide symbols, facts, opening hours, categories, tag filters |
| `providers` | Online sources: Overpass, Mangrove, ratings and trends, chargers, EV models, Open-Meteo, Wikidata, Photon, Nominatim, OSM notes |
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

- **Tests run on real data.** Most modules test against `testdata/omt` (real OpenFreeMap tiles around the Westerkerk) and the repo's `data/ams/raw.json`; the map against the app's bundled region pack (`app/src/main/assets/packs/amsterdam.atlas`).
- **Screenshots.** Robolectric's native graphics draws real Canvas and Compose pixels on the JVM. The map and screen tests leave PNGs under each module's `build/outputs/roborazzi`.
- **Robolectric runs SDK 34.** SDK 35 and later need JDK 21, which this Mac lacks.
- **Online tests are opt-in.** Tests that need the internet run only with `-Datlas.online=true`.
- **Routing data.** Routing tests use BRouter's `E0_N50.rd5` from `../../cache/brouter/segments4/` when it is there.
- **Bundled anchors.** `:app:generateBundledAnchors` renders the Westerkerk's GPU anchors from the bundled pack into `app/build/generated/anchors` (built, never committed; `packer anchors --bundled` does the same by hand). Each carries `EngineVersion.ID`, which follows the engine's own outputs: when a port change alters them, `EngineVersionTest` fails with the new `OUTPUTS` block to paste into `render/live/EngineVersion.kt`.

## On GitHub

- `.github/workflows/android.yml` builds and tests every push. It keeps the APK, the reports and the screenshots as artifacts, then opens the APK on an Android 15 emulator.
- The emulator run follows `ci/smoke.sh`: one screenshot per screen (MainActivity's `atlas.screen` extra), plus frame timings and any crash.
- `release.yml` publishes a signed APK for a `v*` tag.
- `packs.yml` builds the offline packs (Planetiler tiles and a place store per region in `packs/regions.json`) and publishes them as release assets.

The emulator cannot run from a Claude session on this Mac, because the Hypervisor framework reports unsupported. Use GitHub's, run `emulator -avd atlas` from a terminal of your own, or connect a phone over USB.
