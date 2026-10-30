// The cover (plan-vector D1 "The scene", D3, L14): the tiles a frame draws, each with what draws it, its visible cells,
// its fade and its place relative to the eye. Each frame, from the view and the active pack:
//   - every tile of the pack's finest level whose reach (its features' box and top, tiles::Reach) the view's frustum
//     may see, out to the cover's far distance, is wanted at the finest level within the finest level's reach of the
//     eye (or everywhere when the camera snapped onto one of the region's views: D3 pins L14 there), else at the next
//     level up; where the finest level has no tile, the next level's tiles are wanted for their visible cells;
//   - a wanted tile that is resident draws its visible cells (8 × 8 at L14); one that is not yet draws from its nearest
//     resident ancestor's cells over it (4 × 4 at L12, one L14 tile each), so nothing is ever a hole or drawn twice
//     once an ancestor is resident, and it is requested from the residency (visible tiles by their share of the screen,
//     nearest first; ancestors next, so an ancestor is resident before its children are dropped);
//   - a tile that comes to be drawn fades in over 180 ms (D3 "Transitions": 150-200 ms), so the frame at rest is exact
//     once the fades end;
//   - the levels asked for fit the heaps (ENGINE.md one pack per country, step 7): with a budget, every place's
//     coarsest level is asked for whatever it costs (the stand-in under every view), and the finest level within its
//     reach (the near view as before the levels), then nearest first each other place the level its distance asks for
//     where its tile's bytes fit what is left, else the next coarser that fits; a tile's
//     bytes are its heap ranges' while resident, as they were when it was resident before, else its chunk's raw bytes
//     times what its level's resident tiles take per raw byte (every level's before any of its own was resident). A
//     tile no place asks for is not drawn once the tile planned there is, and the world lets it go; so the near tiles
//     are never starved by far ones drawn in their stead. Each place's coarsest tile is kept resident under it while it
//     is in view (kept), so a finer tile drawn in its stead never holds the room the coarser ones wait for.
// Every place is composed in double relative to the eye (view::View::tile_place) through the frame drawn
// (view/Frames.hpp), so a tile of another pack's frame is placed by its affine. A coarser pack may be drawn around the
// active one (D3): its tiles the same way, but those wholly inside the active pack's box (the mask), whose ground lies
// under the active pack's; the world masks the coarser pack's items whose reference points lie inside it. Neighbours
// may be drawn beside them (frame cells' windows, ENGINE.md step 3; up to three, as four cells meet at a corner): each
// one's tiles where neither of those nor a neighbour before it has a tile.
#pragma once

#include "scene/Cells.hpp"
#include "scene/Residency.hpp"
#include "sun/BitTiles.hpp"
#include "view/Frames.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

