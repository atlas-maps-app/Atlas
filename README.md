# Atlas

A maps app that pushes past what a maps app can be. Native Android, built on OpenStreetMap and open data, drawn by its own map engine, and offline from the start.

## The idea

Atlas goes past the best maps apps of today, built on OpenStreetMap and open data as a native Android app that is optimised and efficient, from the best open-source projects there are or from our own.

- **Minimalist and intuitive, yet powerful in every direction.** Symbols, shapes and real things carry the meaning, and text appears only where it must. Skeuomorphism is used where it adds value: a brass compass on the water, a battery gauge at the charger, a clock dial for opening hours, a menu card for restaurants.
- **Everything offline**, with open-source routing and voice directions good enough that you never look at the screen, whether you cycle, sail or drive a truck.
- **Places:** restaurant discovery with several rating providers and what is rising. Brands with their logos and every branch a tap away. Charging, and charging routes.
- **For OpenStreetMap power users,** complex searches made easy: any tags, a radius, one thing with another nearby.

Rethought first, compared later.

## Try it

The first alphas are on the [releases page](https://github.com/atlas-maps-app/Atlas/releases). Atlas needs Android 10 or newer. To install, download the APK on the phone, open it, and allow your browser to install apps. The alphas are debug builds signed with Atlas's release key, so each one installs over the one before.

**Alpha 1** has:
- the map: Atlas's own engine draws the real city in 3D, with the light of the day
- every gesture
- the Map home with weather, the categories and search

The rest of the screens are arriving alpha by alpha.

## How it is made

- **The map engine is our own.** It is a Kotlin port of the Python engine in `render/`. It builds the city as meshes from vector tiles:
  - buildings with the roofs and colours of their place's building tradition
  - canals, quays, streets and trees
  - the sun's light and shadows
  - labels placed without collisions
  It draws them with Android's Canvas, which Skia puts on the GPU.
- **Map data** is OpenStreetMap: OpenMapTiles vector tiles from OpenFreeMap online, and packs built here (Planetiler tiles and a full-tag place database per region) for offline use.
- **Routing** is BRouter, running inside the app on its worldwide offline data. Atlas adds:
  - its own checks for truck and boat limits
  - a water router
  - an EV charging planner
- **Guidance** is our own. Timing is per mode; instructions use landmarks and are phrased in many languages; earcons and haptic patterns tell left from right; an "ears only" mode guides you without the map.
- **Search** runs on the phone over names, tags and brands, typo-tolerant, in many languages. It has a query language for any tags and "A within 200 m of B".
- **Symbols** are Atlas's own, drawn for its screens. They are backed by the OpenStreetMap presets of the iD editor, the name-suggestion-index's brands, and Maki and Temaki.

The screens were designed first as boards, each a phone screen, fed by real open data and drawn on the engine. The boards are the app's specification, and the app takes their elements (icons, symbols, colours, shapes) directly from them. The boards themselves are the designer's working space and are not part of this repository.

## What is where

| Folder | What it holds |
|---|---|
| `app/` | The Android app. `app/README.md` holds the decisions and the order of work, `app/ideas.md` the ideas, and `app/android/` the Kotlin and Compose project (its README has the modules and how to build) |
| `render/`, `fetch/`, `data/` | The Python map engine the app's engine is ported from, the data fetchers, and what they fetched. `render/README.md` covers the engine, the pipelines and the data |
| `reviews/` | Mangrove reviews, a small Python package |
| `.github/workflows/` | Build and test on every push, an emulator run of every screen, releases, and the offline packs |

## Build

```
cd app/android
./gradlew test testDebugUnitTest     # every module's tests, on real data
./gradlew assembleDebug              # app/build/outputs/apk/debug/app-debug.apk
```

It needs JDK 17 and the Android SDK (platform 37); the Gradle wrapper fetches the rest. `app/android/README.md` has the details.

## Licence

The code and the notes are under the MIT licence (`LICENSE`): use them, change them, fork them.

Data keeps the terms of its sources:
- Map data © OpenStreetMap contributors, under the ODbL.
- Tiles come from OpenFreeMap in the OpenMapTiles schema.
- Routing data is BRouter's, from OpenStreetMap.
- The other open datasets carry their own open licences: PDOK, Open-Meteo, Wikidata, NDW, OVapi and Open Food Facts.
- Bundled libraries and data keep their own licences, listed in each module's `LICENSES.md`.
