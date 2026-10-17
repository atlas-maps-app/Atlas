// What the core was built with: the compiler and its C++ library, the language level, the dialect, the floating point
// and the shader compiler. VK facts and the bench print it, and K0's feature probe records it per toolchain.
#pragma once

#include <string_view>

namespace atlas {

/// One line: "atlasmap <version>; <compiler>; libc++ <version>; C++ <level>; <target>; <flags>; slang <version>".
[[nodiscard]] std::string_view build_info() noexcept;

} // namespace atlas

/// The same line for C callers and tools (exported from libatlasmap.so).
extern "C" [[gnu::visibility("default")]] const char* atlas_build_info() noexcept;
