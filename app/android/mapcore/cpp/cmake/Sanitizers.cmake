# Sanitizers (plan-vector T10). ATLAS_SANITIZE picks one set for every core target, tests and fuzzers included:
#   asan            host-debug: ASan and UBSan (with LSan on Linux), every error fatal
#   tsan            host-tsan: TSan
#   ubsan-minimal   Android debug builds: UBSan's minimal runtime, which names the check and aborts
#   hwasan          the sanitized bench APK: HWASan on arm64 Android 14+ (shared libc++, through wrap.sh)
#   fuzzer          host-fuzz (Linux): libFuzzer's instrumentation with ASan and UBSan
include_guard(GLOBAL)

add_library(atlas_sanitizers INTERFACE)
if(NOT ATLAS_SANITIZE MATCHES "^(asan|tsan|ubsan-minimal|hwasan|fuzzer|)$")
    message(FATAL_ERROR "ATLAS_SANITIZE='${ATLAS_SANITIZE}' is not one of asan, tsan, ubsan-minimal, hwasan, fuzzer.")
endif()

# UBSan's checks without RTTI: vptr and function need type information the core does not carry.
set(_atlas_ubsan -fsanitize=undefined -fno-sanitize=vptr,function -fno-sanitize-recover=all)

if(ATLAS_SANITIZE STREQUAL "asan")
    target_compile_options(atlas_sanitizers INTERFACE -fsanitize=address ${_atlas_ubsan} -fno-optimize-sibling-calls)
    target_link_options(atlas_sanitizers INTERFACE -fsanitize=address ${_atlas_ubsan})
elseif(ATLAS_SANITIZE STREQUAL "tsan")
    target_compile_options(atlas_sanitizers INTERFACE -fsanitize=thread)
    target_link_options(atlas_sanitizers INTERFACE -fsanitize=thread)
elseif(ATLAS_SANITIZE STREQUAL "ubsan-minimal")
    target_compile_options(atlas_sanitizers INTERFACE ${_atlas_ubsan} -fsanitize-minimal-runtime)
    target_link_options(atlas_sanitizers INTERFACE ${_atlas_ubsan} -fsanitize-minimal-runtime)
    if(ATLAS_ANDROID)
        # The runtime goes into libatlasmap.so itself. By default the NDK's clang links the shared runtime, which no
        # APK packages, so the library would not load; with -static-libsan it links the static one into executables
        # but not into a -shared library, whose checks then stay undefined. So the archive is named explicitly, found
        # through the compiler (the NDK's clang version is in its path).
        set(_atlas_san_arch_arm64-v8a aarch64)
        set(_atlas_san_arch_x86_64 x86_64)
        set(_atlas_san_arch "${_atlas_san_arch_${ANDROID_ABI}}")
        if(NOT _atlas_san_arch)
            message(FATAL_ERROR "No UBSan runtime name for the ABI '${ANDROID_ABI}' (the core builds arm64-v8a and x86_64).")
        endif()
        execute_process(
            COMMAND "${CMAKE_CXX_COMPILER}" "--target=${CMAKE_CXX_COMPILER_TARGET}"
                    "-print-file-name=libclang_rt.ubsan_minimal-${_atlas_san_arch}-android.a"
            OUTPUT_VARIABLE _atlas_ubsan_runtime OUTPUT_STRIP_TRAILING_WHITESPACE RESULT_VARIABLE _atlas_rc)
        if(NOT _atlas_rc EQUAL 0 OR NOT IS_ABSOLUTE "${_atlas_ubsan_runtime}" OR NOT EXISTS "${_atlas_ubsan_runtime}")
            message(FATAL_ERROR "The NDK's UBSan minimal runtime for ${_atlas_san_arch} was not found "
                "('${_atlas_ubsan_runtime}').")
        endif()
        target_link_options(atlas_sanitizers INTERFACE -static-libsan)
        target_link_libraries(atlas_sanitizers INTERFACE "${_atlas_ubsan_runtime}")
    endif()
elseif(ATLAS_SANITIZE STREQUAL "hwasan")
    if(NOT (ATLAS_ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a"))
        message(FATAL_ERROR "HWASan is for arm64 Android only.")
    endif()
    if(NOT ANDROID_STL STREQUAL "c++_shared")
        message(FATAL_ERROR "HWASan needs the shared libc++ (ANDROID_STL=c++_shared).")
    endif()
    target_compile_options(atlas_sanitizers INTERFACE -fsanitize=hwaddress)
    target_link_options(atlas_sanitizers INTERFACE -fsanitize=hwaddress)
elseif(ATLAS_SANITIZE STREQUAL "fuzzer")
    if(NOT CMAKE_SYSTEM_NAME STREQUAL "Linux")
        message(FATAL_ERROR "The fuzzers build on Linux (Apple's clang has no libFuzzer runtime).")
    endif()
    target_compile_options(atlas_sanitizers INTERFACE -fsanitize=fuzzer-no-link,address ${_atlas_ubsan})
    target_link_options(atlas_sanitizers INTERFACE -fsanitize=address ${_atlas_ubsan})
endif()
