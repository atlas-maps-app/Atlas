#include "base/assert.hpp"
#include "base/log.hpp"
#include "tests/base/death.hpp"

#include <doctest/doctest.h>

#include <string>
#include <string_view>

TEST_CASE("a broken invariant aborts with its expression, reason and place") {
    const auto death = atlas::test::run_in_child([] {
        volatile int tiles = 3;
        ATLAS_ASSERT(tiles == 4, "the cover must hold four tiles");
    });
    CHECK(death.aborted);
    CHECK(death.stderr_text.find("AtlasMap F") != std::string::npos);
    CHECK(death.stderr_text.find("tiles == 4: the cover must hold four tiles") != std::string::npos);
    CHECK(death.stderr_text.find("assert_test.cpp:") != std::string::npos);
}

TEST_CASE("an invariant that holds costs nothing and returns") {
    const auto life = atlas::test::run_in_child([] {
        volatile int tiles = 4;
        ATLAS_ASSERT(tiles == 4);
        ATLAS_ASSERT(tiles > 0, "no tiles");
    });
    CHECK_FALSE(life.aborted);
    CHECK(life.stderr_text.empty());
}

TEST_CASE("an unreachable path aborts with its reason") {
    const auto death = atlas::test::run_in_child([] { ATLAS_UNREACHABLE("a fourth frame configuration"); });
    CHECK(death.aborted);
    CHECK(death.stderr_text.find("unreachable: a fourth frame configuration") != std::string::npos);
}

TEST_CASE("the log formats a line into its own buffer and cuts what does not fit") {
    const atlas::log::Line line("tile {}/{}/{} built in {:.1f} ms", 14, 8414, 5384, 2.3);
    CHECK(line.view() == "tile 14/8414/5384 built in 2.3 ms");
    const std::string long_text(5000, 'x');
    const atlas::log::Line cut("{}", long_text);
    CHECK(cut.view().size() == atlas::log::line_capacity - 1);
    CHECK(cut.view().ends_with("..."));
    CHECK(std::string_view(cut.c_str()).size() == cut.view().size());
}

TEST_CASE("log lines carry the AtlasMap tag smoke.sh reads") {
    const auto out = atlas::test::run_in_child([] { atlas::log::info("vk frame {} {:.2f} ms", 7, 3.5); });
    CHECK_FALSE(out.aborted);
    CHECK(out.stderr_text == "AtlasMap I vk frame 7 3.50 ms\n");
}
