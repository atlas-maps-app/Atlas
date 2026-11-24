#!/bin/sh
# The interface from C: the static library as an app links it (release, core and alloc alone), a C program that sees
# only the header (tests/host.c), and what it says on the hand-written feed held to tests/host.expected.
#   ./check.sh            builds, links, runs, compares
#   ./check.sh --write    writes tests/host.expected anew (read the difference before it is kept)
# It needs cargo and the C and C++ compilers of the machine (cc and c++; CC and CXX choose others): nothing is
# fetched.
set -eu
cd "$(dirname "$0")"
cargo build --locked --release >&2
T="${CARGO_TARGET_DIR:-target}/release"
LIB="$T/libatlas_transit_abi.a"

# the header is C and C++, and the library links into either with nothing beside it
${CC:-cc} -std=c99 -Wall -Wextra -Werror -pedantic -Iinclude tests/host.c "$LIB" -o "$T/atlas-transit-host"
printf '#include "atlas_transit.h"\nint main() { return atlas_transit_abi() == ATLAS_TRANSIT_ABI ? 0 : 1; }\n' > "$T/host.cpp"
${CXX:-c++} -std=c++17 -Wall -Wextra -Werror -pedantic -Iinclude "$T/host.cpp" "$LIB" -o "$T/atlas-transit-host-cpp"
"$T/atlas-transit-host-cpp"

# What the library asks of whoever links it, read off the C program: the C library's memory and a few of its calls
# (and what the program itself calls to read a file and print). Nothing of an unwinder, of the C++ runtime or of
# Rust's standard library: those would be megabytes in the app, or a link that fails there.
# Names as C has them: a Mach-O file puts an underscore before each, an ELF file a version after some.
UNDERSCORE=''
[ "$(uname -s)" = Darwin ] && UNDERSCORE='s/^_//'
ASKS="$(nm -u "$T/atlas-transit-host" | awk '{ print $NF }' | sed -e 's/@.*//' -e "$UNDERSCORE" | sort -u)"
echo "check.sh: the C program and the library in it ask for:" $ASKS >&2
# (__cxa_finalize and __cxa_atexit are the C library's own, for a program's end)
if echo "$ASKS" | grep -E '^(_Unwind|_?_?rust_|_ZN|_R[a-zA-Z0-9]|__cxa|__gxx)' | grep -vE '^__cxa_(finalize|atexit)$' >&2; then
    echo "check.sh: the library asks for more than the C library has (the lines above)" >&2
    exit 1
fi

if [ "${1:-}" = "--write" ]; then
    "$T/atlas-transit-host" ../planner/tests/data/edges.tt > tests/host.expected
    echo "check.sh: wrote tests/host.expected" >&2
else
    "$T/atlas-transit-host" ../planner/tests/data/edges.tt | diff tests/host.expected -
    echo "check.sh: the C program says what tests/host.expected has" >&2
fi
