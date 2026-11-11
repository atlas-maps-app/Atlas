# Licences of what `:providers` ships and reads

## Bundled data

`Catalogue.BUNDLED` gives these to the credits card of About & licences, which shows each with its credit, so the
list below the card leaves them out.

<!-- not on About: the credits card above the list shows them (Catalogue.BUNDLED) -->

| File | Source | Licence | Credit to show |
|---|---|---|---|
| `src/main/resources/app/atlas/providers/ev-models.tsv.gz` | Open EV Data, `data/ev-data.json` of 2025-04-12 (https://github.com/KilowattApp/open-ev-data) merged with the OpenEV Data dataset v1.24.0 (https://github.com/open-ev-data/open-ev-data-dataset) by `tools/build_ev_models.py` | MIT License with Attribution Requirement; CDLA-Permissive-2.0 | "Open EV Data (https://github.com/KilowattApp/open-ev-data)" |
| `src/main/resources/app/atlas/providers/ocm-operators.tsv` | Operator ids and names from Open Charge Map's reference data (`ocm-export/data/referencedata.json`) | CC BY 4.0 | "Operator names from Open Charge Map" |

The Open EV Data credit is required on the About screen. The full texts of both its licences ship beside the data
in `ev-models-LICENCES.txt`, as CDLA-Permissive-2.0 asks.

## Data read at run time

Each provider's `ProviderInfo` carries its licence and credit line; `Catalogue.ALL` lists them for the credits card
of About & licences. AFDC and Transitous are not asked yet (`Catalogue.NOT_WIRED`), so the card leaves them out.

<!-- not on About: the credits card above the list shows each provider the app asks (Catalogue.ALL) -->

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
| OpenAddresses | Each source's own (CC0, CC BY, Licence Ouverte…); a source that names no licence is left out | Read with a region taken offline, by byte range from the public tiles of each source's last run (no account, never a whole file). Each filled address carries its source's credit and licence, and each store lists its sources with theirs (meta key `openaddresses`). |
| Foursquare, Yelp, Tripadvisor | Their API terms, with the user's own key | Yelp is shown apart and never kept over 24 hours; Tripadvisor is never kept |

## Test fixtures

`src/test/resources/fixtures/` holds small answers recorded from the services above (see its `README.md`); they keep
their sources' licences and are used only to test this module.

<!-- not on About: read by the tests only -->

| Fixtures | Licence |
|---|---|
| `overpass_*`, `nominatim_*`, `photon_*`, `osm_note*` | ODbL, © OpenStreetMap contributors |
| `mangrove_*` | CC BY 4.0, the Mangrove reviewers |
| `ocm_poi.json` | CC BY 4.0, Open Charge Map and its data providers (OCM contributors, PowerGo CC0); no non-commercial data |
| `ndw_sample.json.gz` | NDW open data (licence not stated by NDW) |
| `bnetza_head.csv`, `bnetza_page.html` | CC BY 4.0, Bundesnetzagentur.de |
| `afdc_*` | U.S. Department of Energy, public data |
| `meteo_*` | CC BY 4.0, Open-Meteo.com |
| `wd_*` | CC0, Wikidata; `commons_info.json` is Commons file metadata (the files keep their own licences) |
| `openaddresses_castellina.mvt.gz` | CC BY 4.0, Istat e dall'Agenzia delle Entrate (it/countrywide), through OpenAddresses |
| `openaddresses_data.json`, `openaddresses_job.json` | OpenAddresses' service answers: source names, licences and run boxes |
| `transitous_plan.json` | Transitous, from the timetable sources listed at https://transitous.org/sources/ and OpenStreetMap (ODbL) |
| `doc_*` | Written for the tests after each provider's API reference; no recorded data |

No code is vendored in this module.
