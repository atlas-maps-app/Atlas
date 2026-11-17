# The tester's flows

A flow is a text file of steps, one a line, that `tester.yml` runs on an alpha on a fresh emulator (through
`tester.py`, beside this file). `tester-example.flow` is one: the first launch, a search for a café in central
Amsterdam, a long press on the map.

## Running one

```bash
gh workflow run tester.yml -R atlas-maps-app/Atlas --ref app -f tag=v0.1.0-alpha.33 -f label=search -f flow="$(cat search.flow)"
```

- `tag` empty runs the newest alpha. `label` names the run and its artifact (letters, digits, `-`, `_`).
- `-f animations=true` keeps Android's animations on (off by default, as on CI).
- Find the run with `gh run list -R atlas-maps-app/Atlas --workflow tester.yml --limit 3`, follow it with
  `gh run watch <id>`, and fetch what it left with `gh run download <id> -n tester-<label> -D <folder>`.
- Check a flow before you send it: `python3 -I app/android/ci/tester.py check search.flow`. The run checks it again
  before it fetches anything, and a flow that does not parse names its line.

The emulator is a Pixel 7 (1080 x 2400) on Android 15 (API 35), Google APIs, nothing installed but Atlas. It starts with
no permission granted, as a user's phone does: Android asks, and the flow answers (`tap "While using the app"`) or
`grant`s first.

## What a run leaves

One artifact, `tester-<label>`:

| File | What |
|---|---|
| `steps.tsv` | a row a step: step, line, start (UTC), ms, result, detail, shot. Step 0 is the install. |
| `summary.md` | the same as a table; it is also the job's summary on GitHub |
| `shots/NN-<name>.png` | the screen after each step, numbered by step |
| `shots/NN-<name>.ui.xml` | for a step that failed: the texts the screen showed (uiautomator's dump) |
| `logcat.txt` | the device's whole log from the install on |
| `crash.txt`, `anr/` | the crash buffer and the ANR traces, when there were any |
| `device.txt`, `flow.txt`, `run.log` | the device's facts and Atlas's version, the flow as sent, the runner's own log |

A step's **result** is `ok`, `failed` (it did not do what it said: the detail says why), `crash` or `anr` (Atlas
crashed or hung during it), or `skipped` (after a stop). **ms** is the step's action alone: for a `wait`, how long the
text took to show, so it times a lookup or a spinner. After each step's action the runner waits a moment (1 s,
see `settle`) before the screenshot; that is not in ms.

A failed step does not end the run: the next step runs. End a line with `or stop` to end the run there when it fails
(`launch or stop`). The job itself is red only when the harness failed: a flow that does not parse, no such alpha, no
emulator, an install that failed.

## The lines

Words in lower case are written as they are; `"text"` is a text in double quotes (straight or curly). `#` starts a
comment. Several steps can share a line, parted by `;`.

**Places on the screen** (`<place>` below): a text, `"Search or ask"`, the n-th one where it shows more than once,
`"Café" 2`; `center`; or `x y`, in pixels (`540 1200`) or per cent of the screen (`50% 80%`). A text matches what the
screen shows to accessibility, a node's text or its description, in any case, accents and curly quotes aside: `"cafe"`
finds "Café", `"What's here"` finds "What’s here". The exact match comes first, then a text that contains it. Text Atlas
draws itself on the map is not there: use a point.

### The app

| Line | What it does |
|---|---|
| `launch` (or `open`) | starts Atlas from its icon, or brings it back; the detail has the launch's TotalTime |
| `close` | stops Atlas (as swiping it away does) |
| `fresh` | clears Atlas's data and permissions: the next `launch` is a first launch |
| `background` | presses Home; Atlas keeps running |
| `grant location notifications microphone` | grants those permissions (any of them) without Android's question |
| `revoke location` | takes them back |

Close and open again: `close` then `launch`.

### Touch and keys

| Line | What it does |
|---|---|
| `tap <place>` | taps it. A text is looked for for up to 5 s; for longer, `wait` for it first |
| `longpress <place> [2s]` | presses it for 1 s (or as long as said): `longpress center` on the map is "what's here" |
| `type "text"` | types into the field that has the focus. Plain ASCII only: Atlas's search folds accents, so `type "cafe"` |
| `key enter` | presses a key: `enter back home tab delete search menu recents escape space up down left right`, or `KEYCODE_...` |
| `back [n]` | presses Back, once or n times |
| `swipe up\|down\|left\|right [from <place>] [ms]` | the finger moves that way (`swipe up` brings what is below into view): through the middle of the screen, or from the place, over 40 % of the screen, in 300 ms or as said |
| `swipe x1 y1 x2 y2 [ms]` | from one point to the other |
| `zoom in\|out [at <place>]` | two fingers apart or together, in the middle or at the place |
| `turn <degrees> [at <place>]` | two fingers turn the map, `turn 45`, `turn -90` |
| `rotate landscape\|portrait` | turns the phone (auto-rotate off) |

### Waiting and looking

| Line | What it does |
|---|---|
| `wait 5s` | waits that long (`500ms`, `2m`) |
| `wait "text" [30s]` | until the text shows, up to the limit (30 s when none is said); fails if it never does |
| `wait gone "text" [30s]` | until the text no longer shows: a spinner, "Searching" |
| `expect "text"` | the text shows now (no waiting) |
| `expect no "text"` | the text does not show now: `expect no "unreadable"` |
| `shot <name>` | a screenshot named `NN-<name>.png` (every step has one; this one has your name) |
| `settle 2s` | the pause after each later step before its screenshot (1 s to begin with) |

### The phone's world

| Line | What it does |
|---|---|
| `location amsterdam` | the phone is at the Westerkerk in central Amsterdam (52.37452, 4.88377), the project's test spot; location turned on |
| `location 52.3745 4.8838` | the phone is there (latitude, longitude) |
| `location off` / `location on` | the phone's location setting |
| `network off` | Wi-Fi and mobile data off: no network at all |
| `network slow [gsm\|gprs\|edge\|umts\|hsdpa\|lte]` | network on, at that speed and its delay (edge when not said: about 120 kbit/s up, 240 down, 80 to 400 ms) |
| `network on` | network on at full speed |

## Writing a flow

- Wait for what a step needs before the step: after `tap "Search or ask"`, `wait "cafe"` or a list's first entry,
  not `wait 5s`. A wait's ms is the time a user waits.
- A text that is not found fails its step with the texts the screen showed instead (in the detail, and in
  `NN-<name>.ui.xml`), so a wrong guess at a label is quick to correct.
- Put `or stop` where nothing after makes sense without the step: the launch, the home's first appearance.
- Keep a flow to one thing a user does, at most a few minutes of steps; a pass runs at most six.
