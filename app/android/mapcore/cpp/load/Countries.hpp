// Which country a place is in, for what a window takes from it (ENGINE.md step 3: the windows the phone builds):
// render's Countries, the port's coarse lookup for a region the catalogue has no entry for (RegionConfig.forBox). Boxes
// overlap near borders, and the smallest box holding the point wins, so enclaves and small states resolve. From the
// country: its time zone (by longitude where a country spans several), the zone's UTC offset at noon on 1 October 2026
// (regions.py's TZ_H, the renders' fixed day), and the building tradition the renders draw it with (render/traditions:
// the five drawn today), or none, for the generic looks.
#pragma once

#include <cstdint>
#include <optional>
#include <string_view>

namespace atlas::load {

/// The ISO 3166 code of the country around (lat, lon) degrees, or nothing far from any of the boxes (Countries.of).
[[nodiscard]] std::optional<std::string_view> country_of(double lat, double lon) noexcept;

/// The IANA time zone of a point in a country (Countries.timezone): by longitude in the United States, Canada,
/// Australia, Russia and Indonesia, the country's one elsewhere; UTC for a country without one or none.
[[nodiscard]] std::string_view timezone_of(std::optional<std::string_view> code, double lat, double lon) noexcept;

/// A zone's UTC offset at noon on 1 October 2026 in whole hours, rounded down as Python's `// 3600` rounds
/// (regions.py's TZ_H), or nothing for a zone the table lacks.
[[nodiscard]] std::optional<std::int32_t> zone_hours(std::string_view zone) noexcept;

/// zone_hours, 0 for a zone the table lacks.
[[nodiscard]] std::int32_t tz_hours(std::string_view zone) noexcept;

/// The building tradition the renders draw a country with (Countries.tradition: render/traditions/<name>.json), or
/// nothing.
[[nodiscard]] std::optional<std::string_view> tradition_of(std::optional<std::string_view> code) noexcept;

} // namespace atlas::load
