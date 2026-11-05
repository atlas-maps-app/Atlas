// The bend (ENGINE.md step 5, "The bend"; its step 7): beyond a camera 18 km from its target the ground curves away as
// the planet does, so that from 600 km on the world is the sphere.
//
//   - Up to a camera of 18 km nothing moves, bit for bit: the drop is 0 and the frame's camera block is as before.
//   - Beyond, every point of every tile drops by w₁·|Δ|²/2R relative to the eye (camera.slang's bent: Δ its offset
//     from the camera's target along the ground, R 6,371 km), w₁ easing in log D from 0 at 18 km to 1 at 150 km.
//   - The world pass also blends each point to its place on the sphere tangent to the ground at the target, by w₂,
//     from 0 at 150 km to 1 at 600 km (world.slang's on_sphere, scene/WorldBend.hpp's mirror).
//
// Both weights are smoothstep's of log D between their knots, so the bend starts and ends without a kink in motion.
#pragma once

namespace atlas::view {

/// The planet's radius the bend curves the ground to, metres.
inline constexpr double earth_radius = 6'371'000.0;
/// The camera distance (from its target) up to which nothing bends, metres: the world's start (scene::world_from).
inline constexpr double bend_from = 18'000.0;
/// The distance from which the drop is whole (w₁ = 1), and the sphere's blend begins.
inline constexpr double bend_dropped = 150'000.0;
/// The distance from which the world is the sphere (w₂ = 1).
inline constexpr double bend_sphere = 600'000.0;

/// The drop's weight w₁ for a camera `distance` metres from its target: 0 up to 18 km, 1 from 150 km.
[[nodiscard]] double drop_weight(double distance) noexcept;
/// The sphere's weight w₂: 0 up to 150 km, 1 from 600 km.
[[nodiscard]] double sphere_weight(double distance) noexcept;
/// The drop's factor, w₁/2R: metres down per square metre of a point's distance from the target (0 up to 18 km).
[[nodiscard]] double drop_of(double distance) noexcept;

} // namespace atlas::view
