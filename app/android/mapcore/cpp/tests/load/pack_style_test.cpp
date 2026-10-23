// A pack's `rhead` and `style` written by the core (tiles/SectionWriter.hpp, load/PackStyle.hpp, load/PackHead.hpp)
// against the packer's: the style file compiled into the core reads as the packer reads it, its style version is the
// bundled packs', each bundled pack's head written again from its values is its own bytes, the style the core composes
// for the living map and for the canal ring (with the region's R as the packer had it) is the bundled pack's, and so
// are the head and the style the core makes from what the packer knows of each (its frame, the port's engine version,
// its source and R), byte for byte.
#include "load/Amsterdam.hpp"
#include "load/PackHead.hpp"
#include "load/PackStyle.hpp"
#include "load/Regions.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Directory.hpp"
#include "tiles/Json.hpp"
#include "tiles/Payload.hpp"
#include "tiles/SectionWriter.hpp"
#include "tiles/Source.hpp"

#include <doctest/doctest.h>

#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace {

namespace load = atlas::load;
namespace tl = atlas::tiles;

/// A pack's stored `rhead` and its decompressed `style`.
struct Sections {
    std::vector<std::byte> head;
    std::vector<std::byte> style;
};

std::optional<Sections> sections_of(const tl::Pack& pack) {
    const tl::Section* head = tl::find_section(pack.sections(), tl::section_head);
    const tl::Section* style = tl::find_section(pack.sections(), tl::section_style);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, head != nullptr && style != nullptr);
    std::vector<std::byte> head_scratch;
    std::vector<std::byte> style_scratch;
    const auto head_bytes = pack.source().bytes(head->offset, head->stored, head_scratch);
    const auto style_stored = pack.source().bytes(style->offset, style->stored, style_scratch);
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, head_bytes.has_value() && style_stored.has_value());
    Sections out{.head = {head_bytes->begin(), head_bytes->end()}, .style = std::vector<std::byte>(style->raw)};
    tl::Zstd zstd;
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, zstd.frame(*style_stored, out.style, tl::Site::style).has_value());
    return out;
}

/// The head's values, as encode_head takes them.
std::vector<std::byte> head_again(const tl::RenderHead& h) {
    std::vector<tl::HeadViewOut> views;
    for (const tl::HeadView& v : h.views())
        views.push_back({.name = v.name, .camera = v.camera, .tolerance = v.tolerance});
    const auto bytes = tl::encode_head({.profile = h.profile,
                                        .levels = h.levels,
                                        .frame = h.frame,
                                        .region = h.region,
                                        .engine_version = h.engine_version,
                                        .style_version = h.style_version,
                                        .source = h.source,
                                        .views = views,
                                        .bands = h.bands()});
    ATLAS_REQUIRE_OR_RETURN({}, bytes.has_value(), tl::describe(bytes.error()));
    return *bytes;
}

/// Where two byte strings first differ, or "the same".
std::string difference(const std::vector<std::byte>& core, const std::vector<std::byte>& packer) {
    for (std::size_t i = 0; i < core.size() && i < packer.size(); ++i) {
        if (core[i] != packer[i]) {
            return "byte " + std::to_string(i) + ": core " + std::to_string(static_cast<int>(core[i])) + ", packer " +
                   std::to_string(static_cast<int>(packer[i]));
        }
    }
    if (core.size() != packer.size()) {
        return "sizes " + std::to_string(core.size()) + " and " + std::to_string(packer.size());
    }
    return "the same";
}

/// A JSON literal's values.
std::optional<tl::JsonValue> json_of(std::string_view text) {
    auto read = tl::read_json(std::as_bytes(std::span(text.data(), text.size())));
    ATLAS_REQUIRE_OR_RETURN(std::nullopt, read.has_value());
    return std::move(*read);
}

} // namespace

TEST_CASE("load: the style file compiled into the core reads as the packer reads it") {
    const tl::Value file = load::engine_style();
    ATLAS_REQUIRE(file.type() == tl::ValueType::map);
    CHECK(file.key(0) == "land");
    const auto land = file.find("land");
    ATLAS_REQUIRE(land.has_value());
    const auto farmland = land->find("farmland");
    ATLAS_REQUIRE(farmland.has_value());
    CHECK(farmland->child(0).string() == "#D8D4AC");
    CHECK(farmland->child(1).type() == tl::ValueType::int32);
    const std::string version = load::style_version();
    CHECK(version.starts_with("s1.1-"));
    CHECK(version.size() == 21);
}

