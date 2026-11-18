# Public transport: the timetable file and the planner

Atlas plans public transport journeys on the phone, without a signal, from a file of the country's timetable. This folder holds the file's format, the tool that makes the file, and the planner that reads it. `.github/workflows/transit.yml` checks all of it on Linux at every push that changes it, and `.github/workflows/timetable.yml` publishes the Netherlands' file once a week. Nothing here is wired into the app yet.

| Here | What |
|---|---|
| `FORMAT.md` | The timetable file: what it holds, byte by byte; what a reader checks; what is left out; the Dutch feed's licence. |
| `pack/ttpack.py` | Makes the file from a GTFS feed. Python's standard library only. |
| `pack/ttlist.py` | Writes `timetable.json`, the list of published files (FORMAT.md, "How it is published"). |
| `pack/test_ttpack.py` | Holds the packer to the bytes of the tests' two hand-written feeds, its cosine table to the planner's, the days the clock changes, its refusals, and the list. |
| `planner/` | The planner, in Rust: the crate `atlas_transit` and the tool `atlas-transit`. The standard library only; the library itself is core and alloc, with no unsafe code. `src/lib.rs` says what it holds to. |
| `rust-toolchain.toml` | The Rust it is built with, the same as the core's. |

## Making a file

OVapi publishes the Netherlands' timetable daily at `gtfs.ovapi.nl/nl/gtfs-nl.zip` (about 160 MB; CC0 but for the shapes, which are not taken). Fetched data stays outside the tree.

```sh
python3 -I app/android/transit/pack/ttpack.py ~/atlas/var/transit/gtfs/gtfs-nl.zip ~/atlas/var/transit/tt/nl.tt --sizes
```

It takes a minute or two and 1.8 GB of memory for the country (run it through `~/atlas/home/bin/heavy`; its last line says what it took), prints what it kept, what it left out and what it had to mend, and with `--sizes` each section's size. With `--strict` a table or column it does not read is a refusal. `--first`, `--days`, `--box` and `--agency` cut a piece, as the tests' file of Utrecht is.

## Asking it

```sh
cd app/android/transit/planner
export CARGO_TARGET_DIR=~/atlas/var/build/transit-planner     # the build folder stays outside the tree
~/.cargo/bin/cargo build --release
T=$CARGO_TARGET_DIR/release/atlas-transit; F=~/atlas/var/transit/tt/nl.tt

$T info $F                      # what the file holds
$T check $F                     # what opening it does not check
$T days $F                      # how full its days are, week by week, and which operators stop short
$T stops $F "utrecht c"         # stops by a part of their name
$T trip $F 2835 --date 20261117 # one train: its stops, times and ids
$T plan $F --from "Amsterdam Centraal" --to Maastricht --date 20261117 --time 08:00 --window 120
$T plan $F --from 52.3745,4.8840 --to "Utrecht Centraal" --date 20261117 --time 17:00 --arrive-by \
        --avoid mode:bus --never "station:Amsterdam Centraal" --prefer kind:Intercity=5
        # an avoided thing counts as arriving that many minutes later (10 unless said); --every-option keeps the
        # options that are slower by more than their wishes are worth too
$T bench $F --date 20261117     # times on random pairs, each compared with the connection scan (--time, --pairs,
                                # --points; --no-rules without the feed's rules; --quick leaves the slow comparisons out)
$T fixed $F tests/data/journeys-nl-9696.tsv   # the fixed journeys, against the scan and what the file keeps
```

The head of `planner/src/bin/atlas-transit.rs` lists every command, place and wish.

## Its checks

```sh
python3 -I app/android/transit/pack/test_ttpack.py
CARGO_TARGET_DIR=~/atlas/var/build/transit-planner ~/.cargo/bin/cargo test --manifest-path app/android/transit/planner/Cargo.toml
CARGO_TARGET_DIR=~/atlas/var/build/transit-planner ~/.cargo/bin/cargo build --lib --no-default-features --manifest-path app/android/transit/planner/Cargo.toml   # the library without std, as an app links it
```

`python3 -I app/android/ci/affected.py` names the first two for a change here; `.github/workflows/transit.yml` runs all three on x86_64 and arm64 Linux at a push, with a million files a little wrong. The tests run on two feeds written by hand, every answer worked out on paper (`planner/tests/data/tiny`; and `edges`, the cases a review named: the last connection of an evening, the latest departure, a window's last moment, a vehicle that waits, a stop's own time to change, the night the clock changes, a walk all the way over a window), and on a piece of the real timetable (the city of Utrecht, two days). The fixed journeys across the country need the whole country's file, so `cargo test` shows that test as ignored; with the file at hand it is `ATLAS_TRANSIT_FILE=<file> cargo test --test fixed -- --ignored`, which fails without the file.

How the planner is held to being right:
- **A second opinion.** `src/reference.rs` is a connection scan over the same file, sharing the places, the walks and what is closed, and nothing of the search. It holds to the feed's rules between trips in its own way, and is asked as far as the query's own horizon, whatever the search followed. The two must agree on the earliest arrival, the latest departure, and every best pair over a window.
- **A patient search gives what a whole one gives.** By default a search first follows journeys only as far as one could sensibly take, and goes on only for what could still be an option. `tests/cut.rs` and the tool's `bench` hold its answers equal to those of a search as far as the horizon.
- **A journey leaves when it must.** Asked for one moment, each option's departure is the latest that makes its arrival with no more rides: asked back from that arrival, the same departure comes out.
- **Two windows give what one gives.** A window holds its last moment, and what counts against a journey is every journey that leaves no earlier, inside the window or after it. So a window cut in two at any second gives the same options as the whole; `tests/cut.rs` cuts each at the moments its options leave. Between two points near enough to walk all the way, the walk is one option of each window and can begin at any moment: a ride that takes no less time than the walk is none, wherever in a window it leaves, and the rides are the same however the window is cut.
- **Every journey can be made.** `reference::impossible` takes a journey and the file, and checks each ride, walk and change as a rider would meet them. It reads nothing of how the journey was found.
- **Wishes weighed by the rule alone.** An option stays unless another leaves no earlier, arrives no later, has no more rides, and is no later with each one's cost added (`src/query.rs`'s head). The search that weighs prunes by that rule as it goes; `tests/cut.rs` and the tool's `bench` hold it to what the rule leaves of every option, found without weighing.
- **No panic from data.** `src/fuzz.rs` makes files a little wrong and queries at the edge of every field; the tests run 6,300, the tool's `fuzz` command as many as asked (`cargo run --profile fuzz -- fuzz <file> <runs>` keeps the overflow checks on).
- **The same everywhere.** A fixed set of answers folds to one number that the tests keep (`tests/cut.rs`). And the files a little wrong give counts (how many still opened, how many journeys they and the queries gave) that `tests/data/fuzz-counts.txt` keeps and the workflow holds each platform to. After a change to the search or to `fuzz.rs`, write them again: `./fuzz-counts.sh > tests/data/fuzz-counts.txt` in `planner/`.
