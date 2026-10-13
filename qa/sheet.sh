#!/bin/zsh
setopt NO_BG_NICE
# sheet.sh <outdir> <board[:state]>... : freezes the boards and renders one Quick Look contact sheet (sheet.html.png)
OUT=$1; shift
rm -rf "${OUT:?}"; python3 "${0:A:h}/freeze.py" $OUT "$@" >/dev/null
cd $OUT && (qlmanage -t -s 2048 -o . sheet.html >/dev/null 2>&1 &)
for i in $(seq 1 40); do [ -f $OUT/sheet.html.png ] && break; sleep 1; done
ls -la $OUT/sheet.html.png
