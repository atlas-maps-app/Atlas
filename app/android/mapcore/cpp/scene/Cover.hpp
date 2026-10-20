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
//     once the fades end.
// Every place is composed in double relative to the eye (view::View::tile_place) through the frame drawn
// (view/Frames.hpp), so a tile of another pack's frame is placed by its affine. A coarser pack may be drawn around the
// active one (D3): its tiles the same way, but those wholly inside the active pack's box (the mask), whose ground lies
// under the active pack's; the world masks the coarser pack's items whose reference points lie inside it.
#pragma once

#include "scene/Cells.hpp"
#include "scene/Residency.hpp"
#include "sun/BitTiles.hpp"
#include "view/Frames.hpp"

#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <vector>

namespace atlas::scene {

/// No sun bits (CoverTile::sun_base): a tile drawn before its first bits, or a stand-in the sun's scene does not hold.
inline constexpr std::uint32_t no_sun = 0xFFFF'FFFFU;

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
    /// How far from the eye the finest level serves (D3: L14 within about 4.7 km).
    double fine_reach = 4700.0;
    /// How far the cover reaches (the frustum's far plane, metres of depth).
    double far = 12'000.0;
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
};

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
    /// Whether a tile drawn is still fading in (its fade below 1).
    [[nodiscard]] bool fading() const noexcept { return fading_; }

private:
    /// The tiles of one pack (the active one, or the coarser one outside `mask`).
    void cover(const tiles::StorePack& pack, const view::View& view, const view::Frames& frames, const CoverInputs& in,
               std::uint8_t tolerance, const std::optional<tiles::GridBox>& mask, const Residency& residency,
               double seconds);
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
    };
    void request(const tiles::TileKey& key, Lane lane, double priority, std::uint8_t tolerance, std::string_view pack);

    std::vector<CoverTile> tiles_;
    std::vector<Request> requests_;
    std::vector<Shown> shown_;
    std::vector<Pending> pending_;
    std::string pack_id_;    // the active pack's
    std::string coarser_id_; // the coarser pack's
    std::size_t missing_ = 0;
    double fade_seconds_ = 0.18;
    bool fading_ = false;
};

} // namespace atlas::scene
