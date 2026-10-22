#include "tiles/Grid.hpp"

#include "base/pymath/PyMath.hpp"
#include "tiles/Schema.hpp"
#include "tiles/TileBox.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <limits>

namespace atlas::tiles {

namespace {

/// Grid units per degree.
constexpr double scale = 1e7;

/// Web Mercator's latitude limit; tiles clamp beyond it (PackTiles.MAX_LAT).
constexpr double max_lat = 85.0511287798066;

/// Grid.latOf's and lonOf's search around the nearest grid integer.
constexpr std::array<std::int64_t, 9> search = {0, -1, 1, -2, 2, -3, 3, -4, 4};

/// Whether `a` is `b` as Grid's test has it: equal, and for zero of the same sign (`1.0 / a == 1.0 / b`).
[[nodiscard]] bool same(double a, double b) noexcept {
    return a == b && (b != 0.0 || std::signbit(a) == std::signbit(b));
}

/// The first grid integer near `guess` whose value `at` gives is exactly `v`.
template <class At>
[[nodiscard]] std::optional<std::int32_t> invert(std::int64_t guess, double v, At at) noexcept {
    for (const std::int64_t d : search) {
        const std::int64_t q = guess + d;
        if (q < std::numeric_limits<std::int32_t>::min() || q > std::numeric_limits<std::int32_t>::max()) continue;
        if (same(at(static_cast<std::int32_t>(q)), v)) return static_cast<std::int32_t>(q);
    }
    return std::nullopt;
}

[[nodiscard]] double degrees_of(std::int32_t q) noexcept { return static_cast<double>(q) / scale; }

/// PackTiles' clamp of a floored tile number to the world's `n` tiles (NaN's toInt() is 0).
[[nodiscard]] std::uint32_t whole(double t, std::uint32_t n) noexcept {
    if (!(t >= 0)) return 0;
    if (t >= static_cast<double>(n)) return n - 1;
    return static_cast<std::uint32_t>(t);
}

} // namespace

std::optional<std::int32_t> grid_lat(const Frame& frame, double z) noexcept {
    const std::int64_t guess = pymath::java::round((frame.lat0 - z / frame.kz) * scale);
    return invert(guess, z, [&](std::int32_t q) { return frame.z(q); });
}

std::optional<std::int32_t> grid_lon(const Frame& frame, double x) noexcept {
    const std::int64_t guess = pymath::java::round((frame.lon0 + x / frame.kx) * scale);
    return invert(guess, x, [&](std::int32_t q) { return frame.x(q); });
}

GridPoint nearest_grid(const Frame& frame, double x, double z) noexcept {
    return {grid_round(frame.lat0 - z / frame.kz), grid_round(frame.lon0 + x / frame.kx)};
}

std::uint32_t tile_x(double lon, std::uint8_t z) noexcept {
    const std::uint32_t n = std::uint32_t{1} << z;
    return whole(std::floor((lon + 180.0) / 360.0 * static_cast<double>(n)), n);
}

std::uint32_t tile_y(double lat, std::uint8_t z) noexcept {
    const std::uint32_t n = std::uint32_t{1} << z;
    const double la = lat > max_lat ? max_lat : (lat < -max_lat ? -max_lat : lat);
    const double r = la * (pymath::pi / 180.0);
    const double y =
        (1.0 - pymath::strict::log(pymath::strict::tan(r) + 1.0 / pymath::strict::cos(r)) / pymath::pi) / 2.0;
    return whole(std::floor(y * static_cast<double>(n)), n);
}

TileKey tile_of(std::uint8_t z, std::int32_t lat, std::int32_t lon) noexcept {
    const std::uint32_t n = std::uint32_t{1} << z;
    std::uint32_t x = tile_x(degrees_of(lon), z);
    while (x > 0 && lon < column_edge(z, x)) --x;
    while (x < n - 1 && lon >= column_edge(z, x + 1)) ++x;
    std::uint32_t y = tile_y(degrees_of(lat), z);
    while (y > 0 && lat >= row_edge(z, y)) --y;
    while (y < n - 1 && lat < row_edge(z, y + 1)) ++y;
    return {z, x, y};
}

std::array<std::int32_t, 4> tile_box(const TileKey& t) noexcept {
    return {row_edge(t.z, t.y + 1), column_edge(t.z, t.x), row_edge(t.z, t.y), column_edge(t.z, t.x + 1)};
}

std::uint16_t cell_of(const TileKey& tile, std::int32_t lat, std::int32_t lon) noexcept {
    const std::int64_t n = cells_per_side(tile.z);
    const auto b = tile_box(tile);
    const std::int64_t col = std::clamp<std::int64_t>(
        ((static_cast<std::int64_t>(lon) - b[1]) * n) / (static_cast<std::int64_t>(b[3]) - b[1]), 0, n - 1);
    const std::int64_t row = std::clamp<std::int64_t>(
        ((static_cast<std::int64_t>(b[2]) - lat) * n) / (static_cast<std::int64_t>(b[2]) - b[0]), 0, n - 1);
    return static_cast<std::uint16_t>(row * n + col);
}

GridPoint centre_of(const std::array<std::int32_t, 4>& box) noexcept {
    const auto half = [](std::int64_t a, std::int64_t b) {
        return static_cast<std::int32_t>(pymath::floor_div(a + b, std::int64_t{2}));
    };
    return {half(box[0], box[2]), half(box[1], box[3])};
}

} // namespace atlas::tiles
