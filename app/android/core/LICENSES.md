# Licences of what `:core` builds on

| What | Credit | Licence | Where | Source |
|---|---|---|---|---|
| Earcut | Mapbox | ISC License | `Earcut.kt` | port of [mapbox/earcut](https://github.com/mapbox/earcut) 3.2.4, © 2026 Mapbox; the notice is in the file header |
| Flatbush | Vladimir Agafonkin | ISC License | `PackedRTree.kt` | port of [mourner/flatbush](https://github.com/mourner/flatbush) 4.6.2, © 2022 Vladimir Agafonkin; the notice is in the file header |
| FlatQueue | Volodymyr Agafonkin | ISC License | `PackedRTree.kt` | port of [mourner/flatqueue](https://github.com/mourner/flatqueue) 3.1.0, © 2025 Volodymyr Agafonkin; the notice is in the file header |
| Fast Hilbert curve algorithm | threadlocalmutex.com | Public domain | `PackedRTree.kt` | the Hilbert index of the boxes, as flatbush takes it |
| KDBush | Vladimir Agafonkin | ISC License | `PointIndex.kt` | port of [mourner/kdbush](https://github.com/mourner/kdbush) 4.1.0, the nearest search after geokdbush, © 2026 Vladimir Agafonkin |
| Polylabel | Mapbox | ISC License | `Polylabel.kt` | after [mapbox/polylabel](https://github.com/mapbox/polylabel), © 2016 Mapbox |
| Open Location Code | Google Inc. | Apache License 2.0 | `PlusCode.kt` | the Open Location Code algorithm, [google/open-location-code](https://github.com/google/open-location-code), © 2014 Google Inc. |

Methods Atlas's own code implements from their publications, copying no one's code:

<!-- not on About: Atlas's own code after published methods, which carries nothing licensed -->

| Part | Source | Licence |
|---|---|---|
| `Sun.kt` | The NOAA Solar Calculator's equations (after Meeus) | US Government work, public domain |
| `Moon.kt` | Tables 47.A/47.B and formulas from J. Meeus, *Astronomical Algorithms* (2nd ed.) | Published method; our own code |
| `Utm.kt` | Karney's Krüger series (C. F. F. Karney, *Transverse Mercator with an accuracy of a few nanometres*, 2011) | Published method; our own code |
| `CoordinateParser.kt` (`OsmShortLink`) | openstreetmap.org's short link scheme | Published scheme; our own 64-bit implementation (openstreetmap-website is GPL-2.0, none of its code is copied) |

Test data in `src/test/resources`:

<!-- not on About: read by the tests only -->

| Folder | Source | Licence |
|---|---|---|
| `earcut/` | Earcut's test fixtures and `expected.json` | ISC (`earcut/LICENSE`) |
| `olc/` | Open Location Code `test_data/*.csv` | Apache-2.0 (`olc/LICENSE`) |

The Amsterdam extract the tests read from `data/ams/raw.json` is OpenStreetMap data, © OpenStreetMap contributors, ODbL.
