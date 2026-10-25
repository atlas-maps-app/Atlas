// A render pack's style, typed (ENGINE.md, design point 3; rendertiles' Style): every value of its `style` section the
// core draws with, read once as the pack opens. The colours and widths are the engine's style file's
// (app/android/mapcore/style/engine.jsonc, which says what each is); `state` and `scene` are the port's module values
// after the region's load. A section that lacks one of them, or holds one of another kind, is the pack's error, named.
#pragma once

#include "tiles/Error.hpp"
#include "tiles/Sections.hpp"

#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace atlas::tiles {

/// A colour of the style: `#RRGGBB` as 0xRRGGBB (sRGB).
using StyleRgb = std::uint32_t;

/// A colour by day and at night.
struct DayNight {
    StyleRgb day = 0;
    StyleRgb night = 0;
};

/// A marine work's strip (`marine`): its kind, its width in metres where its way is open, its colour.
struct MarineStyle {
    std::string kind;
    double width = 0.0;
    StyleRgb colour = 0;
};

/// The water's reflections (`reflections`): the group's opacity by day and at night, how much darker a mirrored facade
/// reads than the bright sky the water mirrors (by day, under cloud, at night), a building's window rows and their
/// opacity, a tree's crown, a night streak's radial gradient (its stops at 0, .5 and 1: their colours and opacities),
/// and the sun's glitter on the water.
struct ReflectionsStyle {
    std::array<double, 2> opacity{}; ///< by day, at night
    std::array<double, 3> dark{};    ///< by day, under cloud (th['sky_k']), at night
    DayNight windows;
    double windows_opacity = 0.0;
    DayNight trees;
    std::array<StyleRgb, 3> streak{};
    std::array<double, 3> streak_opacity{};
    StyleRgb glitter = 0;
};

/// The drizzle on the canals (`drizzle`): its rings' stroke and width in the pattern's units, the rect's opacity.
struct DrizzleStyle {
    StyleRgb colour = 0;
    double width = 0.0;
    double opacity = 0.0;
};

/// The canal names on the water (`canal_names`): ground_text's fill and its opacity, the face's weight, the letters'
/// size and spacing in metres.
struct CanalNamesStyle {
    StyleRgb fill = 0;
    double opacity = 0.0;
    int weight = 0;
    double size = 0.0;
    double spacing = 0.0;
};

/// The thin strokes of the buildings (`buildings`, `houseboats`): the roofs' edges by day and at night and their widths
/// on slopes and on tops, a wall's stroke in its own colour, a houseboat cabin roof's edge and its width (CSS px).
struct EdgesStyle {
    DayNight roof;
    float roof_slope = 0.0F;
    float roof_top = 0.0F;
    float wall = 0.0F;
    StyleRgb cabin = 0;
    float cabin_width = 0.0F;
};

/// The windows (`windows`): by day with weather the glass's gradients from a wall's windows' top to their bottom (the
/// glass by a clear and by an overcast sky, the glass the sun is on), without weather and at night their flat glass, a
/// warehouse's shutters by day and at night, the frames by day and at night and their width (CSS px).
struct WindowsStyle {
    std::array<StyleRgb, 2> glass{};
    std::array<StyleRgb, 2> glass_overcast{};
    std::array<StyleRgb, 2> lit_glass{};
    DayNight dark;
    StyleRgb lit = 0;
    DayNight warehouse;
    DayNight frame;
    float frame_width = 0.0F;
    DayNight sill;
    DayNight shop_frame;
    float shop_frame_width = 0.0F;
    DayNight plinth;
    DayNight cornice;
    /// A houseboat cabin's windows (`houseboats`): their glass at night (by day the glass's gradient), their frames by
    /// day and at night, and the frames' width.
    StyleRgb cabin_glass_night = 0;
    DayNight cabin_frame;
    float cabin_frame_width = 0.0F;
};

/// A tree's crown form (closeup.py's FORMS and tree_form): the ovoid where its genus has no other, a needleleaved
/// tree's cone.
enum class TreeForm : std::uint8_t { ovoid = 0, vase = 1, broad = 2, dome = 3, column = 4, weep = 5, cone = 6 };

/// The form named `name` ("vase", "ovoid", ...; "cone" too), or nothing.
[[nodiscard]] std::optional<TreeForm> tree_form_named(std::string_view name) noexcept;

