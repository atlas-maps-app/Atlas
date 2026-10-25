#include "load/Regions.hpp"

#include "base/pymath/PyMath.hpp"
#include "geometry/RoofShapes.hpp"
#include "load/Generic.hpp"
#include "load/Real.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <initializer_list>
#include <optional>
#include <set>
#include <span>
#include <string_view>
#include <tuple>
#include <utility>
#include <vector>

namespace atlas::load {

namespace {

using geometry::centroid;
using geometry::dist;
using geometry::pip;
using geometry::signed_area;
using LL = osm::LatLon;

/// Python's tuple equality of two (lat, lon).
[[nodiscard]] bool same(const LL& a, const LL& b) noexcept { return a.lat == b.lat && a.lon == b.lon; }

[[nodiscard]] bool one_of(std::optional<std::string_view> v, std::initializer_list<std::string_view> set) noexcept {
    return v.has_value() && std::ranges::find(set, *v) != set.end();
}

/// regions.py's LAND: each kind of land cover and its draw order (the colours are the style's).
struct LandKind {
    std::string_view kind;
    int order;
};
constexpr std::array<LandKind, 43> land_kinds = {{
    {.kind = "farmland", .order = 0},
    {.kind = "residential", .order = 0},
    {.kind = "industrial", .order = 0},
    {.kind = "port", .order = 1},
    {.kind = "commercial", .order = 0},
    {.kind = "retail", .order = 0},
    {.kind = "construction", .order = 0},
    {.kind = "railway", .order = 0},
    {.kind = "military", .order = 0},
    {.kind = "brownfield", .order = 0},
    {.kind = "greenfield", .order = 0},
    {.kind = "education", .order = 0},
    {.kind = "static_caravan", .order = 1},
    {.kind = "farmyard", .order = 1},
    {.kind = "meadow", .order = 1},
    {.kind = "grass", .order = 2},
    {.kind = "grassland", .order = 2},
    {.kind = "village_green", .order = 2},
    {.kind = "recreation_ground", .order = 2},
    {.kind = "park", .order = 2},
    {.kind = "garden", .order = 2},
    {.kind = "cemetery", .order = 2},
    {.kind = "allotments", .order = 2},
    {.kind = "orchard", .order = 2},
    {.kind = "golf_course", .order = 2},
    {.kind = "heath", .order = 2},
    {.kind = "scrub", .order = 3},
    {.kind = "forest", .order = 3},
    {.kind = "wood", .order = 3},
    {.kind = "wetland", .order = 3},
    {.kind = "sand", .order = 4},
    {.kind = "beach", .order = 4},
    {.kind = "dune", .order = 4},
    {.kind = "bare_rock", .order = 4},
    {.kind = "stone", .order = 4},
    {.kind = "scree", .order = 4},
    {.kind = "apron", .order = 5},
    {.kind = "parking", .order = 5},
    {.kind = "pedestrian", .order = 5},
    {.kind = "playground", .order = 5},
    {.kind = "pitch", .order = 5},
    {.kind = "track", .order = 5},
    {.kind = "flowerbed", .order = 5},
}};

[[nodiscard]] const LandKind* land_of(std::optional<std::string_view> kind) noexcept {
    if (!kind) return nullptr;
    const auto* const it = std::ranges::find(land_kinds, *kind, &LandKind::kind);
    return it != land_kinds.end() ? it : nullptr;
}

/// regions.py's `land_kind(t)`.
[[nodiscard]] std::optional<std::string_view> land_kind(const Tags& t) {
    if (t.is("aeroway", "apron")) return "apron";
    if (t.is("amenity", "parking") && !t.is("parking", "underground")) return "parking";
    if (one_of(t.get("leisure"), {"pitch", "park", "garden", "golf_course", "recreation_ground", "track"})) {
        return t.get("leisure");
    }
    if (t.is("industrial", "port") || t.is("landuse", "port")) return "port";
    if (land_of(t.get("natural")) != nullptr) return t.get("natural");
    if (land_of(t.get("landuse")) != nullptr) return t.get("landuse");
    return std::nullopt;
}

constexpr std::array<std::string_view, 5> paris_old = {"#D9CDB4", "#E2D7C1", "#D4C6AA", "#DDD2BC", "#CFC2A6"};
constexpr std::array<std::string_view, 2> paris_plaster = {"#E8E0D0", "#EEE7D9"};
constexpr std::array<std::string_view, 3> paris_new = {"#CFC8BC", "#C8C2B8", "#D8D2C6"};
constexpr std::array<std::string_view, 2> schiphol_old = {"#9C8E80", "#8F8780"};
constexpr std::array<std::string_view, 1> schiphol_plaster = {"#E6E2DA"};
constexpr std::array<std::string_view, 5> schiphol_new = {"#B8B6B1", "#A9A7A2", "#C4C2BD", "#9FA3A8", "#8E949A"};
constexpr std::array<std::string_view, 1> port_old = {"#9C8E80"};
constexpr std::array<std::string_view, 6> port_new = {"#B8B6B1", "#A9A7A2", "#C4C2BD", "#9FA3A8", "#7E8C96", "#B9B1A2"};

/// `round(v, 1)` as a set member: -0.0 counts as 0.0.
[[nodiscard]] double r1(double v) noexcept { return pymath::round(v, 1) + 0.0; }

/// Reads positions into the region's frame, refusing the first that is off the Earth.
class Frame {
public:
    explicit Frame(const tiles::Frame& f) noexcept : f_(f) {}

