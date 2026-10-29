// draw_over's park shadows on the host's Vulkan (passes/Shadows.cpp's woods and tracks): a theme park's window drawn by
// the ground alone with the sun up and with it set, which differ only by the woods' and the coaster tracks' shadows.
// Away from the tracks' shadows the difference is the woods' union (th['shadow'] over the ground at shadow_op), held to
// a CPU raster of the twin's woods path (each tree's 12-gon as the port projects it) at the standard 4x sample
// positions; the tracks' shadows darken the ground along their paths.
#if !defined(__ANDROID__)

#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Offscreen.hpp"
#include "gpu/PipelineCache.hpp"
#include "passes/Ground.hpp"
#include "scene/Cover.hpp"
#include "scene/World.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"
#include "tests/ground/park_window.hpp"
#include "twin/Twin.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <optional>
#include <string>
#include <vector>

using namespace atlas;

namespace {

/// The standard 4x sample positions within a pixel.
constexpr std::array<std::array<double, 2>, 4> samples{
    {{0.375, 0.125}, {0.875, 0.375}, {0.125, 0.625}, {0.625, 0.875}}};

/// Whether (x, y) is inside the polygon by the nonzero rule.
bool inside(const std::vector<std::array<double, 2>>& poly, double x, double y) {
    int w = 0;
    const std::size_t n = poly.size();
    for (std::size_t i = 0; i < n; ++i) {
        const auto& a = poly[i];
        const auto& b = poly[(i + 1) % n];
        const double cross = ((b[0] - a[0]) * (y - a[1])) - ((x - a[0]) * (b[1] - a[1]));
        if (a[1] <= y) {
            if (b[1] > y && cross > 0) ++w;
        } else if (b[1] <= y && cross < 0) {
            --w;
        }
    }
    return w != 0;
}

/// What a frame of the park drew: its pixels, the ground's decisions, the twin's woods' subpaths and the tracks'
/// shadows' paths (device px), with their widths.
struct ParkDraw {
    gpu::Pixels pixels;
    passes::GroundFrame decided;
    std::vector<std::vector<std::array<double, 2>>> woods;
    std::vector<std::vector<std::array<double, 2>>> tracks;
    std::vector<double> widths;
};

/// The park's window drawn by the ground alone at `camera` with `theme`, until its tile is built and faded in.
std::optional<ParkDraw> draw_park(const test::Session& session, const std::string& path,
                                  const style::CameraState& camera, const style::ThemeBlock& theme) {
    const gpu::Device& device = *session.device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    if (!cache) return std::nullopt;
    constexpr std::array<std::string_view, 3> ground_only{"composite", "frosted", "ground"};
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes(ground_only);
    passes::Ground* g = test::ground_of(passes);
    auto builder = frame::FrameBuilder::create(device, *cache, std::move(passes), {.steps = 0});
    if (!builder) return std::nullopt;
    double now = 0;
    auto world = scene::World::create(device, (*builder)->passes(),
                                      {.workers = 3, .sun = {.enabled = false}, .clock = [&now] { return now; }});
    if (!world) return std::nullopt;
    const std::string id = "window/" + load::place_of(tiles::tile_of(14, 523'746'000, 48'840'000)).name;
    if (!(*world)->store().add({.id = id, .kind = tiles::PackKind::window, .path = path})) return std::nullopt;
    auto output = gpu::Offscreen::create(device, {.width = camera.width_px, .height = camera.height_px}, 2);
    if (!output) return std::nullopt;
    (*builder)->set_inputs(
        {.camera = camera, .theme = theme, .seconds = 0, .mode = frame::Mode::structural, .scene = world->get()});
    const gpu::Configuration config{.config = gpu::FrameConfig::c,
                                    .route = gpu::Route::renderpass,
                                    .form = gpu::FrameForm::composite,
                                    .target = gpu::CompositeTarget::resolve};
    auto renderer = gpu::Renderer::create(device, **output, **builder, config);
    if (!renderer) return std::nullopt;
    bool settled = false;
    for (int f = 0; f < 61 && !settled; ++f) {
        now = static_cast<double>(f) * 0.25;
        (*builder)->set_inputs(
            {.camera = camera, .theme = theme, .seconds = now, .mode = frame::Mode::structural, .scene = world->get()});
        if (!(*renderer)->draw(0)) return std::nullopt;
        (*renderer)->drain();
        (*world)->residency().wait_idle();
        const scene::Cover& cover = *(*world)->cover();
        settled = f > 4 && cover.missing() == 0 && !cover.tiles().empty() &&
                  std::ranges::all_of(cover.tiles(), [](const scene::CoverTile& t) { return t.fade >= 1.0F; });
    }
    // one more frame, all of it drawn
    if (!(*renderer)->draw(0)) return std::nullopt;
    (*renderer)->drain();
    auto packed = (*output)->read_packed((*output)->latest());
    if (!packed) return std::nullopt;
    ParkDraw d{.pixels = gpu::to_rgba8(*packed), .decided = g->decided(), .woods = {}, .tracks = {}, .widths = {}};
    const frame::FrameState& state = (*builder)->state();
    twin::Stream s;
    g->emit(state, s, passes::Ground::phase_opaque);
    const twin::Decoded decoded = twin::decode(s.bytes());
    REQUIRE(decoded.error.empty());
    const float density = state.block.camera.frame.z;
    const std::uint32_t woods = g->slot(geometry::group::over + 4, 0, 0);
    for (const twin::Element& e : decoded.document.elements) {
        if (e.layer != twin::Layer::ground || e.kind != twin::Op::path) continue;
        if (d.decided.woods && e.slot == woods) {
            // a subpath a tree
            std::size_t start = 0;
            for (const std::uint32_t end : e.subpath_ends) {
                std::vector<std::array<double, 2>> p;
                for (std::size_t k = start; k < end; ++k) {
                    p.push_back(
                        {static_cast<double>(e.points[k].x * density), static_cast<double>(e.points[k].y * density)});
                }
                d.woods.push_back(std::move(p));
                start = end;
            }
        } else if (e.slot > woods && e.slot <= woods + d.decided.track_shadows.size()) {
            std::vector<std::array<double, 2>> p;
            p.reserve(e.points.size());
            for (const twin::Point& q : e.points) {
                p.push_back({static_cast<double>(q.x * density), static_cast<double>(q.y * density)});
            }
            d.tracks.push_back(std::move(p));
            d.widths.push_back(static_cast<double>(e.style.stroke_width * density));
        }
    }
    for (const std::string& m : session.take_messages()) CHECK_MESSAGE(false, m);
    renderer->reset();
    return d;
}

/// The distance from (x, y) to the segment from a to b.
double distance(const std::array<double, 2>& a, const std::array<double, 2>& b, double x, double y) {
    const double dx = b[0] - a[0];
    const double dy = b[1] - a[1];
    const double l2 = (dx * dx) + (dy * dy);
    const double t = l2 == 0.0 ? 0.0 : std::clamp((((x - a[0]) * dx) + ((y - a[1]) * dy)) / l2, 0.0, 1.0);
    return pymath::hypot(a[0] + (t * dx) - x, a[1] + (t * dy) - y);
}

} // namespace

