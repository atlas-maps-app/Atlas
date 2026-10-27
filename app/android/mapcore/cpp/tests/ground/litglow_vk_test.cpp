// The lit streets at night on the host's Vulkan (passes/LitGlow.cpp): the Westerkerk home, the ground drawn alone (the
// lamps' pools left out), in full mode with the byte tables bound.
//   - The glow's layer as the pass resolved it, through the CPU's filter (fx/LitGlow.hpp: SvgFilterRaster's chain over
//     the plan's input), is the pass's result word for word over the composite's region, at the home's density (a box
//     of 13) and at one px a dp (5);
//   - the layer is the runs as the frame decided them: every pixel whose centre lies a pixel inside a run's stroke (its
//     segments' round-capped capsules) is the glow's colour, opaque, and every pixel a pixel and a half beyond every
//     stroke is empty;
//   - the frame is the result written back as sRGB and screened at .4 over the frame drawn without the lit streets,
//     within two levels (the composite blends in 8 bits), and that frame exactly beyond the result's region;
//   - on a wet night with the hour's weather, the glow screened at .3 and then the cores' layer at .22;
//   - with the filters off (structural mode), the glow's layer screened as it is, over the whole frame.
#if !defined(__ANDROID__)

#include "base/pymath/PyMath.hpp"
#include "fx/ByteTables.hpp"
#include "fx/FieldCache.hpp"
#include "fx/LitGlow.hpp"
#include "passes/Ground.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <format>
#include <limits>
#include <memory>
#include <optional>
#include <string>
#include <vector>

using namespace atlas;

