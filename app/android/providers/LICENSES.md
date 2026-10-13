# Licences of what `:providers` ships and reads

## Bundled data

| File | Source | Licence | Credit to show |
|---|---|---|---|
| `src/main/resources/app/atlas/providers/ev-models.tsv.gz` | Open EV Data, `data/ev-data.json` of 2025-04-12 (https://github.com/KilowattApp/open-ev-data) merged with the OpenEV Data dataset v1.24.0 (https://github.com/open-ev-data/open-ev-data-dataset) by `tools/build_ev_models.py` | MIT License with Attribution Requirement; CDLA-Permissive-2.0 | "Open EV Data (https://github.com/KilowattApp/open-ev-data)" in the About screen (required). The full texts of both licences ship beside the data in `ev-models-LICENCES.txt`, as CDLA-Permissive-2.0 asks. |
| `src/main/resources/app/atlas/providers/ocm-operators.tsv` | Operator ids and names from Open Charge Map's reference data (`ocm-export/data/referencedata.json`) | CC BY 4.0 | "Operator names from Open Charge Map" |

## Data read at run time

Each provider's `ProviderInfo` carries its licence and credit line; `Catalogue.ALL` lists them for the credits screen.

| Provider | Licence | Notes |
|---|---|---|
| OpenStreetMap via Overpass, Nominatim, Photon, OSM notes | ODbL | "© OpenStreetMap contributors" |
| Mangrove | CC BY 4.0 (some reviews CC BY-SA 4.0, per review) | The review's own licence is kept in `Review.licence` |
| Open Charge Map | CC BY 4.0 for OCM's own data; each data provider's licence otherwise | Only licensed providers are kept (`OpenChargeMap.OPEN_PROVIDERS`); Oplaadpalen.nl (CC BY-NC-SA) is dropped. Never write OCM data back to OSM. |
| NDW (Netherlands) | Not stated on opendata.ndw.nu | Published under the EU's AFIR; to be confirmed with NDW before release |
| Bundesnetzagentur Ladesäulenregister | CC BY 4.0 | "Bundesnetzagentur.de" |
| AFDC (United States) | Public; "may be used for any purpose whatsoever" | |
| Open-Meteo | CC BY 4.0; the free API is for non-commercial use | "Weather data by Open-Meteo.com" with a link |
| Wikidata | CC0 | Commons files carry their own licences; `Commons.files` reads them |
| Transitous | Per timetable source | Link to https://transitous.org/sources/ |
| Foursquare, Yelp, Tripadvisor | Their API terms, with the user's own key | Yelp is shown apart and never kept over 24 hours; Tripadvisor is never kept |

## Test fixtures

`src/test/resources/fixtures/` holds small answers recorded from the services above (see its `README.md`); they keep
their sources' licences and are used only to test this module.

| Fixtures | Licence |
|---|---|
| `overpass_*`, `nominatim_*`, `photon_*`, `osm_note*` | ODbL, © OpenStreetMap contributors |
| `mangrove_*` | CC BY 4.0, the Mangrove reviewers |
| `ocm_poi.json` | CC BY 4.0, Open Charge Map and its data providers; its four Oplaadpalen.nl POIs are CC BY-NC-SA 4.0 (kept only to test that Atlas drops them) |
| `ndw_sample.json.gz` | NDW open data (licence not stated by NDW) |
| `bnetza_head.csv`, `bnetza_page.html` | CC BY 4.0, Bundesnetzagentur.de |
| `afdc_*` | U.S. Department of Energy, public data |
| `meteo_*` | CC BY 4.0, Open-Meteo.com |
| `wd_*` | CC0, Wikidata; `commons_info.json` is Commons file metadata (the files keep their own licences) |
| `transitous_plan.json` | Transitous, from the timetable sources listed at https://transitous.org/sources/ and OpenStreetMap (ODbL) |
| `doc_*` | Written for the tests after each provider's API reference; no recorded data |

No code is vendored in this module.
