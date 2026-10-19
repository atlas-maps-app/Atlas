// The board's labels placed on the frame (plan-vector D13, K13): LabelPainter's algorithm, ported to the core, and its
// behaviour while the camera moves.
//
// - At rest, LabelPainter's placement exactly, in float as it computes: the labels by priority, highest first (a stable
//   sort); each at the first of its points, and there at the first of six spots (centred on the point, above it, then
//   beside it with its near edge on the point, level or above), where its box (its text's half width and half height,
//   each with the pad) lies within the frame and clear of the chrome's keep-out boxes, the markers and the labels
//   placed before it; a label with no such place is not drawn.
// - While moving, a placed label keeps its point and spot while its box stays within the frame and clear of the
//   keep-outs, the markers and the labels kept before it (by priority); the others are placed as at rest around them.
//   A label that goes, or takes another place, fades out over 250 ms where it stood as its new place fades in, and so
//   does the rest frame's own choice once the camera stops (MapLibre's collision approach: our own code).
//
// Everything is device px. The measures are the app's, taken from the paint LabelPainter draws with (MapCore.labels),
// so the core's boxes are LabelPainter's to the bit.
#pragma once

#include "labels/Collision.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace atlas::labels {

/// A point on the frame, device px; NaN where the point stands nearer than the camera's near clip.
struct Point {
    float x = 0.0F;
    float y = 0.0F;
};

/// The spots a label may stand at about its point, in the order tried (LabelPainter.SPOTS): x in its half widths less
/// the pad, y in its text heights.
inline constexpr std::array<float, 12> spots = {0.0F, 0.0F, 0.0F,  -0.5F, -1.0F, 0.0F,
                                                1.0F, 0.0F, -1.0F, -0.5F, 1.0F,  -0.5F};
inline constexpr std::size_t spot_count = spots.size() / 2;

/// A label's measures, as LabelPainter takes them from its paint.
struct Measures {
    float half = 0.0F;        ///< half the text's width (measureText / 2) plus the pad
    float pad = 0.0F;         ///< 4 dp
    float text_height = 0.0F; ///< descent - ascent
    float baseline = 0.0F;    ///< (ascent + descent) / 2: the baseline lies this far below the box's centre, negated
};

/// Where a label stands: the box's centre and the box.
struct Stand {
    float x = 0.0F;
    float y = 0.0F;
    Box box;
};

/// The stand of a label of measures `m` at spot `spot` about point `p`, in LabelPainter's float arithmetic.
[[nodiscard]] Stand stand_at(const Measures& m, Point p, std::size_t spot) noexcept;

/// What a frame's labels stand within and clear of: the frame, the chrome's keep-out boxes, the markers' boxes.
struct Frame {
    float width = 0.0F;
    float height = 0.0F;
    std::span<const Box> keep_out;
    std::span<const Box> markers;
};

/// One label of a frame: its stable id, its index in the caller's list, its priority, its measures and its points this
/// frame (in its own order: the likeliest first).
struct Candidate {
    std::uint32_t id = 0;
    std::uint32_t label = 0;
    std::int32_t priority = 0;
    Measures measures;
    std::span<const Point> points;
};

/// A label's place: its candidate (index into the frame's candidates), the point and the spot it took, its stand.
struct Placed {
    std::uint32_t candidate = 0;
    std::uint16_t point = 0;
    std::uint8_t spot = 0;
    Stand stand;
};

/// The order LabelPainter tries `candidates` in: their indices by priority, highest first, ties in the given order.
void priority_order(std::span<const Candidate> candidates, std::vector<std::uint32_t>& order);

/// Whether a stand lies within the frame and clear of its keep-outs and markers and of the boxes of `index`.
[[nodiscard]] bool fits(const Box& box, const Frame& frame, const CollisionIndex& index) noexcept;

/// LabelPainter's placement: `candidates` in `order`, each at its first fitting point and spot, appended to `out` in
/// that order; `index` holds the placed boxes after (it is not reset: boxes already in it are kept clear of).
void place(std::span<const Candidate> candidates, std::span<const std::uint32_t> order, const Frame& frame,
           CollisionIndex& index, std::vector<Placed>& out);

/// A label as the frame draws it.
struct Drawn {
    std::uint32_t candidate = 0; ///< index into the frame's candidates
    Stand stand;
    float baseline = 0.0F; ///< the text's baseline, device px (LabelPainter's: the centre's y less Measures::baseline)
    /// The place a label left (or the place of a label that goes), fading out; its present place is the entry
    /// without it.
    bool leaving = false;
    float alpha = 1.0F; ///< 0 to 1, the fade's
};

/// The labels of the frames, with their history.
class Placement {
public:
    /// The fade of a label coming, going or changing place, seconds.
    static constexpr double fade_seconds = 0.25;

    /// This frame's labels at `seconds`: LabelPainter's placement at rest; moving, the placed labels kept where they
    /// still fit and the others placed around them; fading. In priority order, then by id.
    [[nodiscard]] std::span<const Drawn> update(std::span<const Candidate> candidates, const Frame& frame, bool rest,
                                                double seconds);

    /// Whether a fade is still running.
    [[nodiscard]] bool fading() const noexcept { return fading_; }
    /// The last update's CPU time, ms, and how many labels it placed.
    [[nodiscard]] double millis() const noexcept { return millis_; }
    [[nodiscard]] std::uint32_t placed() const noexcept { return placed_count_; }
    /// The log's line of the last update: `label place <ms> placed <n>`.
    [[nodiscard]] std::string line() const;
    /// Forgets every label (a new set of labels).
    void reset() noexcept;

private:
    /// A label's history, by id: its place, and the place it left, fading out.
    struct State {
        std::uint32_t id = 0;
        bool present = false;
        std::uint16_t point = 0;
        std::uint8_t spot = 0;
        double since = -1e9;
        bool leaving = false;
        std::uint16_t left_point = 0;
        std::uint8_t left_spot = 0;
        double left_at = -1e9;
    };

    std::vector<State> states_; // by id
    std::vector<State> next_;
    std::vector<std::uint32_t> order_;
    std::vector<std::uint32_t> scratch_;
    std::vector<Placed> placed_;
    std::vector<Drawn> drawn_;
    std::vector<std::uint32_t> state_of_; // per candidate, its state (UINT32_MAX: none)
    std::vector<std::uint32_t> place_of_; // per candidate, its place in placed_ (UINT32_MAX: none)
    CollisionIndex index_;
    bool started_ = false;
    bool fading_ = false;
    double millis_ = 0.0;
    std::uint32_t placed_count_ = 0;
};

} // namespace atlas::labels