namespace {

/// What a frame's lit streets left: their plan, layers and result as the pass wrote them, and the runs.
struct GlowLook {
    bool drew = false;
    passes::GlowPlan plan;
    std::uint32_t w = 0;
    std::uint32_t h = 0;
    std::int32_t margin = 0;
    std::vector<fx::Argb> glow;
    std::vector<fx::Argb> cores;
    std::vector<fx::Argb> result;
    double density = 0;
    double opacity = 0;
    std::vector<passes::LitRun> runs;
    std::vector<view::Vec2> points;
};

/// A kept layer's texels (RGBA8 bytes) as a r g b words.
std::vector<fx::Argb> texels_of(const gpu::Buffer& b, std::size_t n) {
    std::vector<std::uint8_t> bytes(n * 4);
    b.invalidate();
    std::memcpy(bytes.data(), b.bytes().data(), bytes.size());
    std::vector<fx::Argb> out(n);
    for (std::size_t i = 0; i < n; ++i) {
        out[i] = (fx::Argb{bytes[(i * 4) + 3]} << 24U) | (fx::Argb{bytes[i * 4]} << 16U) |
                 (fx::Argb{bytes[(i * 4) + 1]} << 8U) | fx::Argb{bytes[(i * 4) + 2]};
    }
    return out;
}

void take(GlowLook& look, passes::Ground& g, const frame::FrameState& state) {
    look.drew = g.drew_glow();
    const passes::GlowLayer* s = g.glow_layer();
    if (!look.drew || s == nullptr) return;
    look.plan = s->drew;
    look.w = s->extent.width;
    look.h = s->extent.height;
    look.margin = s->margin;
    const std::size_t n = std::size_t{look.w} * look.h;
    if (look.plan.glow) look.glow = texels_of(s->glow_copy, n);
    if (!look.plan.cores.empty()) look.cores = texels_of(s->core_copy, n);
    s->buffers[0].invalidate();
    look.result.resize(n);
    std::memcpy(look.result.data(), s->buffers[0].bytes().data(), n * 4);
    look.density = state.camera.density;
    const passes::GroundFrame& f = g.decided();
    look.opacity = f.lit_opacity;
    look.runs = f.lit_runs;
    look.points = f.lit_run_points;
}

[[nodiscard]] fx::GlowRect rect_of(const passes::PixelBox& b) {
    return {.x0 = b.x0, .y0 = b.y0, .x1 = b.x1, .y1 = b.y1};
}

void quiet(passes::Ground& g) {
    g.keep_glow(true);
    g.draw_pools(false);
}

/// The pass's result against the CPU's chain over the pass's own layer, over the composite's region.
struct Chain {
    std::vector<fx::Argb> want;
    std::size_t covered = 0;
    std::size_t differ = 0;
    std::string first;
};

Chain chain_of(const GlowLook& look) {
    Chain c;
    const fx::GlowBlur blur = fx::litglow_blur(static_cast<float>(look.density));
    c.want = fx::litglow_cpu(look.glow, look.w, look.h, rect_of(look.plan.input), blur);
    const passes::PixelBox& out = look.plan.out;
    for (std::int32_t y = out.y0; y < out.y1; ++y) {
        for (std::int32_t x = out.x0; x < out.x1; ++x) {
            const std::size_t i = (static_cast<std::size_t>(y) * look.w) + static_cast<std::size_t>(x);
            if ((c.want[i] >> 24U) != 0) ++c.covered;
            if (look.result[i] == c.want[i]) continue;
            if (c.differ == 0) {
                c.first = std::format("texel {} {}: {:08x}, the CPU's {:08x}", x, y, look.result[i], c.want[i]);
            }
            ++c.differ;
        }
    }
    return c;
}

/// The layer against the runs: each layer pixel's least distance from its centre to a stroke's edge (device px:
/// negative inside), over the layer's drawn box.
struct Coverage {
    std::size_t inside = 0;
    std::size_t outside = 0;
    std::size_t wrong_inside = 0;
    std::size_t wrong_outside = 0;
    std::string first;
};

Coverage coverage_of(const GlowLook& look, const std::vector<fx::Argb>& layer, bool core, fx::Argb colour) {
    const passes::PixelBox& box = core ? look.plan.cores : look.plan.layer;
    std::vector<double> edge(std::size_t{look.w} * look.h, std::numeric_limits<double>::infinity());
    const double d = look.density;
    const auto m = static_cast<double>(look.margin);
    for (const passes::LitRun& r : look.runs) {
        const double hw = (core ? r.core : r.width) * d / 2;
        for (std::uint32_t k = 0; k + 1 < r.count; ++k) {
            const view::Vec2& p = look.points[r.first + k];
            const view::Vec2& q = look.points[r.first + k + 1];
            const double ax = (static_cast<double>(static_cast<float>(p.x)) * d) + m;
            const double ay = (static_cast<double>(static_cast<float>(p.y)) * d) + m;
            const double bx = (static_cast<double>(static_cast<float>(q.x)) * d) + m;
            const double by = (static_cast<double>(static_cast<float>(q.y)) * d) + m;
            const auto x0 = static_cast<std::int32_t>(std::max<double>(box.x0, std::floor(std::min(ax, bx) - hw - 3)));
            const auto x1 = static_cast<std::int32_t>(std::min<double>(box.x1, std::ceil(std::max(ax, bx) + hw + 3)));
            const auto y0 = static_cast<std::int32_t>(std::max<double>(box.y0, std::floor(std::min(ay, by) - hw - 3)));
            const auto y1 = static_cast<std::int32_t>(std::min<double>(box.y1, std::ceil(std::max(ay, by) + hw + 3)));
            const double ex = bx - ax;
            const double ey = by - ay;
            const double ll = (ex * ex) + (ey * ey);
            for (std::int32_t y = y0; y < y1; ++y) {
                for (std::int32_t x = x0; x < x1; ++x) {
                    const double cx = x + 0.5;
                    const double cy = y + 0.5;
                    const double t = ll > 0 ? std::clamp((((cx - ax) * ex) + ((cy - ay) * ey)) / ll, 0.0, 1.0) : 0.0;
                    const double dist = pymath::hypot(cx - (ax + (t * ex)), cy - (ay + (t * ey))) - hw;
                    double& e = edge[(static_cast<std::size_t>(y) * look.w) + static_cast<std::size_t>(x)];
                    e = std::min(e, dist);
                }
            }
        }
    }
    Coverage c;
    for (std::int32_t y = box.y0; y < box.y1; ++y) {
        for (std::int32_t x = box.x0; x < box.x1; ++x) {
            const std::size_t i = (static_cast<std::size_t>(y) * look.w) + static_cast<std::size_t>(x);
            if (edge[i] < -1.0) {
                ++c.inside;
                if (layer[i] == colour) continue;
                if (c.first.empty()) c.first = std::format("inside {} {}: {:08x}", x, y, layer[i]);
                ++c.wrong_inside;
            } else if (edge[i] > 1.5) {
                ++c.outside;
                if (layer[i] == 0) continue;
                if (c.first.empty()) c.first = std::format("outside {} {}: {:08x}", x, y, layer[i]);
                ++c.wrong_outside;
            }
        }
    }
    return c;
}

/// `over` screened under `s` (premultiplied 0-255 channels) at `opacity`: s·o + d − s·o·d / 255.
[[nodiscard]] double screened(double d, double s, double opacity) {
    const double so = s * opacity;
    return so + d - (so * d / 255.0);
}

/// The frame drawn with the lit streets against the frame without them, the layers' contributions screened in turn.
struct Screen {
    std::size_t under = 0;
    std::size_t wrong = 0;
    std::size_t stray = 0;
    double worst = 0;
    std::string first;
};

/// `glow(x, y)` the glow's premultiplied sRGB at the frame's pixel (0 none), `core(x, y)` the cores'.
template <class Glow, class Core>
Screen screen_of(const gpu::Pixels& with, const gpu::Pixels& without, double glow_opacity, const Glow& glow,
                 const Core& core, double tolerance) {
    Screen s;
    for (std::uint32_t y = 0; y < with.height; ++y) {
        for (std::uint32_t x = 0; x < with.width; ++x) {
            const std::size_t i = ((std::size_t{y} * with.width) + x) * 4;
            const fx::Argb g = glow(x, y);
            const fx::Argb c = core(x, y);
            if (g == 0 && c == 0) {
                if (std::memcmp(&with.rgba[i], &without.rgba[i], 3) != 0) ++s.stray;
                continue;
            }
            ++s.under;
            double worst = 0;
            std::array<double, 3> want{};
            for (std::size_t k = 0; k < 3; ++k) {
                const std::uint32_t shift = 16 - (8 * static_cast<std::uint32_t>(k));
                double v = without.rgba[i + k];
                v = screened(v, static_cast<double>((g >> shift) & 0xFFU), glow_opacity);
                v = screened(v, static_cast<double>((c >> shift) & 0xFFU), 0.22);
                want[k] = v;
                worst = std::max(worst, std::abs(static_cast<double>(with.rgba[i + k]) - v));
            }
            s.worst = std::max(s.worst, worst);
            if (worst <= tolerance) continue;
            if (s.wrong == 0) {
                s.first = std::format("pixel {} {}: {} {} {}, expected {:.1f} {:.1f} {:.1f}", x, y, int(with.rgba[i]),
                                      int(with.rgba[i + 1]), int(with.rgba[i + 2]), want[0], want[1], want[2]);
            }
            ++s.wrong;
        }
    }
    return s;
}

/// The filter's premultiplied linear result written back as premultiplied sRGB (the chain's last conversion).
[[nodiscard]] fx::Argb to_srgb(fx::Argb p) {
    return (p >> 24U) == 0 ? 0 : fx::bytes::premultiply(fx::bytes::unpremultiply_exact(p, false));
}

/// The glow's colour opaque, as its layer holds it.
constexpr fx::Argb glow_colour = 0xFFFF'B866U;
constexpr fx::Argb core_colour = 0xFFFF'E2B0U;

/// The home at `camera` at night in full mode with the fields' cache bound, and the same without the lit streets.
struct Drawn {
    GlowLook look;
    gpu::Pixels with;
    gpu::Pixels without;
};

std::optional<Drawn> draw_home(const test::Session& session, const std::string& dir, const style::CameraState& camera,
                               const style::ThemeBlock& theme, frame::Mode mode) {
    auto made = fx::FieldCache::create(*session.device, {.threads = 3});
    if (!made) return std::nullopt;
    const std::shared_ptr<fx::FieldCache> fields(std::move(*made));
    const auto settle = [&fields] { fields->worker().wait_idle(); };
    Drawn d;
    const auto a = test::draw_ground(
        session, camera, theme, dir, "amsterdam",
        [&](passes::Ground& g, const frame::FrameState& state) { take(d.look, g, state); }, 40, quiet, mode, fields,
        settle);
    if (!a) return std::nullopt;
    const auto b = test::draw_ground(
        session, camera, theme, dir, "amsterdam", {}, 40,
        [](passes::Ground& g) {
            quiet(g);
            g.draw_lit(false);
        },
        mode, fields, settle);
    if (!b) return std::nullopt;
    d.with = a->pixels;
    d.without = b->pixels;
    return d;
}

} // namespace

