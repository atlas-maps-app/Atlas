# clang-tidy (plan-vector T10) with the core's .clang-tidy: with ATLAS_TIDY on, every core translation unit is checked
# as it compiles, and a finding fails the build. The check that bans <cmath>'s transcendentals outside pymath is
# bugprone-unsafe-functions with CustomFunctions (clang-tidy 20 or later: NDK r30's clang-tidy 21 on macOS, LLVM's 21
# on the Linux runners).
include_guard(GLOBAL)

option(ATLAS_TIDY "Run clang-tidy on the core's sources as they compile" OFF)
set(ATLAS_CLANG_TIDY "" CACHE FILEPATH "clang-tidy to run (the NDK's on macOS, LLVM's on Linux)")

if(ATLAS_TIDY)
    if(NOT ATLAS_CLANG_TIDY)
        set(_atlas_tidy_hints)
        foreach(_ndk IN ITEMS "$ENV{ANDROID_NDK_ROOT}" "$ENV{ANDROID_NDK_HOME}" "$ENV{ANDROID_HOME}/ndk/${ATLAS_PIN_ndk.version}"
                              "$ENV{HOME}/Library/Android/sdk/ndk/${ATLAS_PIN_ndk.version}")
            if(_ndk)
                list(APPEND _atlas_tidy_hints "${_ndk}/toolchains/llvm/prebuilt/darwin-x86_64/bin"
                                              "${_ndk}/toolchains/llvm/prebuilt/linux-x86_64/bin")
            endif()
        endforeach()
        # A variable of its own: find_program skips its search when its variable already exists in the cache.
        find_program(_atlas_clang_tidy NAMES clang-tidy-21 clang-tidy HINTS ${_atlas_tidy_hints} REQUIRED)
        set(ATLAS_CLANG_TIDY "${_atlas_clang_tidy}")
    endif()
    message(STATUS "clang-tidy: ${ATLAS_CLANG_TIDY}")
    set(ATLAS_TIDY_COMMAND "${ATLAS_CLANG_TIDY};--quiet")
    # On macOS the compiler is Apple's, which finds the SDK by itself; LLVM's clang-tidy needs it named.
    if(APPLE AND NOT CMAKE_OSX_SYSROOT)
        execute_process(COMMAND xcrun --show-sdk-path OUTPUT_VARIABLE _atlas_sdk OUTPUT_STRIP_TRAILING_WHITESPACE)
        list(APPEND ATLAS_TIDY_COMMAND "--extra-arg-before=-isysroot${_atlas_sdk}")
    endif()
endif()

# Checks a core target with clang-tidy when ATLAS_TIDY is on.
function(atlas_tidy target)
    if(ATLAS_TIDY)
        set_target_properties(${target} PROPERTIES CXX_CLANG_TIDY "${ATLAS_TIDY_COMMAND}")
    endif()
endfunction()