TEST_CASE("load: the core writes the bundled packs' heads and styles byte for byte (ATLAS_TILES_DIR)") {
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the bundled packs are not compared here");
        return;
    }
    auto amsterdam = atlas::test::open_pack(dir + "/amsterdam.atlas");
    auto grachten = atlas::test::open_pack(dir + "/grachten.atlas");
    ATLAS_REQUIRE(amsterdam.has_value() && grachten.has_value());
    const std::optional<Sections> sa = sections_of(*amsterdam);
    const std::optional<Sections> sg = sections_of(*grachten);
    ATLAS_REQUIRE(sa.has_value() && sg.has_value());
    const Sections& a = *sa;
    const Sections& g = *sg;

    SUBCASE("the style version") {
        CHECK(load::style_version() == amsterdam->head()->style_version);
        CHECK(load::style_version() == grachten->head()->style_version);
    }
    SUBCASE("each head written again from its values") {
        const std::vector<std::byte> ha = head_again(*amsterdam->head());
        const std::vector<std::byte> hg = head_again(*grachten->head());
        CHECK_MESSAGE(ha == a.head, difference(ha, a.head));
        CHECK_MESSAGE(hg == g.head, difference(hg, g.head));
    }
    SUBCASE("the living map's style") {
        const auto style = load::style_section({.palette = load::amsterdam_palette(), .state = {}});
        ATLAS_REQUIRE(style.has_value());
        CHECK_MESSAGE(*style == a.style, difference(*style, a.style));
    }
    SUBCASE("the canal ring's style, with its R as the packer had it") {
        const tl::StyleTables* tables = grachten->style_tables();
        ATLAS_REQUIRE(tables != nullptr);
        const auto scene = tables->table("scene");
        ATLAS_REQUIRE(scene.has_value());
        const auto r = scene->find("R");
        ATLAS_REQUIRE(r.has_value());
        const load::SceneScalars scalars{.region = "grachten", .view = "grachten_whole", .tradition = {}, .r = *r};
        const auto style = load::style_section({.palette = load::region_palette("grachten"),
                                                .state = load::region_state("grachten", 2),
                                                .scene = &scalars});
        ATLAS_REQUIRE(style.has_value());
        CHECK_MESSAGE(*style == g.style, difference(*style, g.style));
    }
}

TEST_CASE("load: the core makes the bundled packs' heads and styles from what the packer knows (ATLAS_TILES_DIR)") {
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the bundled packs are not compared here");
        return;
    }
    auto amsterdam = atlas::test::open_pack(dir + "/amsterdam.atlas");
    auto grachten = atlas::test::open_pack(dir + "/grachten.atlas");
    ATLAS_REQUIRE(amsterdam.has_value() && grachten.has_value());
    const std::optional<Sections> sa = sections_of(*amsterdam);
    const std::optional<Sections> sg = sections_of(*grachten);
    ATLAS_REQUIRE(sa.has_value() && sg.has_value());

    SUBCASE("the living map: its camera, one band, no scene") {
        const tl::RenderHead& h = *amsterdam->head();
        const load::PackFacts facts{.profile = tl::Profile::living,
                                    .region = h.region,
                                    .levels = h.levels,
                                    .frame = h.frame,
                                    .engine_version = h.engine_version,
                                    .source = h.source};
        const auto views = load::pack_views(facts);
        ATLAS_REQUIRE(views.has_value(), views.error());
        CHECK(views->size() == 1);
        const auto made = load::pack_sections(facts, *views);
        ATLAS_REQUIRE(made.has_value(), made.error());
        CHECK_MESSAGE(made->head == sa->head, difference(made->head, sa->head));
        CHECK_MESSAGE(made->style == sa->style, difference(made->style, sa->style));
    }
    SUBCASE("the canal ring: its views from its R as the packer had it, the bands, its scene") {
        const tl::RenderHead& h = *grachten->head();
        const tl::StyleTables* tables = grachten->style_tables();
        ATLAS_REQUIRE(tables != nullptr);
        const auto scene = tables->table("scene");
        ATLAS_REQUIRE(scene.has_value());
        const auto r = scene->find("R");
        ATLAS_REQUIRE(r.has_value());
        const load::PackFacts facts{.profile = tl::Profile::region,
                                    .region = h.region,
                                    .levels = h.levels,
                                    .frame = h.frame,
                                    .engine_version = h.engine_version,
                                    .source = h.source,
                                    .r = *r};
        const auto views = load::pack_views(facts);
        ATLAS_REQUIRE(views.has_value(), views.error());
        ATLAS_REQUIRE(views->size() == h.views().size());
        for (std::size_t i = 0; i < views->size(); ++i) {
            CHECK((*views)[i].name == h.views()[i].name);
            CHECK((*views)[i].tolerance == h.views()[i].tolerance);
        }
        CHECK(load::variant_ladder(*views) == std::vector<std::uint8_t>{1, 3, 6, 7, 8});
        const auto made = load::pack_sections(facts, *views);
        ATLAS_REQUIRE(made.has_value(), made.error());
        CHECK_MESSAGE(made->head == sg->head, difference(made->head, sg->head));
        CHECK_MESSAGE(made->style == sg->style, difference(made->style, sg->style));
    }
}

