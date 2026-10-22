// A building tradition, typed (load/Tradition.hpp): render/traditions' five files read as generic.py reads them (the
// ages of an entry in the file's order, the details handed on whole), and what a file cannot hold refused by its path.
// :mapcorecheck's AmsterdamParityTest and RegionsParityTest hold the looks each tradition gives to the port's, record
// for record, over the regions' data.
#include "load/Tradition.hpp"
#include "tests/base/require.hpp"

#include <doctest/doctest.h>

#include <cstdint>
#include <fstream>
#include <iterator>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

namespace {

namespace load = atlas::load;

std::expected<std::shared_ptr<const load::Tradition>, load::TraditionError> read(std::string_view s) {
    return load::read_tradition(std::as_bytes(std::span(s.data(), s.size())));
}

/// render/traditions/<id>.json read, or null (the test case failed) where it cannot be.
std::shared_ptr<const load::Tradition> file(std::string_view id) {
    std::ifstream in(std::string(ATLAS_TESTS_DIR) + "/../../../../../render/traditions/" + std::string(id) + ".json",
                     std::ios::binary);
    ATLAS_REQUIRE_OR_RETURN(nullptr, in.good(), "render/traditions is not in the repository");
    const std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    auto t = read(text);
    if (!t) {
        FAIL_CHECK(id, " is refused at ", t.error().at, ": ", t.error().what);
        return nullptr;
    }
    return *t;
}

/// The path a document is refused at, or `read` where it is read.
std::string refused_at(std::string_view s) {
    const auto t = read(s);
    return t ? std::string("read") : t.error().at;
}

constexpr std::string_view minimal = R"({"name": "n", "says": "s", "floor_h": 3.0, "levels": {"default": 2},
 "walls": {"default": [["brick", "#8E5A46", 1]]},
 "roof": {"default": {"shapes": [["flat", 1]], "material": "metal", "colours": ["#737E88"]}}})";

} // namespace

TEST_CASE("tradition: render/traditions' five files read as generic.py reads them") {
    const auto paris = file("fr_paris");
    const auto tuscan = file("it_tuscan");
    const auto bergen = file("no_timber");
    const auto london = file("gb_terrace");
    const auto amsterdam = file("nl_amsterdam");
    if (!paris || !tuscan || !bergen || !london || !amsterdam) return;
    CHECK(paris->name == "Paris stone and zinc");
    CHECK(paris->floor_h == 3.0);
    CHECK(paris->party_chimneys);
    CHECK(london->party_chimneys);
    CHECK_FALSE(bergen->party_chimneys); // plain chimneys: no party walls
    CHECK(paris->rules.size() == 1);
    CHECK(paris->rules[0].roof.shape == "mansard");
    CHECK(paris->rules[0].when.year_unknown);
    CHECK(paris->rules[0].when.year_from == 1760);
    CHECK(paris->window_kinds.size() == 6);
    CHECK(paris->shutters == std::nullopt); // "shutters": null
    CHECK(tuscan->shutters.value_or(load::Shutters{}).colours.size() == 5);
    CHECK(tuscan->towers_crenellated);
    CHECK(tuscan->heights.size() == 2);
    CHECK(tuscan->eaves == 0.55);
    CHECK(bergen->board_materials.size() == 1);
    CHECK_FALSE(bergen->boards.is_null());
    CHECK(london->band_materials.size() == 1);
    CHECK(london->cornice.always);
    CHECK(amsterdam->windows.is_null()); // "windows": null
    CHECK(amsterdam->cornice.before == 1800.0);
    CHECK(amsterdam->hoist.holds(1700));
    CHECK_FALSE(amsterdam->hoist.holds(1850));
    CHECK_FALSE(amsterdam->hoist.holds(std::nullopt)); // an unknown age is not old
    // an entry by age, its branches in the file's order (generic.py's era): the office's before:1900, then before:1960
    const auto* office = load::Tradition::entry(amsterdam->walls, "office");
    REQUIRE(office != nullptr);
    REQUIRE(office->branches.size() == 2);
    const auto options = [&](std::optional<std::int64_t> yr) {
        const std::optional<std::vector<load::WallOption>>& v = office->at(yr);
        return v ? v->size() : 0;
    };
    CHECK(options(1850) == 5);
    CHECK(options(1950) == 1);
    CHECK(options(1970) == 3);
    CHECK(options(std::nullopt) == 3);
    CHECK(options(0) == 3); // a year of 0 is false
    const auto* roof = load::Tradition::entry(paris->roof, "home");
    REQUIRE(roof != nullptr);
    const auto material = [&](std::int64_t yr) {
        const std::optional<load::RoofEntry>& v = roof->at(yr);
        return v ? v->material : std::string_view();
    };
    CHECK(material(1960) == "gravel");
    CHECK(material(1900) == "metal");
    CHECK(load::Tradition::entry(paris->roof, "nothing") == nullptr);
}

