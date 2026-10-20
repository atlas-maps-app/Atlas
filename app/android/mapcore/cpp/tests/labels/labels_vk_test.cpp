// The board's labels drawn by the frame (plan-vector D13, K13): the app's sprites (a LabelSet in the frame's inputs),
// their texels in set 1 through SpriteGlobals, placed by LabelPainter's algorithm at their points' projections and
// drawn in the composite subpass after the composite; on every configuration and route the host's Vulkan has, each
// sprite's texels land on whole pixels at its place, blended over the frame as premultiplied colour, exactly.
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
    style::CameraState camera = standin::board_view(400, 300, 2.0);
    camera.flags = style::camera_flag::rest;
    const auto configs = configurations(device);
    ATLAS_REQUIRE(!configs.empty());
    for (const gpu::Configuration& config : configs) {
        auto output = platform::Offscreen::create(device, {.width = 400, .height = 300}, 2, gpu::Depth::eight);
        ATLAS_REQUIRE(output.has_value(), output.error().text());
        auto renderer = gpu::Renderer::create(device, **output, **builder, config);
        ATLAS_REQUIRE(renderer.has_value(), gpu::name_of(config) << ": " << renderer.error().text());
        for (int f = 0; f < 2; ++f) {
            (*builder)->set_inputs(
                {.camera = camera, .seconds = 1.0 + (0.016 * f), .scene = &world, .labels = set->get()});
            auto report = (*renderer)->draw(0);
            ATLAS_REQUIRE(report.has_value(), gpu::name_of(config) << ": " << report.error().text());
        }
        (*renderer)->drain();
        auto pixels = (*output)->read((*output)->latest());
        ATLAS_REQUIRE(pixels.has_value(), pixels.error().text());
        // The frame the sprites make: the clear colour as the attachment holds it, then each sprite's texels over it.
        std::vector<double> want(std::size_t{400} * 300 * 3);
        for (std::size_t i = 0; i < want.size(); ++i)
            want[i] = std::round(static_cast<double>(clear.at(i % 3)) * 255.0);
        std::size_t texels = 0;
        for (const auto& d : labels->draws()) {
            const auto texels_of = (*set)->texels().subspan(d.source[0]);
            for (std::int32_t y = 0; y < d.target[3]; ++y) {
                for (std::int32_t x = 0; x < d.target[2]; ++x) {
                    const std::int32_t px = d.target[0] + x;
                    const std::int32_t py = d.target[1] + y;
                    if (px < 0 || py < 0 || px >= 400 || py >= 300) continue;
                    const std::uint32_t t =
                        texels_of[(static_cast<std::size_t>(y) * d.source[1]) + static_cast<std::size_t>(x)];
                    const auto fade = static_cast<double>(d.fade[0]);
                    const double a = static_cast<double>(t >> 24U) / 255.0 * fade;
                    const std::size_t p = (static_cast<std::size_t>(py) * 400) + static_cast<std::size_t>(px);
                    for (std::size_t k = 0; k < 3; ++k) {
                        const double src = static_cast<double>((t >> (8U * k)) & 0xFFU) / 255.0 * fade;
                        want[(3 * p) + k] = std::round((src + (want[(3 * p) + k] / 255.0 * (1.0 - a))) * 255.0);
                    }
                    ++texels;
                }
            }
        }
        std::size_t differ = 0;
        double worst = 0.0;
        for (std::size_t p = 0; p < std::size_t{400} * 300; ++p) {
            for (std::size_t k = 0; k < 3; ++k) {
                const double d = std::fabs(static_cast<double>(pixels->rgba[(4 * p) + k]) - want[(3 * p) + k]);
                worst = std::max(worst, d);
                differ += d > 1.0 ? 1U : 0U;
            }
        }
        MESSAGE(std::format("{}: {} labels drawn of 3, {} texels on the frame; the largest difference {} levels",
                            gpu::name_of(config), labels->draws().size(), texels, worst));
        CHECK(labels->draws().size() == 2); // the third's point is off the frame
        CHECK(texels == (31 * 13) + (17 * 9));
        CHECK(differ == 0);
        CHECK(globals.texels() == (*set)->texels().size());
        renderer->reset();
    }
    const auto messages = session->take_messages();
    for (const auto& m : messages) MESSAGE("validation: " << m);
    CHECK(messages.empty());
}
#endif
