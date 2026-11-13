// Painter ranks (plan-vector D6, N3): the renders draw the objects far to near, sorted by the depth of each item's
// reference point (render/amsterdam.py's drawObjects: items.pySortBy { -it.d }, a stable sort over the gather order).
// Each frame the render thread computes every live item's depth at the camera in double (cam.cam(p)[2] + offset, the
// port's own arithmetic, the camera taken into the frame of the item's points: scene/ItemTable.hpp's places), keeps the
// items the renders draw (at least their minimum depth, their cut point within their most depth where they have one: a
// door's node within the doors pass's reach; within the renders' radius in engine mode) that the frame can see (the
// item's sphere against the frame plus the renders' least cull margin; a sphere across the eye's plane against the
// frame's four sides, so what lies behind the camera takes no rank), fixes last frame's order with a stable insertion
// pass (a full sort when the camera turned too far for that to pay: the key, depth then gather order, is total, so both
// give the stable sort's order), sorts the rows new to the order among themselves and merges them in (a tile's rows
// come thousands at once, as it arrives or is first drawn: the insertion pass would move each past half the order), and
// gives each item its first painter slot: the ground's slots first, then the items' slots in painter's order, each
// item's count that of its detail band (Selectors.hpp) at its depth. Pans and zooms keep the order; turns and tilts
// move few items in a frame.
//
// In the world's frames only the rows of the tiles drawn take ranks (RankOptions::by_tile: the cover's tiles and those
// kept under ones fading in), since no pass reads another tile's; the drawn rows' order is what it would be among
// every row, since the key is each row's own.
//
// The rank table set 0 holds (binding 1) is each row's first slot; shaders/shared/painter.slang turns a slot into its
// depth. The C++ over the item table's columns is K7's benchmark (bench/frame_bench.cpp: the Main board's items within
// 0.3 ms on one Mac core).
#pragma once

#include "frame/Selectors.hpp"
#include "scene/ItemTable.hpp"
#include "view/Camera.hpp"

#include <cstdint>
#include <span>
#include <vector>

namespace atlas::frame {

/// What the ranks take into account beyond the items and the camera.
struct RankOptions {
    /// The slots the ground takes before every item (its ground keys' count).
    std::uint32_t ground_slots = 0;
    /// The renders' radius cut (engine mode, D9): items whose cut point lies beyond `radius` of (`cut_x`, `cut_z`) are
    /// not drawn. Off in map mode.
    bool radius_cut = false;
    double radius = 0;
    double cut_x = 0;
    double cut_z = 0;
    /// The renders' least cull margin, CSS px (polygons wholly beyond the frame and it are dropped).
    double margin = 40.0;
    /// The building the app asks about (FrameState::asked): its items' building key (scene::ItemSpec::building, its
    /// parts' included; 0 for none). Its items keep only their first detail bit (a building's near cells: drawn plain,
    /// without windows or facade detail, as the FillIn board draws it), and carry RANK_ASKED where `hatched`.
    std::uint64_t asked = 0;
    bool hatched = false;
    /// The app's map (FrameState::grounded): the items flagged scene::item_flag::app take ranks, those flagged
    /// item_flag::renders none; elsewhere the other way about.
    bool grounded = false;
    /// With `by_tile`, the tiles whose rows take ranks, by their handles (ItemTable::tile), ascending: the tiles the
    /// frame draws and those kept under ones fading in (scene::Cover::upright), whose rows alone a pass reads. A
    /// resident tile's rows that no pass draws take none, in view or not: a place's coarsest level kept under its
    /// windows, the windows left resident after a zoom out, the level left after a zoom in. Without `by_tile`, every
    /// tile's rows (a scene without a cover).
    std::span<const std::uint32_t> tiles;
    bool by_tile = false;
    /// The other tiles' rows ranked all the same, as before the ranks knew the tiles drawn: the pinch audit's measure
    /// of what leaving them out saves (Ranks::undrawn counts them either way).
    bool rank_undrawn = false;
};

/// The frame's painter ranks.
class Ranks {
public:
    /// No slot: the row is not drawn this frame.
    static constexpr std::uint32_t none = 0xFFFF'FFFFU;

    /// The ranks at `camera` of `items`' live rows, their bands by `selectors`.
    void update(const scene::ItemTable& items, const view::Camera& camera, const Selectors& selectors,
                const RankOptions& options = {});
    /// Forgets the order (a new scene).
    void reset() noexcept;

    /// The rows drawn this frame, far to near (painter's order).
    [[nodiscard]] std::span<const std::uint32_t> order() const noexcept { return order_; }
    /// Each row's first painter slot (none for a row not drawn), and its depth (the sort key).
    [[nodiscard]] std::span<const std::uint32_t> first_slot() const noexcept { return first_; }
    [[nodiscard]] std::span<const double> depth() const noexcept { return depth_; }
    /// Each row's detail band (0 to 3; 0 for a row not drawn): Selectors::band of its kind at its depth, in double as
    /// the port compares, the one choice every pass and shader takes (never recomputed from a float depth).
    [[nodiscard]] std::span<const std::uint8_t> band() const noexcept { return band_; }
    /// The rank table set 0 holds (painter.slang's rank entries): per row its first slot and its detail
    /// (Selectors::detail of its kind at its depth), RANK_NONE for a row not drawn.
    [[nodiscard]] std::span<const std::uint32_t> table() const noexcept { return table_; }
    /// The frame's slots: the ground's and every drawn item's.
    [[nodiscard]] std::uint32_t slots() const noexcept { return slots_; }
    /// What the last update cost: the insertion pass's moves, and whether it fell back on a full sort.
    [[nodiscard]] std::uint64_t moves() const noexcept { return moves_; }
    [[nodiscard]] bool sorted_fully() const noexcept { return sorted_fully_; }
    /// The drawn rows whose sphere reaches within the near plane (drawn by the clipping variant, D6).
    [[nodiscard]] std::uint32_t near_items() const noexcept { return near_; }
    /// The rows of the tiles not drawn (RankOptions::by_tile): those the last update left out for it (live, not
    /// masked, of the frame's kind; in view or not), or with rank_undrawn those of them it ranked (in view).
    [[nodiscard]] std::uint32_t undrawn() const noexcept { return undrawn_; }

private:
    std::vector<std::uint32_t> order_;
    std::vector<std::uint32_t> next_;
    std::vector<std::uint32_t> merged_;
    std::vector<std::uint32_t> first_;
    std::vector<double> depth_;
    std::vector<std::uint8_t> band_;
    std::vector<std::uint32_t> table_;
    std::vector<std::uint8_t> drawn_;  // per row: drawn this frame
    std::vector<std::uint8_t> listed_; // per row: in order_
    std::uint32_t slots_ = 0;
    std::uint64_t moves_ = 0;
    bool sorted_fully_ = false;
    std::uint32_t near_ = 0;
    std::uint32_t undrawn_ = 0;
};

} // namespace atlas::frame
