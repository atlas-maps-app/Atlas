# Studio

The Envisioning studio: https://claude.ai/artifact/91R7EDZTwhvELNpH6nvfSB. It owns the screen ratings, the envisioning words and
prompts, and the Good tab. `studio.html` is the page (published as `index.html`); `screens.json`, `img/` and `big/` are published
with it. Open a screen's large view with `#screen=<Board>` and a tab with `#words`, `#prompt`,
`#goodpage`, `#screens`, `#sourcespage` or `#inbox`.

## Its database

Two collections are in use. Docs not listed here hold nothing the page reads.

**`studio`**: one doc per kind of setting.

| Doc | Shape | What it is |
|---|---|---|
| `ranks` | `{Board: 0.25…5}` | The owner's stars, in quarter steps. **The only place screen ratings are set.** Good means 4★ or more (the gold dot); an unrated screen does not count as good. |
| `config` | see below | Words, mixes, parts of the prompt, agents. Written whole on every change, so unknown fields survive. |
| `good` | `{intro, examples: [{screen, line, pin?, hold?}], share: [{title, text, screens[], hold?}], made: [{title, text}], rule, bar: [word], barHold?, prefs: {n, by, perCat, skipFolders}, from}` | The Good tab. The prompt's Good screens line is the `share` titles not on hold, joined by ". ". `prefs` is how Fill picks examples (`by`: `rank`, shown as Tiers, or `stars`, `looks`, `use`). Beside the intro the tab shows how many screens have 4★+ and 5★, counted from `ranks` as they are (not stored). The Main canvas's TheBar board is made from this doc (`after_canvas.py`). |
| `rank` | `{tiers: [{id, name}], lists: {tierId: [Board, best first]}}` | Tiers (S, A, … renameable), the owner's own order. |
| `dims` | `{Board: {look, use}}` | Looks · Use, 0.1 to 5 each. Optional; not in the live store on 8 Oct. |
| `catrates` | `{pageId: {mode: 'own' or 'sync', value?, page}}` | A category's own stars, or "follow its screens". |
| `claude` | `{screens: {Board: {stars, look, use, idea, at}}, cats: {pageId: {value, at}}}` | Claude's ratings, written by Claude when asked (a `rate` request). Shown beside the owner's, with Keep. Not in the live store on 8 Oct. |
| `srcnotes` | `{notes: {key: {text, at}}, at}` | The owner's notes in the Sources tab. |
| `screennotes`, `notes` | `{Board: {well, badly, page, at}}`, `{pageId: {page, text, at}}` | Well/Badly and category notes. Not shown. Their text goes into the screens' Improve requests. |

`config` fields: `set` `[{w, on: true}]` holds the words that are on, in the order the prompts send them. `pool` `[w]` holds every
word. Off means in the pool and not in the set, so only a mix's From pool draws it. `poolHold` `[w]` holds the words the owner held
('fun' and 'real things' on 8 Oct): off, no prompt draws them, From pool included (shown as held on the Words tab); on, a held word is
in every prompt like any other, and off again it is held again. A set entry left off (`on: false`, none on 8 Oct) is held the same way;
the first Words edit moves it into `poolHold`. A word renamed to another word's name (any case) is not renamed; that word gets the focus. No Words edit changes what From pool draws beyond the edit itself (`writeback_proof.sh`). `forms` `[[a, b]]` lists pairs that can be swapped,
and `formsHold` `['a⇄b']` lists pairs on hold. `wordColors` `{w: hex}` keeps colours the owner picked; other words take their
family's colour. `mixes` `[{name, opener, vary, shuffle, keep, fromPool, good, ours, place, seed, screenMode?, anchor?, frame?, model?, hold?, own?, ownScreen?}]`
(`own` is a prompt written by hand and sent as it is). The parts are `openers`, `app`, `screenLine`, `screen`, `anchorLine`,
`thingLine`, `things`, `ours`, `places`, `frame`, `frameLine` and `tech`. `agents` is `{tools, effort, atOnce, model, tryModels, merge, mergeLine}`.
Kept but unused: `good` (the old hand-copied Good screens line), `colors` (the old hue per word), `notes`, `askMe`, `qDeleteNow`
and `qGone`.

**`requests`**: one doc per request from the owner, newest first in the Inbox.
`{kind, screen, page, pageId, text, status: new | seen | doing | done, at, edited?, note?, reply?}`. `reply` is Claude's answer and
`note` is the owner's note on the request.
- `improve`: a screen's one Improve field, edited in place (the screen's newest improve request not `done`). Emptied, the request stays
  with `text: ''` (its `note` and `reply` stay); a request with empty text is not open: no badge on the card, not in the Inbox count,
  shown in the Inbox as Empty. Written again, it is the same request, `status: 'new'` again.
- `envision`: a round sent from the Prompt tab, or Re-envision on a screen. It carries `mixes: [{name, prompt, own, words, opener, place, thing, screenMode, frame, good, ours, model, modelOwn}]`, each prompt exactly as it was sent, plus `agents`.
- `move` (`direction`, `from`, `toName`, `batch?`) and `delete`: a screen to a folder, back, or off the canvas.
- `rename`: `what` is screen or page, with `from` and `to`.
- `rate`: Claude rates a screen, a category or every screen. `scope` is screen, page or all.
- `source`: from the Sources tab.
- `idea`: one line from you, from the Inbox's Add (no screen); from the Sources tab it carries `need`. Taken up, it goes into `app/ideas.md`.

Sources tab collections: `sources` (the catalogue and the concrete sources), `src`, `needs` (what each screen needs) and `tags` (about the data).


## Files

- `thumbs.py` makes `screens.json` and, only where needed, the pictures. Main canvas boards are 412 wide; `full/` (824) is rendered from
  `canvas/project`. The Engine canvas's working-page boards come from `cache/pictures/engine/` and are never rendered here. From each, `big/`
  (824, the large view) and `img/` (412, the cards) are made. `--list` makes nothing and prints what is needed (`after_canvas.py` uses it).
- `snaps_render.py` draws boards that draw themselves, from DOM snapshots (`snap/`).
- Tests: `qa/pagetest/studio/` (`all.sh` runs the checks, `shots.sh` takes the screenshots, `proofs.sh` runs every prompt proof:
  `prompt_proof.py` against the old page, also on the export after a batch plan (`afterbatch.py`), and `writeback_proof.sh` over the
  config variants of `variants.py`).
- An artifact version holds at most 511 files: `index.html`, `screens.json` and two pictures per screen.
