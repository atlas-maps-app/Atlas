#!/bin/zsh
# all.sh [PAGE] [EXPORT]: the studio's checks against an export (default: the export beside the tests), at 375×812 and 1280×900,
# light and dark. Prints each test's result line; check.py reads them and prints "== Studio checks: N passed, M failed".
here=${0:A:h}; atlas=${here:h:h:h}
PAGE=${1:-$atlas/studio/studio.html}; EXP=${2:-$atlas/qa/pagetest/export}
H=${TMPDIR:-/tmp}/atlas_studio_test/new; R=${TMPDIR:-/tmp}/atlas_studio_test/res; mkdir -p $R
python3 $here/mkstudio.py $PAGE $EXP $H >/dev/null || exit 1
T=$here/tests
for size in "375 812" "1280 900"; do for mode in light dark; do
  tag=${size// /x}_$mode
  { $here/run.sh $H/edit.html ${=size} $mode $T/smoke.js $T/words.js $T/rate.js $T/improve.js $T/parts.js $T/screens_ui.js > $R/edit_$tag.txt
    for h in screen engine screen404; do $here/run.sh $H/$h.html ${=size} $mode $T/hash.js > $R/${h}_$tag.txt; done
    for h in nodb failwrite; do $here/run.sh $H/$h.html ${=size} $mode $T/readonly.js > $R/${h}_$tag.txt; done } &
  { $here/run.sh $H/edit.html ${=size} $mode $T/inbox.js $T/words_more.js $T/engine.js $T/hash_more.js $T/good.js > $R/more_$tag.txt
    $here/run.sh $H/empty.html ${=size} $mode $T/empty.js > $R/empty_$tag.txt
    $here/run.sh $H/claude.html ${=size} $mode $T/claude.js > $R/claude_$tag.txt } &
done; done; wait
python3 $here/check.py $R
