#!/usr/bin/env bash
# smoke.sh <apk dir> <out dir> [<bench apk dir>]: installs the APK on the running emulator, opens Atlas on each screen the
# app lists for CI (MainActivity's "atlas.screen" extra), and keeps a screenshot, the frame timings and any crash per screen.
#
# With the bench APK (the non-debuggable, profileable build with the timings overlay, see app/build.gradle.kts) it then
# measures the engine the way a phone runs it, and keeps what the plan's stage S0 asks for:
#   home.atlasmap.txt, filters.atlasmap.txt  a table of each run's first frame (load, render, parse, raster; the time to the
#                                            frame on screen; CPU at rest; memory peaks), then every run's AtlasMap lines.
#                                            Runs: the debug build at its first launch, then debug and bench, each as
#                                            installed and after `cmd package compile -m speed -f app.atlas`
#   <screen>.<run>.mem.txt                   that run's memory (polled dumpsys meminfo, ART's GC lines, the kernel's
#                                            VmHWM) and, for the home, what the app does at rest after its first frame
#   home.simpleperf.txt                      a simpleperf profile of the bench build's first home frame, split into Skia,
#                                            filter primitives and Turbulence (ci/perfsplit.py)
#   glcaps.txt                               the device's facts and OpenGL ES capabilities (the glcaps screen)
#   pills-*.png, pills*.txt                  the core's frosted pills (K11b): VK bench's still pills with the core's glass
#                                            and with their tint alone, judged by ci/pillcheck.py; the home on both hosts
#                                            (still pills only: the moving check is the phone's)
#   fade.txt                                 whether this API level's SurfaceView follows the alpha of a layer over it (a
#                                            screen's fade, NavDisplay's): VK bench's fade probe, ci/pillcheck.py fade
#   vkfacts.txt, vkbench.txt, vkguard.txt    the Vulkan core (plan-vector K1, Appendix C): VK facts through the guest's
#                                            Vulkan, VK bench in CI's length (the stand-in in every configuration the
#                                            guest has, both hosts, the app's map at its real views, the pills, the
#                                            colour stage with the app sent to the background and brought back in it;
#                                            then the crash guard's marker gone and VK facts' guard clear), and the
#                                            crash guard's planted crash ending in the honest state on the next launch;
#                                            pymath.txt, the core's numerics on Bionic (K3b), which must read `pymath
#                                            $ATLAS_PYMATH_HASH ok`; sun.txt, the core's sun bits on Bionic (K8), which
#                                            must read `sun $ATLAS_SUN_HASH ok`; fields.txt, the core's noise fields at
#                                            S1 on Bionic (K17a), which must end `hash <h> ok` (each field equal to the
#                                            port's); ground.txt, the ground's stage T on Bionic (K9), which must read
#                                            `ground $ATLAS_GROUND_HASH ok`; twin.txt, the core's twin on Bionic, which
#                                            must read `twin $ATLAS_TWIN_HASH ok`; and in vkfacts.txt the core's shaping held
#                                            to the guest's own (K13), `text.glyphs: <n> runs, <m> glyphs equal
#                                            Android's (rule <rule>)`
#   map.txt, map-transition.txt, map-*.png   the map drawn by the core (K11): its first frame from a cleared state and
#                                            warm, a swipe and the rest after it, the Java heap at the canal ring, the
#                                            transitional rule's switch away from the core's tiles and back, and the
#                                            honest state under atlas.vk.off
# Each measured run starts from the installed state (`pm clear`, the install's permissions granted again), so earlier
# screens' downloads and kept scenes do not change what the home draws. `run-as` needs a debuggable APK, so the bench
# phase never uses it: the screen list comes from the debug run and the timings from logcat (tag AtlasMap).
#
# Fails (exit 1) on a crash (a native one symbolised with ndk-stack from the APK's own unstripped libraries), "a frame
# failed", "out of memory", "device lost", the emulator gone for 5 minutes ("DEVICE GONE"), a `vk guard` line outside the planted-crash case (a marker the guard
# discarded because its process was stopped, not crashed, `vk guard: … not counted`, is reported, not failed), a guest
# without a Vulkan device, a VK bench that does not finish within 900 s, exceeds configuration C's graphics ceiling,
# loses its window in the colour stage for good, leaves the guard's marker behind, has a colour stage that ended early
# (its window or the app not back), a host's window that never came, a colour stage that drew no frames, or one that
# drew none once it went on after the app came back, an app that did not come back to the foreground after the colour
# stage's background step, a pymath line other than `pymath $ATLAS_PYMATH_HASH ok`, a sun line other than
# `sun $ATLAS_SUN_HASH ok`, a fields line other than `fields 1080x2400 cpu <ms> gpu <ms>|off (<why>) hash <h> ok`, a ground line
# other than `ground $ATLAS_GROUND_HASH ok`, a twin line other than `twin $ATLAS_TWIN_HASH ok`, VK facts without
# `text.rounding: floor|round (probe …)` or with a `text.glyphs:` line that is not every glyph equal, a composite check
# apart off the diagonal or not run, VK bench's still pills failing ci/pillcheck.py with the core's glass or passing it
# with their tint alone, (once the map's core draws the home) the home's frosted pills failing
# it or further than MAD 2 from HWUI's on the TextureView, the core's first map frame above §3's emulator limits (3 s
# first, 1.5 s warm), a map frame from 4 s after a swipe, a Java heap above 96 MB at the canal ring, a transitional
# switch without its lines, or no honest state under atlas.vk.off. A frame that never comes is "NO FRAME", a
# warning; a background step the bench ended before is skipped, saying so.
# Environment: ATLAS_SCREENS (the debug loop's screens, default: all), ATLAS_MAP_WAIT (seconds to wait for the home's map),
# ATLAS_IDLE (seconds the home is watched at rest, default 20), ATLAS_BENCH=0 (skip the bench phase), ATLAS_PROFILE=0
# (skip simpleperf), ATLAS_PYMATH_HASH (the numerics hash the host build and the port compute), ATLAS_SUN_HASH (the sun
# bits' hash the host build computes), ATLAS_GROUND_HASH (the ground's stage T hash the host build computes),
# ATLAS_TWIN_HASH (the twin check's hash the host build computes): each as ci/hashes.env holds it, unless the
# environment sets another; ATLAS_PACKS (a folder of render packs put on the device for the debug and bench builds to
# draw: the canal ring, android.yml's build job's :app:benchTiles, as no APK carries a map).
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
# The hashes the emulator must reproduce, each the host build's: ci/hashes.env's (outside the workflows, so a change of
# the engine's that moves one edits no workflow file), where the environment sets none.
for key in ATLAS_PYMATH_HASH ATLAS_SUN_HASH ATLAS_GROUND_HASH ATLAS_TWIN_HASH; do
  [ -n "${!key:-}" ] || export "$key=$(sed -n "s/^$key=\([0-9a-f]\{16\}\)\$/\1/p" "$HERE/hashes.env")"
