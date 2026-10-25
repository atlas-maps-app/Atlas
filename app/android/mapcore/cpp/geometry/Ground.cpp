// The ground of a render tile (geometry/Ground.hpp): render/amsterdam.py:615 render's ground and marks and regions.py's
// draw_under and draw_over, element by element in the port's arithmetic (R/Amsterdam.kt render, drawRoads,
// groundMarks; R/Regions.kt drawUnder, drawOver).
#include "geometry/Ground.hpp"

#include "base/pymath/PyMath.hpp"
#include "geometry/Items.hpp"
#include "geometry/RoofShapes.hpp"
#include "geometry/Water.hpp"
#include "sun/Hulls.hpp"
#include "sun/Samples.hpp"
#include "tiles/Checksums.hpp"
#include "tiles/Simplify.hpp"
#include "tiles/Source.hpp"
#include "tiles/layers/Buildings.hpp"
#include "tiles/layers/Ground.hpp"
#include "tiles/layers/Lines.hpp"
#include "tiles/layers/Points.hpp"
#include "tiles/layers/Water.hpp"

#include <mapbox/earcut.hpp>

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdlib>
#include <limits>
#include <string>

namespace atlas::geometry {

namespace pm = atlas::pymath;
namespace tl = atlas::tiles;

namespace {

// ---------------------------------------------------------------- the renders' literals (code, not the style)

// regions.py:501 the canopy's fill (drawn at .55; the filter is K17's)
constexpr Rgba canopy_black{.r = 0, .g = 0, .b = 0, .a = 255};

/// The land kinds the canopy covers (regions.py:498).
constexpr std::array<std::string_view, 3> canopy_kinds = {"forest", "wood", "scrub"};

/// The areas drawn in their mapped surface where TONE holds (amsterdam.py:665).
constexpr std::array<std::string_view, 3> surfaced = {"pedestrian", "square", "parking"};

[[nodiscard]] bool in(std::string_view s, std::span<const std::string_view> set) noexcept {
    return std::ranges::find(set, s) != set.end();
}

/// A decimal `-?D+(.D+)?` as the double nearest it (Python's float()): Clinger's fast path where its digits fit 2^53
/// and its fraction 22 places (one rounding of exact operands), else the C library's strtod (correctly rounded on
/// Bionic, glibc and Apple's libc alike; the core's locale is C).
[[nodiscard]] double decimal(std::string_view text) noexcept {
    static constexpr std::array<double, 23> powers = {1e0,  1e1,  1e2,  1e3,  1e4,  1e5,  1e6,  1e7,
                                                      1e8,  1e9,  1e10, 1e11, 1e12, 1e13, 1e14, 1e15,
                                                      1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22};
    const bool negative = !text.empty() && text[0] == '-';
    std::uint64_t mantissa = 0;
    std::size_t digits = 0;
    std::size_t fraction = 0;
    bool point = false;
    for (std::size_t i = negative ? 1 : 0; i < text.size(); ++i) {
        if (text[i] == '.') {
            point = true;
            continue;
        }
        if (digits == 0 && text[i] == '0' && !point) continue; // leading zeros carry nothing
        ++digits;
        if (point) ++fraction;
        if (digits <= 19) mantissa = (mantissa * 10) + static_cast<std::uint64_t>(text[i] - '0');
    }
    if (digits <= 19 && mantissa <= (std::uint64_t{1} << 53U) && fraction < powers.size()) {
        const double v = static_cast<double>(mantissa) / powers[fraction];
        return negative ? -v : v;
    }
    const std::string copy(text);
    return std::strtod(copy.c_str(), nullptr);
}

/// real.py's num(v, default): the number a tag's value starts with (optional spaces, an optional minus, digits, an
/// optional fraction), else the default (decimal: as Python's float() reads it).
[[nodiscard]] double num(std::optional<std::string_view> v, double fallback) noexcept {
    if (!v) return fallback;
    std::string_view s = *v;
    std::size_t i = 0;
    // Python's \s: the ASCII whitespace and the other Unicode spaces; OSM's tags have the ASCII ones
    while (i < s.size() &&
           (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r' || s[i] == '\f' || s[i] == '\v')) {
        ++i;
    }
    const std::size_t start = i;
    if (i < s.size() && s[i] == '-') ++i;
    const std::size_t digits = i;
    while (i < s.size() && s[i] >= '0' && s[i] <= '9') ++i;
    if (i == digits) return fallback;
    if (i + 1 < s.size() && s[i] == '.' && s[i + 1] >= '0' && s[i + 1] <= '9') {
        ++i;
        while (i < s.size() && s[i] >= '0' && s[i] <= '9') ++i;
    }
    return decimal(s.substr(start, i - start));
}

/// The cell of feature `i` of a layer (its features are sorted by cell).
class CellOf {
public:
    explicit CellOf(const tl::Layer& l) noexcept : first_(l.cell_first) {}
    [[nodiscard]] std::uint8_t operator()(std::uint32_t i) noexcept {
        while (cell_ + 1 < first_.size() && first_[cell_ + 1] <= i) ++cell_;
        return static_cast<std::uint8_t>(cell_);
    }

private:
    std::span<const std::uint32_t> first_;
    std::size_t cell_ = 0;
};

/// A feature's OpenStreetMap element as its order key holds it (OrderKey::element): type << 62 | id << 18 | part, the
/// type a way unless the tile's `otype` says relation; ids past 2^44 and parts past 2^18 held at their largest.
[[nodiscard]] std::uint64_t element_of(const tl::Layer& l, std::uint8_t id, std::uint8_t otype, std::uint8_t part,
                                       std::uint32_t i) noexcept {
    const std::int64_t v = id == 0xFF ? 0 : l.integer(id, i);
    const bool relation = otype != 0xFF && l.string(otype, i).value_or("") == "relation";
    const std::int64_t k = part == 0xFF ? 0 : l.integer(part, i);
    const std::uint64_t ids = std::min<std::uint64_t>(static_cast<std::uint64_t>(std::max<std::int64_t>(v, 0)),
                                                      (std::uint64_t{1} << 44U) - 1);
    const std::uint64_t parts = std::min<std::uint64_t>(static_cast<std::uint64_t>(std::max<std::int64_t>(k, 0)),
                                                        (std::uint64_t{1} << 18U) - 1);
    return (std::uint64_t{relation ? 2U : 1U} << 62U) | (ids << 18U) | parts;
}

/// The distance between segments ab and cd in the plane, 0 where they cross (a sett line's reach back).
[[nodiscard]] double segment_distance(P2 a, P2 b, P2 c, P2 d) noexcept {
    auto side = [](P2 o, P2 p, P2 q) { return ((p.x - o.x) * (q.z - o.z)) - ((p.z - o.z) * (q.x - o.x)); };
    const double d1 = side(c, d, a);
    const double d2 = side(c, d, b);
    const double d3 = side(a, b, c);
    const double d4 = side(a, b, d);
    if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return 0.0;
    auto to = [](P2 p, P2 s, P2 e) {
        const double ex = e.x - s.x;
        const double ez = e.z - s.z;
        const double l2 = (ex * ex) + (ez * ez);
        const double t = l2 > 0 ? pm::max(0.0, pm::min(1.0, (((p.x - s.x) * ex) + ((p.z - s.z) * ez)) / l2)) : 0.0;
        const double dx = p.x - (s.x + (t * ex));
        const double dz = p.z - (s.z + (t * ez));
        return std::sqrt((dx * dx) + (dz * dz));
    };
    return pm::min(pm::min(to(a, c, d), to(b, c, d)), pm::min(to(c, a, b), to(d, a, b)));
}

/// A style's colour by day and at night as an element's paint.
[[nodiscard]] Paint paint_of(const tl::DayNight& c) noexcept { return {.day = rgba(c.day), .night = rgba(c.night)}; }

/// draw_over's ORDER: the stable sort of S['aero'] by kind (the packer's GroundKeys.AERO_ORDER), or none for a kind the
/// renders do not draw.
[[nodiscard]] std::optional<int> aero_order(std::string_view kind) noexcept {
    if (kind == "stopway") return 0;
    if (kind == "taxiway" || kind == "taxilane") return 1;
    if (kind == "runway") return 2;
    if (kind == "parking_position") return 3;
    return std::nullopt;
}

/// A double's bits in an order that compares as the double does (-0 as +0, as Python's sort takes them).
[[nodiscard]] std::uint64_t orderable(double d) noexcept {
    if (d == 0.0) d = 0.0;
    const auto bits = std::bit_cast<std::uint64_t>(d);
    return (bits >> 63U) != 0 ? ~bits : bits | (std::uint64_t{1} << 63U);
}

/// Builds one tile's ground.
class Builder {
public:
    Builder(const tl::Tile& tile, const tl::RenderHead& head, const tl::Style& style, std::uint8_t level,
            GroundTile& out) noexcept
        : tile_(tile), head_(head), style_(style), level_(level), out_(out) {}

    void build() {
        ground();
        roads();
        lines();
        water();
    }

private:
    /// A ring of a layer's geometry column in metres, simplified at the level when `simplified`.
    std::uint32_t ring(const tl::RingView& r, bool simplified) {
        const auto first = static_cast<std::uint32_t>(out_.points.size());
        const tl::Frame& f = head_.frame;
        if (simplified && level_ > 0 && r.has_keep) {
            kept_.resize(r.size());
            const tl::Simplified s = tl::simplify(r, level_, f, kept_);
            for (std::size_t k = 0; k < s.count; ++k)
                out_.points.push_back({f.x(r.lon[kept_[k]]), f.z(r.lat[kept_[k]])});
        } else {
            for (std::size_t k = 0; k < r.size(); ++k) out_.points.push_back({f.x(r.lon[k]), f.z(r.lat[k])});
        }
        return first;
    }

    [[nodiscard]] std::span<const P2> points(std::uint32_t first, std::uint32_t count) const noexcept {
        return std::span<const P2>(out_.points).subspan(first, count);
    }

    /// A fill over points [first, first + count), its box set.
    Fill& fill(FillKind kind, std::uint16_t g, std::uint32_t order, std::uint8_t cell, std::uint32_t first,
               std::uint32_t count, double y, double margin, double min_area, Paint paint) {
        Fill f{.kind = kind,
               .group = g,
               .sub = 0,
               .cell = cell,
               .order = order,
               .first = first,
               .count = count,
               .y = y,
               .margin = margin,
               .min_area = min_area,
               .paint = paint};
        f.x0 = f.z0 = std::numeric_limits<double>::infinity();
        f.x1 = f.z1 = -std::numeric_limits<double>::infinity();
        for (const P2& p : points(first, count)) {
            f.x0 = pm::min(f.x0, p.x);
            f.x1 = pm::max(f.x1, p.x);
            f.z0 = pm::min(f.z0, p.z);
            f.z1 = pm::max(f.z1, p.z);
        }
        f.key = key_;
        out_.fills.push_back(f);
        return out_.fills.back();
    }

    /// A polyline over points [first, first + count) at height y; returns its index.
    std::uint32_t polyline(std::uint32_t first, std::uint32_t count, double y) {
        out_.polylines.push_back({.first = first, .count = count, .y = y});
        return static_cast<std::uint32_t>(out_.polylines.size() - 1);
    }

    /// Appends points; returns the first's index.
    std::uint32_t put(std::span<const P2> pts) {
        const auto first = static_cast<std::uint32_t>(out_.points.size());
        out_.points.insert(out_.points.end(), pts.begin(), pts.end());
        return first;
    }

    // ---- render's areas and regions.py's land cover (the ground layer)
    void ground() {
        const tl::Layer* l = tile_.layer(tl::LayerId::ground);
        if (l == nullptr) return;
        CellOf cell_of(*l);
        for (std::uint32_t i = 0; i < l->count; ++i) {
            const std::uint8_t cell = cell_of(i);
            const tl::RingView r = l->ring(tl::ground::ring, i);
            if (r.size() == 0) continue;
            const auto key = static_cast<std::uint64_t>(l->integer(tl::ground::ground_key, i));
            const std::string_view kind = l->string(tl::ground::kind, i).value_or("");
            key_ = {.element = element_of(*l, tl::ground::id, l->schema->slot("otype"), l->schema->slot("part"), i)};
            if (l->integer(tl::ground::source, i) == tl::ground::source_land) {
                // draw_under: every land ring whole, grouped by kind at 0.01; the canopy over forest, wood and scrub
                const std::optional<std::uint16_t> k = style_.land_kind(kind);
                // LAND[kind] would raise: the packer writes only LAND's kinds (and the GPU's groups hold so many)
                if (!k || *k >= group::land_kinds) continue;
                const std::uint32_t first = ring(r, false);
                const auto count = static_cast<std::uint32_t>(out_.points.size() - first);
                // regions.py's sort of S['land']: by LAND's draw order, then by area, largest first
                area_scratch_.clear();
                for (const P2& p : points(first, count)) area_scratch_.emplace_back(p.x, p.z);
                key_.sort = static_cast<std::uint64_t>(style_.land_orders[*k]);
                key_.sort2 = ~std::bit_cast<std::uint64_t>(std::fabs(signed_area(area_scratch_)));
                const Rgba c = rgba(style_.land_colours[*k]);
                const auto order = static_cast<std::uint32_t>(tl::ground_key::order(key));
                fill(FillKind::land, static_cast<std::uint16_t>(group::land + *k), order, cell, first, count, 0.01, 600,
                     0.3, {.day = c, .night = darken(c, 0.2)});
                if (in(kind, canopy_kinds)) {
                    fill(FillKind::canopy, group::canopy, order, cell, first, count, 0.02, 600, 0.3,
                         {.day = canopy_black, .night = canopy_black});
                }
                continue;
            }
            // render's areas: AREA's colour, a square in its mapped surface (TONE), five and a half times darker at
            // night, at 0.02, in data order
            Rgba c = rgba(style_.area_colour(kind));
            if (in(kind, surfaced) && style_.tone) {
                if (const auto sf = l->string(l->schema->slot("tag:surface"), i)) {
                    if (const auto s = style_.surface(*sf)) c = rgba(*s);
                }
            }
            const std::uint32_t first = ring(r, true);
            const auto count = static_cast<std::uint32_t>(out_.points.size() - first);
            fill(FillKind::area, group::area, static_cast<std::uint32_t>(tl::ground_key::order(key)), cell, first,
                 count, 0.02, 900, 0.2, {.day = c, .night = darken(c, 0.18)});
        }
    }

    // ---- render's roads (drawRoads): ribbons in their mapped material, and a bridge's lines
    void roads() {
        const tl::Layer* l = tile_.layer(tl::LayerId::road);
        if (l == nullptr) return;
        CellOf cell_of(*l);
        const std::uint8_t surface = l->schema->slot("tag:surface");
        const std::uint8_t surface_colour = l->schema->slot("tag:surface:colour");
        for (std::uint32_t i = 0; i < l->count; ++i) {
            const std::uint8_t cell = cell_of(i);
            const tl::RingView r = l->ring(tl::road::line, i);
            if (r.size() < 2) continue;
            const auto key = static_cast<std::uint64_t>(l->integer(tl::road::ground_key, i));
            const std::optional<std::uint16_t> g = group_of_key(key);
            if (!g) continue;
            key_ = {.element = element_of(*l, tl::road::id, 0xFF, 0xFF, i)};
            const std::string_view hw = l->string(tl::road::hw, i).value_or("");
            // the colour: SURF's for the surface, else the theme's road; a mapped surface:colour, else a cycleway red
            Rgba c = theme_colour(ThemeColour::road);
            const std::optional<std::string_view> sf = l->string(surface, i);
            if (sf) {
                if (const auto s = style_.surface(*sf)) c = rgba(*s);
            }
            const std::optional<std::string_view> sc = l->string(surface_colour, i);
            if (sc && !sc->empty()) {
                if (const auto s = style_.surface_colour(*sc)) c = rgba(*s);
            } else if (hw == "cycleway") {
                c = rgba(style_.cycleway);
            }
            const double w = l->decimal(tl::road::w, i).value_or(0.0);
            const bool bridge = l->boolean(tl::road::bridge, i).value_or(false);
            const double y = 0.05 + (bridge ? 0.4 : 0.0);
            centre_.clear();
            const std::uint32_t c0 = ring(r, false);
            centre_.assign(out_.points.begin() + c0, out_.points.end());
            out_.points.resize(c0);
            ribbon_.clear();
            ribbon(centre_, w / 2, ribbon_);
            const std::uint32_t first = put(ribbon_);
            const Paint paint{.day = c, .night = c.a == 0 ? c : darken(c, 0.2)};
            fill(FillKind::road, *g, static_cast<std::uint32_t>(tl::ground_key::order(key)), cell, first,
                 static_cast<std::uint32_t>(ribbon_.size()), y, 900, 0.2, paint);
            // the road loop's marks, the roads class by class, data order within a class: a bridge's lines (by day
            // without weather: the sides of ribbon(pts, w / 2 + 0.3) at 1.1, each a path), then a sett road's courses
            const bool sett = sf && *sf == "sett";
            if (bridge || sett) {
                key_.sort = *g - group::road;
                const auto marks = static_cast<std::uint64_t>(l->integer(tl::road::marks_key, i));
                if (marks != 0 && bridge) bridge_lines_of(cell, marks, w, centre_);
                if (marks != 0 && sett) setts_of(cell, marks, w, y, centre_);
            }
        }
    }

    void bridge_lines_of(std::uint8_t cell, std::uint64_t marks, double w, std::span<const P2> centre) {
        side_.clear();
        ribbon(centre, (w / 2) + 0.3, side_);
        const std::size_t n = centre.size();
        // s_ = -1: off[:n]; s_ = 1: off[n:][::-1]
        const std::uint32_t left = put(std::span<const P2>(side_).first(n));
        std::vector<P2> right(side_.begin() + static_cast<std::ptrdiff_t>(n), side_.end());
        std::ranges::reverse(right);
        const std::uint32_t right_first = put(right);
        const auto order = static_cast<std::uint32_t>(tl::ground_key::order(marks));
        for (const std::uint32_t first : {left, right_first}) {
            const std::uint32_t p = polyline(first, static_cast<std::uint32_t>(n), 1.1);
            out_.strokes.push_back({.kind = StrokeKind::bridge,
                                    .group = group::marks + 0,
                                    .sub = 0,
                                    .cell = cell,
                                    .order = order,
                                    .first = p,
                                    .count = 1,
                                    .width = {.css = style_.bridge_lines_width},
                                    .paint = {.day = rgba(style_.bridge_lines), .night = rgba(style_.bridge_lines)},
                                    .key = key_});
        }
    }

    /// A sett road's courses (amsterdam.py's road loop): along each segment of its centreline, k = 0, 0.6, ... while
    /// k < Lg (k summed as the port sums it), a line across its width at y + 0.01; one path, its lines one polyline of
    /// pairs, and each line's reach back.
    void setts_of(std::uint8_t cell, std::uint64_t marks, double w, double y, std::span<const P2> centre) {
        side_.clear();
        for (std::size_t i = 0; i + 1 < centre.size(); ++i) {
            const P2 a = centre[i];
            const P2 b = centre[i + 1];
            const double lg = pm::dist(std::array<double, 2>{a.x, a.z}, std::array<double, 2>{b.x, b.z});
            // ux, uz = (b[0] - a[0]) / (Lg or 1), ...
            const double l1 = lg == 0.0 ? 1.0 : lg;
            const double ux = (b.x - a.x) / l1;
            const double uz = (b.z - a.z) / l1;
            double k = 0.0;
            while (k < lg) {
                const double cx = a.x + (ux * k);
                const double cz = a.z + (uz * k);
                // (cx_ + uz * r['w'] / 2, y + 0.01, cz_ - ux * r['w'] / 2), then the other side
                side_.push_back({.x = cx + (uz * w / 2), .z = cz - (ux * w / 2)});
                side_.push_back({.x = cx - (uz * w / 2), .z = cz + (ux * w / 2)});
                k += setts_step;
            }
        }
        if (side_.empty()) return;
        const auto lines = static_cast<std::uint32_t>(side_.size() / 2);
        const auto reach = static_cast<std::uint32_t>(out_.reaches.size());
        // each line's reaches: the farthest earlier line within each tolerance of it, six bits a level
        for (std::size_t j = 0; j < lines; ++j) {
            std::array<std::uint32_t, setts_tolerances.size()> back{};
            for (std::uint32_t t = 1; t <= std::min<std::size_t>(j, setts_back); ++t) {
                const std::size_t k = j - t;
                const double d = segment_distance(side_[2 * j], side_[(2 * j) + 1], side_[2 * k], side_[(2 * k) + 1]);
                for (std::size_t l = 0; l < back.size(); ++l) {
                    if (d <= setts_tolerances.at(l)) back.at(l) = t;
                }
            }
            std::uint32_t word = 0;
            for (std::size_t l = 0; l < back.size(); ++l) word |= back.at(l) << (6 * l);
            out_.reaches.push_back(word);
        }
        const std::uint32_t first = put(side_);
        out_.polylines.push_back({.first = first, .count = 2 * lines, .y = y + 0.01, .pairs = true, .reach = reach});
        const auto p = static_cast<std::uint32_t>(out_.polylines.size() - 1);
        Rgba paint = rgba(style_.sett_marks);
        paint.a = static_cast<std::uint8_t>(std::lround(style_.sett_marks_opacity * 255.0));
        out_.strokes.push_back({.kind = StrokeKind::setts,
                                .group = group::marks + 0,
                                .sub = 1,
                                .cell = cell,
                                .order = static_cast<std::uint32_t>(tl::ground_key::order(marks)),
                                .first = p,
                                .count = 1,
                                .width = {.css = style_.sett_marks_width},
                                .paint = {.day = paint, .night = paint},
                                .opacity = style_.sett_marks_opacity,
                                .from = centre.front(),
                                .key = key_});
    }

    // ---- the line layer: tram tracks (S['rails']), and draw_over's rail, big roads, runways and taxiways, marine
    // works and park edges
    void lines() {
        const tl::Layer* l = tile_.layer(tl::LayerId::line);
        if (l == nullptr) return;
        CellOf cell_of(*l);
        const std::uint8_t crane = l->schema->slot("crane");
        const std::uint8_t closed = l->schema->slot("closed");
        const std::uint8_t lanes_tag = l->schema->slot("tag:lanes");
        const std::uint8_t width_tag = l->schema->slot("tag:width");
        const std::uint8_t ref_tag = l->schema->slot("tag:ref");
        for (std::uint32_t i = 0; i < l->count; ++i) {
            const std::uint8_t cell = cell_of(i);
            const tl::RingView r = l->ring(tl::line::line, i);
            if (r.size() < 2) continue;
            const auto key = static_cast<std::uint64_t>(l->integer(tl::line::ground_key, i));
            const std::optional<std::uint16_t> g = group_of_key(key);
            const auto order = static_cast<std::uint32_t>(tl::ground_key::order(key));
            key_ = {.element = element_of(*l, tl::line::id, tl::line::otype, tl::line::part, i)};
            centre_.clear();
            const std::uint32_t c0 = ring(r, false);
            const auto n = static_cast<std::uint32_t>(out_.points.size() - c0);
            centre_.assign(out_.points.begin() + c0, out_.points.end());
            switch (l->integer(tl::line::source, i)) {
            case 0: tram(cell, order, c0, n); break;
            case 1:
                if (g) rail(cell, *g, order, c0, n, l->boolean(crane, i).value_or(false));
                break;
            case 2:
                if (g) {
                    // draw_over's big roads: sorted by their layer, data order within one
                    key_.sort = orderable(l->decimal(tl::line::layer, i).value_or(0.0));
                    big(cell, *g, order, c0, n, l->string(tl::line::hw, i).value_or(""),
                        l->decimal(tl::line::layer, i).value_or(0.0), l->string(lanes_tag, i), l->string(width_tag, i));
                }
                break;
            case 3:
                if (g) {
                    // draw_over's runways and taxiways: sorted by their kind (ORDER), data order within one
                    const std::string_view kind = l->string(tl::line::kind, i).value_or("");
                    const std::optional<int> rank = aero_order(kind);
                    if (!rank) break;
                    key_.sort = orderable(static_cast<double>(*rank));
                    aero(cell, *g, order, c0, n, kind, l->string(width_tag, i), l->string(ref_tag, i));
                }
                break;
            case 5:
                if (g) {
                    marine_of(cell, *g, order, c0, n, l->string(tl::line::kind, i).value_or(""),
                              l->boolean(closed, i).value_or(false));
                }
                break;
            case 6:
                if (g) park_edge(cell, *g, order, c0, n);
                break;
            default: break; // power lines, gantries, conveyors and a park's coaster tracks stand up (the region items)
            }
        }
    }

    /// groundMarks' tram tracks: for off in (-0.72, 0.72), the side of ribbon(rl, 0.72) at 0.08, each its own path.
    void tram(std::uint8_t cell, std::uint32_t order, std::uint32_t c0, std::uint32_t n) {
        side_.clear();
        ribbon(points(c0, n), 0.72, side_);
        const std::uint32_t left = put(std::span<const P2>(side_).first(n));
        std::vector<P2> right(side_.begin() + n, side_.end());
        std::ranges::reverse(right);
        const std::uint32_t right_first = put(right);
        for (const std::uint32_t first : {left, right_first}) {
            const std::uint32_t p = polyline(first, n, 0.08);
            out_.strokes.push_back({.kind = StrokeKind::tram,
                                    .group = group::marks + 1,
                                    .sub = 0,
                                    .cell = cell,
                                    .order = order,
                                    .first = p,
                                    .count = 1,
                                    .width = {.css = style_.trams_width},
                                    .paint = {.day = rgba(style_.trams.day), .night = rgba(style_.trams.night)},
                                    .key = key_});
        }
    }

    /// draw_over's rail: the ballast (its ribbon; a crane rail's 0.5, tested and not drawn) and the two rails.
    void rail(std::uint8_t cell, std::uint16_t g, std::uint32_t order, std::uint32_t c0, std::uint32_t n,
              bool is_crane) {
        if (!is_crane) {
            ribbon_.clear();
            ribbon(points(c0, n), style_.ballast_half_width, ribbon_);
            const std::uint32_t first = put(ribbon_);
            const Rgba ballast = rgba(style_.ballast);
            Fill& f = fill(FillKind::rail, g, order, cell, first, static_cast<std::uint32_t>(ribbon_.size()), 0.04, 600,
                           0.3, {.day = ballast, .night = darken(ballast, 0.2)});
            f.line = c0;
            f.line_count = n;
        }
        // lineD(offset_line(+0.72)) + lineD(offset_line(-0.72)): one path, two polylines
        const auto p0 = static_cast<std::uint32_t>(out_.polylines.size());
        for (const double off : {0.72, -0.72}) {
            side_.clear();
            offset_line(points(c0, n), off, side_);
            polyline(put(side_), n, 0.08);
        }
        out_.strokes.push_back({.kind = StrokeKind::rail,
                                .group = g,
                                .sub = 1,
                                .cell = cell,
                                .order = order,
                                .first = p0,
                                .count = 2,
                                .width = {.css = style_.rails_width},
                                .paint = {.day = rgba(style_.rails.day), .night = rgba(style_.rails.night)},
                                .line = c0,
                                .line_count = n,
                                .key = key_});
    }

    /// draw_over's big road: its asphalt, its edge lines and its lane lines.
    void big(std::uint8_t cell, std::uint16_t g, std::uint32_t order, std::uint32_t c0, std::uint32_t n,
             std::string_view hw, double layer, std::optional<std::string_view> lanes_tag,
             std::optional<std::string_view> width_tag) {
        const bool link = hw.ends_with("link");
        const bool motorway = hw == "motorway";
        const std::int64_t lanes = pm::to_int(num(lanes_tag, link ? 1.0 : 2.0));
        const double w = num(width_tag, (static_cast<double>(lanes) * 3.5) + (motorway ? 3.0 : 1.0));
        const double y = 0.06 + (pm::max(0.0, layer) * 0.5);
        ribbon_.clear();
        ribbon(points(c0, n), w / 2, ribbon_);
        const std::uint32_t first = put(ribbon_);
        const auto asphalt = static_cast<std::uint32_t>(out_.fills.size());
        Fill& f = fill(FillKind::big, g, order, cell, first, static_cast<std::uint32_t>(ribbon_.size()), y, 600, 0.3,
                       {.day = rgba(style_.asphalt.day), .night = rgba(style_.asphalt.night)});
        f.line = c0;
        f.line_count = n;
        const Width sw{.css = 0, .metres = style_.road_lines_width, .at = out_.points[c0 + (n / 2)]};
        const Paint line_paint{.day = rgba(style_.road_lines.day), .night = rgba(style_.road_lines.night)};
        // the edge lines: lineD(offset(w / 2 - 0.5)) + lineD(offset(-(w / 2 - 0.5))), at y + 0.01
        const auto e0 = static_cast<std::uint32_t>(out_.polylines.size());
        for (const double off : {(w / 2) - 0.5, -((w / 2) - 0.5)}) {
            side_.clear();
            offset_line(points(c0, n), off, side_);
            polyline(put(side_), n, y + 0.01);
        }
        out_.strokes.push_back({.kind = StrokeKind::edges,
                                .group = g,
                                .sub = 1,
                                .cell = cell,
                                .order = order,
                                .first = e0,
                                .count = 2,
                                .width = sw,
                                .paint = line_paint,
                                .fill = asphalt,
                                .line = c0,
                                .line_count = n,
                                .key = key_});
        if (lanes <= 1) return;
        // the lane lines: dashes (the style's, 3 on and 9 off) along offset(x0 + lw · i) for i in 1 to lanes - 1
        const double lw = (w - (motorway ? 3.0 : 1.0)) / static_cast<double>(lanes);
        const double x0 = -(w / 2) + (motorway ? 2.0 : 0.5);
        const auto d0 = static_cast<std::uint32_t>(out_.polylines.size());
        for (std::int64_t k = 1; k < lanes; ++k) {
            side_.clear();
            offset_line(points(c0, n), x0 + (lw * static_cast<double>(k)), side_);
            dashes_.clear();
            dashes(side_, style_.lane_dash[0], style_.lane_dash[1], dashes_);
            for (const Dash& d : dashes_) {
                const std::array<P2, 2> ab{d.a, d.b};
                polyline(put(ab), 2, y + 0.01);
            }
        }
        out_.strokes.push_back({.kind = StrokeKind::lanes,
                                .group = g,
                                .sub = 2,
                                .cell = cell,
                                .order = order,
                                .first = d0,
                                .count = static_cast<std::uint32_t>(out_.polylines.size()) - d0,
                                .width = sw,
                                .paint = line_paint,
                                .fill = asphalt,
                                .line = c0,
                                .line_count = n,
                                .key = key_});
    }

    /// draw_over's runways and taxiways: a runway's pavement, centre line and threshold bars (its designators are the
    /// frame's: passes/Ground.cpp), a taxiway's (a taxilane's, a stopway's) pavement and its line, a stand's lead-in
    /// line. The line's points are centre_.
    void aero(std::uint8_t cell, std::uint16_t g, std::uint32_t order, std::uint32_t c0, std::uint32_t n,
              std::string_view kind, std::optional<std::string_view> width_tag, std::optional<std::string_view> ref) {
        if (kind == "runway") {
            runway(cell, g, order, c0, n, num(width_tag, 45.0), ref);
            return;
        }
        if (kind == "taxiway" || kind == "taxilane" || kind == "stopway") {
            const double w = num(width_tag, kind == "taxiway" ? 23.0 : 15.0);
            ribbon_.clear();
            ribbon(centre_, w / 2, ribbon_);
            const std::uint32_t first = put(ribbon_);
            Fill& f = fill(FillKind::taxiway, g, order, cell, first, static_cast<std::uint32_t>(ribbon_.size()), 0.065,
                           600, 0.3, paint_of(style_.taxiway_pavement));
            f.line = c0;
            f.line_count = n;
            // its line, whether or not its pavement is drawn
            aero_line(StrokeKind::taxi, cell, g, order, aero_sub::centre, c0, n, style_.taxiway_lines_width,
                      centre_[n / 2]);
            return;
        }
        // a stand's lead-in line, px at its first point
        aero_line(StrokeKind::lead_in, cell, g, order, aero_sub::pavement, c0, n, style_.lead_in_width, centre_[0]);
    }

    /// A taxiway's line or a stand's lead-in: line_d(pts, 0.08), px(cam, metres, at), in the taxiways' lines' colour.
    void aero_line(StrokeKind kind, std::uint8_t cell, std::uint16_t g, std::uint32_t order, std::uint8_t sub,
                   std::uint32_t c0, std::uint32_t n, double metres, P2 at) {
        const std::uint32_t p = polyline(c0, n, 0.08);
        out_.strokes.push_back({.kind = kind,
                                .group = g,
                                .sub = sub,
                                .cell = cell,
                                .order = order,
                                .first = p,
                                .count = 1,
                                .width = {.css = 0, .metres = metres, .at = at},
                                .paint = paint_of(style_.taxiway_lines),
                                .line = c0,
                                .line_count = n,
                                .key = key_});
    }

    /// draw_over's runway (`if not d: continue`: what follows its pavement is drawn only with it): its pavement, the
    /// dashes of its centre line, and runway_ends' threshold bars at each end, eight on each side of the centre line,
    /// 1.8 m wide, from 6 to 36 m in, each its own fill (an end's drawn bars are one path).
    void runway(std::uint8_t cell, std::uint16_t g, std::uint32_t order, std::uint32_t c0, std::uint32_t n, double w,
                std::optional<std::string_view> ref) {
        ribbon_.clear();
        ribbon(centre_, w / 2, ribbon_);
        const std::uint32_t first = put(ribbon_);
        const auto pavement = static_cast<std::uint32_t>(out_.fills.size());
        Fill& f = fill(FillKind::runway, g, order, cell, first, static_cast<std::uint32_t>(ribbon_.size()), 0.07, 600,
                       0.3, paint_of(style_.runway_pavement));
        f.line = c0;
        f.line_count = n;
        const Paint marks = paint_of(style_.runway_marks);
        // dashes(cam, pts, 0.09, 30, 20), px(0.9) at the middle point: one path
        const auto d0 = static_cast<std::uint32_t>(out_.polylines.size());
        dashes_.clear();
        dashes(centre_, style_.runway_centre_dash[0], style_.runway_centre_dash[1], dashes_);
        for (const Dash& d : dashes_) {
            const std::array<P2, 2> ab{d.a, d.b};
            polyline(put(ab), 2, 0.09);
        }
        out_.strokes.push_back({.kind = StrokeKind::centre,
                                .group = g,
                                .sub = aero_sub::centre,
                                .cell = cell,
                                .order = order,
                                .first = d0,
                                .count = static_cast<std::uint32_t>(out_.polylines.size()) - d0,
                                .width = {.css = 0, .metres = style_.runway_centre_width, .at = centre_[n / 2]},
                                .paint = marks,
                                .fill = pavement,
                                .line = c0,
                                .line_count = n,
                                .key = key_});
        constexpr int nb = 8;
        constexpr double bw = 1.8;
        const double span = w - 6;
        for (std::uint8_t end = 0; end < 2; ++end) {
            const P2 p0 = end == 0 ? centre_[0] : centre_[n - 1];
            const P2 p1 = end == 0 ? centre_[1] : centre_[n - 2];
            const double dx = p1.x - p0.x;
            const double dz = p1.z - p0.z;
            double length = pm::hypot(dx, dz);
            if (length == 0.0) length = 1.0;
            const double ux = dx / length;
            const double uz = dz / length;
            const double nx = -uz;
            const double nz = ux;
            auto at = [&](double along, double across) {
                return P2{.x = (p0.x + (ux * along)) + (nx * across), .z = (p0.z + (uz * along)) + (nz * across)};
            };
            for (int i = 0; i < nb; ++i) {
                for (const double sgn : {-1.0, 1.0}) {
                    const double o = sgn * (3 + ((static_cast<double>(i) * ((span / 2) - 3)) / nb));
                    const std::array<P2, 4> q{at(6, o - (bw / 2)), at(36, o - (bw / 2)), at(36, o + (bw / 2)),
                                              at(6, o + (bw / 2))};
                    Fill& b = fill(FillKind::bars, g, order, cell, put(q), 4, 0.09, 200, 0.3, marks);
                    b.sub = static_cast<std::uint8_t>(aero_sub::bars + (2 * end));
                    b.with = pavement;
                }
            }
        }
        out_.runways.push_back({.pavement = pavement, .ref = std::string(ref.value_or("")), .marks = marks});
    }

    /// draw_over's national park's edge: line_d(ring, 0.15), 2.2 wide, dashed 7 on 5 off, the path at .85; drawn
    /// whatever the radius (its loop has no near()).
    void park_edge(std::uint8_t cell, std::uint16_t g, std::uint32_t order, std::uint32_t c0, std::uint32_t n) {
        Paint paint = paint_of(style_.park_edges);
        paint.day.a = paint.night.a = static_cast<std::uint8_t>(std::lround(style_.park_edges_opacity * 255.0));
        out_.strokes.push_back({.kind = StrokeKind::park,
                                .group = g,
                                .sub = 0,
                                .cell = cell,
                                .order = order,
                                .first = polyline(c0, n, 0.15),
                                .count = 1,
                                .width = {.css = style_.park_edges_width},
                                .paint = paint,
                                .opacity = style_.park_edges_opacity,
                                .dash = style_.park_edges_dash,
                                .key = key_});
    }

    /// draw_over's marine works: a closed way as it is, else its ribbon, at 0.3.
    void marine_of(std::uint8_t cell, std::uint16_t g, std::uint32_t order, std::uint32_t c0, std::uint32_t n,
                   std::string_view kind, bool is_closed) {
        const tl::MarineStyle* m = style_.marine_of(kind);
        if (m == nullptr) return;
        std::uint32_t first = c0;
        std::uint32_t count = n;
        if (!is_closed) {
            ribbon_.clear();
            ribbon(points(c0, n), m->width / 2, ribbon_);
            first = put(ribbon_);
            count = static_cast<std::uint32_t>(ribbon_.size());
        }
        const Rgba c = rgba(m->colour);
        Fill& f =
            fill(FillKind::marine, g, order, cell, first, count, 0.3, 600, 0.3, {.day = c, .night = darken(c, 0.2)});
        f.line = c0;
        f.line_count = n;
    }

    // ---- the water's pieces
    void water() {
        const tl::Layer* l = tile_.layer(tl::LayerId::water);
        if (l == nullptr) return;
        CellOf cell_of(*l);
        for (std::uint32_t i = 0; i < l->count; ++i) {
            const std::uint8_t cell = cell_of(i);
            const tl::RingView r = l->ring(tl::water::ring, i);
            if (r.size() < 3) continue;
            const bool hole = l->boolean(tl::water::hole, i).value_or(false);
            const std::uint32_t first = ring(r, false);
            const auto count = static_cast<std::uint32_t>(out_.points.size() - first);
            orient_water(std::span<P2>(out_.points).subspan(first, count), hole);
            Fill& f = fill(FillKind::water, group::water, 0, cell, first, count, 0.0, 900, 0.2,
                           {.day = theme_colour(ThemeColour::water), .night = theme_colour(ThemeColour::water)});
            f.ring = static_cast<std::uint32_t>(l->integer(tl::water::index, i));
            f.hole = hole;
            f.level = l->decimal(tl::water::level, i).value_or(style_.water_y);
        }
    }

    const tl::Tile& tile_;
    const tl::RenderHead& head_;
    const tl::Style& style_;
    std::uint8_t level_;
    GroundTile& out_;
    OrderKey key_;                 ///< the feature being built's: each of its fills and strokes takes it
    std::vector<V2> area_scratch_; ///< a land ring in V2, for its area as the loader's sort takes it
    std::vector<std::uint32_t> kept_;
    std::vector<P2> centre_;
    std::vector<P2> ribbon_;
    std::vector<P2> side_;
    std::vector<Dash> dashes_;
};

} // namespace

std::optional<std::uint16_t> group_of_key(std::uint64_t key) noexcept {
    const std::uint32_t stage = tl::ground_key::stage(key);
    const std::uint32_t cls = tl::ground_key::klass(key);
    switch (stage) {
    case tl::ground_key::water: return group::water;
    case tl::ground_key::area: return group::area;
    case tl::ground_key::road: return cls < 16 ? std::optional<std::uint16_t>(group::road + cls) : std::nullopt;
    case tl::ground_key::over: return cls < 8 ? std::optional<std::uint16_t>(group::over + cls) : std::nullopt;
    case tl::ground_key::marks: return cls < 8 ? std::optional<std::uint16_t>(group::marks + cls) : std::nullopt;
    default: return std::nullopt; // land: its kind's group
    }
}

Rgba darken(Rgba c, double k) noexcept {
    // tohex: '#%02x' of max(0, min(255, int(round(v)))) for v in mulc(hx(c), k)
    auto ch = [k](std::uint8_t v) {
        const std::int64_t r = pm::round(static_cast<double>(v) * k);
        return static_cast<std::uint8_t>(std::clamp<std::int64_t>(r, 0, 255));
    };
    return {.r = ch(c.r), .g = ch(c.g), .b = ch(c.b), .a = c.a};
}

std::size_t GroundTile::bytes() const noexcept {
    return sizeof(GroundTile) + (points.capacity() * sizeof(P2)) + (fills.capacity() * sizeof(Fill)) +
           (strokes.capacity() * sizeof(Stroke)) + (polylines.capacity() * sizeof(Polyline)) +
           (polyline_words.capacity() * sizeof(std::uint32_t)) + (reaches.capacity() * sizeof(std::uint32_t)) +
           (shadows.capacity() * sizeof(Shadow)) + (shadow_points.capacity() * sizeof(P2)) + casters_bytes +
           (runways.capacity() * sizeof(RunwayRef)) + (tracks.capacity() * sizeof(ShadowTrack)) +
           (heights.capacity() * sizeof(double)) + (woods.capacity() * sizeof(ShadowTree));
}

void build_shadows(const sun::TileCasters& casters, GroundTile& out) {
    const sun::TileSolids& s = casters.solids;
    // a shadow's corners are 16 bits on the GPU, and its place in the tile 16 more
    constexpr std::size_t most = 0xFFFF;
    std::vector<P2> ring;
    std::vector<double> hx;
    std::vector<double> hz;
    for (std::uint32_t i = 0; i < s.size(); ++i) {
        const std::span<const double> xs = s.xs(i);
        const std::span<const double> zs = s.zs(i);
        if (xs.empty() || xs.size() + 2 > most || out.shadows.size() >= most) continue;
        ring.clear();
        for (std::size_t k = 0; k < xs.size(); ++k) ring.push_back({xs[k], zs[k]});
        Shadow sh{.h = s.h[i], .y = 0.1, .at = centroid(ring), .owner = i};
        sh.first = static_cast<std::uint32_t>(out.shadow_points.size());
        if (s.min[i] > 0) {
            // a raised part: its top's outline moved (`if bot > 0`), as the ring is
            if (ring.size() < 3) continue;
            sh.move = ShadowMove::moved;
            out.shadow_points.insert(out.shadow_points.end(), ring.begin(), ring.end());
        } else {
            // a prism: the hull of its footprint, swept on the GPU (hull2 of the footprint and its moved copy is the
            // footprint's hull swept along the offset)
            hx.clear();
            hz.clear();
            sun::hull2(xs, zs, hx, hz);
            if (hx.size() < 2) continue;
            sh.move = ShadowMove::sweep;
            std::vector<P2> hull;
            hull.reserve(hx.size());
            for (std::size_t k = 0; k < hx.size(); ++k) hull.push_back({.x = hx[k], .z = hz[k]});
            if (signed_area(hull) < 0) std::ranges::reverse(hull);
            out.shadow_points.insert(out.shadow_points.end(), hull.begin(), hull.end());
        }
        sh.count = static_cast<std::uint32_t>(out.shadow_points.size()) - sh.first;
        out.shadows.push_back(sh);
    }
    // the trees: their crown's 16-gon around the trunk, moved by the shadow of 0.7 of their height
    const sun::TileTrees& t = casters.trees;
    for (std::uint32_t i = 0; i < t.x.size() && out.shadows.size() < most; ++i) {
        const double r = t.crown[i] / 2;
        Shadow sh{.move = ShadowMove::moved,
                  .tree = true,
                  .first = static_cast<std::uint32_t>(out.shadow_points.size()),
                  .count = 16,
                  .h = t.h[i] * 0.7,
                  .y = t.gy[i] + 0.1,
                  .at = {.x = t.x[i], .z = t.z[i]},
                  .owner = i};
        for (int k = 0; k < 16; ++k) {
            const double a = (k * pm::pi) / 8;
            out.shadow_points.push_back({t.x[i] + (r * pm::cos(a)), t.z[i] + (r * pm::sin(a))});
        }
        out.shadows.push_back(sh);
    }
}

namespace {

/// Whether a fill's ring is a ribbon (city.py's ribbon: the left side, then the right side reversed), drawn as quads.
[[nodiscard]] bool is_ribbon(const Fill& f) noexcept {
    return f.kind == FillKind::road || f.kind == FillKind::rail || f.kind == FillKind::big ||
           f.kind == FillKind::runway || f.kind == FillKind::taxiway ||
           (f.kind == FillKind::marine && f.line != f.first);
}

/// The fills' draw order on the GPU, top first within a draw: draw_over's, then the roads, then the areas (above the
/// water), the land (below); in each, the later painter group, order and sub first.
[[nodiscard]] bool top_first(const Fill& a, const Fill& b) noexcept {
    if (a.group != b.group) return a.group > b.group;
    if (a.order != b.order) return a.order > b.order;
    return a.sub > b.sub;
}

[[nodiscard]] std::uint32_t bits(float v) noexcept { return std::bit_cast<std::uint32_t>(v); }

} // namespace

GroundGpu pack(const GroundTile& g) {
    GroundGpu out;
    // the vertices: every fill's ring, fill by fill
    std::vector<std::uint32_t> base(g.fills.size());
    std::uint32_t n = 0;
    for (std::size_t f = 0; f < g.fills.size(); ++f) {
        base[f] = n;
        n += g.fills[f].count;
    }
    auto& w = out.ground;
    out.vertices = {.first = 0, .count = n};
    w.reserve(std::size_t{n} * 3);
    for (std::size_t f = 0; f < g.fills.size(); ++f) {
        const Fill& fill = g.fills[f];
        for (std::uint32_t k = 0; k < fill.count; ++k) {
            const P2& p = g.points[fill.first + k];
            w.push_back(bits(static_cast<float>(p.x - g.origin.x)));
            w.push_back(bits(static_cast<float>(p.z - g.origin.z)));
            w.push_back(static_cast<std::uint32_t>(f));
        }
    }
    // the triangles, by draw
    std::vector<std::uint32_t> order(g.fills.size());
    for (std::uint32_t f = 0; f < order.size(); ++f) order[f] = f;
    std::ranges::stable_sort(order,
                             [&](std::uint32_t a, std::uint32_t b) { return top_first(g.fills[a], g.fills[b]); });
    std::vector<std::array<double, 2>> ring;
    std::vector<std::vector<std::array<double, 2>>> polygon(1);
    auto triangles = [&](std::uint32_t f, std::vector<std::uint32_t>& into) {
        const Fill& fill = g.fills[f];
        const std::uint32_t v0 = base[f];
        if (fill.count < 3) return;
        if (is_ribbon(fill)) {
            // quads between the two sides: (L_i, L_i+1, R_i+1), (L_i, R_i+1, R_i)
            const std::uint32_t m = fill.count / 2;
            for (std::uint32_t i = 0; i + 1 < m; ++i) {
                const std::uint32_t li = v0 + i;
                const std::uint32_t lj = v0 + i + 1;
                const std::uint32_t rj = v0 + (2 * m) - 2 - i;
                const std::uint32_t ri = v0 + (2 * m) - 1 - i;
                into.insert(into.end(), {li, lj, rj, li, rj, ri});
            }
            return;
        }
        polygon[0].clear();
        for (std::uint32_t k = 0; k < fill.count; ++k) {
            const P2& p = g.points[fill.first + k];
            polygon[0].push_back({p.x - g.origin.x, p.z - g.origin.z});
        }
        const std::vector<std::uint32_t> t = mapbox::earcut<std::uint32_t>(polygon);
        for (const std::uint32_t i : t) into.push_back(v0 + i);
    };
    std::vector<std::uint32_t> above;
    std::vector<std::uint32_t> below;
    std::vector<std::uint32_t> canopy;
    std::vector<std::uint32_t> fans;
    for (const std::uint32_t f : order) {
        const Fill& fill = g.fills[f];
        switch (fill.kind) {
        case FillKind::land: triangles(f, below); break;
        case FillKind::canopy: triangles(f, canopy); break;
        case FillKind::water: fan(base[f], fill.count, fans); break;
        default:
            if (fill.drawn) triangles(f, above);
            break;
        }
    }
    auto put = [&](const std::vector<std::uint32_t>& v) {
        const Words at{.first = static_cast<std::uint32_t>(w.size()), .count = static_cast<std::uint32_t>(v.size())};
        w.insert(w.end(), v.begin(), v.end());
        return at;
    };
    out.above = put(above);
    out.below = put(below);
    out.canopy = put(canopy);
    out.fans = put(fans);
    out.fills = {.first = static_cast<std::uint32_t>(w.size()), .count = static_cast<std::uint32_t>(g.fills.size())};
    for (const Fill& fill : g.fills) {
        // a water piece's record holds its water level (water_vs draws it at the street or there)
        w.push_back(bits(static_cast<float>(fill.kind == FillKind::water ? fill.level : fill.y)));
        w.push_back(std::uint32_t{fill.group} | (std::uint32_t{fill.sub} << 16U));
        w.push_back(fill.order);
        w.push_back(fill.paint.day.packed());
        w.push_back(fill.paint.night.packed());
    }
    // the lines: every polyline's points, then the polylines
    auto& l = out.lines;
    std::vector<std::uint32_t> first(g.polylines.size());
    std::uint32_t points = 0;
    std::uint32_t words = 0;
    for (std::size_t p = 0; p < g.polylines.size(); ++p) {
        first[p] = words;
        points += g.polylines[p].count;
        words += polyline_words_of(g.polylines[p]);
    }
    out.points = {.first = 0, .count = points};
    l.reserve(std::size_t{words} + (g.polylines.size() * 4));
    for (const Polyline& pl : g.polylines) {
        for (std::uint32_t k = 0; k < pl.count; ++k) {
            const P2& p = g.points[pl.first + k];
            l.push_back(bits(static_cast<float>(p.x - g.origin.x)));
            l.push_back(bits(static_cast<float>(p.z - g.origin.z)));
            // a coaster track's shadow's: each point's height after it
            if (pl.heights) l.push_back(bits(static_cast<float>(g.heights[pl.height + k])));
        }
        // a pair polyline's lines' reaches
        if (pl.pairs) l.insert(l.end(), g.reaches.begin() + pl.reach, g.reaches.begin() + pl.reach + (pl.count / 2));
    }
    out.lines_at = {.first = static_cast<std::uint32_t>(l.size()),
                    .count = static_cast<std::uint32_t>(g.polylines.size())};
    for (std::size_t p = 0; p < g.polylines.size(); ++p) {
        l.push_back(first[p]);
        l.push_back(g.polylines[p].count);
        l.push_back(bits(static_cast<float>(g.polylines[p].y)));
        l.push_back(0);
    }
    // the shadows: their points, their records, their triangles over their corners ((shadow << 16) | corner)
    out.shadow_points = {.first = static_cast<std::uint32_t>(w.size()),
                         .count = static_cast<std::uint32_t>(g.shadow_points.size())};
    for (const P2& p : g.shadow_points) {
        w.push_back(bits(static_cast<float>(p.x - g.origin.x)));
        w.push_back(bits(static_cast<float>(p.z - g.origin.z)));
    }
    out.shadows = {.first = static_cast<std::uint32_t>(w.size()),
                   .count = static_cast<std::uint32_t>(g.shadows.size())};
    for (const Shadow& sh : g.shadows) {
        w.push_back(sh.first);
        w.push_back(sh.count);
        w.push_back(bits(static_cast<float>(sh.h)));
        w.push_back((sh.move == ShadowMove::moved ? shadow_flag::moved : 0U) | (sh.tree ? shadow_flag::tree : 0U));
        w.push_back(bits(static_cast<float>(sh.y)));
    }
    std::vector<std::uint32_t> casters;
    std::vector<std::uint32_t> woods;
    for (std::uint32_t s = 0; s < g.shadows.size(); ++s) {
        const Shadow& sh = g.shadows[s];
        const std::uint32_t v0 = s << 16U;
        // the woods' shadows a union of their own (draw_over's), after the casters'
        std::vector<std::uint32_t>& draw = s < g.woods_first() ? casters : woods;
        if (sh.move == ShadowMove::sweep) {
            // the swept hull: count + 2 corners (its points, its two turning points twice), a convex fan
            for (std::uint32_t k = 1; k <= sh.count; ++k) draw.insert(draw.end(), {v0, v0 + k, v0 + k + 1});
        } else if (sh.tree) {
            for (std::uint32_t k = 1; k + 1 < sh.count; ++k) draw.insert(draw.end(), {v0, v0 + k, v0 + k + 1});
        } else {
            polygon[0].clear();
            for (std::uint32_t k = 0; k < sh.count; ++k) {
                const P2& p = g.shadow_points[sh.first + k];
                polygon[0].push_back({p.x - g.origin.x, p.z - g.origin.z});
            }
            for (const std::uint32_t i : mapbox::earcut<std::uint32_t>(polygon)) draw.push_back(v0 + i);
        }
    }
    out.shadow_draw = put(casters);
    out.woods_draw = put(woods);
    return out;
}

tiles::Result<std::uint64_t> ground_check(const tiles::Pack& pack) {
    const tiles::RenderHead* head = pack.head();
    if (head == nullptr || head->levels.empty()) {
        return std::unexpected(tiles::error(tiles::Fault::missing, tiles::Site::none));
    }
    if (pack.style() == nullptr) return std::unexpected(tiles::error(tiles::Fault::missing, tiles::Site::style));
    const tiles::Style& style = *pack.style();
    tiles::Zstd zstd;
    tiles::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    Arena arena(4U << 20U, Growth::grows, "ground check");
    tiles::Sha256 sha;
    const auto put = [&sha](std::span<const std::uint32_t> words) {
        const std::array<std::uint32_t, 1> n{static_cast<std::uint32_t>(words.size())};
        sha.update(std::as_bytes(std::span(n)));
        sha.update(std::as_bytes(words));
    };
    for (const tiles::ChunkRef& c : pack.chunks().level(head->levels.front())) {
        arena.reset();
        auto tile = pack.decode(c, zstd, decoder, arena, raw, scratch);
        if (!tile) return std::unexpected(tile.error());
        const P2 origin{.x = head->frame.x(tile->header.west), .z = head->frame.z(tile->header.south)};
        const GroundGpu gpu = geometry::pack(build_ground(*tile, *head, style, 0, origin));
        put(gpu.ground);
        put(gpu.lines);
    }
    const tiles::Sha256Digest d = sha.finish();
    std::uint64_t h = 0;
    for (std::size_t k = 0; k < 8; ++k) h = (h << 8U) | static_cast<std::uint64_t>(d[k]);
    return h;
}

namespace {

[[nodiscard]] std::string hex16(std::uint64_t v) {
    std::string s(16, '0');
    for (int i = 15; i >= 0; --i) {
        s[static_cast<std::size_t>(i)] = "0123456789abcdef"[v & 0xFU];
        v >>= 4U;
    }
    return s;
}

} // namespace

std::string ground_line(const tiles::Pack& pack) {
    const auto h = ground_check(pack);
    if (!h) return "ground unavailable: " + tiles::describe(h.error());
    if (*h == ground_check_expected) return "ground " + hex16(*h) + " ok";
    return "ground " + hex16(*h) + " FAIL expected " + hex16(ground_check_expected);
}

std::string ground_line(int fd, std::uint64_t offset, std::uint64_t length) {
    auto source = tiles::MappedSource::open(fd, offset, length);
    if (!source) return "ground unavailable: " + tiles::describe(source.error());
    auto pack = tiles::Pack::open(std::move(*source));
    if (!pack) return "ground unavailable: " + tiles::describe(pack.error());
    return ground_line(*pack);
}

namespace {

/// draw_over's park shadows' sources (the region items'): each coaster track's subdivided points with their heights
/// (its pieces end to end), a polyline at 0.12 whose points carry them; each planted tree's shadow, its 12-gon about
/// its trunk (woods.draw's r · 0.95 at k · π / 6) moved by the shadow of 0.72 of its height, after the casters'.
void park_shadows(const tl::Tile& tile, const tl::RenderHead& head, GroundTile& out) {
    const TileRegionItems items = build_region_items({.tile = &tile, .head = &head, .kind = 0});
    for (const CoasterTrackSpan& span : items.tracks) {
        if (span.count == 0) continue;
        const RegionItem& start = items.items[span.first];
        const auto first = static_cast<std::uint32_t>(out.points.size());
        const auto height = static_cast<std::uint32_t>(out.heights.size());
        out.points.push_back({.x = start.x, .z = start.z});
        out.heights.push_back(start.ya);
        for (std::uint32_t k = 0; k < span.count; ++k) {
            const RegionItem& piece = items.items[span.first + k];
            out.points.push_back({.x = piece.bx, .z = piece.bz});
            out.heights.push_back(piece.yb);
        }
        out.polylines.push_back(
            {.first = first, .count = span.count + 1, .y = 0.12, .heights = true, .height = height});
        out.tracks.push_back({.polyline = static_cast<std::uint32_t>(out.polylines.size() - 1),
                              .wooden = span.kind == 1,
                              .cut = span.cut,
                              .index = start.index});
    }
    // a shadow's place in the tile is 16 bits on the GPU
    constexpr std::size_t most = 0xFFFF;
    for (const RegionItem& it : items.items) {
        if (it.kind != RegionItemKind::wood || out.shadows.size() >= most) continue;
        const double r = it.crown / 2;
        out.shadows.push_back({.move = ShadowMove::moved,
                               .tree = true,
                               .first = static_cast<std::uint32_t>(out.shadow_points.size()),
                               .count = 12,
                               .h = it.h * 0.72,
                               .y = 0.12,
                               .at = {.x = it.x, .z = it.z},
                               .owner = it.index});
        for (int k = 0; k < 12; ++k) {
            const double a = (k * pm::pi) / 6;
            out.shadow_points.push_back({.x = it.x + (r * 0.95 * pm::cos(a)), .z = it.z + (r * 0.95 * pm::sin(a))});
        }
        out.woods.push_back({.x = it.x, .z = it.z, .h = it.h, .r = r, .index = it.index});
    }
}

} // namespace

GroundTile build_ground(const tiles::Tile& tile, const tiles::RenderHead& head, const tiles::Style& style,
                        std::uint8_t level, P2 origin) {
    GroundTile out;
    out.origin = origin;
    Builder(tile, head, style, level, out).build();
    // the boxes of the lines draw_over's radius cut samples
    for (Fill& f : out.fills) {
        if (f.line_count > 0) f.near = near_box(std::span<const P2>(out.points).subspan(f.line, f.line_count));
    }
    for (Stroke& s : out.strokes) {
        if (s.line_count > 0) s.near = near_box(std::span<const P2>(out.points).subspan(s.line, s.line_count));
    }
    // each cell's box over its fills, at their heights, and their widest margin
    for (const Fill& f : out.fills) {
        if (f.kind == FillKind::water || f.cell >= max_cells || f.count == 0) continue;
        CellBox& c = out.cells[f.cell];
        if (c.empty()) {
            c = {.x0 = f.x0, .x1 = f.x1, .z0 = f.z0, .z1 = f.z1, .y0 = f.y, .y1 = f.y, .margin = f.margin};
            continue;
        }
        c.x0 = pm::min(c.x0, f.x0);
        c.x1 = pm::max(c.x1, f.x1);
        c.z0 = pm::min(c.z0, f.z0);
        c.z1 = pm::max(c.z1, f.z1);
        c.y0 = pm::min(c.y0, f.y);
        c.y1 = pm::max(c.y1, f.y);
        c.margin = pm::max(c.margin, f.margin);
    }
    // the shadows' casters: the solids and trees the sun's march reads at this level
    auto casters = std::make_shared<sun::TileCasters>(sun::solids_of(tile, head, level));
    const sun::TileSolids& s = casters->solids;
    const sun::TileTrees& t = casters->trees;
    out.casters_bytes =
        sizeof(sun::TileCasters) +
        ((s.x.capacity() + s.z.capacity() + s.h.capacity() + s.min.capacity()) * sizeof(double)) +
        ((s.first.capacity() + s.record.capacity()) * sizeof(std::uint32_t)) + s.source.capacity() +
        ((t.x.capacity() + t.z.capacity() + t.h.capacity() + t.crown.capacity() + t.gy.capacity()) * sizeof(double));
    out.no_shadows = style.no_shadows;
    // each caster's place in the renders' loops: the solids (buildings, then parts) and the trees by source order
    for (std::uint32_t i = 0; i < s.size(); ++i) {
        const bool part = s.source[i] == sun::Source::part;
        const tl::Layer* l = tile.layer(part ? tl::LayerId::part : tl::LayerId::building);
        const auto index = l != nullptr ? static_cast<std::uint64_t>(l->integer(tl::building::index, s.record[i])) : 0;
        out.solid_order.push_back((std::uint64_t{part ? 1U : 0U} << 40U) | index);
    }
    if (const tl::Layer* l = tile.layer(tl::LayerId::tree)) {
        for (std::uint32_t i = 0; i < l->count && i < t.x.size(); ++i) {
            out.tree_order.push_back((std::uint64_t{2} << 40U) |
                                     static_cast<std::uint64_t>(l->integer(tl::tree::index, i)));
        }
    }
    build_shadows(*casters, out);
    out.casters = std::move(casters);
    for (const Shadow& sh : out.shadows) {
        std::array<double, 4>& b = out.shadow_at;
        if (b[1] < b[0]) {
            b = {sh.at.x, sh.at.x, sh.at.z, sh.at.z};
            continue;
        }
        b = {pm::min(b[0], sh.at.x), pm::max(b[1], sh.at.x), pm::min(b[2], sh.at.z), pm::max(b[3], sh.at.z)};
    }
    park_shadows(tile, head, out);
    return out;
}

} // namespace atlas::geometry
