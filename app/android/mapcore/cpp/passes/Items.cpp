#include "passes/Items.hpp"

#include "base/log.hpp"
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "frame/Ranks.hpp"
#include "frame/Selectors.hpp"
#include "gen/spirv/item/draw.hpp"
#include "gpu/Descriptors.hpp"
#include "passes/CoasterWalk.hpp"
#include "scene/Cover.hpp"
#include "scene/Packs.hpp"
#include "scene/Residency.hpp"
#include "style/Light.hpp"
#include "twin/Twin.hpp"
#include "twin/item/ItemShader.hpp"
#include "twin/item/ItemTwin.hpp"
#include "view/Camera.hpp"
#include "view/Frames.hpp"

#include <algorithm>
#include <bit>
#include <cstring>
#include <limits>
#include <numeric>
#include <optional>

namespace atlas::passes {

namespace {

[[nodiscard]] std::uint32_t bits(float v) noexcept { return std::bit_cast<std::uint32_t>(v); }
[[nodiscard]] std::uint32_t f32_bits(double v) noexcept { return bits(static_cast<float>(v)); }

/// The jobs a row of workgroups covers (items_cs's ITEM_JOB_ROW workgroups of 64).
constexpr std::uint32_t job_row = 65535;

/// The most records of each list a frame decides: the jobs beyond are left out.
constexpr std::uint32_t max_records = 1U << 20U;

/// regions.py's POWER_NEAR and POWER_FAR: power lines drawn whole to 1,200 m, faded out by 2,000 m.
constexpr double power_near = 1200.0;
constexpr double power_far = 2000.0;

/// `b`, or a buffer for `use` of at least `bytes` with room to grow by half again where `b` is smaller; whether it was
/// made anew.
[[nodiscard]] gpu::Result<bool> fit(const gpu::Device& device, gpu::Buffer& b, vk::DeviceSize bytes,
                                    vk::BufferUsageFlags usage, gpu::MemoryUse use) {
    if (b && b.size() >= bytes) return false;
    ATLAS_GPU_LET(made, device.memory().buffer(std::max<vk::DeviceSize>(bytes + (bytes / 2), 1024), usage, use));
    b = std::move(made);
    return true;
}

/// A colour of the style (0xRRGGBB) as the shaders take it, 0xBBGGRR.
[[nodiscard]] std::uint32_t bgr(std::uint32_t rgb) noexcept {
    return ((rgb >> 16U) & 0xFFU) | (rgb & 0xFF00U) | ((rgb & 0xFFU) << 16U);
}

/// A style colour as the port's hx reads it, channel by channel.
[[nodiscard]] shaders::double3 hx(std::uint32_t rgb) noexcept {
    return {.x = static_cast<double>((rgb >> 16U) & 0xFFU),
            .y = static_cast<double>((rgb >> 8U) & 0xFFU),
            .z = static_cast<double>(rgb & 0xFFU)};
}

/// tohex of a colour's channels, packed 0xBBGGRR, and its 0xRRGGBB.
[[nodiscard]] std::uint32_t hex_bgr(shaders::double3 c) noexcept {
    const hlsl::uint3 h = shaders::tohex3(c);
    return h.x | (h.y << 8U) | (h.z << 16U);
}
[[nodiscard]] std::uint32_t rgb_of(std::uint32_t bgr_colour) noexcept { return bgr(bgr_colour); }

/// light.slang's light through tohex, packed 0xBBGGRR.
[[nodiscard]] std::uint32_t lit_bgr(const shaders::Theme& th, shaders::double3 base, shaders::double3 n, bool lit) {
    const hlsl::uint3 c = shaders::light_bytes(th, base, n, lit);
    return c.x | (c.y << 8U) | (c.z << 16U);
}

/// city.py's mix: a + (b - a) · t, channel by channel.
[[nodiscard]] shaders::double3 mix(shaders::double3 a, shaders::double3 b, double t) noexcept {
    return {.x = a.x + ((b.x - a.x) * t), .y = a.y + ((b.y - a.y) * t), .z = a.z + ((b.z - a.z) * t)};
}

/// city.py's mulc: each channel times `s`.
[[nodiscard]] shaders::double3 mulc(shaders::double3 a, double s) noexcept {
    return {.x = a.x * s, .y = a.y * s, .z = a.z * s};
}

/// city.py's vnorm: each lane over sqrt(vdot(a, a)), summed left to right.
[[nodiscard]] shaders::double3 vnorm(double x, double y, double z) noexcept {
    const double l = pymath::sqrt((x * x) + (y * y) + (z * z));
    return {.x = x / l, .y = y / l, .z = z / l};
}

/// A value as the port prints it: Python's round(v, places).
[[nodiscard]] float printed(double v, int places) { return static_cast<float>(pymath::round(v, places)); }

/// Whether the moment is night.
[[nodiscard]] bool night_of(const style::ThemeBlock& th) noexcept { return (th.flags & style::theme_flag::night) != 0; }

/// The engine mode's radius cut (FrameBuilder's: the placed view's radius about its target), if any.
struct RadiusCut {
    bool on = false;
    double x = 0;
    double z = 0;
    double radius = 0;
};

[[nodiscard]] RadiusCut radius_cut(const frame::FrameState& state) {
    RadiusCut r;
    if (!state.camera.has(style::camera_flag::engine_mode) || !state.placed) return r;
    const std::optional<double> radius = state.placed->number("radius");
    if (!radius) return r;
    const view::Vec3 target = state.placed->camera().target;
    return {.on = true, .x = target.x, .z = target.z, .radius = *radius};
}

} // namespace

std::vector<hlsl::uint4> item_words(const geometry::TileRegionItems& items, double origin_x, double origin_y,
                                    double origin_z) {
    std::vector<hlsl::uint4> out;
    out.reserve(items.items.size() * shaders::ITEM_VECTORS);
    for (const geometry::RegionItem& it : items.items) {
        auto flags = static_cast<std::uint32_t>(it.kind);
        if (it.cab) flags |= shaders::ITEM_CAB;
        if (it.lighting) flags |= shaders::ITEM_LIGHTING;
        if (it.legs_a) flags |= shaders::ITEM_LEGS_A;
        if (it.legs_b) flags |= shaders::ITEM_LEGS_B;
        if (it.yard) flags |= shaders::ITEM_YARD;
        if (it.coaster == 1) flags |= shaders::ITEM_WOODEN;
        if (it.coaster == 2) flags |= shaders::ITEM_MONO;
        const double extra = it.kind == geometry::RegionItemKind::turbine ? it.rotor : static_cast<double>(it.gaps);
        out.push_back(
            hlsl::uint4{f32_bits(it.x - origin_x), f32_bits(0.0 - origin_y), f32_bits(it.z - origin_z), flags});
        if (it.kind == geometry::RegionItemKind::crane) {
            // its half sizes, its height; its axes
            out.push_back(hlsl::uint4{f32_bits(it.hu), f32_bits(it.hv), f32_bits(it.h), 0U});
            out.push_back(hlsl::uint4{f32_bits(it.ux), f32_bits(it.uz), f32_bits(it.vx), f32_bits(it.vz)});
        } else if (it.kind == geometry::RegionItemKind::coaster) {
            // its second end, its heights at its ends
            out.push_back(
                hlsl::uint4{f32_bits(it.bx - origin_x), f32_bits(it.bz - origin_z), f32_bits(it.ya), f32_bits(it.yb)});
            out.push_back(hlsl::uint4{0U, 0U, 0U, 0U});
        } else if (it.kind == geometry::RegionItemKind::wood) {
            // its crown, its height and its tone (id % 5); its draws after
            out.push_back(hlsl::uint4{f32_bits(it.crown), 0U, f32_bits(it.h),
                                      f32_bits(static_cast<double>(pymath::mod(it.id, std::int64_t{5})))});
            out.push_back(hlsl::uint4{0U, 0U, 0U, 0U});
        } else {
            out.push_back(
                hlsl::uint4{f32_bits(it.bx - origin_x), f32_bits(it.bz - origin_z), f32_bits(it.h), f32_bits(extra)});
            out.push_back(hlsl::uint4{f32_bits(it.sag[0]), f32_bits(it.sag[1]), f32_bits(it.sag[2]), 0U});
        }
        for (std::size_t q = 0; q < it.clumps.size(); q += 4) {
            out.push_back(hlsl::uint4{bits(it.clumps.at(q)), bits(it.clumps.at(q + 1)), bits(it.clumps.at(q + 2)),
                                      bits(it.clumps.at(q + 3))});
        }
    }
    return out;
}

ItemChoice item_choice(const geometry::RegionItem& item, double depth, double focal, bool night) {
    ItemChoice c;
    const double s = focal / depth;
    c.flags = static_cast<std::uint32_t>(item.kind) | (night ? shaders::ITEM_JOB_NIGHT : 0U);
    switch (item.kind) {
    case geometry::RegionItemKind::tower: c.drawn = true; break;
    case geometry::RegionItemKind::turbine:
        // s = cam.focal / c[2]; the hub '%.1f' % max(0.8, 2.2 * s); the light at ph[1] - max(1.0, 3 * s), '%.1f' %
        // max(0.8, 1.2 * s)
        c.drawn = true;
        c.a = static_cast<float>(s);
        c.b = static_cast<float>(pymath::max(1.0, 3 * s));
        c.c = printed(pymath::max(0.8, 2.2 * s), 1);
        c.d = printed(pymath::max(0.8, 1.2 * s), 1);
        break;
    case geometry::RegionItemKind::crane:
        // `if cam.focal / c[2] < 0.6: continue` (too far to show as more than a speck); its stays '%.2f' % max(0.5,
        // 0.35 * cam.focal / c[2])
        if (focal / depth < 0.6) return c;
        c.drawn = true;
        c.a = printed(pymath::max(0.5, 0.35 * focal / depth), 2);
        break;
    case geometry::RegionItemKind::mast:
        // `if c[2] < 5 or cam.focal / c[2] < 0.25: continue`; the glow '%.1f' % max(2.0, 9.0 * cam.focal / c[2]), its
        // core '%.1f' % max(0.6, 1.2 * cam.focal / c[2])
        if (focal / depth < 0.25) return c;
        c.drawn = true;
        c.a = printed(pymath::max(2.0, 9.0 * focal / depth), 1);
        c.b = printed(pymath::max(0.6, 1.2 * focal / depth), 1);
        break;
    case geometry::RegionItemKind::gantry:
    case geometry::RegionItemKind::conveyor:
        // `if c[2] < 5 or cam.focal / c[2] < 0.3: continue`
        c.drawn = !(focal / depth < 0.3);
        break;
    case geometry::RegionItemKind::coaster: {
        // s = cam.focal / c[2]; w = max(1.1, 1.7 * s), the top w * 0.72; a post '%.2f' % max(0.5, (0.5 if wood else
        // 0.7) * s); braces max(0.25, 0.3 * s) (whether it has them, the render thread's walk along its track says)
        c.drawn = true;
        const double w = pymath::max(1.1, 1.7 * s);
        c.a = static_cast<float>(s);
        c.b = printed(w, 2);
        c.c = printed(pymath::max(0.5, (item.coaster == 1 ? 0.5 : 0.7) * s), 2);
        c.d = printed(pymath::max(0.25, 0.3 * s), 2);
        c.e = printed(w * 0.72, 2);
        break;
    }
    case geometry::RegionItemKind::wood:
        // s = cam.focal / c[2] (whether its crown's centre lies in the picture, the render thread says)
        c.drawn = true;
        c.a = static_cast<float>(s);
        break;
    case geometry::RegionItemKind::power: {
        // fade = 1.0 if c[2] <= POWER_NEAR else (POWER_FAR - c[2]) / (POWER_FAR - POWER_NEAR); `if fade <= 0:
        // continue`; its tower '%.2f' % max(0.5, 1.4 * s); `<g opacity="%.2f">` where fade < 1
        const double fade = depth <= power_near ? 1.0 : (power_far - depth) / (power_far - power_near);
        if (fade <= 0) return c;
        c.drawn = true;
        c.a = printed(pymath::max(0.5, 1.4 * s), 2);
        if (fade < 1) {
            c.flags |= shaders::ITEM_JOB_FADE;
            c.b = printed(fade, 2);
        }
        break;
    }
    }
    return c;
}

ItemTones item_tones(const style::ThemeBlock& theme, const shaders::Theme& th, const tiles::ItemsStyle& style) {
    static_assert(shaders::ITEM_TONES == item_tone_count);
    ItemTones out{};
    const bool night = night_of(theme);
    const auto pick = [night](const tiles::DayNight& c) { return bgr(night ? c.night : c.day); };
    // the towers: shaft = tohex(light(th, hx('#C9C6BE'), (0, 0, 1), not night)), the glass, the gallery lit as a face
    // up, the antenna; each band's gradient mix(hx(col), hx(sh), .35), col, mix(hx(col), hx(sh), .5), sh the moment's
    // shadow or the night's
    const std::uint32_t shadow =
        night ? style.shadow_night
              : (std::uint32_t{theme.shadow.r} << 16U) | (std::uint32_t{theme.shadow.g} << 8U) | theme.shadow.b;
    const std::array<std::uint32_t, 4> bands{
        lit_bgr(th, hx(style.shaft), {.x = 0.0, .y = 0.0, .z = 1.0}, !night), pick(style.glass),
        lit_bgr(th, hx(style.gallery), {.x = 0.0, .y = 1.0, .z = 0.0}, !night), bgr(style.antenna)};
    for (std::size_t k = 0; k < bands.size(); ++k) {
        const shaders::double3 col = hx(rgb_of(bands[k]));
        out[shaders::ITEM_TONE_TOWER + (3 * k)] = hex_bgr(mix(col, hx(shadow), 0.35));
        out[shaders::ITEM_TONE_TOWER + (3 * k) + 1] = bands[k];
        out[shaders::ITEM_TONE_TOWER + (3 * k) + 2] = hex_bgr(mix(col, hx(shadow), 0.5));
    }
    out[shaders::ITEM_TONE_TURBINE] = pick(style.turbine);
    out[shaders::ITEM_TONE_TURBINE + 1] = pick(style.blades);
    out[shaders::ITEM_TONE_TURBINE + 2] = pick(style.blade_edge);
    out[shaders::ITEM_TONE_TURBINE + 3] = pick(style.hub);
    out[shaders::ITEM_TONE_TURBINE + 4] = bgr(style.turbine_light);
    out[shaders::ITEM_TONE_POWER] = pick(style.pylon);
    out[shaders::ITEM_TONE_POWER + 1] = pick(style.conductor);
    out[shaders::ITEM_TONE_CORE] = bgr(style.glow_core);
    out[shaders::ITEM_TONE_GLOW] = bgr(style.glow_inner);
    out[shaders::ITEM_TONE_GLOW + 1] = bgr(style.glow_middle);
    out[shaders::ITEM_TONE_GLOW + 2] = bgr(style.glow_middle);
    out[shaders::ITEM_TONE_GLOW_OPACITY] = bits(style.glow_inner_opacity);
    out[shaders::ITEM_TONE_GLOW_OPACITY + 1] = bits(style.glow_middle_opacity);
    out[shaders::ITEM_TONE_GLOW_OPACITY + 2] = bits(style.glow_outer_opacity);
    out[shaders::ITEM_TONE_LIGHT_OPACITY] = bits(style.turbine_light_opacity);
    out[shaders::ITEM_TONE_CONDUCTOR_OPACITY] = bits(style.conductor_opacity);
    out[shaders::ITEM_TONE_CONDUCTOR_WIDTH] = bits(style.conductor_width);
    out[shaders::ITEM_TONE_BLADE_WIDTH] = bits(style.blade_edge_width);
    // the blades: a = math.radians(25 + 120 * k), cos(a), sin(a), cos(a + 0.06), sin(a + 0.06)
    for (std::uint32_t k = 0; k < 3; ++k) {
        const double a = pymath::radians(25.0 + (120.0 * k));
        const std::uint32_t at = shaders::ITEM_TONE_BLADES + (4 * k);
        out[at] = f32_bits(pymath::cos(a));
        out[at + 1] = f32_bits(pymath::sin(a));
        out[at + 2] = f32_bits(pymath::cos(a + 0.06));
        out[at + 3] = f32_bits(pymath::sin(a + 0.06));
    }
    // the wind: wd = math.radians(th['wx']['wind_dir']); fx, fz = math.sin(wd), -math.cos(wd)
    const double wd = pymath::radians(theme.wind_direction);
    out[shaders::ITEM_TONE_WIND] = f32_bits(pymath::sin(wd));
    out[shaders::ITEM_TONE_WIND + 1] = f32_bits(-pymath::cos(wd));
    // a crane's stays: tohex(steel)
    out[shaders::ITEM_TONE_STEEL] = pick(style.steel);
    // a coaster's track: lit = tohex(light(th, hx(top), (0, 1, 0), not night)), dark = tohex(light(th, hx(side), (0, 0,
    // 1), False)), by its kind (COL by day, NIGHT at night)
    const auto day_or_night = [night](const tiles::DayNight& c) { return night ? c.night : c.day; };
    for (std::size_t k = 0; k < style.coaster_top.size(); ++k) {
        const std::size_t at = shaders::ITEM_TONE_COASTER + (2 * k);
        out.at(at) = lit_bgr(th, hx(day_or_night(style.coaster_top.at(k))), {.x = 0.0, .y = 1.0, .z = 0.0}, !night);
        out.at(at + 1) = lit_bgr(th, hx(day_or_night(style.coaster_side.at(k))), {.x = 0.0, .y = 0.0, .z = 1.0}, false);
    }
    // the woods' greens: tohex(light(th, mulc(base, 0.6), (0, 1, 0), False)), tohex(light(th, base, vnorm((L[0], 0.8,
    // L[2])), not night)), tohex(light(th, mulc(base, 1.12), vnorm((L[0], 0.6, L[2])), not night))
    const std::array<double, 3>& l = theme.sun_vector;
    for (std::size_t k = 0; k < style.woods_greens.size(); ++k) {
        const shaders::double3 base = hx(style.woods_greens.at(k));
        const std::size_t at = shaders::ITEM_TONE_WOODS + (3 * k);
        out.at(at) = lit_bgr(th, mulc(base, 0.6), {.x = 0.0, .y = 1.0, .z = 0.0}, false);
        out.at(at + 1) = lit_bgr(th, base, vnorm(l[0], 0.8, l[2]), !night);
        out.at(at + 2) = lit_bgr(th, mulc(base, 1.12), vnorm(l[0], 0.6, l[2]), !night);
    }
    for (std::size_t k = 0; k < 3; ++k) out.at(shaders::ITEM_TONE_SUN + k) = f32_bits(l.at(k));
    return out;
}

bool item_high(const style::ThemeBlock& theme, const view::Camera& camera) noexcept {
    // 0.35 + 0.3 * (L[0] * sx_ + L[2] * sz_ > 0)
    const view::Vec3& r = camera.right();
    return (theme.sun_vector[0] * r.x) + (theme.sun_vector[2] * r.z) > 0;
}

void item_extra(const geometry::RegionItem& item, const view::Camera& camera, const style::ThemeBlock& theme,
                const shaders::Theme& th, const tiles::ItemsStyle& style, double ax, double az, double bx, double bz,
                std::vector<std::uint32_t>& out) {
    const bool night = night_of(theme);
    const bool crane = item.kind == geometry::RegionItemKind::crane;
    // the axes: a crane's (stage P's); a mast's u = (cam.r[0], cam.r[2]); a segment's u = ((b - a) / Lg), Lg =
    // math.dist(a, b); v = (-u[1], u[0])
    double ux = 0;
    double uz = 0;
    if (crane) {
        ux = item.ux;
        uz = item.uz;
    } else if (item.kind == geometry::RegionItemKind::mast) {
        ux = camera.right().x;
        uz = camera.right().z;
    } else {
        const std::array<double, 2> pa{ax, az};
        const std::array<double, 2> pb{bx, bz};
        const double lg = pymath::dist(pa, pb);
        ux = (bx - ax) / lg;
        uz = (bz - az) / lg;
    }
    const double vx = crane ? item.vx : -uz;
    const double vz = crane ? item.vz : ux;
    // box's colours: tohex(light(th, col, n3, (not night) and vdot(n3, L) > 0)) for its faces' normals in their order
    const std::array<shaders::double3, 5> normals{{{.x = ux, .y = 0.0, .z = uz},
                                                   {.x = -ux, .y = 0.0, .z = -uz},
                                                   {.x = vx, .y = 0.0, .z = vz},
                                                   {.x = -vx, .y = 0.0, .z = -vz},
                                                   {.x = 0.0, .y = 1.0, .z = 0.0}}};
    const auto pick = [night](const tiles::DayNight& c) { return night ? c.night : c.day; };
    const std::uint32_t steel = pick(style.steel);
    const std::uint32_t second = crane                                         ? pick(style.machinery)
                                 : item.kind == geometry::RegionItemKind::mast ? pick(style.floodlights)
                                                                               : pick(style.conveyor);
    const std::array<double, 3>& l = theme.sun_vector;
    for (const shaders::double3& n : normals) {
        const bool lit = !night && ((n.x * l[0]) + (n.y * l[1]) + (n.z * l[2])) > 0;
        out.push_back(lit_bgr(th, hx(steel), n, lit));
        out.push_back(lit_bgr(th, hx(second), n, lit));
    }
    if (!crane && item.kind != geometry::RegionItemKind::gantry && item.kind != geometry::RegionItemKind::conveyor) {
        return;
    }
    // the parts: sorted(parts, key=lambda p_: -cam.depth((p_[0], H / 2, p_[1]))), stable
    struct Part {
        double x;
        double z;
    };
    std::vector<Part> parts;
    const double mx = (ax + bx) / 2;
    const double mz = (az + bz) / 2;
    // a crane's part about its foot: (x + u[0] du + v[0] dv, z + u[1] du + v[1] dv)
    const auto along = [&](double du, double dv) {
        return Part{.x = ax + (ux * du) + (vx * dv), .z = az + (uz * du) + (vz * dv)};
    };
    if (crane && item.yard) {
        // its legs, (x + ux su (hu - 0.6) + v[0] sv (hv - 0.6), ...); its girders, (x + v[0] sv (hv - 0.6), ...); its
        // end beams, (x + ux su (hu - 0.6), ...); its trolley at (x, z)
        for (const double su : {-1.0, 1.0}) {
            for (const double sv : {-1.0, 1.0}) {
                parts.push_back({ax + (ux * su * (item.hu - 0.6)) + (vx * sv * (item.hv - 0.6)),
                                 az + (uz * su * (item.hu - 0.6)) + (vz * sv * (item.hv - 0.6))});
            }
        }
        for (const double sv : {-1.0, 1.0})
            parts.push_back({ax + (vx * sv * (item.hv - 0.6)), az + (vz * sv * (item.hv - 0.6))});
        for (const double su : {-1.0, 1.0})
            parts.push_back({ax + (ux * su * (item.hu - 0.6)), az + (uz * su * (item.hu - 0.6))});
        parts.push_back({ax, az});
    } else if (crane) {
        // its legs, (x + w[0] sw g + q[0] sq sp_, ...); its sills, (x + q[0] sq sp_, ...) twice; its boom at mid =
        // (back
        // * -1 + out_) / 2, its machinery house at -14, its A-frame (x + w[0] 3 + q[0] sq sp_ 0.6, ...)
        constexpr double g = 9.0;
        constexpr double sp = 8.0;
        for (const double sw : {-1.0, 1.0}) {
            for (const double sq : {-1.0, 1.0}) parts.push_back(along(sw * g, sq * sp));
        }
        for (const double sq : {-1.0, 1.0}) {
            parts.push_back({ax + (vx * sq * sp), az + (vz * sq * sp)});
            parts.push_back({ax + (vx * sq * sp), az + (vz * sq * sp)});
        }
        const double reach = item.hu + 52.0; // out_
        const double mid = ((22.0 * -1) + reach) / 2;
        parts.push_back({ax + (ux * mid), az + (uz * mid)});
        parts.push_back({ax - (ux * 14), az - (uz * 14)});
        for (const double sq : {-1.0, 1.0}) {
            parts.push_back({ax + (ux * 3) + (vx * sq * sp * 0.6), az + (uz * 3) + (vz * sq * sp * 0.6)});
        }
    } else if (item.kind == geometry::RegionItemKind::gantry) {
        if (item.legs_a) parts.push_back({ax, az});
        if (item.legs_b) parts.push_back({bx, bz});
        parts.push_back({mx, mz});
    } else {
        for (std::uint32_t k = 0; k <= item.gaps; ++k) {
            const double f = static_cast<double>(k) / item.gaps;
            parts.push_back({ax + ((bx - ax) * f), az + ((bz - az) * f)});
        }
        parts.push_back({mx, mz});
    }
    const view::Vec3& c = camera.eye();
    const view::Vec3& f = camera.forward();
    const double y = item.h / 2;
    std::vector<double> depth(parts.size());
    for (std::size_t i = 0; i < parts.size(); ++i) {
        depth[i] = ((parts[i].x - c.x) * f.x) + ((y - c.y) * f.y) + ((parts[i].z - c.z) * f.z);
    }
    std::vector<std::uint32_t> order(parts.size());
    std::ranges::iota(order, 0U);
    std::ranges::stable_sort(order, [&](std::uint32_t a, std::uint32_t b) { return -depth[a] < -depth[b]; });
    out.insert(out.end(), order.begin(), order.end());
}

std::uint32_t first_region_item(const scene::ResidentTile& tile, std::uint16_t kind) noexcept {
    for (std::size_t i = 0; i < tile.items.size(); ++i) {
        if (tile.items[i].kind == kind) return static_cast<std::uint32_t>(i);
    }
    return static_cast<std::uint32_t>(tile.items.size());
}

gpu::Result<void> Items::prepare(const frame::PassContext& context) {
    release();
    // none in the owner mode: the likeness harness's owner maps draw no region items yet
    if (context.owners) return {};
    device_ = &context.device;
    const vk::raii::Device& d = context.device.handle();
    ATLAS_GPU_LET(module, frame::make_module(context, "item_draw", spirv::item_draw));
    module_ = std::move(module);
    using Stage = vk::ShaderStageFlagBits;
    const auto storage = vk::DescriptorType::eStorageBuffer;
    const std::array bindings{gpu::Binding{.slot = 0, .type = storage, .stages = Stage::eCompute},
                              gpu::Binding{.slot = 1, .type = storage, .stages = Stage::eCompute | Stage::eFragment},
                              gpu::Binding{.slot = 2, .type = storage, .stages = Stage::eCompute},
                              gpu::Binding{.slot = 3, .type = storage, .stages = Stage::eCompute},
                              gpu::Binding{.slot = 4, .type = storage, .stages = Stage::eVertex},
                              gpu::Binding{.slot = 5, .type = storage, .stages = Stage::eCompute},
                              gpu::Binding{.slot = 6, .type = storage, .stages = Stage::eVertex},
                              gpu::Binding{.slot = 7, .type = storage, .stages = Stage::eCompute},
                              gpu::Binding{.slot = 8, .type = storage, .stages = Stage::eVertex | Stage::eFragment}};
    ATLAS_GPU_LET(set_layout, gpu::make_set_layout(d, bindings));
    set_layout_ = std::move(set_layout);
    const std::array<std::span<const gpu::Binding>, 1> kinds{bindings};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, static_cast<std::uint32_t>(sets_.size())));
    pool_ = std::move(pool);
    for (vk::raii::DescriptorSet& set : sets_) {
        ATLAS_GPU_LET(made, gpu::allocate_set(d, pool_, set_layout_));
        set = std::move(made);
    }
    const std::array<vk::DescriptorSetLayout, 1> extra{*set_layout_};
    ATLAS_GPU_LET(layout, context.layout.make_layout(extra));
    layout_ = std::move(layout);
    const std::array<vk::DescriptorSetLayout, 3> sets{context.layout.frame_set_layout(),
                                                      context.layout.global_set_layout(), *set_layout_};
    ATLAS_GPU_LET(compute_layout, gpu::make_layout(d, sets, frame::push_bytes, Stage::eCompute));
    compute_layout_ = std::move(compute_layout);
    ATLAS_GPU_LET(decide, frame::make_compute(
                              context, {.name = "items", .module = "item_draw", .configuration = context.configuration},
                              *module_, "items_cs", *compute_layout_));
    decide_ = std::move(decide);
    // the faces and the opaque parts: at their corners' depths, writing the depth and the objects bit
    const auto opaque = [&](const char* name, const char* vertex, const char* fragment) {
        gpu::GraphicsDesc desc{.label = {.name = name, .module = "item_draw", .configuration = context.configuration},
                               .module = *module_,
                               .vertex = vertex,
                               .fragment = fragment,
                               .layout = *layout_,
                               .target = context.pass.scene_target(),
                               .depth_test = true,
                               .depth_write = true,
                               .depth_op = vk::CompareOp::eGreater,
                               .stencil_test = true,
                               .stencil = {.failOp = vk::StencilOp::eKeep,
                                           .passOp = vk::StencilOp::eReplace,
                                           .depthFailOp = vk::StencilOp::eKeep,
                                           .compareOp = vk::CompareOp::eAlways,
                                           .compareMask = 0,
                                           .writeMask = shaders::OBJECTS_BIT,
                                           .reference = shaders::OBJECTS_BIT}};
        if (desc.target.colour_count == 2) desc.blends[1] = gpu::Blend::untouched();
        return frame::make_pipeline(context, desc, frame::Overdraw::opaque);
    };
    ATLAS_GPU_LET(faces, opaque("item faces", "items_faces_vs", "items_faces_fs"));
    faces_pipeline_ = std::move(faces);
    ATLAS_GPU_LET(fills, opaque("item fills", "items_faces_vs", "items_fills_fs"));
    fills_pipeline_ = std::move(fills);
    ATLAS_GPU_LET(parts, opaque("item parts", "items_parts_vs", "items_parts_fs"));
    parts_pipeline_ = std::move(parts);
    // the translucent ones: in painter's order, the test on and writes off
    gpu::GraphicsDesc side{
        .label = {.name = "item sides", .module = "item_draw", .configuration = context.configuration},
        .module = *module_,
        .vertex = "items_sides_vs",
        .fragment = "items_sides_fs",
        .layout = *layout_,
        .target = context.pass.scene_target(),
        .depth_test = true,
        .depth_write = false,
        .depth_op = vk::CompareOp::eGreater};
    side.blends[0] = gpu::Blend::over();
    if (side.target.colour_count == 2) side.blends[1] = gpu::Blend::untouched();
    ATLAS_GPU_LET(side_pipeline, frame::make_pipeline(context, side, frame::Overdraw::translucent));
    sides_pipeline_ = std::move(side_pipeline);
    return {};
}

