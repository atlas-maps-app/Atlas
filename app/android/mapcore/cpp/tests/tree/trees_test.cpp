// The trees' stage T (ENGINE.md step 4, "Trees and things"; geometry/Trees.hpp) and the render thread's choices
// (passes/Trees.hpp): closeup.py's tree_form over a tree's tags, autumn's tint by the date, tree_choice's thresholds as
// the port compares them, and every bundled tile's trees: one for each row of its tree layer, its item in S['nodes']'s
// order at (x, 1, z), its clumps' and blobs' radii in the port's ranges, and the points heap's bytes they take. That
// the clumps are the port's, the twin's circles against the port's SVG say (L1).
#include "base/arena.hpp"
#include "geometry/Trees.hpp"
#include "passes/Trees.hpp"
#include "tests/base/require.hpp"
#include "tests/tiles/support.hpp"
#include "tiles/Payload.hpp"
#include "tiles/layers/Points.hpp"
#include "twin/tree/TreeShader.hpp"

#include <doctest/doctest.h>

#include <format>
#include <string>
#include <vector>

namespace {

namespace tl = atlas::tiles;
namespace gm = atlas::geometry;
namespace sh = atlas::shaders;

/// The test packs' style (tests/tiles/support.hpp's tables), typed.
[[nodiscard]] tl::Style test_style() {
    auto tables = tl::read_style(atlas::test::style_bytes());
    REQUIRE(tables.has_value());
    auto style = tl::Style::of(*tables);
    REQUIRE(style.has_value());
    return *style;
}

[[nodiscard]] std::uint32_t nb_of(std::uint32_t flags) { return (flags >> sh::TREE_JOB_NB_SHIFT) & 0xFFU; }

} // namespace

TEST_CASE("trees: a crown's form is its genus's, as closeup.py's tree_form reads the tags") {
    const tl::Style style = test_style();
    using F = tl::TreeForm;
    CHECK(gm::tree_form(style, "needleleaved", "Tilia", std::nullopt, std::nullopt) == F::cone);
    CHECK(gm::tree_form(style, std::nullopt, "Tilia", std::nullopt, std::nullopt) == F::ovoid);
    CHECK(gm::tree_form(style, "broadleaved", "Platanus", "Platanus x hispanica", std::nullopt) == F::broad);
    CHECK(gm::tree_form(style, std::nullopt, std::nullopt, "Ulmus minor", std::nullopt) == F::vase);
    // an empty genus is Python's false: the species is read
    CHECK(gm::tree_form(style, std::nullopt, "", "Aesculus hippocastanum", std::nullopt) == F::dome);
    CHECK(gm::tree_form(style, std::nullopt, std::nullopt, std::nullopt, "Populus nigra 'Italica'") == F::column);
    CHECK(gm::tree_form(style, std::nullopt, "SALIX", std::nullopt, std::nullopt) == F::weep);
    CHECK(gm::tree_form(style, std::nullopt, "Quercus", std::nullopt, std::nullopt) == F::broad);
    // the first word alone: a leading space leaves it empty
    CHECK(gm::tree_form(style, std::nullopt, " Acer", std::nullopt, std::nullopt) == F::ovoid);
    CHECK(gm::tree_form(style, std::nullopt, "Prunus", std::nullopt, std::nullopt) == F::ovoid);
    CHECK(gm::tree_form(style, std::nullopt, std::nullopt, std::nullopt, std::nullopt) == F::ovoid);
}

TEST_CASE("trees: autumn's tint by the moment's date") {
    atlas::style::ThemeBlock b{};
    b.month = 10;
    b.day = 1;
    CHECK(atlas::passes::tree_tint(b) == ((9 * 30.5) + 1 - 258) / 75);
    b.month = 9;
    b.day = 1;
    CHECK(atlas::passes::tree_tint(b) == 0.0);
    b.month = 12;
    b.day = 31;
    CHECK(atlas::passes::tree_tint(b) == 0.7);
}

