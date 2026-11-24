#!/bin/sh
# MEASURE (budgetcheck-measure, never to land): ci-builds.md's numbers. The test binary's size raw and packed as
# upload-artifact packs it (zip at level 6, so gzip -6) and as zstd would, each with its time, and what else of the
# build folder a shard would need from a build job.
f=$1
b=$2
now() { date +%s.%N; }
took() { awk -v a="$1" -v b="$2" 'BEGIN { printf "%.1f", b - a }'; }
raw=$(wc -c < "$f")
t0=$(now); gz=$(gzip -6 -c "$f" | wc -c); t1=$(now)
zs=none; zt=0
if command -v zstd > /dev/null 2>&1; then t2=$(now); zs=$(zstd -3 -T0 -q -c "$f" | wc -c); t3=$(now); zt=$(took "$t2" "$t3"); fi
echo "MEASURE atlas_tests raw $raw bytes, gzip -6 $gz bytes in $(took "$t0" "$t1") s, zstd -3 $zs bytes in $zt s"
dbg=none
if objcopy --only-keep-debug "$f" "$b/measure.debug" 2> /dev/null; then dbg=$(wc -c < "$b/measure.debug"); rm -f "$b/measure.debug"; fi
echo "MEASURE atlas_tests debug sections $dbg bytes"
echo "MEASURE build folder $(du -sb "$b" | cut -f1) bytes; tests/ $(du -sb "$b/tests" 2> /dev/null | cut -f1); scratch $(du -sb "$b/scratch" 2> /dev/null | cut -f1); host-tools $(du -sb "$b/host-tools" 2> /dev/null | cut -f1); CMakeFiles $(du -sb "$b/CMakeFiles" 2> /dev/null | cut -f1)"
