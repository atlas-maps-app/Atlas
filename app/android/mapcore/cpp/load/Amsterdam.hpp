// The living map's load (ENGINE.md step 3, stage P): render/amsterdam.py's `load(raw_path)` as the port computed it
// (R/Amsterdam.kt's load, the way LiveEngine loads the living map: from a raw.json, so neither the looks nor the kit,
// which a caller may ask for), with guess_tags after it, the places' styles and buildings; then the buildings cleaned
// (load/Clean.hpp). Since 16 Nov 2026 it draws nothing that cannot be there (the user: "fix things that cant be
// there"), where it had followed the old renderer:
//   - a relation's outer members are joined into rings and its inner members are its courtyards; an outer member that
//     is a building's or part's own way loads once, as itself, with the courtyards that lie in it;
//   - what is below the ground (location=underground, or a layer below 0 unless its location puts it on the ground or
//     above) is not loaded;
//   - a building's numbers past what a building can be (building_bounds) are taken as unmapped, and a base at or over
//     the top as none;
//   - an outline is drawn less its parts, not hidden whole where one part's centroid lay in it; overlapping footprints
//     are clipped.
//
// What the renders could not load raised in Python and threw in the port, so the render stopped. The core loads on:
// the element a value of it cannot serve is left out, or the value is taken as missing, each named here.
//   - A canal's name on a way without a geometry (Python's KeyError), a road with an empty geometry (IndexError) and a
//     node without a position (KeyError) are left out.
//   - building:levels without a leading number, or past the bounds, counts the levels from the height, as where it is
//     not mapped.
//   - Levels from a height whose round() fails are 1.
// A position off the Earth (a latitude past ±90°, a longitude past ±180°, NaN) refuses the document: Overpass never
// writes one.
#pragma once

#include "load/Generic.hpp"
#include "load/Scene.hpp"
#include "osm/Overpass.hpp"
#include "tiles/Sections.hpp"

#include <array>
#include <cstdint>
#include <expected>
#include <optional>
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

/// guess_tags for one building or part (`h0` its height as loaded, None where nothing gave one): the generic values for
/// its missing tags, recorded as guessed, from its own random stream (random.Random(id)).
void guess_tags(Building& b, std::optional<double> h0, const Palette& palette);

/// A bound on a building's number, as its tag gives it: from `lo` (`lo_open`: above it) to `hi`, in its tag's unit.
struct BuildingBound {
    std::string_view key;
    double lo = 0.0;
    double hi = 0.0;
    bool lo_open = false;
};

/// What a building can be, where its numbers enter: the tallest building stands 828 m (one of 1,008 m is being built)
/// and the most storeys built are 163, so a height past 1,200 m or more than 200 storeys is a mistake, and so is a
/// number below 0. A height of 0 stays what it was, a building of no height, which nothing draws.
inline constexpr std::array<BuildingBound, 5> building_bounds = {{
    {.key = "height", .lo = 0.0, .hi = 1200.0, .lo_open = false},
    {.key = "min_height", .lo = 0.0, .hi = 1200.0, .lo_open = false},
    {.key = "building:levels", .lo = 0.0, .hi = 200.0, .lo_open = false},
    {.key = "roof:levels", .lo = 0.0, .hi = 100.0, .lo_open = false},
    {.key = "roof:height", .lo = 0.0, .hi = 1200.0, .lo_open = false},
}};

/// The most storeys a building holds (building_bounds' building:levels).
inline constexpr std::int64_t max_levels = 200;

/// A building's number `key` (one of building_bounds', or another, unbounded): num() of its tag, or none where that
/// lies past the bounds, as if unmapped.
[[nodiscard]] std::optional<double> building_num(const Tags& t, std::string_view key) noexcept;

/// Whether one of a building's numbers lies past building_bounds.
[[nodiscard]] bool beyond_bounds(const Tags& t) noexcept;

/// `_bb`: an outline's box, with Python's min and max.
[[nodiscard]] Box box_of(std::span<const V2> poly) noexcept;

/// `load(raw)`: the scene of a document in `frame`, with `palette` for the guessed colours, its buildings cleaned.
/// guess_tags's values come with each record (each building draws from its own random stream, seeded by its id, so the
/// order is free). With `looks`, generic.py's looks after guess_tags, as amsterdam.py applies them to its own data:
/// Amsterdam's New looks (ATLAS_LOOKS: `keep_homes`) or its tradition and kit (ATLAS_KIT: nl_amsterdam), the land use
/// from the areas.
[[nodiscard]] std::expected<Scene, LoadError> load_amsterdam(const osm::Document& doc, const tiles::Frame& frame,
                                                             const Palette& palette, const Looks* looks = nullptr);

/// load_amsterdam before its buildings are cleaned: a region's load (load/Regions.hpp) finds each part's outline in
/// the outlines as mapped, then cleans them.
[[nodiscard]] std::expected<Scene, LoadError> load_living(const osm::Document& doc, const tiles::Frame& frame,
                                                          const Palette& palette, const Looks* looks = nullptr);

/// Counts the roofs with a shape, as drawn (after the closest shapes and the looks: mapped or a tradition's or the
/// guesses'), that stage T draws flat (Scene::counts' flat_roofs): a shape it does not draw, no height, or over a
/// piece of the ring that has a courtyard (geometry::drawn_pitched; a ring with seams is roofed piece by piece); of
/// them those flat only over such a piece (flat_by_seam); and the rings with seams whose roof stands on a piece by
/// itself (roofed_by_piece: a ring may be in both).
void count_flat_roofs(Scene& s);

} // namespace atlas::load