void Items::release() noexcept {
    sides_pipeline_.clear();
    parts_pipeline_.clear();
    fills_pipeline_.clear();
    faces_pipeline_.clear();
    decide_.clear();
    compute_layout_.clear();
    layout_.clear();
    for (vk::raii::DescriptorSet& s : sets_) s.clear();
    pool_.clear();
    set_layout_.clear();
    module_.clear();
    jobs_ = {};
    tones_ = {};
    extra_ = {};
    faces_ = {};
    parts_ = {};
    sides_ = {};
    counts_ = {};
}

void Items::kinds(frame::Selectors& selectors) { kind_ = selectors.kind("region item"); }

gpu::Result<void> Items::build(scene::TileBuild& b) const {
    const geometry::TileRegionItems g =
        geometry::build_region_items({.tile = &b.tile(), .head = &b.head(), .kind = kind_});
    if (g.items.empty()) return {};
    const std::vector<hlsl::uint4> words = item_words(g, b.origin().x, b.origin().y, b.origin().z);
    ATLAS_GPU_LET(
        range, b.allocate(scene::HeapKind::points, static_cast<std::uint32_t>(words.size() * sizeof(hlsl::uint4)), 16));
    b.heaps().write(range, std::as_bytes(std::span(words)));
    b.out().ranges[0] = range;
    auto layout = std::make_shared<ItemTile>();
    layout->items = g.items;
    layout->tracks = g.tracks;
    for (const scene::ItemSpec& item : g.specs) b.add_item(item);
    const std::size_t bytes = sizeof(ItemTile) + (g.items.size() * sizeof(geometry::RegionItem));
    b.keep(std::shared_ptr<const ItemTile>(std::move(layout)), bytes);
    return {};
}

