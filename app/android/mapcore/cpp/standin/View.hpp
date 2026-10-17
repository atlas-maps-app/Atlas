// The stand-in's camera (plan-vector K1): the gesture camera of a frame (CameraState: target, bearing, the map's pitch,
// distance, principal point, size and density) as the engine's camera frames it (D4: a 36° field of view over the
// frame's height, the principal point where MapCamera puts it, the eye `distance` metres from the target), turned into
// the frame's uniforms: the view-projection, the water's mirrored camera, the sun, the haze's 17 stops and the
// vignette. Not held to the port: K7 computes the real camera with pymath and ViewLadder.
#pragma once

#include "gen/layouts.hpp"
#include "standin/City.hpp"
#include "style/CameraState.hpp"

#include <cstdint>

namespace atlas::standin {

using FrameUniforms = layouts::standin_standin::Frame;

/// The stand-in's origin: the Westerkerk (the camera's latitude and longitude are relative to it).
inline constexpr double origin_latitude = 52.3746;
inline constexpr double origin_longitude = 4.8840;

/// The camera's eye and view direction in the city's metres (x east, y up, z south).
struct ViewBasis {
    double eye[3];
    double forward[3];
    double right[3];
    double down[3];
    double target[3];
};

/// The basis of a camera state.
[[nodiscard]] ViewBasis basis_of(const style::CameraState& camera) noexcept;

/// The frame's uniforms for `camera`, drawn into an output of `width` × `height` pixels rotated by the surface's
/// pre-rotation `quarter_turns` (0-3), `seconds` after the bench began.
[[nodiscard]] FrameUniforms uniforms_of(const style::CameraState& camera, const City& city, std::uint32_t width,
                                        std::uint32_t height, std::uint32_t quarter_turns, double seconds) noexcept;

/// A camera state at the Main board's view of the stand-in (2.3 km at map pitch 46°) or at the map's steepest pitch
/// (70°), for a frame of `width` × `height` pixels at `density`.
[[nodiscard]] style::CameraState board_view(std::uint32_t width, std::uint32_t height, double density,
                                            double pitch = 46.0, double bearing = 18.0,
                                            double distance = 2300.0) noexcept;

} // namespace atlas::standin
