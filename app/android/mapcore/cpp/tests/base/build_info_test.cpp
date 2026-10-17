// What the core was built with (base/build_info.hpp): the build line, and the build ID the crash guard keys by.
#include "base/build_info.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cstdint>
#include <cstring>
#include <string>
#include <string_view>
#include <vector>

TEST_CASE("the build line names the core, the compiler, the C++ level, the flags and Slang") {
    const std::string_view info = atlas::build_info();
    CHECK(info.starts_with("atlasmap 0.1.0 ("));
    // The compiler, as its own __VERSION__ names it: a clang of any vendor. LLVM's clang writes "<vendor> Clang
    // <version> (...)" ("Ubuntu Clang 21.1.8 (...)", the NDK's "Android (...) Clang 21.0.0 (...)"), Apple's
    // "Apple LLVM <version> (clang-<build>)".
    CHECK(info.find("; " __VERSION__ "; ") != std::string_view::npos);
    const std::string major = std::to_string(__clang_major__) + ".";
    const bool llvm_clang = info.find("Clang " + major) != std::string_view::npos;
    const bool apple_clang =
        info.find("Apple LLVM " + major) != std::string_view::npos && info.find(" (clang-") != std::string_view::npos;
    CHECK((llvm_clang || apple_clang));
    CHECK(info.find("libc++ ") != std::string_view::npos);
    CHECK(info.find("; C++ 202") != std::string_view::npos);
    CHECK(info.find("fp-contract=off") != std::string_view::npos);
    CHECK(info.find("slang 2026.19") != std::string_view::npos);
    CHECK(std::string_view(atlas_build_info()) == info);
}

TEST_CASE("the build ID is 16 hex digits, stable, and hashes the linker's own identity of the binary") {
    const std::string_view id = atlas::build_id();
    CHECK(id.size() == 16);
    CHECK(id.find_first_not_of("0123456789abcdef") == std::string_view::npos);
    CHECK(atlas::build_id() == id); // computed once
    const std::string_view linker = atlas::linker_id();
    CHECK(linker.find_first_not_of("0123456789abcdef") == std::string_view::npos);
#if defined(__APPLE__)
    CHECK(linker.size() == 32); // LC_UUID, which ld64 always writes
#elif defined(__ANDROID__)
    CHECK(linker.size() == 40); // the GNU build ID NDK r30 links with (--build-id=sha1)
#else
    // A Linux host's linker writes a build ID when its driver asks for one; the build ID is valid without it.
    MESSAGE("linker id: '" << linker << "'");
#endif
}

namespace {

/// One ELF note as an object's PT_NOTE segment holds it: sizes and type, then the name and the description, each
/// padded to four bytes.
void add_note(std::vector<unsigned char>& notes, std::uint32_t type, std::string_view name,
              const std::vector<unsigned char>& desc) {
    const std::array<std::uint32_t, 3> head{static_cast<std::uint32_t>(name.size() + 1),
                                            static_cast<std::uint32_t>(desc.size()), type};
    const std::size_t at = notes.size();
    notes.resize(at + sizeof head);
    std::memcpy(notes.data() + at, head.data(), sizeof head);
    notes.insert(notes.end(), name.begin(), name.end());
    notes.push_back(0);
    while (notes.size() % 4 != 0) notes.push_back(0);
    notes.insert(notes.end(), desc.begin(), desc.end());
    while (notes.size() % 4 != 0) notes.push_back(0);
}

} // namespace

TEST_CASE("the GNU build ID is read from an ELF object's notes, after other notes, and not past their end") {
    // As NDK r30 links libatlasmap.so: Android's ABI note first (type 1, "Android"), then the build ID (type 3, "GNU",
    // 20 bytes of SHA-1).
    std::vector<unsigned char> notes;
    add_note(notes, 1, "Android", {29, 0, 0, 0, 'r', '3', '0'});
    std::vector<unsigned char> sha1(20);
    for (std::size_t i = 0; i < sha1.size(); ++i) sha1[i] = static_cast<unsigned char>(0xA0 + i);
    add_note(notes, 3, "GNU", sha1);
    CHECK(atlas::gnu_build_id(notes) == "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3");
    // A note of another vendor with the same type, or of another type, is not it.
    std::vector<unsigned char> others;
    add_note(others, 3, "GNX", sha1);
    add_note(others, 4, "GNU", sha1);
    CHECK(atlas::gnu_build_id(others).empty());
    // Cut short anywhere, it reads nothing beyond the end.
    for (std::size_t size = 0; size < notes.size(); ++size)
        CHECK(atlas::gnu_build_id(std::span(notes.data(), size)).empty());
    // Sizes that overflow are damage, not a read beyond the end.
    std::vector<unsigned char> damaged(12, 0xFF);
    CHECK(atlas::gnu_build_id(damaged).empty());
}
