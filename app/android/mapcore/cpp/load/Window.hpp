// A window of the map the phone builds itself (ENGINE.md step 3): one L14 tile, built from OpenStreetMap's data of its
// box grown by a margin, loaded and cut as a region of the renders (load/Regions.hpp; load/Cut.hpp's cut_window) and
// written as a small pack of its own, which the store opens like any other (tiles::PackKind::window):
//   - its data: what the renders' query of Overpass returns for window_box (every tagged node, every way, the
//     multipolygons and parks, `out geom`), with each relation's members whole, so what stands on the window's edge and
//     what its derivations look across are there: a building's mapped neighbours (80 m), the parts in an outline
//     (120 m), a quay within 16 m and the buildings lining it within 40 m, the land use under it;
//   - its frame: its frame cell's, the L10 tile holding it (frame_of), so neighbouring windows agree on their metres
//     (an area's size, a neighbour's distance) and the world draws them in one frame;
//   - its place: its country's (load/Countries.hpp, at the window's centre), as the port's RegionConfig picks it for a
//     region it builds: the building tradition, the time zone; Amsterdam's palette;
//   - its tile: the features whose reference point lies in it (a building on its edge is built once, by the window of
//     its reference point) and the water clipped at its edges, with keep codes and the variants of the live map's
//     bands, in the engine's close-up cut (quay walls where buildings line the water, the doors and house numbers on
//     the facades); what the tiles have no columns for yet is left out (cut_window): a tradition's details until its
//     facades;
//   - its pack: that tile, a head (profile region, level 14, the frame, the window's name, the core's stage P version,
//     the style version, its data's SHA-256, no views, the live map's bands) and its style (load/PackStyle.hpp).
// A window builds within about 50 MB on a mid-range phone in a few seconds (ENGINE.md's budget), on a worker.
#pragma once

#include "load/Coasters.hpp"
#include "load/Scene.hpp"
#include "load/Tradition.hpp"
#include "load/Woods.hpp"
#include "osm/Overpass.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Chunks.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Sections.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <expected>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::load {

/// A window's level, its frame cell's, and the margin its data reaches beyond its tile (metres).
inline constexpr std::uint8_t window_level = 14;
inline constexpr std::uint8_t frame_cell_level = 10;
inline constexpr double window_margin = 200.0;

/// The engine version a window's head names: the core's stage P, bumped when a window's tiles change for the same
/// data (the port's packs name EngineVersion.ID there): core-p2 carries gantries and conveyors, which core-p1 left out;
/// core-p3 a crane's form; core-p4 the New looks in the Netherlands; core-p5 a tradition's kit and its details (the
/// building flags, the shutters' colour and the party walls), which core-p4 left out; core-p6 the street wall each door
/// and shopfront opens onto (the doors and their house numbers), which core-p5 left out; core-p7 the New looks' red
/// tiles on about one canal house in four (the canal ring pack's tiles_red), which core-p6 drew all dark; core-p8 the
/// quay walls the buildings line close up (RegionConfig::close_up), where core-p7 gave the water natural shores;
/// core-p9 the doors and house numbers on the facades close up, which core-p8 left out; core-p10 the woods planted
/// about a zoo or a theme park (RegionConfig::zoo_woods), as the renders plant a zoo's and a park's region, which
/// core-p9 left as outlines; core-p11 the New looks' bitumen and gravel on about half and one in ten of the homes' flat
/// roofs whose colour is guessed, which core-p10 drew all mid grey; core-p12 the New looks' red tiles a step warmer
/// (red_tiles_colour), which core-p11 drew in tiles_red's, a flat region's lawns drawn once among its land cover under
/// the woods and the water (load/Regions.cpp's lawn_in_land), which core-p11 drew again as areas over them, and each
/// frame cell's levels beside its windows (load/Levels.hpp: its L13 and L12 tiles, which the core draws beyond the
/// windows' reach), where core-p11's country tiles held the windows alone and today's map drew beyond 9 km; core-p13
/// each building's wall and roof slots (wall_slot and roof_slot: the user's palette's slots their colours go with),
/// which core-p12's tiles lack, so their buildings keep today's colours whatever the palette; core-p14 the buildings
/// that can be there (load/Clean.hpp): courtyards cut out, an outline drawn less its parts, overlapping footprints
/// clipped, what is underground and a relation's ring that is a building's own way left out, and a building's numbers
/// bounded, where core-p13 roofed the courtyards, hid a whole outline for one part and drew the rest as mapped.
inline constexpr std::string_view stage_p_version = "core-p14";

/// Whether a window of `country` draws the renders' New looks instead of its tradition's kit, and every pack there
/// lights with TONE (frame/MapFrame.hpp's with_pack): the Netherlands, as the bundled Westerkerk draws.
[[nodiscard]] constexpr bool draws_new_looks(std::optional<std::string_view> country) noexcept {
    return country.has_value() && *country == "NL";
}

/// The version of a window built now: `<stage P's> <the style's>`, its manifest's engine_version and style_version. A
/// window of another version (an earlier APK built it) is not drawn: the app's PackList does not list it, so a frame
/// cell's windows are of one version.
[[nodiscard]] std::string window_version();

