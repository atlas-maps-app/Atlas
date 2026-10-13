# Licences of what `:core` builds on

| Part | Source | Licence |
|---|---|---|
| `Earcut.kt` | Port of [mapbox/earcut](https://github.com/mapbox/earcut) 3.2.4, © 2026 Mapbox | ISC (notice in the file header) |
| `PackedRTree.kt` | Port of [mourner/flatbush](https://github.com/mourner/flatbush) 4.6.2, © 2022 Vladimir Agafonkin, and [mourner/flatqueue](https://github.com/mourner/flatqueue) 3.1.0, © 2025 Volodymyr Agafonkin; Hilbert curve by threadlocalmutex.com | ISC (notices in the file header); public domain |
| `PointIndex.kt` | Port of [mourner/kdbush](https://github.com/mourner/kdbush) 4.1.0, nearest search after geokdbush, © 2026 Vladimir Agafonkin | ISC |
| `Polylabel.kt` | After [mapbox/polylabel](https://github.com/mapbox/polylabel), © 2016 Mapbox | ISC |
| `PlusCode.kt` | The Open Location Code algorithm, [google/open-location-code](https://github.com/google/open-location-code), © 2014 Google Inc. | Apache-2.0 |
| `Sun.kt` | The NOAA Solar Calculator's equations (after Meeus) | US Government work, public domain |
| `Moon.kt` | Tables 47.A/47.B and formulas from J. Meeus, *Astronomical Algorithms* (2nd ed.) | Published method; our own code |
| `Utm.kt` | Karney's Krüger series (C. F. F. Karney, *Transverse Mercator with an accuracy of a few nanometres*, 2011) | Published method; our own code |
| `CoordinateParser.kt` (`OsmShortLink`) | openstreetmap.org's short link scheme | Published scheme; our own 64-bit implementation (openstreetmap-website is GPL-2.0, none of its code is copied) |

Test data in `src/test/resources`:

| Folder | Source | Licence |
|---|---|---|
| `earcut/` | Earcut's test fixtures and `expected.json` | ISC (`earcut/LICENSE`) |
| `olc/` | Open Location Code `test_data/*.csv` | Apache-2.0 (`olc/LICENSE`) |

The Amsterdam extract the tests read from `data/ams/raw.json` is OpenStreetMap data, © OpenStreetMap contributors, ODbL.