done
APK=$(ls "$1"/*.apk | head -1); OUT=$2; mkdir -p "$OUT"
BENCH_APK=""
[ -n "${3:-}" ] && BENCH_APK=$(ls "$3"/*.apk 2>/dev/null | head -1)
PKG=app.atlas
WAIT=${ATLAS_MAP_WAIT:-180}
IDLE=${ATLAS_IDLE:-20}
# `timeout` is coreutils (the CI runner has it); a Mac may not.
LIMIT=""; command -v timeout >/dev/null 2>&1 && LIMIT="timeout 900"
FAIL=0

# The device's watchdog. An emulator that dies leaves every later adb call waiting for it ("- waiting for device -") until
# the job's own timeout: on 28 Oct (7f28e5d) it went offline at the walk screen and the job waited 110 minutes. Gone for
# 5 minutes (adbd's own restarts take seconds), the run ends here, failed. Where `timeout` is missing, there is none.
if [ -n "$LIMIT" ]; then
  (
    nap=""; trap 'kill $nap 2>/dev/null; exit 0' TERM
    seen=$SECONDS
    while :; do
      sleep 30 & nap=$!; wait $nap
      # into a file, not a pipe: a process an adb that timed out left behind would hold the pipe open
      timeout 20 adb get-state > "$OUT/.device" 2>/dev/null
      [ "$(cat "$OUT/.device")" = device ] && seen=$SECONDS
      if [ $((SECONDS - seen)) -ge 300 ]; then
        echo "DEVICE GONE: no device for $((SECONDS - seen)) s"; timeout 20 adb devices -l > "$OUT/.device" 2>&1; sed 's/^/  /' "$OUT/.device"
        kill -TERM $$; exit 0
      fi
    done
  ) &
  WATCHDOG=$!
  trap 'kill $WATCHDOG 2>/dev/null' EXIT
fi

# --- helpers ---------------------------------------------------------------------------------------------------------

# Installs APK $1 over what is there, or, signed with another key, in its place.
install_apk() { adb install -r -g "$1" || { adb uninstall $PKG >/dev/null 2>&1; adb install -g "$1"; }; }

# The runtime permissions the install granted (-g); `pm clear` takes them back, and a fresh run grants them again.
PERMS=""
read_perms() { PERMS=$(adb shell dumpsys package $PKG 2>/dev/null | tr -d '\r' | grep -o 'android\.permission\.[A-Z_]*: granted=true' | sed 's/: granted=true//' | sort -u); }
fresh_state() {
  adb shell pm clear $PKG >/dev/null 2>&1
  local p; for p in $PERMS; do adb shell pm grant $PKG "$p" >/dev/null 2>&1; done
}

# The AtlasMap lines logged since marker $1 (threadtime format: date, time, pid, tid, level, tag, message).
since_marker() { adb logcat -d -v threadtime -s AtlasMap:I | tr -d '\r' | sed -n "/run-marker $1\$/,\$p"; }
# every tag since the marker (the system's ANR lines are not under AtlasMap)
since_marker_all() { adb logcat -d -v threadtime | tr -d '\r' | sed -n "/run-marker $1\$/,\$p"; }

# How many finished frames (or the last filter probe) are logged since marker $1: today's map's, or the core's first.
frames_since() { since_marker "$1" | grep -c -e "drawn in" -e "filter probe sblur" -e "vk first frame"; }

# Seconds from marker $1 (logged just before the app is started) to the first frame on screen (the filters: the last probe).
drawn_after() {
  since_marker "$1" | awk 'function t(s,  a) { split(s, a, ":"); return a[1] * 3600 + a[2] * 60 + a[3] }
    NR == 1 { t0 = t($2) } / drawn in |filter probe sblur|vk first frame/ { d = t($2) - t0; if (d < 0) d += 86400; printf "%.1f", d; exit }'
}

# The larger of two numbers; a non-number counts as 0.
maxnum() { local a=$1 b=$2; [[ $a =~ ^[0-9]+$ ]] || a=0; [[ $b =~ ^[0-9]+$ ]] || b=0; echo $(( a > b ? a : b )); }

PEAK_PSS=0; PEAK_JAVA=0; PEAK_ALLOC=0; PEAK_NATIVE=0; PEAK_GFX=0
# One look at the app's memory (KB), keeping the peaks. The Dalvik Heap row's Heap Alloc column (next to last) is the
# Java objects alive: what the plan's 384 MB heap budget is about.
mem_poll() {
  local out; out=$(adb shell dumpsys meminfo $PKG 2>/dev/null | tr -d '\r')
  PEAK_PSS=$(maxnum "$PEAK_PSS" "$(echo "$out" | awk '/TOTAL PSS:/ {print $3; exit}')")
  PEAK_JAVA=$(maxnum "$PEAK_JAVA" "$(echo "$out" | awk '/Java Heap:/ {print $3; exit}')")
  PEAK_ALLOC=$(maxnum "$PEAK_ALLOC" "$(echo "$out" | awk '$1 == "Dalvik" && $2 == "Heap" {print $(NF-1); exit}')")
  PEAK_NATIVE=$(maxnum "$PEAK_NATIVE" "$(echo "$out" | awk '/Native Heap:/ {print $3; exit}')")
  PEAK_GFX=$(maxnum "$PEAK_GFX" "$(echo "$out" | awk '/Graphics:/ {print $2; exit}')")
}

# Waits (up to $WAIT seconds) until a frame is drawn since marker $1, polling the memory unless $2 is "quiet" (the
# profile's run, where dumpsys would show up in the samples). Sets FRAME_DONE.
wait_frame() {
  FRAME_DONE=0
  local i
  for i in $(seq 1 $((WAIT / 2))); do
    if [ "$(frames_since "$1")" -gt 0 ]; then FRAME_DONE=1; break; fi
    [ "${2:-}" != quiet ] && [ $((i % 3)) -eq 0 ] && mem_poll
    sleep 2
  done
  [ "${2:-}" != quiet ] && mem_poll
}

# ART's GC lines of process $1: how many, and the largest heap in use after one ("287MB/383MB").
gc_summary() {
  adb logcat -d --pid="$1" 2>/dev/null | tr -d '\r' | grep "GC freed" | grep -o '[0-9]*MB/[0-9]*MB' |
    awk -F'[M/]' '{ n++; if ($1 + 0 > u) { u = $1 + 0; t = $3 + 0 } }
      END { if (n) printf "%d logged GCs; the largest heap in use after one: %d MB of %d MB", n, u, t; else printf "no GC logged" }'
}

# The CPU clock ticks process $1 has used (utime + stime), and each live thread's ("tid|name|ticks").
cpu_ticks() { adb shell cat /proc/"$1"/stat 2>/dev/null | tr -d '\r' | sed 's/.*) //' | awk '{print $12 + $13}'; }
thread_ticks() {
  adb shell "cat /proc/$1/task/*/stat" 2>/dev/null | tr -d '\r' | sed -E 's/^([0-9]+) \((.*)\) (.*)$/\1|\2|\3/' |
    awk -F'|' '{ n = split($3, f, " "); if (n >= 13) print $1 "|" $2 "|" f[12] + f[13] }'
}
CLK_TCK=100

# What the app does with the map at rest: CPU over $IDLE seconds after its first frame (of one core; a map at rest should
# cost next to nothing, and a busy one heats a phone), how many more frames it made, and the busiest threads.
# $1 pid, $2 marker. Sets REST_CPU, REST_FRAMES and REST_LINE.
rest_check() {
  local pid=$1 label=$2 a b f0 f1 busy
  REST_CPU="?"; REST_FRAMES="?"; REST_LINE="at rest: no process"
  [ -n "$pid" ] || return
  f0=$(since_marker "$label" | grep -c -E ': load [0-9]+ ms, render')
  a=$(cpu_ticks "$pid"); thread_ticks "$pid" > "$OUT/.threads0"
  sleep "$IDLE"
  b=$(cpu_ticks "$pid"); thread_ticks "$pid" > "$OUT/.threads1"
  f1=$(since_marker "$label" | grep -c -E ': load [0-9]+ ms, render')
  [[ $a =~ ^[0-9]+$ && $b =~ ^[0-9]+$ ]] || { REST_LINE="at rest: the process's CPU time could not be read"; return; }
  REST_CPU=$(awk -v d=$((b - a)) -v hz=$CLK_TCK -v s="$IDLE" 'BEGIN { printf "%.1f%%", 100 * d / hz / s }')
  REST_FRAMES=$((f1 - f0))
  busy=$(awk -F'|' -v hz=$CLK_TCK 'NR == FNR { a[$1] = $3; next } { d = $3 - (($1 in a) ? a[$1] : 0); if (d > 0) printf "%d|%s\n", d * 1000 / hz, $2 }' \
    "$OUT/.threads0" "$OUT/.threads1" | sort -t'|' -k1,1nr | head -5 | awk -F'|' '{ printf "%s%s %d ms", (NR > 1 ? ", " : ""), $2, $1 }')
  REST_LINE="at rest for ${IDLE} s after the first frame: CPU $REST_CPU of one core, $REST_FRAMES more frames made; busiest threads: ${busy:-none}"
  rm -f "$OUT/.threads0" "$OUT/.threads1"
}

