# Test fixtures

Recorded from the real services on 13 October 2026, unchanged unless noted:

- `overpass_*.json`: Overpass answers around the Westerkerk (maps.mail.ru and overpass.private.coffee); `overpass_504.html` and
  `overpass_mailru_504.html` are real "too busy" answers from overpass-api.de and maps.mail.ru.
- `mangrove_*.json`: api.mangrove.reviews (`/geo` for central Amsterdam, `/reviews`, `/subject` and `/batch` for Pfannenzauber, Aachen).
- `ocm_poi.json`: 16 Dutch POIs from openchargemap/ocm-export (the same compact format as the API). The four recorded
  from Oplaadpalen.nl were taken out, as their licence is non-commercial (CC BY-NC-SA); the test that Atlas drops such
  data relabels a licensed POI instead.
- `ndw_sample.json.gz`: the 99 NDW OCPI locations within 1.2 km of the Westerkerk and the three nearest DC ones, cut from the full file.
- `bnetza_head.csv`: the register's preamble, header and 35 rows; `bnetza_page.html`: the download links of its web page.
- `afdc_*.json`: developer.nlr.gov with DEMO_KEY around the White House.
- `meteo_*.json`: api.open-meteo.com and marine-api.open-meteo.com for Amsterdam and IJmuiden, and their error and on-land answers.
- `wd_*.json`, `commons_info.json`: Wikidata `wbgetentities` and Commons `imageinfo`.
- `photon_*.json`, `nominatim_*.json`: Photon and Nominatim for the Westerkerk.
- `osm_note.json`, `osm_notes_bbox.json`: real anonymous notes read from api.openstreetmap.org (the create answer has the same shape).
- `wikipedia_*.json`: recorded on 4 November 2026 from en.wikipedia.org (Westerkerk), nl.wikipedia.org (Westerkerk
  (Amsterdam)) and a title that does not exist, with `action=query` as `Wikipedia` asks it; the language links cut to
  five languages. Text CC BY-SA 4.0, Wikipedia contributors.
- `openaddresses_*`: recorded on 5 November 2026 from batch.openaddresses.io: `openaddresses_data.json` is `/api/data`
  at Castellina in Chianti (11.285, 43.47), `openaddresses_job.json` `/api/job/896384` (it/countrywide), and
  `openaddresses_castellina.mvt.gz` that run's tile 14/8705/5990, its 11,858 gzipped bytes as the public archive
  (v2.openaddresses.io) holds them at offset 756,206,080.
- `transitous_plan.json`: one MOTIS plan from the Westerkerk to Centraal; trimmed to two itineraries, without debug output, steps and alerts.

`doc_*.json` were not recorded: Foursquare, Yelp and Tripadvisor need paid or personal keys, so these follow the
response shapes in each provider's API reference.
- `met_forecast.json`: written by hand (not recorded) from MET Norway's Locationforecast 2.0 `complete` schema around the
  Westerkerk: hourly steps, then six-hour ones, with the symbols, fog and thunder the parser must read.
