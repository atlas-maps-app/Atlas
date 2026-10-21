// The board's labels drawn by the frame (plan-vector D13, K13): the app's sprites (a LabelSet in the frame's inputs),
// their texels in set 1 through SpriteGlobals, placed by LabelPainter's algorithm at their points' projections and
// drawn in the composite subpass after the composite; on every configuration and route the host's Vulkan has, each
// sprite's texels land on whole pixels at its place, blended over the frame as premultiplied colour, exactly; with the
// camera a quarter nearer, the same labels where they were on the map, a quarter larger, drawn from their images at the
// band's top; and a fifth farther, a fifth smaller, from their own images: each pixel the area its square covers of the
// texels (within two levels).
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "gpu/PipelineCache.hpp"
#include "labels/Sprites.hpp"
#include "passes/GroundText.hpp"
#include "passes/Labels.hpp"
#include "standin/View.hpp"
#include "tests/base/require.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <cmath>
#include <format>
#include <memory>
#include <string>
#include <vector>

#if !defined(__ANDROID__)
#include "platform/host/Offscreen.hpp"

using namespace atlas;

namespace {

/// A world without tiles: the frame drawn and the text globals.
class Stub final : public frame::FrameScene {
public:
    Stub(view::GroundFrame frame, frame::GlobalProvider* globals) noexcept : frame_(frame), globals_{globals} {}
    [[nodiscard]] const view::Ladder* ladder(const style::CameraState& camera) override {
        (void)camera;
        return nullptr;
    }
    void update(const frame::FrameState& state) override { (void)state; }
    [[nodiscard]] const scene::Cover* cover() const noexcept override { return nullptr; }
    [[nodiscard]] const scene::ItemTable* items() const noexcept override { return nullptr; }
    [[nodiscard]] scene::Heaps* heaps() noexcept override { return nullptr; }
    [[nodiscard]] std::optional<view::GroundFrame> ground_frame() const noexcept override { return frame_; }
    [[nodiscard]] std::span<frame::GlobalProvider* const> globals() noexcept override { return globals_; }

private:
    view::GroundFrame frame_;
    std::array<frame::GlobalProvider*, 1> globals_;
};

/// The configurations the host's device draws the map's frame in, each route (composite_vk_test's list).
std::vector<gpu::Configuration> configurations(const gpu::Device& device) {
    std::vector<gpu::Configuration> out;
    const bool dynamic = device.enabled().dynamic_rendering && device.enabled().local_read;
    for (const gpu::FrameConfig c :
         {gpu::FrameConfig::a, gpu::FrameConfig::b, gpu::FrameConfig::c, gpu::FrameConfig::x1}) {
        if (!device.facts().supports(c)) continue;
        const bool one = gpu::Configuration{.config = c}.samples() == vk::SampleCountFlagBits::e1;
        for (const gpu::Route route : {gpu::Route::renderpass, gpu::Route::dynamic}) {
            if (route == gpu::Route::dynamic && !dynamic) continue;
            out.push_back({.config = c,
                           .route = route,
                           .form = gpu::FrameForm::composite,
                           .target = one ? gpu::CompositeTarget::direct : gpu::CompositeTarget::resolve});
        }
    }
    return out;
}

constexpr std::array<float, 4> clear{0.25F, 0.5F, 0.75F, 1.0F};
constexpr std::int32_t frame_width = 400;
constexpr std::int32_t frame_height = 300;

/// What the drawn sprites make of the frame: the clear colour as the attachment holds it, then each sprite over it,
/// premultiplied, 8 bits a blend; at its own size its texels on whole pixels, scaled each pixel of its quad (and a
/// fringe around it) the area its square covers of the image's texels (a texel's width at least), clear beyond the
/// image (shaders/text/sprite.slang's sprite_area, in double). And the pixels the sprites covered.
struct Expected {
    std::vector<double> rgb;
    std::size_t covered = 0;
};

[[nodiscard]] Expected expect(std::span<const layouts::text_sprite::SpriteDraw> draws,
                              std::span<const std::uint32_t> texels) {
    Expected e;
    e.rgb.resize(std::size_t{frame_width} * frame_height * 3);
    for (std::size_t i = 0; i < e.rgb.size(); ++i) e.rgb[i] = std::round(static_cast<double>(clear.at(i % 3)) * 255.0);
    for (const auto& d : draws) {
        const auto sprite = texels.subspan(d.source[0]);
        const auto sw = static_cast<std::int32_t>(d.source[1]);
        const auto sh = static_cast<std::int32_t>(d.source[2]);
        const auto texel = [&](std::int32_t x, std::int32_t y) {
            std::array<double, 4> c{};
            if (x < 0 || y < 0 || x >= sw || y >= sh) return c;
            const std::uint32_t t = sprite[(static_cast<std::size_t>(y) * d.source[1]) + static_cast<std::size_t>(x)];
            for (std::size_t k = 0; k < 4; ++k) c[k] = static_cast<double>((t >> (8U * k)) & 0xFFU) / 255.0;
            return c;
        };
        const auto k = static_cast<double>(d.fade[1]);
        const auto tx = static_cast<double>(d.target[0]);
        const auto ty = static_cast<double>(d.target[1]);
        const double fringe = k != 1.0 ? std::ceil(std::max(1.0, k) * 0.5) : 0.0;
        const auto x0 = static_cast<std::int32_t>(std::floor(tx - fringe));
        const auto y0 = static_cast<std::int32_t>(std::floor(ty - fringe));
        const auto x1 = static_cast<std::int32_t>(std::ceil(tx + static_cast<double>(d.target[2]) + fringe));
        const auto y1 = static_cast<std::int32_t>(std::ceil(ty + static_cast<double>(d.target[3]) + fringe));
        const double w = std::clamp(1.0 / k, 1.0, 3.0);
        for (std::int32_t py = std::max(0, y0); py < std::min(frame_height, y1); ++py) {
            for (std::int32_t px = std::max(0, x0); px < std::min(frame_width, x1); ++px) {
                std::array<double, 4> c{};
                if (k == 1.0) {
                    c = texel(px - static_cast<std::int32_t>(tx), py - static_cast<std::int32_t>(ty));
                } else {
                    const double ux = (static_cast<double>(px) + 0.5 - tx) / k;
                    const double uy = (static_cast<double>(py) + 0.5 - ty) / k;
                    const double lx = ux - (0.5 * w);
                    const double ly = uy - (0.5 * w);
                    const auto fx = static_cast<std::int32_t>(std::floor(lx));
                    const auto fy = static_cast<std::int32_t>(std::floor(ly));
                    for (std::int32_t j = 0; j < 4; ++j) {
                        const double wy = std::min(ly + w, static_cast<double>(fy + j + 1)) -
                                          std::max(ly, static_cast<double>(fy + j));
                        if (wy <= 0.0) break;
                        for (std::int32_t i = 0; i < 4; ++i) {
                            const double wx = std::min(lx + w, static_cast<double>(fx + i + 1)) -
                                              std::max(lx, static_cast<double>(fx + i));
                            if (wx <= 0.0) break;
                            const auto t = texel(fx + i, fy + j);
                            for (std::size_t q = 0; q < 4; ++q) c[q] += t[q] * wx * wy;
                        }
                    }
                    for (double& v : c) v /= w * w;
                }
                const auto fade = static_cast<double>(d.fade[0]);
                const double alpha = c[3] * fade;
                const std::size_t p = (static_cast<std::size_t>(py) * frame_width) + static_cast<std::size_t>(px);
                for (std::size_t q = 0; q < 3; ++q) {
                    e.rgb[(3 * p) + q] =
                        std::round(((c[q] * fade) + (e.rgb[(3 * p) + q] / 255.0 * (1.0 - alpha))) * 255.0);
                }
                if (c[3] > 0.0) ++e.covered;
            }
        }
    }
    return e;
}

/// An image of `width` x `height` px whose texels are premultiplied colours: opaque, translucent and clear ones.
labels::SpriteImage image(std::uint32_t id, std::uint32_t width, std::uint32_t height) {
    labels::SpriteImage m{.width = width,
                          .height = height,
                          .anchor_x = static_cast<std::int32_t>(width / 2),
                          .anchor_y = static_cast<std::int32_t>((height * 3) / 4)};
    for (std::uint32_t y = 0; y < height; ++y) {
        for (std::uint32_t x = 0; x < width; ++x) {
            const std::uint32_t a = (x + y + id) % 3 == 0 ? 0U : ((x * 37 + y * 11 + id * 5) % 2 == 0 ? 255U : 128U);
            const std::uint32_t r = (a * ((x * 7) % 256)) / 255;
            const std::uint32_t g = (a * ((y * 29) % 256)) / 255;
            const std::uint32_t b = (a * ((id * 83) % 256)) / 255;
            m.pixels.push_back(r | (g << 8U) | (b << 16U) | (a << 24U));
        }
    }
    return m;
}

/// A label of `width` x `height` px, and its image at the band's top (1.5 times as large).
labels::LabelSprite sprite(std::uint32_t id, std::uint32_t width, std::uint32_t height, double lat, double lon,
                           std::int32_t priority) {
    labels::LabelSprite s;
    s.id = id;
    s.priority = priority;
    const float pad = 8.0F;
    s.measures = {.half = (static_cast<float>(width) / 2.0F) + pad,
                  .pad = pad,
                  .text_height = static_cast<float>(height),
                  .baseline = -4.0F};
    s.points = {{lat, lon}};
    s.own = image(id, width, height);
    s.large = image(id + 10, ((width * 3) + 1) / 2, ((height * 3) + 1) / 2);
    return s;
}

} // namespace

