// The buildings' records in the walls heap (plan-vector D5.7, K10): the layout the pass's stage T writes, its vertex
// stage reads (shaders/building/building.slangh) and its twin reads back, for one tile at one level. One range a tile:
//   - the primitives, 4 uint4 each (corners c0-c3 as floats relative to the tile's south-west corner, each with a word:
//     the face, the sun sample, the item and flags, the slots), the edged ones (a wall as one cell, a skillion's wall,
//     a houseboat's walls) then the plain ones (roof faces, n-gons' triangles);
//   - the near cells, two words each (two a uint4): the wall's primitive in the edged list, and its place on the wall
//     with the wall's columns and rows (building_cell_prim cuts the cell from its wall);
//   - the windowed walls, a uint4 each (shaders/building/windows.slangh's BuildingWindowWall: its wall's primitive in
//     the edged list with its style and flags, its first window, its rows and bays, its slots), by cell (a houseboat
//     cabin's walls among them);
//   - each face's geometry, 2 uint4 (its normal and area as floats, its test point and flags);
//   - each frame slot's colours, 2 words a face (lit, unlit, 0xBBGGRR), which the CPU's light writes per tile and
//     moment (its inputs stay on the CPU: BuildingTile::lights);
//   - each frame slot's reveals' colours, a word a windowed wall (its face's colour times 0.55, unlit), lit alike;
//   - each item's plinth's and cornice's painter slots (geometry::TileBuildings::item_facades), a word each;
//   - the items' rows in the item table, one word each, written once the tile is taken.
// Plain data: no Vulkan, so the twin and its tests read it as the GPU does.
#pragma once

#include "geometry/Walls.hpp"

#include <array>
#include <cstdint>
#include <span>
#include <vector>

namespace atlas::shaders {
struct Theme;
} // namespace atlas::shaders

