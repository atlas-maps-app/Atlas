// The living map's load (ENGINE.md step 3, stage P): render/amsterdam.py's `load(raw_path)` as the port computes it
// (R/Amsterdam.kt's load, the way LiveEngine loads the living map: from a raw.json, so neither the looks nor the kit),
// with guess_tags after it, the places' styles and buildings, and the outlines their parts stand for. Every record and
// field is the port's, in its order and to the bit (:mapcorecheck's AmsterdamParityTest holds them over the regions'
// raw.json).
//
// What the renders cannot load raises in Python and throws in the port, so the render stops. The core loads on: the
// element a value of it cannot serve is left out, or the value is taken as missing, each named here.
//   - A canal's name on a way without a geometry (Python's KeyError), a road with an empty geometry (IndexError) and a
//     node without a position (KeyError) are left out.
//   - building:levels without a leading number, or one whose int() fails (int(None), or past what an int of 64 bits
//     holds, where the port's longValueExact throws), counts the levels from the height, as where it is not mapped.
//   - Levels from a height whose round() fails (an infinite height) are 1.
// A position off the Earth (a latitude past ±90°, a longitude past ±180°, NaN) refuses the document: Overpass never
// writes one.
#pragma once

#include "load/Scene.hpp"
#include "osm/Overpass.hpp"
#include "tiles/Sections.hpp"

#include <expected>
#include <span>
#include <string_view>

namespace atlas::load {

/// A point of the frame: amsterdam.py's `XZ(lat, lon)`, metres east and south of the frame's origin.
[[nodiscard]] constexpr V2 xz(const tiles::Frame& frame, double lat, double lon) noexcept {
    return {(lon - frame.lon0) * frame.kx, -(lat - frame.lat0) * frame.kz};
}

/// The living map's frame, amsterdam.py's: the Westerkerk at (52.3746, 4.8840), kx = 111320 · cos(52.3746°).
[[nodiscard]] tiles::Frame westerkerk_frame() noexcept;

/// The colours guess_tags picks from for a building without one (`BRICK_OLD`, `PLASTER`, `BRICK_NEW`; regions set
/// their own).
struct Palette {
    std::span<const std::string_view> brick_old;
    std::span<const std::string_view> plaster;
    std::span<const std::string_view> brick_new;
};

/// Amsterdam's palette.
[[nodiscard]] Palette amsterdam_palette() noexcept;

/// `load(raw)`: the scene of a document in `frame`, with `palette` for the guessed colours. guess_tags's values come
/// with each record (each building draws from its own random stream, seeded by its id, so the order is free).
[[nodiscard]] std::expected<Scene, LoadError> load_amsterdam(const osm::Document& doc, const tiles::Frame& frame,
                                                             const Palette& palette);

} // namespace atlas::load
