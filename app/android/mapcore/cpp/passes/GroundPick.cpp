// What a tap on the ground meets (passes/Ground.hpp's Ground::pick; Colour the map's taps): what the last frame drew
// where a ray from the eye meets the ground, from that frame's own decisions, and the slot of the user's palette that
// paints it. The ground is drawn in the painter's order, each fill at its height, so the ray's point at each drawn
// fill's height is tested against its ring in its tile's own frame, and the one drawn last wins: the plate under
// everything, the land kinds, the canopy, the water, the areas, the streets, the over and the marks; and after them
// all, in the tail, the quays' faces. The water is drawn where the stencil counts its rings at the street (w0, an
// island turned the other way) and, with weather, also at its level (wclip); the app's map lays its streets, its big
// roads' asphalt and its pavements as wide as their ways (ground.slang's wide_point) and draws a street off a bridge
// only where no water lies at the street (ground_flag::streets).
#include "frame/FrameBuilder.hpp"
#include "passes/Ground.hpp"
#include "scene/Cover.hpp"
#include "scene/Packs.hpp"
#include "scene/Residency.hpp"
#include "style/Palette.hpp"
#include "tiles/Store.hpp"
#include "view/Frames.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <optional>
#include <span>
#include <utility>
#include <vector>

namespace atlas::passes {

namespace geo = atlas::geometry;

namespace {

/// A street's height on a bridge is over this (ground.slang's BRIDGE_Y: 0.05 m off a bridge, 0.45 on one).
constexpr double bridge_y = 0.25;

/// ground.slang's street_group: S['roads'] by class.
[[nodiscard]] constexpr bool street_group(std::uint16_t g) noexcept {
    return g >= geo::group::road && g < geo::group::over;
}

/// ground.slang's wide_group: the streets, the big roads' asphalt and the runways' and taxiways' pavements.
[[nodiscard]] constexpr bool wide_group(std::uint16_t g, std::uint8_t sub) noexcept {
    return street_group(g) || g == geo::group::over + 1 ||
           (g == geo::group::over + 2 && sub == geo::aero_sub::pavement);
}

/// Whether (x, z) lies in `ring` (even-odd).
[[nodiscard]] bool inside(std::span<const geo::P2> ring, double x, double z) noexcept {
    bool in = false;
    for (std::size_t i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
        const geo::P2& a = ring[i];
        const geo::P2& b = ring[j];
        if ((a.z > z) != (b.z > z) && x < ((b.x - a.x) * (z - a.z) / (b.z - a.z)) + a.x) in = !in;
    }
    return in;
}

/// A ribbon's ring as the app's map lays it as wide as its way (ground.slang's wide_point): each point twice as far
/// from the centreline, from the middle of it and its mirror on the other side (the ribbon's left side, then its right
/// side reversed: point i's mirror is point n - 1 - i).
void widen(std::span<const geo::P2> ring, std::vector<geo::P2>& out) {
    out.clear();
    const std::size_t n = ring.size();
    out.reserve(n);
    for (std::size_t i = 0; i < n; ++i) {
        const geo::P2& p = ring[i];
        const geo::P2& m = ring[n - 1 - i];
        out.push_back({.x = (1.5 * p.x) - (0.5 * m.x), .z = (1.5 * p.z) - (0.5 * m.z)});
    }
}

/// The ray in a drawn tile's frame (its points' metres): the eye and the direction, the frame drawn's taken into it, so
/// a point at the same `t` is the same point.
struct Ray {
    view::Vec3 eye;
    view::Vec3 dir;