/// The box whose data a window needs, [south, west, north, east] degrees: its tile's box grown by `margin` metres
/// (along the parallels at the box's own latitudes, so the margin is at least `margin` everywhere).
[[nodiscard]] std::array<double, 4> window_box(const tiles::TileKey& tile, double margin = window_margin) noexcept;

/// The frame of a window's frame cell, the L10 tile holding it: its origin the cell's centre on the grid, rounded to
/// four decimals as the port rounds a built region's origin; kx = 111320 · cos(lat0) and kz = 110540, regions.py's.
[[nodiscard]] tiles::Frame frame_of(const tiles::TileKey& tile) noexcept;

/// Where a window is built: what its load takes and its pack says.
struct WindowPlace {
    std::string name;   ///< the pack's region: `14/<x>/<y>`, or the renders' region a test builds it in
    tiles::Frame frame; ///< frame_of's, or the region's
    /// The renders' region whose rules it is loaded by: its palette, quays, shores and module state (region_palette,
    /// region_state); `window` anywhere, as the port's region of its own.
    std::string region;
    std::optional<std::string_view> country;
    std::optional<std::string_view> tradition; ///< render/traditions/<name>.json, compiled into the core
    /// Amsterdam's own looks, from each building's tags with the canal houses' brick and plaster kept (the renders' New
    /// looks, ATLAS_LOOKS), in place of a tradition's kit: a window in the Netherlands, as the bundled Westerkerk is
    /// drawn.
    bool new_looks = false;
    /// The engine's close-up cut (RegionConfig::close_up): the phone's windows anywhere; a test's window of a region
    /// cut by the renders' rules, as its render pack is, has it not.
    bool close_up = false;
    std::string_view zone = "UTC";
    std::int32_t tz_h = 0;
    /// A theme park's rides (its coaster tracks load) and whether it plants its woods, as a region with a park or a zoo
    /// does: a test's window of a park; the phone's windows have neither, and plant the woods of the zoos and theme
    /// parks in their data instead ([zoo_woods]).
    const RideProfiles* park = nullptr;
    std::optional<WoodsAnchor> woods;
    /// Whether the woods about the zoos and theme parks in its data are planted (RegionConfig::zoo_woods): the phone's
    /// windows anywhere (place_of).
    bool zoo_woods = false;
};

/// The place of window `tile` anywhere: frame_of's frame, its country at its centre, that country's tradition and
/// time zone, Amsterdam's palette.
[[nodiscard]] WindowPlace place_of(const tiles::TileKey& tile);

/// A tradition compiled into the core, read once (null for a name it does not have).
[[nodiscard]] std::shared_ptr<const Tradition> tradition_named(std::string_view name);

/// A built window: its pack, and what building it took.
struct BuiltWindow {
    std::vector<std::byte> pack;
    std::uint32_t features = 0; ///< in its tile
    double load_ms = 0;         ///< loading its scene at each tolerance
    double cut_ms = 0;
    double write_ms = 0; ///< encoding its tile, head, style and pack
    /// What the loading and the cleaning counted of the buildings of the scene its tile was cut from (tolerance 0's
    /// Scene::counts): the window's own and those in the margin about it (window_margin), which the scenes of the
    /// windows beside it count too.
    BuildingCounts counts;
    std::uint32_t footprints = 0; ///< that scene's buildings and parts
};

/// Why a window could not be built: its data does not read, its scene does not load or cut.
struct WindowError {
    std::string what;
};

/// Builds window `tile` at `place` from its data, read (`doc`), whose bytes' SHA-256 is `source`.
[[nodiscard]] std::expected<BuiltWindow, WindowError> build_window(const osm::Document& doc,
                                                                   const tiles::Sha256Digest& source,
                                                                   const tiles::TileKey& tile,
                                                                   const WindowPlace& place);

/// Builds window `tile` at `place` from its data: Overpass's JSON for window_box(tile).
[[nodiscard]] std::expected<BuiltWindow, WindowError>
build_window(std::span<const std::byte> json, const tiles::TileKey& tile, const WindowPlace& place);

/// The most windows stage T's walls can give the buildings and parts of `tile` at any level of the ladder
/// (geometry/Walls.cpp: a row a storey, a bay every 2.1 m at the closest along a wall over 2.2 m long): each record's
/// storeys, its own or a variant's where more (levels within 1 to tiles::building::most_levels; others have no
/// windows), times its ring's length in `frame`'s metres over 2.1 m, but for an outline hidden at every level. A
/// level's simplified ring is no longer than the record's and a seam carries no wall, so no level gives more: where
/// this is within tiles::building::most_tile_windows, stage T cuts no wall's windows in the tile
/// (TileBuildings::windows_cut), which it counts alone, and a pack's build (stage P) cannot.
[[nodiscard]] std::uint64_t most_windows(const tiles::Tile& tile, const tiles::Frame& frame);

/// What a window's pack holds of buildings, for the pack's build to print (stagep's `windows`).
struct PackBuildings {
    std::uint32_t records = 0;      ///< the buildings and parts of its tile
    std::uint64_t most_windows = 0; ///< most_windows of its tile
};

/// The buildings of the window's tile in `pack`, a pack as build_window writes it (none where it has no tile).
[[nodiscard]] std::expected<PackBuildings, WindowError> buildings_of(std::span<const std::byte> pack);

} // namespace atlas::load
