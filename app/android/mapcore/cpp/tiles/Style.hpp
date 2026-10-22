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
    /// `runways`: the designators' and threshold bars' colours.
    DayNight runway_marks;
    /// `marine`, in the table's order.
    std::vector<MarineStyle> marine;
    /// `trams`: the tracks' colours and width; `bridges`: the lines along a bridge's sides and their width.
    DayNight trams;
    double trams_width = 0.0;
    StyleRgb bridge_lines = 0;
    double bridge_lines_width = 0.0;
    EdgesStyle edges;
    /// `houseboats`: the hull's and the deck's colours (the cabin roof's edge is in `edges`).
    StyleRgb hull = 0;
    StyleRgb deck = 0;
    CanalNamesStyle canal_names;
    /// `state`: WATER_Y, the water's level below the street (metres); TONE; WIN_STYLE == "paris".
    double water_y = 0.0;
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