    [[nodiscard]] V2 xz(LL p, std::int64_t element) {
        const bool on_earth = p.lat >= -90.0 && p.lat <= 90.0 && p.lon >= -180.0 && p.lon <= 180.0; // NaN is not
        if (!on_earth && !error_) error_ = LoadError{.fault = LoadError::Fault::off_earth, .element = element};
        return load::xz(f_, p.lat, p.lon);
    }

    [[nodiscard]] std::vector<V2> points(std::span<const LL> g, std::int64_t element) {
        std::vector<V2> out;
        out.reserve(g.size());
        for (const LL& p : g) out.push_back(xz(p, element));
        return out;
    }

    /// regions.py's `join_rings(ways)`: member ways joined end to end into closed rings of (lat, lon).
    [[nodiscard]] static std::vector<std::vector<LL>> join_rings(const std::vector<std::span<const LL>>& ways) {
        std::vector<std::vector<LL>> segs;
        for (const auto& w : ways) {
            if (w.size() > 1) segs.emplace_back(w.begin(), w.end());
        }
        std::vector<std::vector<LL>> rings;
        while (!segs.empty()) {
            std::vector<LL> cur = std::move(segs.front());
            segs.erase(segs.begin());
            bool changed = true;
            while (!same(cur.front(), cur.back()) && changed) {
                changed = false;
                for (std::size_t i = 0; i < segs.size(); ++i) {
                    const std::vector<LL>& s = segs[i];
                    std::vector<LL> next;
                    if (same(s.front(), cur.back())) {
                        next = cur;
                        next.insert(next.end(), s.begin() + 1, s.end());
                    } else if (same(s.back(), cur.back())) {
                        next = cur;
                        next.insert(next.end(), s.rbegin() + 1, s.rend());
                    } else if (same(s.back(), cur.front())) {
                        next = s;
                        next.insert(next.end(), cur.begin() + 1, cur.end());
                    } else if (same(s.front(), cur.front())) {
                        next.assign(s.rbegin(), s.rend());
                        next.insert(next.end(), cur.begin() + 1, cur.end());
                    } else {
                        continue;
                    }
                    cur = std::move(next);
                    segs.erase(segs.begin() + static_cast<std::ptrdiff_t>(i));
                    changed = true;
                    break;
                }
            }
            if (cur.size() > 3 && same(cur.front(), cur.back())) rings.push_back(std::move(cur));
        }
        return rings;
    }

    /// regions.py's `rings_of(e, role)`: a closed way, or a relation's outer (or inner) member ways joined.
    [[nodiscard]] std::vector<std::vector<V2>> rings_of(const osm::Document& doc, const osm::Element& e,
                                                        bool inner = false) {
        std::vector<std::vector<V2>> out;
        if (e.kind == osm::Kind::way && e.geometry.count != 0) {
            const auto g = doc.geometry(e);
            if (g.size() > 3 && same(g.front(), g.back())) out.push_back(points(g, e.id));
            return out;
        }
        if (e.kind != osm::Kind::relation) return out;
        std::vector<std::span<const LL>> ways;
        for (const osm::Member& m : doc.members(e)) {
            const std::string_view role = m.has_role ? doc.text(m.role) : "outer";
            const bool taken = inner ? role == "inner" : (role == "outer" || role.empty());
            if (m.kind == osm::Kind::way && taken) ways.push_back(doc.geometry(m));
        }
        for (const std::vector<LL>& r : join_rings(ways)) out.push_back(points(r, e.id));
        return out;
    }

