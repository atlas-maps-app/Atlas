# Fuzz targets (plan-vector T10): one per decoder of bytes from outside the core, each fuzz/fuzz_<name>.cpp defining
# LLVMFuzzerTestOneInput. On Linux (ATLAS_SANITIZE=fuzzer, the host-fuzz preset) they link libFuzzer; CI runs each for
# 60 s on every push and 30 minutes nightly, starting from fuzz/corpus/<target>/. Elsewhere they link standalone.cpp,
# which replays inputs and feeds random ones under the same sanitizers.
#
# fuzz/corpus/<target>/ holds the seeds and every input that ever crashed the target (a crash from CI is added there
# with its fix): CTest replays all of them on every host build, so each crash stays a regression test.
file(GLOB _fuzz_sources CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/fuzz_*.cpp")

# libFuzzer's runtime is C++ compiled against a standard library it does not carry. LLVM's Linux packages
# (apt.llvm.org's, which core.yml installs) compile it against libstdc++ (std::__cxx11), while the core uses libc++
# (-stdlib=libc++, std::__1): the driver then links libc++ only, and the runtime's std::basic_ifstream and strings stay
# undefined. So the targets also link libstdc++, which only the runtime uses; the two libraries name their types in
# different namespaces, so neither's symbols take the other's place. A runtime that links as it is gets nothing added.
set(_atlas_fuzzer_libraries "")
if(ATLAS_SANITIZE STREQUAL "fuzzer")
    include(CheckCXXSourceCompiles)
    set(_atlas_fuzzer_probe [[
#include <cstddef>
#include <cstdint>
extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t*, std::size_t) { return 0; }
]])
    set(CMAKE_REQUIRED_LINK_OPTIONS -fsanitize=fuzzer)
    check_cxx_source_compiles("${_atlas_fuzzer_probe}" ATLAS_LIBFUZZER_LINKS)
    if(NOT ATLAS_LIBFUZZER_LINKS)
        set(CMAKE_REQUIRED_LIBRARIES stdc++)
        check_cxx_source_compiles("${_atlas_fuzzer_probe}" ATLAS_LIBFUZZER_LINKS_WITH_LIBSTDCXX)
        unset(CMAKE_REQUIRED_LIBRARIES)
        if(NOT ATLAS_LIBFUZZER_LINKS_WITH_LIBSTDCXX)
            message(FATAL_ERROR "libFuzzer's runtime does not link, with or without libstdc++ (CMakeConfigureLog.yaml "
                "in the build folder has the linker's errors).")
        endif()
        message(STATUS "libFuzzer's runtime needs libstdc++: the fuzz targets link it beside the core's libc++")
        set(_atlas_fuzzer_libraries stdc++)
    endif()
    unset(CMAKE_REQUIRED_LINK_OPTIONS)
endif()

foreach(source IN LISTS _fuzz_sources)
    get_filename_component(name "${source}" NAME_WE)
    add_executable(${name} "${source}" ${_core_objects})
    target_link_libraries(${name} PRIVATE atlas_core_requirements)
    add_dependencies(${name} atlas_core_shaders)
    if(ATLAS_SANITIZE STREQUAL "fuzzer")
        target_link_options(${name} PRIVATE -fsanitize=fuzzer)
        target_link_libraries(${name} PRIVATE ${_atlas_fuzzer_libraries})
    else()
        target_sources(${name} PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/standalone.cpp")
    endif()
    atlas_tidy(${name})
    file(GLOB _corpus CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/corpus/${name}/*")
    if(_corpus AND ATLAS_TESTS)
        add_test(NAME ${name}_corpus COMMAND ${name} ${_corpus})
    endif()
endforeach()