/// The trees (`trees`): closeup.py's tree_close with weather, the leaf's colour and its autumn's (mixed by the date's
/// tint), the bark by day and at night, the trunk's sunlit side and its opacity; amsterdam.py's generic tree without
/// weather, its crown and its trunk by day and at night. `tree_forms`: closeup.py's FORMS, a genus's crown form, in
/// the table's order.
struct TreesStyle {
    StyleRgb leaf = 0;
    StyleRgb autumn = 0;
    DayNight bark;
    StyleRgb bark_lit = 0;
    float bark_lit_opacity = 0.0F;
    StyleRgb crown = 0;
    DayNight trunk;
    std::vector<std::pair<std::string, TreeForm>> forms;
};

/// The street furniture (`things`): a street lamp's mast, lantern and cap by day and at night, its lantern's glint
/// and its opacity; a bench's seat and back and a waste basket; a bollard by day and at night, its head and its
/// opacity; a bicycle rack by day and at night, and its width (CSS px); a bridge's railing by day and at night.
struct ThingsStyle {
    DayNight mast;
    DayNight lantern;
    DayNight cap;
    StyleRgb glint = 0;
    float glint_opacity = 0.0F;
    StyleRgb bench = 0;
    StyleRgb bench_back = 0;
    StyleRgb basket = 0;
    DayNight bollard;
    StyleRgb bollard_head = 0;
    float bollard_head_opacity = 0.0F;
    DayNight rack;
    float rack_width = 0.0F;
    DayNight railing;
};

/// A region's items (`items`): a tower's shaft, its cab's glass by day and at night, its gallery and its antenna, the
/// night's shadow its gradients mix toward; the steel of cranes, masts, gantries and conveyors, a crane's machinery, a
/// lighting mast's floodlights and a conveyor's gallery by day and at night; a lighting mast's glow at night, its
/// stops' colours and opacities, and its core; a turbine's tower, blades and their edges by day and at night, the
/// edges' width (CSS px), its hub, its night light and its opacity; a power line's towers and conductors by day and at
/// night, the conductors' width and opacity; a coaster's track's top and side by its kind by day and at night; the
/// woods' five greens.
struct ItemsStyle {
    StyleRgb shaft = 0;
    DayNight glass;
    StyleRgb gallery = 0;
    StyleRgb antenna = 0;
    StyleRgb shadow_night = 0;
    DayNight steel;
    DayNight machinery;
    DayNight floodlights;
    DayNight conveyor;
    StyleRgb glow_inner = 0;
    float glow_inner_opacity = 0.0F;
    StyleRgb glow_middle = 0;
    float glow_middle_opacity = 0.0F;
    float glow_outer_opacity = 0.0F;
    StyleRgb glow_core = 0;
    DayNight turbine;
    DayNight blades;
    DayNight blade_edge;
    float blade_edge_width = 0.0F;
    DayNight hub;
    StyleRgb turbine_light = 0;
    float turbine_light_opacity = 0.0F;
    DayNight pylon;
    DayNight conductor;
    float conductor_width = 0.0F;
    float conductor_opacity = 0.0F;
    /// A coaster's track's top and side by its kind (steel, wood, mono), by day and at night.
    std::array<DayNight, 3> coaster_top{};
    std::array<DayNight, 3> coaster_side{};
    /// The woods' five generic greens.
    std::array<StyleRgb, 5> woods_greens{};
};

struct Style {
    /// Reads `tables`: every value below, each of its kind; a missing or mistyped one is Fault::missing or
    /// Fault::bad_value at Site::style, named (`table.key`) in the error's column.
    [[nodiscard]] static Result<Style> of(const StyleTables& tables);

