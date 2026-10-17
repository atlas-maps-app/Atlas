# Fuzz targets (plan-vector T10): one per decoder of bytes from outside the core, each fuzz/fuzz_<name>.cpp defining
# LLVMFuzzerTestOneInput. On Linux (ATLAS_SANITIZE=fuzzer, the host-fuzz preset) they link libFuzzer; CI runs each for
# 60 s on every push and 30 minutes nightly, starting from fuzz/corpus/<target>/. Elsewhere they link standalone.cpp,
# which replays inputs and feeds random ones under the same sanitizers.
#
# fuzz/corpus/<target>/ holds the seeds and every input that ever crashed the target (a crash from CI is added there
# with its fix): CTest replays all of them on every host build, so each crash stays a regression test.
file(GLOB _fuzz_sources CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/fuzz_*.cpp")
foreach(source IN LISTS _fuzz_sources)
    get_filename_component(name "${source}" NAME_WE)
    add_executable(${name} "${source}" ${_core_objects})
    target_link_libraries(${name} PRIVATE atlas_core_requirements)
    add_dependencies(${name} atlas_core_shaders)
    if(ATLAS_SANITIZE STREQUAL "fuzzer")
        target_link_options(${name} PRIVATE -fsanitize=fuzzer)
    else()
        target_sources(${name} PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/standalone.cpp")
    endif()
    atlas_tidy(${name})
    file(GLOB _corpus CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/corpus/${name}/*")
    if(_corpus AND ATLAS_TESTS)
        add_test(NAME ${name}_corpus COMMAND ${name} ${_corpus})
    endif()
endforeach()
