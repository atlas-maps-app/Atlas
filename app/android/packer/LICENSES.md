# :packer — licences

The code of this module is Atlas's own (MIT, as the repository). Nothing third-party is vendored here: the OSM PBF
reader follows the format's description on the OpenStreetMap wiki (https://wiki.openstreetmap.org/wiki/PBF_Format)
and reads protobuf with `:tiles`' own `ProtoReader`.

What it builds on, through other modules: SQLite (public domain) through AndroidX `sqlite-bundled` (Apache-2.0), and
kotlinx-serialization-json (Apache-2.0) for the manifest.

Data:

- `src/test/resources/monaco-2026-10-13.osm.pbf` is Geofabrik's Monaco extract of 13 October 2026
  (https://download.geofabrik.de/europe/monaco.html): © OpenStreetMap contributors, under the Open Database Licence
  (ODbL 1.0).
- The packs it makes hold **OpenStreetMap** data from Geofabrik's extracts: © OpenStreetMap contributors, ODbL 1.0.
  A place store (`*.places.db.gz`) is a derived database and a region pack (`*.atlas`) holds OpenStreetMap elements;
  both are published under the ODbL. Each release's notes carry this attribution.
