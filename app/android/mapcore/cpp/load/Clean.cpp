#include "load/Clean.hpp"

#include "base/assert.hpp"
#include "load/Amsterdam.hpp"
#include "load/CleanAbi.h"
#include "tiles/Grid.hpp"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <utility>
#include <vector>

namespace atlas::load {

namespace {

/// The footprints as the library takes them: buildings first, then parts, each its ring then its courtyards.
struct Input {
    std::vector<atlas_clean_point> points;
    std::vector<std::uint32_t> sizes;
    std::vector<atlas_clean_footprint> footprints;
    std::vector<bool> on_grid; ///< per footprint: every vertex on the grid (else it is left as it is)
};

/// A ring onto the grid, or false where a vertex lies off it.
bool add_ring(Input& in, std::span<const V2> ring, const tiles::Frame& frame) {
    const std::size_t at = in.points.size();
    for (const V2& p : ring) {
        const auto lat = tiles::grid_lat(frame, p.z);
        const auto lon = tiles::grid_lon(frame, p.x);
        if (!lat || !lon) {
            in.points.resize(at);
            return false;
        }
        in.points.push_back({.lat = *lat, .lon = *lon});
    }
    in.sizes.push_back(static_cast<std::uint32_t>(ring.size()));
    return true;
}

void add(Input& in, const Building& b, bool part, const tiles::Frame& frame) {
    const auto first = static_cast<std::uint32_t>(in.sizes.size());
    const std::size_t points = in.points.size();
    bool ok = add_ring(in, b.poly, frame);
    for (const std::vector<V2>& h : b.holes) {
        if (!ok) break;
        ok = add_ring(in, h, frame);
    }
    if (!ok) {
        in.sizes.resize(first);
        in.points.resize(points);
    }
    in.footprints.push_back({.first_ring = first,
                             .rings = ok ? static_cast<std::uint32_t>(in.sizes.size()) - first : 0U,
                             .min = b.min,
                             .top = b.h,
                             .part = static_cast<std::uint8_t>(part ? 1 : 0),
                             .pad = {}});
    in.on_grid.push_back(ok);
}

/// The library's output, freed with it.
class Output {
public:
    Output() = default;
    Output(const Output&) = delete;
    Output& operator=(const Output&) = delete;
    Output(Output&&) = delete;
    Output& operator=(Output&&) = delete;
    ~Output() { atlas_clean_free(&out_); }

    [[nodiscard]] atlas_clean_output* get() noexcept { return &out_; }
    [[nodiscard]] const atlas_clean_output& operator*() const noexcept { return out_; }

private:
    atlas_clean_output out_{};
};

} // namespace

void clean_buildings(Scene& s, const tiles::Frame& frame) {
    Input in;
    in.footprints.reserve(s.buildings.size() + s.parts.size());
    for (const Building& b : s.buildings) add(in, b, false, frame);
    for (const Building& p : s.parts) add(in, p, true, frame);
    const atlas_clean_input input{.points = in.points.data(),
                                  .point_count = in.points.size(),
                                  .ring_sizes = in.sizes.data(),
                                  .ring_count = in.sizes.size(),
                                  .footprints = in.footprints.data(),
                                  .footprint_count = in.footprints.size(),
                                  .metres_per_lon = frame.kx / 1e7,
                                  .metres_per_lat = frame.kz / 1e7};
    Output out;
    const std::int32_t done = atlas_clean_run(&input, out.get());
    ATLAS_ASSERT(done == 0, "the cleaning's input does not add up");
    const atlas_clean_output& o = *out;
    // NOLINTBEGIN(cppcoreguidelines-pro-bounds-pointer-arithmetic): the library's arrays, of the counts it was given
    const auto apply = [&](std::vector<Building>& list, std::size_t first, bool part) {
        std::vector<Building> kept;
        kept.reserve(list.size());
        for (std::size_t i = 0; i < list.size(); ++i) {
            Building& b = list[i];
            const std::size_t k = first + i;
            b.holes.clear();
            if (!in.on_grid[k]) {
                kept.push_back(std::move(b));
                continue;
            }
            const std::uint8_t state = o.state[k];
            if ((state & ATLAS_CLEAN_RING) != 0) {
                std::vector<V2> ring;
                ring.reserve(o.ring_first[k + 1] - o.ring_first[k]);
                for (std::size_t v = o.ring_first[k]; v < o.ring_first[k + 1]; ++v) {
                    ring.emplace_back(frame.x(o.points[v].lon), frame.z(o.points[v].lat));
                }
                if (geometry::signed_area(ring) < 0) std::ranges::reverse(ring);
                b.poly = std::move(ring);
                b.bb = box_of(b.poly);
            }
            b.min = o.min[k];
            const bool drawn = (state & ATLAS_CLEAN_DRAWN) != 0;
            if (part && !drawn) continue; // a part others cover whole goes
            b.hidden = !drawn;
            kept.push_back(std::move(b));
        }
        list = std::move(kept);
    };
    // NOLINTEND(cppcoreguidelines-pro-bounds-pointer-arithmetic)
    const std::size_t buildings = s.buildings.size();
    apply(s.buildings, 0, false);
    apply(s.parts, buildings, true);
    BuildingCounts& c = s.counts;
    c.overlapping_pairs = o.counts.overlapping_pairs;
    c.uncovered_outlines = o.counts.uncovered_outlines;
    c.courtyards = o.counts.courtyards;
    c.covered = o.counts.covered;
    c.hidden = o.counts.hidden;
    c.clipped = o.counts.clipped;
    c.raised = o.counts.raised;
    c.vanished = o.counts.vanished;
    c.seamed = o.counts.seamed;
    c.unsettled = o.counts.unsettled;
    c.unjoined = o.counts.unjoined;
    c.uncovered_m2 = o.counts.uncovered_m2;
    c.sliver_m2 = o.counts.sliver_m2;
}

} // namespace atlas::load
