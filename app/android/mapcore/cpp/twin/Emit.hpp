// The vertex stage on the CPU, for the passes' twins (plan-vector T6): where the GPU draws a point, computed with the
// shaders' own code compiled as C++ (shaders/shared/camera.slang, by frame/FrameBlock.hpp), from the frame block's
// float camera, so a pass's emit (frame::Pass::emit) writes into the twin's stream (twin/Twin.hpp) the CSS px its
// vertex stage computes: a tile-local point placed relative to the eye (the tile's TilePlace, composed in double),
// projected in float, snapped to the SVG's print precision when the camera rests.
#pragma once

#include "frame/FrameBlock.hpp"
#include "twin/Twin.hpp"

#include <optional>

namespace atlas::twin {

/// Projects points as the frame's vertex stage does.
class Projector {
public:
    explicit Projector(const shaders::FrameCamera& camera) noexcept : camera_(camera) {}

    /// The camera coordinates (right, up, forward) of a tile-local point in the tile placed at `place`.
    [[nodiscard]] hlsl::float3 camera_of(const shaders::TilePlace& place, hlsl::float3 local) const noexcept {
        return shaders::camera_of(camera_, shaders::eye_relative(place, local));
    }
    /// Whether camera coordinates lie at or beyond the near plane (the vertex stage's clip distance is not negative).
    [[nodiscard]] bool ahead(hlsl::float3 k) const noexcept { return shaders::near_distance(camera_, k) >= 0.0F; }
    /// The CSS point the vertex stage draws camera coordinates `k` at (snapped when the camera rests).
    [[nodiscard]] Point css(hlsl::float3 k) const noexcept {
        const hlsl::float2 s = shaders::project_css(camera_, k);
        return {.x = s.x, .y = s.y};
    }
    /// The CSS point of a tile-local point, or nothing nearer than the near plane.
    [[nodiscard]] std::optional<Point> css(const shaders::TilePlace& place, hlsl::float3 local) const noexcept {
        const hlsl::float3 k = camera_of(place, local);
        if (!ahead(k)) return std::nullopt;
        return css(k);
    }

    [[nodiscard]] const shaders::FrameCamera& camera() const noexcept { return camera_; }
    /// The camera rests: points are snapped.
    [[nodiscard]] bool rests() const noexcept { return shaders::camera_rests(camera_); }

private:
    shaders::FrameCamera camera_;
};

} // namespace atlas::twin
