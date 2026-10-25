// coasters.draw's walk along a theme park's track (render/coasters.py, R/Coasters.kt), shared by the passes that follow
// it: passes/Items.cpp draws the pieces it draws and the posts it puts, passes/Ground.cpp the posts' shadows. Fed a
// track's pieces in their order, in double on the render thread, it says of each whether coasters.draw draws it
// (nearer than 3 m, an end behind the near plane, both ends 40 px beyond the picture: not) and whether it puts a post
// at its end: where the length drawn since the last post reaches the gap (a wooden coaster's 2.6 m, else 7.5) and the
// piece's end stands over 2.2 m, the length starting again there; the post stands where its foot projects.
#pragma once

#include "view/Camera.hpp"

namespace atlas::passes {

class CoasterWalk {
public:
    /// What the walk says of a piece: drawn, a post at its end, and s = focal / the depth of its middle.
    struct Step {
        bool drawn = false;
        bool post = false;
        double s = 0.0;
    };

    /// A track begins: the length since the last post starts again.
    void start() noexcept { drawn_since_ = 0.0; }

    /// The piece from a to b (the frame's metres) at heights ya and yb, `length` long in the scene's own metres
    /// (Amsterdam.dist of its ends), of a wooden coaster or not.
    [[nodiscard]] Step step(const view::Camera& camera, const view::Vec3& a, const view::Vec3& b, double ya, double yb,
                            double length, bool wooden) noexcept {
        Step out;
        const view::Vec3 c = camera.cam({(a.x + b.x) / 2, (ya + yb) / 2, (a.z + b.z) / 2});
        if (c.z < 3) return out;
        const auto pa = camera.proj({a.x, ya, a.z});
        const auto pb = camera.proj({b.x, yb, b.z});
        if (!pa || !pb || (!in_picture(camera, *pa) && !in_picture(camera, *pb))) return out;
        out.drawn = true;
        out.s = camera.focal() / c.z;
        drawn_since_ += length;
        if (drawn_since_ >= (wooden ? 2.6 : 7.5) && yb > 2.2) {
            drawn_since_ = 0.0;
            out.post = camera.proj({b.x, 0.0, b.z}).has_value();
        }
        return out;
    }

private:
    /// Within 40 px of the picture: `-40 < q[0] < cam.W + 40 and -40 < q[1] < cam.H + 40`.
    [[nodiscard]] static bool in_picture(const view::Camera& camera, const view::Vec2& q) noexcept {
        return -40 < q.x && q.x < camera.width() + 40 && -40 < q.y && q.y < camera.height() + 40;
    }

    double drawn_since_ = 0.0;
};

} // namespace atlas::passes
