// pymath's check (check/NumericsCheck.hpp) on this host: its total must equal the port's, which
// MT/numerics/NumericsCheckTest.kt computes on the JVM. This is L0 d's bit test where no JVM runs: the arm64 runner and
// the emulator's and the phone's Bionic (atlas_tests built for Android, or the `pymath` line of the app).
#include "check/NumericsCheck.hpp"

#include <doctest/doctest.h>

#include <cstdio>
#include <set>
#include <string_view>

TEST_CASE("pymath's check equals the port's on this host") {
    const atlas::check::Numerics n = atlas::check::numerics();
    for (const atlas::check::NumericsPart& p : n.parts) {
        MESSAGE(p.name, " ", p.results, " results, hash ", p.hash);
    }
    const std::string_view line = atlas::check::numerics_line();
    MESSAGE(line);
    (void)std::printf("%.*s\n", static_cast<int>(line.size()), line.data());
    CHECK(n.total == atlas::check::numerics_expected);
    CHECK(line.ends_with(" ok"));
}

TEST_CASE("pymath's check is deterministic and its parts are distinct") {
    const atlas::check::Numerics a = atlas::check::numerics();
    const atlas::check::Numerics b = atlas::check::numerics();
    CHECK(a.total == b.total);
    std::set<std::string_view> names;
    std::size_t results = 0;
    for (const atlas::check::NumericsPart& p : a.parts) {
        CHECK(!p.name.empty());
        CHECK(p.results > 0);
        names.insert(p.name);
        results += p.results;
    }
    CHECK(names.size() == a.parts.size());
    CHECK(results > 100000);
    CHECK(std::string_view(atlas_numerics_line()) == atlas::check::numerics_line());
}