TEST_CASE("tradition: what a file cannot hold, refused by its path") {
    CHECK(refused_at(minimal) == "read");
    CHECK(refused_at(R"({"says": "s"})") == "name");
    CHECK(refused_at("[1]").empty()); // not a map at all
    std::string s(minimal);
    const auto with = [&](std::string_view from, std::string_view to) {
        std::string t = s;
        t.replace(t.find(from), from.size(), to);
        return refused_at(t);
    };
    CHECK(with(R"("floor_h": 3.0)", R"("floor_h": 0)") == "floor_h");
    CHECK(with(R"("levels": {"default": 2})", R"("levels": {"house": 2})") == "levels.default");
    CHECK(with(R"("levels": {"default": 2})", R"("levels": {"default": 2.5})") == "levels.default");
    CHECK(with(R"([["brick", "#8E5A46", 1]])", R"([["brick", 1]])") == "walls.default[0]");
    CHECK(with(R"([["brick", "#8E5A46", 1]])", R"([])") == "walls.default");
    CHECK(with(R"("walls": {"default": [["brick", "#8E5A46", 1]]})",
               R"("walls": {"default": {"before:18x0": [["brick", "#8E5A46", 1]]}})") == "walls.default.before:18x0");
    CHECK(with(R"("colours": ["#737E88"])", R"("colours": [])") == "roof.default.colours");
    CHECK(with(R"({"shapes": [["flat", 1]], "material": "metal", "colours": ["#737E88"]})",
               R"({"after:1950": {"shapes": [["flat", 1]], "material": "metal", "colours": ["#737E88"]}})") ==
          "roof.default"); // its era can give None
    CHECK(with(R"("roof": {"default")", R"("roof": {"home")") == "roof.default");
    CHECK(with(R"("floor_h": 3.0,)", R"("floor_h": 3.0, "windows": {"bay": 2.9},)") == "windows.kinds");
    CHECK(with(R"("floor_h": 3.0,)", R"("floor_h": 3.0, "shutters": {"kinds": [], "share": 1, "colours": []},)") ==
          "shutters.colours");
    CHECK(with(R"("floor_h": 3.0,)", R"("floor_h": 3.0, "rules": [{"when": {}, "roof": {"pitch": 1}}],)") ==
          "rules[0].roof.shape");
    CHECK(with(R"("floor_h": 3.0,)", R"("floor_h": 3.0, "cornice": "yes",)") == "cornice");
    CHECK(with(R"("floor_h": 3.0,)", R"("floor_h": 3.0, "says_by": null,)") == "says_by");
    // what Python reads alike stays read: a null detail, an empty windows map (false), a null era branch
    CHECK(with(R"("floor_h": 3.0,)", R"("floor_h": 3.0, "windows": {}, "boards": null, "eaves": 0,)") == "read");
    CHECK(
        with(
            R"("walls": {"default": [["brick", "#8E5A46", 1]]})",
            R"("walls": {"home": {"before:1900": null, "default": [["brick", "#8E5A46", 1]]}, "default": [["brick", "#8E5A46", 1]]})") ==
        "read");
    const auto bad = read("{");
    REQUIRE_FALSE(bad.has_value());
    CHECK(bad.error().json.has_value());
}