TEST_CASE("ground: a park's woods' shadows are the union of the twin's 12-gons, sample for sample, under its tracks' "
          "shadows") {
    auto session = test::open_session();
    if (!session) return;
    const std::string path = test::park_window("park-shadows-vk");
    ATLAS_REQUIRE(!path.empty());
    const style::CameraState camera = test::s1_camera(52.3747, 4.8844, 30.0, 50.0, 300.0);
    // the sunny moment's light without a blur, and the sun set
    style::ThemeBlock up = test::sunny();
    up.flags &= ~style::theme_flag::shadow_blur;
    up.shadow_blur = 0.0;
    style::ThemeBlock set = up;
    set.sun_vector = {0.0, -1.0, 0.0};
    set.sun_elevation = -5.0;
    const auto a = draw_park(*session, path, camera, up);
    ATLAS_REQUIRE(a.has_value(), "the park with the sun up was not drawn");
    const auto b = draw_park(*session, path, camera, set);
    ATLAS_REQUIRE(b.has_value(), "the park with the sun set was not drawn");
    CHECK(a->decided.woods);
    CHECK_FALSE(b->decided.woods);
    CHECK(b->decided.track_shadows.empty());
    // with the sun up, the map leaving the park shadows out (style::camera_part::park_shadows): neither the woods' nor
    // the tracks' decided, the buildings' as with them
    style::CameraState no_parks = camera;
    no_parks.parts = style::camera_part::park_shadows;
    const auto without = draw_park(*session, path, no_parks, up);
    ATLAS_REQUIRE(without.has_value(), "the park without its shadows was not drawn");
    CHECK_FALSE(without->decided.woods);
    CHECK(without->decided.track_shadows.empty());
    CHECK(without->decided.shadows == a->decided.shadows);
    REQUIRE(a->woods.size() > 20);
    REQUIRE(!a->tracks.empty());
    const std::uint32_t w = a->pixels.width;
    const std::uint32_t h = a->pixels.height;
    // the woods' union at the four samples of each pixel, and the pixels the tracks' shadows reach
    std::vector<std::uint8_t> cpu(std::size_t{w} * h, 0);
    std::vector<std::uint8_t> tracked(std::size_t{w} * h, 0);
    for (const auto& p : a->woods) {
        double x0 = p[0][0];
        double x1 = x0;
        double y0 = p[0][1];
        double y1 = y0;
        for (const auto& q : p) {
            x0 = std::min(x0, q[0]);
            x1 = std::max(x1, q[0]);
            y0 = std::min(y0, q[1]);
            y1 = std::max(y1, q[1]);
        }
        for (auto y = static_cast<std::int64_t>(std::max(0.0, std::floor(y0)));
             y <= static_cast<std::int64_t>(std::min(static_cast<double>(h) - 1, std::floor(y1))); ++y) {
            for (auto x = static_cast<std::int64_t>(std::max(0.0, std::floor(x0)));
                 x <= static_cast<std::int64_t>(std::min(static_cast<double>(w) - 1, std::floor(x1))); ++x) {
                std::uint8_t& c = cpu[(static_cast<std::size_t>(y) * w) + static_cast<std::size_t>(x)];
                for (std::size_t k = 0; k < samples.size(); ++k) {
                    if (inside(p, static_cast<double>(x) + samples[k][0], static_cast<double>(y) + samples[k][1])) {
                        c = static_cast<std::uint8_t>(c | (1U << k));
                    }
                }
            }
        }
    }
    std::size_t darkened = 0;
    std::size_t along = 0;
    for (std::size_t t = 0; t < a->tracks.size(); ++t) {
        const auto& p = a->tracks[t];
        const double reach = (a->widths[t] / 2) + 2.0;
        for (std::size_t k = 0; k + 1 < p.size(); ++k) {
            const double x0 = std::min(p[k][0], p[k + 1][0]) - reach;
            const double x1 = std::max(p[k][0], p[k + 1][0]) + reach;
            const double y0 = std::min(p[k][1], p[k + 1][1]) - reach;
            const double y1 = std::max(p[k][1], p[k + 1][1]) + reach;
            for (auto y = static_cast<std::int64_t>(std::max(0.0, std::floor(y0)));
                 y <= static_cast<std::int64_t>(std::min(static_cast<double>(h) - 1, std::floor(y1))); ++y) {
                for (auto x = static_cast<std::int64_t>(std::max(0.0, std::floor(x0)));
                     x <= static_cast<std::int64_t>(std::min(static_cast<double>(w) - 1, std::floor(x1))); ++x) {
                    const double d =
                        distance(p[k], p[k + 1], static_cast<double>(x) + 0.5, static_cast<double>(y) + 0.5);
                    if (d > reach) continue;
                    const std::size_t i = (static_cast<std::size_t>(y) * w) + static_cast<std::size_t>(x);
                    tracked[i] = 1;
                    // a pixel the shadow wholly covers, away from the woods': darker with the sun up
                    if (d + 0.75 < a->widths[t] / 2 && cpu[i] == 0) {
                        ++along;
                        const std::uint8_t* pa = &a->pixels.rgba[i * 4];
                        const std::uint8_t* pb = &b->pixels.rgba[i * 4];
                        if (pa[0] + pa[1] + pa[2] + 3 < pb[0] + pb[1] + pb[2]) ++darkened;
                    }
                }
            }
        }
    }
    // the GPU's coverage away from the tracks' shadows: (without - with) / (shadow_op · (without - shadow))
    const auto op = static_cast<double>(static_cast<float>(passes::printed(up.shadow_opacity, 2)));
    const std::array<double, 3> shadow{static_cast<double>(up.shadow.r), static_cast<double>(up.shadow.g),
                                       static_cast<double>(up.shadow.b)};
    std::size_t told = 0;
    std::size_t off = 0;
    std::size_t covered = 0;
    double worst = 0;
    for (std::size_t i = 0; i < std::size_t{w} * h; ++i) {
        if (tracked[i] != 0) continue;
        const std::uint8_t* pa = &a->pixels.rgba[i * 4];
        const std::uint8_t* pb = &b->pixels.rgba[i * 4];
        int best = 0;
        double span = 0;
        for (int k = 0; k < 3; ++k) {
            const double s = std::abs(static_cast<double>(pb[k]) - shadow[static_cast<std::size_t>(k)]);
            if (s > span) {
                span = s;
                best = k;
            }
        }
        const double want = static_cast<double>(std::popcount(cpu[i])) / 4;
        if (want == 1.0) ++covered;
        if (span < 24) continue;
        ++told;
        const double got = (static_cast<double>(pb[best]) - static_cast<double>(pa[best])) /
                           (op * (static_cast<double>(pb[best]) - shadow[static_cast<std::size_t>(best)]));
        const double d = std::abs(got - want);
        worst = std::max(worst, d);
        if (d > 0.25 + 0.05) ++off;
    }
    MESSAGE("the park by day: " << a->woods.size() << " trees' shadows, " << a->tracks.size()
                                << " tracks' shadows' paths (" << a->decided.track_shadows.size() << " elements); "
                                << covered << " pixels wholly in the woods' shadow; of " << told
                                << " pixels that tell, " << off << " off by more than a sample, the worst by " << worst
                                << "; " << darkened << " of " << along << " pixels along the tracks' shadows darker");
    CHECK(covered > 1000);
    CHECK(off <= told / 20000);
    CHECK(along > 50);
    CHECK(static_cast<double>(darkened) >= 0.95 * static_cast<double>(along));
}

#endif
