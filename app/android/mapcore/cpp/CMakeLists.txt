# Atlas's map core: the C++ engine (plan-vector T3-T10). One CMake project builds it for Android, through Gradle's
# externalNativeBuild (mapcore/build.gradle.kts) into libatlasmap.so, and for hosts through CMakePresets.json, where
# the unit tests run on lavapipe under sanitizers, the fuzzers run on Linux and clang-tidy checks every source.
# CMake never downloads anything: third-party code is vendored in third_party/ (VERSIONS.md), and slangc comes from
# Gradle's pinned fetch task (mapcore/tools.properties).
#
# Sources are found by directory, so a package adds its files without editing this file: everything under the core's
# areas below goes into the core; platform/android on Android; jni/Test*.cpp (test entry points for
# :mapcorecheck) into the host library only; tests/**/*.cpp into atlas_tests (a file ending in _vk_test.cpp uses
# Vulkan-Hpp and gets its precompiled header; tests/planted/ is left out: CI adds one of its planted failures at a time
# through ATLAS_EXTRA_TEST_SOURCES and requires the run to fail); bench/*_bench.cpp and fuzz/fuzz_*.cpp into their own
# executables; shaders/**/*.slang (shaders/shared/ excepted) through cmake/Shaders.cmake; stagep/ into the packer's tool
# (atlas_stagep) on hosts. ATLAS_STAGE_P builds that tool alone: stage P needs no slangc, Vulkan or text stack.
cmake_minimum_required(VERSION 3.28...4.1)
project(atlasmap VERSION 0.1.0 DESCRIPTION "Atlas's map core" LANGUAGES C CXX)

option(ATLAS_STAGE_P "Build stage P's tool alone (atlas_stagep, the packer's): no slangc, shaders, Vulkan or text stack"
       OFF)

list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
include(Toolchains)
include(Warnings)
include(Sanitizers)
include(Tidy)
include(Shaders)
include(Data)

option(ATLAS_TESTS "Build the unit tests" ${ATLAS_HOST})
option(ATLAS_BENCH "Build the microbenchmarks" OFF)
option(ATLAS_FUZZ "Build the fuzz targets" OFF)
set(ATLAS_EXTRA_TEST_SOURCES "" CACHE STRING "More test sources, compiled into atlas_tests (CI's planted failures)")
# CI's planted failure of pymath's check (core.yml's canaries): the platform's sin in pymath's place, defined for
# base/pymath/fdlibm/Trig.cpp alone, so the rest of the canary's build is the host build's own, and ccache's.
option(ATLAS_PLANTED_PLATFORM_SIN "Plant the platform's sin in pymath's (CI's canary; never in a real build)" OFF)
if(ATLAS_TESTS)
    enable_testing()
endif()

# ---- Third-party code (third_party/VERSIONS.md), as SYSTEM headers so the core's warnings stay the core's own.

add_library(atlas_vulkan_headers INTERFACE)
target_include_directories(atlas_vulkan_headers SYSTEM INTERFACE third_party/vulkan-headers/include)
# No prototypes (the core loads every Vulkan function through the loader) and Android's platform types on every target
# (the host build compiles the phone's code paths; vulkan_android.h only declares ANativeWindow and AHardwareBuffer).
target_compile_definitions(atlas_vulkan_headers INTERFACE VK_NO_PROTOTYPES VK_USE_PLATFORM_ANDROID_KHR)

add_library(atlas_third_party INTERFACE)
target_include_directories(atlas_third_party SYSTEM INTERFACE
    third_party/vma/include third_party/earcut/include third_party/vulkan-profiles/include)
target_link_libraries(atlas_third_party INTERFACE atlas_vulkan_headers)

