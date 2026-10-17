#!/bin/bash
# The MODS probe: builds this folder's `import std` and `import vulkan` programs with the host's compiler and with NDK
# r30 for arm64-v8a, and writes one line per toolchain, "MODS <toolchain>: builds" or "MODS <toolchain>: fails (<first
# error>)", with each build's whole log beside it.
#
#   probe.sh <cmake 4.2+> <out dir> <ndk dir or "none"> [host CMake arguments...]
set -u
cmake=$1 out=$2 ndk=$3; shift 3
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$out" && : > "$out/summary.txt"
"$cmake" --version | head -1 | tee -a "$out/summary.txt"
run() { # <name> <cmake arguments...>
    local name=$1; shift
    rm -rf "$out/build-$name"
    if "$cmake" -S "$here" -B "$out/build-$name" -G Ninja "$@" > "$out/$name.log" 2>&1 &&
       "$cmake" --build "$out/build-$name" >> "$out/$name.log" 2>&1; then
        echo "MODS $name: builds" | tee -a "$out/summary.txt"
    else
        # The first error with the lines that explain it (CMake puts its reason on the lines after "CMake Error").
        local why
        why=$(awk '/CMake Error|error:/ {found=1} found && NF {printf "%s ", $0; if (++n == 4) exit}' "$out/$name.log" |
              tr -s ' ' | cut -c1-300)
        echo "MODS $name: fails ($why)" | tee -a "$out/summary.txt"
    fi
}
run host "$@"
if [ "$ndk" != none ]; then
    run android-arm64 -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a \
        -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static
    # Below CMake: can the NDK's clang build libc++'s std module for Android at all? (With r29 and r30 it cannot:
    # Bionic's <ctype.h> functions are inline with internal linkage, and a module cannot export them.)
    prebuilt=$(ls -d "$ndk"/toolchains/llvm/prebuilt/*/ | head -1)
    for api in 29 35; do
        if "$prebuilt/bin/clang++" --target=aarch64-linux-android$api -std=c++23 -fno-exceptions -fno-rtti \
            -Wno-reserved-module-identifier --precompile "$prebuilt/share/libc++/v1/std.cppm" \
            -o "$out/std-android$api.pcm" > "$out/std-android$api.log" 2>&1; then
            echo "MODS std module for android-$api: builds" | tee -a "$out/summary.txt"
        else
            echo "MODS std module for android-$api: fails ($(grep -c 'error:' "$out/std-android$api.log") errors; first:" \
                "$(grep -m1 'error:' "$out/std-android$api.log" | sed 's/.*error: //' | cut -c1-200))" | tee -a "$out/summary.txt"
        fi
    done
fi
