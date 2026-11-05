// What a frame sees of the ground (scene/Cells.hpp).
#include "scene/Cells.hpp"

#include "base/pymath/PyMath.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

namespace atlas::scene {

namespace {

/// Below the street: water lies 1.5 m down in the canal ring, quays and moats a few metres; the boxes reach this far.
constexpr double below_street = -6.0;

[[nodiscard]] double dot(const view::Vec3& a, const view::Vec3& b) noexcept {
    return a.x * b.x + a.y * b.y + a.z * b.z;
}

/// The box's eight corners in camera coordinates.
[[nodiscard]] std::array<view::Vec3, 8> corners(const view::Camera& camera, const Box& b) noexcept {
    std::array<view::Vec3, 8> k{};
    std::size_t i = 0;
    for (const double x : {b.x0, b.x1}) {
        for (const double y : {b.y0, b.y1}) {
            for (const double z : {b.z0, b.z1}) k[i++] = camera.cam({x, y, z});
        }
    }
    return k;
}

} // namespace

Box box_of(const tiles::GridBox& grid, std::int32_t top_cm, const view::GroundFrame& frame) noexcept {
    const double west = tiles::Frame::degrees(grid.west);
    const double east = tiles::Frame::degrees(grid.east);
    const double north = tiles::Frame::degrees(grid.north);
    const double south = tiles::Frame::degrees(grid.south);
    return {.x0 = frame.x(0, west),
            .x1 = frame.x(0, east),
            .y0 = below_street,
            .y1 = std::max(0.0, static_cast<double>(top_cm) / 100.0),
            .z0 = frame.z(north, 0),
            .z1 = frame.z(south, 0)};
}

Frustum::Frustum(const view::Camera& camera, double margin_px, double far, double drop) noexcept
    : camera_(camera), far_(far), drop_(drop) {
    const double f = camera.focal();
    const double m = margin_px;
    normals_[0] = {f, 0, camera.cx() + m};                   // left: x ≥ −m
    normals_[1] = {-f, 0, camera.width() + m - camera.cx()}; // right: x ≤ W + m
    normals_[2] = {0, -f, camera.cy() + m};                  // top: y ≥ −m
    normals_[3] = {0, f, camera.height() + m - camera.cy()}; // bottom: y ≤ H + m
    normals_[4] = {0, 0, 1};                                 // near
    offsets_ = {0, 0, 0, 0, camera.near()};
}

Box Frustum::bent(const Box& box) const noexcept {
    if (drop_ == 0.0) return box;
    const view::Vec3& t = camera_.spec().target;
    // an interval's nearest and farthest distance from t, along one axis
    const auto near = [](double lo, double hi, double at) { return at < lo ? lo - at : (at > hi ? at - hi : 0.0); };
    const auto far = [](double lo, double hi, double at) { return std::max(std::abs(lo - at), std::abs(hi - at)); };
    const double nx = near(box.x0, box.x1, t.x);
    const double nz = near(box.z0, box.z1, t.z);
    const double fx = far(box.x0, box.x1, t.x);
    const double fz = far(box.z0, box.z1, t.z);
    Box b = box;
    b.y0 -= drop_ * ((fx * fx) + (fz * fz));
    b.y1 -= drop_ * ((nx * nx) + (nz * nz));
    return b;
}

bool Frustum::sees(const Box& box) const noexcept {
    const auto k = corners(camera_, bent(box));
    for (std::size_t p = 0; p < normals_.size(); ++p) {
        const bool outside =
            std::ranges::all_of(k, [&](const view::Vec3& c) { return dot(c, normals_[p]) < offsets_[p]; });
        if (outside) return false;
    }
    return !std::ranges::all_of(k, [&](const view::Vec3& c) { return c.z > far_; });
}

double Frustum::share(const Box& box) const noexcept {
    const auto k = corners(camera_, bent(box));
    double x0 = std::numeric_limits<double>::infinity();
    double x1 = -x0;
    double y0 = x0;
    double y1 = -x0;
    for (const view::Vec3& c : k) {
        if (c.z <= camera_.near()) return 1.0; // it reaches the eye: the most there is
        const view::Vec2 s = camera_.pc(c);
        x0 = pymath::min(x0, s.x);
        x1 = pymath::max(x1, s.x);
        y0 = pymath::min(y0, s.y);
        y1 = pymath::max(y1, s.y);
    }
    const double w = camera_.width();
    const double h = camera_.height();
    const double cw = pymath::max(0.0, pymath::min(w, x1) - pymath::max(0.0, x0));
    const double ch = pymath::max(0.0, pymath::min(h, y1) - pymath::max(0.0, y0));
    return w > 0 && h > 0 ? cw * ch / (w * h) : 0.0;
}

double Frustum::distance(const Box& box) const noexcept {
    const Box b = bent(box);
    const view::Vec3& e = camera_.eye();
    const double dx = e.x < b.x0 ? b.x0 - e.x : (e.x > b.x1 ? e.x - b.x1 : 0.0);
    const double dy = e.y < b.y0 ? b.y0 - e.y : (e.y > b.y1 ? e.y - b.y1 : 0.0);
    const double dz = e.z < b.z0 ? b.z0 - e.z : (e.z > b.z1 ? e.z - b.z1 : 0.0);
    return std::sqrt(dx * dx + dy * dy + dz * dz);
}

std::uint64_t visible_cells(std::span<const tiles::CellInfo> cells, std::uint8_t per_side,
                            const view::GroundFrame& frame, const Frustum& frustum) noexcept {
    std::uint64_t bits = 0;
    const std::size_t n = std::min<std::size_t>(cells.size(), std::size_t{per_side} * per_side);
    for (std::size_t c = 0; c < n && c < 64; ++c) {
        const tiles::CellInfo& cell = cells[c];
        if (cell.total == 0) continue;
        const tiles::GridBox grid{.south = cell.south, .west = cell.west, .north = cell.north, .east = cell.east};
        if (frustum.sees(box_of(grid, cell.top_cm, frame))) bits |= std::uint64_t{1} << c;
    }
    return bits;
}

std::uint64_t cells_over(std::uint8_t per_side, std::uint32_t gap, std::uint32_t x, std::uint32_t y) noexcept {
    const std::uint32_t n = per_side;
    if (n == 0 || n > 8) return 0;
    const std::uint64_t all = n * n == 64 ? ~std::uint64_t{0} : (std::uint64_t{1} << (n * n)) - 1;
    if (gap >= 32) return all;
    const std::uint32_t side = std::uint32_t{1} << gap; // descendants a side
    if (side >= n) {
        // each cell over (side / n)² descendants: the one holding (x, y)
        const std::uint32_t per = side / n;
        const std::uint32_t cx = (x & (side - 1)) / per;
        const std::uint32_t cy = (y & (side - 1)) / per;
        return std::uint64_t{1} << (cy * n + cx);
    }
    // each descendant under k × k cells
    const std::uint32_t k = n / side;
    const std::uint32_t cx = (x & (side - 1)) * k;
    const std::uint32_t cy = (y & (side - 1)) * k;
    std::uint64_t bits = 0;
    for (std::uint32_t r = 0; r < k; ++r) {
        for (std::uint32_t c = 0; c < k; ++c) bits |= std::uint64_t{1} << ((cy + r) * n + cx + c);
    }
    return bits;
}

std::uint64_t cells_overlapping(std::uint8_t per_side, const std::array<std::int32_t, 4>& tile,
                                const std::array<std::int32_t, 4>& part) noexcept {
    const std::int64_t n = per_side;
    if (n == 0 || n > 8) return 0;
    // two levels' edges on the grid (tile_box's, the cells' ceilings) part by a unit or two where they agree
    constexpr std::int64_t rounding = 16;
    const std::int64_t south = tile[0];
    const std::int64_t west = tile[1];
    const std::int64_t north = tile[2];
    const std::int64_t east = tile[3];
    // WorldGeometry.cellBoxes: row r's north edge north − ⌈r · (north − south) / n⌉, column k's west edge
    // west + ⌈k · (east − west) / n⌉
    const auto row = [&](std::int64_t r) { return north - pymath::floor_div((r * (north - south)) + n - 1, n); };
    const auto column = [&](std::int64_t k) { return west + pymath::floor_div((k * (east - west)) + n - 1, n); };
    std::uint64_t bits = 0;
    for (std::int64_t r = 0; r < n; ++r) {
        if (std::min<std::int64_t>(row(r), part[2]) - std::max<std::int64_t>(row(r + 1), part[0]) <= rounding) continue;
        for (std::int64_t k = 0; k < n; ++k) {
            if (std::min<std::int64_t>(column(k + 1), part[3]) - std::max<std::int64_t>(column(k), part[1]) <=
                rounding) {
                continue;
            }
            bits |= std::uint64_t{1} << static_cast<std::uint32_t>((r * n) + k);
        }
    }
    return bits;
}

} // namespace atlas::scene