# Opens screen $1 as run $2 (such as "bench-speed"), waits for it, and keeps its logcat lines, screenshot and memory: files
# <screen>.<run>.atlasmap.txt, .png, .gfxinfo.txt and (home, filters) .mem.txt. $3 = "fresh" starts from the installed
# state. The home's map is the Westerkerk, drawn from the canal ring put on the device (ATLAS_PACKS), and the filters
# screen runs the SVG filter probes through AGSL: those two wait until the frame is drawn or the last probe is done. The
# logcat marker is "<run>-<screen>", which must be unique to this call.
measure() {
  local s=$1 run=$2 label="$2-$1" pid drawn
  adb shell am force-stop $PKG
  [ "${3:-}" = fresh ] && fresh_state
  adb shell dumpsys gfxinfo $PKG reset >/dev/null 2>&1
  adb shell log -t AtlasMap "run-marker $label"
  PEAK_PSS=0; PEAK_JAVA=0; PEAK_ALLOC=0; PEAK_NATIVE=0; PEAK_GFX=0
  adb shell am start -W -n $PKG/.MainActivity --es atlas.screen "$s" --ez atlas.ci true > "$OUT/$s.start.txt"
  sleep 12
  if [ "$s" = "home" ] || [ "$s" = "filters" ]; then
    wait_frame "$label"
    [ "$FRAME_DONE" = 1 ] || echo "NO FRAME on $s ($run) within ${WAIT}s"
    sleep 2
    pid=$(adb shell pidof $PKG | tr -d '\r' | awk '{print $1}')
    REST_CPU="-"; REST_FRAMES="-"; REST_LINE=""
    [ "$s" = home ] && [ "$FRAME_DONE" = 1 ] && rest_check "$pid" "$label"
    drawn=$(drawn_after "$label")
    {
      echo "run: $run, screen: $s, pid: ${pid:-none}"
      echo "summary: drawn_s=${drawn:-none} rest_cpu=$REST_CPU rest_frames=$REST_FRAMES heap_mb=$((PEAK_ALLOC / 1024)) pss_mb=$((PEAK_PSS / 1024)) hwm_mb=$(adb shell cat /proc/${pid:-0}/status 2>/dev/null | awk '/^VmHWM/ {printf "%d", $2 / 1024}')"
      echo "first frame on screen (filters: the last probe done) ${drawn:-never} s after the start"
      [ -n "$REST_LINE" ] && echo "$REST_LINE"
      echo "polled peaks (KB, every ~6 s): dalvik heap alloc $PEAK_ALLOC, java heap pss $PEAK_JAVA, native heap pss $PEAK_NATIVE, graphics $PEAK_GFX, total pss $PEAK_PSS"
      [ -n "$pid" ] && echo "ART: $(gc_summary "$pid")"
      [ -n "$pid" ] && adb shell cat /proc/"$pid"/status 2>/dev/null | tr -d '\r' | grep -e '^VmHWM' -e '^VmRSS' -e '^VmSwap' -e '^Threads'
      echo "device heap: heapgrowthlimit $(adb shell getprop dalvik.vm.heapgrowthlimit | tr -d '\r'), heapsize (largeHeap) $(adb shell getprop dalvik.vm.heapsize | tr -d '\r')"
      echo "--- dumpsys meminfo after the frame ---"
      adb shell dumpsys meminfo $PKG 2>/dev/null | tr -d '\r'
    } > "$OUT/$s.$run.mem.txt"
  fi
  since_marker "$label" > "$OUT/$s.$run.atlasmap.txt"
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
  sleep 1
  adb exec-out screencap -p > "$OUT/$s.$run.png"
  adb shell dumpsys gfxinfo $PKG > "$OUT/$s.$run.gfxinfo.txt"
  # The stage fails on a failed frame, an out-of-memory or a crash.
  if grep -q -e "a frame failed" -e "out of memory" -e "device lost" "$OUT/$s.$run.atlasmap.txt"; then
    echo "FRAME FAILED on $s ($run)"; grep -e "a frame failed" -e "out of memory" -e "device lost" "$OUT/$s.$run.atlasmap.txt" | head -3; FAIL=1
  fi
  # The crash guard speaks only when a driver crashed (Appendix C): outside the planted case, a failure. A marker it
  # discarded because its process was stopped (`vk guard: … not counted`; a force-stop between runs) is reported only.
  if grep "AtlasMap: vk guard" "$OUT/$s.$run.atlasmap.txt" | grep -q -v ' not counted$'; then
    echo "VK GUARD on $s ($run)"; grep "AtlasMap: vk guard" "$OUT/$s.$run.atlasmap.txt" | head -3; FAIL=1
  elif grep -q "AtlasMap: vk guard" "$OUT/$s.$run.atlasmap.txt"; then
    echo "vk guard on $s ($run): $(grep -m1 -o 'vk guard: .*' "$OUT/$s.$run.atlasmap.txt")"
  fi
  if adb logcat -d -b crash | grep -q "$PKG"; then
    echo "CRASH on $s ($run)"; adb logcat -d -b crash > "$OUT/$s.$run.crash.txt"; FAIL=1
    native_trace "$OUT/$s.$run.crash.txt" "$OUT/$s.$run.native.txt"
    adb logcat -c
  fi
  # An ANR (the main thread held for 5 s) fails the stage too, with the main thread's stack from /data/anr (root on the
  # Google APIs image), so the cause is in the artifacts.
  if since_marker_all "$label" | grep -q "ANR in $PKG"; then
    echo "ANR on $s ($run)"; FAIL=1
    adb root >/dev/null 2>&1; adb wait-for-device
    adb shell 'ls -t /data/anr/ 2>/dev/null | head -1' | tr -d '\r' | while read f; do
      [ -n "$f" ] && adb shell "cat /data/anr/$f" > "$OUT/$s.$run.anr.txt" 2>/dev/null
    done
    [ -s "$OUT/$s.$run.anr.txt" ] && sed -n '/"main"/,/^$/p' "$OUT/$s.$run.anr.txt" | head -40
  fi
}

# A native crash's trace (a tombstone in the crash buffer, $1), symbolised by the NDK's ndk-stack against the installed
# APK's own libraries (debug and bench builds keep their symbols), into $2; nothing when the crash was Java's.
native_trace() {
  grep -q -e "Fatal signal" -e "backtrace:" "$1" 2>/dev/null || return 0
  echo "NATIVE CRASH: $(grep -m1 -o 'Fatal signal [^,]*' "$1")"
  local ndk=${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}} abi dir
  abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
  dir="$OUT/.symbols/$abi"
  mkdir -p "$dir"
  [ -f "$dir/libatlasmap.so" ] || unzip -q -o -j "$APK" "lib/$abi/*.so" -d "$dir" 2>/dev/null
  if [ -n "$ndk" ] && [ -x "$ndk/ndk-stack" ]; then
    "$ndk/ndk-stack" -sym "$dir" -i "$1" > "$2" 2>&1 && sed -n '1,40p' "$2"
  else
    echo "  (no ndk-stack: the raw backtrace is in $1)"
  fi
}

# Waits up to $2 seconds for a line matching $3 among the AtlasMap lines since marker $1; 0 when it came.
wait_line() {
  local i
  for i in $(seq 1 "$2"); do since_marker "$1" | grep -q -e "$3" && return 0; sleep 1; done
  return 1
}

# --- the debug loop: every screen, at the debug build's first launch ---------------------------------------------------

