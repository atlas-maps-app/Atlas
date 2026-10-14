# Working on Atlas

How a working session runs. The public face of the project is the [README](README.md).

The boards live on two design canvases, the Main canvas and the Engine canvas, and their ratings and requests live in the Studio. The tools that talk to them keep their addresses: `inbox.py`, `after_canvas.py`, `rename.py` and `dbplan.py`. `cache/` and `google/` stay out of git.

| Page | Owns | Source here |
|---|---|---|
| Main canvas | the boards: the app's screens, 177, 88 rated 4★+ | `canvas/project/` (download from live before changing) |
| Engine canvas | Atlas's own engine: the living map, regions, traditions | `engine/project/` |
| Studio | screen ratings, envisioning words, the Good tab, the Inbox (requests and ideas) | `studio/studio.html` (`studio/README.md`) |

## Every session

Tokens are the budget: the boards and the two pages are big, and the databases are small.

- A board's source is the material for the app: take its elements (SVG, CSS, symbols); do not rebuild them by eye.
  - Read boards under about 60 KB whole.
  - Grep narrow windows in bigger ones.
  - Never read the engine's SVG renders; script them.
  - The boards' symbols are catalogued by `cache/look-ref/symbols/extract.py`.
- Exports always use `out_dir`, with their versions taken from the list result through `dbplan.py versions`. Writes always go as one pinned batch from a plan file. Never `list` a collection into context.
- Comments: the header says how many threads are open. Open only those, by id.
- A page publish (Studio) is proven by its page tests, never by reading the page.

1. **Inbox first.** Export the Studio's database and read the open comment threads, then run `python3 inbox.py <export dir>`. Its docstring has the steps; exports go to `cache/state/inbox/<date>/`. Answer everything open, and run `--mark` at the end.
2. **The work.** `app/README.md` says what is next; the Studio's inbox and stars say how.
3. **After a canvas or page change.** `python3 after_canvas.py canvas/project --engine engine/project --ranks <export>/studio/studio/ranks.json` says what to publish. Publish it, run the page tests, commit.
4. **Renames.** `python3 rename.py <from> <to> --export <export>` renames a screen in every store at once.
5. **The app.** Push the work branch to GitHub. CI builds, tests and opens every screen on an emulator. A tag `v…-alpha.N` publishes a test build to the releases.

## Traps already paid for

- **Token costs.** Reading one heavy board (300 KB and more) costs 70,000 to 230,000 tokens, a page read about 80,000, and a page of ten comment threads about 9,000.
- **Shell and Python.**
  - `rm` only literal paths or `"${VAR:?}"`.
  - zsh does not word-split `$var` in loops: use Python for lists of files.
  - Python `%` templates need `%%` in CSS.
  - macOS has no `timeout` command: give subprocesses a timeout in Python.
- **Preview servers.** The desktop app's preview servers cannot read `~/Documents` (macOS privacy). `qa/stage.sh` stages what they serve in `~/atlas-stage`.
- **The emulator.** The Android emulator cannot boot from a Claude session, because the Hypervisor framework reports unsupported. GitHub's CI runs one, or run `emulator -avd atlas` from a terminal of your own, or use a phone over USB.
- **Quick Look renders.** Quick Look (`qlmanage -t`) renders HTML in a 1024 px square. Scale a 412 × 915 board to fill it to get a sharp render (see `cache/look-ref/`).