void Items::drawn(const frame::FrameState& state, std::vector<Drawn>& out) const {
    out.clear();
    if (state.cover == nullptr || state.ranks == nullptr || !state.view) return;
    const std::uint32_t me = index();
    const std::span<const std::uint32_t> table = state.ranks->table();
    const std::span<const double> depth = state.ranks->depth();
    const std::span<const scene::CoverTile> tiles = state.cover->tiles();
    const RadiusCut cut = radius_cut(state);
    const bool night = night_of(state.theme);
    const view::Camera& camera = state.view->camera();
    const double focal = camera.focal();
    // woods.draw's sun: by day, the sun above the horizon (L[1] > 0.05)
    const bool sun = !night && state.theme.sun_vector[1] > 0.05;
    for (std::size_t c = 0; c < tiles.size(); ++c) {
        const scene::CoverTile& t = tiles[c];
        if (t.drawn == nullptr || me >= t.drawn->passes.size()) continue;
        const scene::PassTile& mine = t.drawn->passes[me];
        const auto* items = mine.cpu_as<ItemTile>();
        if (items == nullptr || !mine.ranges[0]) continue;
        // a tile's items once, though it draws for several tiles (a stand-in ancestor, its cells over each)
        bool seen = false;
        for (std::size_t e = 0; e < c && !seen; ++e) seen = tiles[e].drawn == t.drawn;
        if (seen) continue;
        const bool convert = state.ground_frame && *state.ground_frame != t.drawn->frame;
        const auto drawn_point = [&](double x, double z) {
            const view::Vec3 p{x, 0.0, z};
            return convert ? view::convert(t.drawn->frame, *state.ground_frame, p) : p;
        };
        const std::uint32_t first = first_region_item(*t.drawn, kind_);
        const std::span<const std::uint32_t> rows(t.drawn->rows);
        // coasters.draw's walk along each track (passes/CoasterWalk.hpp): its radius cut at every fourth of its points
        // and its last; the pieces it draws and the posts it puts (a wooden coaster's braced back to the post before)
        std::uint32_t walk_track = std::numeric_limits<std::uint32_t>::max();
        bool walk_far = false;
        CoasterWalk walk;
        std::optional<std::uint32_t> last_post;
        const auto in_picture = [&](const view::Vec2& q, double margin) {
            return -margin < q.x && q.x < camera.width() + margin && -margin < q.y && q.y < camera.height() + margin;
        };
        for (std::uint32_t i = 0; i < items->items.size() && first + i < rows.size(); ++i) {
            const geometry::RegionItem& it = items->items[i];
            std::uint32_t walked = 0;
            std::uint32_t prev_post = 0;
            if (it.kind == geometry::RegionItemKind::coaster) {
                if (it.track != walk_track) {
                    walk_track = it.track;
                    walk.start();
                    last_post.reset();
                    walk_far = false;
                    if (cut.on && it.track < items->tracks.size()) {
                        walk_far =
                            std::ranges::all_of(items->tracks[it.track].cut, [&](const std::array<double, 2>& p) {
                                const view::Vec3 q = drawn_point(p[0], p[1]);
                                return view::java_hypot(q.x - cut.x, q.z - cut.z) > cut.radius;
                            });
                    }
                }
                if (walk_far) continue;
                const std::array<double, 2> a2{it.x, it.z};
                const std::array<double, 2> b2{it.bx, it.bz};
                const CoasterWalk::Step step = walk.step(camera, drawn_point(it.x, it.z), drawn_point(it.bx, it.bz),
                                                         it.ya, it.yb, pymath::dist(a2, b2), it.coaster == 1);
                if (!step.drawn) continue;
                if (step.post) {
                    walked = shaders::ITEM_JOB_POST;
                    if (it.coaster == 1 && last_post) {
                        walked |= shaders::ITEM_JOB_BRACES;
                        prev_post = *last_post;
                    }
                    last_post = i;
                }
            }
            const std::uint32_t row = rows[first + i];
            const std::uint32_t rank = row < table.size() ? table[row] : shaders::RANK_NONE;
            if (rank == shaders::RANK_NONE) continue;
            Drawn d{.cover = static_cast<std::uint32_t>(c), .item = i, .rank = rank, .depth = depth[row]};
            const view::Vec3 a = drawn_point(it.x, it.z);
            const view::Vec3 b = drawn_point(it.bx, it.bz);
            // a wood's tree: `if not q0 or not (-30 < q0[0] < cam.W + 30 and -30 < q0[1] < cam.H + 30): continue`
            if (it.kind == geometry::RegionItemKind::wood) {
                const std::optional<view::Vec2> q0 = camera.proj({a.x, it.h * 0.7, a.z});
                if (!q0 || !in_picture(*q0, 30)) continue;
            }
            d.ax = a.x;
            d.az = a.z;
            d.bx = b.x;
            d.bz = b.z;
            // a segment's radius cut: `if far(*a) and far(*b): continue` (a point item's the ranks')
            const bool segment = it.kind == geometry::RegionItemKind::gantry ||
                                 it.kind == geometry::RegionItemKind::conveyor ||
                                 it.kind == geometry::RegionItemKind::power;
            if (segment && cut.on && view::java_hypot(a.x - cut.x, a.z - cut.z) > cut.radius &&
                view::java_hypot(b.x - cut.x, b.z - cut.z) > cut.radius) {
                continue;
            }
            d.choice = item_choice(it, d.depth, focal, night);
            if (!d.choice.drawn) continue;
            d.choice.flags |= walked;
            if (it.kind == geometry::RegionItemKind::wood && sun) d.choice.flags |= shaders::ITEM_JOB_SUN;
            d.prev_post = prev_post;
            out.push_back(d);
        }
    }
    // the nearest first (the latest slots), so early depth testing rejects what they hide
    std::ranges::stable_sort(out, std::ranges::greater{}, [](const Drawn& d) { return shaders::rank_slot(d.rank); });
}

