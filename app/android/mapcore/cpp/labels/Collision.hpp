// The labels' collision index (plan-vector D13, K13): the boxes a label must stand clear of on the frame, in device px,
// as LabelPainter tests them (two boxes meet where their open intervals overlap on both axes, in float), kept in a
// uniform grid of cells so that a frame with many labels (K23's place names) tests each candidate against the few
// boxes near it, not against all. A frame's index is filled and queried on one thread and reset for the next; it
// allocates only when a frame holds more boxes than any before it.
#pragma once

#include <cstdint>
#include <span>
#include <vector>

namespace atlas::labels {

/// A box on the frame, device px: left, top, right and bottom.
struct Box {
    float l = 0.0F;
    float t = 0.0F;
    float r = 0.0F;
    float b = 0.0F;

    friend constexpr bool operator==(const Box&, const Box&) = default;
};

/// LabelPainter's overlap test: `a` and `b` meet where their open intervals overlap on both axes.
[[nodiscard]] constexpr bool overlaps(const Box& a, const Box& b) noexcept {
    return b.l < a.r && b.r > a.l && b.t < a.b && b.b > a.t;
}

/// Whether `box` meets any of `boxes`.
[[nodiscard]] bool overlaps_any(std::span<const Box> boxes, const Box& box) noexcept;

/// The boxes of a frame in a grid of cells.
class CollisionIndex {
public:
    /// The side of a cell, px: about a label's height, so a label's box meets a few cells.
    static constexpr float default_cell = 64.0F;

    /// Empties the index for a frame of `width` x `height` px. Boxes beyond the frame go to its edge cells.
    void reset(float width, float height, float cell = default_cell);
    /// Adds `box`.
    void insert(const Box& box);
    /// Whether `box` meets a box of the index.
    [[nodiscard]] bool hits(const Box& box) const noexcept;
    /// The boxes in the order they came.
    [[nodiscard]] std::span<const Box> boxes() const noexcept { return boxes_; }

private:
    /// The cells `box` covers: [x0, x1] x [y0, y1].
    struct Span {
        std::uint32_t x0 = 0;
        std::uint32_t y0 = 0;
        std::uint32_t x1 = 0;
        std::uint32_t y1 = 0;
    };
    [[nodiscard]] Span cells_of(const Box& box) const noexcept;

    float cell_ = default_cell;
    std::uint32_t columns_ = 1;
    std::uint32_t rows_ = 1;
    std::vector<Box> boxes_;
    /// Per cell, the first entry of its list (none: UINT32_MAX); per entry, its box and the next entry of its cell.
    std::vector<std::uint32_t> heads_;
    std::vector<std::uint32_t> entry_box_;
    std::vector<std::uint32_t> entry_next_;
};

} // namespace atlas::labels
