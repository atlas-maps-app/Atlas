// The ground shadows on the host's Vulkan (plan-vector K9, D8 "Ground shadows"; L2 (b) for the shadows): at the
// Westerkerk home, the frame drawn with the sun up and with it set differ only by the shadows.
//
// Drawn sharp (the moments without a blur: golden and noon), their difference is the shadows' coverage per pixel
// (th['shadow'] over the ground at shadow_op). It is held to a CPU raster of the twin's shadow paths (the port's own
// polygons: K8's hull2 of each footprint and its moved top, each raised outline moved, each crown's 16-gon), their
// union at the standard 4x sample positions: the vertex stage's swept hulls (passes/Shadows.cpp) cover what the port's
// hulls cover, sample for sample but where an edge passes within float rounding of a sample.
//
// Drawn soft (the sunny moment's 0.6 CSS px, in full mode: structural mode turns the renders' filters off), each step
// is held to the CPU's: the box the GPU measured to the twin's
// paths' (one print step at most), the grid it worked out to fx::blur_grid's from that box, its mask to the twin's
// paths rastered on that grid (sample for sample, as above), its blur of that mask to fx::square_blur_cpu's and the
// fade bit for bit, and the frame to the result drawn back as soft_fs draws it.
//
// And the soft shadows' kept mask (passes/Shadows.cpp's key_shadow_mask): the same frames drawn with the mask made
// every frame (make_mask_always) and kept where nothing it reads moved are the same pixels, frame for frame, and a
// frame that keeps it dispatches nothing for it; the building shadows or the soft shadows left out for a frame and
// drawn again keep it too.
#if !defined(__ANDROID__)

#include "fx/Blur.hpp"
#include "fx/BlurGrid.hpp"
#include "gen/layouts.hpp"
#include "passes/Ground.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"
#include "twin/Twin.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <limits>
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

/// The frames without their quay walls and the walls' reflections: their light changes with the sun's too, as the
/// ground's world has no sun bits and a wall facing the sun is lit by day without them.
void no_walls(passes::Ground& g) {
    g.draw_quays(false);
    g.draw_reflections(false);
}

} // namespace