TEST_CASE("the board's labels drawn: each sprite's texels land whole at its place, on every configuration") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const std::array<std::string_view, 1> composite{"composite"};
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes(composite);
    auto owned = std::make_unique<passes::Labels>();
    const passes::Labels* labels = owned.get();
    passes.push_back(std::move(owned));
    auto builder = frame::FrameBuilder::create(device, *cache, std::move(passes), {.clear = clear, .steps = 0});
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    passes::SpriteGlobals globals(device);
    const double lat0 = standin::origin_latitude;
    const double lon0 = standin::origin_longitude;
    const view::GroundFrame ground{
        .lat0 = lat0, .lon0 = lon0, .kx = 111320.0 * pymath::cos(pymath::radians(lat0)), .kz = 110540.0};
    Stub world(ground, &globals);
    // Three labels about the view's target, apart; the last's place is off the frame.
    std::vector<labels::LabelSprite> sprites;
    sprites.push_back(sprite(1, 31, 13, lat0, lon0, 5));
    sprites.push_back(sprite(2, 17, 9, lat0 + 0.0015, lon0 - 0.002, 3));
    sprites.push_back(sprite(3, 23, 11, lat0 + 0.4, lon0, 1));
    const auto set = labels::LabelSet::make(sprites, 1, 0.0F, 1.5F);
    ATLAS_REQUIRE(set.has_value(), labels::describe(set.error()));
    style::CameraState camera = standin::board_view(frame_width, frame_height, 2.0);
    camera.flags = style::camera_flag::rest;
    // a quarter nearer: the same labels, where they were on the map, a quarter larger, from their images at the band's
    // top; a fifth farther: a fifth smaller, from their own images
    struct Case {
        const char* name;
        double scale;
    };
    const std::array<Case, 3> cases{Case{.name = "at its own size", .scale = 1.0},
                                    Case{.name = "a quarter nearer", .scale = 1.25},
                                    Case{.name = "a fifth farther", .scale = 0.8}};
    const auto configs = configurations(device);
    ATLAS_REQUIRE(!configs.empty());
    for (const gpu::Configuration& config : configs) {
        auto output =
            platform::Offscreen::create(device, {.width = frame_width, .height = frame_height}, 2, gpu::Depth::eight);
        ATLAS_REQUIRE(output.has_value(), output.error().text());
        auto renderer = gpu::Renderer::create(device, **output, **builder, config);
        ATLAS_REQUIRE(renderer.has_value(), gpu::name_of(config) << ": " << renderer.error().text());
        float stood = 0.0F; // the second label's x at its own size
        for (std::size_t n = 0; n < cases.size(); ++n) {
            const Case& c = cases.at(n);
            style::CameraState at = camera;
            at.distance = camera.distance / c.scale;
            for (int f = 0; f < 2; ++f) {
                (*builder)->set_inputs({.camera = at,
                                        .seconds = (1.0 + (2.0 * static_cast<double>(n))) + (0.016 * f),
                                        .scene = &world,
                                        .labels = set->get()});
                auto report = (*renderer)->draw(0);
                ATLAS_REQUIRE(report.has_value(), gpu::name_of(config) << ": " << report.error().text());
            }
            (*renderer)->drain();
            auto pixels = (*output)->read((*output)->latest());
            ATLAS_REQUIRE(pixels.has_value(), pixels.error().text());
            const Expected want = expect(labels->draws(), (*set)->texels());
            std::size_t differ = 0;
            double worst = 0.0;
            for (std::size_t p = 0; p < std::size_t{frame_width} * frame_height; ++p) {
                for (std::size_t k = 0; k < 3; ++k) {
                    const double d = std::fabs(static_cast<double>(pixels->rgba[(4 * p) + k]) - want.rgb[(3 * p) + k]);
                    worst = std::max(worst, d);
                    differ += d > (c.scale != 1.0 ? 2.0 : 1.0) ? 1U : 0U;
                }
            }
            MESSAGE(std::format("{} {}: {} labels drawn of 3, {} pixels covered; the largest difference {} levels",
                                gpu::name_of(config), c.name, labels->draws().size(), want.covered, worst));
            CHECK(labels->draws().size() == 2); // the third's point is off the frame
            CHECK(differ == 0);
            CHECK(globals.texels() == (*set)->texels().size());
            ATLAS_REQUIRE(labels->drawn().size() == 2);
            for (std::size_t i = 0; i < 2; ++i) {
                const auto& d = labels->draws()[i];
                const labels::LabelSprite& s = sprites.at(labels->drawn()[i].candidate);
                const bool large = c.scale > 1.0;
                CHECK(d.source[1] == (large ? s.large.width : s.own.width));
                CHECK(d.source[0] == (*set)->offsets()[labels->drawn()[i].candidate].at(large ? 1 : 0));
                CHECK(d.fade[1] == doctest::Approx(large ? c.scale / 1.5 : c.scale).epsilon(1e-6));
            }
            if (c.scale == 1.0) {
                stood = labels->drawn()[1].stand.x;
                CHECK(want.covered <= std::size_t{(31 * 13) + (17 * 9)});
            } else {
                // kept at its point and spot: the point spread from the frame's centre with the map
                const float centre = static_cast<float>(frame_width) / 2.0F;
                CHECK(labels->drawn()[1].stand.x - centre ==
                      doctest::Approx((stood - centre) * static_cast<float>(c.scale)).epsilon(0.01));
            }
        }
        renderer->reset();
    }
    const auto messages = session->take_messages();
    for (const auto& m : messages) MESSAGE("validation: " << m);
    CHECK(messages.empty());
}
#endif
