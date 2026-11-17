// A big road's lanes and width as the ground draws them (geometry/Ground.cpp's draw_over, stage T), and as stage P
// widens the road's box by (load/Cut.cpp's big_pad): one rule for both, so that the box a road is filed by is never
// narrower than its asphalt.
#pragma once

#include <cstdint>
#include <optional>
#include <string_view>

namespace atlas::geometry {

/// The most lanes and the widest width a big road is drawn with: past these no road has them (the Netherlands' extract
/// of 16 November 2026 has 14 lanes at most and Germany's 21, the widest toll plazas about 50 one way; 64 lanes of a
/// motorway are 228 m wide), and their lane lines would be drawn without bound (each lane a dashed line along an
/// offset whose bends part by twice the offset).
inline constexpr std::int64_t most_lanes = 64;
inline constexpr double widest_road = 250.0;

/// A big road's lanes and width (metres) as drawn.
struct BigRoadSize {
    std::int64_t lanes = 0;
    double width = 0.0;
};

/// The lanes and width a big road of highway `hw` is drawn with, from its `lanes` and `width` tags (real.py's num): a
/// lanes that cannot be read, or lies past 0 to most_lanes, takes the default (a link's 1, else 2), and a width that is
/// not finite and within 0 to widest_road what an absent width gives (lanes × 3.5 + 1, + 3 on a motorway). A value
/// out of range reads as an absent tag, never as the bound; one in range as it is (a list's first number, "0").
[[nodiscard]] BigRoadSize big_road_size(std::string_view hw, std::optional<std::string_view> lanes,
                                        std::optional<std::string_view> width) noexcept;

} // namespace atlas::geometry