    /// `land`: its kinds in the table's order (a land fill's kind is its place here), their colours and their draw
    /// orders (regions.py sorts S['land'] by them, then by area, largest first).
    std::vector<std::string> land_kinds;
    std::vector<StyleRgb> land_colours;
    std::vector<std::int64_t> land_orders;
    /// `area`, `surf`, `surf_colour`: a kind's, a surface's, a `surface:colour`'s colour, in the tables' order.
    std::vector<std::pair<std::string, StyleRgb>> areas;
    std::vector<std::pair<std::string, StyleRgb>> surfaces;
    std::vector<std::pair<std::string, StyleRgb>> surface_colours;
    /// `ground`: an area's colour where `area` lacks its kind; a cycleway's without a mapped surface:colour.
    StyleRgb area_other = 0;
    StyleRgb cycleway = 0;
    /// `rail`: the ballast's colour and half width (metres), the rails' colours and width.
    StyleRgb ballast = 0;
    double ballast_half_width = 0.0;
    DayNight rails;
    double rails_width = 0.0;
    /// `big_roads`: the asphalt, the edge and lane lines (their width in metres), the lane lines' dash and gap.
    DayNight asphalt;
    DayNight road_lines;
    double road_lines_width = 0.0;
    std::array<double, 2> lane_dash{};
    /// `runways`: the pavement; the designators', threshold bars' and centre line's colour; the centre line's width
    /// (metres) and its dashes and gaps (metres along it).
    DayNight runway_pavement;
    DayNight runway_marks;
    double runway_centre_width = 0.0;
    std::array<double, 2> runway_centre_dash{};
    /// `taxiways`: the pavement of a taxiway, a taxilane and a stopway; the line along it and a stand's lead-in line,
    /// their widths (metres).
    DayNight taxiway_pavement;
    DayNight taxiway_lines;
    double taxiway_lines_width = 0.0;
    double lead_in_width = 0.0;
    /// `marine`, in the table's order.
    std::vector<MarineStyle> marine;
    /// `quays`: a quay wall's band at the waterline, by day and at night, and its coping stone.
    DayNight quay_waterline;
    StyleRgb quay_coping = 0;
    ReflectionsStyle reflections;
    DrizzleStyle drizzle;
    /// `park_edges`: a national park's edge, its width, its dashes and gaps (CSS px) and the path's opacity.
    DayNight park_edges;
    double park_edges_width = 0.0;
    std::array<double, 2> park_edges_dash{};
    double park_edges_opacity = 0.0;
    /// `trams`: the tracks' colours and width; `bridges`: the lines along a bridge's sides and their width.
    DayNight trams;
    double trams_width = 0.0;
    StyleRgb bridge_lines = 0;
    double bridge_lines_width = 0.0;
    /// `setts`: the courses across a sett road, their width and the path's opacity.
    StyleRgb sett_marks = 0;
    double sett_marks_width = 0.0;
    double sett_marks_opacity = 0.0;
    EdgesStyle edges;
    WindowsStyle windows;
    TreesStyle trees;
    ThingsStyle things;
    ItemsStyle items;
    /// `houseboats`: the hull's and the deck's colours (the cabin roof's edge is in `edges`).
    StyleRgb hull = 0;
    StyleRgb deck = 0;
    CanalNamesStyle canal_names;
    /// `state`: WATER_Y, the water's level below the street (metres); QUAY_COL, the quay walls' colour, and
    /// QUAY_COURSE, their courses' height up close (none: no courses); TONE; WIN_STYLE == "paris".
    double water_y = 0.0;
    StyleRgb quay_colour = 0;
    std::optional<double> quay_course;
    bool tone = false;
    bool paris = false;
    /// `scene`'s `no_shadows` (Python's truth; false where the scene does not set it).
    bool no_shadows = false;

    /// A land kind's place in `land`, or nothing.
    [[nodiscard]] std::optional<std::uint16_t> land_kind(std::string_view kind) const noexcept;
    /// `area`'s colour of a kind, area_other for one it lacks.
    [[nodiscard]] StyleRgb area_colour(std::string_view kind) const noexcept;
    /// `surf`'s colour of a surface, `surf_colour`'s of a `surface:colour`, or nothing.
    [[nodiscard]] std::optional<StyleRgb> surface(std::string_view name) const noexcept;
    [[nodiscard]] std::optional<StyleRgb> surface_colour(std::string_view colour) const noexcept;
    /// `marine`'s strip of a kind, or null.
    [[nodiscard]] const MarineStyle* marine_of(std::string_view kind) const noexcept;
};

/// `#RRGGBB` (or `#RGB`, as city.py's hx) as 0xRRGGBB, or nothing.
[[nodiscard]] std::optional<StyleRgb> style_rgb(std::string_view text) noexcept;

} // namespace atlas::tiles
