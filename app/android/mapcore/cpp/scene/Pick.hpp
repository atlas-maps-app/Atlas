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
#pragma once

#include "tiles/Chunks.hpp"
#include "view/Camera.hpp"
#include "view/Ladder.hpp"

#include <cstdint>
#include <optional>
#include <span>
#include <string>

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

/// The ray of the view's device point (`x`, `y`, device px): from the eye, along the camera's forward, right and up
/// as the point lies off the principal point (frame metres).
[[nodiscard]] view::Vec3 ray_of(const view::View& view, double x, double y) noexcept;

} // namespace atlas::scene
