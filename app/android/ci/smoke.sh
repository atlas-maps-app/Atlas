#!/usr/bin/env bash
# smoke.sh <apk dir> <out dir>: installs the APK on the running emulator, opens Atlas on each screen the app lists for
# CI (MainActivity's "atlas.screen" extra), and keeps a screenshot, the frame timings and any crash per screen.
set -u
APK=$(ls "$1"/*.apk | head -1); OUT=$2; mkdir -p "$OUT"
adb wait-for-device
adb install -r -g "$APK" || exit 1
adb logcat -c
SCREENS=$(adb shell am start -W -n app.atlas/.MainActivity --es atlas.screen list >/dev/null 2>&1; sleep 6; adb shell run-as app.atlas cat files/ci_screens.txt 2>/dev/null | tr -d '\r')
[ -n "$SCREENS" ] || SCREENS="home"
echo "Screens: $SCREENS"
FAIL=0
for s in $SCREENS; do
  adb shell am force-stop app.atlas
  adb shell dumpsys gfxinfo app.atlas reset >/dev/null 2>&1
  adb shell am start -W -n app.atlas/.MainActivity --es atlas.screen "$s" --ez atlas.ci true > "$OUT/$s.start.txt"
  sleep 12
  adb exec-out screencap -p > "$OUT/$s.png"
  adb shell dumpsys gfxinfo app.atlas > "$OUT/$s.gfxinfo.txt"
  if adb logcat -d -b crash | grep -q "app.atlas"; then echo "CRASH on $s"; adb logcat -d -b crash > "$OUT/$s.crash.txt"; FAIL=1; adb logcat -c; fi
done
adb logcat -d > "$OUT/logcat.txt"
exit $FAIL