namespace atlas::passes {

/// An n-gon the frame fills as triangles and the twin emits whole, as the port's face() gets it: its ring's points in
/// BuildingTile::ring_points, and its triangles among the plain primitives.
struct BuildingRing {
    std::uint32_t first = 0;  ///< its first point
    std::uint32_t points = 0; ///< how many
    std::uint32_t prim = 0;   ///< its first triangle in the plain list
    std::uint32_t prims = 0;  ///< how many
};

/// What lights a face (light.slang's light(): its base colour and its normal in double, the port's), and whether by day
/// alone (building.slangh's BUILDING_FACE_DAY).
struct BuildingLight {
    std::array<double, 3> normal{};
    std::uint32_t base = 0; ///< 0xRRGGBB
    std::uint32_t flags = 0;
};

/// What the buildings' stage T keeps of a tile (PassTile::cpu): where its records lie in its range of the walls heap
/// (uint4 indexes from the range's start), its counts, each cell's primitives and near cells, its faces' light inputs,
/// its n-gons, and its items' owners.
struct BuildingTile {
    std::uint32_t edged = 0;        ///< primitives drawn with their edge, from `prims_at`
    std::uint32_t plain = 0;        ///< then the plain ones
    std::uint32_t cells = 0;        ///< near cells, from `cells_at`
    std::uint32_t window_walls = 0; ///< windowed walls, from `windows_at`
    std::uint32_t windows = 0;      ///< their windows (the tile's window group of sun samples)
    std::uint32_t faces = 0;
    std::uint32_t items = 0;
    std::uint32_t prims_at = 0;   ///< the primitives, 4 uint4 each: the edged ones, then the plain
    std::uint32_t cells_at = 0;   ///< the near cells, two words each
    std::uint32_t windows_at = 0; ///< the windowed walls, a uint4 each
    std::uint32_t faces_at = 0;   ///< each face's geometry, 2 uint4
    std::uint32_t colours_at = 0; ///< each frame slot's colours: slot s from word colours_at · 4 + s · 2 · faces
    std::uint32_t reveals_at = 0; ///< each frame slot's reveals' colours: slot s from word reveals_at · 4 + s · walls
    std::uint32_t facades_at = 0; ///< each item's plinth's and cornice's slots, four a uint4
    std::uint32_t rows_at = 0;    ///< the items' rows, four a uint4
    std::uint32_t vectors = 0;    ///< the range's size in uint4
    std::uint8_t cells_per_side = 0;
    /// The tile's cell c's edged primitives: [edged_first[c], edged_first[c + 1]); its near cells, plain ones and
    /// windowed walls alike.
    std::vector<std::uint32_t> edged_first;
    std::vector<std::uint32_t> cells_first;
    std::vector<std::uint32_t> plain_first;
    std::vector<std::uint32_t> windows_first;
    /// The items whose walls have near cells in cell c, ascending: [near_items_first[c], near_items_first[c + 1]) of
    /// near_items. A near cell draws only for an item nearer than the cells' reach, so the frame draws a cell's near
    /// cells only where one of these is (Buildings::update).
    std::vector<std::uint32_t> near_items_first;
    std::vector<std::uint16_t> near_items;
    /// Each windowed wall's first window among the tile's by cell (its windows: [window_at[i], window_at[i + 1])).
    std::vector<std::uint32_t> window_at;
    /// Each windowed wall's item (its wall's), so the frame's jobs take only the walls whose windows it draws.
    std::vector<std::uint16_t> window_items;
    /// Each windowed wall's face (its wall's), whose light inputs its reveals' colour comes from.
    std::vector<std::uint32_t> window_faces;
    /// Each windowed wall's flags (geometry::window_flag), so the frame's jobs know its flat detail, and its style
    /// (geometry::WindowStyle: a houseboat cabin's windows have a reach of their own).
    std::vector<std::uint8_t> window_flags;
    std::vector<geometry::WindowStyle> window_styles;
    /// Each windowed wall's church windows (geometry::WindowWall's, each count at most 255, as the frame's jobs carry
    /// them): how many, bits 0-15, each one's transoms, 16-31; 0 where it has none.
    std::vector<std::uint32_t> window_churches;
    /// Each windowed wall's material face (geometry::WindowWall's), as the frame's jobs decide it each frame in double:
    /// its kind, its storeys, their height and its length, and a metal wall's ribs' colour.
    struct MaterialWall {
        geometry::Material kind = geometry::Material::none;
        std::uint16_t levels = 0;
        double fh = 0.0;
        double elen = 0.0;
        std::uint32_t ribs = 0;
    };
    std::vector<MaterialWall> window_materials;
    /// Each hoist beam under an old gable (geometry::RoofBeam's): its ends relative to the tile's corner and its
    /// gable's face's normal and test point (its turn), in the floats the heap holds of the primitives, its item and
    /// its slots, for the frame's beam records and the twin.
    struct Beam {
        std::uint32_t item = 0;
        std::array<float, 3> from{};
        std::array<float, 3> to{};
        std::array<float, 3> normal{};
        std::array<float, 3> test{};
        std::uint16_t near_slot = 0;
        std::uint16_t far_slot = 0;
    };
    std::vector<Beam> beams;
    /// Each slope's tile courses on a kit's roof (geometry::RoofCourses): its corners in frame metres (the port's
    /// doubles, from which each frame lays its courses as the port does) and the tile's corner, its slope's face's
    /// normal and test point in the heap's floats (its turn), its course count, step and colour, its item and slots.
    struct Courses {
        std::uint32_t item = 0;
        std::array<double, 3> pa{};
        std::array<double, 3> pb{};
        std::array<double, 3> qa{};
        std::array<double, 3> qe{};
        double origin_x = 0.0;
        double origin_z = 0.0;
        std::array<float, 3> normal{};
        std::array<float, 3> test{};
        std::uint32_t k = 0;
        double rstep = 0.0;
        std::uint32_t rgb = 0;
        std::uint16_t near_slot = 0;
        std::uint16_t far_slot = 0;
    };
    std::vector<Courses> courses;
    /// Each kit roof's lit ridge (geometry::RoofRidge): its ends in the heap's floats, its item and slots.
    struct Ridge {
        std::uint32_t item = 0;
        std::array<float, 3> from{};
        std::array<float, 3> to{};
        std::uint16_t near_slot = 0;
        std::uint16_t far_slot = 0;
    };
    std::vector<Ridge> ridges;
    /// Each item's plinth's and cornice's slots (geometry::TileBuildings::item_facades).
    std::vector<std::uint32_t> item_facades;
    /// Each face's light inputs (the CPU's light, per tile and moment, and the twin's).
    std::vector<BuildingLight> lights;
    /// Each face's item (its primitives' building), and whether it is a wall's (a cell, a gable, a skillion's,
    /// houseboat's or cabin's wall: in the wall's material, which a building asked about takes its flat colour on).
    std::vector<std::uint16_t> face_items;
    std::vector<std::uint8_t> face_walls;
    /// The n-gons, in their triangles' order, and their rings' points relative to the tile's corner (x, y, z each, the
    /// floats the heap holds of the triangles' corners).
    std::vector<BuildingRing> rings;
    std::vector<float> ring_points;
    /// Each item's building (its own OSM id, or a part's outline's where the part names one), and its owner: bit 0 a
    /// part (else a building), bit 1 that building is a relation (else a way).
    std::vector<std::int64_t> ids;
    std::vector<std::uint8_t> owners;
};

/// Every face's colours at the theme `theme`, lit and unlit (0xBBGGRR: light.slang's light() and tohex in binary64, the
/// port's bits), two words a face, into `out` (2 · lights.size() words).
void building_colours(const shaders::Theme& theme, std::span<const BuildingLight> lights, std::span<std::uint32_t> out);
/// Each windowed wall's reveals' colour at the theme `theme` (0xBBGGRR: its face's colour times 0.55, unlit,
/// light.slang's light() in binary64 as the port's), a word a wall of `faces` (BuildingTile::window_faces), into `out`.
void building_reveals(const shaders::Theme& theme, std::span<const BuildingLight> lights,
                      std::span<const std::uint32_t> faces, std::span<std::uint32_t> out);

/// The sash bars' least width (CSS px).
inline constexpr double sash_least = 0.7;

/// The width of a church's windows' leading drawn at depth `depth` (the port's d) by a camera of focal length `focal`
/// (CSS px, in double): max(0.4, 0.05 · focal / max(d, 1)), in hundredths of a CSS px as the port prints it ('%.2f').
[[nodiscard]] std::uint16_t church_leading(double depth, double focal);

/// What a wall's material face draws at depth `depth` (the port's d) by a camera of focal length `focal` (CSS px), at
/// night or by day, in double as render/amsterdam.py's material_face compares (ppm = focal / max(d, 1)): its fill
/// records (a glass wall's glass; a parking garage's decks where fh · ppm > 2.0), its line records (a glass wall's
/// floors where fh · ppm > 2.4, then its mullions; a metal wall's ribs by day; a stone front's balconies where fh · ppm
/// > 2.2), the jobs' word of them (how many floors, decks or balconies, bits 0-11; the mullions' or ribs' n, or the
/// balconies' width in hundredths of a CSS px as the port prints it, bits 12-31) and its ribs' colour (0xRRGGBB).
struct MaterialChoice {
    std::uint32_t fills = 0;
    std::uint32_t lines = 0;
    std::uint32_t counts = 0;
    std::uint32_t ribs = 0;
};
[[nodiscard]] MaterialChoice material_choice(const BuildingTile::MaterialWall& m, bool night, double depth,
                                             double focal);

/// The width of a hoist beam drawn at depth `depth` (the port's d) by a camera of focal length `focal` (CSS px, in
/// double): max(0.5, 0.12 · focal / d), in hundredths of a CSS px as the port prints it ('%.2f').
[[nodiscard]] std::uint32_t beam_width(double depth, double focal);

/// Whether a kit roof's slope at depth `depth` shows its tile courses of step `rstep` (trad_roof's
/// `ppm * rstep > 2.0`, ppm = focal / max(d, 1)).
[[nodiscard]] bool courses_shown(double depth, double focal, double rstep);

/// The width of a kit roof's tile courses, max(0.35, ppm · 0.03), and of its lit ridge, max(0.45, ppm · 0.08), in
/// hundredths of a CSS px as the port prints them.
[[nodiscard]] std::uint32_t courses_width(double depth, double focal);
[[nodiscard]] std::uint32_t ridge_width(double depth, double focal);

/// The ends of course `j` (1 to k - 1) of a slope's courses, in the heap's floats: at t = j / k from pa to qa and from
/// pb to qe, as trad_roof lays them (in frame metres, then relative to the tile's corner).
[[nodiscard]] std::array<std::array<float, 3>, 2> course_ends(const BuildingTile::Courses& c, std::uint32_t j);

/// A wall's windows' frames' width and their sash bars' (0: none), in tenths of a CSS px as the port prints them.
struct WindowWidths {
    std::uint16_t frame = 0;
    std::uint16_t sash = 0;
};

/// The widths of the windows of an item drawn with detail `detail` (its rank's) at depth `depth` (Ranks::depth, the
/// port's d) by a camera of focal length `focal` (CSS px, in double), with weather or not, the frames' own width being
/// `frame_width` (the style's): deep, the frames max(frame_width, 0.07 · focal / max(d, 1)), else frame_width; nearer
/// than 140 m the sash bars max(0.7, 0.045 · focal / max(d, 1)) deep, else max(0.7, 9 / max(d, 1)); each to a tenth.
[[nodiscard]] WindowWidths window_widths(std::uint32_t detail, bool weather, double depth, double focal,
                                         double frame_width);

/// The uint4 words of a tile's buildings (BuildingTile's layout, `layout` filled), from stage T's geometry: the
/// primitives' corners relative to the tile's south-west corner (`origin_x`, `origin_z`, frame metres) as floats.
[[nodiscard]] std::vector<std::array<std::uint32_t, 4>> heap_words(const geometry::TileBuildings& g, double origin_x,
                                                                   double origin_z, BuildingTile& layout);

/// A run of primitives a draw covers: [first, end) of a tile's list.
struct BuildingRun {
    std::uint32_t first = 0;
    std::uint32_t end = 0;
};

/// A tile's visible cells (bit c for cell c) as runs of the list whose cell c holds [first[c], first[c + 1]), runs
/// with few primitives between them joined, appended to `out`.
void building_runs(std::uint64_t cells, std::span<const std::uint32_t> first, std::vector<BuildingRun>& out);

} // namespace atlas::passes
