#include "tiles/Style.hpp"

#include <algorithm>
#include <cmath>
#include <format>

namespace atlas::tiles {

namespace {

/// The style's tables, read value by value: the first value missing or of another kind is kept as the error, and
/// every read after it gives a default.
class StyleReader {
public:
    explicit StyleReader(const StyleTables& tables) noexcept : tables_(tables) {}

    [[nodiscard]] const std::optional<TileError>& error() const noexcept { return error_; }

    /// The table `name`: a map, or the error.
    [[nodiscard]] std::optional<Value> table(std::string_view name) {
        const std::optional<Value> t = tables_.table(name);
        if (!t) return fail(Fault::missing, name);
        if (t->type() != ValueType::map) return fail(Fault::bad_value, name);
        return t;
    }

    /// The value `key` of table `t` (named `table`), or the error.
    [[nodiscard]] std::optional<Value> at(const std::optional<Value>& t, std::string_view table, std::string_view key) {
        if (!t) return std::nullopt;
        const std::optional<Value> v = t->find(key);
        if (!v) return fail(Fault::missing, table, key);
        return v;
    }

    /// Value `v` (named `table.key`) as a colour, a finite number.
    [[nodiscard]] StyleRgb colour_of(const std::optional<Value>& v, std::string_view table, std::string_view key) {
        if (!v) return 0;
        if (const auto s = v->string()) {
            if (const auto c = style_rgb(*s)) return *c;
        }
        fail(Fault::bad_value, table, key);
        return 0;
    }
    [[nodiscard]] double number_of(const std::optional<Value>& v, std::string_view table, std::string_view key) {
        if (!v) return 0.0;
        if (const auto n = v->number(); n && std::isfinite(*n)) return *n;
        fail(Fault::bad_value, table, key);
        return 0.0;
    }

    /// The value `key` of table `t` as a colour, a number, a colour by day and at night (keys `day` and `night`).
    [[nodiscard]] StyleRgb colour(const std::optional<Value>& t, std::string_view table, std::string_view key) {
        return colour_of(at(t, table, key), table, key);
    }
    [[nodiscard]] double number(const std::optional<Value>& t, std::string_view table, std::string_view key) {
        return number_of(at(t, table, key), table, key);
    }
    [[nodiscard]] DayNight day_night(const std::optional<Value>& t, std::string_view table, std::string_view day,
                                     std::string_view night) {
        return {.day = colour(t, table, day), .night = colour(t, table, night)};
    }

    /// Each entry of map `t` (named `table`) as a colour, in its order.
    void colours(const std::optional<Value>& t, std::string_view table,
                 std::vector<std::pair<std::string, StyleRgb>>& out) {
        if (!t) return;
        for (std::size_t i = 0; i < t->size(); ++i) {
            out.emplace_back(std::string(t->key(i)), colour_of(t->child(i), table, t->key(i)));
        }
    }

    /// Each entry of map `t` (named `table`) when it is a list of `n`, in its order.
    template <class F>
    void lists(const std::optional<Value>& t, std::string_view table, std::size_t n, F each) {
        if (!t) return;
        for (std::size_t i = 0; i < t->size(); ++i) {
            const Value entry = t->child(i);
            if (entry.type() != ValueType::list || entry.size() != n) {
                fail(Fault::bad_value, table, t->key(i));
                return;
            }
            each(t->key(i), entry);
        }
    }