void Items::update(const frame::FrameState& state) {
    counts_ = {};
    if (decide_ == nullptr || state.heaps == nullptr || !state.view) return;
    const tiles::Style* style =
        state.active_pack != nullptr && state.active_pack->source ? state.active_pack->source->pack.style() : nullptr;
    if (style == nullptr) return;
    drawn(state, drawn_);
    if (drawn_.empty()) return;
    const std::uint32_t me = index();
    const view::Camera& camera = state.view->camera();
    const bool high = item_high(state.theme, camera);
    const std::span<const scene::CoverTile> tiles = state.cover->tiles();
    words_.clear();
    extra_words_.clear();
    std::uint32_t faces = 0;
    std::uint32_t parts = 0;
    std::uint32_t sides = 0;
    std::vector<std::uint32_t> side_counts;
    for (const Drawn& d : drawn_) {
        const scene::CoverTile& t = tiles[d.cover];
        const scene::PassTile& mine = t.drawn->passes[me];
        const ItemTile& items = *mine.cpu_as<ItemTile>();
        const geometry::RegionItem& it = items.items[d.item];
        const auto kind = static_cast<std::uint32_t>(it.kind);
        const std::uint32_t nf = shaders::item_face_count(kind, it.gaps);
        const std::uint32_t np = shaders::item_part_count(kind);
        const std::uint32_t ns = shaders::item_side_count(kind);
        if (faces + nf > max_records || parts + np > max_records || sides + ns > max_records) continue;
        const auto extra = static_cast<std::uint32_t>(extra_words_.size());
        if (it.kind == geometry::RegionItemKind::coaster) {
            // the post its braces lead back to (its item's words), its top's width
            const bool braces = (d.choice.flags & shaders::ITEM_JOB_BRACES) != 0;
            extra_words_.push_back(braces ? (mine.ranges[0].offset / 16) + (d.prev_post * shaders::ITEM_VECTORS)
                                          : std::numeric_limits<std::uint32_t>::max());
            extra_words_.push_back(bits(d.choice.e));
        } else if (it.kind != geometry::RegionItemKind::wood) {
            item_extra(it, camera, state.theme, state.block.theme, style->items, d.ax, d.az, d.bx, d.bz, extra_words_);
        }
        const std::uint32_t flags = d.choice.flags | (high ? shaders::ITEM_JOB_HIGH : 0U);
        const std::uint32_t words[] = {bits(t.place.offset.x),
                                       bits(t.place.offset.y),
                                       bits(t.place.offset.z),
                                       bits(t.place.offset.w),
                                       bits(t.place.scale.x),
                                       bits(t.place.scale.y),
                                       bits(t.place.scale.z),
                                       bits(t.place.scale.w),
                                       (mine.ranges[0].offset / 16) + (d.item * shaders::ITEM_VECTORS),
                                       flags,
                                       shaders::rank_slot(d.rank),
                                       faces,
                                       parts,
                                       sides,
                                       extra,
                                       0U,
                                       bits(d.choice.a),
                                       bits(d.choice.b),
                                       bits(d.choice.c),
                                       bits(d.choice.d)};
        static_assert(std::size(words) == std::size_t{4} * shaders::ITEM_JOB_VECTORS);
        words_.insert(words_.end(), std::begin(words), std::end(words));
        side_counts.push_back(ns);
        faces += nf;
        parts += np;
        sides += ns;
    }
    // the translucent ones far to near (painter's order), each job's block in its own order: the jobs went nearest
    // first
    for (std::size_t j = 0, at = 0; at < words_.size(); ++j, at += std::size_t{4} * shaders::ITEM_JOB_VECTORS) {
        words_[at + 13] = sides - (words_[at + 13] + side_counts[j]);
    }
    const auto jobs = static_cast<std::uint32_t>(words_.size() / (std::size_t{4} * shaders::ITEM_JOB_VECTORS));
    if (jobs == 0) return;
    if (extra_words_.empty()) extra_words_.push_back(0U);
    const ItemTones tones = item_tones(state.theme, state.block.theme, style->items);
    // into this frame slot's buffers (its previous frame has finished: its buffers and set are free)
    const std::uint32_t slot = state.slot % 2;
    using B = vk::BufferUsageFlagBits;
    const auto vectors = [](std::uint32_t n) {
        return vk::DeviceSize{std::max(n, 1U)} * shaders::ITEM_RECORD_VECTORS * 16;
    };
    auto jobs_made =
        fit(*device_, jobs_[slot], words_.size() * sizeof(std::uint32_t), B::eStorageBuffer, gpu::MemoryUse::upload);
    auto tones_made = fit(*device_, tones_[slot], sizeof tones, B::eStorageBuffer, gpu::MemoryUse::upload);
    auto extra_made = fit(*device_, extra_[slot], extra_words_.size() * sizeof(std::uint32_t), B::eStorageBuffer,
                          gpu::MemoryUse::upload);
    auto faces_made = fit(*device_, faces_[slot], vectors(faces), B::eStorageBuffer, gpu::MemoryUse::gpu);
    auto parts_made = fit(*device_, parts_[slot], vectors(parts), B::eStorageBuffer, gpu::MemoryUse::gpu);
    auto sides_made = fit(*device_, sides_[slot], vectors(sides), B::eStorageBuffer, gpu::MemoryUse::gpu);
    for (const auto* made : {&jobs_made, &tones_made, &extra_made, &faces_made, &parts_made, &sides_made}) {
        if (*made) continue;
        log::error("vk items: {} jobs, {} faces, {} parts and {} translucent ones: {}", jobs, faces, parts, sides,
                   made->error().text());
        jobs_[slot] = {};
        tones_[slot] = {};
        extra_[slot] = {};
        faces_[slot] = {};
        parts_[slot] = {};
        sides_[slot] = {};
        return;
    }
    if (*jobs_made || *tones_made || *extra_made || *faces_made || *parts_made || *sides_made) {
        gpu::SetWriter(*sets_[slot])
            .buffer(0, vk::DescriptorType::eStorageBuffer, jobs_[slot].handle())
            .buffer(1, vk::DescriptorType::eStorageBuffer, tones_[slot].handle())
            .buffer(2, vk::DescriptorType::eStorageBuffer, extra_[slot].handle())
            .buffer(3, vk::DescriptorType::eStorageBuffer, faces_[slot].handle())
            .buffer(4, vk::DescriptorType::eStorageBuffer, faces_[slot].handle())
            .buffer(5, vk::DescriptorType::eStorageBuffer, parts_[slot].handle())
            .buffer(6, vk::DescriptorType::eStorageBuffer, parts_[slot].handle())
            .buffer(7, vk::DescriptorType::eStorageBuffer, sides_[slot].handle())
            .buffer(8, vk::DescriptorType::eStorageBuffer, sides_[slot].handle())
            .apply(device_->handle());
    }
    std::memcpy(jobs_[slot].bytes().data(), words_.data(), words_.size() * sizeof(std::uint32_t));
    jobs_[slot].flush();
    std::memcpy(tones_[slot].bytes().data(), tones.data(), sizeof tones);
    tones_[slot].flush();
    std::memcpy(extra_[slot].bytes().data(), extra_words_.data(), extra_words_.size() * sizeof(std::uint32_t));
    extra_[slot].flush();
    slot_ = slot;
    counts_ = {.jobs = jobs, .faces = faces, .parts = parts, .sides = sides};
}

