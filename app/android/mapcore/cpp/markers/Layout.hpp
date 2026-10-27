// Where the markers go on a frame (ENGINE.md step 6): mapview's MarkerLayout, ported, in its float arithmetic, so a
// frame's markers are the ones the canvas would place, with the same boxes and counts.
//
// The markers whose places stand on the view (within 60 dp of it) are taken by priority: the chosen one first, then
// higher priority, then the list's order. Each takes its box about its place (Metrics::box: a pin's disc on its place,
// or the chosen pin's on its stem, with its value chip below; a dot's, a tile's, a bubble's or a pill's) unless it
// meets a box placed before it, into which it folds: a placed dot or cluster that folded others is drawn as a cluster
// of their counts, any other style simply hides them. The markers are drawn in the reverse order, the most important
// on top. What the labels keep clear of (OverlayPainter.layoutMarkers' boxes) is each placed marker's box and, beside
// a named pin drawn as itself, its name's (MarkerPainter.labelBox, on the side MarkerPainter writes it).
#pragma once

#include "labels/Collision.hpp"
#include "markers/Markers.hpp"

#include <cstdint>
#include <span>
#include <vector>

namespace atlas::markers {

/// MarkerMetrics: the styles' sizes, the board's px (dp) times the density, in float.
struct Metrics {
    float density = 1.0F;

    [[nodiscard]] float pin_diameter(bool selected) const noexcept { return (selected ? 44.0F : 30.0F) * density; }
    /// The chosen pin's stem and the shadow under it; a pin that is not chosen sits on its place.
    [[nodiscard]] float pin_stem(bool selected) const noexcept { return (selected ? 10.0F : 0.0F) * density; }
    [[nodiscard]] float pin_foot(bool selected) const noexcept { return (selected ? 2.0F : 0.0F) * density; }
    [[nodiscard]] float dot_diameter(bool selected) const noexcept { return (selected ? 26.0F : 20.0F) * density; }
    [[nodiscard]] float brand_side(bool selected) const noexcept { return (selected ? 40.0F : 34.0F) * density; }
    /// A cluster's bubble: 28 dp, and 6 dp more for each tenfold of its count, at most 46.
    [[nodiscard]] float cluster_diameter(std::uint64_t count) const noexcept;
    [[nodiscard]] float chip_height(bool selected) const noexcept { return (selected ? 36.0F : 32.0F) * density; }
    [[nodiscard]] float chip_pad() const noexcept { return 5.0F * density; }
    [[nodiscard]] float chip_gap() const noexcept { return 4.0F * density; }
    [[nodiscard]] float chip_end() const noexcept { return 9.0F * density; }
    [[nodiscard]] float chip_disc() const noexcept { return 24.0F * density; }
    [[nodiscard]] float value_chip_height() const noexcept { return 18.0F * density; }
    [[nodiscard]] float value_chip_pad() const noexcept { return 7.0F * density; }
    [[nodiscard]] float value_chip_gap() const noexcept { return 3.0F * density; }
    [[nodiscard]] float border() const noexcept { return 2.0F * density; }
    /// The chip's width for a text `text` px wide: padding, symbol disc, gap, text, end padding.
    [[nodiscard]] float chip_width(bool selected, float text) const noexcept;
    /// The size a pin's name is written in (MarkerPainter's name paint: 13 dp).
    [[nodiscard]] float name_size() const noexcept { return 13.0F * density; }

    /// MarkerLayout.boxOf: the box of `m` with its place at (x, y).
    [[nodiscard]] labels::Box box(const Marker& m, float x, float y) const noexcept;
    /// MarkerPainter.labelLeft's choice: whether a pin's name goes left of the pin at x on a view `width` px wide (the
    /// right is taken unless the name would run off the view there and fits on the left).
    [[nodiscard]] bool name_left(const Marker& m, float x, float width) const noexcept;
    /// MarkerPainter.labelBox: the box of a pin's name beside it, with its place at (x, y).
    [[nodiscard]] labels::Box name_box(const Marker& m, float x, float y, float width) const noexcept;
};

/// A frame's markers, placed.
class Layout {
public:
    /// How far beyond the view a marker's place may stand and still be placed, dp (OverlayPainter.layoutMarkers).
    static constexpr float margin_dp = 60.0F;

    /// Places `markers` whose places are at (`xs`, `ys`), px, where `visible` (non-zero), on a view `width` x `height`
    /// px: MarkerLayout.layout. The results hold until the next call.
    void place(std::span<const Marker> markers, std::span<const float> xs, std::span<const float> ys,
               std::span<const std::uint8_t> visible, const Metrics& metrics, float width, float height);

    /// The placed markers in draw order (the lowest priority first), as indices into the markers.
    [[nodiscard]] std::span<const std::uint32_t> placed() const noexcept { return placed_; }
    /// Each placed marker's box.
    [[nodiscard]] std::span<const labels::Box> boxes() const noexcept { return boxes_; }
    /// Each placed marker's cluster count: 0 where it is drawn as itself.
    [[nodiscard]] std::span<const std::uint64_t> clusters() const noexcept { return clusters_; }

private:
    /// BoxGrid: a uniform grid over the view and a view's width and height beyond it on every side, for the first
    /// placed box a box meets.
    class Grid {
    public:
        void reset(float width, float height, float cell);
        void add(std::uint32_t index, const labels::Box& box);
        /// The first placed box (the lowest index) of `boxes` that `box` meets, or -1.
        [[nodiscard]] std::int64_t first_overlap(const labels::Box& box, std::span<const labels::Box> boxes) const;

    private:
        [[nodiscard]] std::uint32_t column(float x) const noexcept;
        [[nodiscard]] std::uint32_t row(float y) const noexcept;
        std::uint32_t columns_ = 1;
        std::uint32_t rows_ = 1;
        float cell_ = 64.0F;
        float origin_x_ = 0.0F;
        float origin_y_ = 0.0F;
        std::vector<std::int32_t> head_;
        std::vector<std::int32_t> next_;
        std::vector<std::uint32_t> item_;
    };

    std::vector<std::uint64_t> keys_;
    std::vector<std::uint32_t> placed_;
    std::vector<labels::Box> boxes_;
    std::vector<std::uint64_t> folded_;
    std::vector<std::uint64_t> clusters_;
    Grid grid_;
};

} // namespace atlas::markers
