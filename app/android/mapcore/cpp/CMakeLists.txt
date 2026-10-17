# Atlas's map core: the C++ engine (plan-vector T3-T10). One CMake project builds it for Android, through Gradle's
# externalNativeBuild (mapcore/build.gradle.kts) into libatlasmap.so, and for hosts through CMakePresets.json, where
# the unit tests run on lavapipe under sanitizers, the fuzzers run on Linux and clang-tidy checks every source.
# CMake never downloads anything: third-party code is vendored in third_party/ (VERSIONS.md), and slangc comes from
# Gradle's pinned fetch task (mapcore/tools.properties).
#
# Sources are found by directory, so a package adds its files without editing this file: everything under the core's
# areas below goes into the core; platform/android or platform/host by target; jni/Test*.cpp (test entry points for
# :mapcorecheck) into the host library only; tests/**/*.cpp into atlas_tests (a file ending in _vk_test.cpp uses
# Vulkan-Hpp and gets its precompiled header; tests/planted/ is left out: CI adds one of its planted failures at a time
# through ATLAS_EXTRA_TEST_SOURCES and requires the run to fail); bench/*_bench.cpp and fuzz/fuzz_*.cpp into their own
# executables; shaders/**/*.slang (shaders/shared/ excepted) through cmake/Shaders.cmake.
cmake_minimum_required(VERSION 3.28...4.1)
project(atlasmap VERSION 0.1.0 DESCRIPTION "Atlas's map core" LANGUAGES C CXX)

list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
include(Toolchains)
include(Warnings)
include(Sanitizers)
include(Tidy)
include(Shaders)

option(ATLAS_TESTS "Build the unit tests" ${ATLAS_HOST})
option(ATLAS_BENCH "Build the microbenchmarks" OFF)
option(ATLAS_FUZZ "Build the fuzz targets" OFF)
set(ATLAS_EXTRA_TEST_SOURCES "" CACHE STRING "More test sources, compiled into atlas_tests (CI's planted failures)")
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

# ---- The build's own tools: nocontract checks exact shader modules. Cross-compiling, it is built for the host.

if(ATLAS_HOST)
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
# (cmake/Shaders.cmake), the per-target ones (platform, jni) and what surrounds the core (tests, fuzz, bench).
file(GLOB _entries CONFIGURE_DEPENDS LIST_DIRECTORIES true RELATIVE "${CMAKE_CURRENT_SOURCE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}/*")
set(ATLAS_AREAS "")
foreach(entry IN LISTS _entries)
    if(IS_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/${entry}"
       AND NOT entry MATCHES "^(cmake|third_party|tools|shaders|platform|jni|tests|fuzz|bench|build|\\..*)$")
        list(APPEND ATLAS_AREAS "${entry}")
    endif()
endforeach()
# The areas that include Vulkan-Hpp (base/vulkan.hpp), compiled with it as a precompiled header.
set(ATLAS_VULKAN_AREAS gpu standin)
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
else()
    file(GLOB_RECURSE _platform_sources CONFIGURE_DEPENDS platform/host/*.cpp)
endif()
list(APPEND _vulkan_sources ${_platform_sources})
file(GLOB _jni_sources CONFIGURE_DEPENDS jni/*.cpp)
set(_jni_test_sources ${_jni_sources})
list(FILTER _jni_sources EXCLUDE REGEX "/Test[^/]*\\.cpp$")
list(FILTER _jni_test_sources INCLUDE REGEX "/Test[^/]*\\.cpp$")

# What every target built from the core's code links: dialect, warnings, sanitizers, third-party headers, zstd, and
# the generated headers (gen/spirv/*, gen/layouts.hpp) under the build directory.
find_package(Threads REQUIRED)
add_library(atlas_core_requirements INTERFACE)
target_include_directories(atlas_core_requirements INTERFACE "${CMAKE_CURRENT_SOURCE_DIR}" "${CMAKE_BINARY_DIR}")
target_link_libraries(atlas_core_requirements INTERFACE
    atlas_options atlas_warnings atlas_sanitizers atlas_third_party atlas_zstd Threads::Threads)
if(ATLAS_ANDROID)
    target_link_libraries(atlas_core_requirements INTERFACE log android)
else()
    target_link_libraries(atlas_core_requirements INTERFACE ${CMAKE_DL_LIBS})
endif()

add_library(atlas_core OBJECT ${_core_sources})
target_link_libraries(atlas_core PUBLIC atlas_core_requirements)
atlas_add_shaders(atlas_core "${CMAKE_CURRENT_SOURCE_DIR}/shaders")
atlas_tidy(atlas_core)
set(_core_objects $<TARGET_OBJECTS:atlas_core>)

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
    target_compile_definitions(atlas_doctest INTERFACE DOCTEST_CONFIG_NO_EXCEPTIONS_BUT_WITH_ALL_ASSERTS)
    add_executable(atlas_tests ${_test_sources} ${ATLAS_EXTRA_TEST_SOURCES} ${_core_objects})
    target_link_libraries(atlas_tests PRIVATE atlas_core_requirements atlas_doctest)
    add_dependencies(atlas_tests atlas_core_shaders)
    if(_test_vulkan_sources)
        atlas_vulkan_objects(atlas_tests_vulkan ${_test_vulkan_sources})
        target_link_libraries(atlas_tests_vulkan PRIVATE atlas_doctest)
        target_sources(atlas_tests PRIVATE $<TARGET_OBJECTS:atlas_tests_vulkan>)
    endif()
    atlas_tidy(atlas_tests)
    add_test(NAME atlas_tests COMMAND atlas_tests)
endif()
if(ATLAS_BENCH)
    add_subdirectory(bench)
endif()
if(ATLAS_FUZZ)
    add_subdirectory(fuzz)
endif()
