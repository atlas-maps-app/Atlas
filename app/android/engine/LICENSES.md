# :engine — licences

The engine's code is Atlas's own (MIT, see the repository's LICENSE). It vendors no third-party code; the triangulation
(Earcut, ISC) and the packed R-tree (Flatbush, ISC) it uses live in `:core`, with their notices there.

Data shipped in `src/main/resources/app/atlas/engine/traditions/`:

- `nl_amsterdam.json`, `fr_paris.json`, `it_tuscan.json`, `no_timber.json`, `gb_terrace.json`, `catalogue.json`: the
  building-tradition profiles of the Python prototype (`render/traditions/`), Atlas's own work (MIT). The engine's copy
  of `nl_amsterdam.json` warms the roof tiles to the Main board's terracotta and darkens flat bitumen roofs; its
  `atlas_note` field says so.

Test fixtures (not shipped):

- `app/android/testdata/omt/`: OpenMapTiles-schema vector tiles from OpenFreeMap. Map data © OpenStreetMap
  contributors, ODbL 1.0; schema © OpenMapTiles, CC-BY 4.0.
- `data/ams/raw.json` (repository root): an Overpass extract of OpenStreetMap, ODbL 1.0, read by the preview tests for
  real trees.
