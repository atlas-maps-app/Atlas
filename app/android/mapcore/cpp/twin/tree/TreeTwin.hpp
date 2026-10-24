// The trees' twin (plan-vector D16's L1; ENGINE.md step 4, "Trees and things"): what the trees pass draws at a frame,
// as the port's SVG elements (render/closeup.py's tree_close, amsterdam.py's draw_tree3d), into the twin's stream
// (twin/Twin.hpp). It decides each drawn tree as trees_cs does, with the shader's own text (twin/tree/TreeShader.hpp)
// over the points heap's words the GPU reads, and emits in the port's order, each in the tree's item (its OSM node):
//   - tree_close: its trunk, a closed path of four points in the bark's colour (slot 0); its sunlit side, alike in its
//     colour at its opacity (1); its limbs, one path of up to five open subpaths stroked in the bark's colour, round
//     capped (2); its crown under `<g filter="url(#crown)">`, slice by slice and tone by tone, each tone's circles in
//     its own group (3 + 4 · slice + tone); a small crown its circle or two (0 and 1);
//   - the generic tree (without weather): its trunk, one open subpath stroked round-capped (its fill the default
//     black: the port's path has none), its blobs' dark circles in a group (1), their lit ones in another (2).
// The colours are the pass's tones (passes::tree_tones: the port's light in binary64 through tohex), the crown's lit
// tones by its sun bit. Each of its records (the GPU's, in their order) can be kept for the tests.
#pragma once

#include "frame/FrameBlock.hpp"
#include "passes/Trees.hpp"
#include "twin/Twin.hpp"
#include "twin/tree/TreeShader.hpp"

#include <array>
#include <cstdint>
#include <span>
#include <vector>

namespace atlas::frame {
struct FrameState;
} // namespace atlas::frame

namespace atlas::scene {
struct CoverTile;
} // namespace atlas::scene

namespace atlas::twin {

/// The trees of one frame into a stream.
class TreeTwin {
public:
    /// The frame `state` (its block, its view, its moment) with the active pack's `style`, into `out`.
    TreeTwin(const frame::FrameState& state, const tiles::Style& style, Stream& out);

    /// Emits tree `tree` of cover tile `tile` (its trees `trees`, their words `heap`: the tile's range of the points
    /// heap), drawn with rank entry `rank` at its item's depth `depth`; trees go in the jobs' order, the nearest first.
    void tree(const scene::CoverTile& tile, const passes::TreeTile& trees, std::span<const std::byte> heap,
              std::uint32_t tree, std::uint32_t rank, double depth);

    /// Keeps the records the GPU writes for each tree from here on, in its order (the sunlit sides in the jobs' order,
    /// the nearest first: the GPU's list holds them the other way round).
    void keep_records(std::vector<shaders::TreeRecord>* records, std::vector<shaders::TreeRecord>* sides) noexcept {
        records_ = records;
        sides_ = sides;
    }

private:
    /// A tree's circle as the port prints it, and its tone.
    struct Circle {
        Point at;
        float r = 0;
        std::uint32_t tone = 0;
        bool drawn = false;
    };

    void close_tree(const shaders::TreeJob& j, const shaders::TreeHeader& t, std::span<const hlsl::uint4> words,
                    std::int32_t item);
    void small_tree(const shaders::TreeJob& j, const shaders::TreeHeader& t, std::int32_t item);
    void generic_tree(const shaders::TreeJob& j, const shaders::TreeHeader& t, std::span<const hlsl::uint4> words,
                      std::int32_t item);
    /// Record `k` of the tree's (its first, its circles').
    void put(std::uint32_t k, const shaders::TreeRecord& r);
    [[nodiscard]] static Paint paint(std::uint32_t bgr) noexcept;

    const frame::FrameState& state_;
    Stream& out_;
    shaders::FrameCamera camera_;
    std::array<std::uint32_t, 25> tones_{};
    bool weather_ = false;
    bool night_ = false;
    bool right_ = false;
    double focal_ = 0;
    std::int32_t objects_ = none;
    std::int32_t crown_ = none;
    std::int32_t tones_group_ = none;
    std::int32_t blobs_ = none;
    std::vector<shaders::TreeRecord>* records_ = nullptr;
    std::vector<shaders::TreeRecord>* sides_ = nullptr;
    std::vector<shaders::TreeRecord> tree_records_;
    shaders::TreeRecord side_record_{};
    bool side_kept_ = false;
    /// The tree's crown's sun texel (set 1's, for its sample alone) and whether its tile has bits.
    hlsl::uint2 sun_texel_{};
    bool sun_has_ = false;
};

} // namespace atlas::twin
