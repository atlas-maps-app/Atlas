// The street furniture's twin (plan-vector D16's L1; ENGINE.md step 4, "Trees and things"): what the things pass draws
// at a frame, as the port's SVG elements (render/amsterdam.py's draw_lamp, draw_bench, draw_box, draw_bollard,
// draw_rack), into the twin's stream. It decides each drawn thing as things_cs does, with the shader's own text
// (twin/tree/TreeShader.hpp's things.slangh) over the points heap's words, and emits in the port's order, each in the
// thing's item (its OSM node):
//   - a lamp: its mast, one open subpath stroked round-capped (slot 1; its fill SVG's black, which a line paints
//     nothing with), its lantern (2) and cap (3), closed paths, by day its glint, an open subpath at its opacity (4);
//   - a box: its leg, an open subpath stroked 1 px (slot k), its top as face() prints it, a closed path (k + 1): a
//     bench's seat (0, 1) and back (2, 3), a basket (0, 1);
//   - a bollard: its post (0), by day its head, a circle at its opacity (1);
//   - a rack: one path of its arcs' open subpaths (0), butt-capped, round-joined;
//   - a railing's piece: one path of its rails' and posts' open subpaths (0), round-capped, in its road's item (a
//     way).
// Each of its records (the GPU's, in their order) can be kept for the tests.
#pragma once

#include "frame/FrameBlock.hpp"
#include "passes/Things.hpp"
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

/// The street furniture of one frame into a stream.
class ThingTwin {
public:
    /// The frame `state` (its block, its view, its moment) with the active pack's `style`, into `out`.
    ThingTwin(const frame::FrameState& state, const tiles::Style& style, Stream& out);

    /// Emits thing `thing` of cover tile `tile` (its things `things`, their words `heap`: the tile's range of the
    /// points heap), drawn with rank entry `rank` at its item's depth `depth`; things go in the jobs' order, the
    /// nearest first.
    void thing(const scene::CoverTile& tile, const passes::ThingTile& things, std::span<const std::byte> heap,
               std::uint32_t thing, std::uint32_t rank, double depth);

    /// Keeps the records the GPU writes for each thing from here on, in its order (the translucent ones in the jobs'
    /// order, the nearest first: the GPU's list holds them the other way round).
    void keep_records(std::vector<shaders::TreeRecord>* records, std::vector<shaders::TreeRecord>* sides) noexcept {
        records_ = records;
        sides_ = sides;
    }

private:
    void lamp(const shaders::ThingJob& j, hlsl::float3 foot, float kz, std::int32_t item);
    void box(const shaders::ThingJob& j, hlsl::float3 foot, float dz, float w, float dd, float h, std::uint32_t top,
             std::uint32_t leg, std::uint32_t k, float kz, std::uint32_t at, std::int32_t item);
    void bollard(const shaders::ThingJob& j, hlsl::float3 foot, float kz, std::int32_t item);
    void rack(const shaders::ThingJob& j, hlsl::float3 foot, float kz, std::int32_t item);
    void railing(const shaders::ThingJob& j, hlsl::float3 a, hlsl::float3 b, float kz, std::int32_t item);
    /// Record `k` of the thing's.
    void put(std::uint32_t k, const shaders::TreeRecord& r);
    [[nodiscard]] static Paint paint(std::uint32_t bgr) noexcept;

    Stream& out_;
    shaders::FrameCamera camera_;
    std::array<std::uint32_t, 17> tones_{};
    bool night_ = false;
    bool weather_ = false;
    bool wet_ = false;
    double focal_ = 0;
    std::int32_t objects_ = none;
    std::vector<shaders::TreeRecord>* records_ = nullptr;
    std::vector<shaders::TreeRecord>* sides_ = nullptr;
    std::vector<shaders::TreeRecord> thing_records_;
    std::vector<shaders::TreeRecord> side_records_; ///< the thing's translucent records, in the GPU's order
};

} // namespace atlas::twin
