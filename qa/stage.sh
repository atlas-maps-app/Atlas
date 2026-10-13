#!/bin/zsh
# stage.sh snap|pages <dir>|preview <dir>|reset — copies what a local server needs into ~/atlas-stage/<name>.
# The desktop app's preview servers cannot read ~/Documents (macOS privacy), so .claude/launch.json serves
# ~/atlas-stage instead. This folder is a copy: edit in the project, then stage again.
set -e
A=${0:A:h:h}; S=$HOME/atlas-stage
case "$1" in
  snap)   # boards of both canvases + their images + the snapshot server; snapshots are saved to $S/snap/snaps
    mkdir -p $S/snap/snaps
    rsync -a $A/canvas/project/*.dc.html $A/engine/project/*.dc.html $S/snap/
    [ -f $A/canvas/project/support.js ] && cp $A/canvas/project/support.js $S/snap/ || cp $A/studio/snap/support.js $S/snap/
    rsync -a $A/cache/blob/ $S/snap/
    cp $A/studio/snap/srv.py $S/snap/srv.py
    echo "staged $S/snap — start atlas-snap; copy new snaps back: rsync -a $S/snap/snaps/ $A/studio/snap/snaps/" ;;
  pages|preview)
    [ -n "$2" ] || { echo "usage: stage.sh $1 <dir to serve>"; exit 1; }
    mkdir -p $S/$1 && rsync -a --delete "$2"/ $S/$1/ && echo "staged $S/$1 from $2" ;;
  reset)  # the Claude reset page, served by the pane's reset-claude entry as a dry run
    cp $HOME/Documents/reset-claude.py $S/reset-claude.py && echo "staged $S/reset-claude.py — start reset-claude (dry run); for real: python3 ~/Documents/reset-claude.py" ;;
  *) echo "usage: stage.sh snap | pages <dir> | preview <dir> | reset"; exit 1 ;;
esac
