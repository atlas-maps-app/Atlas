// The ground layer (rendertiles' Schema.GROUND): S['areas'] and S['land'], each ring stored whole in the tile of its
// reference point (the renders test and group each polygon whole), with simplify's keep codes where the pack's views
// and bands simplify. The renders simplify areas at a view's or band's tolerance and draw land cover whole there; its
// codes serve the coarser levels. Each with its slot in the renders' ground order.
#pragma once

#include "tiles/Schema.hpp"

#include <array>
#include <cstdint>

namespace atlas::tiles::ground {

inline constexpr std::array<ColumnSpec, 10> columns = {{
    {"source", Codec::u8},       // 0: S['areas']; 1: S['land']
    {"index", Codec::delta},     // source order in its list (S['land'] sorted by LAND's draw order, then area)
    {"id", Codec::nvarz},        // the OSM id (land cover has one, areas do not)
    {"ring", Codec::geom},       // the ring, whole
    {"ring.keep", Codec::keep},  // keep codes (simplify's)
    {"kind", Codec::str},        // the kind (park, square, residential, ...)
    {"ground_key", Codec::varu}, // the slot in the renders' ground order (land cover's class is the frame's)
    {"tag:bridge", Codec::str},
    {"tag:man_made", Codec::str},
    {"tag:surface", Codec::str},
}};

inline constexpr std::uint8_t source = slot_of(columns, "source");
inline constexpr std::uint8_t index = slot_of(columns, "index");
inline constexpr std::uint8_t id = slot_of(columns, "id");
inline constexpr std::uint8_t ring = slot_of(columns, "ring");
inline constexpr std::uint8_t ring_keep = slot_of(columns, "ring.keep");
inline constexpr std::uint8_t kind = slot_of(columns, "kind");
inline constexpr std::uint8_t ground_key = slot_of(columns, "ground_key");

/// The two sources of the layer.
inline constexpr std::uint8_t source_area = 0;
inline constexpr std::uint8_t source_land = 1;

} // namespace atlas::tiles::ground

namespace atlas::tiles::ground_key {

/// A ground feature's slot in the renders' ground order (rendertiles' GroundKey): stage << 28 | class << 22 | order.
/// Every key is static but land cover's class, which the frame chooses (land).
[[nodiscard]] constexpr std::uint32_t stage(std::uint64_t key) noexcept {
    return static_cast<std::uint32_t>(key >> 28U);
}
[[nodiscard]] constexpr std::uint32_t klass(std::uint64_t key) noexcept {
    return static_cast<std::uint32_t>((key >> 22U) & 63U);
}
[[nodiscard]] constexpr std::uint32_t order(std::uint64_t key) noexcept {
    return static_cast<std::uint32_t>(key & 0x3F'FFFFU);
}

/// The stages, in the fixed order of `render`'s ground layers.
///
/// land: regions.py's draw_under, one path per kind, the kinds in the order their first drawn polygon comes in
/// S['land']. A land feature's key is (land, 0, its index), as the reader carries it; the frame ranks the kinds (K9),
/// each by the smallest index among its rings that gpoly keeps at that camera (whole, clipped at the near plane, margin
/// 600, area over 0.3 px²), and a land feature drawn in that frame takes its kind's rank as its class.
inline constexpr std::uint32_t land = 1;
inline constexpr std::uint32_t water = 2; // the water's fill
inline constexpr std::uint32_t area = 3;  // S['areas'], in data order, under the mat filter
inline constexpr std::uint32_t road = 4;  // S['roads'], class by class (ROAD_ORDER), data order within a class
inline constexpr std::uint32_t over =
    5; // regions.py's draw_over: rail, big roads, runways, marine works, a park's edge
inline constexpr std::uint32_t marks = 6; // the marks over the ground, drawn after the whole ground (classes below)

/// The classes of `marks`, in the order `render` appends them.
inline constexpr std::uint32_t marks_road =
    0; // the road loop's marks (a bridge's railing lines, a sett road's courses)
inline constexpr std::uint32_t marks_tram = 1;  // tram tracks, in data order
inline constexpr std::uint32_t marks_canal = 2; // canal names, in source order

} // namespace atlas::tiles::ground_key