TEST_CASE("trees: the render thread's choices, as tree_close and draw_tree3d compare them in double") {
    using atlas::passes::tree_choice;
    // a 7 m crown seen at 700 px of focal length: a few pixels across from 377 m on, one circle under 4.2 px
    const auto far = tree_choice(400, 700, 3.5, true, false, true);
    CHECK((far.flags & sh::TREE_JOB_SMALL) != 0U);
    CHECK((far.flags & sh::TREE_JOB_ONE) == 0U);
    CHECK(far.w0 == static_cast<float>(3.5 * 700 / 400));
    CHECK((tree_choice(1000, 700, 3.5, true, false, true).flags & sh::TREE_JOB_ONE) != 0U);
    // 24.5 px: 16 clumps; s = 7, its trunk's sunlit side by day and its limbs, '%.2f' of 0.49
    const auto mid = tree_choice(100, 700, 3.5, true, false, true);
    CHECK((mid.flags & sh::TREE_JOB_SMALL) == 0U);
    CHECK(nb_of(mid.flags) == 16);
    CHECK((mid.flags & sh::TREE_JOB_SIDE) != 0U);
    CHECK((mid.flags & sh::TREE_JOB_RIGHT) != 0U);
    CHECK((mid.flags & sh::TREE_JOB_LIMBS) != 0U);
    CHECK(mid.w0 == static_cast<float>(0.22 * 7));
    CHECK(mid.w1 == static_cast<float>(0.12 * 7));
    CHECK(mid.limb == static_cast<float>(0.49));
    CHECK(nb_of(tree_choice(60, 700, 3.5, true, false, true).flags) == 26);
    CHECK(nb_of(tree_choice(30, 700, 3.5, true, false, true).flags) == 36);
    CHECK(nb_of(tree_choice(200, 700, 3.5, true, false, true).flags) == 9);
    // at night no sunlit side; from 280 m (s under 2.5) no limbs
    CHECK((tree_choice(100, 700, 3.5, true, true, true).flags & sh::TREE_JOB_SIDE) == 0U);
    CHECK((tree_choice(100, 700, 3.5, true, true, true).flags & sh::TREE_JOB_NIGHT) != 0U);
    CHECK((tree_choice(300, 700, 5.0, true, false, true).flags & sh::TREE_JOB_LIMBS) == 0U);
    // without weather the generic tree: 9 blobs within 400 m, else 5; its trunk '%.2f' of max(0.6, 0.3 · focal / d)
    const auto dry = tree_choice(300, 700, 3.5, false, false, true);
    CHECK((dry.flags & sh::TREE_JOB_GENERIC) != 0U);
    CHECK(nb_of(dry.flags) == 9);
    CHECK(dry.w0 == static_cast<float>(0.7));
    const auto beyond = tree_choice(500, 700, 3.5, false, false, true);
    CHECK(nb_of(beyond.flags) == 5);
    CHECK(beyond.w0 == static_cast<float>(0.6));
    // a job's records: a small crown's two, tree_close's trunk, limbs and three circles a clump, the generic tree's
    CHECK(sh::tree_record_count(far.flags) == 2);
    CHECK(sh::tree_record_count(mid.flags) == 6 + (3 * 16));
    CHECK(sh::tree_record_count(dry.flags) == 1 + (2 * 9));
}

TEST_CASE("trees: stage T of every bundled tile") {
    const std::string dir = atlas::test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the bundled packs are not checked here");
        return;
    }
    for (const char* name : {"amsterdam", "grachten"}) {
        auto pack = atlas::test::open_pack(dir + "/" + name + ".atlas");
        ATLAS_REQUIRE(pack.has_value(), tl::describe(pack.error()));
        const tl::RenderHead* head = pack->head();
        ATLAS_REQUIRE(head != nullptr);
        ATLAS_REQUIRE(pack->style() != nullptr);
        tl::Zstd zstd;
        tl::TileDecoder decoder;
        std::vector<std::byte> raw;
        std::vector<std::byte> scratch;
        atlas::Arena arena(4U << 20U, atlas::Growth::grows, "trees test");
        std::size_t trees = 0;
        std::size_t most = 0;
        std::size_t forms[7] = {};
        std::size_t evergreen = 0;
        for (const tl::ChunkRef& chunk : pack->chunks().level(head->levels[0])) {
            arena.reset();
            auto tile = pack->decode(chunk, zstd, decoder, arena, raw, scratch);
            ATLAS_REQUIRE(tile.has_value(), tl::describe(tile.error()));
            const gm::TileTrees g = gm::build_trees({.tile = &*tile, .head = head, .style = pack->style(), .kind = 7});
            const tl::Layer* layer = tile->layer(tl::LayerId::tree);
            const std::size_t rows = layer != nullptr ? layer->count : 0;
            CHECK(g.trees.size() == rows);
            CHECK(g.items.size() == rows);
            for (std::size_t i = 0; i < g.trees.size(); ++i) {
                const gm::Tree& t = g.trees[i];
                const atlas::scene::ItemSpec& item = g.items[i];
                CHECK(item.y == 1.0);
                CHECK(item.x == t.x);
                CHECK(item.z == t.z);
                CHECK(item.order == atlas::scene::order_of(gm::nodes_list, t.node));
                CHECK(item.kind == 7);
                CHECK(item.min_depth == 2.0F);
                CHECK(item.owner == atlas::scene::owner_key(gm::owner_node, static_cast<std::uint64_t>(t.id)));
                ++forms[static_cast<std::size_t>(t.form)];
                if (t.evergreen) ++evergreen;
                for (const gm::TreeClump& c : t.clumps) {
                    CHECK(c.br >= t.r * 0.27);
                    CHECK(c.br <= t.r * 0.38);
                    CHECK(c.y >= 0.0);
                    CHECK(c.y <= t.h);
                }
                for (const gm::TreeClump& b : t.blobs) {
                    CHECK(b.br >= t.r * 0.38);
                    CHECK(b.br <= t.r * 0.55);
                    CHECK(((b.dx * b.dx) + (b.dz * b.dz)) <= (t.r * 0.7 * t.r * 0.7) * 1.000001);
                }
            }
            const std::vector<atlas::hlsl::uint4> words = atlas::passes::tree_words(g, 0.0, 0.0, 0.0);
            CHECK(words.size() == g.trees.size() * sh::TREE_VECTORS);
            most = std::max(most, words.size() * sizeof(atlas::hlsl::uint4));
            trees += g.trees.size();
        }
        MESSAGE(std::format("{}: {} trees ({} ovoid, {} vase, {} broad, {} dome, {} column, {} weep, {} cone; {} "
                            "evergreen), the densest tile's {:.2f} MB of the points heap",
                            name, trees, forms[0], forms[1], forms[2], forms[3], forms[4], forms[5], forms[6],
                            evergreen, static_cast<double>(most) / (1 << 20)));
        CHECK(most <= std::size_t{2} << 20U);
    }
}
