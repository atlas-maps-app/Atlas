// The water's pieces (geometry/Water.hpp).
#include "geometry/Water.hpp"

#include <algorithm>

namespace atlas::geometry {

void orient_water(std::span<P2> ring, bool hole) noexcept {
    const double a = signed_area(ring);
    if (a == 0.0) return;
    if ((a > 0) != hole) return;
    std::ranges::reverse(ring);
}

void fan(std::uint32_t first, std::uint32_t count, std::vector<std::uint32_t>& out) {
    for (std::uint32_t i = 1; i + 1 < count; ++i) {
        out.push_back(first);
        out.push_back(first + i);
        out.push_back(first + i + 1);
    }
}

} // namespace atlas::geometry
