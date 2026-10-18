// The item table (plan-vector D1 "The scene", D6, T7): the painter-order units of the objects group (a building, a
// part, a tree, a lamp, a region item, ...), one row each, in structures of arrays, never per-feature objects. Per row:
// the point and offset the renders sort it by (render/amsterdam.py's gatherItems: Item(cam.cam(p)[2] + offset, ...),
// M1b's reference point and sort offset), the point the radius cut tests, its place in the renders' gather order (its
// list, then its source order: the stable sort's tie-break), its kind (Selectors' detail bands), its painter slots per
// detail band (an upper bound of the primitives it draws at a camera in that band: D6) and its tile.
//
// Rows come from the tiles' stage-T builders as tiles become resident (scene/Residency.hpp) and go with them; the
// render thread alone changes the table, between frames. A freed row is reused; rows keep their numbers while they
// live, so the ranks' order (frame/Ranks.hpp) survives a tile's arrival or departure.
#pragma once

#include <array>
#include <cstdint>
#include <limits>
#include <span>
#include <vector>

namespace atlas::scene {

/// The detail bands of an item (Selectors.hpp): its kind's distance thresholds split its depth into up to four.
inline constexpr std::size_t bands = 4;

/// An item's flags.
namespace item_flag {
inline constexpr std::uint16_t near_plane = 1; ///< its bounds may reach within the near plane (the clipping variant)
inline constexpr std::uint16_t radius = 2;     ///< the renders' radius cut applies to it (in engine mode)
} // namespace item_flag

/// What a stage-T builder adds for one item.
struct ItemSpec {
    /// The point the renders sort it by (frame metres: x east, y up, z south) and the offset added to its depth.
    double x = 0;
    double y = 0;
    double z = 0;
    double offset = 0;
    /// The point the radius cut tests (frame metres, x and z).
    double cut_x = 0;
    double cut_z = 0;
    /// Its place in the renders' gather order: its list (buildings 0, parts 1, nodes 2, region items 3, facades 4) in
    /// the high bits, its source order in the low 40 (order_of).
    std::uint64_t order = 0;
    /// Its kind (Selectors' row) and flags.
    std::uint16_t kind = 0;
    std::uint16_t flags = 0;
    /// Its painter slots in each detail band, nearest band first.
    std::array<std::uint32_t, bands> slots{};
    /// The least depth at which it is drawn (gatherItems: 2 m; 3 m for furniture at eye level).
    float min_depth = 2.0F;
    /// The radius of a sphere about its sort point that holds everything it draws (the frustum test, metres).
    float radius = 0.0F;
    /// The tile it belongs to (Residency's handle).
    std::uint32_t tile = 0;
};

/// An item's place in the gather order: its list and its source order.
[[nodiscard]] constexpr std::uint64_t order_of(std::uint32_t list, std::uint64_t source) noexcept {
    return (std::uint64_t{list} << 40U) | (source & ((std::uint64_t{1} << 40U) - 1));
}

/// The rows.
class ItemTable {
public:
    /// No row.
    static constexpr std::uint32_t none = std::numeric_limits<std::uint32_t>::max();

    /// Adds an item; returns its row.
    std::uint32_t add(const ItemSpec& item);
    /// Frees a row (its tile left the scene).
    void remove(std::uint32_t row) noexcept;
    /// Frees every row of tile `tile`; returns how many.
    std::size_t remove_tile(std::uint32_t tile) noexcept;
    /// Forgets every row.
    void clear() noexcept;

    /// The rows in use and the rows the table holds (in use or free).
    [[nodiscard]] std::size_t live() const noexcept { return live_; }
    [[nodiscard]] std::size_t rows() const noexcept { return x_.size(); }
    [[nodiscard]] bool alive(std::uint32_t row) const noexcept { return row < alive_.size() && alive_[row] != 0; }
    /// How many rows were added or freed since the table was made (the ranks' cue to fix their order).
    [[nodiscard]] std::uint64_t changes() const noexcept { return changes_; }

    // The columns, by row.
    [[nodiscard]] std::span<const double> x() const noexcept { return x_; }
    [[nodiscard]] std::span<const double> y() const noexcept { return y_; }
    [[nodiscard]] std::span<const double> z() const noexcept { return z_; }
    [[nodiscard]] std::span<const double> offset() const noexcept { return offset_; }
    [[nodiscard]] std::span<const double> cut_x() const noexcept { return cut_x_; }
    [[nodiscard]] std::span<const double> cut_z() const noexcept { return cut_z_; }
    [[nodiscard]] std::span<const std::uint64_t> order() const noexcept { return order_; }
    [[nodiscard]] std::span<const std::uint16_t> kind() const noexcept { return kind_; }
    [[nodiscard]] std::span<const std::uint16_t> flags() const noexcept { return flags_; }
    [[nodiscard]] std::span<const float> min_depth() const noexcept { return min_depth_; }
    [[nodiscard]] std::span<const float> radius() const noexcept { return radius_; }
    [[nodiscard]] std::span<const std::uint32_t> tile() const noexcept { return tile_; }
    [[nodiscard]] std::span<const std::uint8_t> alive_rows() const noexcept { return alive_; }
    /// Row `row`'s slots in band `band`.
    [[nodiscard]] std::uint32_t slots(std::uint32_t row, std::size_t band) const noexcept {
        return slots_[row * bands + band];
    }

private:
    std::vector<double> x_, y_, z_, offset_, cut_x_, cut_z_;
    std::vector<std::uint64_t> order_;
    std::vector<std::uint16_t> kind_, flags_;
    std::vector<float> min_depth_, radius_;
    std::vector<std::uint32_t> tile_;
    std::vector<std::uint32_t> slots_; // bands per row
    std::vector<std::uint8_t> alive_;
    std::vector<std::uint32_t> free_;
    std::size_t live_ = 0;
    std::uint64_t changes_ = 0;
};

} // namespace atlas::scene