TEST_CASE("ground: the lit streets' glow is SvgFilterRaster's chain over its layer, screened over the ground") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the lit streets are not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    const style::CameraState camera = test::home_camera();
    const auto drawn = draw_home(*session, dir, camera, test::night(), frame::Mode::full);
    ATLAS_REQUIRE(drawn.has_value(), "the home was not drawn at night");
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    const GlowLook& look = drawn->look;
    // the frames kept in the build's scratch folder, to look at
    const auto scratch = std::filesystem::path(ATLAS_TEST_SCRATCH) / "ground";
    test::write_ppm(drawn->with, scratch / "litglow-home-night.ppm");
    test::write_ppm(drawn->without, scratch / "litglow-home-night-without.ppm");
    REQUIRE_MESSAGE(look.drew, "the frame drew no lit streets");
    REQUIRE(look.plan.glow);
    CHECK(look.plan.filtered);
    CHECK(look.plan.cores.empty());
    CHECK(look.opacity == 0.4);
    // the runs' box and the filter's region, in the frame's px
    const auto inf = std::numeric_limits<float>::infinity();
    std::array<float, 4> box{inf, inf, -inf, -inf};
    for (const view::Vec2& p : look.points) {
        box = {std::min(box[0], static_cast<float>(p.x)), std::min(box[1], static_cast<float>(p.y)),
               std::max(box[2], static_cast<float>(p.x)), std::max(box[3], static_cast<float>(p.y))};
    }
    const fx::GlowBlur blur = fx::litglow_blur(static_cast<float>(look.density));
    const std::optional<fx::GlowRect> made = fx::litglow_region(box, static_cast<float>(look.density));
    REQUIRE(made.has_value());
    const fx::GlowRect region = made.value_or(fx::GlowRect{});
    MESSAGE(
        std::format("the home at night: {} runs of {} points, their box {} … {} × {} … {} CSS px, the filter's region "
                    "{} … {} × {} … {} px, a box of {}",
                    look.runs.size(), look.points.size(), box[0], box[2], box[1], box[3], region.x0, region.x1,
                    region.y0, region.y1, blur.side));
    CHECK(look.runs.size() > 20);
    CHECK(look.plan.side == 13);

    const Chain chain = chain_of(look);
    MESSAGE(std::format("the glow's result: {} texels covered of the composite's region, {} differing from the CPU's",
                        chain.covered, chain.differ));
    CHECK(chain.covered > 100000);
    CHECK_MESSAGE(chain.differ == 0, chain.first);

    const Coverage cov = coverage_of(look, look.glow, false, glow_colour);
    MESSAGE(
        std::format("the glow's layer: {} pixels a pixel inside a stroke, {} of them not its colour; {} a pixel and a "
                    "half beyond every stroke, {} of them not empty",
                    cov.inside, cov.wrong_inside, cov.outside, cov.wrong_outside));
    CHECK(cov.inside > 10000);
    CHECK_MESSAGE(cov.wrong_inside == 0, cov.first);
    CHECK_MESSAGE(cov.wrong_outside == 0, cov.first);

    const std::int32_t m = look.margin;
    const passes::PixelBox& out = look.plan.out;
    const Screen s = screen_of(
        drawn->with, drawn->without, look.opacity,
        [&](std::uint32_t x, std::uint32_t y) -> fx::Argb {
            const auto lx = static_cast<std::int32_t>(x) + m;
            const auto ly = static_cast<std::int32_t>(y) + m;
            if (!out.contains(lx, ly)) return 0;
            const fx::Argb r = chain.want[(static_cast<std::size_t>(ly) * look.w) + static_cast<std::size_t>(lx)];
            return to_srgb(r);
        },
        [](std::uint32_t, std::uint32_t) -> fx::Argb { return 0; }, 2.0);
    MESSAGE(
        std::format("the frame: {} pixels under the glow, the worst {:.2f} levels from its screen, {} beyond two; {} "
                    "pixels changed beyond it",
                    s.under, s.worst, s.wrong, s.stray));
    CHECK(s.under > 100000);
    CHECK_MESSAGE(s.wrong == 0, s.first);
    CHECK(s.stray == 0);
}