namespace atlas::scene {

/// No sun bits (CoverTile::sun_base): a tile drawn before its first bits, or a stand-in the sun's scene does not hold.
inline constexpr std::uint32_t no_sun = 0xFFFF'FFFFU;

/// The packs drawn beside the active one at most: the other frame cells in view (ENGINE.md one pack per country, step
/// 4: every cell in view drawn; three where four cells meet, more where a far view reaches beyond the cells around the
/// target: step 7, the levels), and how many of them, the nearest, have a sun's lane (their places' tiles near the
/// eye).
inline constexpr std::size_t max_neighbours = 8;
inline constexpr std::size_t sun_neighbours = 3;

/// One tile the frame draws: the tile wanted, the resident tile that draws it (itself, or its nearest resident
/// ancestor, whose cells over it are drawn), the drawn tile's cells that are visible (bit r · n + c), its fade (0 to
/// 1), its place relative to the eye, its distance from the eye and its share of the screen; and its sun (the world's
/// SunGlobals fills them each frame).
struct CoverTile {
    tiles::TileKey key;
    const ResidentTile* drawn = nullptr;
    std::uint64_t cells = 0;
    float fade = 1.0F;
    shaders::TilePlace place{};
    double distance = 0;
    double share = 0;
    /// The drawn tile's sun bits this frame, on the CPU (the twin's and the passes' own choices): its latest bucket's
    /// and the one before, and the swap's fade weight.
    std::optional<sun::TileView> sun;
    /// Where its texels begin in set 1's sun_bits, in texels (R32G32_UINT: per 32 samples the new bits, then the old;
    /// sample i's in texel sun_base + i / 32, bit i % 32), or no_sun; and how far the swap has faded (old 0, new 1).
    std::uint32_t sun_base = no_sun;
    float sun_weight = 1.0F;
};

/// What the cover is made of for a frame.
struct CoverInputs {
    /// The active pack (the store's) and the frame drawn; null: no tiles.
    const tiles::StorePack* pack = nullptr;
    /// The camera snapped onto one of the region's views: the finest level everywhere (D3).
    bool pinned = false;
    /// The ladder index tiles are built at (rhead's tolerance_of for the view).
    std::uint8_t tolerance = 0;
    /// How far from the eye the finest level serves (D3: L14 within about 4.7 km), and the next where a third follows
    /// it (a frame cell's coarse level, L13, before its far level, L12: ENGINE.md one pack per country, step 7).
    double fine_reach = 4700.0;
    double coarse_reach = 9400.0;
    /// How far the cover reaches (the frustum's far plane, metres of depth).
    double far = 12'000.0;
    /// How far a place whose pack holds no coarser tile there (a cell before the phone took its levels, windows outside
    /// the countries) is drawn at its finest level: the cover's reach before the levels.
    double fine_far = 12'000.0;
    /// The renders' least cull margin, CSS px.
    double margin_px = 40.0;
    /// How long a tile fades in, seconds.
    double fade_seconds = 0.18;
    /// A coarser pack drawn around the active one (D3: the canal ring around the Westerkerk; null: none), the ladder
    /// index its tiles are built at, and the box its tiles are left out under when they lie wholly inside it (the
    /// active pack's box, whose ground covers them; their items inside it are masked by the world).
    const tiles::StorePack* coarser = nullptr;
    std::uint8_t coarser_tolerance = 0;
    std::optional<tiles::GridBox> mask;
    /// The packs drawn beside the active one, in the world's order (World::neighbours: frame cells' windows beside a
    /// render pack or beside a cell's windows, render packs beside a cell's windows; null: none), and the ladder index
    /// each one's tiles are built at. None is masked, but a neighbour's tiles at a place where the active pack, the
    /// coarser one or a neighbour before it has a tile of its finest level are left out: windows are built where no
    /// pack has a tile, and a pack's tile there is the one drawn.
    std::array<const tiles::StorePack*, max_neighbours> neighbours{};
    std::array<std::uint8_t, max_neighbours> neighbour_tolerances{};
    /// The heaps' bytes the tiles asked for may take (0: no limit): the plan's budget.
    std::uint64_t budget = 0;
};

/// What the plan takes a tile to cost per raw byte of its chunk before any tile was resident: the levels' densest
/// tiles hold their walls in 11 to 12 times their raw bytes (Alkmaar's L13, 30 October), their roofs, ground and trees
/// besides.
inline constexpr double first_ratio = 16.0;

/// The frame's cover.
class Cover {
public:
    /// The cover of `view` (its camera in the frame drawn by `frames`) over `in.pack`, drawn from `residency`'s
    /// resident tiles, at `seconds` (the frame's clock). Render thread.
    void update(const view::View& view, const view::Frames& frames, const CoverInputs& in, const Residency& residency,
                double seconds);

    /// The tiles drawn, nearest first.
    [[nodiscard]] std::span<const CoverTile> tiles() const noexcept { return tiles_; }
    /// The same, for the world to fill their sun (SunGlobals::prepare).
    [[nodiscard]] std::span<CoverTile> tiles() noexcept { return tiles_; }
    /// The requests for the residency (want), by lane and priority.
    [[nodiscard]] std::span<const Request> requests() const noexcept { return requests_; }
    /// The tiles wanted at the finest level that are not resident yet (drawn from an ancestor, or not at all).
    [[nodiscard]] std::size_t missing() const noexcept { return missing_; }
    /// The resident tiles kept under the places in view though not drawn (their handles): with a budget, each place's
    /// coarsest level, the stand-in under every view, which the plan counts whatever it costs. The world keeps them
    /// as it keeps the tiles drawn, so a place zoomed out from draws its coarsest tile at once and its finer ones go:
    /// drawn in its stead, they held the room the coarser tiles of the places around it waited for, which then showed
    /// nothing (the Westerkerk zoomed out from 5 to 20 km, 30 October). And a place's own tile where none of its cells
    /// is seen (its features all beyond the view, or none): let go of as not drawn, it was asked for again the next
    /// frame and built again, again and again while the heaps were full (Alkmaar from 5 km, its finest tiles).
    [[nodiscard]] std::span<const std::uint32_t> kept() const noexcept { return kept_; }
    /// Whether a tile drawn is still fading in (its fade below 1).
    [[nodiscard]] bool fading() const noexcept { return fading_; }
    /// The active pack and the coarser one drawn this frame (their store ids; empty: none).
    [[nodiscard]] std::string_view active() const noexcept { return pack_id_; }
    [[nodiscard]] std::string_view coarser() const noexcept { return coarser_id_; }

