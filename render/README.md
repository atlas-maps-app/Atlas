# The engine and the data

The Amsterdam render is Thursday 1 October 2026 at 16:00 (sunrise 07:42, sunset 19:18), the moment every living-map board shares; the one-real-day screens that were built on it went on 13 October.

Atlas's own map engine (`render/`), the data fetchers (`fetch/`) and what they fetched (`data/`). This is the part that goes to GitHub (`github.sh`) and the part the app is built from: the engine's look is the spec for the app's renderer, the fetchers and extracts are its data.

- `render/amsterdam.py`: the living map at the Westerkerk and the shared kit; `regions.py`: every place beyond, framed by the one table `data/regions/places.py`; `generic.py`, `city.py`, `living.py`, `real.py` (shadows, open hours), `shade.py`, `terrain.py`, `relief.py`, `woods.py`, `coasters.py`: the layers; `views.py`, `closeup.py`: cameras; `minify.py`, `cull.py`: uploaded copies under 2 MB; `day_frames.py`: frames at any hour; `traditions/`: building traditions; `routes_ams.py`: walking routes with shade; `check.py`: the smoke test. Renders land in `cache/render/`.
- `python3 render/check.py [place] [moment]` renders one place from its saved data (New York, sunny, by default) and checks the layers come out well-formed: no network, a few seconds.
- A new place: one entry in `data/regions/places.py`, then `fetch/regions.py <name>`, `render/regions.py <name> sunny,grey,wet`, `render/check.py <name>`.
- `fetch/`: Overpass, PDOK (BAG, AHN), Open-Meteo, Wikidata, park.fan, Open Food Facts; `fetch/categories_ams.py` holds the category rules `nearest.py` classifies with. `data/` has one folder per source or place, with the small scripts that belong to one source (`transit/gtfs_extract.py`, `transit/journey.py`, `traffic/ndw.py`, `live/gtfsrt.py`).
- `reviews/`: Mangrove reviews (a local signing key at `~/.config/atlas/mangrove/key.pem`); never `--send` without the owner's yes per review. `google/`: optional, off by default, run by the owner by hand; never run against Google, never a link out to Google Maps.

## Pipelines

- **Living map** (Westerkerk, 815 × 730 m): `render/amsterdam.py`, `ATLAS_KIT=1` for the kit look, `ATLAS_DAYLIGHT=1` for the day's light; frames at any hour with `render/day_frames.py 0730,2100 ['{cfg}' tag]`. Renders land in `cache/render/`. Kit image ids in `gen/kit_blobs.json`. The dark seam at building feet is off unless `ATLAS_AO=1`; `render/strip_seam.py` removes it from older renders.
- **The canal ring** (zoomed out): `fetch/regions.py grachten` (52.360–52.393 N, 4.872–4.922 E with Noord's waterfront; Overpass timed out on the whole box, so it came as the ring plus three boxes merged by id), `render/regions.py grachten` (the engine's view) and `grachten_home` (the Map home's camera, the Westermarkt where the blue dot stands). `Main` on the canvas stands on it (pins on real places by OSM id, walks on the extract's paths, trams from `data/transit/gvb_20261001.json`). Canvas assets are at most 2 MB: for the uploaded copies only, `render/minify.py`, then `render/cull.py` drops what lies wholly outside the frame (here about 60%); the renders in `cache/render` stay whole. Full colour at every zoom: no softening.
- **Eat with the optional Google data** (a test): `EatGoogle` was made from `EatAms` and the owner's hand fetch in `google/fetched/eat/`; the board and the fetched data stay out of git.
- **Routes** `render/routes_ams.py` (OSM walk paths, shade every 3 m from 3D BAG and the tree register). **Categories** `fetch/categories_ams.py` (the rules), `fetch/nearest.py`. **What's here** `fetch/whatshere.py` (BAG via PDOK OGC API, AHN via PDOK WCS).
- **Transit** `data/transit/gtfs_extract.py <zip> <YYYYMMDD> GVB <out>` (OVapi archive; the feed of day D covers D), `data/transit/journey.py`. **Live** `data/live/record_morning.sh`, `data/live/gtfsrt.py`. **Traffic** `data/traffic/ndw.py`, `data/traffic/analyse.py`, `data/traffic/carroute.py`.
- **A new place beyond Amsterdam**: one entry in `data/regions/places.py` (its box, origin, time zone and its views' cameras), then `python3 fetch/regions.py <name>` and `python3 render/regions.py <name> sunny,grey,wet`; `render/check.py <name>` proves the render.
- **Traditions** profiles in `render/traditions/*.json`, engine `generic.trad_look`; the whole catalogue (36 traditions, the kit of 62 parts, 15 foundations, each with its status) is `render/traditions/catalogue.json`, readable in `render/traditions/README.md`.

## Data in and out of git

Out of git, in `data/`, because they are big: the recorded morning `live/rec_20261006/` and the NDW day `traffic/flat_wide.json` (records of the one real day: cannot be fetched again), the GTFS feed `transit/NL-20261001.gtfs.zip` (OVapi no longer serves it; its extract `gvb_20261001.json` is in git), the Overpass and height extracts `regions/*/raw.json` and `dem*.json` (`fetch/regions.py`, `fetch/dem.py` fetch them again, but a new extract shifts the counts under Facts). Back those four up separately.
In git and on GitHub although big: `data/ams/raw.json`, the Amsterdam extract every living-map script reads (nothing in `fetch/` makes it, so it is a record, not a download), and the two GVB days `data/transit/gvb_20260924.json` and `gvb_20261001.json` (`day_journeys.py` sets the journey against the week before).

## Traps already paid for



- Overpass `out geom tags` has no node ids: key nodes by rounded lat/lon. When overpass-api.de is down, `maps.mail.ru/osm/tools/overpass` answered.
- Opening hours past midnight: step `open_at` into the next day (`day_spans` stops at midnight).
- NDW route records carry only end points (route them with `carroute`); one accident appears several times (merge within 500 m).
- The Westerkerk hides the Descarteshuis from the living-map camera: ray-test a press point.
- OSM still maps the Westermarkt tram stop: check stops against the day's timetable.
