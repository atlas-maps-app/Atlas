// The frame drawn (plan-vector D4, "The frame"; D2 "Coordinates"): the renders' region frame inside a renders' region,
// else the window frame of plan-anywhere D4, so the vector frame and the CI oracle's frame are always the same. Every
// frame of the port is equirectangular (x = (lon − lon0)·kx east, z = −(lat − lat0)·kz south, kz the port's 110540),
// so a tile built in its own pack's frame maps into the frame drawn by an affine: x scaled by kx_drawn / kx_tile and
// moved, z moved.
//
// A change of the frame drawn (the active pack changes) is a uniform change, blended over 300 ms: geometry relative to
// the camera's target is scaled along x by the old frame's kx over the new one's at the change, easing to the new
// frame's own scale, so nothing jumps and the frame is exact once the blend ends.
#pragma once

#include "view/Camera.hpp"
#include "view/Ladder.hpp"

#include <optional>

namespace atlas::view {

/// How long a change of the frame drawn blends, seconds.
inline constexpr double frame_blend_seconds = 0.3;

/// A tile's place in the frame drawn: its origin (the tile's south-west corner at street level, in the frame drawn's
/// metres) and the frame's metres per tile metre along x.
struct Placement {
    Vec3 origin;
    double scale = 1;
};

/// A point of frame `from` in frame `to`: through degrees, kz as each frame has it.
[[nodiscard]] Vec3 convert(const GroundFrame& from, const GroundFrame& to, Vec3 p) noexcept;

/// The frame drawn, and its last change.
class Frames {
public:
    /// The frame drawn from `seconds` on (the frame's clock). The first frame set is drawn at once; a different one
    /// starts a blend from the one drawn.
    void set(const GroundFrame& frame, double seconds) noexcept;
    [[nodiscard]] const std::optional<GroundFrame>& current() const noexcept { return current_; }

    /// The blend's weight at `seconds`: 0 at the change, 1 from 300 ms on (and with no change).
    [[nodiscard]] double weight(double seconds) const noexcept;

    /// The place in the frame drawn of a tile of frame `from` whose south-west corner is `origin` (from's metres), at
    /// `seconds`, the camera's target at `target` (the frame drawn's metres).
    [[nodiscard]] Placement place(const GroundFrame& from, Vec3 origin, Vec3 target, double seconds) const noexcept;

private:
    std::optional<GroundFrame> current_;
    std::optional<GroundFrame> previous_;
    double changed_ = -1.0e9;
};

} // namespace atlas::view