TEST_CASE("ground: the lit streets' glow at one px a dp (a box of 5) is its chain too") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the lit streets are not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    style::CameraState camera = test::home_camera();
    camera.density = 1.0;
    camera.width_px = 412;
    camera.height_px = 916;
    const auto drawn = draw_home(*session, dir, camera, test::night(), frame::Mode::full);
    ATLAS_REQUIRE(drawn.has_value(), "the home was not drawn at night");
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    const GlowLook& look = drawn->look;
    REQUIRE_MESSAGE(look.drew, "the frame drew no lit streets");
    REQUIRE(look.plan.glow);
    CHECK(look.plan.filtered);
    CHECK(look.plan.side == 5);
    const Chain chain = chain_of(look);
    MESSAGE(std::format("the glow at one px a dp: {} texels covered, {} differing from the CPU's", chain.covered,
                        chain.differ));
    CHECK(chain.covered > 10000);
    CHECK_MESSAGE(chain.differ == 0, chain.first);
    const Coverage cov = coverage_of(look, look.glow, false, glow_colour);
    CHECK(cov.inside > 1000);
    CHECK_MESSAGE(cov.wrong_inside == 0, cov.first);
    CHECK_MESSAGE(cov.wrong_outside == 0, cov.first);
}

TEST_CASE("ground: on a wet night the lit streets' cores are screened after their glow") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the lit streets are not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    style::ThemeBlock wet = test::night_weather();
    wet.flags |= style::theme_flag::wet | style::theme_flag::wet_set;
    const auto drawn = draw_home(*session, dir, test::home_camera(), wet, frame::Mode::full);
    ATLAS_REQUIRE(drawn.has_value(), "the home was not drawn on a wet night");
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    const GlowLook& look = drawn->look;
    REQUIRE_MESSAGE(look.drew, "the frame drew no lit streets");
    REQUIRE(look.plan.glow);
    REQUIRE(!look.plan.cores.empty());
    CHECK(look.opacity == 0.3);
    const Chain chain = chain_of(look);
    CHECK(chain.covered > 100000);
    CHECK_MESSAGE(chain.differ == 0, chain.first);
    const Coverage cov = coverage_of(look, look.cores, true, core_colour);
    MESSAGE(
        std::format("the cores' layer: {} pixels a pixel inside a core, {} of them not its colour; {} a pixel and a "
                    "half beyond every core, {} of them not empty",
                    cov.inside, cov.wrong_inside, cov.outside, cov.wrong_outside));
    CHECK_MESSAGE(cov.wrong_inside == 0, cov.first);
    CHECK_MESSAGE(cov.wrong_outside == 0, cov.first);
    const std::int32_t m = look.margin;
    const passes::PixelBox& out = look.plan.out;
    const auto at = [&](std::uint32_t x, std::uint32_t y) {
        return (static_cast<std::size_t>(static_cast<std::int32_t>(y) + m) * look.w) +
               static_cast<std::size_t>(static_cast<std::int32_t>(x) + m);
    };
    const Screen s = screen_of(
        drawn->with, drawn->without, look.opacity,
        [&](std::uint32_t x, std::uint32_t y) -> fx::Argb {
            const std::size_t i = at(x, y);
            if (!out.contains(static_cast<std::int32_t>(x) + m, static_cast<std::int32_t>(y) + m)) return 0;
            return to_srgb(chain.want[i]);
        },
        [&](std::uint32_t x, std::uint32_t y) -> fx::Argb { return look.cores[at(x, y)]; }, 2.5);
    MESSAGE(std::format("the wet frame: {} pixels under the glow or the cores, the worst {:.2f} levels from their "
                        "screens, {} beyond 2.5; {} pixels changed beyond them",
                        s.under, s.worst, s.wrong, s.stray));
    CHECK(s.under > 100000);
    CHECK_MESSAGE(s.wrong == 0, s.first);
    CHECK(s.stray == 0);
}

