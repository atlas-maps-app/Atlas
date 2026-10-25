#include "load/Amsterdam.hpp"

#include "base/pymath/PyMath.hpp"
#include "base/pymath/PyRandom.hpp"
#include "load/PoiStyle.hpp"
#include "load/Real.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <initializer_list>
#include <optional>
#include <unordered_map>
#include <utility>
#include <vector>

namespace atlas::load {

namespace {

constexpr std::array<std::string_view, 6> brick_old = {"#6E3B2E", "#7A4434", "#5C3A30",
                                                       "#8A5040", "#6A4A3C", "#7E5242"};
constexpr std::array<std::string_view, 3> plaster = {"#E8E2D4", "#EFE9DC", "#D9D2C2"};
constexpr std::array<std::string_view, 5> brick_new = {"#9C6A52", "#8F7F74", "#B08A6A", "#8C8A86", "#A9876E"};
constexpr std::array<std::string_view, 4> houseboat_cols = {"#2F4A3A", "#3A4A6A", "#6A3A2E", "#E8E2D4"};
constexpr std::array<std::string_view, 3> gabled_roof_cols = {"#4A3E3A", "#56504C", "#3E4248"};
constexpr std::array<std::string_view, 3> flat_roof_cols = {"#7E7A74", "#8C8882", "#6E6A66"};

/// A road class amsterdam.py draws, and its width (its `W`), in its order.
struct RoadClass {
    std::string_view hw;
    double w;
};
constexpr std::array<RoadClass, 12> road_classes = {{
    {.hw = "tertiary", .w = 7.5},
    {.hw = "secondary", .w = 9.0},
    {.hw = "residential", .w = 5.5},
    {.hw = "unclassified", .w = 5.0},
    {.hw = "living_street", .w = 4.5},
    {.hw = "pedestrian", .w = 5.0},
    {.hw = "busway", .w = 6.0},
    {.hw = "service", .w = 3.5},
    {.hw = "cycleway", .w = 2.4},
    {.hw = "footway", .w = 2.0},
    {.hw = "path", .w = 1.6},
    {.hw = "steps", .w = 2.0},
}};

/// `v in (a, b, ...)` for a tag's value (None is in none).
[[nodiscard]] bool one_of(std::optional<std::string_view> v, std::initializer_list<std::string_view> set) noexcept {
    return v.has_value() && std::ranges::find(set, *v) != set.end();
}

[[nodiscard]] bool one_of(std::string_view v, std::span<const std::string_view> set) noexcept {
    return std::ranges::find(set, v) != set.end();
}

using geometry::centroid;
using geometry::dist;
using geometry::pip;
using geometry::signed_area;

/// amsterdam.py's `closed(r)`: more than three points, the last within half a metre of the first.
[[nodiscard]] bool closed(std::span<const V2> r) noexcept { return r.size() > 3 && dist(r.front(), r.back()) < 0.5; }

/// amsterdam.py's `clean(r)`: the closing point dropped, turned so the signed area is positive.
[[nodiscard]] std::vector<V2> clean(std::vector<V2> r) {
    if (closed(r)) r.pop_back();
    if (!(signed_area(r) > 0)) std::ranges::reverse(r);
    return r;
}

/// `x // 40` as an int: the grid cell amsterdam.py files buildings and places in.
[[nodiscard]] std::int64_t cell_of(double v) noexcept { return static_cast<std::int64_t>(pymath::floor_div(v, 40.0)); }

struct CellKey {
    std::int64_t gx = 0;
    std::int64_t gz = 0;
    friend bool operator==(const CellKey&, const CellKey&) = default;
};

struct CellHash {
    [[nodiscard]] std::size_t operator()(const CellKey& k) const noexcept {
        const auto x = static_cast<std::uint64_t>(k.gx) * 0x9E37'79B9'7F4A'7C15ULL;
        return static_cast<std::size_t>(x ^ (static_cast<std::uint64_t>(k.gz) + 0x632B'E59B'D9B4'E019ULL + (x << 6U)));
    }
};

/// Reads the elements' positions into the frame, refusing the first that is off the Earth.
class Positions {
public:
    explicit Positions(const tiles::Frame& frame) noexcept : frame_(frame) {}

    [[nodiscard]] V2 at(osm::LatLon p, std::int64_t element) {
        const bool on_earth = p.lat >= -90.0 && p.lat <= 90.0 && p.lon >= -180.0 && p.lon <= 180.0; // NaN is not
        if (!on_earth && !error_) {
            error_ = LoadError{.fault = LoadError::Fault::off_earth, .element = element};
        }
        return xz(frame_, p.lat, p.lon);
    }

    [[nodiscard]] std::vector<V2> points(std::span<const osm::LatLon> g, std::int64_t element) {
        std::vector<V2> out;
        out.reserve(g.size());
        for (const osm::LatLon& p : g) out.push_back(at(p, element));
        return out;
    }

    /// amsterdam.py's `rings(e)`: a way's geometry, or a relation's outer members' (those with points).
    [[nodiscard]] std::vector<std::vector<V2>> rings(const osm::Document& doc, const osm::Element& e) {
        std::vector<std::vector<V2>> out;
        if (e.kind == osm::Kind::way && e.has_geometry) {
            out.push_back(points(doc.geometry(e), e.id));
        } else if (e.kind == osm::Kind::relation) {
            for (const osm::Member& m : doc.members(e)) {
                if (m.has_role && doc.text(m.role) == "outer" && m.has_geometry && m.geometry.count != 0) {
                    out.push_back(points(doc.geometry(m), e.id));
                }
            }
        }
        return out;
    }

    [[nodiscard]] const std::optional<LoadError>& error() const noexcept { return error_; }

private:
    tiles::Frame frame_;
    std::optional<LoadError> error_;
};

/// Python's truth of an optional float: there, and not zero (NaN is true).
[[nodiscard]] bool truthy(const std::optional<double>& v) noexcept { return v.has_value() && *v != 0.0; }

/// Kotlin's Long.toInt(): the low 32 bits.
[[nodiscard]] std::int32_t low_int(std::int64_t v) noexcept {
    return static_cast<std::int32_t>(static_cast<std::uint32_t>(static_cast<std::uint64_t>(v)));
}

/// `max(1, int(round((h - (roof_h or 0)) / 3.1)))`, as the port's Int; 1 where round() fails.
[[nodiscard]] std::int32_t levels_from_height(double h, const std::optional<double>& roof_h) noexcept {
    const double r = roof_h.value_or(0.0); // `roof_h or 0`: None and both zeros are 0
    const auto rounded = pymath::checked::round((h - (r != 0.0 ? r : 0.0)) / 3.1);
    if (!rounded) return 1;
    // The port goes through a double (PyMath.int of the Long's toDouble), as Python's ints never do.
    const auto whole = pymath::checked::to_int(static_cast<double>(*rounded));
    if (!whole) return 1;
    return low_int(std::max<std::int64_t>(1, *whole));
}

} // namespace

void guess_tags(Building& b, std::optional<double> h0, const Palette& palette) {
    const bool part = b.tags.has("building:part");
    pymath::PyRandom rnd(b.id);
    // edges[0] of the sorted sides: the shortest
    const std::size_t n = b.poly.size();
    double shortest = dist(b.poly[0], b.poly[1 % n]);
    for (std::size_t i = 1; i < n; ++i) shortest = pymath::min(shortest, dist(b.poly[i], b.poly[(i + 1) % n]));
    const bool narrow = n >= 3 ? shortest < 11 : false;
    const bool old = (b.year.has_value() && *b.year != 0 ? *b.year : 1900) < 1900;
    const auto building = b.tags.get("building");
    const bool houseboat = b.tags.is("building", "houseboat");
    if (!h0) {
        b.guessed.add(Guess::height);
        b.h = houseboat ? 2.5 : 10.0;
    } else {
        b.h = *h0;
    }
    if (!part) {
        if (!b.tags.has("building:levels")) b.guessed.add(Guess::building_levels);
        if (!load::truthy(b.roof)) {
            b.guessed.add(Guess::roof_shape);
            const bool gabled =
                old && narrow && !one_of(building, {"church", "houseboat", "industrial", "roof", "shed"}) && b.h > 8;
            b.roof = gabled ? "gabled" : "flat";
            if (gabled) {
                b.roof_h = pymath::min(5.0, pymath::max(3.0, shortest * 0.55));
                b.guessed.add(Guess::roof_height);
                b.orient = "along";
            }
        }
        if (!load::truthy(b.col) && !load::truthy(b.mat)) {
            b.guessed.add(Guess::building_colour);
            const double r = rnd.random();
            if (houseboat) {
                b.col = rnd.choice(std::span(houseboat_cols));
            } else if (old) {
                b.col = r < 0.18 ? rnd.choice(palette.plaster) : rnd.choice(palette.brick_old);
            } else {
                b.col = rnd.choice(palette.brick_new);
            }
            b.mat = std::nullopt;
            if (!houseboat) {
                // the colour comes with a material: brick, or plaster for the pale ones
                b.mat_draw = one_of(*b.col, palette.plaster) ? "plaster" : "brick";
                b.guessed.add(Guess::building_material);
            }
        }
        if (!load::truthy(b.rcol) && !load::truthy(b.rmat)) {
            b.guessed.add(Guess::roof_colour);
            const bool gabled = b.roof && *b.roof == "gabled";
            b.rcol = gabled ? rnd.choice(std::span(gabled_roof_cols)) : rnd.choice(std::span(flat_roof_cols));
        }
    }
    if (one_of(b.roof, {"gabled", "hipped", "pyramidal", "skillion", "quadruple_saltbox", "mansard"}) &&
        !truthy(b.roof_h)) {
        const auto rl = num(b.tags.get("roof:levels"));
        const double levels = rl.value_or(0.0); // `rl * 2.8 if rl`: None and both zeros are false
        b.roof_h =
            pymath::min(b.h * 0.4, levels != 0.0 ? levels * 2.8 : pymath::max(2.0, pymath::min(5.0, shortest * 0.45)));
        b.guessed.add(Guess::roof_height);
    }
    if (b.roof && *b.roof == "skillion" && !b.tags.truthy("roof:direction")) {
        b.guessed.add(Guess::roof_direction);
    }
    b.levels = levels_from_height(b.h, b.roof_h);
    if (b.tags.truthy("building:levels")) {
        if (const auto lv = num(b.tags.get("building:levels"))) {
            if (const auto whole = pymath::checked::to_int(*lv)) b.levels = low_int(*whole);
        }
    }
    if (load::truthy(b.mat) && !load::truthy(b.mat_draw)) b.mat_draw = b.mat;
}

Box box_of(std::span<const V2> poly) noexcept {
    Box bb{.x0 = poly[0].x, .x1 = poly[0].x, .z0 = poly[0].z, .z1 = poly[0].z};
    for (const V2& p : poly.subspan(1)) {
        bb.x0 = pymath::min(bb.x0, p.x);
        bb.x1 = pymath::max(bb.x1, p.x);
        bb.z0 = pymath::min(bb.z0, p.z);
        bb.z1 = pymath::max(bb.z1, p.z);
    }
    return bb;
}

namespace {

/// The places: each node with a place style gets it, and stands in the first building (not a part) of its 40 m cell
/// whose box and outline hold it. amsterdam.py files every building in each cell its box reaches; a building that
/// reaches more than `wide_cells` cells is kept aside instead and tried where its cells hold the node, so the first
/// building found is the same.
void place(Scene& s) {
    constexpr std::int64_t wide_cells = 1024;
    struct Span {
        std::int64_t gx0, gx1, gz0, gz1;
    };
    std::unordered_map<CellKey, std::vector<std::uint32_t>, CellHash> grid;
    std::vector<std::pair<std::uint32_t, Span>> wide;
    for (std::uint32_t i = 0; i < s.buildings.size(); ++i) {
        const Box& bb = s.buildings[i].bb;
        const Span c{.gx0 = cell_of(bb.x0), .gx1 = cell_of(bb.x1), .gz0 = cell_of(bb.z0), .gz1 = cell_of(bb.z1)};
        if ((c.gx1 - c.gx0 + 1) * (c.gz1 - c.gz0 + 1) > wide_cells) {
            wide.emplace_back(i, c);
            continue;
        }
        for (std::int64_t gx = c.gx0; gx <= c.gx1; ++gx) {
            for (std::int64_t gz = c.gz0; gz <= c.gz1; ++gz) grid[{gx, gz}].push_back(i);
        }
    }
    const std::vector<std::uint32_t> none;
    for (std::uint32_t k = 0; k < s.nodes.size(); ++k) {
        Node& n = s.nodes[k];
        std::optional<PlaceStyle> style = place_style(n.tags);
        if (!style) continue;
        n.style = static_cast<std::uint32_t>(s.styles.size());
        s.styles.push_back(std::move(*style));
        const CellKey key{.gx = cell_of(n.x), .gz = cell_of(n.z)};
        const auto it = grid.find(key);
        const std::vector<std::uint32_t>& near = it != grid.end() ? it->second : none;
        std::size_t a = 0;
        std::size_t w = 0;
        while (a < near.size() || w < wide.size()) {
            std::uint32_t i = 0;
            if (w == wide.size() || (a < near.size() && near[a] < wide[w].first)) {
                i = near[a++];
            } else {
                const auto& [index, c] = wide[w++];
                if (key.gx < c.gx0 || key.gx > c.gx1 || key.gz < c.gz0 || key.gz > c.gz1) continue;
                i = index;
            }
            Building& b = s.buildings[i];
            if (b.bb.x0 <= n.x && n.x <= b.bb.x1 && b.bb.z0 <= n.z && n.z <= b.bb.z1 && pip(n.x, n.z, b.poly)) {
                b.places.push_back(k);
                n.building = i;
                break;
            }
        }
    }
}

/// `hidden`: an outline with a part's centroid within 80 m of its own, inside it. The parts are filed by their
/// centroids' 80 m cells, so each outline tries only the parts of the cells around its reach (one more each way, for
/// the rounding of the differences); `any` does not depend on the order.
void hide(Scene& s) {
    constexpr double reach = 80.0;
    std::vector<V2> pc;
    pc.reserve(s.parts.size());
    std::unordered_map<CellKey, std::vector<std::uint32_t>, CellHash> grid;
    const auto cell = [](double v) { return static_cast<std::int64_t>(std::floor(v / reach)); };
    for (std::uint32_t i = 0; i < s.parts.size(); ++i) {
        pc.push_back(centroid(s.parts[i].poly));
        if (std::isfinite(pc.back().x) && std::isfinite(pc.back().z)) {
            grid[{cell(pc.back().x), cell(pc.back().z)}].push_back(i);
        }
    }
    for (Building& b : s.buildings) {
        const V2 bc = centroid(b.poly);
        if (!std::isfinite(bc.x) || !std::isfinite(bc.z)) continue;
        bool hidden = false;
        for (std::int64_t gx = cell(bc.x - reach) - 1; gx <= cell(bc.x + reach) + 1 && !hidden; ++gx) {
            for (std::int64_t gz = cell(bc.z - reach) - 1; gz <= cell(bc.z + reach) + 1 && !hidden; ++gz) {
                const auto it = grid.find({gx, gz});
                if (it == grid.end()) continue;
                for (const std::uint32_t i : it->second) {
                    const V2 c = pc[i];
                    if (std::fabs(c.x - bc.x) < reach && std::fabs(c.z - bc.z) < reach && pip(c.x, c.z, b.poly)) {
                        hidden = true;
                        break;
                    }
                }
            }
        }
        b.hidden = hidden;
    }
}

} // namespace

std::string_view key_of(Guess guess) noexcept {
    switch (guess) {
    case Guess::height: return "height";
    case Guess::building_levels: return "building:levels";
    case Guess::roof_shape: return "roof:shape";
    case Guess::roof_height: return "roof:height";
    case Guess::building_colour: return "building:colour";
    case Guess::building_material: return "building:material";
    case Guess::roof_colour: return "roof:colour";
    case Guess::roof_direction: return "roof:direction";
    case Guess::roof_material: return "roof:material";
    case Guess::roof_orientation: return "roof:orientation";
    }
    return "?";
}

std::string_view name_of(LoadError::Fault fault) noexcept {
    switch (fault) {
    case LoadError::Fault::off_earth: return "off_earth";
    case LoadError::Fault::not_a_number: return "not_a_number";
    }
    return "?";
}

tiles::Frame westerkerk_frame() noexcept {
    constexpr double lat0 = 52.3746;
    return {.lat0 = lat0, .lon0 = 4.8840, .kx = 111320 * pymath::cos(pymath::radians(lat0)), .kz = 110540};
}

Palette amsterdam_palette() noexcept { return {.brick_old = brick_old, .plaster = plaster, .brick_new = brick_new}; }

std::expected<Scene, LoadError> load_amsterdam(const osm::Document& doc, const tiles::Frame& frame,
                                               const Palette& palette, const Looks* looks) {
    Scene s;
    Positions at(frame);
    // The largest lists' room up front, from the elements that fill them (a relation's other rings may add a few):
    // grown by doubling, a dense window's scene held about a quarter more than its records (ENGINE.md step 3's budget
    // for a window's memory).
    {
        std::size_t buildings = 0;
        std::size_t parts = 0;
        std::size_t nodes = 0;
        for (const osm::Element& e : doc.elements()) {
            if (!e.has_tags || e.tags.count == 0) continue;
            if (e.kind == osm::Kind::node) {
                if (e.has_at) ++nodes;
                continue;
            }
            const Tags t(doc.tags(e));
            if (t.has("building:part")) {
                ++parts;
            } else if (t.has("building") && !t.is("building", "no")) {
                ++buildings;
            }
        }
        s.buildings.reserve(buildings);
        s.parts.reserve(parts);
        s.nodes.reserve(nodes);
    }
    for (const osm::Element& e : doc.elements()) {
        if (!e.has_tags || e.tags.count == 0) continue;
        const Tags t(doc.tags(e));
        const bool node = e.kind == osm::Kind::node;
        if ((t.has("building") || t.has("building:part")) && !node && !t.is("building", "no")) {
            const bool part = t.has("building:part");
            for (std::vector<V2>& r : at.rings(doc, e)) {
                if (!closed(r) || std::fabs(signed_area(r)) < 3) continue;
                Building b;
                b.poly = clean(std::move(r));
                b.id = e.id;
                b.tags = t;
                std::optional<double> h = num(t.get("height"));
                const std::optional<double> lv = num(t.get("building:levels"));
                b.src = h ? HeightSource::height : (lv ? HeightSource::levels : HeightSource::none);
                if (!h && lv) h = *lv * 3.0 + num(t.get("roof:levels")).value_or(0.0) * 2.0;
                b.year = leading_year(t.get("start_date").value_or(""));
                b.min = num(t.get("min_height")).value_or(0.0);
                b.roof = t.get("roof:shape");
                b.roof_h = num(t.get("roof:height"));
                b.orient = t.get("roof:orientation");
                b.mat = t.get("building:material");
                b.rmat = t.get("roof:material");
                b.col = t.get("building:colour");
                b.rcol = t.get("roof:colour");
                guess_tags(b, h, palette);
                b.bb = box_of(b.poly);
                (part ? s.parts : s.buildings).push_back(std::move(b));
            }
            continue;
        }
        if ((t.is("natural", "water") || one_of(t.get("waterway"), {"canal", "riverbank", "dock"})) && !node) {
            for (std::vector<V2>& r : at.rings(doc, e)) {
                if (closed(r)) s.water.push_back(clean(std::move(r)));
            }
            if (t.truthy("name") && t.is("waterway", "canal") && e.kind == osm::Kind::way && e.has_geometry) {
                std::vector<V2> pts = at.points(doc.geometry(e), e.id);
                if (!closed(pts)) s.labels.push_back({.pts = std::move(pts), .name = *t.get("name"), .kind = "canal"});
            }
            continue;
        }
        if (e.kind == osm::Kind::way && t.has("highway") && e.has_geometry) {
            const std::string_view hw = *t.get("highway");
            const auto* const cls = std::ranges::find(road_classes, hw, &RoadClass::hw);
            if (cls == road_classes.end() || e.geometry.count == 0) continue;
            std::vector<V2> pts = at.points(doc.geometry(e), e.id);
            if (pts.front() == pts.back() && (t.is("area", "yes") || hw == "pedestrian")) {
                std::vector<V2> poly = pts;
                if (pts.size() > 3) {
                    poly.push_back(pts.front());
                    poly = clean(std::move(poly));
                }
                s.areas.push_back(
                    {.poly = std::move(poly), .kind = "pedestrian", .tags = t, .from = {.kind = e.kind, .id = e.id}});
            } else {
                const bool named = t.truthy("name") &&
                                   one_of(hw, std::array<std::string_view, 5>{"residential", "tertiary", "pedestrian",
                                                                              "living_street", "unclassified"});
                if (named) s.labels.push_back({.pts = pts, .name = *t.get("name"), .kind = "street"});
                s.roads.push_back({.pts = std::move(pts),
                                   .id = e.id,
                                   .tags = t,
                                   .w = num(t.get("width")).value_or(cls->w),
                                   .hw = hw,
                                   .bridge = t.truthy("bridge"),
                                   .layer = num(t.get("layer")).value_or(0.0),
                                   .wguess = !t.truthy("width")});
            }
            continue;
        }
        if (e.kind == osm::Kind::way && t.is("railway", "tram") && e.has_geometry) {
            s.rails.push_back(at.points(doc.geometry(e), e.id));
            s.rails_from.push_back({.kind = e.kind, .id = e.id});
            continue;
        }
        if (!node && (one_of(t.get("leisure"), {"park", "garden", "playground"}) || t.is("landuse", "grass") ||
                      t.is("man_made", "bridge") || t.is("amenity", "parking") || t.is("place", "square") ||
                      t.is("historic", "memorial"))) {
            std::string_view kind = "memorial";
            for (const std::string_view k : {"leisure", "landuse", "man_made", "amenity", "place"}) {
                if (t.truthy(k)) {
                    kind = *t.get(k);
                    break;
                }
            }
            std::uint32_t part = 0;
            for (std::vector<V2>& r : at.rings(doc, e)) {
                if (closed(r)) {
                    s.areas.push_back({.poly = clean(std::move(r)),
                                       .kind = kind,
                                       .tags = t,
                                       .from = {.kind = e.kind, .id = e.id, .part = part}});
                }
                ++part;
            }
            continue;
        }
        if (node && e.has_at) {
            const V2 p = at.at(e.at, e.id);
            const auto index = static_cast<std::uint32_t>(s.nodes.size());
            s.nodes.push_back({.x = p.x, .z = p.z, .id = e.id, .tags = t});
            if (t.is("natural", "tree")) {
                s.trees.push_back({.node = index,
                                   .h = num(t.get("height")).value_or(12.0),
                                   .crown = num(t.get("diameter_crown")).value_or(7.0),
                                   .guessed = {.height = !t.has("height"),
                                               .diameter_crown = !t.has("diameter_crown"),
                                               .leaf_type = !t.has("leaf_type")}});
            }
            if (t.has("shop") ||
                one_of(t.get("amenity"), {"restaurant", "cafe", "bar", "pub", "fast_food", "ice_cream", "pharmacy"}) ||
                one_of(t.get("tourism"), {"museum", "hotel", "gallery"})) {
                s.pois.push_back(index);
            }
        }
    }
    if (at.error()) return std::unexpected(*at.error());
    if (looks != nullptr) apply_looks(s, *looks);
    place(s);
    hide(s);
    return s;
}

} // namespace atlas::load
