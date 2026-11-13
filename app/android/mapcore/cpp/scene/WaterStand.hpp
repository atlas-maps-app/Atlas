// Where a water's name stands when its own point does not show (ENGINE.md step 5, "Names"). A water is named at one
// point, the middle of the largest circle within it (the packer's), and a view that shows the water need not show
// that point: the Markermeer lies along the east side of a view from 100 km over Amsterdam while its point lies past
// the frame's side. Its name then stands where its water shows on the frame, as near its own point as the frame
// lets it.
//
//   - From the water's point lines run over the ground toward the camera's target and to either side of that way
//     (`rays` of them, `spread` degrees apart), each sampled every `step` metres (8 dp of the screen at the target,
//     more for a water so large that a line would take over `most` samples) out to three radii of a disc of the
//     water's area. A line ends where the frame draws land (it has left the water; where the frame draws no tile
//     nothing is known, and the line goes on), and at its first sample that is water on the frame clear of its edges
//     by the name's own measures: half its width from the sides, its height from the top and the foot.
//   - The stand is the nearest of the lines' ends to the water's point: joined to it by water, so the same water.
//     The name's middle is over its water; its ends may lie over the shore, as a name's at its own point may (a
//     lake's strip along the frame's side is named). A water none of whose lines ends on the frame has no stand.
//   - A water whose point lies farther from the frame than its lines run (twice over, by the screen's scale at the
//     camera's target, for a pitched view's sake) is not looked at: most waters past a frame's edge are ponds.
//   - A stand is a place of the ground: the pick keeps it while it lies on the frame clear of its edges and is water
//     (WorldNames, which gives it an id of its own, so the name fades from its point to its stand and back).
//
// Pure: where a place lies on the frame and whether it is water are the caller's to say.
#pragma once

#include "scene/WorldNames.hpp"
#include "view/Camera.hpp"

#include <cstdint>
#include <functional>
#include <optional>

namespace atlas::scene {

/// The frame a pick's stands are found on.
struct StandFrame {
    double width = 0;  ///< device px
    double height = 0; ///< device px
    double step = 0;   ///< a line's step, metres (stand_step_dp of the screen at the camera's target)
    double px = 0;     ///< the device px a metre of the ground is at the camera's target (0: unknown)
    double lat = 0;    ///< the camera's target, degrees
    double lon = 0;
};

/// A line's step on the screen at the camera's target, dp.
inline constexpr double stand_step_dp = 8.0;

/// The stands of a pick's waters.
class WaterStands {
public:
    /// The lines from a water's point: one toward the camera's target and `rays / 2` to either side, `spread`
    /// degrees apart; the samples a line takes at most; and how many radii of a disc of the water's area it runs.
    static constexpr int rays = 13;
    static constexpr double spread = 12.0;
    static constexpr int most = 256;
    static constexpr double radii = 3.0;

    /// Where a place lies on the frame, device px, or nothing where it does not show (scene::name_point).
    using Point = std::function<std::optional<view::Vec2>(double lat, double lon)>;
    /// Whether the frame draws water at a place, or nothing where it draws no tile.
    using Water = std::function<std::optional<bool>(double lat, double lon)>;

    /// A pick begins: its frame, and what its lines ask (both must outlive the pick's finds).
    void begin(const StandFrame& frame, const Point* point, const Water* water);

    /// The stand of `water` (a water of a known area, at its point), its name `half` device px wide to each side of
    /// its middle and `tall` px high, or nothing (the file's head).
    [[nodiscard]] std::optional<NamePlace> find(const WorldName& water, double half, double tall);

    /// Whether a place lies on the frame clear of its edges by `clear` device px.
    [[nodiscard]] bool on_frame(double lat, double lon, double clear) const;

    /// How many samples the finds have asked of the frame since the map began (for tests and the cost's measure).
    [[nodiscard]] std::uint64_t samples() const noexcept { return samples_; }

private:
    StandFrame frame_;
    const Point* point_ = nullptr;
    const Water* water_ = nullptr;
    std::uint64_t samples_ = 0;
};

} // namespace atlas::scene
