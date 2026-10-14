# :packer — licences

The code of this module is Atlas's own (MIT, as the repository). Nothing third-party is vendored here: the OSM PBF
reader follows the format's description on the OpenStreetMap wiki (https://wiki.openstreetmap.org/wiki/PBF_Format)
and reads protobuf with `:tiles`' own `ProtoReader`.

What it builds on, through other modules: SQLite (public domain) through AndroidX `sqlite-bundled` (Apache-2.0), and
kotlinx-serialization-json (Apache-2.0) for the manifest.

Tools the pack workflow (`.github/workflows/packs.yml`) downloads on GitHub's runners, not kept here:

- **Planetiler** 0.10.2 (Apache-2.0, https://github.com/onthegomap/planetiler), pinned by version and SHA-256, with its
  **OpenMapTiles** profile: the schema is © OpenMapTiles, CC-BY 4.0 (https://openmaptiles.org), so maps drawn from
  the tiles credit OpenMapTiles.
- Planetiler's sources: **OSM water polygons** (osmdata.openstreetmap.de, ODbL), **Natural Earth** (public domain).

Data:

- `src/test/resources/monaco-2026-10-13.osm.pbf` is Geofabrik's Monaco extract of 13 October 2026
  (https://download.geofabrik.de/europe/monaco.html): © OpenStreetMap contributors, under the Open Database Licence
  (ODbL 1.0).
- The packs it makes hold **OpenStreetMap** data from Geofabrik's extracts: © OpenStreetMap contributors, ODbL 1.0.
  A place store (`*.places.db.gz`) is a derived database and is published under the ODbL; the tiles (`*.pmtiles`) are
  a produced work. Each release's notes carry this attribution.
