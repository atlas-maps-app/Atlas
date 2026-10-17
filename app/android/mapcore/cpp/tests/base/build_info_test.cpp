#include "base/build_info.hpp"

#include <doctest/doctest.h>

#include <string>
#include <string_view>

TEST_CASE("the build line names the core, the compiler, the C++ level, the flags and Slang") {
    const std::string_view info = atlas::build_info();
    CHECK(info.starts_with("atlasmap 0.1.0 ("));
    CHECK(info.find("clang") != std::string_view::npos);
    CHECK(info.find("libc++ ") != std::string_view::npos);
    CHECK(info.find("; C++ 202") != std::string_view::npos);
    CHECK(info.find("fp-contract=off") != std::string_view::npos);
    CHECK(info.find("slang 2026.19") != std::string_view::npos);
    CHECK(std::string_view(atlas_build_info()) == info);
}