adb wait-for-device
# The emulator's own launcher sometimes stalls under the software GPU; its "isn't responding" dialog is not Atlas's.
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1
adb shell settings put global anr_show_background 0 >/dev/null 2>&1
# A long run writes more than the default log buffer holds.
adb logcat -G 16M >/dev/null 2>&1
install_apk "$APK" || exit 1
# The map the runs draw at the Westerkerk home (the home's frames, VK bench's map): the APK carries none (ENGINE.md
# step 3, one pack per country) and CI's run builds no windows, so the canal ring goes where the debug and bench builds
# list render packs for the map (MainActivity.BENCH_PACKS), which neither a reinstall nor `pm clear` empties.
if [ -n "${ATLAS_PACKS:-}" ] && ls "$ATLAS_PACKS"/*.atlas > /dev/null 2>&1; then
  adb shell mkdir -p /data/local/tmp/atlas
  adb push "$ATLAS_PACKS"/*.atlas /data/local/tmp/atlas/ > /dev/null
  adb shell chmod 755 /data/local/tmp/atlas
  adb shell chmod 644 '/data/local/tmp/atlas/*.atlas'
  echo "Packs for the map: $(adb shell ls -l /data/local/tmp/atlas | tr -d '\r' | awk 'NF > 4 { printf "%s (%s bytes) ", $NF, $5 }')"
else
  echo "WARNING: no packs for the map (ATLAS_PACKS): the home and VK bench's map have nothing to draw"
fi
adb logcat -c
CLK_TCK=$(adb shell getconf CLK_TCK 2>/dev/null | tr -d '\r'); [[ $CLK_TCK =~ ^[0-9]+$ ]] || CLK_TCK=100
SCREENS=$(adb shell am start -W -n $PKG/.MainActivity --es atlas.screen list >/dev/null 2>&1; sleep 6; adb shell run-as $PKG cat files/ci_screens.txt 2>/dev/null | tr -d '\r')
# The list run leaves its process behind (it finishes during the splash screen); a first launch starts a fresh one.
adb shell am force-stop $PKG
[ -n "$SCREENS" ] || SCREENS="home"
# A run by hand may name its own screens.
[ -n "${ATLAS_SCREENS:-}" ] && SCREENS="$ATLAS_SCREENS"
echo "Screens: $SCREENS"

for s in $SCREENS; do
  measure "$s" debug-first
  # Per screen, as before: the lines, the screenshot, the gfxinfo (home and filters also keep the run's own files).
  cp "$OUT/$s.debug-first.atlasmap.txt" "$OUT/$s.atlasmap.txt"
  mv "$OUT/$s.debug-first.png" "$OUT/$s.png"
  mv "$OUT/$s.debug-first.gfxinfo.txt" "$OUT/$s.gfxinfo.txt"
  [ -f "$OUT/$s.debug-first.crash.txt" ] && mv "$OUT/$s.debug-first.crash.txt" "$OUT/$s.crash.txt"
  if [ "$s" != home ] && [ "$s" != filters ]; then rm -f "$OUT/$s.debug-first.atlasmap.txt"; fi
done
adb logcat -d > "$OUT/logcat.txt"

# --- the Vulkan core: VK facts, VK bench, the crash guard (plan-vector K1, Appendix C) --------------------------------

# A launch of the core's screens: $1 the marker, then the activity's extras.
vk_launch() {
  local label=$1; shift
  adb shell am force-stop $PKG
  adb shell log -t AtlasMap "run-marker $label"
  adb shell am start -W -n $PKG/.MainActivity "$@" > /dev/null
}

# The crash buffer after a run: a crash fails (and is symbolised), unless $2 says it was planted.
vk_crashes() {
  if adb logcat -d -b crash | grep -q "$PKG"; then
    adb logcat -d -b crash > "$OUT/$1.crash.txt"
    if [ "${2:-}" = planted ]; then
      echo "planted crash ($1): $(grep -m1 -o 'Abort message: .*' "$OUT/$1.crash.txt" || echo 'the process died')"
    else
      echo "CRASH on $1"; FAIL=1
      native_trace "$OUT/$1.crash.txt" "$OUT/$1.native.txt"
    fi
    adb logcat -b crash -c
  fi
}

# An ANR of the app since marker $1 fails (the system's lines are not under AtlasMap).
vk_anr() {
  if since_marker_all "$1" | grep -q "ANR in $PKG"; then
    echo "ANR on $1"; since_marker_all "$1" | grep -A3 "ANR in $PKG" | head -8; FAIL=1
  fi
}

# During VK bench's colour stage (marker $1): the app to the background and back, as an app switch does. VK bench
# pauses as the app goes (the core leaves the colour stage and draws nothing, so no crash-guard marker stays on disk for
# a process the system may freeze and kill), its colour stage waits for the app (or, on Android 13 and older, for the
# SurfaceView's window, which goes with it), and the bench must go on to its end with nothing left on the guard's disk.
# The app goes once the stage draws (a `vk frame` line after the first configuration the stage set up, its `vk config`:
# the frame loop draws the last step's frame on until then), so that it goes in the middle of a step; where the colour
# stage never begins, or the bench is done before the app can go (CI's cells are short, and where the driver refuses 10
# bits the stage is a few seconds long), the step is skipped, saying so.
vk_background() {
  local label=$1 line i out resumed
  for i in $(seq 1 840); do
    since_marker "$label" | grep -q -e 'vkbench colour stage: ' -e 'vkbench done' && break
    sleep 1
  done
  if ! since_marker "$label" | grep -q 'vkbench colour stage: '; then
    echo "VK BENCH: no colour stage began (the bench ended first, or not within 840 s); the app was not sent to the background"
    return
  fi
  line=$(since_marker "$label" | grep -m1 -o 'vkbench colour stage: .*')
  echo "vk background: $line"
  for i in $(seq 1 120); do
    since_marker "$label" | awk '/AtlasMap: vkbench colour stage: /{s=1} s && /AtlasMap: vk config /{c=1}
      c && /AtlasMap: vk frame /{f=1} /AtlasMap: vkbench done/{f=1} END{exit !f}' && break
    sleep 0.5
  done
  if since_marker "$label" | grep -q 'vkbench done'; then
    echo "VK BENCH: the bench was done before its colour stage could be sent to the background; the background step is skipped"
    return
  fi
  adb shell input keyevent KEYCODE_HOME
  adb shell log -t AtlasMap "vk background: the app was sent to the background in the colour stage"
  sleep 4
  # The task's own intent again (no extras, the same filter as its root): Android brings the task back as it was. -W
  # waits for its launch, and the activity resumed then must be the app's.
  out=$(adb shell am start -W -n $PKG/.MainActivity 2>&1 | tr -d '\r')
  resumed=$(adb shell dumpsys activity activities 2>/dev/null | tr -d '\r' |
    grep -E 'topResumedActivity|ResumedActivity:|Resumed: ' | head -3)
  if echo "$resumed" | grep -q "$PKG/"; then
    adb shell log -t AtlasMap "vk background: the app came back"
    echo "vk background: the app came back ($(echo "$out" | grep -m1 -E '^(Status|LaunchState):' || echo 'am start -W said nothing'))"
  else
    echo "VK BENCH FAILED: the app did not come back to the foreground after the colour stage's background step"
    echo "  resumed: ${resumed:-none}"; echo "$out" | head -5 | sed 's/^/  am start: /'; FAIL=1
  fi
}

vk_phase() {
  # VK facts through the guest's Vulkan (the emulator's lavapipe): there must be a device.
  fresh_state
  vk_launch vkfacts --es atlas.screen vkfacts
  wait_line vkfacts 120 "vkfacts done" || echo "VK FACTS: no 'vkfacts done' within 120 s"
  since_marker vkfacts | grep "AtlasMap: vkfacts " | sed 's/.*AtlasMap: vkfacts //' | grep -v '^done$' > "$OUT/vkfacts.txt"
  adb exec-out screencap -p > "$OUT/vkfacts.png"
  if ! grep -q '^gpu: ' "$OUT/vkfacts.txt"; then
    echo "VK FACTS FAILED: the emulator's guest has no Vulkan device the core can run on"
    grep -e '^vulkan\.' -e '^guard' "$OUT/vkfacts.txt" | head -5; FAIL=1
  fi
  grep -E '^(gpu|api|driver|tiler|lazily\.allocated\.memory|config|route|composite|colour|colour\.stage|colour\.formats|configs\.available|feature\.shader(Float|Int)64|profiles|surface\.(usage|present\.modes|formats)|android\.screen\.colour|core\.build):' "$OUT/vkfacts.txt"
  # The frame's colour on the guest's surface (plan-vector D5, "Colour depth"): VK facts names it, from the surface, and
  # what VK bench's colour stage would draw (10 bits, off after a crash in it, or none).
  grep -q -E '^colour: [A-Za-z0-9]+ \(' "$OUT/vkfacts.txt" || { echo "VK FACTS FAILED: no 'colour:' line"; FAIL=1; }
  # VK facts' composite check (frame/CompositeCheck.hpp): the composite's resolve form against its direct form, behind
  # the crash guard's checks stage. Alike, or apart on the diagonal alone (a driver that clips the full-screen
  # triangle, as the hosts' lavapipe does: the check's planted failure; the guest draws the direct form anyway, not
  # being a tiler); apart off the diagonal, off, not run, unavailable or no line fails.
  grep -E '^composite\.check: ' "$OUT/vkfacts.txt" || true
  if ! grep -q -E '^composite\.check: the resolve form (draws as the direct form|differs on [0-9]+ pixels, all on the diagonal: the driver clips the full-screen triangle) \(' "$OUT/vkfacts.txt"; then
    echo "COMPOSITE CHECK FAILED: VK facts' composite check found the resolve form apart off the diagonal, or did not run"; FAIL=1
  fi
  grep -q -E '^colour\.stage: (rgb10a2|bgr10a2|off|none) \(' "$OUT/vkfacts.txt" || { echo "VK FACTS FAILED: no 'colour.stage:' line"; FAIL=1; }
  # The core's numerics on Bionic (K3b's acceptance, L0 d): VK facts logs `pymath <hash> ok`, and the hash must be
  # the one the host build and the port compute (ATLAS_PYMATH_HASH, from ci/hashes.env).
  since_marker vkfacts | grep -o 'AtlasMap: pymath .*' | sed 's/AtlasMap: //' | tr -d '\r' | head -1 > "$OUT/pymath.txt"
  echo "pymath: $(cat "$OUT/pymath.txt")"
  if [ -z "${ATLAS_PYMATH_HASH:-}" ]; then
    echo "PYMATH FAILED: ATLAS_PYMATH_HASH is not set (ci/hashes.env holds the expected hash)"; FAIL=1
  elif ! grep -qx "pymath $ATLAS_PYMATH_HASH ok" "$OUT/pymath.txt"; then
    echo "PYMATH FAILED: the emulator's numerics read '$(cat "$OUT/pymath.txt")', not 'pymath $ATLAS_PYMATH_HASH ok'"; FAIL=1
  fi
  # The core's sun bits on Bionic (K8's acceptance): VK facts logs `sun <hash> ok` over the bundled Westerkerk's every
  # sample and ground shadow at its sunny moment, and the hash must be the host build's (ATLAS_SUN_HASH, from ci/hashes.env).
  since_marker vkfacts | grep -o 'AtlasMap: sun .*' | sed 's/AtlasMap: //' | tr -d '\r' | head -1 > "$OUT/sun.txt"
  echo "sun: $(cat "$OUT/sun.txt")"
  if [ -z "${ATLAS_SUN_HASH:-}" ]; then
    echo "SUN FAILED: ATLAS_SUN_HASH is not set (ci/hashes.env holds the expected hash)"; FAIL=1
  elif ! grep -qx "sun $ATLAS_SUN_HASH ok" "$OUT/sun.txt"; then
    echo "SUN FAILED: the emulator's sun bits read '$(cat "$OUT/sun.txt")', not 'sun $ATLAS_SUN_HASH ok'"; FAIL=1
  fi
  # The core's noise fields on Bionic (K17a's acceptance): VK facts logs `fields 1080x2400 cpu <ms> gpu <gpu> hash <h>
  # ok`, the eight screen-wide fields at S1 each equal to the port's (fields.inc's hashes, which the host build's tests
  # hold), on the core's device: <gpu> is the GPU's milliseconds where it made them too, every byte the CPU's (a
  # difference ends the line `FAIL gpu <field> …`), or `off (<why>)` where the device takes the CPU's (the emulator's
  # lavapipe: `off (a CPU implementation)`).
  since_marker vkfacts | grep -o 'AtlasMap: fields .*' | sed 's/AtlasMap: //' | tr -d '\r' | head -1 > "$OUT/fields.txt"
  echo "fields: $(cat "$OUT/fields.txt")"
  if ! grep -qE '^fields 1080x2400 cpu [0-9]+ gpu ([0-9]+|off \(.+\)) hash [0-9a-f]{16} ok$' "$OUT/fields.txt"; then
    echo "FIELDS FAILED: the emulator's noise fields read '$(cat "$OUT/fields.txt")', not 'fields 1080x2400 cpu <ms> gpu <ms>|off (<why>) hash <h> ok'"; FAIL=1
  fi
  # The ground's stage T on Bionic (K9's, D16's L0 c): VK facts logs `ground <hash> ok` over the bundled Westerkerk's
  # every tile built and packed for the GPU, and the hash must be the host build's (ATLAS_GROUND_HASH, from ci/hashes.env).
  since_marker vkfacts | grep -o 'AtlasMap: ground .*' | sed 's/AtlasMap: //' | tr -d '\r' | head -1 > "$OUT/ground.txt"
  echo "ground: $(cat "$OUT/ground.txt")"
  if [ -z "${ATLAS_GROUND_HASH:-}" ]; then
    echo "GROUND FAILED: ATLAS_GROUND_HASH is not set (ci/hashes.env holds the expected hash)"; FAIL=1
  elif ! grep -qx "ground $ATLAS_GROUND_HASH ok" "$OUT/ground.txt"; then
    echo "GROUND FAILED: the emulator's ground reads '$(cat "$OUT/ground.txt")', not 'ground $ATLAS_GROUND_HASH ok'"; FAIL=1
  fi
  # The core's twin on Bionic (ENGINE.md's checks: VK facts' hash lines): VK facts logs `twin <hash> ok`, the app's frame
  # over the bundled Westerkerk at the engine references' home emitted without drawing (frame/TwinCheck.hpp), and the
  # hash must be the host build's (ATLAS_TWIN_HASH, from ci/hashes.env).
  since_marker vkfacts | grep -o 'AtlasMap: twin .*' | sed 's/AtlasMap: //' | tr -d '\r' | head -1 > "$OUT/twin.txt"
  echo "twin: $(cat "$OUT/twin.txt")"
  if [ -z "${ATLAS_TWIN_HASH:-}" ]; then
    echo "TWIN FAILED: ATLAS_TWIN_HASH is not set (ci/hashes.env holds the expected hash)"; FAIL=1
  elif ! grep -qx "twin $ATLAS_TWIN_HASH ok" "$OUT/twin.txt"; then
    echo "TWIN FAILED: the emulator's twin reads '$(cat "$OUT/twin.txt")', not 'twin $ATLAS_TWIN_HASH ok'"; FAIL=1
  fi
  # The core's text on the guest (K13's acceptance): VK facts logs the kerning rule the guest's own Paint shows
  # (`text.rounding: floor|round (probe …)`), then the core's shaping of the canal names, runway designators and
  # kerning pairs held glyph for glyph to the guest's TextRunShaper under that rule (`text.glyphs: …`).
  grep -E '^text\.(rounding|glyphs): ' "$OUT/vkfacts.txt" || true
  grep -q -E '^text\.rounding: (floor|round) \(probe ' "$OUT/vkfacts.txt" || { echo "TEXT FAILED: no 'text.rounding:' line"; FAIL=1; }
  if ! grep -q -E "^text\.glyphs: [0-9]+ runs, [0-9]+ glyphs equal Android's \(rule (floor|round)\)" "$OUT/vkfacts.txt"; then
    echo "TEXT FAILED: the guest's glyph check read '$(grep -m1 '^text\.glyphs:' "$OUT/vkfacts.txt" || echo nothing)'"; FAIL=1
  fi
  vk_crashes vkfacts

  # VK bench in CI's length: every configuration the guest has, both hosts with the pills, the app's map at its real
  # views, the pills moving, then the colour stage, with the app sent to the background and brought back in it (the
  # colour stage runs at 8 bits alone, saying so, where the guest's surface offers no 10 bits). 900 s in all, the colour
  # stage's wait included.
  vk_launch vkbench --es atlas.screen vkbench --es atlas.vk.bench ci --ez atlas.vk.ci true
  local done_ok=1 bench_start=$SECONDS left
  vk_background vkbench
  left=$((900 - (SECONDS - bench_start))); [ "$left" -gt 0 ] || left=1
  wait_line vkbench "$left" "vkbench done" || { echo "VK BENCH FAILED: no 'vkbench done' within 900 s"; FAIL=1; done_ok=0; }
  since_marker vkbench | grep -E 'AtlasMap: (vkbench|vkfacts|vk config|vk path|vk surface|vk window|vk frame|vk guard|vk background|a0 |a frame failed|device lost)' |
    sed 's/.*AtlasMap: //' > "$OUT/vkbench.txt"
  adb exec-out screencap -p > "$OUT/vkbench.png"
  if [ "$done_ok" = 1 ]; then
    grep -e '^vkbench ' -e '^a0 ' "$OUT/vkbench.txt" | grep -v '^vkbench soak' | head -60
    grep -q '^vk config C ' "$OUT/vkbench.txt" || { echo "VK BENCH FAILED: configuration C never took effect (no 'vk config C' line)"; FAIL=1; }
    grep -q '^vkbench board C ' "$OUT/vkbench.txt" || { echo "VK BENCH FAILED: no line for configuration C at the board's view"; FAIL=1; }
    # The colour stage, after every 8-bit run: the 10-bit frame timed against the 8-bit one beside it, or why it does
    # not run (no 10 bits offered, or off after a crash in it).
    grep -q '^vkbench colour' "$OUT/vkbench.txt" || { echo "VK BENCH FAILED: no 'vkbench colour' line"; FAIL=1; }
    grep -q -e '^a frame failed' -e '^device lost' "$OUT/vkbench.txt" && { echo "VK BENCH FAILED: a frame failed"; FAIL=1; }
    # The app's map at its real views (ENGINE.md step 2: VK bench runs the real paths): its first picture complete, and
    # a line for each path (the guest's software Vulkan is far slower than a phone: the numbers are the phone's to judge).
    grep '^vkbench map' "$OUT/vkbench.txt" || true
    grep -q '^vkbench map first picture: complete after ' "$OUT/vkbench.txt" ||
      { echo "VK BENCH FAILED: the map's first picture was not complete (no 'vkbench map first picture: complete after' line)"; FAIL=1; }
    for p in 'home still' 'home orbit' 'home pan' 'home pinch' 'board orbit' 'pitch70 orbit'; do
      grep -q "^vkbench map $p: p50 " "$OUT/vkbench.txt" || { echo "VK BENCH FAILED: no line for the map's $p"; FAIL=1; }
    done
    # Configuration C's graphics memory ceiling at S1 (plan-vector §3: 225 MB; the emulator's pixel_7 is 1080 × 2400).
    awk '/^vkbench (board|pitch70|surface|texture) / { for (i = 1; i < NF; i++) if ($i == "graphics" && $(i + 1) + 0 > 225) { print; bad = 1 } }
         END { exit bad }' "$OUT/vkbench.txt" || { echo "VK BENCH FAILED: graphics memory above configuration C's 225 MB"; FAIL=1; }
    # No wait ran out: the colour stage's (for its window, gone on Android 13 and older, or for the app sent to the
    # background), which ends the stage, and a host switch's, whose window never came.
    if grep -q 'the colour stage ends here' "$OUT/vkbench.txt"; then
      echo "VK BENCH FAILED: the colour stage ended early (its window or the app did not come back)"
      grep 'the colour stage ends here' "$OUT/vkbench.txt" | head -3; FAIL=1
    fi
    if grep -q 'window came within' "$OUT/vkbench.txt"; then
      echo "VK BENCH FAILED: a host's window did not come within its wait"; grep 'window came within' "$OUT/vkbench.txt" | head -3; FAIL=1
    fi
    if grep -q 'vkbench colour: the window went away' "$OUT/vkbench.txt" && ! grep -q 'vkbench colour: the window came back' "$OUT/vkbench.txt"; then
      echo "VK BENCH FAILED: the window went away in the colour stage and never came back"; FAIL=1
    fi
    if grep -q 'vkbench colour: the app went to the background' "$OUT/vkbench.txt" && ! grep -q 'vkbench colour: the app came back' "$OUT/vkbench.txt"; then
      echo "VK BENCH FAILED: the app went to the background in the colour stage and the stage never went on"; FAIL=1
    fi
    # The colour stage drew frames, and drew again once it went on after the app came back: else the background step
    # proves little (no marker is left, and the guard is clear, trivially when nothing is drawn). CI's per-frame 'vk
    # frame' lines count, from the first configuration the stage set up ('vk config' after its first line: the frame
    # loop draws the last step's frame on until then). After the app came back, judged by the bench's own lines: from its
    # last pause in the stage (its own 'the app came back', or a step it measured again after a cut), the frames after
    # the first configuration it set up then, and only where it set one up (a stage whose last steps were refused
    # rightly draws nothing more); a bench that never saw the app go (done with the stage first) is not judged.
    local colour_frames back_set back_frames
    colour_frames=$(awk '/^vkbench colour stage: /{s=1} s && /^vk config /{c=1} c && /^vk frame /{n++} END{print n+0}' "$OUT/vkbench.txt")
    read -r back_set back_frames < <(awk '
      /^vkbench colour: the app came back$/ || /^vkbench colour .* cut short \(the app went to the background\)/ { b = 1; c = 0; n = 0 }
      b && /^vk config / { c = 1 }
      c && /^vk frame / { n++ }
      END { print c + 0, n + 0 }' "$OUT/vkbench.txt")
    if grep -q '^vkbench colour stage: ' "$OUT/vkbench.txt" && [ "$colour_frames" -eq 0 ]; then
      echo "VK BENCH FAILED: the colour stage drew no frames"; FAIL=1
    fi
    if grep -q -e '^vkbench colour: the app came back$' -e '^vkbench colour .* cut short (the app went to the background)' "$OUT/vkbench.txt"; then
      echo "VK BENCH: the colour stage drew $colour_frames frames, $back_frames of them once it went on after the app came back"
      if [ "$back_set" -eq 1 ] && [ "$back_frames" -eq 0 ]; then
        echo "VK BENCH FAILED: the colour stage drew nothing once it went on after the app came back"; FAIL=1
      fi
    elif grep -q '^vk background: the app was sent to the background' "$OUT/vkbench.txt"; then
      echo "VK BENCH: the colour stage drew $colour_frames frames; the bench did not see the app go in it (not judged)"
    else
      echo "VK BENCH: the colour stage drew $colour_frames frames"
    fi
    # Nothing of the bench left a marker: the crash guard's file is gone once it is done (the debug APK: run-as).
    if adb shell run-as $PKG ls no_backup/vk/guard > /dev/null 2>&1; then
      echo "VK BENCH FAILED: the crash guard's marker is still on disk after the bench:"
      adb shell run-as $PKG cat no_backup/vk/guard | tr -d '\r' | head -3; FAIL=1
    else
      echo "VK BENCH: the crash guard's marker is gone after the bench"
    fi
  fi
  vk_crashes vkbench
  vk_anr vkbench
  # VK facts after the bench: the guard clear, and no marker left for it to find (stopped or crashed).
  vk_launch vkafter --es atlas.screen vkfacts
  wait_line vkafter 120 "vkfacts done" || echo "VK FACTS: no 'vkfacts done' after the bench within 120 s"
  since_marker vkafter | grep -E 'AtlasMap: vkfacts (guard|vk guard)' | sed 's/.*AtlasMap: vkfacts //' > "$OUT/vkfacts-after.txt"
  cat "$OUT/vkfacts-after.txt"
  if grep -qx 'guard: clear' "$OUT/vkfacts-after.txt" && ! grep -q '^vk guard' "$OUT/vkfacts-after.txt"; then
    echo "VK FACTS: the guard is clear after the bench"
  else
    echo "VK FACTS FAILED: the guard is not clear after the bench"; FAIL=1
  fi
  vk_crashes vkafter

  # The crash guard: a crash planted in pipeline creation, twice (the second start retries without the cache), then
  # the next launch shows the honest state with the guard's line (D5.10).
  fresh_state
  local attempt
  for attempt in 1 2; do
    vk_launch "vkguard$attempt" --es atlas.screen vkbench --es atlas.vk.bench ci --es atlas.vk.plant pipelines
    wait_line "vkguard$attempt" 120 "vk guard planted crash" || echo "VK GUARD: attempt $attempt did not reach the planted stage"
    sleep 3
    vk_crashes "vkguard$attempt" planted
  done
  vk_launch vkguard3 --es atlas.screen vkfacts
  wait_line vkguard3 120 "vkfacts done"
  since_marker vkguard1 | grep -E 'AtlasMap: (vk guard|vkfacts vulkan|vkfacts guard)' | sed 's/.*AtlasMap: //' > "$OUT/vkguard.txt"
  adb exec-out screencap -p > "$OUT/vkguard.png"
  cat "$OUT/vkguard.txt"
  if grep -q '^vk guard pipelines .* attempt 2' "$OUT/vkguard.txt" && grep -q "^vkfacts vulkan.state: This phone's graphics driver can't draw the Atlas map." "$OUT/vkguard.txt"; then
    echo "VK GUARD: the planted crash ended in the honest state"
  else
    echo "VK GUARD FAILED: no 'vk guard pipelines … attempt 2' and honest state after two planted crashes"; FAIL=1
  fi
  vk_crashes vkguard3
  fresh_state
}
vk_phase
adb logcat -d > "$OUT/logcat.vk.txt"

# --- the frosted pills (plan-vector D5.1, K11b) -----------------------------------------------------------------------

# The glass the map's core draws under the chrome's frosted pills (CP/passes/Frosted.cpp), judged on screen captures by
# ci/pillcheck.py. The split: this emulator's software Vulkan draws far slower than its display, so it judges still
# pills alone; the moving check (each frosted region and its pill in the same frame in >= 95 % of frames) is the
# phone's: VK bench's `vkbench pills:` line ("same frame while moving N of M") and `pillcheck.py film` over the phone's
# screen recording of VK bench's Pills run.
#  1. VK bench's Pills run, its pills still: with the core's glass, the pill check must pass; with their tint alone, it
#     must fail (and call them plain), so the check is seen to tell the two apart.
#  2. The home on the SurfaceView (the core's glass) and on the TextureView (HWUI's, APP/ui/kit/Backdrop.kt): the
#     core's pills must pass the pill check, and the two within MAD 2 inside the frosted pills. It runs once the map's
#     core draws the home (its `vk frosted` line) and the home takes `atlas.vk.host texture` (a `vk window: texture`
#     line); until then it says so and skips.
pills_phase() {
  local label=vkpills rc_frosted rc_plain rc_home rc_mad
  echo "PILLS: still pills judged here; the moving check (>= 95 % of frames) is the phone's (VK bench's pills line, pillcheck.py film)"
  if ! python3 -I "$HERE/pillcheck.py" selftest > "$OUT/pillcheck-selftest.txt" 2>&1; then
    echo "PILLS FAILED: pillcheck.py's selftest"; cat "$OUT/pillcheck-selftest.txt"; FAIL=1; return
  fi
  fresh_state
  vk_launch $label --es atlas.screen vkbench --es atlas.vk.bench pills
  if ! wait_line $label 300 "vkbench pills still: frosted"; then
    echo "PILLS FAILED: VK bench's pills never stood still (no 'vkbench pills still: frosted' within 300 s)"
    since_marker $label | grep -E 'AtlasMap: (vkbench|vk chrome|vk frosted)' | tail -5 | sed 's/.*AtlasMap: /  /'
    FAIL=1; vk_crashes $label; return
  fi
  # A frame of the stand-in takes this emulator a third to half a second, and a few are in flight and queued: the frame
  # on screen can be two seconds older than the line, so each capture waits 6 s into its 8 s still.
  sleep 6
  # Raw captures for the check (a pure-Python PNG decoder takes half a minute a screen), PNGs to look at.
  adb exec-out screencap > "$OUT/pills-frosted.raw"
  adb exec-out screencap -p > "$OUT/pills-frosted.png"
  wait_line $label 30 "vkbench pills still: plain tint" || { echo "PILLS FAILED: no 'vkbench pills still: plain tint'"; FAIL=1; return; }
  sleep 6
  adb exec-out screencap > "$OUT/pills-plain.raw"
  adb exec-out screencap -p > "$OUT/pills-plain.png"
  wait_line $label 30 "vkbench pills still: done"
  # The fade probe: the same still map and pills under a layer of alpha 0.5. A record of this API level, not a check.
  if wait_line $label 30 "vkbench fade: the map under a layer"; then
    sleep 6
    adb exec-out screencap > "$OUT/pills-fade.raw"
    adb exec-out screencap -p > "$OUT/pills-fade.png"
    wait_line $label 30 "vkbench fade: done"
  fi
  since_marker $label | grep -E 'AtlasMap: (vkbench pills|vkbench fade|vk chrome|vk frosted|vk window: surface changed)' |
    sed 's/.*AtlasMap: //' > "$OUT/pills.txt"
  grep -m1 '^vkbench pills: ' "$OUT/pills.txt"
  if [ -f "$OUT/pills-fade.raw" ]; then
    python3 -I "$HERE/pillcheck.py" fade "$OUT/pills-frosted.raw" "$OUT/pills-fade.raw" --shapes "$OUT/pills.txt" > "$OUT/fade.txt" 2>&1
    echo "FADE: $(tail -1 "$OUT/fade.txt")"
    rm -f "$OUT/pills-fade.raw"
  else
    echo "FADE: VK bench's fade probe never came (no 'vkbench fade:' line): nothing recorded for this API level" | tee "$OUT/fade.txt"
  fi
  python3 -I "$HERE/pillcheck.py" check "$OUT/pills-frosted.raw" --shapes "$OUT/pills.txt" > "$OUT/pills-frosted.txt" 2>&1; rc_frosted=$?
  python3 -I "$HERE/pillcheck.py" check "$OUT/pills-plain.raw" --shapes "$OUT/pills.txt" --expect plain > "$OUT/pills-plain.txt" 2>&1; rc_plain=$?
  rm -f "$OUT/pills-frosted.raw" "$OUT/pills-plain.raw"
  sed 's/^/  frosted: /' "$OUT/pills-frosted.txt"; sed 's/^/  plain tint: /' "$OUT/pills-plain.txt"
  grep -E '^vk (chrome|frosted) ' "$OUT/pills.txt" | tail -4 | sed 's/^/  log: /' # the chrome's shapes and the core's glass
  if [ $rc_frosted -eq 0 ] && [ $rc_plain -eq 0 ]; then
    echo "PILLS: VK bench's still pills pass the pill check with the core's glass and fail it with their tint alone (emulator: still pills only)"
  else
    [ $rc_frosted -ne 0 ] && echo "PILLS FAILED: VK bench's frosted pills did not pass the pill check"
    [ $rc_plain -ne 0 ] && echo "PILLS FAILED: VK bench's plain-tint pills were not caught as plain"
    FAIL=1
  fi
  vk_crashes $label
  vk_anr $label

  # The home on both hosts, from the installed state each, captured once it has drawn and settled.
  home_capture() { # $1 label, $2 picture, then the activity's extras
    local l=$1 picture=$2 i; shift 2
    fresh_state
    vk_launch "$l" --es atlas.screen home --ez atlas.ci true "$@"
    for i in $(seq 1 90); do
      since_marker "$l" | grep -q -e 'vk first frame' -e 'drawn in' && break
      sleep 2
    done
    sleep 8
    adb exec-out screencap > "$OUT/${picture%.png}.raw"
    adb exec-out screencap -p > "$OUT/$picture"
    since_marker "$l" > "$OUT/${picture%.png}.atlasmap.txt"
    vk_crashes "$l"
  }
  # Both in the sunny moment: in the live one each launch drew the light the forecast gave it by then (28 October,
  # 7ed9a86: the SurfaceView's capture came before the forecast, sunny, the TextureView's after it, grey, MAD 2.4).
  home_capture homesurface pills-home.png --es atlas.ci.theme DAY
  if ! grep -q 'AtlasMap: vk frosted [1-9]' "$OUT/pills-home.atlasmap.txt"; then
    echo "PILLS: the home is not drawn by the map's core yet (no 'vk frosted' line): its pills against HWUI's wait for it"
    rm -f "$OUT"/pills-home*.raw
    return
  fi
  python3 -I "$HERE/pillcheck.py" check "$OUT/pills-home.raw" --shapes "$OUT/pills-home.atlasmap.txt" > "$OUT/pills-home.txt" 2>&1; rc_home=$?
  sed 's/^/  home: /' "$OUT/pills-home.txt"
  [ $rc_home -eq 0 ] || { echo "PILLS FAILED: the home's frosted pills did not pass the pill check"; FAIL=1; }
  home_capture hometexture pills-home-texture.png --es atlas.ci.theme DAY --es atlas.vk.host texture
  if ! grep -q 'AtlasMap: vk window: texture' "$OUT/pills-home-texture.atlasmap.txt"; then
    echo "PILLS: the home took no TextureView (no 'vk window: texture' line): its pills against HWUI's wait for atlas.vk.host"
    rm -f "$OUT"/pills-home*.raw
    return
  fi
  python3 -I "$HERE/pillcheck.py" compare "$OUT/pills-home.raw" "$OUT/pills-home-texture.raw" --shapes "$OUT/pills-home.atlasmap.txt" --limit 2 > "$OUT/pills-home-mad.txt" 2>&1; rc_mad=$?
  rm -f "$OUT"/pills-home*.raw
  sed 's/^/  core against HWUI: /' "$OUT/pills-home-mad.txt"
  [ $rc_mad -eq 0 ] || { echo "PILLS FAILED: the home's frosted pills are further than MAD 2 from HWUI's"; FAIL=1; }
}
pills_phase

# --- the map's core (plan-vector K11, Appendix C): the home drawn by the core -----------------------------------------

# The core's first-frame line since marker $1, as "<ms> <first|warm>" ("" when there is none).
first_frame() { since_marker "$1" | grep -m1 -o 'vk first frame -*[0-9]* [a-z]*' | awk '{print $4, $5}'; }

# K11's lines: the home drawn by the core with its first complete frame within the emulator's limits (`vk first frame
# <ms> first|warm`: 45 s, cleared or warm; §3's 3 s and 1.5 s were set when the core drew the ground alone: the full map
# is ten tiles here, the Westerkerk's three and the canal ring's seven around it, 32 MB, each built in 1 to 2.5 s on the
# emulator's two cores, under lavapipe's 250 to 400 ms frames; both runs are warm since the pipeline cache outlives the
# facts' phase, and with step 4's features they took 18 to 22 s by 24 October, two seconds apart from run to run for
# the same work, so the warm limit is the cleared one's; 30 s since 26 October, when a slow run took the cleared start to
# 25.1 s with the ripple and the glints on lavapipe, its VK bench first picture 2.7 s slower than the run before; 45 s
# since that afternoon, when both runs on 95856df drew it in 30.7 to 35.9 s against 16.1 to 17.1 s on the commit before,
# on runners whose own work was slower alike (the ten tiles' build 2,599.9 ms against 1,019.3, VK bench's pipelines 2.5
# to 3.5 times as long, the emulator's boot 36.1 s against 24.7), none of it the commit's; ENGINE.md's gain 7, stage T kept on disk, is what makes a warm
# start quick, and the phone's first picture is measured on the phone, ENGINE.md step 1), no frame from 4 s after a swipe (CI's `vk frame` lines:
# the swipe's fling and the emulator's frames of a second or more took its last frame past 2 s on 25 October), the Java
# heap at the canal ring at most 96 MB, the transitional rule's switch from the core's tiles to a place without them and
# back with no blank frame (its `vk transition core>canvas` and `canvas>core` lines, each written as the picture it
# fades to is ready, the outgoing one held until then, and `vk transitional`), and T2's honest state under atlas.vk.off.
map_phase() {
  local run line ms kind limit before after heap
  for run in mapfirst mapwarm; do
    vk_launch "$run" --es atlas.screen home --ez atlas.ci true --ez atlas.vk.ci true
    wait_line "$run" 60 "vk first frame" || echo "MAP FAILED: no 'vk first frame' within 60 s ($run)"
    line=$(first_frame "$run"); ms=${line% *}; kind=${line#* }
    limit=45000
    echo "map: $run first frame ${line:-none} (limit ${limit} ms)"
    if ! [[ $ms =~ ^[0-9]+$ ]] || [ "$ms" -gt "$limit" ]; then echo "MAP FAILED: the core's first frame ($run): ${line:-none}"; FAIL=1; fi
    [ "$run" = mapwarm ] && [ "$kind" != warm ] && { echo "MAP FAILED: the second launch was not warm (${line:-none})"; FAIL=1; }
    vk_crashes "$run"; vk_anr "$run"
    # To the background, as a phone leaves the app: the trim writes the pipeline cache back (D5.6), and the next launch
    # is a warm one.
    [ "$run" = mapfirst ] && { adb shell input keyevent KEYCODE_HOME; sleep 3; }
  done
  # A swipe on the warm home, then rest: frames while it moves, none from 4 s after (a map that does not rest draws on
  # through the 6 s that follow).
  sleep 3
  adb shell log -t AtlasMap "vk swipe: start"
  adb shell input swipe 540 1500 540 900 300
  adb shell log -t AtlasMap "vk swipe: done"
  sleep 4
  adb shell log -t AtlasMap "vk swipe: 4 s after"
  sleep 6
  before=$(since_marker mapwarm | sed -n '/AtlasMap: vk swipe: start$/,/AtlasMap: vk swipe: 4 s after$/p' | grep -c 'AtlasMap: vk frame ')
  after=$(since_marker mapwarm | sed -n '/AtlasMap: vk swipe: 4 s after$/,$p' | grep -c 'AtlasMap: vk frame ')
  echo "map: the swipe drew $before frames, $after from 4 s after it"
  [ "$before" -gt 0 ] || { echo "MAP FAILED: the swipe drew no frame (the map did not move)"; FAIL=1; }
  [ "$after" -eq 0 ] || { echo "MAP FAILED: $after frames from 4 s after the swipe (the map does not rest)"; FAIL=1; }
  # The Java heap at the canal ring, after the swipe (dumpsys' Dalvik heap in use); the native heap and graphics reported.
  PEAK_ALLOC=0; PEAK_NATIVE=0; PEAK_GFX=0; mem_poll; heap=$((PEAK_ALLOC / 1024))
  echo "map: Java heap at the canal ring ${heap} MB (native heap $((PEAK_NATIVE / 1024)) MB, graphics $((PEAK_GFX / 1024)) MB)"
  [ "$heap" -le 96 ] || { echo "MAP FAILED: the Java heap at the canal ring is ${heap} MB (at most 96)"; FAIL=1; }
  adb exec-out screencap -p > "$OUT/map-swipe.png"
  since_marker mapwarm | grep -E 'AtlasMap: (vk (first frame|map|tiles|config|path|surface|window|honest|swipe)|vk frame )' | sed 's/.*AtlasMap: //' > "$OUT/map.txt"
  # The transitional rule: away from the core's tiles (today's map, or its own honest state offline) and back.
  vk_launch maptrans --es atlas.screen home --ez atlas.ci true --es atlas.vk.script transition
  wait_line maptrans 420 "vk script done" || { echo "MAP FAILED: the transitional script did not finish within 420 s"; FAIL=1; }
  since_marker maptrans | grep -E 'AtlasMap: vk (script|transition|transitional|first frame|map)' | sed 's/.*AtlasMap: //' > "$OUT/map-transition.txt"
  cat "$OUT/map-transition.txt"
  for line in '^vk transitional ' '^vk transition core>canvas ' '^vk transition canvas>core '; do
    grep -q "$line" "$OUT/map-transition.txt" || { echo "MAP FAILED: no '${line#^}' line"; FAIL=1; }
  done
  adb exec-out screencap -p > "$OUT/map-transition.png"
  vk_crashes maptrans; vk_anr maptrans
  # T2's honest state: the map's place says the phone's driver cannot draw it.
  vk_launch mapoff --es atlas.screen home --ez atlas.ci true --ez atlas.vk.off true
  wait_line mapoff 60 "vk honest:" && echo "map: $(since_marker mapoff | grep -m1 -o 'vk honest: .*')" ||
    { echo "MAP FAILED: no 'vk honest:' line with atlas.vk.off"; FAIL=1; }
  adb exec-out screencap -p > "$OUT/map-off.png"
  vk_crashes mapoff; vk_anr mapoff
}
map_phase
adb logcat -d > "$OUT/logcat.map.txt"

# --- the truth phase: the same frames as a phone runs them -------------------------------------------------------------

# One build's home and filters runs, each from the installed state: as installed, then after ART has compiled the app
# (`speed`, about what a phone reaches after its background dexopt). $1 is the build's name, $2 its APK.
truth_runs() {
  local build=$1 s
  install_apk "$2" || { echo "INSTALL FAILED: $2"; FAIL=1; return 1; }
  read_perms
  [ "$build" = bench ] && adb shell dumpsys package $PKG | grep -q "DEBUGGABLE" && echo "WARNING: the bench build is debuggable"
  [ "$build" = bench ] && glcaps
  for s in home filters; do measure "$s" "$build-installed" fresh; done
  adb shell am force-stop $PKG
  adb shell cmd package compile -m speed -f $PKG > "$OUT/compile-$build.txt" 2>&1
  echo "compile $build: $(tr -d '\r' < "$OUT/compile-$build.txt" | tail -1)"
  for s in home filters; do measure "$s" "$build-speed" fresh; done
}

# The device's facts and GL capabilities: the glcaps screen logs them as "glcaps name: value".
glcaps() {
  local i
  adb shell am force-stop $PKG
  adb shell log -t AtlasMap "run-marker glcaps"
  adb shell am start -W -n $PKG/.MainActivity --es atlas.screen glcaps > /dev/null
  for i in $(seq 1 30); do since_marker glcaps | grep -q "glcaps done" && break; sleep 1; done
  since_marker glcaps | grep "AtlasMap: glcaps " | sed 's/.*AtlasMap: glcaps //' | grep -v '^done$' > "$OUT/glcaps.txt"
  [ -s "$OUT/glcaps.txt" ] || echo "gl: no glcaps lines in logcat" > "$OUT/glcaps.txt"
  adb exec-out screencap -p > "$OUT/glcaps.png"
}

# A simpleperf profile of the bench build's first home frame from the installed state, after the speed compile. The bench
# build is profileable, so `--app` works without root; root (the emulator's google_apis image has it) also shows the
# kernel. simpleperf starts first and waits for the app, so the profile covers the whole cold start up to the frame.
profile_home() {
  local label=bench-profile-home sp=simpleperf rec i abi ndk dir
  adb root >/dev/null 2>&1; adb wait-for-device; sleep 3
  if ! adb shell 'command -v simpleperf' >/dev/null 2>&1; then
    # An image without simpleperf: the NDK's, for the device's ABI.
    abi=$(adb shell getprop ro.product.cpu.abi | tr -d '\r')
    case "$abi" in arm64*) dir=arm64 ;; armeabi*) dir=arm ;; x86_64) dir=x86_64 ;; *) dir=x86 ;; esac
    ndk=${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}
    if [ -n "$ndk" ] && [ -f "$ndk/simpleperf/bin/android/$dir/simpleperf" ]; then
      adb push "$ndk/simpleperf/bin/android/$dir/simpleperf" /data/local/tmp/simpleperf >/dev/null && adb shell chmod 755 /data/local/tmp/simpleperf
      sp=/data/local/tmp/simpleperf
    else
      echo "simpleperf skipped: the device has none and no NDK was found" > "$OUT/home.simpleperf.txt"; return
    fi
  fi
  adb shell am force-stop $PKG
  fresh_state
  adb shell rm -f /data/local/tmp/home.perf.data /data/local/tmp/home.samples.txt
  adb shell log -t AtlasMap "run-marker $label"
  adb shell "$sp record --app $PKG -e cpu-clock -f 250 --call-graph dwarf,8192 --duration $((WAIT + 60)) -o /data/local/tmp/home.perf.data" > "$OUT/home.simpleperf.record.txt" 2>&1 &
  rec=$!
  sleep 3
  adb shell am start -n $PKG/.MainActivity --es atlas.screen home --ez atlas.ci true > /dev/null
  wait_frame "$label" quiet
  # Stop recording once the frame is drawn (simpleperf writes the file on SIGINT), and give it time to unwind.
  adb shell "kill -INT \$(pidof simpleperf) 2>/dev/null" >/dev/null 2>&1
  for i in $(seq 1 300); do kill -0 $rec 2>/dev/null || break; sleep 2; done
  kill $rec 2>/dev/null
  $LIMIT adb shell "$sp report-sample -i /data/local/tmp/home.perf.data --show-callchain -o /data/local/tmp/home.samples.txt" > "$OUT/home.simpleperf.report.txt" 2>&1
  adb pull /data/local/tmp/home.samples.txt "$OUT/home.samples.tmp" >/dev/null 2>&1
  {
    echo "simpleperf profile of the bench build's first home frame (installed state, speed-compiled), frame drawn: $FRAME_DONE (1 = yes)"
    since_marker "$label" | grep -m1 -o "frame [0-9]* .*: load [0-9]* ms, render.*"
    echo "(250 Hz with dwarf call graphs on every thread of the app: the frame's times include simpleperf's overhead)"
    grep -i -e "samples recorded" -e "samples lost" -e "error" "$OUT/home.simpleperf.record.txt" | head -5
    echo
    python3 "$HERE/perfsplit.py" "$OUT/home.samples.tmp"
  } > "$OUT/home.simpleperf.txt" 2>&1
  rm -f "$OUT/home.samples.tmp"
  adb shell rm -f /data/local/tmp/home.perf.data /data/local/tmp/home.samples.txt
}

# The first-frame table of screen $1 over its runs, then each run's lines, in <screen>.atlasmap.txt.
RUNS="debug-first debug-installed debug-speed bench-installed bench-speed"
compose() {
  local s=$1 run f m line nums sum
  {
    echo "Atlas on $(adb shell getprop ro.product.model | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'), $(adb shell getprop ro.product.cpu.abi | tr -d '\r')), first frame of each run."
    if [ "$s" = home ]; then
      echo "Engine times (ms) from its frame line; drawn = seconds from the start to the frame on screen; rest = CPU of one core"
      echo "in the ${IDLE} s after it; heap = peak Java heap in use (dumpsys, polled); hwm = the process's peak resident memory."
      printf '  %-16s %-10s %-7s %7s %7s %6s %7s %7s %7s %6s %7s %6s\n' run region moment load render parse raster total drawn rest heapMB hwmMB
    else
      echo "Filter probes: their count and summed CPU and AGSL (GPU) times."
    fi
    for run in $RUNS; do
      f="$OUT/$s.$run.atlasmap.txt"; m="$OUT/$s.$run.mem.txt"
      [ -f "$f" ] || continue
      if [ "$s" = home ]; then
        line=$(grep -m1 -E ': load [0-9]+ ms, render' "$f")
        sum=$(grep -m1 '^summary:' "$m" 2>/dev/null)
        if [ -z "$line" ]; then printf '  %-16s %s\n' "$run" "no frame"; continue; fi
        nums=$(echo "$line" | sed -E 's/.*AtlasMap: frame [0-9]+ ([^ ]+) view=[^ ]+ moment=([^ ]+) band=[^ ]+ [^ ]+: load ([0-9]+) ms, render ([0-9]+) ms, parse ([0-9]+) ms, raster ([0-9]+) ms, total ([0-9]+) ms.*/\1 \2 \3 \4 \5 \6 \7/')
        # shellcheck disable=SC2086
        printf '  %-16s %-10s %-7s %7s %7s %6s %7s %7s %7s %6s %7s %6s\n' "$run" $nums \
          "$(echo "$sum" | grep -o 'drawn_s=[^ ]*' | cut -d= -f2)" "$(echo "$sum" | grep -o 'rest_cpu=[^ ]*' | cut -d= -f2)" \
          "$(echo "$sum" | grep -o 'heap_mb=[^ ]*' | cut -d= -f2)" "$(echo "$sum" | grep -o 'hwm_mb=[^ ]*' | cut -d= -f2)"
      else
        printf '  %-16s %s\n' "$run" "$(grep -o 'CPU [0-9]* ms, GPU [0-9]* ms' "$f" | awk '{ c += $2; g += $5; n++ } END { printf "%d probes, CPU %d ms, GPU %d ms", n, c, g }')"
      fi
    done
    for run in $RUNS; do
      f="$OUT/$s.$run.atlasmap.txt"
      [ -f "$f" ] || continue
      echo; echo "=== $run ==="
      [ -f "$OUT/$s.$run.mem.txt" ] && grep -e '^first frame' -e '^at rest' -e '^polled' -e '^ART:' -e '^VmHWM' "$OUT/$s.$run.mem.txt"
      cat "$f"
    done
  } > "$OUT/$s.atlasmap.new"
  mv "$OUT/$s.atlasmap.new" "$OUT/$s.atlasmap.txt"
}