    std::nullopt_t fail(Fault fault, std::string_view table, std::string_view key = {}) {
        if (!error_) error_ = style_error(fault, key.empty() ? std::string(table) : std::format("{}.{}", table, key));
        return std::nullopt;
    }

private:
    const StyleTables& tables_;
    std::optional<TileError> error_;
};

template <class T>
[[nodiscard]] auto find_in(const std::vector<std::pair<std::string, T>>& table, std::string_view key) noexcept {
    return std::ranges::find(table, key, &std::pair<std::string, T>::first);
}

} // namespace

std::optional<StyleRgb> style_rgb(std::string_view text) noexcept {
    if (text.size() != 4 && text.size() != 7) return std::nullopt;
    if (text[0] != '#') return std::nullopt;
    StyleRgb out = 0;
    for (std::size_t i = 1; i < text.size(); ++i) {
        const char c = text[i];
        std::uint32_t d = 0;
        if (c >= '0' && c <= '9') {
            d = static_cast<std::uint32_t>(c - '0');
        } else if (c >= 'a' && c <= 'f') {
            d = static_cast<std::uint32_t>(c - 'a' + 10);
        } else if (c >= 'A' && c <= 'F') {
            d = static_cast<std::uint32_t>(c - 'A' + 10);
        } else {
            return std::nullopt;
        }
        // #RGB: each digit twice
        out = text.size() == 4 ? (out << 8U) | (d << 4U) | d : (out << 4U) | d;
    }
    return out;
}

Result<Style> Style::of(const StyleTables& tables) {
    Style s;
    StyleReader r(tables);
    // LAND: kind -> [colour, draw order]
    r.lists(r.table("land"), "land", 2, [&](std::string_view kind, const Value& entry) {
        s.land_kinds.emplace_back(kind);
        s.land_colours.push_back(r.colour_of(entry.child(0), "land", kind));
        s.land_orders.push_back(entry.child(1).integer().value_or(0));
    });
    r.colours(r.table("area"), "area", s.areas);
    r.colours(r.table("surf"), "surf", s.surfaces);
    r.colours(r.table("surf_colour"), "surf_colour", s.surface_colours);
    const auto ground = r.table("ground");
    s.area_other = r.colour(ground, "ground", "area_other");
    s.cycleway = r.colour(ground, "ground", "cycleway");
    const auto rail = r.table("rail");
    s.ballast = r.colour(rail, "rail", "ballast");
    s.ballast_half_width = r.number(rail, "rail", "ballast_half_width");
    s.rails = r.day_night(rail, "rail", "rails_day", "rails_night");
    s.rails_width = r.number(rail, "rail", "rails_width");
    const auto big = r.table("big_roads");
    s.asphalt = r.day_night(big, "big_roads", "asphalt_day", "asphalt_night");
    s.road_lines = r.day_night(big, "big_roads", "lines_day", "lines_night");
    s.road_lines_width = r.number(big, "big_roads", "lines_width");
    if (const auto dash = r.at(big, "big_roads", "lane_dash")) {
        if (dash->type() == ValueType::list && dash->size() == 2) {
            s.lane_dash = {r.number_of(dash->child(0), "big_roads", "lane_dash"),
                           r.number_of(dash->child(1), "big_roads", "lane_dash")};
        } else {
            r.fail(Fault::bad_value, "big_roads", "lane_dash");
        }
    }
    s.runway_marks = r.day_night(r.table("runways"), "runways", "marks_day", "marks_night");
    // kind -> [width, colour]
    r.lists(r.table("marine"), "marine", 2, [&](std::string_view kind, const Value& entry) {
        s.marine.push_back({.kind = std::string(kind),
                            .width = r.number_of(entry.child(0), "marine", kind),
                            .colour = r.colour_of(entry.child(1), "marine", kind)});
    });
    const auto trams = r.table("trams");
    s.trams = r.day_night(trams, "trams", "day", "night");
    s.trams_width = r.number(trams, "trams", "width");
    const auto bridges = r.table("bridges");
    s.bridge_lines = r.colour(bridges, "bridges", "lines");
    s.bridge_lines_width = r.number(bridges, "bridges", "lines_width");
    const auto buildings = r.table("buildings");
    s.edges.roof = r.day_night(buildings, "buildings", "roof_edge_day", "roof_edge_night");
    s.edges.roof_slope = static_cast<float>(r.number(buildings, "buildings", "roof_edge_slope"));
    s.edges.roof_top = static_cast<float>(r.number(buildings, "buildings", "roof_edge_top"));
    s.edges.wall = static_cast<float>(r.number(buildings, "buildings", "wall_edge"));
    const auto boats = r.table("houseboats");
    s.hull = r.colour(boats, "houseboats", "hull");
    s.deck = r.colour(boats, "houseboats", "deck");
    s.edges.cabin = r.colour(boats, "houseboats", "cabin_edge");
    s.edges.cabin_width = static_cast<float>(r.number(boats, "houseboats", "cabin_edge_width"));
    const auto windows = r.table("windows");
    WindowsStyle& ws = s.windows;
    ws.glass = {r.colour(windows, "windows", "glass_top"), r.colour(windows, "windows", "glass_bottom")};
    ws.glass_overcast = {r.colour(windows, "windows", "glass_overcast_top"),
                         r.colour(windows, "windows", "glass_overcast_bottom")};
    ws.lit_glass = {r.colour(windows, "windows", "lit_top"), r.colour(windows, "windows", "lit_bottom")};
    ws.dark = r.day_night(windows, "windows", "dark_day", "dark_night");
    ws.lit = r.colour(windows, "windows", "lit");
    ws.warehouse = r.day_night(windows, "windows", "warehouse_day", "warehouse_night");
    ws.frame = r.day_night(windows, "windows", "frame_day", "frame_night");
    ws.frame_width = static_cast<float>(r.number(windows, "windows", "frame_width"));
    const auto names = r.table("canal_names");
    s.canal_names.fill = r.colour(names, "canal_names", "fill");
    s.canal_names.opacity = r.number(names, "canal_names", "opacity");
    if (const auto weight = r.at(names, "canal_names", "weight")) {
        const std::optional<std::int64_t> w = weight->integer();
        if (w && *w >= 1 && *w <= 1000) {
            s.canal_names.weight = static_cast<int>(*w);
        } else {
            r.fail(Fault::bad_value, "canal_names", "weight");
        }
    }
    s.canal_names.size = r.number(names, "canal_names", "size");
    s.canal_names.spacing = r.number(names, "canal_names", "spacing");
    // the port's state after the region's load
    const auto state = r.table("state");
    s.water_y = r.number(state, "state", "water_y");
    if (const auto tone = r.at(state, "state", "tone")) {
        if (const auto b = tone->boolean()) {
            s.tone = *b;
        } else {
            r.fail(Fault::bad_value, "state", "tone");
        }
    }
    if (const auto win = r.at(state, "state", "win_style"); win && !win->is_null()) {
        if (const auto w = win->string()) {
            s.paris = *w == "paris";
        } else {
            r.fail(Fault::bad_value, "state", "win_style");
        }
    }
    if (const auto scene = r.table("scene")) {
        // S.get('no_shadows'), true as Python takes it
        if (const auto ns = scene->find("no_shadows")) {
            s.no_shadows = ns->boolean().value_or(false) || ns->number().value_or(0.0) != 0.0;
        }
    }
    if (r.error()) return std::unexpected(*r.error());
    return s;
}

std::optional<std::uint16_t> Style::land_kind(std::string_view kind) const noexcept {
    const auto it = std::ranges::find(land_kinds, kind);
    if (it == land_kinds.end()) return std::nullopt;
    return static_cast<std::uint16_t>(it - land_kinds.begin());
}

StyleRgb Style::area_colour(std::string_view kind) const noexcept {
    const auto it = find_in(areas, kind);
    return it != areas.end() ? it->second : area_other;
}

std::optional<StyleRgb> Style::surface(std::string_view name) const noexcept {
    const auto it = find_in(surfaces, name);
    if (it == surfaces.end()) return std::nullopt;
    return it->second;
}

std::optional<StyleRgb> Style::surface_colour(std::string_view colour) const noexcept {
    const auto it = find_in(surface_colours, colour);
    if (it == surface_colours.end()) return std::nullopt;
    return it->second;
}

const MarineStyle* Style::marine_of(std::string_view kind) const noexcept {
    const auto it = std::ranges::find(marine, kind, &MarineStyle::kind);
    return it != marine.end() ? &*it : nullptr;
}

} // namespace atlas::tiles
