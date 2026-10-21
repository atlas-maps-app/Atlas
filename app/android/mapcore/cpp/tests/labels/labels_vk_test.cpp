// The board's labels drawn by the frame (plan-vector D13, K13): the app's sprites (a LabelSet in the frame's inputs),
// their texels in set 1 through SpriteGlobals, placed by LabelPainter's algorithm at their points' projections and
// drawn in the composite subpass after the composite; on every configuration and route the host's Vulkan has, each
// sprite's texels land on whole pixels at its place, blended over the frame as premultiplied colour, exactly; and with
// the camera a quarter nearer, the same labels where they were on the map, a quarter larger, each pixel the bilinear
// mix of the texels about its centre (within two levels).
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
/// premultiplied, 8 bits a blend; at its own size its texels on whole pixels, scaled each pixel of its quad (a pixel
/// around its rectangle) the bilinear mix of the four texels about the pixel's centre, clear beyond the sprite. And the
/// pixels the sprites covered.
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
        const double fringe = k != 1.0 ? 1.0 : 0.0;
        const auto x0 = static_cast<std::int32_t>(std::floor(tx - fringe));
        const auto y0 = static_cast<std::int32_t>(std::floor(ty - fringe));
        const auto x1 = static_cast<std::int32_t>(std::ceil(tx + static_cast<double>(d.target[2]) + fringe));
        const auto y1 = static_cast<std::int32_t>(std::ceil(ty + static_cast<double>(d.target[3]) + fringe));
        for (std::int32_t py = std::max(0, y0); py < std::min(frame_height, y1); ++py) {
            for (std::int32_t px = std::max(0, x0); px < std::min(frame_width, x1); ++px) {
                std::array<double, 4> c{};
                if (k == 1.0) {
                    c = texel(px - static_cast<std::int32_t>(tx), py - static_cast<std::int32_t>(ty));
                } else {
                    const double ux = ((static_cast<double>(px) + 0.5 - tx) / k) - 0.5;
                    const double uy = ((static_cast<double>(py) + 0.5 - ty) / k) - 0.5;
                    const double bx = std::floor(ux);
                    const double by = std::floor(uy);
                    const double fx = ux - bx;
                    const double fy = uy - by;
                    const auto ix = static_cast<std::int32_t>(bx);
                    const auto iy = static_cast<std::int32_t>(by);
                    const auto a = texel(ix, iy);
                    const auto b = texel(ix + 1, iy);
                    const auto cc = texel(ix, iy + 1);
                    const auto dd = texel(ix + 1, iy + 1);
                    for (std::size_t q = 0; q < 4; ++q) {
                        const double top = a[q] + ((b[q] - a[q]) * fx);
                        const double bottom = cc[q] + ((dd[q] - cc[q]) * fx);
                        c[q] = top + ((bottom - top) * fy);
                    }
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

/// A sprite of `width` x `height` px whose texels are premultiplied colours: opaque, translucent and clear ones.
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
    s.width = width;
    s.height = height;
    s.anchor_x = static_cast<std::int32_t>(width / 2);
    s.anchor_y = static_cast<std::int32_t>((height * 3) / 4);
    for (std::uint32_t y = 0; y < height; ++y) {
        for (std::uint32_t x = 0; x < width; ++x) {
            const std::uint32_t a = (x + y + id) % 3 == 0 ? 0U : ((x * 37 + y * 11 + id * 5) % 2 == 0 ? 255U : 128U);
            const std::uint32_t r = (a * ((x * 7) % 256)) / 255;
            const std::uint32_t g = (a * ((y * 29) % 256)) / 255;
            const std::uint32_t b = (a * ((id * 83) % 256)) / 255;
            s.pixels.push_back(r | (g << 8U) | (b << 16U) | (a << 24U));
        }
    }
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
    const auto set = labels::LabelSet::make(sprites, 1);
    ATLAS_REQUIRE(set.has_value(), labels::describe(set.error()));
    style::CameraState camera = standin::board_view(frame_width, frame_height, 2.0);
    camera.flags = style::camera_flag::rest;
    // and a quarter nearer: the same labels, where they were on the map, a quarter larger
    style::CameraState nearer = camera;
    nearer.distance = camera.distance / 1.25;
    const auto configs = configurations(device);
    ATLAS_REQUIRE(!configs.empty());
    for (const gpu::Configuration& config : configs) {
        auto output =
            platform::Offscreen::create(device, {.width = frame_width, .height = frame_height}, 2, gpu::Depth::eight);
        ATLAS_REQUIRE(output.has_value(), output.error().text());
        auto renderer = gpu::Renderer::create(device, **output, **builder, config);
        ATLAS_REQUIRE(renderer.has_value(), gpu::name_of(config) << ": " << renderer.error().text());
        std::array<float, 2> stood{}; // the second label's x at its own size, then nearer
        for (const bool near : {false, true}) {
            for (int f = 0; f < 2; ++f) {
                (*builder)->set_inputs({.camera = near ? nearer : camera,
                                        .seconds = (near ? 3.0 : 1.0) + (0.016 * f),
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
                    differ += d > (near ? 2.0 : 1.0) ? 1U : 0U;
                }
            }
            MESSAGE(std::format("{} {}: {} labels drawn of 3, {} pixels covered; the largest difference {} levels",
                                gpu::name_of(config), near ? "a quarter nearer" : "at its own size",
                                labels->draws().size(), want.covered, worst));
            CHECK(labels->draws().size() == 2); // the third's point is off the frame
            CHECK(differ == 0);
            CHECK(globals.texels() == (*set)->texels().size());
            ATLAS_REQUIRE(labels->drawn().size() == 2);
            for (const auto& d : labels->draws()) CHECK(d.fade[1] == (near ? 1.25F : 1.0F));
            stood.at(near ? 1 : 0) = labels->drawn()[1].stand.x;
            if (!near) {
                CHECK(want.covered <= std::size_t{(31 * 13) + (17 * 9)});
            } else {
                // kept at its point and spot: the point spread a quarter from the frame's centre with the map
                const float centre = static_cast<float>(frame_width) / 2.0F;
                CHECK(stood[1] - centre == doctest::Approx((stood[0] - centre) * 1.25F).epsilon(0.01));
            }
        }
        renderer->reset();
    }
    const auto messages = session->take_messages();
    for (const auto& m : messages) MESSAGE("validation: " << m);
    CHECK(messages.empty());
}
#endif
