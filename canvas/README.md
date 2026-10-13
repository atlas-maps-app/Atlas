# The canvas

The Main canvas holds the app's screens as boards; `project/` is its download (`canvas.json` and one `.dc.html` per board). Since 13 Oct the boards are the spec: a board changes only when the owner picks a side-by-side, or when its screen is replaced by a real one. The 12 Oct reworked versions are in the git bundle of that day and in `cache/state/after-2026-10-13.zip`.

## Publish

- A Design canvas publish needs `root` = the folder above `project/` (`canvas/`, `engine/`), `file_path` = the `project/canvas.json` copy, and `files` keyed `project/<Board>`; with `root` = `project/` the file_path also lands as a stray top-level `canvas.json`. Read the artifact (url only) first or the publish is refused. Canvas versions jump (the app re-saves its index seconds after each publish): read the number from the result.
- Uploading an SVG as an asset strips every `data:` image inside it: upload rasters as their own assets.
- A board leaves the canvas with `null` for its path in `files` (42 went that way on 13 Oct, canvas v313), together with its entry in canvas.json; every `.dc.html` the canvas holds shows on it, listed or not, so the file must go, not only the entry. Deleted boards go into a zip for the owner (`cache/state/deleted-<date>.zip`) and the git bundle keeps them.
- A studio publish needs every line of the live page read first (about 350 KB): diff the saved copy against git first; when it equals a committed version, the publish goes through on the second try.
- **The app's tabs: Map · Go · Soon · Local · Mine** (the owner's choice, 9 Oct; Plan, Know and Me before). The three home screens are the boards Soon, Local and Mine; `gen/common.py` TABS draws the tab bar.
- Board titles stay under 120 characters. Keep a page to about 8 boards; boards never animate on load.

## Look at a board without reading it

- `qa/sheet.sh <fresh dir> A.dc.html B.dc.html …` (run from `canvas/project/`): freezes up to 8 boards and renders one contact sheet with Quick Look (`qlmanage`). `SCALE=1.0` for a 2-board close-up. `board:key=val,…` sets a board's state. Quick Look runs no JavaScript.
- The browser pane caches restaged boards: reload with a new `?v=N`.
- The preview server cannot read `~/Documents` (macOS privacy): stage what it serves with `qa/stage.sh` (into `~/atlas-stage`, which survives sessions).

## Pictures

- `studio/thumbs.py` makes every screen's pictures into `cache/pictures/` (`--list` only says which are out of date, by the board sha1s in `studio/full_hashes.json`); boards drawn by their own script need a DOM snapshot first (`studio/snap/srv.py` through the `atlas-snap` server, then `studio/snaps_render.py`).
- The studio's stars once lost 95 of 110 ratings (a rating saved before `studio/ranks` had loaded): ratings save as a merge, and nothing is saved before its doc has arrived.

