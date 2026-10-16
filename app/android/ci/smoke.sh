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
# Each measured run starts from the installed state (`pm clear`, the install's permissions granted again), so earlier
# screens' downloads and kept scenes do not change what the home draws. `run-as` needs a debuggable APK, so the bench
# phase never uses it: the screen list comes from the debug run and the timings from logcat (tag AtlasMap).
#
# Fails (exit 1) on a crash, "a frame failed" or "out of memory". A frame that never comes is "NO FRAME", a warning.
# Environment: ATLAS_SCREENS (the debug loop's screens, default: all), ATLAS_MAP_WAIT (seconds to wait for the home's map),
# ATLAS_IDLE (seconds the home is watched at rest, default 20), ATLAS_BENCH=0 (skip the bench phase), ATLAS_PROFILE=0
# (skip simpleperf).
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
APK=$(ls "$1"/*.apk | head -1); OUT=$2; mkdir -p "$OUT"
BENCH_APK=""
[ -n "${3:-}" ] && BENCH_APK=$(ls "$3"/*.apk 2>/dev/null | head -1)
PKG=app.atlas
WAIT=${ATLAS_MAP_WAIT:-180}
IDLE=${ATLAS_IDLE:-20}
# `timeout` is coreutils (the CI runner has it); a Mac may not.
LIMIT=""; command -v timeout >/dev/null 2>&1 && LIMIT="timeout 900"
FAIL=0

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

# How many finished frames (or the last filter probe) are logged since marker $1.
frames_since() { since_marker "$1" | grep -c -e "drawn in" -e "filter probe sblur"; }

# Seconds from marker $1 (logged just before the app is started) to the first frame on screen (the filters: the last probe).
drawn_after() {
  since_marker "$1" | awk 'function t(s,  a) { split(s, a, ":"); return a[1] * 3600 + a[2] * 60 + a[3] }
    NR == 1 { t0 = t($2) } / drawn in |filter probe sblur/ { d = t($2) - t0; if (d < 0) d += 86400; printf "%.1f", d; exit }'
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
# state. The home's map is the engine's exact frame of the bundled Westerkerk, and the filters screen runs the SVG filter
# probes through AGSL: those two wait until the frame is drawn or the last probe is done. The logcat marker is
# "<run>-<screen>", which must be unique to this call.
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
  if grep -q -e "a frame failed" -e "out of memory" "$OUT/$s.$run.atlasmap.txt"; then
    echo "FRAME FAILED on $s ($run)"; grep -e "a frame failed" -e "out of memory" "$OUT/$s.$run.atlasmap.txt" | head -3; FAIL=1
  fi
  if adb logcat -d -b crash | grep -q "$PKG"; then
    echo "CRASH on $s ($run)"; adb logcat -d -b crash > "$OUT/$s.$run.crash.txt"; FAIL=1; adb logcat -c
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

# --- the debug loop: every screen, at the debug build's first launch ---------------------------------------------------

adb wait-for-device
# The emulator's own launcher sometimes stalls under the software GPU; its "isn't responding" dialog is not Atlas's.
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1
adb shell settings put global anr_show_background 0 >/dev/null 2>&1
# A long run writes more than the default log buffer holds.
adb logcat -G 16M >/dev/null 2>&1
install_apk "$APK" || exit 1
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
