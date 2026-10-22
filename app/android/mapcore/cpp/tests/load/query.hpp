// What the renders' query of Overpass returns for a box, from a region's data (tests of the windows the phone builds,
// load/Window.hpp): the tagged nodes in it, every way that meets it, the multipolygons and parks one of whose ways
// meets it, their members whole.
#pragma once

#include "osm/Overpass.hpp"

#include <algorithm>
#include <array>
#include <span>

namespace atlas::test {

namespace query_detail {

/// Whether a point lies in a box [south, west, north, east].
[[nodiscard]] inline bool inside(const std::array<double, 4>& b, double lat, double lon) {
    return lat >= b[0] && lat <= b[2] && lon >= b[1] && lon <= b[3];
}

/// Whether the segment (p, q) meets the box (Liang-Barsky).
[[nodiscard]] inline bool crosses(const std::array<double, 4>& b, const osm::LatLon& p, const osm::LatLon& q) {
    double t0 = 0.0;
    double t1 = 1.0;
    const double dx = q.lon - p.lon;
    const double dy = q.lat - p.lat;
    const std::array<std::array<double, 2>, 4> sides = {
        {{-dx, p.lon - b[1]}, {dx, b[3] - p.lon}, {-dy, p.lat - b[0]}, {dy, b[2] - p.lat}}};
    for (const auto& [pk, qk] : sides) {
        if (pk == 0.0) {
            if (qk < 0.0) return false;
            continue;
        }
        const double r = qk / pk;
        if (pk < 0.0) {
            t0 = std::max(t0, r);
        } else {
            t1 = std::min(t1, r);
        }
        if (t0 > t1) return false;
    }
    return true;
}

/// Whether a line meets the box: a point in it, or a segment across it.
[[nodiscard]] inline bool meets(const std::array<double, 4>& b, std::span<const osm::LatLon> line) {
    for (std::size_t i = 0; i < line.size(); ++i) {
        if (inside(b, line[i].lat, line[i].lon)) return true;
        if (i > 0 && crosses(b, line[i - 1], line[i])) return true;
    }
    return false;
}

} // namespace query_detail

/// The elements of a region's data the renders' Overpass query returns for box `b` (they view `region`).
[[nodiscard]] inline osm::Document query(const osm::Document& region, const std::array<double, 4>& b) {
    using namespace query_detail;
    return osm::select(region, [&](const osm::Element& e) {
        switch (e.kind) {
        case osm::Kind::node: return e.has_at && e.has_tags && !e.tags.empty() && inside(b, e.at.lat, e.at.lon);
        case osm::Kind::way: return meets(b, e.geometry);
        case osm::Kind::relation: {
            const bool multipolygon = std::ranges::any_of(e.tags, [](const osm::Tag& t) {
                return (t.key == "type" && t.value == "multipolygon") ||
                       (t.key == "boundary" && (t.value == "national_park" || t.value == "protected_area"));
            });
            return multipolygon && std::ranges::any_of(e.members, [&](const osm::Member& m) {
                       return m.kind == osm::Kind::way && meets(b, m.geometry);
                   });
        }
        case osm::Kind::other: return false;
        }
        return false;
    });
}

} // namespace atlas::test
