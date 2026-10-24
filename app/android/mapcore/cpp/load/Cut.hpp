// The scene cut into render tiles (ENGINE.md step 3, stage P): rendertiles' SceneToTiles for the living map, so the
// core writes from the same data the tiles the packer writes, byte for byte (tiles/TileWriter.hpp writes them). Every
// record of the scene becomes a feature in the tile and cell of its reference point (a building's centroid rounded onto
// the grid, a line's middle vertex, a ring's box centre, a node), with the values the packer derives for it:
//   - buildings and parts: the ring on OSM's grid, the loader's fields (heights, year, levels, roof, orientation, the
//     material drawn, the guessed tags, the places and their opening hours, hidden), the tags the renders read, the
//     wall's and roof's base colours as surface_colour resolves them, and the derived flags, a quay edge within 16 m
//     among them (near_water over water_edges);
//   - the quay edges water_edges finds; trees; things (lamps, furniture, places, and the doors and shopfronts on the
//     facades); roads with their ground and marks keys; tram rails; areas; canal names and place nodes;
//   - the water, each ring clipped at the edges of every tile it reaches (rendertiles' Clip, in exact integers).
// Each feature's box and top bound what the renders draw from it (rendertiles' Drawn), and each tile's cell table holds
// their union. A vertex off OSM's grid refuses the cut, as the packer's build fails: Overpass prints every coordinate
// on it. The living map is cut at L14 alone (its pack has no other level) and carries no keep codes.
//
// Where the packer's build fails on a value, the core cuts on, named here:
//   - a wall or roof colour `#…` that Java cannot read as three hex pairs (hx throws) is taken as unmapped: the clay;
//   - a tree that is also a place: the tree layer has no column for its style, which is left out;
//   - a piece of a water ring that turns against its ring (a ring that crosses itself) is left out.
#pragma once

#include "load/Scene.hpp"
#include "tiles/Chunks.hpp"
#include "tiles/Sections.hpp"
#include "tiles/TileWriter.hpp"

#include <expected>
#include <memory>
#include <span>
#include <string>
#include <vector>

namespace atlas::load {

/// Why a scene cannot be cut: what showed it, as the packer names it (`building 3 (123456): z = … is not on the grid`).
struct CutError {
    std::string what;
    /// What the scene holds that the tiles cannot carry yet (a tradition's details), which a region's cut refuses as
    /// the packer's build fails: a region the format carries once its columns come (the packer's render_gap), not a
    /// failure.
    bool uncarried = false;
};

/// The living map's render tiles at L14: the scene cut in `frame`, the tiles in the order a feature first reached
/// them.
[[nodiscard]] std::expected<std::vector<tiles::TileFill>, CutError> cut_living(const Scene& scene,
                                                                               const tiles::Frame& frame);

/// The same region loaded at another tolerance of the ladder (its views' and the live map's bands'): where a
/// building's or part's attributes differ there, a variant feature carries them for that tolerance.
struct CutVariant {
    double tolerance = 0.0;
    const Scene* scene = nullptr;
};

/// A region's render tiles at L14 and L12 (SceneToTiles for a region, its scene loaded whole at tolerance 0): rings
/// with keep codes, the variants at `variants`' tolerances, and what regions.py adds to the living map's lists. L12
/// holds the ground (the areas and the land cover) and the water at the ladder's 4 m, and the labels.
[[nodiscard]] std::expected<std::vector<tiles::TileFill>, CutError>
cut_region(const Scene& scene, const tiles::Frame& frame, std::span<const CutVariant> variants);

/// One window of the map the phone builds (load/Window.hpp): a region's cut at L14 alone, of the features whose
/// reference point lies in `tile` and the water clipped to its edges, with keep codes and the variants of the features
/// in it. What the tiles have no columns for yet is left out where the packer's cut refuses it: a tradition's details
/// (trad, party, lv_why, roof_why) until step 4's facades; the looks the details lead to (the levels, the roofs, the
/// colours) are carried.
[[nodiscard]] std::expected<std::vector<tiles::TileFill>, CutError> cut_window(const Scene& scene,
                                                                               const tiles::Frame& frame,
                                                                               std::span<const CutVariant> variants,
                                                                               const tiles::TileKey& tile);

/// cut_window in steps, so that a window's scenes at the live map's tolerances are never held together (ENGINE.md step
/// 3's budget for a window's memory): `base` takes the attributes of the buildings and parts in the tile from the
/// scene at tolerance 0, `compare` takes each other tolerance's scene as it comes and keeps a variant wherever a
/// building's or part's attributes differ, and `cut` cuts the tile from the scene at tolerance 0, loaded again, with
/// those variants. The tiles are cut_window's for the same scenes, byte for byte. The scenes' document must outlive
/// it: the attributes view its strings.
class WindowCut {
public:
    WindowCut(const tiles::Frame& frame, const tiles::TileKey& tile);
    WindowCut(const WindowCut&) = delete;
    WindowCut& operator=(const WindowCut&) = delete;
    WindowCut(WindowCut&&) noexcept;
    WindowCut& operator=(WindowCut&&) noexcept;
    ~WindowCut();

    /// Takes the base: the scene at tolerance 0.
    [[nodiscard]] std::expected<void, CutError> base(const Scene& scene);
    /// Compares the scene at `tolerance` (on the keep ladder) with the base.
    [[nodiscard]] std::expected<void, CutError> compare(const Scene& scene, double tolerance);
    /// The tile, cut from the scene at tolerance 0 (the base's own, loaded again) with the variants found.
    [[nodiscard]] std::expected<std::vector<tiles::TileFill>, CutError> cut(const Scene& scene);

private:
    struct State;
    std::unique_ptr<State> state_;
};

} // namespace atlas::load
