// What a press on the map points at (ENGINE.md step 6, taps on buildings): the building a ray from the eye through a
// pixel of the view meets first, read from the tiles the map draws, with its OpenStreetMap id. What's here and FillIn
// then name the building under the finger exactly, where the street point under it (a guess from the point's own
// outlines) may lie behind a tall front, or in the next building's.
//
// Each building and part the chunks of the packs drawn hold where the ray passes (their reach meets its track from
// where it is at the tallest building's height to where it meets the street) stands as its footprint's walls from its
// base to its height, lidded at its height (a pitched roof's eaves stand lower: a ray just over them may still meet the
// lid), its footprint the scene's ring at tolerance 0. An outline drawn by its parts is met through them, and named by
// its own id beside the part's. The nearest one met along the ray wins.
//
// What it met is told apart as geometry/Roofs.cpp draws the building (Colour the map's taps): its lid is its roof; a
// wall below the eaves is a wall, and above them its roof, but for a gabled roof's gable end within its triangle and a
// skillion's walls up to its slope, which stand in the walls' colour. A ray that passes over a pitched roof's ridge
// without meeting its slopes still names it.
#pragma once

#include "geometry/RoofShapes.hpp"
#include "tiles/Chunks.hpp"
#include "view/Camera.hpp"
#include "view/Ladder.hpp"

#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>

namespace atlas::tiles {
class Store; // tiles/Store.hpp
} // namespace atlas::tiles

namespace atlas::scene {

/// A building a pick met.
struct Picked {
    bool relation = false; ///< the building is mapped as a multipolygon (its otype), else as a way
    std::int64_t id = 0;   ///< the building: an outline, or the outline a part stands in
    std::int64_t part = 0; ///< the part met, where the ray met a part (0: the outline itself)
    double distance = 0.0; ///< metres along the ray from the eye
    double latitude = 0.0; ///< where the ray met it
    double longitude = 0.0;
    /// The face met as the building's geometry draws it (geometry/Roofs.cpp): its roof (a flat top, a slope, a pitched
    /// roof's face over its eaves), else a wall (a gable's triangle among them).
    bool roof = false;
    /// That face's slot word (geometry::Face::slot): the tiles' roof_slot or wall_slot, with a guessed flat top's mid
    /// grey above them (style::flat_roof_slot).
    std::uint32_t slot = 0;
    /// Its base colour, 0xRRGGBB before light: the tiles' roof_rgb or wall_rgb.
    std::uint32_t rgb = 0;
};

/// A pack a pick looks in: its id and the zoom of its chunks the map draws.
struct PickPack {
    std::string id;
    std::uint8_t z = 0;
};

/// The tallest building a pick looks for, metres: where the ray's track begins.
inline constexpr double pick_top = 350.0;

/// The building the ray from `eye` along `ray` (the frame metres of `ground`; `ray` need not be of unit length) meets
/// first among those of the chunks of `packs` in `store` whose reach meets the ray's track; none where the ray does not
/// fall to the street or meets none.
[[nodiscard]] std::optional<Picked> pick_building(const tiles::Store& store, std::span<const PickPack> packs,
                                                  const view::GroundFrame& ground, const view::Vec3& eye,
                                                  const view::Vec3& ray);

/// A building's roof as the tiles hold it, for in_walls_colour: its shape (`roof`), height (`roof_h`), along or across
/// its longest side (roof:orientation=across) and downhill direction (roof:direction).
struct PickRoof {
    std::string_view shape;
    double roof_h = 0.0;
    bool across = false;
    std::string_view direction;
};

/// Whether a building `top` tall over the footprint `poly` (metres: x east, z south), its roof `roof`, stands in its
/// walls' colour where a ray meets its wall at height `y`, `s` of the way along the footprint's side from vertex
/// `edge`, as geometry/Roofs.cpp draws it: below the eaves; above them, a gabled roof's gable end within its triangle
/// (a side whose ends meet the ridge as one point, as gable_roof finds it) and a skillion's wall below its slope.
/// Anything else above the eaves is the roof the ray goes on to meet.
[[nodiscard]] bool in_walls_colour(std::span<const geometry::V2> poly, std::size_t edge, double s, double y, double top,
                                   const PickRoof& roof);

/// The ray of the view's device point (`x`, `y`, device px): from the eye, along the camera's forward, right and up
/// as the point lies off the principal point (frame metres).
[[nodiscard]] view::Vec3 ray_of(const view::View& view, double x, double y) noexcept;

} // namespace atlas::scene