void Items::record(const frame::Recording& recording, std::uint32_t phase) {
    switch (phase) {
    case draw_phase: record_draw(recording); break;
    case decide_phase: record_decide(recording); break;
    case sides_phase: record_sides(recording); break;
    default: break;
    }
}

void Items::record_decide(const frame::Recording& recording) {
    if (decide_ == nullptr || counts_.jobs == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eCompute, *decide_);
    const auto frame_sets = recording.layout.sets();
    const auto offsets = recording.layout.offsets(recording.frame.slot.slot);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *compute_layout_, 0, frame_sets, offsets);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, *compute_layout_, 2, *sets_[slot_], {});
    const hlsl::uint4 push{counts_.jobs, 0U, 0U, 0U};
    cmd.pushConstants<hlsl::uint4>(*compute_layout_, vk::ShaderStageFlagBits::eCompute, 0, push);
    const std::uint32_t groups = (counts_.jobs + 63) / 64;
    cmd.dispatch(std::min(groups, job_row), (groups + job_row - 1) / job_row, 1);
    recording.counters.dispatch("items");
    // the records, for the vertex and fragment stages
    using S = vk::PipelineStageFlagBits2;
    using A = vk::AccessFlagBits2;
    recording.frame.sync.barrier(cmd, gpu::MemoryDependency{.src_stage = S::eComputeShader,
                                                            .src_access = A::eShaderWrite,
                                                            .dst_stage = S::eVertexShader | S::eFragmentShader,
                                                            .dst_access = A::eShaderRead});
}

