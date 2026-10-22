# The core's toolchain (plan-vector T3, T4): the language level and the dialect every core target shares, the floating
# point the port's bits need, the three build kinds, and the host tools the build runs (slangc, and a host compiler
# for the build's own tools when cross-compiling for Android).
include_guard(GLOBAL)

if(NOT CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    message(FATAL_ERROR "The core builds with clang only (the NDK's, Apple's or LLVM's), not ${CMAKE_CXX_COMPILER_ID}.")
endif()

if(CMAKE_SYSTEM_NAME STREQUAL "Android")
    set(ATLAS_ANDROID ON)
    set(ATLAS_HOST OFF)
else()
    set(ATLAS_ANDROID OFF)
    set(ATLAS_HOST ON)
endif()

# Read here already (ThinLTO is off under sanitizers); cmake/Sanitizers.cmake applies it.
set(ATLAS_SANITIZE "" CACHE STRING "Sanitizers: asan, tsan, ubsan-minimal, hwasan, fuzzer, or empty")

# The repository and its git-ignored cache, where Gradle's fetch tasks put the pinned tools (mapcore/tools.properties).
get_filename_component(ATLAS_MAPCORE_DIR "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
get_filename_component(ATLAS_REPO_DIR "${ATLAS_MAPCORE_DIR}/../../.." ABSOLUTE)
set(ATLAS_TOOLS_DIR "${ATLAS_REPO_DIR}/cache/atlas-tools" CACHE PATH "Where the pinned tools are unpacked")

# The pins, read from the one file Gradle's fetch tasks and CI read too.
file(STRINGS "${ATLAS_MAPCORE_DIR}/tools.properties" _atlas_tool_lines REGEX "^[a-z0-9.-]+=")
foreach(_line IN LISTS _atlas_tool_lines)
    string(REGEX MATCH "^([^=]+)=(.*)$" _ "${_line}")
    set("ATLAS_PIN_${CMAKE_MATCH_1}" "${CMAKE_MATCH_2}")
endforeach()

# Language level. C++23 is the base; ATLAS_CXX26 builds as C++2c, which brings the C++26 features the NDK's libc++
# already has (saturation arithmetic, std::span from an initializer list) and allows pack indexing, = delete("why")
# and the placeholder _ without extension warnings.
option(ATLAS_CXX26 "Build the core as C++2c" ON)
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
# The core uses no C++ modules (the MODS probe decides whether it ever will), so no unit needs a dependency scan.
set(CMAKE_CXX_SCAN_FOR_MODULES OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# The build kind: debug (-O1 -g, assertions, UBSan on Android), bench (release code with symbols, profileable) or
# release (-O2, ThinLTO, stripped by AGP). Gradle passes it per build type; on hosts it follows CMAKE_BUILD_TYPE.
set(ATLAS_BUILD "" CACHE STRING "debug, bench or release")
if(NOT ATLAS_BUILD)
    if(CMAKE_BUILD_TYPE MATCHES "^(Release|MinSizeRel)$")
        set(ATLAS_BUILD release)
    elseif(CMAKE_BUILD_TYPE STREQUAL "RelWithDebInfo")
        set(ATLAS_BUILD bench)
    else()
        set(ATLAS_BUILD debug)
    endif()
endif()
if(NOT ATLAS_BUILD MATCHES "^(debug|bench|release)$")
    message(FATAL_ERROR "ATLAS_BUILD must be debug, bench or release, not '${ATLAS_BUILD}'.")
endif()
# The core's flags replace CMake's and the NDK's per-configuration defaults (-O3, -O0), so every build kind compiles
# the same way whoever configures it.
foreach(_lang C CXX)
    foreach(_config DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
        set(CMAKE_${_lang}_FLAGS_${_config} "")
    endforeach()
endforeach()

# Every target of the core links atlas_options: the dialect, the floating point and the build kind.
add_library(atlas_options INTERFACE)
target_compile_options(atlas_options INTERFACE
    # Errors are values and invariants abort (base/assert.hpp); no RTTI. Smaller and faster code.
    $<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions -fno-rtti>
    # Contraction off for the whole core: clang fuses a*b + c by default on arm64, Python and the JVM never do. A fused
    # operation appears only where the port has one, written explicitly (std::fma, simd::fma, hlsl::fma_exact).
    -ffp-contract=off
    # Lets sqrt compile to its instruction; changes no result (sqrt is correctly rounded either way).
    -fno-math-errno
    -fno-omit-frame-pointer)
if(ATLAS_CXX26)
    # After CMake's -std=c++23 on the command line, so it wins (CMake's own C++26 flag table lags behind the compilers).
    target_compile_options(atlas_options INTERFACE $<$<COMPILE_LANGUAGE:CXX>:-std=c++2c>)
endif()
target_compile_definitions(atlas_options INTERFACE
    ATLAS_VERSION="${PROJECT_VERSION}"
    ATLAS_BUILD_KIND="${ATLAS_BUILD}"
    ATLAS_CORE_FLAGS="fp-contract=off, no exceptions, no rtti"
    $<$<NOT:$<STREQUAL:${ATLAS_BUILD},debug>>:NDEBUG>)
# Source paths in the binaries (ATLAS_ASSERT's std::source_location, __FILE__) relative to the repository, so no build
# carries the machine's own paths (/Users/<name>/... on a developer's Mac); debug information keeps its absolute paths.
target_compile_options(atlas_options INTERFACE "-fmacro-prefix-map=${ATLAS_REPO_DIR}/=")
# Symbols are hidden unless exported on purpose (JNI entry points, atlas_build_info), in every build kind.
target_compile_options(atlas_options INTERFACE -fvisibility=hidden $<$<COMPILE_LANGUAGE:CXX>:-fvisibility-inlines-hidden>)
if(ATLAS_BUILD STREQUAL "debug")
    target_compile_options(atlas_options INTERFACE -O1 -g)
else()
    target_compile_options(atlas_options INTERFACE -O2 -ffunction-sections -fdata-sections)
    if(ATLAS_BUILD STREQUAL "bench")
        target_compile_options(atlas_options INTERFACE -g)
    endif()
    option(ATLAS_LTO "ThinLTO in bench and release builds" ON)
    if(ATLAS_LTO AND NOT ATLAS_SANITIZE)
        target_compile_options(atlas_options INTERFACE -flto=thin)
        target_link_options(atlas_options INTERFACE -flto=thin)
        if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
            target_link_options(atlas_options INTERFACE -fuse-ld=lld)
        endif()
    endif()
    if(APPLE)
        target_link_options(atlas_options INTERFACE -Wl,-dead_strip)
    elseif(ATLAS_ANDROID)
        target_link_options(atlas_options INTERFACE -Wl,--gc-sections -Wl,--icf=safe)
    else()
        target_link_options(atlas_options INTERFACE -Wl,--gc-sections)
    endif()
endif()

# slangc (plan-vector T6): Gradle's fetchSlang task unpacks the pinned release into the tools cache; CI does the same
# from tools.properties. CMake never downloads it.
if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
    set(_atlas_host_os macos)
elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
    set(_atlas_host_os linux)
else()
    set(_atlas_host_os unsupported)
endif()
if(CMAKE_HOST_SYSTEM_PROCESSOR MATCHES "^(arm64|aarch64|ARM64)$")
    set(_atlas_host_arch aarch64)
else()
    set(_atlas_host_arch x86_64)
endif()
set(ATLAS_HOST_PLATFORM "${_atlas_host_os}-${_atlas_host_arch}")
set(ATLAS_SLANG_VERSION "${ATLAS_PIN_slang.version}")
set(ATLAS_SLANGC "" CACHE FILEPATH "slangc of the pinned Slang release")
if(NOT ATLAS_SLANGC)
    if(DEFINED ENV{ATLAS_SLANGC})
        set(ATLAS_SLANGC "$ENV{ATLAS_SLANGC}")
    else()
        set(ATLAS_SLANGC "${ATLAS_TOOLS_DIR}/slang-${ATLAS_SLANG_VERSION}-${ATLAS_HOST_PLATFORM}/bin/slangc")
    endif()
endif()
# Stage P's tool alone (ATLAS_STAGE_P) compiles no shader: it needs no slangc.
if(NOT ATLAS_STAGE_P)
    if(NOT EXISTS "${ATLAS_SLANGC}")
        message(FATAL_ERROR "slangc ${ATLAS_SLANG_VERSION} is not at ${ATLAS_SLANGC}. Fetch it with "
            "'./gradlew :mapcore:fetchSlang' (from app/android), or set ATLAS_SLANGC.")
    endif()
    execute_process(COMMAND "${ATLAS_SLANGC}" -v OUTPUT_VARIABLE _slang_v ERROR_VARIABLE _slang_v
        RESULT_VARIABLE _slang_rc OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_STRIP_TRAILING_WHITESPACE)
    string(STRIP "${_slang_v}" _slang_v)
    if(NOT _slang_rc EQUAL 0 OR NOT _slang_v STREQUAL ATLAS_SLANG_VERSION)
        message(FATAL_ERROR "${ATLAS_SLANGC} reports '${_slang_v}', but the core is pinned to Slang "
            "${ATLAS_SLANG_VERSION}: a compiler upgrade is a reviewed change of mapcore/tools.properties.")
    endif()
endif()
target_compile_definitions(atlas_options INTERFACE ATLAS_SLANG_VERSION="${ATLAS_SLANG_VERSION}")

# A host C++ compiler for the build's own tools (tools/nocontract) when the core itself is cross-compiled.
if(ATLAS_ANDROID)
    set(ATLAS_HOST_CXX "" CACHE FILEPATH "A host C++23 compiler for the build's tools")
    # The host's compilers come from PATH only: CMake's Android platform adds the NDK's own bin (whose clang++
    # targets Android) to the system program path, and the toolchain file sets a find root.
    set(_atlas_host_search NO_CMAKE_PATH NO_CMAKE_ENVIRONMENT_PATH NO_CMAKE_SYSTEM_PATH NO_CMAKE_FIND_ROOT_PATH)
    if(NOT ATLAS_HOST_CXX)
        if(DEFINED ENV{ATLAS_HOST_CXX})
            # A path or a command name (android.yml exports clang++-21 for :mapcorecheck's host build, which means the
            # same: a host C++ compiler), resolved through PATH.
            find_program(_atlas_host_cxx_env NAMES "$ENV{ATLAS_HOST_CXX}" ${_atlas_host_search} NO_CACHE)
            set(ATLAS_HOST_CXX "${_atlas_host_cxx_env}")
        elseif(CMAKE_HOST_APPLE)
            execute_process(COMMAND xcrun --find clang++ OUTPUT_VARIABLE ATLAS_HOST_CXX OUTPUT_STRIP_TRAILING_WHITESPACE)
        else()
            # The first compiler that compiles nocontract itself, with its flags and the environment's CXXFLAGS (which
            # ExternalProject passes on). It needs C++23's <expected>, which clang before 19 lacks with libstdc++ (its
            # __cpp_concepts stays below the 202002L libstdc++'s <expected> requires); clang 18 is the default clang++
            # of GitHub's ubuntu-24.04 runners (android.yml, release.yml), where g++ 13 has it.
            set(_atlas_probe "${CMAKE_CURRENT_LIST_DIR}/../tools/nocontract/nocontract.cpp")
            separate_arguments(_atlas_env_flags UNIX_COMMAND "$ENV{CXXFLAGS}")
            foreach(_candidate IN ITEMS clang++-21 clang++ g++ c++)
                string(MAKE_C_IDENTIFIER "_atlas_host_cxx_${_candidate}" _var)
                # A variable of its own: find_program skips its search when its variable already exists in the cache.
                find_program(${_var} NAMES ${_candidate} ${_atlas_host_search})
                if(${_var})
                    execute_process(COMMAND "${${_var}}" -std=c++2b -fno-exceptions -fno-rtti ${_atlas_env_flags}
                                            -fsyntax-only "${_atlas_probe}"
                        RESULT_VARIABLE _atlas_rc OUTPUT_QUIET ERROR_QUIET)
                    if(_atlas_rc EQUAL 0)
                        set(ATLAS_HOST_CXX "${${_var}}")
                        break()
                    endif()
                endif()
            endforeach()
        endif()
    endif()
    if(NOT ATLAS_HOST_CXX OR NOT EXISTS "${ATLAS_HOST_CXX}")
        message(FATAL_ERROR "No host C++ compiler with C++23's <expected> for the build's tools; set ATLAS_HOST_CXX.")
    endif()
endif()
