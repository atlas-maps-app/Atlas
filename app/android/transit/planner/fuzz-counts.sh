#!/bin/sh
# The counts of the files a little wrong, as .github/workflows/transit.yml compares them on each platform:
#   ./fuzz-counts.sh > tests/data/fuzz-counts.txt
# (the run's seconds are taken out of each line; everything else must be the same everywhere)
set -eu
cd "$(dirname "$0")"
cargo build --locked --profile fuzz >&2
T="${CARGO_TARGET_DIR:-target}/fuzz/atlas-transit"
{ "$T" fuzz tests/data/tiny.tt 1000000; "$T" fuzz tests/data/edges.tt 300000; "$T" fuzz tests/data/utrecht-20261117.tt 20000; "$T" fuzz tests/data/autumn.tt 300000; } | sed -E 's/, [0-9.]+ s \(/ (/'
