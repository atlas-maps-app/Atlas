# :packer — licences

The code of this module is Atlas's own (MIT, as the repository). Nothing third-party is vendored here: the OSM PBF
reader follows the format's description on the OpenStreetMap wiki (https://wiki.openstreetmap.org/wiki/PBF_Format)
and reads protobuf with `:tiles`' own `ProtoReader`.

What it builds on, through other modules: SQLite (public domain) through AndroidX `sqlite-bundled` (Apache-2.0), and
kotlinx-serialization-json (Apache-2.0) for the manifest.

`packer world` runs **Planetiler** 0.7.0 (https://github.com/onthegomap/planetiler, Apache-2.0) in-process, with what
it brings: JTS (EPL-2.0 or EDL-1.0), Jackson, Guava, sqlite-jdbc, Log4j and the others its NOTICE.md lists, each under
its own licence; its GeoTools readers (LGPL-2.1) are left out. Planetiler runs only where the packer runs (the Mac, CI)
and ships in no app. Its shapefile reader (`world/Shapefile.kt`) is Atlas's own, from ESRI's and dBASE's published
layouts.

Data:

- `src/test/resources/monaco-2026-10-13.osm.pbf` is Geofabrik's Monaco extract of 13 October 2026
  (https://download.geofabrik.de/europe/monaco.html): © OpenStreetMap contributors, under the Open Database Licence
  (ODbL 1.0).
- `src/test/resources/overpass-liechtenstein-14-8628-5754.json.gz` is overpass.osm.ch's answer (Overpass API 0.7.62.4)
  of 28 October 2026 to the phone's window query for the L14 window 14/8628/5754 in Liechtenstein, gzipped:
  © OpenStreetMap contributors, under the Open Database Licence (ODbL 1.0).
- The world packs (`packer world`, `packer globe`) hold OpenStreetMap data from Geofabrik's extracts and
  osmdata.openstreetmap.de's land polygons (© OpenStreetMap contributors, ODbL 1.0: derived databases offered under the
  ODbL), and Natural Earth's layers (public domain, "Made with Natural Earth").
- The packs it makes hold **OpenStreetMap** data from Geofabrik's extracts: © OpenStreetMap contributors, ODbL 1.0.
  A place store (`*.places.db.gz`) is a derived database and a region pack (`*.atlas`) holds OpenStreetMap elements;
  both are published under the ODbL. Each release's notes carry this attribution.
