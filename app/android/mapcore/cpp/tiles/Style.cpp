#include "tiles/Style.hpp"

#include <algorithm>
#include <array>
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

    /// The value `key` of table `t` as a pair of numbers (a list of two).
    [[nodiscard]] std::array<double, 2> pair(const std::optional<Value>& t, std::string_view table,
                                             std::string_view key) {
        const auto v = at(t, table, key);
        if (!v) return {};
        if (v->type() != ValueType::list || v->size() != 2) {
            fail(Fault::bad_value, table, key);
            return {};
        }
        return {number_of(v->child(0), table, key), number_of(v->child(1), table, key)};
    }

    /// The value `key` of table `t` as three colours and as three numbers (a list of three).
    [[nodiscard]] std::array<StyleRgb, 3> colours3(const std::optional<Value>& t, std::string_view table,
                                                   std::string_view key) {
        const auto v = list3(t, table, key);
        if (!v) return {};
        return {colour_of(v->child(0), table, key), colour_of(v->child(1), table, key),
                colour_of(v->child(2), table, key)};
    }
    [[nodiscard]] std::array<double, 3> numbers3(const std::optional<Value>& t, std::string_view table,
                                                 std::string_view key) {
        const auto v = list3(t, table, key);
        if (!v) return {};
        return {number_of(v->child(0), table, key), number_of(v->child(1), table, key),
                number_of(v->child(2), table, key)};
    }
    [[nodiscard]] std::optional<Value> list3(const std::optional<Value>& t, std::string_view table,
                                             std::string_view key) {
        const auto v = at(t, table, key);
        if (!v) return std::nullopt;
        if (v->type() != ValueType::list || v->size() != 3) return fail(Fault::bad_value, table, key);
        return v;
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

std::optional<TreeForm> tree_form_named(std::string_view name) noexcept {
    static constexpr std::array<std::pair<std::string_view, TreeForm>, 7> names{{{"ovoid", TreeForm::ovoid},
                                                                                 {"vase", TreeForm::vase},
                                                                                 {"broad", TreeForm::broad},
                                                                                 {"dome", TreeForm::dome},
                                                                                 {"column", TreeForm::column},
                                                                                 {"weep", TreeForm::weep},
                                                                                 {"cone", TreeForm::cone}}};
    for (const auto& [n, form] : names) {
        if (n == name) return form;
    }
    return std::nullopt;
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
    s.lane_dash = r.pair(big, "big_roads", "lane_dash");
    const auto runways = r.table("runways");
    s.runway_pavement = r.day_night(runways, "runways", "pavement_day", "pavement_night");
    s.runway_marks = r.day_night(runways, "runways", "marks_day", "marks_night");
    s.runway_centre_width = r.number(runways, "runways", "centre_width");
    s.runway_centre_dash = r.pair(runways, "runways", "centre_dash");
    const auto taxiways = r.table("taxiways");
    s.taxiway_pavement = r.day_night(taxiways, "taxiways", "pavement_day", "pavement_night");
    s.taxiway_lines = r.day_night(taxiways, "taxiways", "lines_day", "lines_night");
    s.taxiway_lines_width = r.number(taxiways, "taxiways", "lines_width");
    s.lead_in_width = r.number(taxiways, "taxiways", "lead_in_width");
    // kind -> [width, colour]
    r.lists(r.table("marine"), "marine", 2, [&](std::string_view kind, const Value& entry) {
        s.marine.push_back({.kind = std::string(kind),
                            .width = r.number_of(entry.child(0), "marine", kind),
                            .colour = r.colour_of(entry.child(1), "marine", kind)});
    });
    const auto quays = r.table("quays");
    s.quay_waterline = r.day_night(quays, "quays", "waterline_day", "waterline_night");
    s.quay_coping = r.colour(quays, "quays", "coping");
    const auto mirrored = r.table("reflections");
    s.reflections.opacity = {r.number(mirrored, "reflections", "opacity_day"),
                             r.number(mirrored, "reflections", "opacity_night")};
    s.reflections.dark = {r.number(mirrored, "reflections", "dark_day"),
                          r.number(mirrored, "reflections", "dark_overcast"),
                          r.number(mirrored, "reflections", "dark_night")};
    s.reflections.windows = r.day_night(mirrored, "reflections", "windows_day", "windows_night");
    s.reflections.windows_opacity = r.number(mirrored, "reflections", "windows_opacity");
    s.reflections.trees = r.day_night(mirrored, "reflections", "trees_day", "trees_night");
    s.reflections.streak = r.colours3(mirrored, "reflections", "streak");
    s.reflections.streak_opacity = r.numbers3(mirrored, "reflections", "streak_opacity");
    s.reflections.glitter = r.colour(mirrored, "reflections", "glitter");
    const auto drizzle = r.table("drizzle");
    s.drizzle = {.colour = r.colour(drizzle, "drizzle", "colour"),
                 .width = r.number(drizzle, "drizzle", "width"),
                 .opacity = r.number(drizzle, "drizzle", "opacity")};
    const auto parks = r.table("park_edges");
    s.park_edges = r.day_night(parks, "park_edges", "day", "night");
    s.park_edges_width = r.number(parks, "park_edges", "width");
    s.park_edges_dash = r.pair(parks, "park_edges", "dash");
    s.park_edges_opacity = r.number(parks, "park_edges", "opacity");
    const auto trams = r.table("trams");
    s.trams = r.day_night(trams, "trams", "day", "night");
    s.trams_width = r.number(trams, "trams", "width");
    const auto bridges = r.table("bridges");
    s.bridge_lines = r.colour(bridges, "bridges", "lines");
    s.bridge_lines_width = r.number(bridges, "bridges", "lines_width");
    const auto setts = r.table("setts");
    s.sett_marks = r.colour(setts, "setts", "marks");
    s.sett_marks_width = r.number(setts, "setts", "marks_width");
    s.sett_marks_opacity = r.number(setts, "setts", "marks_opacity");
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
    ws.sill = r.day_night(windows, "windows", "sill_day", "sill_night");
    ws.shop_frame = r.day_night(windows, "windows", "shop_frame_day", "shop_frame_night");
    ws.shop_frame_width = static_cast<float>(r.number(windows, "windows", "shop_frame_width"));
    ws.plinth = r.day_night(windows, "windows", "plinth_day", "plinth_night");
    ws.cornice = r.day_night(windows, "windows", "cornice_day", "cornice_night");
    const auto trees = r.table("trees");
    TreesStyle& ts = s.trees;
    ts.leaf = r.colour(trees, "trees", "leaf");
    ts.autumn = r.colour(trees, "trees", "autumn");
    ts.bark = r.day_night(trees, "trees", "bark_day", "bark_night");
    ts.bark_lit = r.colour(trees, "trees", "bark_lit");
    ts.bark_lit_opacity = static_cast<float>(r.number(trees, "trees", "bark_lit_opacity"));
    ts.crown = r.colour(trees, "trees", "crown");
    ts.trunk = r.day_night(trees, "trees", "trunk_day", "trunk_night");
    if (const auto forms = r.table("tree_forms")) {
        for (std::size_t i = 0; i < forms->size(); ++i) {
            const std::optional<std::string_view> name = forms->child(i).string();
            const std::optional<TreeForm> form = name ? tree_form_named(*name) : std::nullopt;
            if (!form || *form == TreeForm::cone) {
                r.fail(Fault::bad_value, "tree_forms", forms->key(i));
                break;
            }
            ts.forms.emplace_back(std::string(forms->key(i)), *form);
        }
    }
    const auto things = r.table("things");
    ThingsStyle& th = s.things;
    th.mast = r.day_night(things, "things", "mast_day", "mast_night");
    th.lantern = r.day_night(things, "things", "lantern_day", "lantern_night");
    th.cap = r.day_night(things, "things", "cap_day", "cap_night");
    th.glint = r.colour(things, "things", "glint");
    th.glint_opacity = static_cast<float>(r.number(things, "things", "glint_opacity"));
    th.bench = r.colour(things, "things", "bench");
    th.bench_back = r.colour(things, "things", "bench_back");
    th.basket = r.colour(things, "things", "basket");
    th.bollard = r.day_night(things, "things", "bollard_day", "bollard_night");
    th.bollard_head = r.colour(things, "things", "bollard_head");
    th.bollard_head_opacity = static_cast<float>(r.number(things, "things", "bollard_head_opacity"));
    th.rack = r.day_night(things, "things", "rack_day", "rack_night");
    th.rack_width = static_cast<float>(r.number(things, "things", "rack_width"));
    th.railing = r.day_night(things, "things", "railing_day", "railing_night");
    const auto items = r.table("items");
    ItemsStyle& it = s.items;
    const auto number = [&](const char* key) { return static_cast<float>(r.number(items, "items", key)); };
    it.shaft = r.colour(items, "items", "shaft");
    it.glass = r.day_night(items, "items", "glass_day", "glass_night");
    it.gallery = r.colour(items, "items", "gallery");
    it.antenna = r.colour(items, "items", "antenna");
    it.shadow_night = r.colour(items, "items", "shadow_night");
    it.steel = r.day_night(items, "items", "steel_day", "steel_night");
    it.machinery = r.day_night(items, "items", "machinery_day", "machinery_night");
    it.floodlights = r.day_night(items, "items", "floodlights_day", "floodlights_night");
    it.conveyor = r.day_night(items, "items", "conveyor_day", "conveyor_night");
    it.glow_inner = r.colour(items, "items", "glow_inner");
    it.glow_inner_opacity = number("glow_inner_opacity");
    it.glow_middle = r.colour(items, "items", "glow_middle");
    it.glow_middle_opacity = number("glow_middle_opacity");
    it.glow_outer_opacity = number("glow_outer_opacity");
    it.glow_core = r.colour(items, "items", "glow_core");
    it.turbine = r.day_night(items, "items", "turbine_day", "turbine_night");
    it.blades = r.day_night(items, "items", "blades_day", "blades_night");
    it.blade_edge = r.day_night(items, "items", "blade_edge_day", "blade_edge_night");
    it.blade_edge_width = number("blade_edge_width");
    it.hub = r.day_night(items, "items", "hub_day", "hub_night");
    it.turbine_light = r.colour(items, "items", "turbine_light");
    it.turbine_light_opacity = number("turbine_light_opacity");
    it.pylon = r.day_night(items, "items", "pylon_day", "pylon_night");
    it.conductor = r.day_night(items, "items", "conductor_day", "conductor_night");
    it.conductor_width = number("conductor_width");
    it.conductor_opacity = number("conductor_opacity");
    constexpr std::array<std::string_view, 3> coaster_kinds{"steel", "wood", "mono"};
    for (std::size_t k = 0; k < coaster_kinds.size(); ++k) {
        const std::string c = std::format("coaster_{}_", coaster_kinds.at(k));
        it.coaster_top.at(k) = r.day_night(items, "items", c + "top_day", c + "top_night");
        it.coaster_side.at(k) = r.day_night(items, "items", c + "side_day", c + "side_night");
    }
    for (std::size_t k = 0; k < it.woods_greens.size(); ++k) {
        it.woods_greens.at(k) = r.colour(items, "items", std::format("woods_green_{}", k + 1));
    }
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
    s.quay_colour = r.colour(state, "state", "quay_col");
    if (const auto course = r.at(state, "state", "quay_course"); course && !course->is_null()) {
        s.quay_course = r.number_of(course, "state", "quay_course");
    }
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