TEST_CASE("ground: the shadows' union covers the twin's shadow paths, sample for sample") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the shadows are not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    const style::CameraState camera = test::home_camera();
    // the sunny moment's light with the sharp shadows of a moment without a blur
    style::ThemeBlock up = test::sunny();
    up.flags &= ~style::theme_flag::shadow_blur;
    up.shadow_blur = 0.0;
    style::ThemeBlock set = up;
    set.sun_vector = {0.0, -1.0, 0.0};
    set.sun_elevation = -5.0;
    // the frame with the sun up, and its twin's shadow paths in device px
    std::vector<std::vector<std::array<double, 2>>> polys;
    std::size_t tiles = 0;
    const auto a = test::draw_ground(
        *session, camera, up, dir, "amsterdam",
        [&](passes::Ground& g, const frame::FrameState& state) {
            twin::Stream s;
            g.emit(state, s, passes::Ground::phase_opaque);
            const twin::Decoded d = twin::decode(s.bytes());
            REQUIRE(d.error.empty());
            const std::uint32_t slot = g.slot(geometry::group::shadow, 0, 0);
            // device px as the vertex stage finds them: CSS px times the density, f32
            const float density = state.block.camera.frame.z;
            for (const twin::Element& e : d.document.elements) {
                if (e.layer != twin::Layer::ground || e.slot != slot) continue;
                if (e.kind != twin::Op::path || e.clip != twin::none) continue;
                std::vector<std::array<double, 2>> p;
                p.reserve(e.points.size());
                for (const twin::Point& q : e.points) {
                    p.push_back({static_cast<double>(q.x * density), static_cast<double>(q.y * density)});
                }
                polys.push_back(std::move(p));
            }
            tiles = g.decided().tiles.size();
        },
        40, no_walls);
    ATLAS_REQUIRE(a.has_value(), "the frame with the sun up was not drawn");
    CHECK(a->decided.shadows);
    const auto b = test::draw_ground(*session, camera, set, dir, "amsterdam", {}, 40, no_walls);
    ATLAS_REQUIRE(b.has_value(), "the frame with the sun set was not drawn");
    CHECK_FALSE(b->decided.shadows);
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    REQUIRE(polys.size() > 100);
    // the union of the paths at the four samples of each pixel
    const std::uint32_t w = a->pixels.width;
    const std::uint32_t h = a->pixels.height;
    std::vector<std::uint8_t> cpu(std::size_t{w} * h, 0);
    for (const auto& p : polys) {
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
        const auto px0 = static_cast<std::int64_t>(std::max(0.0, std::floor(x0)));
        const auto px1 = static_cast<std::int64_t>(std::min(static_cast<double>(w) - 1, std::floor(x1)));
        const auto py0 = static_cast<std::int64_t>(std::max(0.0, std::floor(y0)));
        const auto py1 = static_cast<std::int64_t>(std::min(static_cast<double>(h) - 1, std::floor(y1)));
        for (std::int64_t y = py0; y <= py1; ++y) {
            for (std::int64_t x = px0; x <= px1; ++x) {
                std::uint8_t& c = cpu[(static_cast<std::size_t>(y) * w) + static_cast<std::size_t>(x)];
                for (std::size_t k = 0; k < samples.size(); ++k) {
                    if ((c & (1U << k)) != 0) continue;
                    if (inside(p, static_cast<double>(x) + samples[k][0], static_cast<double>(y) + samples[k][1])) {
                        c = static_cast<std::uint8_t>(c | (1U << k));
                    }
                }
            }
        }
    }
    // the GPU's coverage: (without - with) / (shadow_op · (without - shadow)), on the channel that tells it best
    const double op = up.shadow_opacity;
    const std::array<double, 3> shadow{static_cast<double>(up.shadow.r), static_cast<double>(up.shadow.g),
                                       static_cast<double>(up.shadow.b)};
    std::size_t off = 0;
    std::size_t covered = 0;
    std::size_t told = 0;
    double worst = 0;
    for (std::size_t i = 0; i < std::size_t{w} * h; ++i) {
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
        if (span < 24) continue; // the ground's colour too near the shadow's to tell
        ++told;
        const double got = (static_cast<double>(pb[best]) - static_cast<double>(pa[best])) /
                           (op * (static_cast<double>(pb[best]) - shadow[static_cast<std::size_t>(best)]));
        const double d = std::abs(got - want);
        worst = std::max(worst, d);
        // a sample may flip where an edge passes within float rounding of it: a quarter, and the 8 bits' rounding
        if (d > 0.25 + 0.05) ++off;
    }
    MESSAGE("home sunny: " << tiles << " tiles, " << polys.size() << " shadow paths in the twin; " << covered
                           << " pixels wholly in shadow; of " << told << " pixels that tell, " << off
                           << " off by more than a sample, the worst by " << worst);
    CHECK(static_cast<double>(covered) > 0.2 * static_cast<double>(w) * h);
    CHECK(off <= told / 20000);
}

namespace {

/// The twin's shadow paths at the frame `g` last drew, CSS px as the SVG prints them.
std::vector<std::vector<std::array<float, 2>>> twin_paths(passes::Ground& g, const frame::FrameState& state) {
    twin::Stream s;
    g.emit(state, s, passes::Ground::phase_opaque);
    const twin::Decoded d = twin::decode(s.bytes());
    REQUIRE(d.error.empty());
    const std::uint32_t slot = g.slot(geometry::group::shadow, 0, 0);
    std::vector<std::vector<std::array<float, 2>>> out;
    for (const twin::Element& e : d.document.elements) {
        if (e.layer != twin::Layer::ground || e.slot != slot) continue;
        if (e.kind != twin::Op::path || e.clip != twin::none) continue;
        std::vector<std::array<float, 2>> p;
        p.reserve(e.points.size());
        for (const twin::Point& q : e.points) p.push_back({q.x, q.y});
        out.push_back(std::move(p));
    }
    return out;
}

/// A ShadowGrid key's float (shared/shadow_grid.slang's box_value).
[[nodiscard]] float box_value(std::uint32_t k) noexcept {
    return std::bit_cast<float>((k & 0x8000'0000U) != 0 ? (k & 0x7FFF'FFFFU) : ~k);
}

} // namespace

