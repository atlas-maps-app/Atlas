#!/bin/zsh
# shots.sh [PAGE] [EXPORT] [SHOTDIR]: screenshots of every tab, the large view (#screen=), and the ⋯ panels, at 375×812 and 1280×900,
# light and dark, into SHOTDIR (default studio/shots), with small copies of the studio's own pictures.
here=${0:A:h}; atlas=${here:h:h:h}
PAGE=${1:-$atlas/studio/studio.html}; EXP=${2:-$atlas/qa/pagetest/export}; SH=${3:-$atlas/cache/pagetest/studio/shots}
H=${TMPDIR:-/tmp}/atlas_studio_test/shots; T=${TMPDIR:-/tmp}/atlas_studio_test/snap; mkdir -p $SH $T
python3 $here/mkstudio.py $PAGE $EXP $H --pics >/dev/null || exit 1
for size in "375 812" "1280 900"; do for mode in light dark; do
  tag=${size%% *}_$mode; files=()
  for tab in words prompt goodpage screens sourcespage inbox; do sed -e "s#__TAB__#$tab#; s#__OUT__#$SH/${tab}_$tag.png#" $here/snap/tab.js > $T/${tab}_$tag.js; files+=($T/${tab}_$tag.js); done
  sed -e "s#__OUT__#$SH/more_$tag.png#" $here/snap/more.js > $T/more_$tag.js; files+=($T/more_$tag.js)
  sed -e "s#__OUT__#$SH/focus_$tag.png#" $here/snap/focus.js > $T/focus_$tag.js
  sed -e "s#__OUT__#$SH/focusengine_$tag.png#" $here/snap/focus.js > $T/focusengine_$tag.js
  { $here/run.sh $H/edit.html ${=size} $mode $files; $here/run.sh $H/screen.html ${=size} $mode $T/focus_$tag.js; $here/run.sh $H/engine.html ${=size} $mode $T/focusengine_$tag.js } &
done; done; wait
ls $SH
