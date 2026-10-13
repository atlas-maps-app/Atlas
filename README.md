# Atlas

A maps app that pushes the limits of what a maps app can be. Its screens were designed as boards on a claude.ai Design canvas, fed by real open data and drawn on Atlas's own map engine. The boards are the spec; the app itself is the work ahead. Everything here runs on this Mac and is pushed to https://github.com/atlas-maps-app/Atlas (public); `cache/` and `google/` stay out of git.

## The brief

Atlas goes past the best maps apps of today, built on OpenStreetMap and open data as a native Android app that is perfectly optimised and efficient, from the best open-source projects there are or from our own. Minimalist and intuitive yet powerful in every direction; symbols, shapes and real things carry the meaning and text appears only where it must; skeuomorphism where it adds value. Everything offline, with open-source routing and voice directions good enough that you never look at the screen, whether you cycle, sail or drive a truck. Restaurant discovery with several rating providers and what is rising. Nothing missing, and for OpenStreetMap power users complex searches made easy: any tags, a radius, one thing with another nearby. Brands with their SVG logos and every branch a tap away; charging and charging routes with Chargemap's data. Rethought first, compared later.

## What is where

| Folder | What it holds |
|---|---|
| `canvas/` | the Main canvas as downloaded (`project/`: the boards and `canvas.json`), `boards.py` (the canvas as a structure), `board_notes.py` (diff the live canvas before a publish); `README.md`: publishing, pictures, the one real day's facts |
| `engine/` | the Engine canvas as downloaded (`project/`: 17 boards, the engine's look) |
| `app/` | the app: `README.md` (start here: the order, the decisions), `ideas.md` (the ideas, one line each), `android/` (the Kotlin and Compose project; builds with `./gradlew assembleDebug`) |
| `studio/` | the Studio page (`studio.html`), its database notes (`README.md`), the picture pipeline (`thumbs.py`, `snaps_render.py`, `snap/` the snapshot server), `screens.json`, `full_hashes.json`, the envisioning procedure (`envision_prompts.md`) |
| `render/`, `fetch/`, `data/` | Atlas's own map engine, the data fetchers and what they fetched: the part the app is built from and the part that goes to GitHub (`github.sh`); `render/README.md`: the engine, the pipelines, the data, its traps; `render/traditions/`: the building traditions, the kit and the foundations with their status |
| `reviews/` | Mangrove reviews, a Python package |
| `qa/` | `freeze.py` + `sheet.sh` (a board without a browser), `stage.sh` (the local servers), `pagetest/` (headless page tests for the Studio, with the export they were written against) |
| root | the session commands `inbox.py`, `after_canvas.py`, `rename.py`, `dbplan.py`; `github.sh` copies the GitHub part to `~/Documents/atlas-github` |

## The three pages

Each page owns its facts; the others link to it or show a copy made by a script.

| Page | Link | Owns | Source here |
|---|---|---|---|
| Main canvas | https://claude.ai/artifact/Dapy8WL82jeyUhqPKPmA5L | the boards: the app's screens, 177, 88 rated 4★+ | `canvas/project/` (download from live before changing) |
| Engine canvas | https://claude.ai/artifact/UQAwzFPMbEhhLcvrpZsjZR | Atlas's own engine: the living map, regions, traditions | `engine/project/` |
| Studio | https://claude.ai/artifact/91R7EDZTwhvELNpH6nvfSB | screen ratings, envisioning words, the Good tab, the Inbox (your requests and ideas) | `studio/studio.html` (`studio/README.md`) |


## Licence

The code, the boards and the notes are under the MIT licence (`LICENSE`): use it, change it, fork it. The data in `data/` keeps the terms of its sources: the OpenStreetMap extracts are © OpenStreetMap contributors under the ODbL, and the other open datasets (PDOK, Open-Meteo, Wikidata, NDW, OVapi, Open Food Facts) carry their own open licences; the sources are listed in `render/README.md` and in the studio's Sources tab.

## Every session

Tokens are the budget: the boards and the two pages are big, the databases are small.

- Never read a board or an SVG into context. Look at a PNG (`qa/sheet.sh`, the studio's pictures); edit with a window of a few hundred characters.
- Exports always with `out_dir`, their versions from the list result through `dbplan.py versions`; writes always as one pinned batch from a plan file. Never `list` a collection into context.
- Comments: the header says how many threads are open; open only those, by id.
- A page publish (Studio) is proven by its page tests, never by reading the page. The read a publish demands is taken the cheap way: the first attempt is refused and counts as the read when the saved copy equals the committed one, so the second goes through.

1. **Inbox first**: export the studio's database and read the open comment threads, then `python3 inbox.py <export dir>` (its docstring has the steps; exports go to `cache/state/inbox/<date>/`); answer everything open; `--mark` at the end.
2. **The work**: `app/README.md` says what is next; the studio's inbox and stars say how.
3. **After a canvas or page change**: `python3 after_canvas.py canvas/project --engine engine/project --ranks <export>/studio/studio/ranks.json` says what to publish (counts on the Start boards, the studio's pictures); publish it, run the page tests, commit.
4. **Renames**: `python3 rename.py <from> <to> --export <export>` renames a screen in every store at once.

## Next

The app: `app/README.md` says where it stands and what comes next. The Plan page was retired on 13 October; its contents are in `app/` and in `cache/state/plan-retired-2026-10-13.zip`.

## Traps already paid for

- Reading one heavy board (300 KB and more) costs 70,000 to 230,000 tokens; a page read about 80,000; a page of ten comment threads about 9,000. One session's whole database work costs less than one such board.
- `rm` only literal paths or `"${VAR:?}"`. zsh does not word-split `$var` in loops: use Python for lists of files. Python `%` templates need `%%` in CSS. macOS has no `timeout` command: give subprocesses a timeout in Python.
- The desktop app's preview servers cannot read `~/Documents` (macOS privacy): `qa/stage.sh` stages what they serve in `~/atlas-stage`.
- The Android emulator cannot boot from a Claude session (the Hypervisor framework reports unsupported): run `emulator -avd atlas` from a terminal of your own, or use a phone over USB. The APK builds fine from the session.