TEST_CASE("ground: the soft shadows are the port's grid, its three boxes and its fade, drawn back over the frame") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the shadows are not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    const style::CameraState camera = test::home_camera();
    const style::ThemeBlock up = test::sunny();
    REQUIRE(up.shadow_blur > 0.0);
    style::ThemeBlock set = up;
    set.sun_vector = {0.0, -1.0, 0.0};
    set.sun_elevation = -5.0;
    // the frame with the sun up: the twin's paths, and the soft shadows' grid, mask and blurred mask as the GPU left
    // them in the last frame's entry, and in the other entry, the frame before it's (at rest, the same frame; each
    // frame makes its mask here, none standing: make_mask_always)
    std::vector<std::vector<std::array<float, 2>>> paths;
    layouts::ground_ground::ShadowGrid grid{};
    using GridBytes = std::array<std::uint8_t, sizeof grid>;
    GridBytes grid_bytes{};
    GridBytes other_grid_bytes{};
    std::vector<std::uint8_t> mask;
    std::vector<std::uint8_t> other_mask;
    std::vector<std::uint32_t> blurred;
    std::vector<std::uint32_t> other_blurred;
    vk::Extent2D extent{};
    std::uint32_t stride = 0;
    vk::DeviceSize slot_bytes = 0;
    vk::DeviceSize samples_bytes = 0;
    bool soft = false;
    float density = 0;
    std::int32_t clip_w = 0;
    std::int32_t clip_h = 0;
    const auto a = test::draw_ground(
        *session, camera, up, dir, "amsterdam",
        [&](passes::Ground& g, const frame::FrameState& state) {
            soft = g.drew_soft();
            density = state.block.camera.frame.z;
            clip_w = static_cast<std::int32_t>(std::ceil(state.block.camera.frame.x));
            clip_h = static_cast<std::int32_t>(std::ceil(state.block.camera.frame.y));
            paths = twin_paths(g, state);
            const passes::SoftShadows* s = g.soft_shadows();
            REQUIRE(s != nullptr);
            REQUIRE(s->kept);
            CHECK(s->result);
            extent = s->extent;
            stride = s->stride;
            const auto look = [&](const passes::SoftShadowSlot& t, GridBytes& g_out, std::vector<std::uint8_t>& m_out,
                                  std::vector<std::uint32_t>& b_out) {
                t.grid.invalidate();
                std::memcpy(g_out.data(), t.grid.bytes().data(), g_out.size());
                t.mask_copy.invalidate();
                m_out.resize(std::size_t{extent.width} * extent.height);
                std::memcpy(m_out.data(), t.mask_copy.bytes().data(), m_out.size());
                t.blurred[0].invalidate();
                b_out.resize(std::size_t{stride} * extent.height);
                std::memcpy(b_out.data(), t.blurred[0].bytes().data(), b_out.size() * 4);
            };
            look(s->slots[s->last], grid_bytes, mask, blurred);
            look(s->slots[1 - s->last], other_grid_bytes, other_mask, other_blurred);
            std::memcpy(&grid, grid_bytes.data(), sizeof grid);
            const passes::SoftShadowSlot& first = s->slots[0];
            slot_bytes = first.mask.bytes() + first.grid.size() + first.blurred[0].size() + first.blurred[1].size();
            samples_bytes = s->samples.bytes();
        },
        40,
        [](passes::Ground& g) {
            g.keep_soft_shadows(true);
            g.make_mask_always(true);
            no_walls(g);
        },
        frame::Mode::full);
    ATLAS_REQUIRE(a.has_value(), "the frame with the sun up was not drawn");
    const auto b = test::draw_ground(*session, camera, set, dir, "amsterdam", {}, 40, no_walls, frame::Mode::full);
    ATLAS_REQUIRE(b.has_value(), "the frame with the sun set was not drawn");
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    REQUIRE(soft);
    REQUIRE(paths.size() > 100);
    // each entry its own grid, mask and blur, and the last two frames', at rest, the same in both
    MESSAGE("a frame slot's soft shadows at " << a->pixels.width << " x " << a->pixels.height << ": " << slot_bytes
                                              << " bytes (the " << extent.width << " x " << extent.height
                                              << " mask, the grid, the blur's two buffers); the 4x samples, one "
                                                 "for both slots: "
                                              << samples_bytes << " bytes");
    CHECK(grid_bytes == other_grid_bytes);
    CHECK(mask == other_mask);
    // the blur where it writes, the grid's input: past it a buffer holds what its memory held before
    std::size_t slots_differ = 0;
    for (std::int32_t y = 0; y < grid.input[3]; ++y) {
        for (std::int32_t x = 0; x < grid.input[2]; ++x) {
            const std::size_t at = (static_cast<std::size_t>(y) * stride) + (static_cast<std::size_t>(x) / 4);
            const std::uint32_t shift = 8U * (static_cast<std::uint32_t>(x) % 4U);
            if ((((blurred[at] ^ other_blurred[at]) >> shift) & 0xFFU) != 0) ++slots_differ;
        }
    }
    CHECK_MESSAGE(slots_differ == 0, slots_differ << " texels of the two slots' blurs differ");

    // 1. the box: the twin's paths' (SvgBounds over their printed points), within a print step of the GPU's
    std::array<float, 4> box{std::numeric_limits<float>::infinity(), std::numeric_limits<float>::infinity(),
                             -std::numeric_limits<float>::infinity(), -std::numeric_limits<float>::infinity()};
    for (const auto& p : paths) {
        for (const auto& q : p) {
            box[0] = std::min(box[0], q[0]);
            box[1] = std::min(box[1], q[1]);
            box[2] = std::max(box[2], q[0]);
            box[3] = std::max(box[3], q[1]);
        }
    }
    const std::array<float, 4> measured{box_value(grid.box[0]), box_value(grid.box[1]), box_value(grid.box[2]),
                                        box_value(grid.box[3])};
    MESSAGE("home sunny: the twin's box " << box[0] << " " << box[1] << " " << box[2] << " " << box[3] << ", the GPU's "
                                          << measured[0] << " " << measured[1] << " " << measured[2] << " "
                                          << measured[3]);
    for (std::size_t k = 0; k < 4; ++k) CHECK(std::abs(measured[k] - box[k]) <= 0.1001F);

    // 2. the grid: fx::blur_grid's from the GPU's own box
    const auto sigma = static_cast<float>(passes::printed(up.shadow_blur, 1));
    const fx::BlurGrid want = fx::blur_grid(measured, sigma, density, clip_w, clip_h);
    REQUIRE(want.drawn);
    MESSAGE("the grid: scale " << grid.scale[0] << " (the clamp " << grid.scale[1] << "), input " << grid.input[0]
                               << " " << grid.input[1] << " " << grid.input[2] << " x " << grid.input[3] << ", box "
                               << grid.blur[0]);
    CHECK((grid.blur[3] & 1U) != 0);
    CHECK(((grid.blur[3] & 2U) != 0) == (want.clamp < 1.0));
    CHECK(static_cast<double>(grid.scale[0]) == doctest::Approx(want.scale).epsilon(1e-6));
    CHECK(grid.output[0] == want.output.left);
    CHECK(grid.output[1] == want.output.top);
    CHECK(grid.output[2] == want.output.right);
    CHECK(grid.output[3] == want.output.bottom);
    CHECK(grid.input[0] == want.input.left);
    CHECK(grid.input[1] == want.input.top);
    CHECK(grid.input[2] == want.input.width());
    CHECK(grid.input[3] == want.input.height());
    CHECK(grid.blur[0] == want.box);
    CHECK(grid.blur[2] == (want.box * want.box) / 2);

    // 3. the mask: the twin's paths on the grid at the standard samples, sample for sample but where an edge passes
    // within float rounding of one; each texel a whole 63 for each sample covered
    const auto w = static_cast<std::size_t>(grid.input[2]);
    const auto h = static_cast<std::size_t>(grid.input[3]);
    REQUIRE(w <= extent.width);
    REQUIRE(h <= extent.height);
    const auto scale = static_cast<double>(grid.scale[0]);
    std::vector<std::uint8_t> cpu(w * h, 0);
    for (const auto& p : paths) {
        std::vector<std::array<double, 2>> m;
        m.reserve(p.size());
        double x0 = 1e30;
        double x1 = -1e30;
        double y0 = 1e30;
        double y1 = -1e30;
        for (const auto& q : p) {
            const double x = (static_cast<double>(q[0]) * scale) - grid.input[0];
            const double y = (static_cast<double>(q[1]) * scale) - grid.input[1];
            m.push_back({x, y});
            x0 = std::min(x0, x);
            x1 = std::max(x1, x);
            y0 = std::min(y0, y);
            y1 = std::max(y1, y);
        }
        const auto px0 = static_cast<std::int64_t>(std::max(0.0, std::floor(x0)));
        const auto px1 = static_cast<std::int64_t>(std::min(static_cast<double>(w) - 1, std::floor(x1)));
        const auto py0 = static_cast<std::int64_t>(std::max(0.0, std::floor(y0)));
        const auto py1 = static_cast<std::int64_t>(std::min(static_cast<double>(h) - 1, std::floor(y1)));
        for (std::int64_t y = py0; y <= py1; ++y) {
            for (std::int64_t x = px0; x <= px1; ++x) {
                std::uint8_t& c = cpu[(static_cast<std::size_t>(y) * w) + static_cast<std::size_t>(x)];
                for (std::size_t k = 0; k < samples.size(); ++k) {
                    if ((c & (1U << k)) != 0) continue;
                    if (inside(m, static_cast<double>(x) + samples[k][0], static_cast<double>(y) + samples[k][1])) {
                        c = static_cast<std::uint8_t>(c | (1U << k));
                    }
                }
            }
        }
    }
    std::size_t whole = 0;
    std::size_t off = 0;
    std::size_t covered = 0;
    std::vector<std::uint8_t> coverage(w * h);
    for (std::size_t y = 0; y < h; ++y) {
        for (std::size_t x = 0; x < w; ++x) {
            const std::uint8_t v = mask[(y * extent.width) + x];
            if (v % 63 == 0) ++whole;
            const auto n = static_cast<std::uint32_t>((v + 31) / 63);
            const auto want_n = static_cast<std::uint32_t>(std::popcount(cpu[(y * w) + x]));
            if (n == 4) ++covered;
            if ((n > want_n ? n - want_n : want_n - n) > 1) ++off;
            coverage[(y * w) + x] = static_cast<std::uint8_t>(((n * 255) + 2) / 4);
        }
    }
    MESSAGE("the mask: " << w << " x " << h << " texels, " << covered << " wholly in shadow, " << off
                         << " off by more than a sample, " << (w * h - whole) << " not a whole level");
    CHECK(whole == w * h);
    CHECK(static_cast<double>(covered) > 0.2 * static_cast<double>(w * h));
    CHECK(off <= (w * h) / 20000);

    // 4. the blur: vImage's three boxes over the GPU's own mask, the last faded as the port fades its bitmap, bit for
    // bit
    fx::square_blur_cpu(coverage, static_cast<std::uint32_t>(w), static_cast<std::uint32_t>(h), grid.blur[0], 3);
    const auto op = static_cast<float>(passes::printed(up.shadow_opacity, 2));
    auto blurred_at = [&](std::size_t x, std::size_t y) {
        return (blurred[(y * stride) + (x / 4)] >> (8U * (x % 4))) & 0xFFU;
    };
    std::size_t differ = 0;
    for (std::size_t y = 0; y < h; ++y) {
        for (std::size_t x = 0; x < w; ++x) {
            // the port's (a · opacity + ½).toInt(): a float truncated
            const float scaled = (static_cast<float>(coverage[(y * w) + x]) * op) + 0.5F;
            if (blurred_at(x, y) != static_cast<std::uint32_t>(scaled)) ++differ;
        }
    }
    CHECK(differ == 0);

    // 5. the frame: the result drawn back over the frame with the sun set, as soft_fs draws it
    const bool filtered = (grid.blur[3] & 2U) != 0;
    const std::array<double, 3> shadow{static_cast<double>(up.shadow.r), static_cast<double>(up.shadow.g),
                                       static_cast<double>(up.shadow.b)};
    auto tap = [&](std::int64_t gx, std::int64_t gy) {
        const std::int64_t x = std::clamp<std::int64_t>(gx, grid.output[0], grid.output[2] - 1) - grid.input[0];
        const std::int64_t y = std::clamp<std::int64_t>(gy, grid.output[1], grid.output[3] - 1) - grid.input[1];
        return static_cast<double>(blurred_at(static_cast<std::size_t>(x), static_cast<std::size_t>(y)));
    };
    std::size_t told = 0;
    std::size_t wrong = 0;
    double worst = 0;
    for (std::uint32_t y = 0; y < a->pixels.height; ++y) {
        for (std::uint32_t x = 0; x < a->pixels.width; ++x) {
            const double gx = (static_cast<double>(x) + 0.5) / static_cast<double>(density) * scale;
            const double gy = (static_cast<double>(y) + 0.5) / static_cast<double>(density) * scale;
            double alpha = 0;
            if (gx >= grid.output[0] && gx < grid.output[2] && gy >= grid.output[1] && gy < grid.output[3]) {
                if (!filtered) {
                    alpha = tap(static_cast<std::int64_t>(std::floor(gx)), static_cast<std::int64_t>(std::floor(gy)));
                } else {
                    const double u = gx - 0.5;
                    const double v = gy - 0.5;
                    const double i0 = std::floor(u);
                    const double j0 = std::floor(v);
                    const double tu = u - i0;
                    const double tv = v - j0;
                    const auto i = static_cast<std::int64_t>(i0);
                    const auto j = static_cast<std::int64_t>(j0);
                    const double top = (tap(i, j) * (1 - tu)) + (tap(i + 1, j) * tu);
                    const double bottom = (tap(i, j + 1) * (1 - tu)) + (tap(i + 1, j + 1) * tu);
                    alpha = (top * (1 - tv)) + (bottom * tv);
                }
                alpha /= 255;
            }
            const std::size_t at = ((std::size_t{y} * a->pixels.width) + x) * 4;
            if (alpha > 0) ++told;
            for (std::size_t k = 0; k < 3; ++k) {
                const double under = b->pixels.rgba[at + k];
                const double want_c = (shadow[k] * alpha) + (under * (1 - alpha));
                const double d = std::abs(want_c - static_cast<double>(a->pixels.rgba[at + k]));
                worst = std::max(worst, d);
                if (d > 2.0) {
                    ++wrong;
                    break;
                }
            }
        }
    }
    MESSAGE("the frame: " << told << " pixels under a shadow, " << wrong
                          << " off by more than two levels, the worst by " << worst);
    CHECK(told > (std::size_t{a->pixels.width} * a->pixels.height) / 5);
    CHECK(wrong <= told / 20000);
}

