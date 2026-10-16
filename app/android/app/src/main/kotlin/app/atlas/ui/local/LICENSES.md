# What the local package is built from

Code and data that are not this project's own, with their terms.

## Code

- **SGP4 / SDP4** (`astro/Sgp4.kt`): a Kotlin port of David Vallado's reference implementation (Vallado, Crawford, Hujsak, Kelso,
  "Revisiting Spacetrack Report #3", AIAA 2006-6753, and the public-domain C++ code that accompanies it). Checked against Vallado's own
  test vectors (`src/test/resources/local/sgp4/`, 32 satellites, worst difference 4e-6 km over 632 states).
- **Planet positions** (`astro/Planets.kt`): the approximate Keplerian elements and rates of E. M. Standish (JPL, "Keplerian Elements for
  Approximate Positions of the Major Planets", table 1, valid 1800 to 2050), a public NASA/JPL publication. Checked against JPL Horizons
  vectors (`horizons_helio.json`, `horizons_geo.json`).
- **Sun position for the terminator** (`astro/Sky.kt`, `astro/Terminator.kt`): Meeus, *Astronomical Algorithms*, formulae only. Tested against
  `:core`'s `Sun`.
- **Moon** phase and position: `:core`'s `Moon`.

## Data

- **Natural Earth** land outlines (`res/raw/local_globe.json`, made by `art/local_globe.py`): public domain.
- **CelesTrak** two-line elements of the International Space Station (`res/raw/local_iss.tle` as the bundled fallback; fetched live from
  celestrak.org when online and kept on the phone): free to use with credit to CelesTrak.
- **OpenStreetMap** (places, buildings and their `start_date`, parks, peaks, pools, markets): (c) OpenStreetMap contributors, ODbL. The test
  fixtures in `src/test/resources/local/` (`nature_ams.json`, `buildings_westerkerk.json`, `pois_westerkerk.json`, `park_westerpark.json`,
  `peaks_innsbruck.json`) are trimmed extracts of Overpass answers of 15 and 16 October 2026 and carry the same licence.
- **Wikidata** (named after, a building's inception, architect, style and designation): CC0.
- **Panoramax** street photos: each picture carries its own licence (CC-BY-SA 4.0 for the City of Amsterdam's); the credit is shown with the
  photo. The fixture `pano_westerkerk.jpg` is a reduced copy of one, with its search answer `panoramax_westerkerk.json`.
- **Open-Meteo** forecast, marine and elevation data: CC BY 4.0, through `Services.weather`.

## Fonts and drawings

- **Caveat** (SIL OFL 1.1) and **Cormorant Garamond** (SIL OFL 1.1): the files are the eat package's (`R.font.eat_caveat`,
  `eat_cormorant`, `eat_cormorant_italic`); their licences are in `ui/eat/LICENSES.md`.
- The boards' own drawings (the symbols, the chevrons, the brass handle, the plan board) are the project's design.
- The food glyphs (`res/raw/local_art.json`) are drawn for this project by `art/local_art.py`.
