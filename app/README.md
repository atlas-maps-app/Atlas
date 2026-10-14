# Atlas, the app

Start here. The brief is in the [README](../README.md). The boards are the spec. They stay on the owner's Mac, out of the public repo: `canvas/project/` holds each board's HTML, CSS and SVG, which are the material the screens are built from; `cache/pictures/img/` has every screen as a picture; and the Studio has their stars and the owner's requests (see `WORKING.md`, also local). Atlas's own map engine, in Python, is `render/` (its README says how it draws from real data), and the data it draws from is `data/`.

Everything about the app is decided here, in its code and in this file. Nothing sits in between: no plan page, no procedure. A decision is written down the day it is made, under Decisions.

The code is `android/`: a Kotlin and Jetpack Compose project that builds from the command line and on GitHub (its README says how, which versions it stands on, and what each module is).

## The order

1. **The base: how the map, the routes and the voice get made on Android.** The engine's look drawn natively by our own vector renderer, a port of `render/`; which open-source offline router and voice; what else is built as our own.
2. **The app: Native Android
3. **Traditions, further.** The plan’s next places.

## Decisions

1. **Our own vector renderer, a port of `render/`.** The engine's look is drawn by our own code on the phone, not by an open-source renderer carrying its style.
2. **It draws on Android's Canvas, not OpenGL** (13 October). The engine is the same painter's-algorithm renderer as `render/`: it projects world meshes on the CPU and hands triangle batches to `Canvas.drawVertices`, which Skia draws on the GPU. The reason: Robolectric's native graphics draws real Canvas pixels on this Mac, so every frame the phone would draw can be pictured and tested here, while the emulator cannot boot from a session. The meshes are world-space, so a GPU backend can take them later unchanged.
3. **Modules** (13 October). `core` (geometry, the shared model), `tiles`, `engine`, `places`, `providers`, `search`, `route`, `guide` and `brouter` are plain Kotlin or Java, tested on the JVM against real data; `mapview` and `app` are Android. `android/README.md` has the map.
4. **The map data is OpenMapTiles vector tiles** (13 October). Online from OpenFreeMap (free, no key; the TileJSON is resolved at runtime because its tile URLs carry a release). Offline in MBTiles and PMTiles files. OpenFreeMap's terms are unclear about bulk downloads, so whole-region packs come from our own builds (decision 8).
5. **The router is BRouter, embedded** (13 October). MIT, pure Java, vendored as source in `android/brouter` (commit 2989810, v1.7.10 + 13): no artifact exists on Maven Central. Its routing data for any place on Earth comes from brouter.de as 5° tiles (Amsterdam is `E0_N50.rd5`, 79 MB), updated daily with small diffs; we host nothing. Its data has no truck dimensions and no street names: Atlas checks a truck or boat route against OSM's limits and routes again around what does not fit, and names streets from its own map data. Valhalla routes trucks better but needs tiles someone builds and hosts; GraphHopper no longer runs on Android.
6. **The guide and the voice are our own** (13 October). Turn by turn, the eyes-free callouts, earcons and haptics are a pure Kotlin `guide`, tested on simulated drives; phrasing builds on osrm-text-instructions (BSD-2). The voice is Android's TextToSpeech with offline voices preferred. Not Ferrostar (a Rust core, less room for mode-specific callouts) and no neural voice inside the app: sherpa-onnx's TTS includes eSpeak NG (GPL-3), which Atlas's MIT licence cannot take; a neural voice comes as a separate TTS engine app.
7. **Providers** (13 October). Open and keyless by default: Overpass, Mangrove reviews, Open-Meteo, Wikidata, Photon/Nominatim, OSM notes, Open Food Facts prices. Keyed and optional, the key typed in by the user: Foursquare, Open Charge Map, Tankerkönig. **Chargemap has no public API**: using its data needs a partner agreement, so Atlas links out to it and keeps a key slot. Yelp is left out: its terms forbid showing it beside other reviews.
8. **GitHub builds, tests and releases** (13 October). Every push builds and tests on GitHub Actions and opens the APK on an Android emulator for screenshots (`.github/workflows/android.yml`); a `v*` tag publishes a signed release (`release.yml`). Offline packs (map tiles by Planetiler, a full-tag place database per region) are built there too and published as release assets (`packs.yml`).

## Ideas

One line each, by tab, in [ideas.md](ideas.md). New ideas come in through the Studio's Inbox (Add an idea); they are moved here when they are taken up. The planned screens and the open to-dos the pages carried are there too, with the categories the boards cover.


- An idea is one plain line. What it is and how it is done is figured out when it is built. When it is built, the line is purged from ideas.md, not marked done.
- Before any workflow over many files or agents: the agent count and a token estimate