TEST_CASE("ground: the soft shadows' mask is made once a view and stands while nothing it reads moves, its frames "
          "the frames made every frame") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the shadows are not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    // a degree off the home's view: the ladder snaps a camera within 1 m and 0.05° of it onto the view's own, where a
    // hair's move would leave nothing the mask reads moved
    style::CameraState home = test::home_camera();
    home.bearing += 1.0;
    const style::ThemeBlock day = test::sunny();
    REQUIRE(day.shadow_blur > 0.0);
    // after the frames to settle at rest, four frames apiece: at its camera and time again; at a later time; every
    // tile drawn fading in (its place's fade, which no step of the mask reads); the shadows' colour changed (soft_fs
    // draws the result in it, the mask is the same); their opacity changed (the blur's fade); the camera moved by a
    // hair; the building shadows left out for a frame, then drawn again (no step runs while they are out, so the entry
    // holds the result its key names); the soft shadows left out for a frame (drawn sharp), then again; moving by a
    // hair a frame (not at rest: no snap)
    constexpr int settled = 40;
    constexpr int phases = 9;
    constexpr int frames = settled + (4 * phases);
    struct Run {
        std::vector<std::uint64_t> makes;                // the masks made by the end of each frame
        std::vector<bool> soft;                          // whether it drew its shadows soft
        std::vector<std::uint64_t> hashes;               // its pixels' (gpu::hash_of)
        std::vector<std::array<std::uint32_t, 4>> steps; // the box's, the grid's and the blur's dispatches, the union's
                                                         // draws
        std::optional<test::GroundDraw> last;
    };
    const auto run = [&](bool always) {
        Run out;
        passes::Ground* ground = nullptr;
        const auto each = [&](int f, frame::FrameInputs& in) {
            if (f < settled) return;
            const int phase = (f - settled) / 4;
            in.camera = home;
            in.theme = day;
            if (phase >= 1) in.seconds = 1.0;
            ground->fade_every(phase == 2 ? std::optional<float>(0.5F) : std::nullopt);
            if (phase >= 3) in.theme.shadow = {.r = 40, .g = 30, .b = 90, .a = 255};
            if (phase >= 4) in.theme.shadow_opacity = day.shadow_opacity + 0.05;
            if (phase >= 5) in.camera.longitude += 1e-6;
            if (phase == 6 && (f - settled) % 4 == 0) in.camera.parts = style::camera_part::building_shadows;
            if (phase == 7 && (f - settled) % 4 == 0) in.camera.parts = style::camera_part::shadow_blur;
            if (phase >= 8) {
                in.camera = test::moving(in.camera);
                in.camera.longitude += 1e-6 * ((f - settled) % 4 + 1);
            }
        };
        const auto drawn = [&](int /*f*/, const gpu::Pixels& px, const frame::FrameBuilder& builder) {
            out.makes.push_back(ground->mask_makes());
            out.soft.push_back(ground->drew_soft());
            out.hashes.push_back(gpu::hash_of(px));
            std::array<std::uint32_t, 4> steps{};
            for (const frame::PassCounters& p : builder.counters()) {
                for (const frame::KindCount& k : p.kinds()) {
                    if (k.kind == "shadow box") steps[0] += k.count.dispatches;
                    if (k.kind == "shadow grid") steps[1] += k.count.dispatches;
                    if (k.kind == "shadow blur") steps[2] += k.count.dispatches;
                    if (k.kind == "shadow mask") steps[3] += k.count.draws;
                }
            }
            out.steps.push_back(steps);
        };
        out.last = test::draw_ground(
            *session, home, day, dir, "amsterdam", {}, frames,
            [&](passes::Ground& g) {
                ground = &g;
                g.make_mask_always(always);
                no_walls(g);
            },
            frame::Mode::full, nullptr, {}, {.steps = 0}, false, each, false, drawn);
        return out;
    };
    const Run kept = run(false);
    const Run made = run(true);
    ATLAS_REQUIRE(kept.last.has_value() && made.last.has_value(), "the home was not drawn both ways");
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    REQUIRE(kept.makes.size() == frames);
    REQUIRE(made.makes.size() == frames);
    REQUIRE_MESSAGE(kept.soft[settled - 1], "the frame drew its shadows sharp (the moment does not blur them?)");

    // the same frames both ways, every one
    std::size_t differ = 0;
    int first = -1;
    for (int f = 0; f < frames; ++f) {
        const auto i = static_cast<std::size_t>(f);
        if (kept.soft[i] != made.soft[i] || kept.hashes[i] != made.hashes[i]) {
            ++differ;
            if (first < 0) first = f;
        }
    }
    CHECK_MESSAGE(differ == 0, differ << " frames differ between the kept mask and the mask made every frame, the "
                                         "first "
                                      << first);
    CHECK(kept.last->pixels.rgba == made.last->pixels.rgba);

    // made: a mask each frame that draws them soft
    const auto made_in = [](const Run& r, std::size_t f) { return r.makes[f] - (f > 0 ? r.makes[f - 1] : 0U); };
    for (std::size_t f = 0; f < made.makes.size(); ++f) {
        CHECK_MESSAGE(made_in(made, f) == (made.soft[f] ? 1U : 0U), "frame " << f);
    }
    // kept: once a view, and again where something it reads moved
    const auto over = [&](std::size_t phase, std::size_t from, std::size_t to) {
        const std::size_t start = std::size_t{settled} + (4 * phase);
        return kept.makes[start + to] - kept.makes[start + from - 1];
    };
    MESSAGE("the masks made: " << kept.makes.back() << " of " << frames << " frames where they stand, "
                               << made.makes.back() << " made every frame; " << kept.makes[settled - 1] << " over the "
                               << settled << " frames to settle");
    CHECK(over(0, 0, 3) == 0); // the same camera and time: the last result stands
    CHECK(over(1, 0, 3) == 0); // a later time: nothing the mask reads moves with it
    CHECK(over(2, 0, 3) == 0); // every tile fading in: no step reads a fade
    CHECK(over(3, 0, 3) == 0); // the shadows' colour: soft_fs's, not the mask's
    CHECK(over(4, 0, 3) == 1); // their opacity: made again once, as the frame takes the new moment
    CHECK(over(5, 0, 0) == 1); // the camera moved: made again
    CHECK(over(5, 1, 3) == 0);
    CHECK(over(6, 0, 3) == 0); // the building shadows out for a frame: none drawn, then the last result stands
    CHECK(over(7, 0, 3) == 0); // the soft shadows out for a frame: drawn sharp, then the last result stands
    CHECK(over(8, 0, 3) == 4); // moving: made each frame
    // a frame that keeps its mask dispatches nothing for it and draws no union; one that makes it, its grid and three
    // boxes (and its box where a tile casts)
    constexpr std::array<std::uint32_t, 4> none{};
    for (std::size_t f = 0; f < kept.makes.size(); ++f) {
        const std::array<std::uint32_t, 4>& s = kept.steps[f];
        if (made_in(kept, f) == 0) {
            CHECK_MESSAGE(s == none,
                          "frame " << f << " kept its mask and dispatched " << s[0] << " " << s[1] << " " << s[2]);
        } else {
            CHECK_MESSAGE((s[0] <= 1 && s[1] == 1 && s[2] == 3), "frame " << f);
        }
    }
    // soft from the settled frames on, but in the two frames that leave them out
    const auto out_frame = [&](std::size_t f) {
        return f == std::size_t{settled} + (4 * 6) || f == std::size_t{settled} + (4 * 7);
    };
    for (std::size_t f = settled; f < kept.soft.size(); ++f) CHECK_MESSAGE(kept.soft[f] != out_frame(f), "frame " << f);
}

#endif
