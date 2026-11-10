// The window's frame rate as the map moves (ENGINE.md gain 11): the frame loop tells the window its frames come at 120
// a second from the map's second new camera since the loop last rested, so that the display keeps 120 Hz through a
// fling's tail and the camera's animations as SurfaceFlinger's touch boost gives it under the finger, and at 60 once
// the loop has rested a second with the map still since. The second keeps pan, lift and pan again from switching the
// display at each rest: SurfaceFlinger switches at once, with no hold of its own, and each switch starts its vsync
// model again (the stutter review's checks, 3 November). One camera alone (a location fix, a change at rest), a tile or
// a fade moves nothing: it neither raises the rate nor holds it longer, so fixes a second apart keep no 120 at rest.
// Portable and pure, so the hosts test it; the frame loop (jni/Bridge.cpp) tells it each frame and each rest, and the
// render thread's timer when the second is up.
#pragma once

#include <cstdint>

namespace atlas::frame {

/// Whether the window's frames come at 120 a second (the map moves) or at 60 (at rest), and when they go back to 60.
class Motion {
public:
    /// How long the window keeps 120 after the loop rests with the map moved since it last rested.
    static constexpr std::int64_t hold_ns = 1'000'000'000;

    /// A frame was drawn, with a new camera from Kotlin or not. True where the map moves from this frame on (its
    /// second new camera since the loop last rested): the window is told 120, and the drop to 60 to come is off.
    [[nodiscard]] bool drew(bool camera) noexcept {
        if (!camera || cameras_ >= 2) return false;
        ++cameras_;
        if (cameras_ < 2) return false;
        moving_ = true;
        drop_ns_ = 0;
        return true;
    }

    /// The loop rests at `now_ns`. True where the map moved since it last rested: the window is told 60 at `drop_ns()`,
    /// unless the map moves again before then. Otherwise a drop to come stays where it was.
    [[nodiscard]] bool rested(std::int64_t now_ns) noexcept {
        const bool moved = cameras_ >= 2;
        cameras_ = 0;
        if (moved) drop_ns_ = now_ns + hold_ns;
        return moved;
    }

    /// When the window is to be told 60, ns; 0 where no drop is to come.
    [[nodiscard]] std::int64_t drop_ns() const noexcept { return drop_ns_; }

    /// The drop's time came (the render thread's timer): true where the window is to be told 60 now.
    [[nodiscard]] bool drop() noexcept {
        if (drop_ns_ == 0) return false;
        drop_ns_ = 0;
        moving_ = false;
        return true;
    }

    /// Whether the window was last told 120.
    [[nodiscard]] bool moving() const noexcept { return moving_; }

private:
    int cameras_ = 0;          // new cameras drawn since the loop last rested (from two the map moves)
    std::int64_t drop_ns_ = 0; // when the window goes back to 60 (0: no drop to come)
    bool moving_ = false;      // the window was last told 120
};

} // namespace atlas::frame
