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

## Terrain: the elevation sources

The terrain packs (`packer heights`, ENGINE.md part 6) hold heights from the sources below, resampled onto the tiles'
lattice and rounded to their steps: adapted data, which each licence allows with the credit it asks. The registry
(`../packs/terrain-sources.json`) carries the same credits, with each source's hosts and the day its terms were read;
a further source is one entry there and one line here. About & licences shows this table (`mine_licences.py`), so the
credits are in the app before a phone reads a terrain pack. Each Copernicus line is its licence's Art. 6(b) notice for
adapted data and its Art. 6(c) sentence, whole. GDAL (MIT) warps the national grids in terrain.yml's job only, and is
in no app.

| Dataset | Credit | Licence | In the terrain packs of |
|---|---|---|---|
| Copernicus DEM GLO-30 | produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved. The organisations in charge of the Copernicus programme by law or by delegation do not incur any liability for any use of the Copernicus WorldDEM-30. | Licence for the use of the Copernicus WorldDEM-30 | the gaps of the national models at L12 (and, from step 4 of the terrain, the world's L11) |
| Copernicus DEM GLO-90 | produced using Copernicus WorldDEM-90 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved. The organisations in charge of the Copernicus programme by law or by delegation do not incur any liability for any use of the Copernicus WorldDEM-90. | Licence for the use of the Copernicus WorldDEM-90 | the world's terrain, L0 to L10 (from step 4 of the terrain) |
| AHN4 terrain model | AHN (Actueel Hoogtebestand Nederland), through PDOK. CC0. | CC0 1.0 | the Netherlands, L12 to L14 |
| TINITALY 1.1 | Tarquini S., I. Isola, M. Favalli, A. Battistini, G. Dotta (2023). TINITALY, a digital elevation model of Italy with a 10 meters cell size (Version 1.1). Istituto Nazionale di Geofisica e Vulcanologia (INGV). https://doi.org/10.13127/tinitaly/1.1. CC BY 4.0; the heights were resampled. | CC BY 4.0 | Tuscany, L12 and L13 |
| Kartverket DTM 1 m | © Kartverket. Contains data under the Norwegian licence for Open Government data (NLOD) distributed by Kartverket; the heights were resampled. | Norwegian Licence for Open Government Data 2.0 | Norway, L12 to L14 |
