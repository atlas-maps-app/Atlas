// The world's look of a frame (ENGINE.md step 5, "The style ladder" and "The globe"; its step 8): the ladder's weights
// at the camera's distance, the sun and the planet's axes in the frame's axes, and the world's style's values
// (tiles/WorldLadder.hpp) as the world pass's shaders read them (shaders/shared/globe.slang's WorldLookBlock).
//
// The weights are smoothsteps of log D between the ladder's knots, as the bend's are (view/Bend.hpp), so the look
// changes without a kink in a zoom. Up to the first knot (18 km) every weight is 0 and the world's colours are its
// palette's.
//
// The sun is the moment's: the theme block's azimuth and elevation are the sun's as it stands over the bundled packs'
// place (CoreMoment writes every moment's there: Regions.resetPlace's Westerkerk), so the point of the planet the sun
// is overhead of follows from them, and with it the sun's altitude anywhere: the true terminator. The live moment's is
// the real sun's of its instant; a named moment's (the map's day and night styles) that of its own date and hour.
#pragma once

#include "frame/FrameBlock.hpp"
#include "scene/WorldBend.hpp"
#include "style/Theme.hpp"
#include "tiles/WorldLadder.hpp"
#include "view/Camera.hpp"

namespace atlas::shaders {

#include "shaders/shared/globe.slang"

static_assert(sizeof(WorldLookBlock) == 608, "globe.slang's WorldLookBlock: thirty-eight four-lane vectors");

} // namespace atlas::shaders

namespace atlas::scene {

/// A weight of the ladder: 0 up to a camera `from` metres from its target, 1 from `to`, smoothstep of log D between.
[[nodiscard]] double ladder_weight(double distance, double from, double to) noexcept;

/// The ladder's weights for a camera `distance` metres from its target.
struct LadderWeights {
    double caps = 0;  ///< the night's hand-over to the day's palette under the caps, and the lights (18 to 75 km)
    double ramps = 0; ///< the board's ramps (600 to 6,000 km)
    double ring = 0;  ///< the graticule, the ring, the outline, the rim and the shine (2,000 to 6,000 km)
    double space = 0; ///< space where a pitched view's sky was (75 to 2,000 km)
    double haze = 0;  ///< how much of the haze is gone (600 to 2,000 km)
};
[[nodiscard]] LadderWeights ladder_weights(const tiles::WorldLadder& ladder, double distance) noexcept;

/// A point of the planet, degrees.
struct GeoPoint {
    double lat = 0;
    double lon = 0;
};

/// The point the sun is overhead of when it stands at `azimuth` (from north, clockwise) and `elevation` degrees over
/// the place (`lat`, `lon`): the theme block's place, where its sun is seen from.
[[nodiscard]] GeoPoint sub_solar(double azimuth, double elevation, double lat, double lon) noexcept;

/// The unit vector from the planet's centre through (`lat`, `lon`), in the axes of a frame whose camera's target lies
/// at `bend`'s (x east, y up, z south there).
[[nodiscard]] view::Vec3 direction_of(const WorldBend& bend, double lat, double lon) noexcept;

/// A frame's look.
struct WorldLook {
    LadderWeights weights;
    GeoPoint sun; ///< where the sun is overhead
    /// Whether the moment is night: the world's palette is the night's, handed over to the day's under the caps.
    bool night = false;
    /// Whether the ladder leaves every fragment of the frame its moment's colour: by day, under the ramps' first knot,
    /// with the sun up over all the ground the eye can see (its altitude under the eye less the horizon's angle, over
    /// the caps' softness). The block's first weight is then 0, so no fragment does the look's work, and no light is
    /// drawn (none shows by day); the pixels are the same.
    bool plain = false;
    shaders::WorldLookBlock block{};
};

/// The look of a frame drawn with `ladder` at a camera `distance` metres from its target, in the light of `theme`, on
/// `bend`'s ground, its eye at `eye` (frame metres) on a device of `density` px a dp and `width` by `height` px.
[[nodiscard]] WorldLook world_look(const tiles::WorldLadder& ladder, double distance, const style::ThemeBlock& theme,
                                   const WorldBend& bend, const view::Vec3& eye, double density, double width,
                                   double height) noexcept;

} // namespace atlas::scene