    /// How far along it the ray is at height `y`.
    [[nodiscard]] double t_at(double y) const noexcept { return (y - eye.y) / dir.y; }
    /// Where it is at height `y`.
    [[nodiscard]] geo::P2 at(double y) const noexcept {
        const double t = t_at(y);
        return {.x = eye.x + (t * dir.x), .z = eye.z + (t * dir.z)};
    }
};

/// Where the ray meets the vertical face from (ax, az) to (bx, bz) between heights y0 and y1 (pick_building's walls):
/// its distance along the ray, none where it misses the face.
[[nodiscard]] std::optional<double> meets_face(const Ray& r, double ax, double az, double bx, double bz, double y0,
                                               double y1) noexcept {
    const double ex = bx - ax;
    const double ez = bz - az;
    const double den = (r.dir.x * ez) - (r.dir.z * ex);
    if (den == 0.0) return std::nullopt;
    const double wx = ax - r.eye.x;
    const double wz = az - r.eye.z;
    const double t = ((wx * ez) - (wz * ex)) / den;
    const double s = ((wx * r.dir.z) - (wz * r.dir.x)) / den;
    if (!(t > 0.0) || s < 0.0 || s > 1.0) return std::nullopt;
    const double y = r.eye.y + (t * r.dir.y);
    if (y < y0 || y > y1) return std::nullopt;
    return t;
}

/// A quay face's heights as passes/Quays.cpp's face_of lays it: its waterline from the water's level, its coping at
/// the street, its wall (or a tall wall's bands) between them.
[[nodiscard]] std::pair<double, double> face_heights(QuayFace::Kind kind, double wy) noexcept {
    switch (kind) {
    case QuayFace::Kind::waterline: return {wy, wy + 0.35};
    case QuayFace::Kind::coping: return {-0.18, 0.02};
    default: return {wy + 0.35, 0.0};
    }
}

} // namespace

std::size_t ground_slot_of(const geo::Paint& paint, const tiles::Style* style, const style::PaletteBlock* palette,
                           bool night, bool grass) {
    namespace slot = style::palette_slot;
    const geo::Rgba c = paint.day;
    if (c.a == 0) {
        if (c == geo::theme_colour(geo::ThemeColour::ground)) {
            return night ? slot::night_ground : (grass ? slot::lawn : slot::paper);
        }
        if (c == geo::theme_colour(geo::ThemeColour::road)) return slot::street;
        if (c == geo::theme_colour(geo::ThemeColour::water)) return night ? slot::night_water : slot::water;
        return 0;
    }
    if (style == nullptr) return 0;
    const tiles::StyleRgb rgb = (std::uint32_t{c.r} << 16U) | (std::uint32_t{c.g} << 8U) | c.b;
    std::size_t first = 0;
    for (const geo::GroundSlot& s : geo::ground_slots(*style, false)) {
        // the big roads' asphalt moves only where the style has an asphalt of its own (ground_palette)
        if (s.colour != rgb || (!s.darkens && s.own == 0U)) continue;
        if (style::paint(palette, s.code, s.own, s.colour) != s.colour) return s.code;
        if (first == 0) first = s.code;
    }
    return first;
}

std::optional<scene::PickedGround> Ground::pick(const frame::FrameState& state, double x, double y) const {
    using What = scene::PickedGround::What;
    const GroundFrame& f = frame_;
    if (!f.valid || !state.view || !state.ground_frame || state.cover == nullptr) return std::nullopt;
    const view::Vec3 eye = state.view->camera().eye();
    const view::Vec3 ray = scene::ray_of(*state.view, x, y);
    if (!(ray.y < 0.0) || !(eye.y > 0.0)) return std::nullopt;
    const view::GroundFrame& drawn = *state.ground_frame;
    // The tiles the frame drew: each stays resident until the next frame's update lets a tile go (scene::World), so
    // those of the frame's cover are the ones to read.
    std::vector<const scene::ResidentTile*> covered;
    covered.reserve(state.cover->tiles().size());
    for (const scene::CoverTile& c : state.cover->tiles()) covered.push_back(c.drawn);
    std::vector<Ray> rays;
    std::vector<bool> live;
    rays.reserve(f.tiles.size());
    live.reserve(f.tiles.size());
    for (const DrawnTile& t : f.tiles) {
        live.push_back(std::ranges::find(covered, t.tile) != covered.end());
        if (t.tile->frame == drawn) {
            rays.push_back({.eye = eye, .dir = ray});
            continue;
        }
        const view::Vec3 e = view::convert(drawn, t.tile->frame, eye);
        const view::Vec3 e1 = view::convert(drawn, t.tile->frame, {eye.x + ray.x, eye.y + ray.y, eye.z + ray.z});
        rays.push_back({.eye = e, .dir = {e1.x - e.x, e1.y - e.y, e1.z - e.z}});
    }

    // the one drawn last so far: its painter slot, what it is (a fill's own), and how far along the ray it was met
    struct Top {
        std::uint32_t slot = 0;
        scene::PickedGround picked;
        const geo::Fill* fill = nullptr;
        double t = 0.0;
    };
    std::optional<Top> top;
    const auto offer = [&top](std::uint32_t slot, const scene::PickedGround& picked, const geo::Fill* fill, double t) {
        if (!top || slot >= top->slot) top = Top{.slot = slot, .picked = picked, .fill = fill, .t = t};
    };
    const Ray mine{.eye = eye, .dir = ray};

    // the plate: the renders' about the frame's origin, or where the world draws each wanted tile's box
    const geo::P2 street = mine.at(0.0);
    bool plate = f.plate && std::abs(street.x) <= f.ground_r && std::abs(street.z) <= f.ground_r;
    for (const TilePlate& p : f.tile_plates) {
        if (plate || p.tile >= f.tiles.size() || !live[p.tile]) continue;
        const scene::ResidentTile& d = *f.tiles[p.tile].tile;
        const geo::P2 q = rays[p.tile].at(0.0);
        const double qx = q.x - d.origin.x;
        const double qz = q.z - d.origin.z;
        const auto x0 = static_cast<double>(std::min(p.x0, p.x1));
        const auto x1 = static_cast<double>(std::max(p.x0, p.x1));
        const auto z0 = static_cast<double>(std::min(p.z0, p.z1));
        const auto z1 = static_cast<double>(std::max(p.z0, p.z1));
        plate = qx >= x0 && qx <= x1 && qz >= z0 && qz <= z1;
    }
    if (plate) offer(slot(geo::group::plate, 0, 0), {.what = What::plate}, nullptr, mine.t_at(0.0));

    // the water: its rings' count at the street (w0), and at their level (wclip)
    int at_street = 0;
    int at_level = 0;
    double level_t = mine.t_at(0.0);
    for (std::size_t ti = 0; ti < f.tiles.size() && f.water; ++ti) {
        if (!live[ti]) continue;
        const DrawnTile& t = f.tiles[ti];
        const geo::GroundTile& g = *t.ground;
        for (std::uint32_t i = 0; i < g.fills.size() && t.decisions + i < f.drawn.size(); ++i) {
            const geo::Fill& fill = g.fills[i];
            const std::uint8_t d = f.drawn[t.decisions + i];
            if (fill.kind != geo::FillKind::water || d == 0) continue;
            const std::span<const geo::P2> ring = std::span(g.points).subspan(fill.first, fill.count);
            const int turn = fill.hole ? -1 : 1;
            if ((d & drawn_bit::street) != 0) {
                const geo::P2 p = rays[ti].at(0.0);
                if (inside(ring, p.x, p.z)) at_street += turn;
            }
            if ((d & drawn_bit::level) != 0) {
                const geo::P2 p = rays[ti].at(fill.level);
                if (inside(ring, p.x, p.z)) {
                    at_level += turn;
                    level_t = rays[ti].t_at(fill.level);
                }
            }
        }
    }
    const bool water_at_street = f.water && at_street != 0;
    if (water_at_street && (!f.weather || at_level != 0)) {
        offer(slot(geo::group::water, 0, 0), {.what = What::water}, nullptr, f.weather ? level_t : mine.t_at(0.0));
    }

    // the drawn fills, the canopy's among them
    std::vector<geo::P2> wide;
    for (std::size_t ti = 0; ti < f.tiles.size(); ++ti) {
        if (!live[ti]) continue;
        const DrawnTile& t = f.tiles[ti];
        const geo::GroundTile& g = *t.ground;
        const Ray& r = rays[ti];
        for (std::uint32_t i = 0; i < g.fills.size() && t.decisions + i < f.drawn.size(); ++i) {
            const geo::Fill& fill = g.fills[i];
            if (fill.kind == geo::FillKind::water || (f.drawn[t.decisions + i] & drawn_bit::street) == 0) continue;
            const geo::P2 p = r.at(fill.y);
            const bool widened = f.grounded && wide_group(fill.group, fill.sub);
            // its box (a ribbon laid as wide as its way reaches up to half the box's side beyond it)
            const double mx = widened ? (fill.x1 - fill.x0) / 2 : 0.0;
            const double mz = widened ? (fill.z1 - fill.z0) / 2 : 0.0;
            if (p.x < fill.x0 - mx || p.x > fill.x1 + mx || p.z < fill.z0 - mz || p.z > fill.z1 + mz) continue;
            std::span<const geo::P2> ring = std::span(g.points).subspan(fill.first, fill.count);
            if (widened) {
                widen(ring, wide);
                ring = wide;
            }
            if (!inside(ring, p.x, p.z)) continue;
            // the app's map: a street off a bridge keeps off the water at the street
            if (widened && street_group(fill.group) && fill.y < bridge_y && water_at_street) continue;
            offer(slot(fill.group, fill_order(t, i), fill.sub),
                  {.what = What::fill, .kind = fill.kind, .group = fill.group}, &fill, r.t_at(fill.y));
        }
    }

    // the quays' faces, in the tail: the last of the ground drawn
    for (std::uint32_t k = 0; k < f.quays.size(); ++k) {
        const QuayFace& q = f.quays[k];
        if (q.tile >= f.tiles.size() || !live[q.tile]) continue;
        const geo::GroundTile& g = *f.tiles[q.tile].ground;
        if (q.edge >= g.quays.size()) continue;
        const auto [y0, y1] = face_heights(q.kind, g.quays[q.edge].wy);
        const std::optional<double> t = meets_face(rays[q.tile], q.ax, q.az, q.bx, q.bz, y0, y1);
        if (t) offer(slot(geo::group::quay, k, 0), {.what = What::quay}, nullptr, *t);
    }

    if (!top) return std::nullopt;
    // the slot that paints it: the quays' walls; the woods' for the canopy (its filter's, in the user's woods); the
    // water with weather its gradient's end nearer the tap's row (wgrad: the theme's water_top at its first row to its
    // water_bot at its last, CSS px, which mapview's PaletteTheme moves by Water, sky and Water, deep), the water's own
    // colour only its tint at .12 over it; the theme's colours as the theme carries them, and the style's as the
    // palette's table moves them (the active pack's, which the frame's table is made of: Ground::write_palette)
    namespace code = style::palette_slot;
    scene::PickedGround out = top->picked;
    const style::PaletteBlock* palette = state.palette;
    const bool grass = palette != nullptr && palette->has(style::palette_flag::grass);
    const tiles::Style* pack_style =
        state.active_pack != nullptr && state.active_pack->source ? state.active_pack->source->pack.style() : nullptr;
    const auto theme = [&](geo::ThemeColour c) {
        const geo::Rgba rgba = geo::theme_colour(c);
        return ground_slot_of({.day = rgba, .night = rgba}, nullptr, palette, f.night, grass);
    };
    const double row = y / state.camera.density;
    switch (out.what) {
    case What::quay: out.slot = code::quay_walls; break;
    case What::plate: out.slot = theme(geo::ThemeColour::ground); break;
    case What::water:
        out.slot = !f.weather                              ? theme(geo::ThemeColour::water)
                   : row < (f.wgrad_y1 + f.wgrad_y2) / 2.0 ? code::water_sky
                                                           : code::water_deep;
        break;
    case What::fill:
        out.slot = top->fill->kind == geo::FillKind::canopy
                       ? code::woods
                       : ground_slot_of(top->fill->paint, pack_style, palette, f.night, grass);
        break;
    }
    const double mx = eye.x + (top->t * ray.x);
    const double mz = eye.z + (top->t * ray.z);
    out.latitude = drawn.lat0 - (mz / drawn.kz);
    out.longitude = drawn.lon0 + (mx / drawn.kx);
    return out;
}

} // namespace atlas::passes