if [ -n "$BENCH_APK" ] && [ "${ATLAS_BENCH:-1}" != 0 ]; then
  echo "Truth phase: $APK, then $BENCH_APK"
  truth_runs debug "$APK"
  truth_runs bench "$BENCH_APK"
  [ "${ATLAS_PROFILE:-1}" != 0 ] && profile_home
  for s in home filters; do compose "$s"; done
  adb logcat -d > "$OUT/logcat.bench.txt"
  # The numbers in the job's log too, so a run can be read without its artifacts.
  echo; sed -n '1,9p' "$OUT/home.atlasmap.txt"
  echo; sed -n '1,7p' "$OUT/filters.atlasmap.txt"
  echo; grep -E '^(device\.(model|soc|cores|cpu|heap|thermal)|app\.|gl\.(version|renderer|highp\.fragment|EXT_multisampled_render_to_texture|KHR_blend_equation_advanced|EXT_shader_framebuffer_fetch|max\.samples):|gl:)' "$OUT/glcaps.txt"
  [ -f "$OUT/home.simpleperf.txt" ] && { echo; sed -n '/^RASTER split/,/^$/p' "$OUT/home.simpleperf.txt"; sed -n '1,3p' "$OUT/home.simpleperf.txt"; }
else
  RUNS="debug-first"
  for s in home filters; do [ -f "$OUT/$s.debug-first.atlasmap.txt" ] && compose "$s"; done
fi
exit $FAIL
