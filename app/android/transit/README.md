# Public transport: the timetable file and the planner

Atlas plans public transport journeys on the phone, without a signal, from a file of the country's timetable. This folder holds the file's format, the tool that makes the file, the planner that reads it, and the planner's C interface. `.github/workflows/transit.yml` checks the first three on Linux at every push that changes them, and `.github/workflows/timetable.yml` publishes the Netherlands' file once a week. Nothing here is wired into the app yet: no module builds the interface for a phone, and nothing in Kotlin calls it.

| Here | What |
|---|---|
| `FORMAT.md` | The timetable file: what it holds, byte by byte; what a reader checks; what is left out; the Dutch feed's licence. |
| `pack/ttpack.py` | Makes the file from a GTFS feed. Python's standard library only. |
| `pack/ttlist.py` | Writes `timetable.json`, the list of published files (FORMAT.md, "How it is published"). |
| `pack/test_ttpack.py` | Holds the packer to the bytes of the tests' three hand-written feeds, its cosine table to the planner's, the days the clock changes, its refusals, and the list. |
| `planner/` | The planner, in Rust: the crate `atlas_transit` and the tool `atlas-transit`. The standard library only; the library itself is core and alloc, with no unsafe code. `src/lib.rs` says what it holds to. |
| `abi/` | The planner behind a C interface: the crate `atlas_transit_abi`, a static library for an app's own shared library, and its header `include/atlas_transit.h`. Only numbers cross it, and every line of unsafe code is here ("The interface", below). |
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
CARGO_TARGET_DIR=~/atlas/var/build/transit-abi ~/.cargo/bin/cargo test --locked --manifest-path app/android/transit/abi/Cargo.toml   # the interface against the planner itself
CARGO_TARGET_DIR=~/atlas/var/build/transit-abi PATH=~/.cargo/bin:$PATH app/android/transit/abi/check.sh   # the interface from C: the release library in a C and a C++ program
```

`python3 -I app/android/ci/affected.py` names the first two and the last two for a change here; `.github/workflows/transit.yml` runs the first three on x86_64 and arm64 Linux at a push, with a million files a little wrong (the interface's two are not in it yet). The tests run on three feeds written by hand, every answer worked out on paper (`planner/tests/data/tiny`; `edges`, the cases a review named: the last connection of an evening, the latest departure, a window's last moment, a vehicle that waits, a stop's own time to change, the night the clock changes, a walk all the way over a window, and the feed's rules between two different stops: a least time, a change in which one stays seated, a line that waits for another, and the one vehicle such a rule holds; and `autumn`, the night the clock goes back and a stop where the feed says nobody changes), and on a piece of the real timetable (the city of Utrecht, two days). The fixed journeys across the country need the whole country's file, so `cargo test` shows that test as ignored; with the file at hand it is `ATLAS_TRANSIT_FILE=<file> cargo test --test fixed -- --ignored`, which fails without the file.

How the planner is held to being right:
- **A second opinion.** `src/reference.rs` is a connection scan over the same file, with nothing of the search in it. When a service day begins (the days the clock changes), what a change at a stop costs, the file's walks turned round, the stops a place stands for with the seconds on foot, and what a wish of "never" closes (from the wish list, a wish and a stop or a ride at a time) it reads by itself, as it holds to the feed's rules between trips in its own way; and it is asked as far as the query's own horizon, whatever the search followed. A point's stops and seconds it measures with a distance of its own, without the search's table: the search's number must lie within what a tenth of a degree of latitude changes (a third of a per cent at Utrecht), and `tests/many.rs` holds both to paper with the feed moved to seven latitudes. What the two still have in common is what a wish means of one thing (whether a point lies in an area, which kind of vehicle a line's type is) and how far a query is followed: `tests/tiny.rs` holds an area to paper at its edge, a metre from a stop on either side, and a station to its stops. The two must agree on the earliest arrival, the latest departure, and every best pair over a window, asked to leave in it or to arrive in it.
- **A vehicle that waits is one vehicle.** Where the feed says a vehicle waits for another (transfer type 1, or 4 for staying seated), the rule holds one vehicle for each arrival: the trip it names, or the first trip of the line it names to leave that stop at or after the arrival, on any service day in reach. That one is boarded whatever the walk or the change takes; every other trip is reached as without the rule, by the file's walk or the stop's time to change, or not at all. The file alone says which vehicle it is: where a wish closes it, none waits. Arriving by, the same read back: a vehicle waits for every arrival since the one before it left. The search looks the vehicle up when it holds the arrival, and holds a route's later trips too where they would be in before the plain change is made (each has another vehicle waiting for it); the scan and `reference::impossible` ask of each pair of rides whether anything else the rule speaks of leaves between the two. `tests/edges.rs` has it on paper: a walk longer than the gap between two buses, a shorter one, one stop, no walk at all.
- **A patient search gives what a whole one gives.** By default a search first follows journeys only as far as one could sensibly take, and goes on only for what could still be an option. `tests/cut.rs` and the tool's `bench` hold its answers equal to those of a search as far as the horizon.
- **A journey leaves when it must.** Asked for one moment, each option's departure is the latest that makes its arrival with no more rides: asked back from that arrival with no more rides than it has, the same departure comes out. That is the planner asked twice, so the scan that counts rides is asked too: from the second after an option's departure it has no way that arrives no later with no more rides, and where its latest way to that arrival leaves later than the option, every such way has more rides by the scan's own count.
- **Two windows give what one gives.** A window holds its last moment, and what counts against a journey is every journey that leaves no earlier, inside the window or after it. So a window cut in two at any second gives the same options as the whole; `tests/cut.rs` cuts each at the moments its options leave. Between two points near enough to walk all the way, the walk is one option of each window and can begin at any moment: a ride that takes no less time than the walk is none, wherever in a window it leaves, and the rides are the same however the window is cut. Whether the points are near enough, and how long the walk takes, is the scan's to say from the coordinates: a near pair without the walk fails, as does a walk between points too far apart, and such a window's rides are the scan's best pairs less those no faster than the walk.
- **Every journey can be made.** `reference::impossible` takes a journey and the file, and checks each ride, walk and change as a rider would meet them. It reads nothing of how the journey was found.
- **Wishes weighed by the rule alone.** An option stays unless another leaves no earlier, arrives no later, has no more rides, and is no later with each one's cost added (`src/query.rs`'s head). The search that weighs prunes by that rule as it goes; `tests/cut.rs` and the tool's `bench` hold it to what the rule leaves of every option, found without weighing. And the cost has a second opinion of its own: the scan works out what a journey's wishes cost from the wish list, a wish and a ride at a time (`reference::wished`, which `impossible` holds every journey to), and carries that cost and a count of rides beside the time in scans of its own, forward and back (`Reference::ways`, `ways_back`). Asked for one moment, an answer is held to them whole (`moment_differs`), leaving at and arriving by, with every option and weighed: its options are the scan's best ways by time, cost and rides, every one and no other (weighed: what the rule leaves of them); each leaves as late as it can, since from the second after its departure the scan has no way that arrives no later at no more cost with no more rides (arriving by: is over as early as it can); and between two points near enough, the walk all the way is judged with the rest, the rides being the ways that are in before it. Nothing is passed over: `tests/cut.rs` counts the judged, 180 of each of the four kinds, 72 of them with a walk. A window with a cost the scan does not judge by its cost: such a window is held by its best pairs of departure and arrival (`window_differs`, whatever they cost), by every journey and its cost (`impossible`), by the rule put to every option, and by two windows giving what one gives.
- **Bounded, and it says so.** A query's `Limits` cap what one answer may cost: the steps, the labels held, the moments searched (those tried for a later departure among them), the labels a stop keeps a round when wishes cost something, and the options (of more than may be shown for one moment the fastest stay; weighed, those that are in first with their cost added). Where a cap is met the answer says `cut_short`, wherever it was met, and never passes for the whole one (`tests/cut.rs`; a stop's bag is 8 unless said, which none of the 240 wished queries of that test fills, on Utrecht). It says so too where a place stands for more stops than a search begins at (4,096, of which the nearest stay): a station of many stops, a point with a wide reach, a long list of walks. And how many rules a file has sets no query's time and loses no rule: `tests/many.rs` gives the hand-written feed tens of thousands more, built in the test, and holds the answers and the time; as it does with thousands of stops more, and with a trip that is twelve days under way, which the format allows (a search reads every service day such a trip could still be running from).
- **No panic from data.** `src/fuzz.rs` makes files a little wrong and queries at the edge of every field; the tests run 6,300, the tool's `fuzz` command as many as asked (`cargo run --profile fuzz -- fuzz <file> <runs>` keeps the overflow checks on).
- **The same everywhere.** A fixed set of answers folds to one number that the tests keep (`tests/cut.rs`). And the files a little wrong give counts (how many still opened, how many journeys they and the queries gave) that `tests/data/fuzz-counts.txt` keeps and the workflow holds each platform to. After a change to the search or to `fuzz.rs`, write them again: `./fuzz-counts.sh > tests/data/fuzz-counts.txt` in `planner/`.

## The interface

`abi/` is what an app links: the planner as a static library behind eight C functions (`include/atlas_transit.h` says what each takes and gives; `src/lib.rs` is the other side). An app's shared library of its own, `libatlastransit.so` beside the core's, will link it; that module is not here yet.

- **Only numbers cross.** A query is an array of 32-bit numbers, the wishes are one, and an answer is one: its head (how many journeys, whether it was cut short), then each journey and its legs. A name is the file's own bytes of UTF-8 and their length, good until the timetable is closed. No structure crosses, so no layout can differ between the two sides, and the header's numbers are held to the library's by a test.
- **A query that cannot be read is refused whole.** A kind of place or wish this library does not know, a wish cut off, an amount below zero: nothing is planned, rather than a journey without the part that was not understood. A number a caller leaves out, or gives as -1, is the planner's own default.
- **Nothing a caller passes does harm.** A null pointer is an empty array or no timetable, a number out of range names nothing, bytes that are no timetable are not opened and the reason is said. What the caller owes is what C always owes: an array as long as it says, the file's bytes left where they are until the timetable is closed, one plan at a time on one timetable (its names and numbers may be read from any thread meanwhile).
- **It stands by itself.** The library is core and alloc alone, built for size, with memory from the C library's `malloc` and a panic that says where and aborts. Linked into a program it asks for nothing but the C library: no unwinder, no C++ runtime, none of Rust's standard library.

Its checks: `cargo test --locked` in `abi/` sends 121,500 fuzzed queries through the interface as numbers and holds every answer equal to the planner's own, reads every stop, line, operator, route and trip of four files through it, opens 4,000 files a little wrong, passes null pointers and buffers too short, and reads names from three threads while a plan runs. `abi/check.sh` builds the release library, links it into a C program that sees only the header (`tests/host.c`) and into a C++ one, holds what the C program says on the hand-written feed to `tests/host.expected`, and fails where the linked program asks for more than the C library has.
