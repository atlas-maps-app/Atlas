// What a frame sees of the ground (plan-vector D1 "the cover", L14): boxes of the world (a tile's reach, a cell's box
// on OSM's grid with the height of what stands in it) against the view's frustum, with the renders' cull margin; the
// eye's distance to a box; a box's share of the screen. A render tile's cells (8 × 8 at L14, about 186 m; 4 × 4 at
// L12, one L14 tile each) are the cover's unit: a tile draws only its visible cells' ranges, coalesced (D5.7), and a
// missing tile is drawn from its nearest resident ancestor's cells over it.
#pragma once

#include "tiles/Payload.hpp"
#include "view/Camera.hpp"
#include "view/Ladder.hpp"

#include <array>
#include <cstdint>
#include <span>

namespace atlas::scene {

/// A box of the world in a frame's metres: x east from x0 to x1, y up from y0 to y1, z south from z0 to z1.
struct Box {
    double x0 = 0;
    double x1 = 0;
    double y0 = 0;
    double y1 = 0;
    double z0 = 0;
    double z1 = 0;
};

/// The box of a grid box (degrees × 1e7) and a top (centimetres above the street) in ground frame `frame`.
[[nodiscard]] Box box_of(const tiles::GridBox& grid, std::int32_t top_cm, const view::GroundFrame& frame) noexcept;

/// The view's frustum in its camera's coordinates: the frame plus a margin (CSS px) on every side, from the near
/// plane out to `far` metres of depth.
class Frustum {
public:
    Frustum(const view::Camera& camera, double margin_px, double far) noexcept;

    /// Whether any of the box may be seen: false only when its eight corners all lie outside one plane.
    [[nodiscard]] bool sees(const Box& box) const noexcept;
    /// The share of the frame (0 to 1) the box's projection covers, roughly: the bounding rectangle of its corners in
    /// front of the eye, clipped to the frame (1 for a box around the eye).
    [[nodiscard]] double share(const Box& box) const noexcept;
    /// The eye's distance to the nearest point of the box (0 inside it).
    [[nodiscard]] double distance(const Box& box) const noexcept;

    [[nodiscard]] const view::Camera& camera() const noexcept { return camera_; }

private:
    const view::Camera& camera_;
    std::array<view::Vec3, 5> normals_{}; // left, right, top, bottom, near: k · n >= offset is inside
    std::array<double, 5> offsets_{};
    double far_;
};

/// The visible cells of a tile: bit r · n + c for the cell in row r (from the north) and column c of its n × n
/// cells, set where the cell holds features and the frustum may see its box.
[[nodiscard]] std::uint64_t visible_cells(std::span<const tiles::CellInfo> cells, std::uint8_t per_side,
                                          const view::GroundFrame& frame, const Frustum& frustum) noexcept;

/// The cells of an ancestor `gap` levels up that lie over a descendant tile (its n × n cells cover 2^gap × 2^gap
/// descendants exactly when n = 2^gap): the bits of the ancestor's cells over descendant (x, y), or every cell when the
/// grids do not line up.
[[nodiscard]] std::uint64_t cells_over(std::uint8_t per_side, std::uint32_t gap, std::uint32_t x,
                                       std::uint32_t y) noexcept;

} // namespace atlas::scene
