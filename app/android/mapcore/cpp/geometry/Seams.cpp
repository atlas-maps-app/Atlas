#include "geometry/Seams.hpp"

#include "load/CleanAbi.h"

#include <cstddef>
#include <optional>
#include <type_traits>

namespace atlas::geometry {

static_assert(std::is_standard_layout_v<V2> && sizeof(V2) == 2 * sizeof(double), "V2 is x then z, two doubles");

std::vector<std::uint8_t> seams(std::span<const V2> ring) {
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-reinterpret-cast): V2 is two doubles, x then z
    return seams_xz({reinterpret_cast<const double*>(ring.data()), 2 * ring.size()});
}

std::vector<std::uint8_t> seams_xz(std::span<const double> xz) {
    std::vector<std::uint8_t> out(xz.size() / 2);
    if (atlas_clean_seams(xz.data(), out.size(), out.data()) == 0) out.clear();
    return out;
}

std::optional<std::vector<RingPiece>> rings_of(const tiles::RingView& ring) {
    std::vector<atlas_clean_point> in(ring.size());
    for (std::size_t i = 0; i < ring.size(); ++i) in[i] = {.lat = ring.lat[i], .lon = ring.lon[i]};
    atlas_clean_rings_output out{};
    if (atlas_clean_rings(in.data(), in.size(), &out) != 0) return std::nullopt;
    std::vector<RingPiece> pieces;
    // NOLINTBEGIN(cppcoreguidelines-pro-bounds-pointer-arithmetic): the library's arrays, of the counts it gave
    for (std::size_t r = 0; r < out.ring_count; ++r) {
        std::vector<tiles::GridPoint> points;
        for (std::size_t v = out.ring_first[r]; v < out.ring_first[r + 1]; ++v) {
            points.emplace_back(out.points[v].lat, out.points[v].lon);
        }
        if (out.piece[r] >= pieces.size()) {
            pieces.push_back({.outer = std::move(points), .holes = {}});
        } else {
            pieces.back().holes.push_back(std::move(points));
        }
    }
    // NOLINTEND(cppcoreguidelines-pro-bounds-pointer-arithmetic)
    atlas_clean_rings_free(&out);
    return pieces;
}

} // namespace atlas::geometry
