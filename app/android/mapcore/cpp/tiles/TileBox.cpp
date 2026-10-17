#include "tiles/TileBox.hpp"

#include "base/assert.hpp"
#include "base/pymath/PyMath.hpp"

#include <limits>

namespace atlas::tiles {

std::int32_t grid_round(double degrees) noexcept {
    const std::int64_t q = pymath::java::round(degrees * 1e7);
    // Long.toInt() keeps the low 32 bits; the grid's degrees never need more, and a box beyond would be a broken
    // invariant
    ATLAS_ASSERT(q >= std::numeric_limits<std::int32_t>::min() && q <= std::numeric_limits<std::int32_t>::max(),
                 "a grid coordinate past 32 bits");
    return static_cast<std::int32_t>(q);
}

std::int32_t column_edge(std::uint8_t z, std::uint32_t x) noexcept {
    // x.toDouble() / (1 shl z) * 360.0 - 180.0, left to right
    const auto n = static_cast<double>(std::uint64_t{1} << z);
    return grid_round(static_cast<double>(x) / n * 360.0 - 180.0);
}

std::int32_t row_edge(std::uint8_t z, std::uint32_t y) noexcept {
    // Math.PI - 2.0 * Math.PI * y / (1 shl z), left to right, then toDegrees(StrictMath.atan(StrictMath.sinh(n)))
    const auto n =
        pymath::pi - ((2.0 * pymath::pi) * static_cast<double>(y)) / static_cast<double>(std::uint64_t{1} << z);
    return grid_round(pymath::degrees(pymath::strict::atan(pymath::strict::sinh(n))));
}

bool tile_box_matches(const TileHeader& h) noexcept {
    return h.west == column_edge(h.level, h.x) && h.east == column_edge(h.level, h.x + 1) &&
           h.north == row_edge(h.level, h.y) && h.south == row_edge(h.level, h.y + 1);
}

} // namespace atlas::tiles