TEST_CASE("ground: with the filters off the lit streets' glow is its layer screened as it is") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the lit streets are not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    const auto drawn = draw_home(*session, dir, test::home_camera(), test::night(), frame::Mode::structural);
    ATLAS_REQUIRE(drawn.has_value(), "the home was not drawn at night");
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    const GlowLook& look = drawn->look;
    REQUIRE_MESSAGE(look.drew, "the frame drew no lit streets");
    REQUIRE(look.plan.glow);
    CHECK_FALSE(look.plan.filtered);
    const std::int32_t m = look.margin;
    const Screen s = screen_of(
        drawn->with, drawn->without, look.opacity,
        [&](std::uint32_t x, std::uint32_t y) -> fx::Argb {
            return look.glow[(static_cast<std::size_t>(static_cast<std::int32_t>(y) + m) * look.w) +
                             static_cast<std::size_t>(static_cast<std::int32_t>(x) + m)];
        },
        [](std::uint32_t, std::uint32_t) -> fx::Argb { return 0; }, 2.0);
    MESSAGE(std::format("the frame with the filters off: {} pixels under the strokes, the worst {:.2f} levels from "
                        "their screen, {} beyond two; {} pixels changed beyond them",
                        s.under, s.worst, s.wrong, s.stray));
    CHECK(s.under > 10000);
    CHECK_MESSAGE(s.wrong == 0, s.first);
    CHECK(s.stray == 0);
}

#endif