TEST_CASE("load: a region's views, nearest first, at the tolerances its renders load them at") {
    CHECK(load::generalise("grachten", "grachten_home") == 0.7);
    CHECK(load::generalise("grachten", "grachten_lod150") == 0.8);
    CHECK(load::generalise("paris", "paris_close_detail") == 0.3);
    CHECK(load::generalise("paris", "paris_walk") == 0.0);
    CHECK(load::generalise("bergen", "bergen_close_kit") == 0.2);
    CHECK(load::generalise("newyork", "newyork") == 0.0);

    const auto r = json_of(R"({"bbox": [52.36, 4.872, 52.393, 4.922], "views": {
        "": {"target": [0, 0, 0], "yaw": 52, "pitch": 42, "dist": 2700},
        "home": {"target": [1, 0, 2], "yaw": 52, "pitch": 44, "dist": 1600},
        "eat": {"target": [3, 0, 4], "yaw": 52, "pitch": 46, "dist": 2700.0}}})");
    ATLAS_REQUIRE(r.has_value());
    const std::array<std::uint8_t, 2> levels = {14, 12};
    const load::PackFacts facts{
        .region = "grachten", .levels = levels, .engine_version = "a1-x", .source = "00", .r = r->root()};
    const auto views = load::pack_views(facts);
    ATLAS_REQUIRE(views.has_value(), views.error());
    ATLAS_REQUIRE(views->size() == 3);
    // nearest first; the overview and the Eat view tie, and keep R's order
    CHECK((*views)[0].name == "grachten_home");
    CHECK((*views)[1].name == "grachten");
    CHECK((*views)[2].name == "grachten_eat");
    CHECK((*views)[0].tolerance == 7);
    CHECK((*views)[1].tolerance == 8);
    CHECK((*views)[2].tolerance == 6);
    CHECK(load::variant_ladder(*views) == std::vector<std::uint8_t>{1, 3, 6, 7, 8});
    const auto made = load::pack_sections(facts, *views);
    ATLAS_REQUIRE(made.has_value(), made.error());
    const auto head = tl::read_head(made->head);
    ATLAS_REQUIRE(head.has_value(), tl::describe(head.error()));
    CHECK(head->region == "grachten");
    CHECK(head->views().size() == 3);
    CHECK(head->bands().size() == load::region_bands().size());

    SUBCASE("what the renders could not frame or the core cannot place is refused") {
        const auto none = json_of(R"({"views": {}})");
        const auto no_dist = json_of(R"({"views": {"": {"target": [0, 0, 0], "yaw": 1, "pitch": 2}}})");
        const auto flat = json_of(R"({"views": {"": {"target": [0, 0], "yaw": 1, "pitch": 2, "dist": 3}}})");
        ATLAS_REQUIRE(none.has_value() && no_dist.has_value() && flat.has_value());
        CHECK_FALSE(load::pack_views({.region = "x", .r = none->root()}).has_value());
        CHECK_FALSE(load::pack_views({.region = "x", .r = no_dist->root()}).has_value());
        CHECK_FALSE(load::pack_views({.region = "x", .r = flat->root()}).has_value());
        const auto mars =
            json_of(R"({"tz": "Mars/Olympus", "views": {"": {"target": [0, 0, 0], "yaw": 1, "pitch": 2, "dist": 3}}})");
        ATLAS_REQUIRE(mars.has_value());
        const load::PackFacts on_mars{
            .region = "x", .levels = levels, .engine_version = "a1-x", .source = "00", .r = mars->root()};
        const auto mars_views = load::pack_views(on_mars);
        ATLAS_REQUIRE(mars_views.has_value(), mars_views.error());
        const auto refused = load::pack_sections(on_mars, *mars_views);
        ATLAS_REQUIRE(!refused.has_value());
        CHECK(refused.error().find("Mars/Olympus") != std::string::npos);
    }
}

TEST_CASE("load: a head the format cannot hold is refused") {
    const std::array<std::uint8_t, 1> levels = {14};
    const std::array<tl::HeadBand, 2> open_ended = {{{.tolerance = 0, .up_to = std::nullopt}, {.tolerance = 1}}};
    const std::array<tl::HeadBand, 1> ends = {{{.tolerance = 0, .up_to = 250.0}}};
    const std::array<tl::HeadBand, 1> off_ladder = {{{.tolerance = 14}}};
    const std::array<tl::HeadBand, 1> one = {{{.tolerance = 0}}};
    CHECK_FALSE(tl::encode_head({.levels = levels, .bands = open_ended}).has_value());
    CHECK_FALSE(tl::encode_head({.levels = levels, .bands = ends}).has_value());
    CHECK_FALSE(tl::encode_head({.levels = levels, .bands = off_ladder}).has_value());
    CHECK_FALSE(tl::encode_head({.levels = {}, .bands = one}).has_value());
    const auto fine = tl::encode_head({.levels = levels, .region = "w", .bands = one});
    ATLAS_REQUIRE(fine.has_value());
    const auto read = tl::read_head(*fine);
    ATLAS_REQUIRE(read.has_value(), tl::describe(read.error()));
    CHECK(read->region == "w");
    CHECK(read->levels == std::vector<std::uint8_t>{14});
    CHECK(read->never_simplified());
}
