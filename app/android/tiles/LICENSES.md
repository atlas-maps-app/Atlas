# Licences in `:tiles`

No third-party source code is vendored here. Everything is Atlas's own code, written from the public specifications:

- **Mapbox Vector Tile 2.1** (github.com/mapbox/vector-tile-spec, CC BY 3.0 US). Grouping polygon rings by the first ring's winding, which also reads 1.x tiles, follows the behaviour of mapbox/vector-tile-js `classifyRings` (BSD-3-Clause); no code was copied.
- **PMTiles v3** (github.com/protomaps/PMTiles `spec/v3`, BSD-3-Clause). The Hilbert tile id conversion follows the spec's reference algorithm.
- **MBTiles 1.3** (github.com/mapbox/mbtiles-spec, CC BY 3.0 US).
- **TileJSON 3.0** (github.com/mapbox/tilejson-spec, CC BY 3.0 US).

Libraries used (from the Gradle catalog, not vendored): OkHttp and Okio (Apache-2.0), AndroidX SQLite bundled driver (Apache-2.0, with SQLite in the public domain), kotlinx.coroutines (Apache-2.0).

## Data

Tiles come from OpenFreeMap (openfreemap.org): OpenMapTiles schema, data © OpenStreetMap contributors under the ODbL. Any map drawn from them must show "© OpenMapTiles © OpenStreetMap contributors" (`OpenFreeMapSource.ATTRIBUTION`). The test fixtures in `app/android/testdata/omt` are such tiles.
