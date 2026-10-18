// The screen layers (plan-vector D4, D18; R/live/ScreenLayers.kt, render/amsterdam.py:615 render): what a render draws
// fixed to the screen rather than on the ground, as numbers, from the camera, the moment's theme and the camera's
// settings alone: the horizon, the sky layer's clouds and sun glow, the haze by ground depth in 17 stops (or at eye
// level the 25-stop band on the horizon), the golden wash, wfx's cloud shadows and rain, and fin (the grain and the
// vignette). The core computes them again every frame, bit-equal to the port's ScreenLayers.of
// (MT/frame/LadderParityTest.kt), and the composite draws the haze and the vignette from them (block(): the values
// rounded to the precision the SVG prints them, as the oracle reads them).
//
// The port's arithmetic in its order: pymath's pow (Python's), Python's min, max and int(), the camera's ground_depth
// and horizon_y, contraction off.
#pragma once

#include "frame/FrameBlock.hpp"
#include "style/Theme.hpp"
#include "view/Camera.hpp"

#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <optional>

namespace atlas::view {

class Placed; // view/Ladder.hpp

/// ScreenLayers' constants.
inline constexpr double haze0_default = 380.0;
inline constexpr double haze1_default = 800.0;
inline constexpr double haze_power = 1.3;
inline constexpr double sky_rise = 300.0;
inline constexpr double sun_margin = 260.0;
inline constexpr double sun_below_horizon = 60.0;

/// The sky layer's clouds (`cloudf`): colour (red, green, blue), alpha k · (n − t).
struct Clouds {
    double k = 0;
    double t = 0;
    double red = 0;
    double green = 0;
    double blue = 0;
};

/// The haze: the gradient of the theme's haze colour from row y0 to row y1, its stops' offsets and opacities.
struct Haze {
    bool band = false; ///< the eye-level band (25 stops), else 17 stops down from the horizon or the frame's top
    double y0 = 0;
    double y1 = 0;
    std::uint32_t count = 0;
    std::array<double, 25> offsets{};
    std::array<double, 25> alphas{};
};

/// The settings of a camera that the screen layers read (cfg's W, H, pitch, haze0, haze1, target's y, no_fin).
struct LayerSettings {
    double width = 0;
    double height = 0;
    double pitch = 90.0; ///< cfg's pitch where it has one (the engine's), else 90
    double haze0 = haze0_default;
    double haze1 = haze1_default;
    double ground_y = 0; ///< the target's height (the ground's under it)
    bool no_fin = false;
};

/// The settings of a placed camera (ViewLadder.place's cfg).
[[nodiscard]] LayerSettings settings_of(const Placed& cfg) noexcept;

/// The numbers of one frame's screen layers (ScreenLayers.Frame).
struct Layers {
    double width = 0;
    double height = 0;
    double horizon = 0; ///< the horizon's row (Camera.horizon_y)
    bool weather = false;
    bool night = false;
    bool overcast = false;
    bool eye = false;
    bool front = false; ///< the render's layer is "front" (the map never draws it)
    std::optional<Clouds> clouds;
    double sky_bottom = 0;
    std::optional<Vec2> sun;
    Haze haze;
    bool wash = false;
    double cloud_shadow_cut = std::numeric_limits<double>::quiet_NaN(); ///< NaN: no cloud shadows
    std::optional<std::int64_t> rain_angle;
    bool draws_fin = false;

    [[nodiscard]] bool cloud_shadows() const noexcept { return !std::isnan(cloud_shadow_cut); }
    /// fin's vignette opacity at the corners, as the render prints it (.38 at night, .2 by day).
    [[nodiscard]] double vignette_opacity() const noexcept { return night ? 0.38 : 0.2; }
};

/// ScreenLayers.of: the layers of a frame with camera `cam`, the moment's theme block `th`, the camera's settings `s`;
/// `front` for a render's "front" layer (never the map's).
[[nodiscard]] Layers layers_of(const Camera& cam, const style::ThemeBlock& th, const LayerSettings& s,
                               bool front = false);

/// ScreenLayers.haze: the haze over a frame `height` px tall with its horizon at row hy.
[[nodiscard]] Haze haze_of(const Camera& cam, double haze_max, double haze0, double haze1, double ground_y, bool eye,
                           double hy, double height);

/// ScreenLayers.sunPoint: the sun's point 4 km out along the sun vector from the eye, when its glow reaches the frame.
[[nodiscard]] std::optional<Vec2> sun_point(const Camera& cam, const std::array<double, 3>& sun, double width,
                                            double hy);

/// The composite's layers (composite.slang's FrameLayers) for the frame: the haze's rows, stops and opacities and the
/// vignette's opacity rounded as the SVG prints them (the stops' rows at %.1f, or the frame's height whole; offsets and
/// opacities at %.3f), the haze colour's levels, and which steps run: `steps` (composite.slang's COMPOSITE_ bits) of
/// those the layers draw (the vignette where fin draws), at the output's `levels` (255, 1023).
[[nodiscard]] shaders::FrameLayers block_of(const Layers& layers, const style::ThemeBlock& th, std::uint32_t steps,
                                            std::uint32_t levels) noexcept;

} // namespace atlas::view
