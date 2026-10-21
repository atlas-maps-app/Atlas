// The board's labels placed on the frame (plan-vector D13, K13; ENGINE.md design point 8): LabelPainter's algorithm,
// ported to the core, for where a label goes; once placed, it stays there on the map and grows and shrinks with it.
//
// - A label is placed once, as LabelPainter places it, in float as it computes: the labels by priority, highest first
//   (a stable sort); each at the first of its points, and there at the first of six spots (centred on the point, above
//   it, then beside it with its near edge on the point, level or above), where its box (its text's half width and half
//   height, each with the pad) lies within the frame and clear of the chrome's keep-out boxes, the markers and the
//   labels placed before it. The first frame of a set is LabelPainter's placement exactly.
// - From then on it keeps its point and spot, at rest and moving alike: no re-placing, no sliding or jumping. Its size
//   follows the map's scale since its set was placed (the camera's distance: a tilted view keeps the zoom's size), so a
//   set placed clear of collisions stays clear at every zoom.
// - Turning and tilting move the points but keep the boxes level, so placed labels can meet: each frame, by priority,
//   a placed label that meets one kept before it, a keep-out or a marker fades out where it stands, and fades back in
//   once it is clear again (by a pixel, so it does not flicker at the edge). Labels not yet placed (a point the frame
//   brings in) are placed around the kept ones, at the set's size.
// - The band: a set is drawn from 0.7 to 1.5 times its own size (the readable floor, Frame::least, may raise the low
//   end); beyond it the set is placed anew at its own size, each label fading out over 250 ms where it stood as its
//   new place fades in (the set standing for its own next level until the levels' sets come).
//
// Everything is device px. The measures are the app's, taken from the paint LabelPainter draws with (MapCore.labels),
// so at its own size a label's box is LabelPainter's to the bit.
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

/// What a frame's labels stand within and clear of: the frame, the chrome's keep-out boxes, the markers' boxes; and the
/// least size a label may draw at, a fraction of its own (the readable floor: 0, none).
struct Frame {
    float width = 0.0F;
    float height = 0.0F;
    std::span<const Box> keep_out;
    std::span<const Box> markers;
    float least = 0.0F;
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

/// Whether a stand lies clear of the frame's keep-outs and markers and of the boxes of `index`, wherever it lies.
[[nodiscard]] bool clear_of(const Box& box, const Frame& frame, const CollisionIndex& index) noexcept;

/// Measures `m` at `scale` times their size.
[[nodiscard]] Measures scaled(const Measures& m, float scale) noexcept;

/// LabelPainter's placement: `candidates` in `order`, each at its first fitting point and spot, appended to `out` in
/// that order; `index` holds the placed boxes after (it is not reset: boxes already in it are kept clear of).
void place(std::span<const Candidate> candidates, std::span<const std::uint32_t> order, const Frame& frame,
           CollisionIndex& index, std::vector<Placed>& out);

/// A label as the frame draws it.
struct Drawn {
    std::uint32_t candidate = 0; ///< index into the frame's candidates
    Stand stand;
    float baseline = 0.0F; ///< the text's baseline, device px (LabelPainter's: the centre's y less Measures::baseline)
    /// The place a label left (its place in the set's band before), fading out; its present place is the entry
    /// without it.
    bool leaving = false;
    float alpha = 1.0F; ///< 0 to 1, the fade's
    /// Its size, a fraction of its own (its measures' and its sprite's): the map's scale since its set was placed.
    float scale = 1.0F;
};

/// The labels of the frames, with their history.
class Placement {
public:
    /// The fade of a label coming, going or changing place, seconds.
    static constexpr double fade_seconds = 0.25;
    /// The band a set is drawn in, a fraction of its own size; and how much clearer than its box a hidden label must
    /// be to come back, px.
    static constexpr double band_low = 0.7;
    static constexpr double band_high = 1.5;
    static constexpr float margin = 1.0F;

    /// This frame's labels at `seconds`, the map's scale being `scale` (anything that grows with the map as the zoom
    /// does: the reciprocal of the camera's distance): each placed once, kept, scaled, faded where it meets another or
    /// leaves the band (the file's head). In priority order, then by id.
    [[nodiscard]] std::span<const Drawn> update(std::span<const Candidate> candidates, const Frame& frame, double scale,
                                                double seconds);

    /// Whether a fade is still running.
    [[nodiscard]] bool fading() const noexcept { return fading_; }
    /// The last update's CPU time, ms, and how many labels it drew clear (placed and not hidden).
    [[nodiscard]] double millis() const noexcept { return millis_; }
    [[nodiscard]] std::uint32_t placed() const noexcept { return placed_count_; }
    /// The set's size this frame, a fraction of its own; and the scale its band was placed at (0: none yet).
    [[nodiscard]] float size() const noexcept { return size_; }
    [[nodiscard]] double reference() const noexcept { return reference_; }
    /// The log's line of the last update: `label place <ms> placed <n>`.
    [[nodiscard]] std::string line() const;
    /// Forgets every label (a new set of labels).
    void reset() noexcept;

private:
    /// A label's history, by id: its place in the set's band (kept from its first frame there), whether it is drawn
    /// clear or fading out where it stands, and its place in the band before, fading out.
    struct State {
        std::uint32_t id = 0;
        bool present = false;
        bool shown = false;
        std::uint16_t point = 0;
        std::uint8_t spot = 0;
        double since =
            -1e9; ///< when its fade in (shown) or out began, set back so a turned fade goes on from its alpha
        bool leaving = false;
        std::uint16_t left_point = 0;
        std::uint8_t left_spot = 0;
        double left_at = -1e9;
        double left_reference = 0.0; ///< the scale its band before was placed at
    };

    std::vector<State> states_; // by id
    std::vector<State> next_;
    std::vector<std::uint32_t> order_;
    std::vector<std::uint32_t> scratch_;
    std::vector<Candidate> scaled_; // the candidates at the set's size this frame
    std::vector<Placed> placed_;
    std::vector<std::uint8_t> clear_; // per place in placed_: drawn clear
    std::vector<Drawn> drawn_;
    std::vector<std::uint32_t> state_of_; // per candidate, its state (UINT32_MAX: none)
    std::vector<std::uint32_t> place_of_; // per candidate, its place in placed_ (UINT32_MAX: none)
    CollisionIndex index_;
    bool started_ = false;
    bool fading_ = false;
    double millis_ = 0.0;
    std::uint32_t placed_count_ = 0;
    double reference_ = 0.0;
    float size_ = 1.0F;
};

} // namespace atlas::labels