void Items::record_draw(const frame::Recording& recording) {
    if (faces_pipeline_ == nullptr || counts_.jobs == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *sets_[slot_], {});
    const auto samples = static_cast<std::uint32_t>(recording.frame.pass.spec().config.samples());
    const hlsl::uint4 push{samples, 0U, 0U, 0U};
    cmd.pushConstants<hlsl::uint4>(*layout_, frame::push_stages, 0, push);
    if (counts_.faces > 0) {
        const std::uint32_t vertices = counts_.faces * shaders::ITEM_FACE_VERTICES;
        if ((recording.state.block.camera.flags.x & (shaders::CAMERA_PAINTER | shaders::CAMERA_DEPTH24)) == 0U) {
            // at true depth with float depth the fans first, alone, in a pipeline that neither writes a mask nor
            // discards, so a tiler keeps its early depth test and hidden-surface removal for them (ENGINE.md gain 18);
            // then the edges alone. A box's faces lead one another (items.slangh), so no edge of an earlier face ties a
            // later face's fan in depth: the order of the two draws decides no sample. On 24-bit depth a lead falls
            // under the depth's step a few dozen metres out (0.5 r 2^-16 under 2^-24 where r < 2^-7), and there the
            // one draw's order (each face's fan, then its edges) decides where an edge meets the next face's fan.
            cmd.pushConstants<hlsl::uint4>(*layout_, frame::push_stages, 0, hlsl::uint4{samples, 1U, 0U, 0U});
            cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *fills_pipeline_);
            cmd.draw(vertices, 1, 0, 0);
            recording.counters.draw("item fills", std::uint64_t{counts_.faces} * 3);
            cmd.pushConstants<hlsl::uint4>(*layout_, frame::push_stages, 0, hlsl::uint4{samples, 2U, 0U, 0U});
            cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *faces_pipeline_);
            cmd.draw(vertices, 1, 0, 0);
            recording.counters.draw("item faces", std::uint64_t{counts_.faces} * 10);
            cmd.pushConstants<hlsl::uint4>(*layout_, frame::push_stages, 0, push);
        } else {
            cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *faces_pipeline_);
            cmd.draw(vertices, 1, 0, 0);
            recording.counters.draw("item faces", std::uint64_t{counts_.faces} * 13);
        }
    }
    if (counts_.parts > 0) {
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *parts_pipeline_);
        cmd.draw(counts_.parts * shaders::ITEM_PART_VERTICES, 1, 0, 0);
        recording.counters.draw("item parts", std::uint64_t{counts_.parts} * 2);
    }
}

