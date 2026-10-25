#include "load/Coasters.hpp"

#include "base/pymath/PyMath.hpp"
#include "geometry/RoofShapes.hpp"
#include "load/Amsterdam.hpp"
#include "load/Real.hpp"
#include "tiles/Json.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <filesystem>
#include <format>
#include <fstream>
#include <initializer_list>
#include <iterator>
#include <optional>
#include <ranges>
#include <span>
#include <utility>

namespace atlas::load {

namespace {

namespace pm = atlas::pymath;

/// coasters.py's LOW (a track at layer 0: just off the ground) and STATION; a monorail's beam.
constexpr double low = 1.6;
constexpr double station = 1.2;
constexpr double monorail = 3.5;

/// LAYER_SHARE.get(lay): a layer's share of its ride's top height (layer 0 and the rest: none).
[[nodiscard]] std::optional<double> layer_share(std::int64_t layer) noexcept {
    switch (layer) {
    case 1: return 0.45;
    case 2: return 0.75;
    case 3: return 1.0;
    default: return std::nullopt;
    }
}

/// coasters.py's smooth: t clamped to [0, 1], then t · t · (3 - 2 t).
[[nodiscard]] double smooth(double t0) noexcept {
    const double t = pm::max(0.0, pm::min(1.0, t0));
    return t * t * (3 - (2 * t));
}

/// Python's truth of a JSON value.
[[nodiscard]] bool truthy(const tiles::Value& v) noexcept {
    switch (v.type()) {
    case tiles::ValueType::null:
    case tiles::ValueType::boolean_false: return false;
    case tiles::ValueType::boolean_true: return true;
    case tiles::ValueType::int32:
    case tiles::ValueType::int64: return v.integer().value_or(0) != 0;
    case tiles::ValueType::real: return v.real().value_or(0.0) != 0.0;
    case tiles::ValueType::string: return !v.string().value_or("").empty();
    case tiles::ValueType::list:
    case tiles::ValueType::map: return v.size() > 0;
    }
    return false;
}

/// `t.get(key) in set`.
[[nodiscard]] bool one_of(std::optional<std::string_view> v, std::initializer_list<std::string_view> set) noexcept {
    return v && std::ranges::find(set, *v) != set.end();
}

/// `m.get(key) or {}` and the like: the value where it is there and true.
[[nodiscard]] std::optional<tiles::Value> got(const tiles::Value& m, std::string_view key) noexcept {
    std::optional<tiles::Value> v = m.find(key);
    if (!v || !truthy(*v)) return std::nullopt;
    return v;
}

/// A node's key where pieces meet: (round(lat, 7), round(lon, 7)), -0.0 as 0.0 (Python's dict counts them equal).
using Key = std::pair<double, double>;
[[nodiscard]] Key key_of(const osm::LatLon& p) noexcept {
    return {pm::round(p.lat, 7) + 0.0, pm::round(p.lon, 7) + 0.0};
}

/// A piece as load gathers it: its points and its ends' keys, its kind, ride, height (none for a lift hill), role and
/// its ride's top height.
struct Way {
    std::vector<V2> pts;
    Key first;
    Key last;
    std::string_view kind;
    std::optional<std::string> ride;
    std::optional<double> h;
    std::string role;
    double top = 0.0;
};

/// Python's sum(xs) / len(xs).
template <typename F>
[[nodiscard]] double mean(std::size_t n, const F& at) noexcept {
    pm::Summer sum;
    for (std::size_t i = 0; i < n; ++i) sum.add(at(i));
    return sum.value() / static_cast<double>(n);
}

} // namespace

std::expected<RideProfiles, std::string> read_ride_profiles(const std::string& folder) {
    RideProfiles out;
    std::error_code e;
    std::vector<std::filesystem::path> files;
    for (const auto& entry : std::filesystem::directory_iterator(folder, e)) {
        if (entry.path().extension() == ".json") files.push_back(entry.path());
    }
    if (e) return std::unexpected(std::format("{}: {}", folder, e.message()));
    std::ranges::sort(files,
                      [](const auto& a, const auto& b) { return a.filename().string() < b.filename().string(); });
    for (const std::filesystem::path& f : files) {
        std::ifstream in(f, std::ios::binary);
        const std::string text((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
        if (!in && !in.eof()) return std::unexpected(std::format("{}: not read", f.string()));
        const auto json = tiles::read_json(std::as_bytes(std::span(text.data(), text.size())));
        if (!json)
            return std::unexpected(
                std::format("{}: {} at byte {}", f.string(), tiles::name_of(json.error().fault), json.error().at));
        const tiles::Value a = json->root();
        const std::optional<tiles::Value> name = a.find("name");
        const std::optional<std::string_view> n = name ? name->string() : std::nullopt;
        if (!n) return std::unexpected(std::format("{}: no name", f.string()));
        // rp = a_.get('rideProfile') or {}; (rp.get('stats') or {}).get('heightM'); 'wooden-coaster' in
        // (rp.get('types') or [])
        const std::optional<tiles::Value> rp = got(a, "rideProfile");
        if (!rp) continue;
        if (const std::optional<tiles::Value> stats = got(*rp, "stats")) {
            if (const std::optional<tiles::Value> h = got(*stats, "heightM")) {
                std::optional<double> v = h->number();
                if (!v && h->string()) v = py_float(*h->string());
                if (!v && h->boolean()) v = 1.0; // float(True)
                if (!v) return std::unexpected(std::format("{}: heightM is not a number", f.string()));
                out.heights.insert_or_assign(std::string(*n), *v);
            }
        }
        if (const std::optional<tiles::Value> types = got(*rp, "types")) {
            for (std::size_t i = 0; i < types->size(); ++i) {
                if (types->child(i).string() == "wooden-coaster") out.wooden.insert_or_assign(std::string(*n), true);
            }
        }
    }
    return out;
}

std::expected<std::vector<CoasterTrack>, LoadError> load_coasters(const osm::Document& doc, const tiles::Frame& frame,
                                                                  const Scene& scene, const RideProfiles& rides) {
    std::optional<LoadError> error;
    const auto xz = [&](const osm::LatLon& p, std::int64_t element) {
        const bool on_earth = p.lat >= -90.0 && p.lat <= 90.0 && p.lon >= -180.0 && p.lon <= 180.0; // NaN is not
        if (!on_earth && !error) error = LoadError{.fault = LoadError::Fault::off_earth, .element = element};
        return load::xz(frame, p.lat, p.lon);
    };
    // the rides: each roller coaster with a name, at its node or at its geometry's mean (of the degrees)
    std::vector<std::pair<std::string, V2>> named;
    for (const osm::Element& e : doc.elements()) {
        const Tags t(doc.tags(e));
        if (!t.is("attraction", "roller_coaster") || !t.truthy("name")) continue;
        const std::span<const osm::LatLon> g = doc.geometry(e);
        if (e.kind == osm::Kind::node) {
            if (e.has_at) named.emplace_back(std::string(*t.get("name")), xz(e.at, e.id));
        } else if (e.has_geometry && !g.empty()) {
            const osm::LatLon m{.lat = mean(g.size(), [&](std::size_t i) { return g[i].lat; }),
                                .lon = mean(g.size(), [&](std::size_t i) { return g[i].lon; })};
            named.emplace_back(std::string(*t.get("name")), xz(m, e.id));
        }
    }
    // indoors: within a building's or a part's box and ring
    struct Outline {
        std::array<double, 4> box; // min x, max x, min z, max z
        std::span<const V2> poly;
    };
    std::vector<Outline> outlines;
    for (const std::vector<Building>* list : {&scene.buildings, &scene.parts}) {
        for (const Building& b : *list) {
            if (b.poly.empty()) continue;
            const auto [x0, x1] = std::ranges::minmax(b.poly | std::views::transform(&V2::x));
            const auto [z0, z1] = std::ranges::minmax(b.poly | std::views::transform(&V2::z));
            outlines.push_back({.box = {x0, x1, z0, z1}, .poly = b.poly});
        }
    }
    const auto indoors = [&](const V2& p) {
        return std::ranges::any_of(outlines, [&](const Outline& o) {
            return o.box[0] <= p.x && p.x <= o.box[1] && o.box[2] <= p.z && p.z <= o.box[3] &&
                   geometry::pip(p.x, p.z, o.poly);
        });
    };
    // the pieces
    std::vector<Way> ways;
    for (const osm::Element& e : doc.elements()) {
        const Tags t(doc.tags(e));
        const bool mono = t.is("railway", "monorail");
        const std::span<const osm::LatLon> geo = doc.geometry(e);
        if (!(t.is("roller_coaster", "track") || mono) || e.kind != osm::Kind::way || !e.has_geometry || geo.empty()) {
            continue;
        }
        // lay = int(float(t.get('layer', '0') or 0))
        std::int64_t lay = 0;
        if (const std::optional<std::string_view> layer = t.get("layer"); layer && !layer->empty()) {
            const std::optional<double> f = py_float(*layer);
            if (!f || !std::isfinite(*f)) {
                return std::unexpected(LoadError{.fault = LoadError::Fault::not_a_number, .element = e.id});
            }
            lay = static_cast<std::int64_t>(std::trunc(std::clamp(*f, -8.0, 8.0))); // past 3 a layer has no share
        }
        if (one_of(t.get("tunnel"), {"yes", "building_passage"}) || lay < 0 || t.is("indoor", "yes")) continue;
        Way w;
        w.pts.reserve(geo.size());
        for (const osm::LatLon& p : geo) w.pts.push_back(xz(p, e.id));
        w.first = key_of(geo.front());
        w.last = key_of(geo.back());
        const V2& mid = w.pts[w.pts.size() / 2];
        if (indoors(mid) && indoors(w.pts.front()) && indoors(w.pts.back())) continue;
        if (mono) {
            w.kind = "mono";
            w.ride = t.truthy("name") ? std::string(*t.get("name")) : std::string("Monorail");
            w.h = monorail;
            w.role = "mono";
            ways.push_back(std::move(w));
            continue;
        }
        // its ride: the nearest (the first of equals), Max and Moritz's both tracks theirs
        const V2 c{mean(w.pts.size(), [&](std::size_t i) { return w.pts[i].x; }),
                   mean(w.pts.size(), [&](std::size_t i) { return w.pts[i].z; })};
        if (!named.empty()) {
            const auto* best = named.data();
            double bd = geometry::dist(best->second, c);
            for (const auto& r : named) {
                const double d = geometry::dist(r.second, c);
                if (d < bd) {
                    bd = d;
                    best = &r;
                }
            }
            w.ride = best->first;
        }
        if (one_of(t.get("name"), {"Max", "Moritz"})) w.ride = "Max & Moritz";
        const auto height = w.ride ? rides.heights.find(*w.ride) : rides.heights.end();
        w.top = height != rides.heights.end() ? height->second : 12.0;
        w.role = t.truthy("roller_coaster:track") ? std::string(*t.get("roller_coaster:track")) : std::string("track");
        if (w.role == "station" || w.role == "brake_run") {
            w.h = station;
        } else if (w.role != "lift_hill") {
            const std::optional<double> share = layer_share(lay);
            w.h = share ? pm::max(low, w.top * *share) : low;
        }
        w.kind = w.ride && rides.wooden.contains(*w.ride) ? "wood" : "steel";
        ways.push_back(std::move(w));
    }
    if (error) return std::unexpected(*error);
    // the height where pieces meet: the mean of the pieces meeting at that node
    std::map<Key, std::vector<double>> at;
    for (const Way& w : ways) {
        if (!w.h) continue;
        at[w.first].push_back(*w.h);
        at[w.last].push_back(*w.h);
    }
    std::map<Key, double> node_h;
    for (const auto& [k, v] : at) node_h.emplace(k, mean(v.size(), [&](std::size_t i) { return v[i]; }));
    const auto meet = [&](const Key& k) -> std::optional<double> {
        const auto found = node_h.find(k);
        if (found == node_h.end()) return std::nullopt;
        return found->second;
    };
    std::vector<CoasterTrack> out;
    out.reserve(ways.size());
    for (Way& w : ways) {
        std::vector<double> s{0.0};
        for (std::size_t i = 1; i < w.pts.size(); ++i) s.push_back(s.back() + geometry::dist(w.pts[i - 1], w.pts[i]));
        const double lw = s.back() != 0.0 ? s.back() : 1.0;
        std::optional<double> h0 = meet(w.first);
        std::optional<double> h1 = meet(w.last);
        std::vector<double> ys;
        ys.reserve(s.size());
        if (!w.h) {
            // the lift hill: from the low end up to the ride's top
            const double a = h0.value_or(low);
            const double b = h1.value_or(w.top);
            const double lo = b < a ? b : a;
            const double hi = pm::max(b < a ? a : b, w.top * 0.95);
            const bool up = (h1 && *h1 != 0.0 ? *h1 : 0.0) >= (h0 && *h0 != 0.0 ? *h0 : 0.0);
            for (const double si : s) ys.push_back(lo + ((hi - lo) * (up ? si / lw : 1 - (si / lw))));
        } else {
            const double hw = *w.h;
            const double e0 = h0.value_or(hw);
            const double e1 = h1.value_or(hw);
            const double ramp = pm::min(18.0, lw / 2);
            for (const double si : s) {
                ys.push_back(hw + ((e0 - hw) * (1 - smooth(si / ramp))) + ((e1 - hw) * (1 - smooth((lw - si) / ramp))));
            }
        }
        out.push_back({.pts = std::move(w.pts),
                       .ys = std::move(ys),
                       .kind = w.kind,
                       .ride = std::move(w.ride),
                       .role = std::move(w.role)});
    }
    return out;
}

} // namespace atlas::load
