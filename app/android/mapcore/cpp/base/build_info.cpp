#include "base/build_info.hpp"

#include "base/std_check.hpp"

#include <version>

// Set by CMake (cmake/Toolchains.cmake); the defaults keep the file compiling on its own.
#ifndef ATLAS_VERSION
#define ATLAS_VERSION "0"
#endif
#ifndef ATLAS_BUILD_KIND
#define ATLAS_BUILD_KIND "unknown"
#endif
#ifndef ATLAS_SLANG_VERSION
#define ATLAS_SLANG_VERSION "none"
#endif
#ifndef ATLAS_CORE_FLAGS
#define ATLAS_CORE_FLAGS "flags unknown"
#endif

#define ATLAS_STRINGIFY_INNER(x) #x
#define ATLAS_STRINGIFY(x) ATLAS_STRINGIFY_INNER(x)

#if defined(__ANDROID__)
#define ATLAS_TARGET_OS "android-" ATLAS_STRINGIFY(__ANDROID_API__)
#elif defined(__APPLE__)
#define ATLAS_TARGET_OS "macos"
#elif defined(__linux__)
#define ATLAS_TARGET_OS "linux"
#else
#define ATLAS_TARGET_OS "other"
#endif

#if defined(__aarch64__)
#define ATLAS_TARGET_ARCH "arm64"
#elif defined(__x86_64__)
#define ATLAS_TARGET_ARCH "x86_64"
#else
#define ATLAS_TARGET_ARCH "other"
#endif

#if defined(__has_feature)
#if __has_feature(address_sanitizer)
#define ATLAS_SANITIZER_ADDRESS " asan"
#endif
#if __has_feature(hwaddress_sanitizer)
#define ATLAS_SANITIZER_HWADDRESS " hwasan"
#endif
#if __has_feature(thread_sanitizer)
#define ATLAS_SANITIZER_THREAD " tsan"
#endif
#endif
#ifndef ATLAS_SANITIZER_ADDRESS
#define ATLAS_SANITIZER_ADDRESS ""
#endif
#ifndef ATLAS_SANITIZER_HWADDRESS
#define ATLAS_SANITIZER_HWADDRESS ""
#endif
#ifndef ATLAS_SANITIZER_THREAD
#define ATLAS_SANITIZER_THREAD ""
#endif

namespace {

constexpr const char* line =
    "atlasmap " ATLAS_VERSION " (" ATLAS_BUILD_KIND "); " __VERSION__
    "; libc++ " ATLAS_STRINGIFY(_LIBCPP_VERSION) "; C++ " ATLAS_STRINGIFY(
        __cplusplus) "; " ATLAS_TARGET_OS " " ATLAS_TARGET_ARCH
                     "; " ATLAS_CORE_FLAGS ATLAS_SANITIZER_ADDRESS ATLAS_SANITIZER_HWADDRESS ATLAS_SANITIZER_THREAD
                     "; slang " ATLAS_SLANG_VERSION;

} // namespace

namespace atlas {

std::string_view build_info() noexcept { return line; }

} // namespace atlas

extern "C" const char* atlas_build_info() noexcept { return line; }