void Items::record_sides(const frame::Recording& recording) {
    if (sides_pipeline_ == nullptr || counts_.sides == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *sets_[slot_], {});
    const hlsl::uint4 push{static_cast<std::uint32_t>(recording.frame.pass.spec().config.samples()), 0U, 0U, 0U};
    cmd.pushConstants<hlsl::uint4>(*layout_, frame::push_stages, 0, push);
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *sides_pipeline_);
    cmd.draw(counts_.sides * shaders::ITEM_PART_VERTICES, 1, 0, 0);
    recording.counters.draw("item sides", std::uint64_t{counts_.sides} * 2);
}

void Items::emit(const frame::FrameState& state, twin::Stream& out, std::uint32_t phase) const {
    if (phase != draw_phase) return;
    emit_records(state, out, nullptr, nullptr, nullptr);
}

void Items::emit_records(const frame::FrameState& state, twin::Stream& out, std::vector<shaders::ItemRecord>* faces,
                         std::vector<shaders::ItemRecord>* parts, std::vector<shaders::ItemRecord>* sides) const {
    if (state.cover == nullptr || state.ranks == nullptr || state.heaps == nullptr || !state.view) return;
    const tiles::Style* style =
        state.active_pack != nullptr && state.active_pack->source ? state.active_pack->source->pack.style() : nullptr;
    if (style == nullptr) return;
    std::vector<Drawn> items;
    drawn(state, items);
    if (items.empty()) return;
    out.layer(twin::Layer::objects); // the port's objects image (`o`)
    const view::Camera& camera = state.view->camera();
    const bool high = item_high(state.theme, camera);
    const ItemTones tones = item_tones(state.theme, state.block.theme, style->items);
    twin::ItemTwin twin(state, tones, out);
    twin.keep_records(faces, parts, sides);
    const std::uint32_t me = index();
    const std::span<const scene::CoverTile> tiles = state.cover->tiles();
    std::vector<std::uint32_t> extra;
    for (const Drawn& d : items) {
        const scene::CoverTile& t = tiles[d.cover];
        const scene::PassTile& mine = t.drawn->passes[me];
        const ItemTile& list = *mine.cpu_as<ItemTile>();
        extra.clear();
        const geometry::RegionItem& it = list.items[d.item];
        if (it.kind == geometry::RegionItemKind::coaster) {
            const bool braces = (d.choice.flags & shaders::ITEM_JOB_BRACES) != 0;
            extra.push_back(braces ? (mine.ranges[0].offset / 16) + (d.prev_post * shaders::ITEM_VECTORS)
                                   : std::numeric_limits<std::uint32_t>::max());
            extra.push_back(bits(d.choice.e));
        } else if (it.kind != geometry::RegionItemKind::wood) {
            item_extra(it, camera, state.theme, state.block.theme, style->items, d.ax, d.az, d.bx, d.bz, extra);
        }
        ItemChoice choice = d.choice;
        if (high) choice.flags |= shaders::ITEM_JOB_HIGH;
        twin.item(t, list, state.heaps->writer(mine.ranges[0]).bytes(), mine.ranges[0].offset / 16, d.item, d.rank,
                  choice, extra);
    }
}

} // namespace atlas::passes
