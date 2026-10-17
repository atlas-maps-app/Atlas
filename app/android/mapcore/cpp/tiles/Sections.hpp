// A render pack's binary sections (plan-vector Appendix A; rendertiles' RenderHead and StyleTables): `rhead`, what the
// core needs to place the pack's tiles and draw them by its region's rules, and `style`, the renders' tables the stages
// read. Both little-endian; the core reads no JSON.
//
// `rhead`: u32 "ATRH", u16 payload version, u8 profile, u8 level count and the levels (finest first), the frame's lat0,
// lon0, kx and kz as the port's doubles (their exact bits), then strings (varint length, UTF-8): the region's id,
// EngineVersion.ID, StyleVersion.ID and the source's SHA-256; then a varint view count and per view its name, its
// camera (a typed map with written-out strings: places.py's view, target, yaw, pitch, dist, fov, cy, radius, ...) and a
// u8, the ladder index of the tolerance its renders load the region at (GENERALISE); then the live map's bands: a u8
// count B (at least 1), B u8 ladder indexes, and B − 1 doubles, the distances (metres, eye to target) where each band
// but the last ends.
//
// Which tolerance a frame loads at (the port's Lod.tolerance, RenderHead.toleranceOf): a camera the ladder snaps onto
// one of the views takes that view's index; any other takes the first band whose end its distance is below, else the
// last. The living map has one band, at index 0: it is never simplified.
//
// `style`: u32 "ATRS", u16 payload version, a varint table count, then per table its name and its typed value.
#pragma once

#include "tiles/Error.hpp"
#include "tiles/Values.hpp"

#include <cstddef>
#include <cstdint>
#include <optional>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::tiles {

/// The payload version this reader knows: `rhead`, `style` and every tile carry it.
inline constexpr std::uint16_t payload_version = 1;

/// The first four bytes of a tile, of `rhead` and of `style` ("ATRT", "ATRH", "ATRS", little-endian).
inline constexpr std::uint32_t tile_magic = 0x5452'5441U;
inline constexpr std::uint32_t head_magic = 0x4852'5441U;
inline constexpr std::uint32_t style_magic = 0x5352'5441U;

/// Which of the renders' rule sets draws a pack (plan-vector D3, D15).
enum class Profile : std::uint8_t {
    living = 0,       ///< the Westerkerk's living map (amsterdam.load, layer='split')
    region = 1,       ///< every other region of the renders (regions.load)
    williamsburg = 2, ///< real.py (K30)
    world_base = 3,   ///< the world mid-zoom base (K20b)
};

/// A pack's frame: the port's metres from OSM's grid, as amsterdam.py's XZ computes them from the pack's coordinates
/// (each the grid's integer divided by 1e7): x = (lon − lon0)·kx east, z = −(lat − lat0)·kz south. The same operations
/// in the same order, contraction off, so a vertex lands bit for bit where the oracle puts it.
struct Frame {
    double lat0 = 0.0;
    double lon0 = 0.0;
    double kx = 0.0;
    double kz = 0.0;

    [[nodiscard]] static constexpr double degrees(std::int32_t grid) noexcept {
        return static_cast<double>(grid) / 1e7;
    }
    [[nodiscard]] constexpr double x(std::int32_t lon) const noexcept { return (degrees(lon) - lon0) * kx; }
    [[nodiscard]] constexpr double z(std::int32_t lat) const noexcept { return -(degrees(lat) - lat0) * kz; }
};

/// One of the region's views: its name (grachten_home), its camera, and the ladder index of its tolerance.
struct HeadView {
    std::string_view name;
    Value camera;
    std::uint8_t tolerance = 0;
};

/// A band of the live map: the ladder index of its tolerance, and the distance (metres, eye to target) it ends at; the
/// last band has none.
struct HeadBand {
    std::uint8_t tolerance = 0;
    std::optional<double> up_to;
};

/// The `rhead` section.
class RenderHead {
public:
    RenderHead() = default;
    RenderHead(const RenderHead&) = delete; // its strings and values view its own buffers
    RenderHead& operator=(const RenderHead&) = delete;
    RenderHead(RenderHead&&) noexcept = default;
    RenderHead& operator=(RenderHead&&) noexcept = default;
    ~RenderHead() = default;

    Profile profile = Profile::region;
    std::vector<std::uint8_t> levels;
    Frame frame;
    std::string_view region;
    std::string_view engine_version;
    std::string_view style_version;
    std::string_view source;

    [[nodiscard]] std::span<const HeadView> views() const noexcept { return views_; }
    [[nodiscard]] std::optional<HeadView> view(std::string_view name) const noexcept;

    /// The live map's bands, nearest first (at least one; the last without an end).
    [[nodiscard]] std::span<const HeadBand> bands() const noexcept { return bands_; }

    /// The ladder index a frame loads at: the view's when the ladder snapped the camera onto `view` (nothing when the
    /// head has no such view), else the band of `distance`, the eye's metres from its target.
    [[nodiscard]] std::optional<std::uint8_t> tolerance_of(std::optional<std::string_view> view,
                                                           double distance) const noexcept;

    /// Whether every view and band loads at ladder index 0: the pack is never simplified and carries no keep codes.
    [[nodiscard]] bool never_simplified() const noexcept;

private:
    friend Result<RenderHead> read_head(std::span<const std::byte> section);

    std::vector<std::byte> bytes_;
    std::vector<ValueNode> nodes_;
    std::vector<std::string_view> strings_;
    std::vector<HeadView> views_;
    std::vector<HeadBand> bands_;
};

/// Reads `rhead` (a copy of its bytes is kept): magic, payload version, a known profile, levels (from fine to coarse,
/// each at most the finest zoom), every view's camera a map and its tolerance on the ladder, at least one band, each
/// on the ladder, every band but the last ending at a number, no byte left over.
[[nodiscard]] Result<RenderHead> read_head(std::span<const std::byte> section);

/// The `style` section: the renders' tables by name.
class StyleTables {
public:
    StyleTables() = default;
    StyleTables(const StyleTables&) = delete;
    StyleTables& operator=(const StyleTables&) = delete;
    StyleTables(StyleTables&&) noexcept = default;
    StyleTables& operator=(StyleTables&&) noexcept = default;
    ~StyleTables() = default;

    /// The table named `name`, or nothing.
    [[nodiscard]] std::optional<Value> table(std::string_view name) const noexcept;

    /// Every table, in the section's order.
    [[nodiscard]] std::span<const std::pair<std::string_view, Value>> tables() const noexcept { return tables_; }

private:
    friend Result<StyleTables> read_style(std::vector<std::byte> raw);

    std::vector<std::byte> bytes_;
    std::vector<ValueNode> nodes_;
    std::vector<std::string_view> strings_;
    std::vector<std::pair<std::string_view, Value>> tables_;
};

/// Reads the decompressed `style` section (it keeps the bytes): magic, payload version, each table once, no byte left.
[[nodiscard]] Result<StyleTables> read_style(std::vector<std::byte> raw);

} // namespace atlas::tiles