    /// The heaps' bytes the tiles asked for this frame are planned to take (0 without a budget), and how many places
    /// the plan gave a coarser level than their distance asks for.
    [[nodiscard]] std::uint64_t planned() const noexcept { return planned_; }
    [[nodiscard]] std::size_t coarsened() const noexcept { return coarsened_; }

private:
    /// A pack drawn this frame: its store's pack and id, whether it is the active one, its tiles' ladder index.
    struct Drawn {
        const tiles::StorePack* pack = nullptr;
        std::string_view id;
        bool active = false;
        std::uint8_t tolerance = 0;
    };
    /// One place of a drawn pack's finest level in view: its pack (drawn_'s index), where it lies, how far it is and
    /// its priority, its levels' tiles there (finest first; none where the pack has none), the level its distance asks
    /// for and the level the plan gives it (indexes into keys).
    struct Spot {
        std::uint32_t x = 0;
        std::uint32_t y = 0;
        double d = 0;
        double priority = 0;
        std::array<std::optional<tiles::TileKey>, 3> keys{};
        std::uint8_t pack = 0;
        std::uint8_t want = 0;
        std::uint8_t plan = 0;
    };
    /// The places of one pack in view (the active one; the coarser one outside `mask`; a neighbour where none of
    /// `above` has a tile of its finest level), each with the level its distance asks for.
    void walk(const Drawn& drawn, const view::Frames& frames, const Frustum& frustum, const CoverInputs& in,
              const std::optional<tiles::GridBox>& mask, std::span<const tiles::StorePack* const> above);
    /// The levels within the budget (Spot::plan).
    void plan(const CoverInputs& in, const Residency& residency);
    /// Pack `p`'s places drawn at their planned levels, and what they lack asked for.
    void emit(std::size_t p, const view::View& view, const view::Frames& frames, const Frustum& frustum,
              const Residency& residency, double seconds);
    void draw(const ResidentTile& tile, std::uint64_t cells, const view::View& view, const view::Frames& frames,
              const Frustum& frustum, tiles::TileKey wanted, double seconds);

    /// A resident tile drawn, and when it was first drawn (its fade).
    struct Shown {
        std::uint32_t handle = 0;
        double since = 0;
        bool drawn = false; // this frame
    };
    /// An ancestor's cells to draw this frame over its missing descendants.
    struct Pending {
        tiles::TileKey key;
        std::uint64_t cells = 0;
        bool planned = false; // a place's own tile (its plan's level), kept though none of its cells is seen
    };
    void keep(std::uint32_t handle);
    void request(const tiles::TileKey& key, Lane lane, double priority, std::uint8_t tolerance, std::string_view pack);

    std::vector<CoverTile> tiles_;
    std::vector<Request> requests_;
    std::vector<Shown> shown_;
    std::vector<Pending> pending_;
    std::string pack_id_;                                   // the active pack's
    std::string coarser_id_;                                // the coarser pack's
    std::array<std::string, max_neighbours> neighbour_ids_; // the neighbours'
    std::size_t missing_ = 0;
    double fade_seconds_ = 0.18;
    bool fading_ = false;
    std::array<Drawn, 2 + max_neighbours> drawn_{};
    std::size_t drawn_count_ = 0;
    std::vector<Spot> spots_;
    std::vector<std::uint32_t> kept_;
    bool planning_ = false; // a budget this frame: the plan and the coarsest levels kept
    std::uint64_t planned_ = 0;
    std::size_t coarsened_ = 0;
    /// Per zoom, the heaps' bytes per raw byte of the resident tiles (0: none seen yet), and the bytes of the tiles
    /// resident before (by tile_id), for when they are asked for again.
    std::array<double, 32> ratio_{};
    std::unordered_map<std::uint64_t, std::uint64_t> bytes_;
};

} // namespace atlas::scene