    [[nodiscard]] const std::optional<LoadError>& error() const noexcept { return error_; }
    [[nodiscard]] const tiles::Frame& frame() const noexcept { return f_; }

private:
    tiles::Frame f_;
    std::optional<LoadError> error_;
};

/// A building record as regions.py writes one for a relation (its `src` reads the tags as they are).
[[nodiscard]] Building relation_building(const osm::Element& e, const Tags& t, std::vector<V2> r,
                                         const Palette& palette) {
    Building b;
    b.poly = std::move(r);
    b.id = e.id;
    b.tags = t;
    std::optional<double> h = num(t.get("height"));
    const std::optional<double> lv = num(t.get("building:levels"));
    if (!h && lv) h = *lv * 3.0 + num(t.get("roof:levels")).value_or(0.0) * 2.0;
    b.src = t.truthy("height") ? HeightSource::height
                               : (lv.has_value() && *lv != 0.0 ? HeightSource::levels : HeightSource::none);
    b.year = leading_year(t.get("start_date").value_or(""));
    b.min = num(t.get("min_height")).value_or(0.0);
    b.roof = t.get("roof:shape");
    b.roof_h = num(t.get("roof:height"));
    b.orient = t.get("roof:orientation");
    b.mat = t.get("building:material");
    b.rmat = t.get("roof:material");
    b.col = t.get("building:colour");
    b.rcol = t.get("roof:colour");
    b.relation = true;
    guess_tags(b, h, palette);
    b.bb = box_of(b.poly);
    return b;
}

/// Buildings and parts mapped as multipolygon relations: their outer ways joined into rings, each one not already a
/// building's or part's (by its first point to the decimetre), guess_tags's values, `hidden` within 120 m.
void relation_buildings(const osm::Document& doc, Scene& s, Frame& frame, const Palette& palette) {
    std::set<std::pair<double, double>> have;
    for (const auto* list : {&s.buildings, &s.parts}) {
        for (const Building& b : *list) have.emplace(r1(b.poly[0].x), r1(b.poly[0].z));
    }
    std::vector<Building> buildings;
    std::vector<Building> parts;
    for (const osm::Element& e : doc.elements()) {
        if (e.kind != osm::Kind::relation) continue;
        const Tags t(doc.tags(e));
        if (!(t.truthy("building") || t.truthy("building:part")) || t.is("building", "no")) continue;
        for (std::vector<V2>& r : frame.rings_of(doc, e)) {
            if (dist(r.front(), r.back()) < 0.5) r.pop_back();
            if (signed_area(r) < 0) std::ranges::reverse(r);
            if (std::fabs(signed_area(r)) < 3 || have.contains({r1(r[0].x), r1(r[0].z)})) continue;
            (t.truthy("building:part") ? parts : buildings).push_back(relation_building(e, t, std::move(r), palette));
        }
    }
    if (buildings.empty() && parts.empty()) return;
    std::vector<V2> centres;
    for (const auto* list : {&s.parts, &parts}) {
        for (const Building& p : *list) centres.push_back(centroid(p.poly));
    }
    for (Building& b : buildings) {
        const V2 cb = centroid(b.poly);
        b.hidden = std::ranges::any_of(centres, [&](const V2& c) {
            return std::fabs(c.x - cb.x) < 120 && std::fabs(c.z - cb.z) < 120 && pip(c.x, c.z, b.poly);
        });
    }
    for (Building& b : buildings) s.buildings.push_back(std::move(b));
    for (Building& p : parts) s.parts.push_back(std::move(p));
}

/// Roof shapes the renders do not draw are drawn as the closest one they do; a mansard as itself, with a height.
void closest_roofs(Scene& s) {
    struct Closest {
        std::string_view from;
        std::string_view to;
    };
    constexpr std::array<Closest, 8> closest = {{
        {.from = "gambrel", .to = "gabled"},
        {.from = "dome", .to = "pyramidal"},
        {.from = "onion", .to = "pyramidal"},
        {.from = "round", .to = "gabled"},
        {.from = "half-hipped", .to = "hipped"},
        {.from = "saltbox", .to = "gabled"},
        {.from = "cone", .to = "pyramidal"},
        {.from = "mansard", .to = "mansard"},
    }};
    for (auto* list : {&s.buildings, &s.parts}) {
        for (Building& b : *list) {
            if (!b.roof) continue;
            const auto* const c = std::ranges::find(closest, *b.roof, &Closest::from);
            if (c == closest.end()) continue;
            b.roof = c->to;
            const bool roof_h = b.roof_h.has_value() && *b.roof_h != 0.0;
            if (!roof_h && b.h != 0.0) {
                const double rl = num(b.tags.get("roof:levels")).value_or(0.0);
                b.roof_h = pymath::min(b.h * 0.35, rl != 0.0 ? rl * 2.8 : 3.5);
                b.guessed.add(Guess::roof_height);
            }
        }
    }
}

/// What only regions draw, from the extract, and the coastline's ways.
void region_features(const osm::Document& doc, Scene& s, Frame& frame, std::string_view name,
                     std::vector<std::span<const LL>>& coast) {
    for (const osm::Element& e : doc.elements()) {
        if (!e.has_tags || e.tags.count == 0) continue;
        const Tags t(doc.tags(e));
        const bool way = e.kind == osm::Kind::way;
        const bool node = e.kind == osm::Kind::node;
        const bool geo = e.geometry.count != 0;
        if (t.is("natural", "coastline") && way) {
            if (e.has_geometry) coast.push_back(doc.geometry(e));
            continue;
        }
        if (t.is("boundary", "national_park") || (t.is("boundary", "protected_area") && name == "dunes")) {
            std::uint32_t part = 0;
            for (std::vector<V2>& r : frame.rings_of(doc, e)) {
                s.park.push_back(
                    {.ring = std::move(r), .tags = t, .from = {.kind = e.kind, .id = e.id, .part = part++}});
            }
            continue;
        }
        if (const auto k = land_kind(t); k && !node && !t.truthy("building")) {
            std::uint32_t part = 0;
            for (std::vector<V2>& r : frame.rings_of(doc, e)) {
                if (std::fabs(signed_area(r)) > 4) {
                    s.land.push_back({.poly = std::move(r),
                                      .kind = *k,
                                      .tags = t,
                                      .id = e.id,
                                      .from = {.kind = e.kind, .id = e.id, .part = part}});
                }
                ++part;
            }
            continue;
        }
        if (way && one_of(t.get("aeroway"), {"runway", "taxiway", "taxilane", "parking_position", "stopway"}) && geo) {
            s.aero.push_back({.pts = frame.points(doc.geometry(e), e.id),
                              .kind = *t.get("aeroway"),
                              .tags = t,
                              .id = e.id,
                              .from = {.kind = e.kind, .id = e.id}});
            continue;
        }
        const auto underground = [&] {
            return one_of(t.get("tunnel"), {"yes", "building_passage"}) || num(t.get("layer")).value_or(0.0) < 0;
        };
        if (way &&
            one_of(t.get("highway"),
                   {"motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary_link"}) &&
            geo) {
            if (underground()) continue;
            s.big.push_back({.pts = frame.points(doc.geometry(e), e.id),
                             .tags = t,
                             .hw = *t.get("highway"),
                             .layer = num(t.get("layer")).value_or(0.0),
                             .id = e.id,
                             .from = {.kind = e.kind, .id = e.id}});
            continue;
        }
        const bool crane = t.is("man_made", "crane_rail") || t.is("railway", "crane");
        if (way && (one_of(t.get("railway"), {"rail", "light_rail", "subway", "narrow_gauge"}) || crane) && geo) {
            if (underground()) continue;
            s.rail.push_back({.pts = frame.points(doc.geometry(e), e.id),
                              .tags = t,
                              .crane = crane,
                              .from = {.kind = e.kind, .id = e.id}});
            continue;
        }
        if (way && one_of(t.get("power"), {"line", "minor_line"}) && geo) {
            s.power.push_back(
                {.pts = frame.points(doc.geometry(e), e.id), .tags = t, .from = {.kind = e.kind, .id = e.id}});
            continue;
        }
        if (one_of(t.get("man_made"), {"breakwater", "groyne", "pier", "quay"}) && way && geo) {
            s.marine.push_back({.pts = frame.points(doc.geometry(e), e.id),
                                .kind = *t.get("man_made"),
                                .tags = t,
                                .closed = same(doc.geometry(e).front(), doc.geometry(e).back()),
                                .from = {.kind = e.kind, .id = e.id}});
            continue;
        }
        if (t.is("man_made", "crane")) {
            if (node && e.has_at) {
                const V2 p = frame.xz(e.at, e.id);
                s.cranes.push_back({.x = p.x, .z = p.z, .tags = t, .id = e.id});
            } else if (!node && geo) {
                std::vector<V2> pts = frame.points(doc.geometry(e), e.id);
                pymath::Summer sx;
                pymath::Summer sz;
                for (const V2& p : pts) {
                    sx.add(p.x);
                    sz.add(p.z);
                }
                const auto n = static_cast<double>(pts.size());
                s.cranes.push_back(
                    {.x = sx.value() / n, .z = sz.value() / n, .tags = t, .id = e.id, .poly = std::move(pts)});
            }
            continue;
        }
        if (t.is("man_made", "mast") && node) {
            if (e.has_at) {
                const V2 p = frame.xz(e.at, e.id);
                s.masts.push_back({.x = p.x, .z = p.z, .tags = t, .id = e.id, .h = num(t.get("height"))});
            }
            continue;
        }
        if (one_of(t.get("man_made"), {"gantry", "goods_conveyor"}) && way && geo &&
            !one_of(t.get("location"), {"underground", "underwater"})) {
            (t.is("man_made", "gantry") ? s.gantries : s.conveyors)
                .push_back(
                    {.pts = frame.points(doc.geometry(e), e.id), .tags = t, .id = e.id, .h = num(t.get("height"))});
            continue;
        }
        if (node && t.is("power", "generator") && t.is("generator:source", "wind")) {
            if (e.has_at) {
                const V2 p = frame.xz(e.at, e.id);
                s.turbines.push_back({.x = p.x,
                                      .z = p.z,
                                      .tags = t,
                                      .id = e.id,
                                      .h = num(t.get("height")),
                                      .rotor = num(t.get("rotor:diameter"))});
            }
            continue;
        }
        if (node && t.is("man_made", "tower")) {
            const auto h = num(t.get("height"));
            if (h && *h != 0.0) {
                if (e.has_at) {
                    const V2 p = frame.xz(e.at, e.id);
                    s.mtowers.push_back({.x = p.x, .z = p.z, .tags = t, .id = e.id, .h = h});
                }
                continue;
            }
        }
        if (node && one_of(t.get("power"), {"tower", "pole"}) && e.has_at) {
            const V2 p = frame.xz(e.at, e.id);
            const auto it = std::ranges::find(s.towers, e.id, &Tower::id);
            if (it != s.towers.end()) {
                *it = {.id = e.id, .at = p, .h = num(t.get("height"))};
            } else {
                s.towers.push_back({.id = e.id, .at = p, .h = num(t.get("height"))});
            }
        }
    }
}

/// regions.py's `join_open(chains)`: open chains joined end to start.
[[nodiscard]] std::vector<std::vector<LL>> join_open(std::vector<std::vector<LL>> chains) {
    std::erase_if(chains, [](const std::vector<LL>& c) { return c.size() <= 1; });
    std::vector<std::vector<LL>> out;
    while (!chains.empty()) {
        std::vector<LL> cur = std::move(chains.front());
        chains.erase(chains.begin());
        bool changed = true;
        while (changed) {
            changed = false;
            for (std::size_t i = 0; i < chains.size(); ++i) {
                const std::vector<LL>& c = chains[i];
                if (same(c.front(), cur.back())) {
                    cur.insert(cur.end(), c.begin() + 1, c.end());
                } else if (same(c.back(), cur.front())) {
                    std::vector<LL> next = c;
                    next.insert(next.end(), cur.begin() + 1, cur.end());
                    cur = std::move(next);
                } else {
                    continue;
                }
                chains.erase(chains.begin() + static_cast<std::ptrdiff_t>(i));
                changed = true;
                break;
            }
        }
        out.push_back(std::move(cur));
    }
    return out;
}

/// regions.py's `sea_from_coast(coast_ways, bbox)`: the coastline (land on its left) closed along a box well beyond
/// the extract into water polygons.
[[nodiscard]] std::vector<std::vector<V2>> sea_from_coast(const std::vector<std::span<const LL>>& coast_ways,
                                                          const std::array<double, 4>& bbox, Frame& frame) {
    const double s = bbox[0] - 0.06;
    const double w = bbox[1] - 0.1;
    const double n = bbox[2] + 0.06;
    const double e = bbox[3] + 0.1;
    std::vector<std::vector<LL>> chains;
    chains.reserve(coast_ways.size());
    for (const auto& c : coast_ways) chains.emplace_back(c.begin(), c.end());
    std::vector<std::vector<LL>> segs = join_open(std::move(chains));
    const auto comp = [](const LL& p, int k) { return k == 0 ? p.lat : p.lon; };
    const std::array<std::pair<int, double>, 4> limits = {{{0, s}, {0, n}, {1, w}, {1, e}}};
    // from p, straight on away from q, until the box's edge
    const auto extend = [&](const LL& p, const LL& q) -> std::optional<LL> {
        const double dla = p.lat - q.lat;
        const double dlo = p.lon - q.lon;
        std::optional<std::pair<double, LL>> best;
        for (const auto& [k, lim] : limits) {
            const double dk = k == 0 ? dla : dlo;
            if (std::fabs(dk) < 1e-12) continue;
            const double t = (lim - comp(p, k)) / dk;
            if (t <= 0) continue;
            const LL r{.lat = p.lat + dla * t, .lon = p.lon + dlo * t};
            if (s - 1e-9 <= r.lat && r.lat <= n + 1e-9 && w - 1e-9 <= r.lon && r.lon <= e + 1e-9 &&
                (!best || t < best->first)) {
                best = std::pair{t, r};
            }
        }
        if (!best) return std::nullopt;
        return best->second;
    };
    for (std::vector<LL>& c : segs) {
        if (c.size() > 1 && !same(c.front(), c.back())) {
            if (s < c.front().lat && c.front().lat < n && w < c.front().lon && c.front().lon < e) {
                if (const auto r = extend(c[0], c[1])) c.insert(c.begin(), *r);
            }
            if (s < c.back().lat && c.back().lat < n && w < c.back().lon && c.back().lon < e) {
                if (const auto r = extend(c[c.size() - 1], c[c.size() - 2])) c.push_back(*r);
            }
        }
    }
    // every chain clipped to the box, keeping the pieces that run inside it
    const auto inside = [&](const LL& p) { return s <= p.lat && p.lat <= n && w <= p.lon && p.lon <= e; };
    const auto cross = [&](const LL& a, const LL& b) -> std::optional<LL> {
        std::optional<std::pair<double, LL>> best;
        for (const auto& [k, lim] : limits) {
            if ((comp(a, k) - lim) * (comp(b, k) - lim) < 0) {
                const double t = (lim - comp(a, k)) / (comp(b, k) - comp(a, k));
                const LL q{.lat = a.lat + (b.lat - a.lat) * t, .lon = a.lon + (b.lon - a.lon) * t};
                const LL qc{.lat = pymath::min(pymath::max(q.lat, s), n), .lon = pymath::min(pymath::max(q.lon, w), e)};
                if (inside(qc) && (!best || t < best->first)) best = std::pair{t, qc};
            }
        }
        if (!best) return std::nullopt;
        return best->second;
    };
    std::vector<std::vector<LL>> pieces;
    for (const std::vector<LL>& c : segs) {
        std::vector<LL> cur;
        for (std::size_t i = 0; i < c.size(); ++i) {
            const LL& p = c[i];
            if (inside(p)) {
                if (cur.empty() && i > 0) {
                    if (const auto q = cross(c[i - 1], p)) cur.push_back(*q);
                }
                cur.push_back(p);
            } else if (!cur.empty()) {
                if (const auto q = cross(c[i - 1], p)) cur.push_back(*q);
                pieces.push_back(std::move(cur));
                cur.clear();
            }
        }
        if (!cur.empty()) pieces.push_back(std::move(cur));
    }
    std::erase_if(pieces, [](const std::vector<LL>& p) { return p.size() <= 1; });
    if (pieces.empty()) return {};
    // the box's edge walked clockwise (lat up, lon right) from each exit to the next entry: the water on the right
    const double width = e - w;
    const double height = n - s;
    const auto perim = [&](const LL& p) {
        if (std::fabs(p.lat - n) < 1e-9) return p.lon - w;
        if (std::fabs(p.lon - e) < 1e-9) return width + (n - p.lat);
        if (std::fabs(p.lat - s) < 1e-9) return width + height + (e - p.lon);
        return 2 * width + height + (p.lat - s);
    };
    const double total = 2 * ((e - w) + (n - s));
    std::vector<std::pair<double, LL>> cpos;
    for (const LL& c :
         {LL{.lat = n, .lon = w}, LL{.lat = n, .lon = e}, LL{.lat = s, .lon = e}, LL{.lat = s, .lon = w}}) {
        cpos.emplace_back(perim(c), c);
    }
    std::vector<std::vector<LL>> open;
    for (std::vector<LL>& p : pieces) {
        const bool closed = p.size() > 3 && same(p.front(), p.back()); // a closed piece is no sea's edge
        if (!closed) open.push_back(std::move(p));
    }
    std::vector<std::vector<V2>> polys;
    std::vector<bool> used(open.size(), false);
    for (std::size_t i0 = 0; i0 < open.size(); ++i0) {
        if (used[i0]) continue;
        std::vector<LL> poly;
        std::size_t i = i0;
        for (std::size_t step = 0; step < open.size() + 1; ++step) {
            used[i] = true;
            poly.insert(poly.end(), open[i].begin(), open[i].end());
            const double x = perim(open[i].back());
            // the next entry clockwise from this exit
            double dj = 0.0;
            std::size_t j = open.size();
            for (std::size_t jj = 0; jj < open.size(); ++jj) {
                const double dd = pymath::mod(perim(open[jj].front()) - x, total);
                if (j == open.size() || dd < dj) {
                    dj = dd;
                    j = jj;
                }
            }
            std::vector<std::pair<double, LL>> corners = cpos;
            std::ranges::stable_sort(corners, {}, [&](const auto& c) { return pymath::mod(c.first - x, total); });
            for (const auto& [cp, c] : corners) {
                if (pymath::mod(cp - x, total) < dj) poly.push_back(c);
            }
            if (j == i0) break;
            i = j;
        }
        if (poly.size() > 2) {
            // its corners lie beyond the extract's box: not a mapped position, so not refused off the Earth
            std::vector<V2> pts;
            pts.reserve(poly.size());
            for (const LL& p : poly) pts.push_back(load::xz(frame.frame(), p.lat, p.lon));
            polys.push_back(std::move(pts));
        }
    }
    return polys;
}

/// Water mapped as multipolygons (outer rings not already loaded, by first point and length), its islands, the bays,
/// which shores are natural, and the sea from the coastline.
void region_water(const osm::Document& doc, Scene& s, Frame& frame, const RegionConfig& region,
                  const std::vector<std::span<const LL>>& coast) {
    std::set<std::tuple<double, double, std::size_t>> seen;
    for (const std::vector<V2>& r : s.water) seen.emplace(r1(r[0].x), r1(r[0].z), r.size());
    std::vector<const osm::Element*> bays;
    for (const osm::Element& e : doc.elements()) {
        const Tags t(doc.tags(e));
        const bool relation = e.kind == osm::Kind::relation;
        if (relation && (t.is("natural", "water") || one_of(t.get("waterway"), {"riverbank", "dock", "canal"}))) {
            for (std::vector<V2>& r : frame.rings_of(doc, e)) {
                if (!seen.contains({r1(r[0].x), r1(r[0].z), r.size()})) s.water.push_back(std::move(r));
            }
            for (std::vector<V2>& r : frame.rings_of(doc, e, true)) s.water_holes.push_back(std::move(r));
        }
        if (one_of(t.get("natural"), {"bay", "strait"}) && (relation || e.kind == osm::Kind::way)) bays.push_back(&e);
    }
    const bool quays = region.name == "paris" || region.name == "port";
    s.natural_shore.assign(s.water.size(), !quays);
    std::vector<std::vector<V2>> seas = sea_from_coast(coast, region.bbox, frame);
    if (!seas.empty() && !s.buildings.empty()) {
        // a coastline closed round the wrong side covers the town: no sea holds more than a few buildings
        std::vector<V2> cents;
        const std::size_t step = std::max<std::size_t>(1, s.buildings.size() / 400);
        for (std::size_t i = 0; i < s.buildings.size(); i += step) cents.push_back(centroid(s.buildings[i].poly));
        const double most = pymath::max(2.0, 0.03 * static_cast<double>(cents.size()));
        std::erase_if(seas, [&](const std::vector<V2>& poly) {
            const auto in = std::ranges::count_if(cents, [&](const V2& c) { return pip(c.x, c.z, poly); });
            return !(static_cast<double>(in) <= most);
        });
    }
    for (std::vector<V2>& poly : seas) {
        s.water.push_back(std::move(poly));
        s.natural_shore.push_back(region.name != "bergen"); // Bergen's harbour is quays all round
    }
    if (seas.empty()) {
        for (const osm::Element* e : bays) {
            for (std::vector<V2>& r : frame.rings_of(doc, *e)) {
                if (std::fabs(signed_area(r)) > 2000) {
                    s.water.push_back(std::move(r)); // a harbour's edges are quays, drawn as such
                    s.natural_shore.push_back(false);
                }
            }
        }
    }
}

/// Each part's outline: the first building whose box and ring hold its centroid; a church's parts are in it.
void outlines(Scene& s) {
    for (Building& p : s.parts) {
        const V2 c = centroid(p.poly);
        for (const Building& b : s.buildings) {
            if (b.bb.x0 <= c.x && c.x <= b.bb.x1 && b.bb.z0 <= c.z && c.z <= b.bb.z1 && pip(c.x, c.z, b.poly)) {
                p.parent_id = b.id;
                if (one_of(b.tags.get("building"), {"church", "cathedral", "chapel"}) ||
                    b.tags.is("amenity", "place_of_worship")) {
                    p.in_church = true;
                }
                break;
            }
        }
    }
}

} // namespace

Palette region_palette(std::string_view name) noexcept {
    if (name == "paris") return {.brick_old = paris_old, .plaster = paris_plaster, .brick_new = paris_new};
    if (name == "schiphol") return {.brick_old = schiphol_old, .plaster = schiphol_plaster, .brick_new = schiphol_new};
    if (name == "port") return {.brick_old = port_old, .plaster = schiphol_plaster, .brick_new = port_new};
    return amsterdam_palette();
}

std::vector<V2> simplify(std::span<const V2> pts, double tol) {
    if (pts.size() < 6) return {pts.begin(), pts.end()};
    std::vector<V2> ring;
    const auto dp = [&](auto&& self, std::span<const V2> seg) -> void {
        if (seg.size() < 3) {
            ring.push_back(seg[0]);
            return;
        }
        const double x0 = seg.front().x;
        const double z0 = seg.front().z;
        const double dx = seg.back().x - x0;
        const double dz = seg.back().z - z0;
        double l = geometry::hypot(dx, dz);
        if (l == 0.0) l = 1e-9;
        double dmax = 0.0;
        std::size_t k = 0;
        for (std::size_t i = 1; i + 1 < seg.size(); ++i) {
            const double dd = std::fabs((seg[i].x - x0) * dz - (seg[i].z - z0) * dx) / l;
            if (dd > dmax) {
                dmax = dd;
                k = i;
            }
        }
        if (dmax <= tol) {
            ring.push_back(seg[0]);
            return;
        }
        self(self, seg.first(k + 1));
        self(self, seg.subspan(k));
    };
    std::size_t far = 0;
    double best = dist(pts[0], pts[0]);
    for (std::size_t i = 0; i < pts.size(); ++i) {
        const double dd = dist(pts[0], pts[i]);
        if (dd > best) {
            best = dd;
            far = i;
        }
    }
    dp(dp, pts.first(far + 1));
    std::vector<V2> back(pts.begin() + static_cast<std::ptrdiff_t>(far), pts.end());
    back.push_back(pts[0]);
    dp(dp, back);
    if (ring.size() >= 3 && std::fabs(signed_area(ring)) > 2) return ring;
    return {pts.begin(), pts.end()};
}

std::expected<Scene, LoadError> load_region(const osm::Document& doc, const RegionConfig& region, double tolerance) {
    auto loaded = load_amsterdam(doc, region.frame, region.palette);
    if (!loaded) return loaded;
    Scene& s = *loaded;
    Frame frame(region.frame);
    relation_buildings(doc, s, frame, region.palette);
    closest_roofs(s);
    if (tolerance != 0.0) {
        for (auto* list : {&s.buildings, &s.parts}) {
            for (Building& b : *list) b.poly = simplify(b.poly, tolerance);
        }
        for (Area& a : s.areas) a.poly = simplify(a.poly, tolerance);
    }
    std::vector<std::span<const LL>> coast;
    region_features(doc, s, frame, region.name, coast);
    region_water(doc, s, frame, region, coast);
    std::ranges::stable_sort(s.land, [](const Land& a, const Land& b) {
        const int oa = land_of(a.kind)->order;
        const int ob = land_of(b.kind)->order;
        if (oa != ob) return oa < ob;
        return -std::fabs(signed_area(a.poly)) < -std::fabs(signed_area(b.poly));
    });
    outlines(s);
    // each building's look from its own tags, the land use it stands in and the region's tradition
    apply_looks(s, {.region = region.name, .tradition = region.tradition, .landuse = LandUseFrom::land});
    if (frame.error()) return std::unexpected(*frame.error());
    if (region.park != nullptr) {
        // a theme park: its coaster tracks standing up, each ride's top height from its published profile
        auto coasters = load_coasters(doc, region.frame, s, *region.park);
        if (!coasters) return std::unexpected(coasters.error());
        s.coasters = std::move(*coasters);
    }
    // a park's or a zoo's woods: generic trees inside the mapped outlines, off everything mapped in them, planted once
    // for the region
    if (region.woods) s.woods = plant_woods(s, region.woods->cx, region.woods->cz, region.woods->reach);
    return loaded;
}

} // namespace atlas::load
