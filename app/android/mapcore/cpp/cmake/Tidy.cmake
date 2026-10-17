# clang-tidy (plan-vector T10) with the core's .clang-tidy: with ATLAS_TIDY on, every core translation unit is checked
# as it compiles, and a finding fails the build. The clang-tidy is NDK r30's (ndk.version in mapcore/tools.properties),
# the one core.yml's tidy job runs, so a clean run here is a clean run there: .clang-tidy takes in every modernize-*
# check, and another version brings checks of its own (LLVM's 21.1 has modernize-use-scoped-lock, r30's 21.0 has
# not). The check that bans <cmath>'s transcendentals outside pymath is bugprone-unsafe-functions with CustomFunctions
# (clang-tidy 20 or later).
include_guard(GLOBAL)

option(ATLAS_TIDY "Run clang-tidy on the core's sources as they compile" OFF)
set(ATLAS_CLANG_TIDY "" CACHE FILEPATH "clang-tidy to run (by default NDK r30's, which CI runs)")

if(ATLAS_TIDY)
    if(NOT ATLAS_CLANG_TIDY)
        # The pinned NDK where the SDK keeps it, or where ANDROID_NDK_ROOT or ANDROID_NDK_HOME point if that is the
        # pinned one (GitHub's runners point them at the image's default NDK).
        set(_atlas_ndks)
        foreach(_sdk IN ITEMS "$ENV{ANDROID_HOME}" "$ENV{ANDROID_SDK_ROOT}" "$ENV{HOME}/Library/Android/sdk"
                              "$ENV{HOME}/Android/Sdk")
            list(APPEND _atlas_ndks "${_sdk}/ndk/${ATLAS_PIN_ndk.version}")
        endforeach()
        foreach(_ndk IN ITEMS "$ENV{ANDROID_NDK_ROOT}" "$ENV{ANDROID_NDK_HOME}")
            if(_ndk AND EXISTS "${_ndk}/source.properties")
                file(STRINGS "${_ndk}/source.properties" _atlas_revision REGEX "^Pkg\\.Revision *=")
                if(_atlas_revision MATCHES "= *([0-9.]+)")
                    if(CMAKE_MATCH_1 STREQUAL ATLAS_PIN_ndk.version)
                        list(APPEND _atlas_ndks "${_ndk}")
                    endif()
                endif()
            endif()
        endforeach()
        set(_atlas_tidy_dirs)
        foreach(_ndk IN LISTS _atlas_ndks)
            list(APPEND _atlas_tidy_dirs "${_ndk}/toolchains/llvm/prebuilt/darwin-x86_64/bin"
                                         "${_ndk}/toolchains/llvm/prebuilt/linux-x86_64/bin")
        endforeach()
        # A variable of its own: find_program skips its search when its variable already exists in the cache.
        find_program(_atlas_clang_tidy NAMES clang-tidy PATHS ${_atlas_tidy_dirs} NO_DEFAULT_PATH)
        if(NOT _atlas_clang_tidy)
            message(FATAL_ERROR "NDK r30's clang-tidy was not found (NDK ${ATLAS_PIN_ndk.version}, under ANDROID_HOME "
                "or the SDK's usual places). Install that NDK, or name a clang-tidy with -DATLAS_CLANG_TIDY=..., whose "
                "checks may then differ from CI's.")
        endif()
        set(ATLAS_CLANG_TIDY "${_atlas_clang_tidy}")
    endif()
    message(STATUS "clang-tidy: ${ATLAS_CLANG_TIDY}")
    set(ATLAS_TIDY_COMMAND "${ATLAS_CLANG_TIDY};--quiet")
    # On macOS the compiler is Apple's, which finds the SDK by itself; the NDK's clang-tidy needs it named.
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
