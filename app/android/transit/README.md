# Public transport: the timetable file and the planner

Atlas plans public transport journeys on the phone, without a signal, from a file of the country's timetable. This folder holds the file's format, the tool that makes the file, and the planner that reads it. `.github/workflows/timetable.yml` checks all of it on Linux and publishes the Netherlands' file once a week. Nothing here is wired into the app yet.

| Here | What |
|---|---|
| `FORMAT.md` | The timetable file: what it holds, byte by byte; what a reader checks; what is left out; the Dutch feed's licence. |
| `pack/ttpack.py` | Makes the file from a GTFS feed. Python's standard library only. |
| `pack/ttlist.py` | Writes `timetable.json`, the list of published files (FORMAT.md, "How it is published"). |
| `pack/test_ttpack.py` | Holds the packer to the bytes of the tests' hand-written feed, its cosine table to the planner's, its refusals, and the list. |
| `planner/` | The planner, in Rust: the crate `atlas_transit` and the tool `atlas-transit`. The standard library only; the library itself is core and alloc, with no unsafe code. `src/lib.rs` says what it holds to. |
| `rust-toolchain.toml` | The Rust it is built with, the same as the core's. |

## Making a file

OVapi publishes the Netherlands' timetable daily at `gtfs.ovapi.nl/nl/gtfs-nl.zip` (about 160 MB; CC0 but for the shapes, which are not taken). Fetched data stays outside the tree.

```sh
python3 -I app/android/transit/pack/ttpack.py ~/atlas/var/transit/gtfs/gtfs-nl.zip ~/atlas/var/transit/tt/nl.tt --sizes
```

It takes about two minutes for the country (run it through `~/atlas/home/bin/heavy`), prints what it kept, what it left out and what it had to mend, and with `--sizes` each section's size. With `--strict` a table or column it does not read is a refusal. `--first`, `--days`, `--box` and `--agency` cut a piece, as the tests' file of Utrecht is.

## Asking it

```sh
cd app/android/transit/planner
export CARGO_TARGET_DIR=~/atlas/var/build/transit-planner     # the build folder stays outside the tree
~/.cargo/bin/cargo build --release
T=$CARGO_TARGET_DIR/release/atlas-transit; F=~/atlas/var/transit/tt/nl.tt

$T info $F                      # what the file holds
$T check $F                     # what opening it does not check
$T stops $F "utrecht c"         # stops by a part of their name
$T trip $F 2835 --date 20261117 # one train: its stops, times and ids
$T plan $F --from "Amsterdam Centraal" --to Maastricht --date 20261117 --time 08:00 --window 120
$T plan $F --from 52.3745,4.8840 --to "Utrecht Centraal" --date 20261117 --time 17:00 --arrive-by \
        --avoid mode:bus --never "station:Amsterdam Centraal" --prefer kind:Intercity=5
$T bench $F --date 20261117     # times on random pairs, each compared with the connection scan
$T fixed $F tests/data/journeys-nl-9696.tsv   # the fixed journeys, against the scan and what the file keeps
```

The head of `planner/src/bin/atlas-transit.rs` lists every command, place and wish.

## Its checks

```sh
python3 -I app/android/transit/pack/test_ttpack.py
CARGO_TARGET_DIR=~/atlas/var/build/transit-planner ~/.cargo/bin/cargo test --manifest-path app/android/transit/planner/Cargo.toml
CARGO_TARGET_DIR=~/atlas/var/build/transit-planner ~/.cargo/bin/cargo build --lib --no-default-features --manifest-path app/android/transit/planner/Cargo.toml   # the library without std, as an app links it
```

`python3 -I app/android/ci/affected.py` names the first two for a change here; the workflow's first job runs all three on Linux, with a million files a little wrong. The tests run on a feed written by hand (`planner/tests/data/tiny`, every answer worked out on paper) and on a piece of the real timetable (the city of Utrecht, two days). With the whole country's file at hand, `ATLAS_TRANSIT_FILE=<file> cargo test --test fixed` also runs the fixed journeys across the country.

How the planner is held to being right:
- **A second opinion.** `src/reference.rs` is a connection scan over the same file, sharing the places, the walks and what is closed, and nothing of the search. The two must agree on the earliest arrival, the latest departure, and every best pair over a window.
- **Every journey can be made.** `reference::impossible` takes a journey and the file, and checks each ride, walk and change as a rider would meet them. It reads nothing of how the journey was found.
- **No panic from data.** `src/fuzz.rs` makes files a little wrong and queries at the edge of every field; the tests run 6,300, the tool's `fuzz` command as many as asked (`cargo run --profile fuzz -- fuzz <file> <runs>` keeps the overflow checks on).
- **The same everywhere.** A fixed set of answers folds to one number that the tests keep.
