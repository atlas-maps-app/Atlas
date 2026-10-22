// A pack's `rhead` and `style` written by the core (tiles/SectionWriter.hpp, load/PackStyle.hpp) against the packer's:
// the style file compiled into the core reads as the packer reads it, its style version is the bundled packs', each
// bundled pack's head written again from its values is its own bytes, and the style the core composes for the living
// map and for the canal ring (with the region's R as the packer had it) is the bundled pack's, byte for byte.
#include "load/Amsterdam.hpp"
#include "load/PackStyle.hpp"
#include "load/Regions.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Directory.hpp"
#include "tiles/Payload.hpp"
#include "tiles/SectionWriter.hpp"
#include "tiles/Source.hpp"

#include <doctest/doctest.h>

#include <cstddef>
#include <optional>
#include <string>
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
