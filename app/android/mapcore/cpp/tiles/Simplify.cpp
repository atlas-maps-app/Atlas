#include "tiles/Simplify.hpp"

#include "base/assert.hpp"

#include <cmath>

namespace atlas::tiles {

double signed_area(const RingView& ring, std::span<const std::uint32_t> indexes, const Frame& frame) noexcept {
    // s += x1·z2 − x2·z1 over each edge, then s / 2: real.py's loop, every operation rounded on its own
    double s = 0.0;
    const std::size_t n = indexes.size();
    for (std::size_t i = 0; i < n; ++i) {
        const std::uint32_t a = indexes[i];
        const std::uint32_t b = indexes[(i + 1) % n];
        const double x1 = frame.x(ring.lon[a]);
        const double z1 = frame.z(ring.lat[a]);
        const double x2 = frame.x(ring.lon[b]);
        const double z2 = frame.z(ring.lat[b]);
        s += (x1 * z2) - (x2 * z1);
    }
    return s / 2;
}

Simplified simplify(const RingView& ring, unsigned level, const Frame& frame, std::span<std::uint32_t> out) noexcept {
    const std::size_t n = ring.size();
    ATLAS_ASSERT(out.size() >= n, "the output must hold the whole ring");
    auto whole = [&] {
        for (std::size_t i = 0; i < n; ++i) out[i] = static_cast<std::uint32_t>(i);
        return Simplified{.count = n, .whole = true};
    };
    if (!ring.has_keep || level == 0 || n < keep::min_points) return whole();
    std::size_t k = 0;
    for (std::size_t i = 0; i < n; ++i) {
        if (ring.keep[i] >= level) out[k++] = static_cast<std::uint32_t>(i);
    }
    if (k < 3 || !(std::abs(signed_area(ring, out.first(k), frame)) > 2.0)) return whole();
    return {.count = k, .whole = k == n};
}

} // namespace atlas::tiles
