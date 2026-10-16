#!/usr/bin/env bash
# smoke.sh <apk dir> <out dir>: installs the APK on the running emulator, opens Atlas on each screen the app lists for
# CI (MainActivity's "atlas.screen" extra), and keeps a screenshot, the frame timings and any crash per screen.
set -u
APK=$(ls "$1"/*.apk | head -1); OUT=$2; mkdir -p "$OUT"
adb wait-for-device
# The emulator's own launcher sometimes stalls under the software GPU; its "isn't responding" dialog is not Atlas's.
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1
adb shell settings put global anr_show_background 0 >/dev/null 2>&1
adb install -r -g "$APK" || exit 1
adb logcat -c
SCREENS=$(adb shell am start -W -n app.atlas/.MainActivity --es atlas.screen list >/dev/null 2>&1; sleep 6; adb shell run-as app.atlas cat files/ci_screens.txt 2>/dev/null | tr -d '\r')
[ -n "$SCREENS" ] || SCREENS="home"
# A run by hand may name its own screens, and how long to wait for the map (seconds).
[ -n "${ATLAS_SCREENS:-}" ] && SCREENS="$ATLAS_SCREENS"
WAIT=${ATLAS_MAP_WAIT:-180}
echo "Screens: $SCREENS"
FAIL=0
for s in $SCREENS; do
  adb shell am force-stop app.atlas
  adb shell dumpsys gfxinfo app.atlas reset >/dev/null 2>&1
  SEEN=$(adb logcat -d -s AtlasMap:I | grep -c -e "drawn in" -e "filter probe sblur")
  BEFORE=$(adb logcat -d -s AtlasMap:I | wc -l)
  adb shell am start -W -n app.atlas/.MainActivity --es atlas.screen "$s" --ez atlas.ci true > "$OUT/$s.start.txt"
  sleep 12
  # The home's map is the engine's exact frame of the bundled Westerkerk, and the filters screen runs the SVG filter
  # probes through AGSL: wait (up to WAIT seconds) until the frame is drawn or the last probe is done. The engine's lines
  # (logcat tag AtlasMap: load, render, parse, raster and draw times) are kept per screen, only that screen's.
  if [ "$s" = "home" ] || [ "$s" = "filters" ]; then
    for i in $(seq 1 $((WAIT / 2))); do
      [ "$(adb logcat -d -s AtlasMap:I | grep -c -e "drawn in" -e "filter probe sblur")" -gt "$SEEN" ] && break
      sleep 2
    done
    sleep 2
  fi
  adb logcat -d -s AtlasMap:I | tail -n +$((BEFORE + 1)) > "$OUT/$s.atlasmap.txt"
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
  sleep 1
  adb exec-out screencap -p > "$OUT/$s.png"
  adb shell dumpsys gfxinfo app.atlas > "$OUT/$s.gfxinfo.txt"
  if adb logcat -d -b crash | grep -q "app.atlas"; then echo "CRASH on $s"; adb logcat -d -b crash > "$OUT/$s.crash.txt"; FAIL=1; adb logcat -c; fi
done
adb logcat -d > "$OUT/logcat.txt"
exit $FAIL
