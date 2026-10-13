#!/bin/zsh
# run.sh <harness.html> <width> <height> <light|dark> <test.js> [test.js ...]
# Runs each test in the harness with the studio's prelude (prelude.js here); prints what each test returns.
here=${0:A:h}
H=$1 W=$2 HT=$3 M=$4; shift 4
PAGETEST_PRELUDE=$here/prelude.js exec ${here:h}/runner $H $W $HT $M "$@"
