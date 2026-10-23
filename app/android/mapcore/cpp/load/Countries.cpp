// Which country a place is in (load/Countries.hpp).
#include "load/Countries.hpp"

#include <array>
#include <cmath>

namespace atlas::load {

namespace {

struct CountryBox {
    std::string_view code;
    double west;
    double south;
    double east;
    double north;
    [[nodiscard]] constexpr double area() const noexcept { return (east - west) * (north - south); }
    [[nodiscard]] constexpr bool has(double lat, double lon) const noexcept {
        return lon >= west && lon <= east && lat >= south && lat <= north;
    }
};

struct CountryZone {
    std::string_view code;
    std::string_view zone;
};

struct ZoneOffset {
    std::string_view zone;
    std::int32_t seconds;
};

// render's Countries.boxes: code, west, south, east, north (the port's Kotlin literals)
constexpr std::array<CountryBox, 72> boxes = {{
    {.code = "NL", .west = 3.2, .south = 50.75, .east = 7.25, .north = 53.6},
    {.code = "BE", .west = 2.5, .south = 49.5, .east = 6.4, .north = 51.5},
    {.code = "LU", .west = 5.73, .south = 49.44, .east = 6.53, .north = 50.19},
    {.code = "DE", .west = 5.8, .south = 47.25, .east = 15.1, .north = 55.1},
    {.code = "FR", .west = -5.2, .south = 41.3, .east = 9.7, .north = 51.1},
    {.code = "GB", .west = -8.7, .south = 49.8, .east = 1.8, .north = 60.9},
    {.code = "IE", .west = -10.7, .south = 51.4, .east = -5.9, .north = 55.5},
    {.code = "ES", .west = -9.4, .south = 35.9, .east = 4.4, .north = 43.9},
    {.code = "PT", .west = -9.6, .south = 36.9, .east = -6.1, .north = 42.2},
    {.code = "IT", .west = 6.6, .south = 36.6, .east = 18.6, .north = 47.1},
    {.code = "CH", .west = 5.95, .south = 45.8, .east = 10.5, .north = 47.85},
    {.code = "AT", .west = 9.5, .south = 46.35, .east = 17.2, .north = 49.05},
    {.code = "DK", .west = 8.0, .south = 54.5, .east = 15.2, .north = 57.8},
    {.code = "NO", .west = 4.4, .south = 57.9, .east = 31.2, .north = 71.3},
    {.code = "SE", .west = 10.9, .south = 55.3, .east = 24.2, .north = 69.1},
    {.code = "FI", .west = 20.5, .south = 59.7, .east = 31.7, .north = 70.2},
    {.code = "IS", .west = -24.6, .south = 63.2, .east = -13.4, .north = 66.6},
    {.code = "PL", .west = 14.1, .south = 49.0, .east = 24.2, .north = 54.9},
    {.code = "CZ", .west = 12.1, .south = 48.5, .east = 18.9, .north = 51.1},
    {.code = "SK", .west = 16.8, .south = 47.7, .east = 22.6, .north = 49.7},
    {.code = "HU", .west = 16.1, .south = 45.7, .east = 22.9, .north = 48.6},
    {.code = "RO", .west = 20.2, .south = 43.6, .east = 29.8, .north = 48.3},
    {.code = "BG", .west = 22.3, .south = 41.2, .east = 28.7, .north = 44.3},
    {.code = "GR", .west = 19.3, .south = 34.7, .east = 28.3, .north = 41.8},
    {.code = "HR", .west = 13.4, .south = 42.3, .east = 19.5, .north = 46.6},
    {.code = "SI", .west = 13.3, .south = 45.4, .east = 16.7, .north = 46.9},
    {.code = "RS", .west = 18.8, .south = 42.2, .east = 23.1, .north = 46.2},
    {.code = "EE", .west = 21.7, .south = 57.5, .east = 28.3, .north = 59.8},
    {.code = "LV", .west = 20.9, .south = 55.6, .east = 28.3, .north = 58.1},
    {.code = "LT", .west = 20.9, .south = 53.9, .east = 26.9, .north = 56.5},
    {.code = "UA", .west = 22.1, .south = 44.3, .east = 40.3, .north = 52.4},
    {.code = "BY", .west = 23.1, .south = 51.2, .east = 32.8, .north = 56.2},
    {.code = "TR", .west = 25.6, .south = 35.8, .east = 44.9, .north = 42.2},
    {.code = "RU", .west = 27.0, .south = 41.0, .east = 180.0, .north = 77.7},
    {.code = "MT", .west = 14.1, .south = 35.78, .east = 14.6, .north = 36.1},
    {.code = "CY", .west = 32.2, .south = 34.5, .east = 34.7, .north = 35.75},
    {.code = "US", .west = -125.0, .south = 24.4, .east = -66.9, .north = 49.5},
    {.code = "US", .west = -170.0, .south = 51.0, .east = -129.9, .north = 71.5},
    {.code = "US", .west = -160.5, .south = 18.8, .east = -154.7, .north = 22.3},
    {.code = "CA", .west = -141.0, .south = 41.7, .east = -52.6, .north = 83.2},
    {.code = "MX", .west = -118.5, .south = 14.5, .east = -86.7, .north = 32.8},
    {.code = "CU", .west = -85.0, .south = 19.8, .east = -74.1, .north = 23.3},
    {.code = "BR", .west = -74.0, .south = -33.8, .east = -34.8, .north = 5.3},
    {.code = "AR", .west = -73.6, .south = -55.1, .east = -53.6, .north = -21.8},
    {.code = "CL", .west = -75.7, .south = -55.9, .east = -66.4, .north = -17.5},
    {.code = "PE", .west = -81.4, .south = -18.4, .east = -68.7, .north = -0.0},
    {.code = "CO", .west = -79.0, .south = -4.3, .east = -66.8, .north = 12.5},
    {.code = "VE", .west = -73.4, .south = 0.6, .east = -59.8, .north = 12.2},
    {.code = "ZA", .west = 16.4, .south = -34.9, .east = 32.9, .north = -22.1},
    {.code = "EG", .west = 24.7, .south = 21.7, .east = 36.9, .north = 31.7},
    {.code = "MA", .west = -13.2, .south = 27.6, .east = -1.0, .north = 35.9},
    {.code = "KE", .west = 33.9, .south = -4.7, .east = 41.9, .north = 5.0},
    {.code = "NG", .west = 2.7, .south = 4.3, .east = 14.7, .north = 13.9},
    {.code = "ET", .west = 33.0, .south = 3.4, .east = 48.0, .north = 14.9},
    {.code = "IL", .west = 34.2, .south = 29.5, .east = 35.9, .north = 33.4},
    {.code = "SA", .west = 34.6, .south = 16.4, .east = 55.7, .north = 32.2},
    {.code = "AE", .west = 51.5, .south = 22.6, .east = 56.4, .north = 26.1},
    {.code = "IR", .west = 44.0, .south = 25.0, .east = 63.3, .north = 39.8},
    {.code = "IN", .west = 68.1, .south = 6.7, .east = 97.4, .north = 35.5},
    {.code = "PK", .west = 60.9, .south = 23.6, .east = 77.8, .north = 37.1},
    {.code = "CN", .west = 73.5, .south = 18.1, .east = 134.8, .north = 53.6},
    {.code = "JP", .west = 122.9, .south = 24.0, .east = 153.99, .north = 45.6},
    {.code = "KR", .west = 125.0, .south = 33.0, .east = 131.0, .north = 38.7},
    {.code = "TW", .west = 119.9, .south = 21.8, .east = 122.1, .north = 25.4},
    {.code = "TH", .west = 97.3, .south = 5.6, .east = 105.7, .north = 20.5},
    {.code = "VN", .west = 102.1, .south = 8.4, .east = 109.5, .north = 23.4},
    {.code = "SG", .west = 103.6, .south = 1.15, .east = 104.1, .north = 1.5},
    {.code = "MY", .west = 99.6, .south = 0.8, .east = 119.3, .north = 7.4},
    {.code = "ID", .west = 95.0, .south = -11.1, .east = 141.0, .north = 5.9},
    {.code = "PH", .west = 116.9, .south = 4.6, .east = 126.6, .north = 21.2},
    {.code = "AU", .west = 112.9, .south = -43.7, .east = 153.7, .north = -10.6},
    {.code = "NZ", .west = 166.4, .south = -47.4, .east = 178.6, .north = -34.4},
}};

// render's Countries.zones: a country's time zone where it has one
constexpr std::array<CountryZone, 64> zones = {{
    {.code = "NL", .zone = "Europe/Amsterdam"},
    {.code = "BE", .zone = "Europe/Brussels"},
    {.code = "LU", .zone = "Europe/Luxembourg"},
    {.code = "DE", .zone = "Europe/Berlin"},
    {.code = "FR", .zone = "Europe/Paris"},
    {.code = "GB", .zone = "Europe/London"},
    {.code = "IE", .zone = "Europe/Dublin"},
    {.code = "ES", .zone = "Europe/Madrid"},
    {.code = "PT", .zone = "Europe/Lisbon"},
    {.code = "IT", .zone = "Europe/Rome"},
    {.code = "CH", .zone = "Europe/Zurich"},
    {.code = "AT", .zone = "Europe/Vienna"},
    {.code = "DK", .zone = "Europe/Copenhagen"},
    {.code = "NO", .zone = "Europe/Oslo"},
    {.code = "SE", .zone = "Europe/Stockholm"},
    {.code = "FI", .zone = "Europe/Helsinki"},
    {.code = "IS", .zone = "Atlantic/Reykjavik"},
    {.code = "PL", .zone = "Europe/Warsaw"},
    {.code = "CZ", .zone = "Europe/Prague"},
    {.code = "SK", .zone = "Europe/Bratislava"},
    {.code = "HU", .zone = "Europe/Budapest"},
    {.code = "RO", .zone = "Europe/Bucharest"},
    {.code = "BG", .zone = "Europe/Sofia"},
    {.code = "GR", .zone = "Europe/Athens"},
    {.code = "HR", .zone = "Europe/Zagreb"},
    {.code = "SI", .zone = "Europe/Ljubljana"},
    {.code = "RS", .zone = "Europe/Belgrade"},
    {.code = "EE", .zone = "Europe/Tallinn"},
    {.code = "LV", .zone = "Europe/Riga"},
    {.code = "LT", .zone = "Europe/Vilnius"},
    {.code = "UA", .zone = "Europe/Kyiv"},
    {.code = "BY", .zone = "Europe/Minsk"},
    {.code = "TR", .zone = "Europe/Istanbul"},
    {.code = "MT", .zone = "Europe/Malta"},
    {.code = "CY", .zone = "Asia/Nicosia"},
    {.code = "CU", .zone = "America/Havana"},
    {.code = "MX", .zone = "America/Mexico_City"},
    {.code = "BR", .zone = "America/Sao_Paulo"},
    {.code = "AR", .zone = "America/Argentina/Buenos_Aires"},
    {.code = "CL", .zone = "America/Santiago"},
    {.code = "PE", .zone = "America/Lima"},
    {.code = "CO", .zone = "America/Bogota"},
    {.code = "VE", .zone = "America/Caracas"},
    {.code = "ZA", .zone = "Africa/Johannesburg"},
    {.code = "EG", .zone = "Africa/Cairo"},
    {.code = "MA", .zone = "Africa/Casablanca"},
    {.code = "KE", .zone = "Africa/Nairobi"},
    {.code = "NG", .zone = "Africa/Lagos"},
    {.code = "ET", .zone = "Africa/Addis_Ababa"},
    {.code = "IL", .zone = "Asia/Jerusalem"},
    {.code = "SA", .zone = "Asia/Riyadh"},
    {.code = "AE", .zone = "Asia/Dubai"},
    {.code = "IR", .zone = "Asia/Tehran"},
    {.code = "IN", .zone = "Asia/Kolkata"},
    {.code = "PK", .zone = "Asia/Karachi"},
    {.code = "CN", .zone = "Asia/Shanghai"},
    {.code = "JP", .zone = "Asia/Tokyo"},
    {.code = "KR", .zone = "Asia/Seoul"},
    {.code = "TW", .zone = "Asia/Taipei"},
    {.code = "TH", .zone = "Asia/Bangkok"},
    {.code = "VN", .zone = "Asia/Ho_Chi_Minh"},
    {.code = "SG", .zone = "Asia/Singapore"},
    {.code = "MY", .zone = "Asia/Kuala_Lumpur"},
    {.code = "PH", .zone = "Asia/Manila"},
}};

// Each zone's UTC offset at noon on 1 October 2026, seconds (the IANA rules, as Python's zoneinfo gives them)
constexpr std::array<ZoneOffset, 88> offsets = {{
    {.zone = "Africa/Addis_Ababa", .seconds = 10800},   {.zone = "Africa/Cairo", .seconds = 10800},
    {.zone = "Africa/Casablanca", .seconds = 0},        {.zone = "Africa/Johannesburg", .seconds = 7200},
    {.zone = "Africa/Lagos", .seconds = 3600},          {.zone = "Africa/Nairobi", .seconds = 10800},
    {.zone = "America/Anchorage", .seconds = -28800},   {.zone = "America/Argentina/Buenos_Aires", .seconds = -10800},
    {.zone = "America/Bogota", .seconds = -18000},      {.zone = "America/Caracas", .seconds = -14400},
    {.zone = "America/Chicago", .seconds = -18000},     {.zone = "America/Denver", .seconds = -21600},
    {.zone = "America/Edmonton", .seconds = -21600},    {.zone = "America/Halifax", .seconds = -10800},
    {.zone = "America/Havana", .seconds = -14400},      {.zone = "America/Lima", .seconds = -18000},
    {.zone = "America/Los_Angeles", .seconds = -25200}, {.zone = "America/Mexico_City", .seconds = -21600},
    {.zone = "America/New_York", .seconds = -14400},    {.zone = "America/Santiago", .seconds = -10800},
    {.zone = "America/Sao_Paulo", .seconds = -10800},   {.zone = "America/Toronto", .seconds = -14400},
    {.zone = "America/Vancouver", .seconds = -25200},   {.zone = "America/Winnipeg", .seconds = -18000},
    {.zone = "Asia/Bangkok", .seconds = 25200},         {.zone = "Asia/Dubai", .seconds = 14400},
    {.zone = "Asia/Ho_Chi_Minh", .seconds = 25200},     {.zone = "Asia/Jakarta", .seconds = 25200},
    {.zone = "Asia/Jayapura", .seconds = 32400},        {.zone = "Asia/Jerusalem", .seconds = 10800},
    {.zone = "Asia/Karachi", .seconds = 18000},         {.zone = "Asia/Kolkata", .seconds = 19800},
    {.zone = "Asia/Krasnoyarsk", .seconds = 25200},     {.zone = "Asia/Kuala_Lumpur", .seconds = 28800},
    {.zone = "Asia/Makassar", .seconds = 28800},        {.zone = "Asia/Manila", .seconds = 28800},
    {.zone = "Asia/Nicosia", .seconds = 10800},         {.zone = "Asia/Riyadh", .seconds = 10800},
    {.zone = "Asia/Seoul", .seconds = 32400},           {.zone = "Asia/Shanghai", .seconds = 28800},
    {.zone = "Asia/Singapore", .seconds = 28800},       {.zone = "Asia/Taipei", .seconds = 28800},
    {.zone = "Asia/Tehran", .seconds = 12600},          {.zone = "Asia/Tokyo", .seconds = 32400},
    {.zone = "Asia/Vladivostok", .seconds = 36000},     {.zone = "Asia/Yakutsk", .seconds = 32400},
    {.zone = "Asia/Yekaterinburg", .seconds = 18000},   {.zone = "Atlantic/Reykjavik", .seconds = 0},
    {.zone = "Australia/Adelaide", .seconds = 34200},   {.zone = "Australia/Perth", .seconds = 28800},
    {.zone = "Australia/Sydney", .seconds = 36000},     {.zone = "Europe/Amsterdam", .seconds = 7200},
    {.zone = "Europe/Athens", .seconds = 10800},        {.zone = "Europe/Belgrade", .seconds = 7200},
    {.zone = "Europe/Berlin", .seconds = 7200},         {.zone = "Europe/Bratislava", .seconds = 7200},
    {.zone = "Europe/Brussels", .seconds = 7200},       {.zone = "Europe/Bucharest", .seconds = 10800},
    {.zone = "Europe/Budapest", .seconds = 7200},       {.zone = "Europe/Copenhagen", .seconds = 7200},
    {.zone = "Europe/Dublin", .seconds = 3600},         {.zone = "Europe/Helsinki", .seconds = 10800},
    {.zone = "Europe/Istanbul", .seconds = 10800},      {.zone = "Europe/Kyiv", .seconds = 10800},
    {.zone = "Europe/Lisbon", .seconds = 3600},         {.zone = "Europe/Ljubljana", .seconds = 7200},
    {.zone = "Europe/London", .seconds = 3600},         {.zone = "Europe/Luxembourg", .seconds = 7200},
    {.zone = "Europe/Madrid", .seconds = 7200},         {.zone = "Europe/Malta", .seconds = 7200},
    {.zone = "Europe/Minsk", .seconds = 10800},         {.zone = "Europe/Moscow", .seconds = 10800},
    {.zone = "Europe/Oslo", .seconds = 7200},           {.zone = "Europe/Paris", .seconds = 7200},
    {.zone = "Europe/Prague", .seconds = 7200},         {.zone = "Europe/Riga", .seconds = 10800},
    {.zone = "Europe/Rome", .seconds = 7200},           {.zone = "Europe/Sofia", .seconds = 10800},
    {.zone = "Europe/Stockholm", .seconds = 7200},      {.zone = "Europe/Tallinn", .seconds = 10800},
    {.zone = "Europe/Vienna", .seconds = 7200},         {.zone = "Europe/Vilnius", .seconds = 10800},
    {.zone = "Europe/Warsaw", .seconds = 7200},         {.zone = "Europe/Zagreb", .seconds = 7200},
    {.zone = "Europe/Zurich", .seconds = 7200},         {.zone = "Pacific/Auckland", .seconds = 46800},
    {.zone = "Pacific/Honolulu", .seconds = -36000},    {.zone = "UTC", .seconds = 0},
}};

} // namespace

std::optional<std::string_view> country_of(double lat, double lon) noexcept {
    const CountryBox* best = nullptr;
    for (const CountryBox& b : boxes) {
        if (b.has(lat, lon) && (best == nullptr || b.area() < best->area())) best = &b;
    }
    if (best == nullptr) return std::nullopt;
    return best->code;
}

std::string_view timezone_of(std::optional<std::string_view> code, double lat, double lon) noexcept {
    if (!code) return "UTC";
    const std::string_view c = *code;
    if (c == "US") {
        if (lon < -154 && lat < 23) return "Pacific/Honolulu";
        if (lat > 51 && lon < -130) return "America/Anchorage";
        if (lon < -114.5) return "America/Los_Angeles";
        if (lon < -101) return "America/Denver";
        if (lon < -87) return "America/Chicago";
        return "America/New_York";
    }
    if (c == "CA") {
        if (lon < -114) return "America/Vancouver";
        if (lon < -101) return "America/Edmonton";
        if (lon < -87) return "America/Winnipeg";
        if (lon < -60) return "America/Toronto";
        return "America/Halifax";
    }
    if (c == "AU") {
        if (lon < 129) return "Australia/Perth";
        if (lon < 141) return "Australia/Adelaide";
        return "Australia/Sydney";
    }
    if (c == "RU") {
        if (lon < 60) return "Europe/Moscow";
        if (lon < 90) return "Asia/Yekaterinburg";
        if (lon < 120) return "Asia/Krasnoyarsk";
        if (lon < 140) return "Asia/Yakutsk";
        return "Asia/Vladivostok";
    }
    if (c == "ID") {
        if (lon < 114) return "Asia/Jakarta";
        if (lon < 135) return "Asia/Makassar";
        return "Asia/Jayapura";
    }
    if (c == "NZ") return "Pacific/Auckland";
    for (const CountryZone& z : zones) {
        if (z.code == c) return z.zone;
    }
    return "UTC";
}

std::optional<std::int32_t> zone_hours(std::string_view zone) noexcept {
    for (const ZoneOffset& z : offsets) {
        if (z.zone == zone) {
            // Python's // rounds toward minus infinity
            const std::int32_t q = z.seconds / 3600;
            return (z.seconds % 3600 != 0 && z.seconds < 0) ? q - 1 : q;
        }
    }
    return std::nullopt;
}

std::int32_t tz_hours(std::string_view zone) noexcept { return zone_hours(zone).value_or(0); }

std::optional<std::string_view> tradition_of(std::optional<std::string_view> code) noexcept {
    if (!code) return std::nullopt;
    if (*code == "NL") return "nl_amsterdam";
    if (*code == "FR") return "fr_paris";
    if (*code == "IT") return "it_tuscan";
    if (*code == "NO") return "no_timber";
    if (*code == "GB") return "gb_terrace";
    return std::nullopt;
}

} // namespace atlas::load
