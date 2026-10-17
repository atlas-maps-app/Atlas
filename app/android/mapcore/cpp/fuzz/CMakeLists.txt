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
# undefined. So when the runtime's own symbols name libstdc++'s types, the targets also link libstdc++, which only the
# runtime uses; the two libraries name their types in different namespaces, so neither's symbols take the other's
# place. The archive is read directly rather than probed with a test link, which compiles with the build's sanitizer
# flags and failed for reasons of its own. A runtime built against libc++ (the NDK's) gets nothing added.
set(_atlas_fuzzer_libraries "")
if(ATLAS_SANITIZE STREQUAL "fuzzer")
    execute_process(COMMAND "${CMAKE_CXX_COMPILER}" -print-resource-dir OUTPUT_VARIABLE _resource
        OUTPUT_STRIP_TRAILING_WHITESPACE RESULT_VARIABLE _rc)
    set(_runtime "")
    if(_rc EQUAL 0)
        # the older layout (lib/linux/libclang_rt.fuzzer-<arch>.a) or the per-target one (lib/<triple>/libclang_rt.fuzzer.a)
        file(GLOB _runtime "${_resource}/lib/linux/libclang_rt.fuzzer-${CMAKE_SYSTEM_PROCESSOR}*.a"
            "${_resource}/lib/${CMAKE_SYSTEM_PROCESSOR}-*/libclang_rt.fuzzer.a")
    endif()
    if(NOT _runtime)
        message(FATAL_ERROR "libFuzzer's runtime is not in ${CMAKE_CXX_COMPILER}'s resource directory (${_resource}); "
            "install LLVM's compiler-rt (libclang-rt-<version>-dev)")
    endif()
    list(GET _runtime 0 _runtime)
    execute_process(COMMAND "${CMAKE_NM}" "${_runtime}" OUTPUT_VARIABLE _symbols ERROR_QUIET RESULT_VARIABLE _rc)
    if(NOT _rc EQUAL 0)
        message(FATAL_ERROR "cannot read libFuzzer's runtime ${_runtime} with ${CMAKE_NM}")
    endif()
    string(FIND "${_symbols}" "St7__cxx11" _cxx11)
    if(_cxx11 GREATER -1)
        message(STATUS "libFuzzer's runtime (${_runtime}) is built against libstdc++: the fuzz targets link it beside "
            "the core's libc++")
        # kept needed: the driver appends the runtime after every library, and GNU ld's --as-needed (Ubuntu's default)
        # would drop libstdc++ before the runtime's references to it are seen
        set(_atlas_fuzzer_libraries "-Wl,--push-state,--no-as-needed" stdc++ "-Wl,--pop-state")
    endif()
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
