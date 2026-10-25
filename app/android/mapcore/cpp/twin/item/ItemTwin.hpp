// The region items' twin (plan-vector D16's L1; ENGINE.md step 4, "Trees and things"): what the items pass draws at a
// frame, as the port's SVG elements (render/regions.py's draw_items), into the twin's stream. It decides each drawn
// item as items_cs does, with the shader's own text (twin/item/ItemShader.hpp's items.slangh) over the points heap's
// words, and emits in the port's order, each in the item's item (its OSM node or way):
//   - a tower: each band a closed path filled with its gradient (`tw<id % 100000>_<int(y0)>`, across its box);
//   - a crane, a mast, a gantry's or a conveyor's segment: each box's faces toward the eye, closed paths filled and
//     stroked .25 in their colour, in draw_items' order; a ship-to-shore crane's stays, one path of open subpaths
//     stroked in the steel; at night a lighting mast's glow (url(#mastglow)) and its core, circles;
//   - a turbine: its tower, a closed path; its blades, one path of closed subpaths filled and stroked .4; its hub, a
//     circle; at night its light, a circle at .9;
//   - a power line's segment: its tower, an open subpath stroked (its fill SVG's black); its conductors, one path of
//     open subpaths at .8; both in a group at the segment's fade where it fades.
// Each of its records (the GPU's, in their order) can be kept for the tests.
#pragma once

#include "frame/FrameBlock.hpp"
#include "passes/Items.hpp"
#include "twin/Twin.hpp"
#include "twin/item/ItemShader.hpp"

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

/// The region items of one frame into a stream.
class ItemTwin {
public:
    /// The frame `state` (its block, its view, its moment) with the moment's tones `tones`, into `out`.
    ItemTwin(const frame::FrameState& state, std::span<const std::uint32_t> tones, Stream& out);

    /// Emits item `item` of cover tile `tile` (its items `items`, their words `heap`: the tile's range of the points
    /// heap), drawn with rank entry `rank` and choice `choice`, its own words `extra`; items go in the jobs' order, the
    /// nearest first.
    void item(const scene::CoverTile& tile, const passes::ItemTile& items, std::span<const std::byte> heap,
              std::uint32_t item, std::uint32_t rank, const passes::ItemChoice& choice,
              std::span<const std::uint32_t> extra);

    /// Keeps the records the GPU writes for each item from here on, in its order: its faces, its opaque parts and its
    /// translucent ones (in the GPU's list: the items' blocks far to near, each in its own order).
    void keep_records(std::vector<shaders::ItemRecord>* faces, std::vector<shaders::ItemRecord>* parts,
                      std::vector<shaders::ItemRecord>* sides) noexcept {
        faces_ = faces;
        parts_ = parts;
        sides_ = sides;
    }

private:
    void tower(const shaders::ItemJob& j, const shaders::ItemWords& w, const geometry::RegionItem& it,
               hlsl::float3 foot, std::int32_t item);
    void boxes(const shaders::ItemJob& j, const shaders::ItemWords& w, hlsl::float3 a, hlsl::float3 b,
               std::uint32_t kind, std::span<const std::uint32_t> extra, std::int32_t item);
    void crane(const shaders::ItemJob& j, const shaders::ItemWords& w, hlsl::float3 foot,
               std::span<const std::uint32_t> extra, std::int32_t item);
    void mast(const shaders::ItemJob& j, const shaders::ItemWords& w, hlsl::float3 foot,
              std::span<const std::uint32_t> extra, std::int32_t item);
    void turbine(const shaders::ItemJob& j, const shaders::ItemWords& w, hlsl::float3 foot, std::int32_t item);
    void power(const shaders::ItemJob& j, const shaders::ItemWords& w, hlsl::float3 a, hlsl::float3 b,
               std::int32_t item);
    /// A face's path, as printed, filled (and stroked .25 where `stroked`) in `colour`.
    void face_path(const shaders::ItemRecord& r, std::uint32_t slot, bool stroked, std::int32_t group,
                   std::int32_t item);
    [[nodiscard]] static Paint paint(std::uint32_t bgr) noexcept;
    [[nodiscard]] float tone(std::uint32_t i) const noexcept;

    Stream& out_;
    shaders::FrameCamera camera_;
    std::array<std::uint32_t, shaders::ITEM_TONES> tones_{};
    std::int32_t objects_ = none;
    std::int32_t glow_ = none;
    std::vector<shaders::ItemRecord>* faces_ = nullptr;
    std::vector<shaders::ItemRecord>* parts_ = nullptr;
    std::vector<shaders::ItemRecord>* sides_ = nullptr;
    std::vector<shaders::ItemRecord> face_records_;
    std::vector<shaders::ItemRecord> part_records_;
    std::vector<shaders::ItemRecord> side_records_;
};

} // namespace atlas::twin