# zstd 1.5.7, decompression only, compiled with the core's dialect and its own (silent) warnings.
file(GLOB _zstd_sources CONFIGURE_DEPENDS third_party/zstd/lib/common/*.c third_party/zstd/lib/decompress/*.c)
add_library(atlas_zstd STATIC ${_zstd_sources})
target_include_directories(atlas_zstd SYSTEM PUBLIC third_party/zstd/lib)
target_compile_definitions(atlas_zstd PRIVATE ZSTD_DISABLE_ASM=1 ZSTD_LEGACY_SUPPORT=0 ZSTD_TRACE=0 DEBUGLEVEL=0)
target_compile_options(atlas_zstd PRIVATE -w)
target_link_libraries(atlas_zstd PRIVATE atlas_options atlas_sanitizers)

# HarfBuzz 14.6.0 (K13): the shaper Android's text stack runs, compiled as its amalgamation (src/harfbuzz.cc) in the
# core's dialect (no exceptions, no RTTI, contraction off, hidden symbols) with its own (silent) warnings. Built: the
# OpenType shaper (GSUB, GPOS, GDEF, with device tables' hinting, the legacy kern table and the fallback shaper, as
# Android builds it), variations (fvar, avar, gvar, HVAR, MVAR) and glyph outlines (hb_font_draw_glyph). Left out: the
# platform backends (none is enabled), colour, bitmap and SVG glyphs, CFF, AAT, the C library's locale, errno and
# environment, and the APIs the core never calls. VERSIONS.md names Atlas's one change to it (hb_font_set_em_mult_floor).
set(ATLAS_HARFBUZZ_OPTIONS
    HB_DISABLE_DEPRECATED HB_NO_AAT HB_NO_ATEXIT HB_NO_BITMAP HB_NO_BUFFER_MESSAGE HB_NO_BUFFER_SERIALIZE
    HB_NO_BUFFER_VERIFY HB_NO_CFF HB_NO_COLOR HB_NO_ERRNO HB_NO_FACE_COLLECT_UNICODES HB_NO_GETENV
    HB_NO_LAYOUT_COLLECT_GLYPHS HB_NO_LAYOUT_FEATURE_PARAMS HB_NO_LAYOUT_RARELY_USED HB_NO_LAYOUT_UNUSED HB_NO_MATH
    HB_NO_META HB_NO_MMAP HB_NO_NAME HB_NO_OPEN HB_NO_OT_FETCH HB_NO_OT_FONT_GLYPH_NAMES HB_NO_PAINT HB_NO_SETLOCALE
    HB_NO_STYLE HB_NO_SVG HB_NO_VERTICAL)
if(NOT ATLAS_STAGE_P)
    add_library(atlas_harfbuzz STATIC third_party/harfbuzz/src/harfbuzz.cc)
    target_include_directories(atlas_harfbuzz SYSTEM PUBLIC third_party/harfbuzz/src)
    target_compile_definitions(atlas_harfbuzz PRIVATE ${ATLAS_HARFBUZZ_OPTIONS})
    target_compile_options(atlas_harfbuzz PRIVATE -w)
    target_link_libraries(atlas_harfbuzz PRIVATE atlas_options atlas_sanitizers)
endif()

# ---- The build's own tools: nocontract checks exact shader modules. Cross-compiling, it is built for the host.

# Its check of specialization constants refuses by the pinned Slang's SPIR-V grammar (tools/nocontract/grammar.hpp,
# derived from the table Slang's compiler embeds by tools/nocontract/grammar.py): another Slang may know instructions
# the table lacks, so the table must be derived again first.
file(STRINGS "${CMAKE_CURRENT_SOURCE_DIR}/tools/nocontract/grammar.hpp" _grammar_version REGEX "slang_version = \"")
string(REGEX MATCH "\"([^\"]*)\"" _grammar_match "${_grammar_version}")
if(NOT CMAKE_MATCH_1 STREQUAL ATLAS_SLANG_VERSION)
    message(FATAL_ERROR "tools/nocontract/grammar.hpp holds Slang ${CMAKE_MATCH_1}'s SPIR-V grammar, but the core is "
        "pinned to Slang ${ATLAS_SLANG_VERSION}: derive it from the new release with tools/nocontract/grammar.py.")
endif()

if(ATLAS_STAGE_P)
    # stage P compiles no shader
elseif(ATLAS_HOST)
    add_subdirectory(tools/nocontract)
    set(ATLAS_NOCONTRACT "$<TARGET_FILE:nocontract>")
    set(ATLAS_NOCONTRACT_DEPENDS nocontract)
else()
    include(ExternalProject)
    set(_host_tools "${CMAKE_BINARY_DIR}/host-tools")
    ExternalProject_Add(atlas_host_tools
        SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/tools/nocontract"
        BINARY_DIR "${_host_tools}"
        CMAKE_ARGS -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_COMPILER=${ATLAS_HOST_CXX} -G "${CMAKE_GENERATOR}"
                   -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
        BUILD_BYPRODUCTS "${_host_tools}/nocontract"
        # Rebuilt on every build (a no-op when nothing changed): ExternalProject alone never sees its sources change.
        BUILD_ALWAYS TRUE
        INSTALL_COMMAND ""
        DOWNLOAD_COMMAND ""
        UPDATE_COMMAND "")
    set(ATLAS_NOCONTRACT "${_host_tools}/nocontract")
    # The target for ordering, the file so that every module is processed again when the tool changes.
    set(ATLAS_NOCONTRACT_DEPENDS atlas_host_tools "${_host_tools}/nocontract")
endif()

# ---- The core.

# The core's areas are its top-level directories (base, gpu, tiles, style, view, geometry for stage T, ...), except the
# build's own (cmake, tools, and build/, where the presets put their build trees and which git ignores), the shaders
# (cmake/Shaders.cmake), the per-target ones (platform, jni, stagep) and what surrounds the core (tests, fuzz, bench).
file(GLOB _entries CONFIGURE_DEPENDS LIST_DIRECTORIES true RELATIVE "${CMAKE_CURRENT_SOURCE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}/*")
set(ATLAS_AREAS "")
foreach(entry IN LISTS _entries)
    if(IS_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/${entry}"
       AND NOT entry MATCHES "^(cmake|third_party|tools|shaders|platform|jni|stagep|tests|fuzz|bench|build|\\..*)$")
        list(APPEND ATLAS_AREAS "${entry}")
    endif()
endforeach()
# The areas that include Vulkan-Hpp (base/vulkan.hpp), compiled with it as a precompiled header: the device layer, the
# stand-in, and the frame (its builder and passes, and the scene's heaps and residency, which see the device layer),
# and the effects (fx: the noise fields' images and the byte tables, a provider of set 1).
set(ATLAS_VULKAN_AREAS gpu standin frame passes scene fx)
set(_core_sources "")
set(_vulkan_sources "")
foreach(area IN LISTS ATLAS_AREAS)
    file(GLOB_RECURSE _found CONFIGURE_DEPENDS "${area}/*.cpp" "${area}/*.c")
    if(area IN_LIST ATLAS_VULKAN_AREAS)
        list(APPEND _vulkan_sources ${_found})
    else()
        list(APPEND _core_sources ${_found})
    endif()
endforeach()
if(ATLAS_ANDROID)
    file(GLOB_RECURSE _platform_sources CONFIGURE_DEPENDS platform/android/*.cpp)
    list(APPEND _vulkan_sources ${_platform_sources})
endif()
file(GLOB _jni_sources CONFIGURE_DEPENDS jni/*.cpp)
set(_jni_test_sources ${_jni_sources})
list(FILTER _jni_sources EXCLUDE REGEX "/Test[^/]*\\.cpp$")
list(FILTER _jni_test_sources INCLUDE REGEX "/Test[^/]*\\.cpp$")

# Stage P (ENGINE.md step 3): OpenStreetMap's data into render tiles, for the phone's windows and the packer alike:
# pymath, the Overpass reader, the loads and the cut, the tiles' reader and writer, the text's case and code points and
# the roofs' shapes. Its objects are a library of their own (atlas_stage_p), in the core and in the packer's tool, which
# links nothing else: no Vulkan, shaders or HarfBuzz.
set(_stage_p_sources "")
foreach(area IN ITEMS base/pymath osm load tiles)
    file(GLOB_RECURSE _found CONFIGURE_DEPENDS "${area}/*.cpp" "${area}/*.c")
    list(APPEND _stage_p_sources ${_found})
endforeach()
list(APPEND _stage_p_sources "${CMAKE_CURRENT_SOURCE_DIR}/text/Case.cpp" "${CMAKE_CURRENT_SOURCE_DIR}/text/Utf8.cpp"
     "${CMAKE_CURRENT_SOURCE_DIR}/geometry/RoofShapes.cpp")
list(REMOVE_ITEM _core_sources ${_stage_p_sources})

# What stage P's code links: dialect, warnings, sanitizers, zstd, and the generated headers under the build directory
# (gen/data/*: the style file and the traditions).
find_package(Threads REQUIRED)
add_library(atlas_stage_p_requirements INTERFACE)
target_include_directories(atlas_stage_p_requirements INTERFACE "${CMAKE_CURRENT_SOURCE_DIR}" "${CMAKE_BINARY_DIR}")
target_link_libraries(atlas_stage_p_requirements INTERFACE
    atlas_options atlas_warnings atlas_sanitizers atlas_zstd Threads::Threads)
if(ATLAS_ANDROID)
    target_link_libraries(atlas_stage_p_requirements INTERFACE log android)
else()
    target_link_libraries(atlas_stage_p_requirements INTERFACE ${CMAKE_DL_LIBS})
endif()

# The data compiled into the core (cmake/Data.cmake): the engine's style file, which the packs it builds carry, and the
# building traditions a window takes from its country (render/traditions' five, read as data until the renderer
# retires and they join the engine's style).
atlas_embed_data(engine_style "${CMAKE_CURRENT_SOURCE_DIR}/../style/engine.jsonc")
foreach(_tradition IN ITEMS fr_paris gb_terrace it_tuscan nl_amsterdam no_timber)
    atlas_embed_data(tradition_${_tradition} "${CMAKE_CURRENT_SOURCE_DIR}/../../../../render/traditions/${_tradition}.json")
endforeach()

add_library(atlas_stage_p OBJECT ${_stage_p_sources})
target_link_libraries(atlas_stage_p PUBLIC atlas_stage_p_requirements)
if(ATLAS_PLANTED_PLATFORM_SIN)
    set_source_files_properties(base/pymath/fdlibm/Trig.cpp PROPERTIES COMPILE_DEFINITIONS ATLAS_PLANTED_PLATFORM_SIN)
endif()
atlas_tidy(atlas_stage_p)

# The packer's tool (stagep/main.cpp): a region's or the living map's render tiles from Overpass's JSON, as the packer
# writes them into its packs, so a pack's tiles and a window the phone builds come from the same code.
if(ATLAS_HOST)
    add_executable(atlas_stagep stagep/main.cpp $<TARGET_OBJECTS:atlas_stage_p>)
    target_link_libraries(atlas_stagep PRIVATE atlas_stage_p_requirements)
    atlas_tidy(atlas_stagep)
endif()
if(ATLAS_STAGE_P)
    return()
endif()

# What every target built from the core's code links: stage P's, the third-party headers and HarfBuzz, and the
# generated headers (gen/spirv/*, gen/layouts.hpp) under the build directory.
add_library(atlas_core_requirements INTERFACE)
target_link_libraries(atlas_core_requirements INTERFACE atlas_stage_p_requirements atlas_third_party atlas_harfbuzz)

add_library(atlas_core OBJECT ${_core_sources})
target_link_libraries(atlas_core PUBLIC atlas_core_requirements)
atlas_add_shaders(atlas_core "${CMAKE_CURRENT_SOURCE_DIR}/shaders")
atlas_tidy(atlas_core)
set(_core_objects $<TARGET_OBJECTS:atlas_core> $<TARGET_OBJECTS:atlas_stage_p>)

# Compiles sources that include Vulkan-Hpp with base/vulkan.hpp as their precompiled header. Those units take a
# gigabyte or more each, so Ninja compiles at most two at once (the owner's Mac is small on memory).
set_property(GLOBAL APPEND PROPERTY JOB_POOLS atlas_heavy=2)
function(atlas_vulkan_objects target)
    add_library(${target} OBJECT ${ARGN})
    target_link_libraries(${target} PUBLIC atlas_core_requirements)
    target_precompile_headers(${target} PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/base/vulkan.hpp")
    # No timestamp in the precompiled header, so ccache (android.yml) can cache the units that use it.
    target_compile_options(${target} PRIVATE "SHELL:-Xclang -fno-pch-timestamp")
    set_target_properties(${target} PROPERTIES JOB_POOL_COMPILE atlas_heavy)
    add_dependencies(${target} atlas_core_shaders)
    atlas_tidy(${target})
endfunction()

if(_vulkan_sources)
    atlas_vulkan_objects(atlas_core_vulkan ${_vulkan_sources})
    list(APPEND _core_objects $<TARGET_OBJECTS:atlas_core_vulkan>)
endif()

# The shared library: libatlasmap.so on Android, libatlasmap_host on hosts (with the test entry points :mapcorecheck
# calls). Only JNI entry points and atlas_* functions are exported.
if(ATLAS_ANDROID)
    add_library(atlasmap SHARED ${_core_objects} ${_jni_sources})
    set(_exports "${CMAKE_BINARY_DIR}/atlasmap.map")
    # Patterns only: lld refuses a version script that names a symbol the library does not define.
    file(CONFIGURE OUTPUT "${_exports}" CONTENT
        "{\n  global: JNI_On*; Java_*; atlas_*;\n  local: *;\n};\n")
    target_link_options(atlasmap PRIVATE "-Wl,--version-script=${_exports}" -Wl,-z,max-page-size=16384)
    set_target_properties(atlasmap PROPERTIES LINK_DEPENDS "${_exports}")
else()
    add_library(atlasmap SHARED ${_core_objects} ${_jni_sources} ${_jni_test_sources})
    set_target_properties(atlasmap PROPERTIES OUTPUT_NAME atlasmap_host)
    if(_jni_sources OR _jni_test_sources)
        # jni.h from the JDK that runs :mapcorecheck (JAVA_HOME; on macOS, else the one java_home names); Android's
        # comes with the NDK.
        if(NOT JAVA_HOME AND NOT DEFINED ENV{JAVA_HOME} AND CMAKE_HOST_APPLE AND EXISTS /usr/libexec/java_home)
            execute_process(COMMAND /usr/libexec/java_home OUTPUT_VARIABLE JAVA_HOME OUTPUT_STRIP_TRAILING_WHITESPACE
                ERROR_QUIET)
        endif()
        find_package(JNI REQUIRED COMPONENTS JVM)
        target_include_directories(atlasmap SYSTEM PRIVATE ${JNI_INCLUDE_DIRS})
    endif()
endif()
target_link_libraries(atlasmap PRIVATE atlas_core_requirements)
atlas_tidy(atlasmap)

# ---- Tests (doctest), benchmarks, fuzzers.

# Where the tests and the fuzz targets keep the files they write (ATLAS_TEST_SCRATCH): the build folder, never the
# system's temp folder, where a file a run leaves (a crash's, or one kept to look at) would pile up.
set(ATLAS_TEST_SCRATCH "${CMAKE_BINARY_DIR}/scratch")

if(ATLAS_TESTS)
    file(GLOB_RECURSE _test_sources CONFIGURE_DEPENDS tests/*.cpp)
    # tests/planted/ holds the planted failures CI proves the sanitizers and clang-tidy catch; never in a normal build.
    list(FILTER _test_sources EXCLUDE REGEX "/tests/planted/")
    set(_test_vulkan_sources ${_test_sources})
    list(FILTER _test_sources EXCLUDE REGEX "_vk_test\\.cpp$")
    list(FILTER _test_vulkan_sources INCLUDE REGEX "_vk_test\\.cpp$")
    add_library(atlas_doctest INTERFACE)
    target_include_directories(atlas_doctest SYSTEM INTERFACE third_party/doctest)
    # REQUIRE stays available without exceptions, but a failed REQUIRE only reports and fails the test case, which runs
    # on: what a test cannot go on without it requires with ATLAS_REQUIRE (tests/base/require.hpp), which returns.
    # The text tests read the bundled fonts (mapview's assets) and the tests' own data (tests/text).
    target_compile_definitions(atlas_doctest INTERFACE DOCTEST_CONFIG_NO_EXCEPTIONS_BUT_WITH_ALL_ASSERTS
                                                        ATLAS_TEST_SCRATCH="${ATLAS_TEST_SCRATCH}"
        ATLAS_FONTS_DIR="${ATLAS_MAPCORE_DIR}/../mapview/src/main/assets/svgfonts"
        ATLAS_TESTS_DIR="${CMAKE_CURRENT_SOURCE_DIR}/tests")
    add_executable(atlas_tests ${_test_sources} ${ATLAS_EXTRA_TEST_SOURCES} ${_core_objects})
    target_link_libraries(atlas_tests PRIVATE atlas_core_requirements atlas_doctest)
    add_dependencies(atlas_tests atlas_core_shaders)
    if(_test_vulkan_sources)
        atlas_vulkan_objects(atlas_tests_vulkan ${_test_vulkan_sources})
        target_link_libraries(atlas_tests_vulkan PRIVATE atlas_doctest)
        target_sources(atlas_tests PRIVATE $<TARGET_OBJECTS:atlas_tests_vulkan>)
    endif()
    atlas_tidy(atlas_tests)
    # One ctest entry per test file, tests/<area>/<name>.cpp as <area>/<name>, each a process of its own, so ctest -j
    # runs them side by side and picks a part by label: the area (ctest -L <area>), vk (every *_vk_test.cpp) or
    # atlas_tests (all of them). doctest's --source-file takes the cases a file defines by their __FILE__, which ends in
    # tests/<area>/<name>.cpp however the build names the source (-fmacro-prefix-map makes it relative to the
    # repository, ccache's base_dir relative to the build folder). atlas_tests_rest runs every case none of them takes
    # (one a header defines, say), so the split loses none, and atlas_tests_split proves it (cmake/TestSplit.cmake).
    # Each entry prints its cases' seconds (--duration), where a file's cost lies. A file whose cases take too long for
    # one entry, beside the runner's others, runs as parts (_test_parts, <area>/<name>=<n>,<n>...): part k, the entry
    # <area>/<name>:<k>, takes the next n of the cases the file defines in their order (doctest's --first and --last
    # over them), and the last part every case after those, so a case added later runs too.
    # building/buildings_vk_test took 17 of a shard's 18 minutes of tests on 30 October (30 under TSan),
    # ground/ripple_vk_test and frame/map_frame_vk_test 11 each, frame/composite_vk_test 9 under TSan: their cases one a
    # part (each opens its own session, so a process apiece costs little).
    set(_test_parts "building/buildings_vk_test=1,1,1,1,1,1,1,1,1" "frame/map_frame_vk_test=1,1,1,1,1"
        "ground/ripple_vk_test=1,1,1" "frame/composite_vk_test=1,1,1")
    set(_test_patterns "")
    set(_test_entries "")
    foreach(source IN LISTS _test_sources _test_vulkan_sources)
        file(RELATIVE_PATH _test_file "${CMAKE_CURRENT_SOURCE_DIR}/tests" "${source}")
        if(_test_file STREQUAL "main.cpp")
            continue()
        endif()
        string(REGEX REPLACE "\\.cpp$" "" _test_name "${_test_file}")
        string(REGEX MATCH "^[^/]+/" _test_area "${_test_file}")
        string(REGEX REPLACE "/$" "" _test_area "${_test_area}")
        set(_test_labels atlas_tests ${_test_area})
        if(_test_file MATCHES "_vk_test\\.cpp$")
            list(APPEND _test_labels vk)
        endif()
        set(_test_filter "--source-file=*tests/${_test_file}")
        set(_test_sizes "")
        foreach(_test_part IN LISTS _test_parts)
            if(_test_part MATCHES "^([^=]+)=(.+)$" AND CMAKE_MATCH_1 STREQUAL _test_name)
                string(REPLACE "," ";" _test_sizes "${CMAKE_MATCH_2}")
            endif()
        endforeach()
        set(_test_runs "")
        if(_test_sizes)
            set(_test_first 1)
            set(_test_k 1)
            foreach(_test_size IN LISTS _test_sizes)
                math(EXPR _test_last "${_test_first} + ${_test_size} - 1")
                list(APPEND _test_runs "${_test_name}:${_test_k}|--first=${_test_first}|--last=${_test_last}")
                math(EXPR _test_first "${_test_last} + 1")
                math(EXPR _test_k "${_test_k} + 1")
            endforeach()
            list(APPEND _test_runs "${_test_name}:${_test_k}|--first=${_test_first}")
        else()
            list(APPEND _test_runs "${_test_name}")
        endif()
        foreach(_test_run IN LISTS _test_runs)
            string(REPLACE "|" ";" _test_run "${_test_run}")
            list(POP_FRONT _test_run _test_entry)
            add_test(NAME "${_test_entry}" COMMAND atlas_tests "${_test_filter}" ${_test_run} --duration=true)
            set_tests_properties("${_test_entry}" PROPERTIES LABELS "${_test_labels}")
            list(JOIN _test_run "\t" _test_range)
            string(APPEND _test_entries "${_test_entry}\t${_test_filter}")
            if(_test_range)
                string(APPEND _test_entries "\t${_test_range}")
            endif()
            string(APPEND _test_entries "\n")
        endforeach()
        list(APPEND _test_patterns "*tests/${_test_file}")
    endforeach()
    list(JOIN _test_patterns "," _test_excluded)
    add_test(NAME atlas_tests_rest COMMAND atlas_tests "--source-file-exclude=${_test_excluded}" --duration=true)
    string(APPEND _test_entries "atlas_tests_rest\t--source-file-exclude=${_test_excluded}\n")
    file(WRITE "${CMAKE_BINARY_DIR}/atlas_tests_split.tsv" "${_test_entries}")
    add_test(NAME atlas_tests_split COMMAND "${CMAKE_COMMAND}" "-DATLAS_TESTS_EXE=$<TARGET_FILE:atlas_tests>"
        "-DATLAS_TESTS_SPLIT=${CMAKE_BINARY_DIR}/atlas_tests_split.tsv" -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/TestSplit.cmake")
    set_tests_properties(atlas_tests_rest atlas_tests_split PROPERTIES LABELS atlas_tests)
endif()
if(ATLAS_BENCH)
    add_subdirectory(bench)
endif()
if(ATLAS_FUZZ)
    add_subdirectory(fuzz)
endif()
