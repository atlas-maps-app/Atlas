// Stage T of the trees (ENGINE.md step 4, "Trees and things"): a render tile's natural=tree nodes as the port draws
// them, render/closeup.py's tree_close with weather (Kotlin Closeup.treeClose) and amsterdam.py's draw_tree3d without:
//   - each tree's item, as amsterdam.py's gather makes it: its node's place in S['nodes'] (the nodes' list), sorted by
//     the depth of (x, 1, z), drawn from 2 m, cut by the renders' radius at its trunk;
//   - its crown's form (closeup.py's tree_form: a needleleaved tree's cone, else its genus's in the style's FORMS) and
//     whether its leaves stay green (autumn: an evergreen or needleleaved tree's tint is 0);
//   - the clumps its crown draws from its own random stream (random.Random(id)), every one tree_close makes: their
//     place, radius and whether they face down (nrm.y < -0.45, the under tone), in the port's own arithmetic, for its
//     36 clumps when none is skipped (nb's 9, 16 and 26 take the first of them: the same draws); and the generic tree's
//     9 blobs alike (its 5 take the first).
// The port skips a clump nearer than 1 m to the eye without drawing its radius, which shifts every later clump's draws
// and reshuffles the crown as the camera passes; the frame leaves that clump out and keeps the others (ENGINE.md: a
// difference on purpose, close up at eye level alone). Which clumps, at what size and in which tone, the frame decides
// at its camera (shaders/tree/tree.slangh).
#pragma once

#include "scene/ItemTable.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Sections.hpp"
#include "tiles/Style.hpp"

#include <array>
#include <cstdint>
#include <optional>
#include <string_view>
#include <vector>

namespace atlas::geometry {

/// A tree's clumps (tree_close's most: nb = 36 where its crown is over 70 px) and the generic tree's blobs (9 within
/// 400 m).
inline constexpr std::size_t tree_clumps = 36;
inline constexpr std::size_t tree_blobs = 9;

/// A tree's owner kind in scene::owner_key: a node (AnchorItems' OwnerType.NODE).
inline constexpr std::uint8_t owner_node = 1;

/// The items' list in the renders' gather order (scene::order_of): S['nodes'].
inline constexpr std::uint32_t nodes_list = 2;

/// One clump of a crown, or one blob of the generic tree: its centre from the trunk (x and z, metres, the port's x -
/// n['x'] and z - n['z']) and above the tree's ground (y), its radius (metres: a clump's before the 1.25 of a small
/// crown), and whether it faces down (a clump's nrm.y < -0.45: the under tone; a blob has none).
struct TreeClump {
    double dx = 0;
    double y = 0;
    double dz = 0;
    double br = 0;
    bool under = false;
};

/// One tree.
struct Tree {
    std::int64_t id = 0;    ///< its OSM node
    std::uint32_t node = 0; ///< its place in S['nodes']
    double x = 0;           ///< its trunk (frame metres)
    double z = 0;
    double h = 0;  ///< n['h']
    double r = 0;  ///< n['crown'] / 2
    double gy = 0; ///< the ground under it (terrain towns; 0 elsewhere)
    tiles::TreeForm form = tiles::TreeForm::ovoid;
    bool evergreen = false; ///< autumn's tint is 0 (leaf_cycle=evergreen or needleleaved)
    std::array<TreeClump, tree_clumps> clumps{};
    std::array<TreeClump, tree_blobs> blobs{};
};

/// The painter slots of a tree (the most it draws): its trunk, its trunk's sunlit side, its limbs, then its crown's
/// three slices of four tones each (tree_close); a small crown takes two, the generic tree three.
inline constexpr std::uint32_t tree_slots = 15;

/// A tile's trees, in the tile's order, and their items.
struct TileTrees {
    std::vector<Tree> trees;
    std::vector<scene::ItemSpec> items;
};

/// What the trees read: the tile, its pack's head (its frame) and style (FORMS), and the items' kind.
struct TreeInput {
    const tiles::Tile* tile = nullptr;
    const tiles::RenderHead* head = nullptr;
    const tiles::Style* style = nullptr;
    std::uint16_t kind = 0;
};

/// The tile's trees.
[[nodiscard]] TileTrees build_trees(const TreeInput& in);

/// closeup.py's tree_form of a tree's tags: a needleleaved tree's cone, else the form FORMS gives the first word of its
/// genus, species or taxon (the first of them set and not empty), lowercased, else an ovoid.
[[nodiscard]] tiles::TreeForm tree_form(const tiles::Style& style, std::optional<std::string_view> leaf_type,
                                        std::optional<std::string_view> genus, std::optional<std::string_view> species,
                                        std::optional<std::string_view> taxon);

/// tree_close's clumps of a tree of form `form`, height `h` and crown radius `r` with seed `id` whose trunk stands at
/// (`x`, `z`), as its loop draws them when none is skipped.
[[nodiscard]] std::array<TreeClump, tree_clumps> tree_close_clumps(std::int64_t id, tiles::TreeForm form, double h,
                                                                   double r, double x, double z);

/// draw_tree3d's generic blobs of a tree of height `h` and crown radius `r` with seed `id` whose trunk stands at (`x`,
/// `z`) (within 400 m: nb = 9), as its loop draws them when none is skipped.
[[nodiscard]] std::array<TreeClump, tree_blobs> tree_generic_blobs(std::int64_t id, double h, double r, double x,
                                                                   double z);

} // namespace atlas::geometry
