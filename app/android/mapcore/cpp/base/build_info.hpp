// What the core was built with: the compiler and its C++ library, the language level, the dialect, the floating point
// and the shader compiler. VK facts and the bench print it, and K0's feature probe records it per toolchain.
#pragma once

#include <span>
#include <string>
#include <string_view>

namespace atlas {

/// One line: "atlasmap <version>; <compiler>; libc++ <version>; C++ <level>; <target>; <flags>; slang <version>".
[[nodiscard]] std::string_view build_info() noexcept;

/// The core's build ID, 16 hex digits: a hash of the line above, of every embedded SPIR-V module's SHA-256 and of the
/// linker's own identity of the binary the core is in (the GNU build ID that NDK r30 links every library with,
/// --build-id=sha1, or a Mach-O's LC_UUID), so any change of the core's code, shaders or build gives another. The crash
/// guard keys its verdicts by it (plan-vector D5.10): a new build is not held to an older one's crash.
[[nodiscard]] std::string_view build_id() noexcept;

/// The linker's identity of the binary the core is in, as hex ("" where the binary has none).
[[nodiscard]] std::string_view linker_id() noexcept;

/// The GNU build ID among an ELF object's notes (a PT_NOTE segment as mapped: 4-byte aligned entries of the name's
/// size, the description's size and the type, then the name and the description), as hex; "" when it has none or the
/// notes are damaged. linker_id() reads the core's own notes with it; its test, notes of its own.
[[nodiscard]] std::string gnu_build_id(std::span<const unsigned char> notes);

} // namespace atlas

/// The same line for C callers and tools (exported from libatlasmap.so).
extern "C" [[gnu::visibility("default")]] const char* atlas_build_info() noexcept;
