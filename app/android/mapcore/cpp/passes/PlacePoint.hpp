// Where a place the app marks stands on a frame (ENGINE.md step 5's bend, its step 7's "Left"): a marker, a point of
// a board's label, the location's dot.
//
// Up to a camera 150 km from its target the place stands on the street as the packs' passes draw it
// (View::device_proj: the flat ground, dropped from 18 km), to the bit as before. From 150 km the world bends to the
// sphere (view/Bend.hpp's w₂), and the place stands where the world pass draws its ground (scene::name_point, the
// world's names' own), with nothing behind the horizon: on the flat ground a place 300 km from the target stood tens
// of pixels off its ground from 2,000 km, and one over the horizon stood in space.
#pragma once

#include "frame/FrameBuilder.hpp"
#include "scene/WorldNames.hpp"
#include "scene/WorldTiles.hpp"
#include "view/Camera.hpp"
#include "view/Ladder.hpp"

#include <optional>

namespace atlas::passes {

/// The frame a frame's places are in: the packs', or where no pack is drawn (the world alone, over a sea or from far)
/// the world's; null where neither is drawn.
[[nodiscard]] inline const view::GroundFrame* place_frame(const frame::FrameState& state) noexcept {
    if (state.ground_frame.has_value()) return &*state.ground_frame;
    return state.world != nullptr ? &state.world->frame : nullptr;
}

/// Whether the frame's places stand on the world's bent ground (the file's head): the world is drawn and its sphere's
/// weight is over 0.
[[nodiscard]] inline bool places_on_sphere(const frame::FrameState& state) noexcept {
    return state.world != nullptr && state.world->bend.sphere > 0.0;
}

/// Where the place (`lat`, `lon` degrees) stands on `view`'s device pixels, `ground` being place_frame's frame; or
/// nothing nearer than the near plane, or behind the sphere's horizon.
[[nodiscard]] inline std::optional<view::Vec2> place_point(const frame::FrameState& state, const view::View& view,
                                                           const view::GroundFrame& ground, double lat,
                                                           double lon) noexcept {
    if (places_on_sphere(state)) return scene::name_point(state.world->bend, state.world->frame, view, lat, lon);
    return view.device_proj({ground.x(lat, lon), 0.0, ground.z(lat, lon)});
}

} // namespace atlas::passes
