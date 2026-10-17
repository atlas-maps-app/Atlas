#include "base/build_info.hpp"

#include <doctest/doctest.h>

#include <string>
#include <string_view>

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
