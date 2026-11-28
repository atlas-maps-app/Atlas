// Frames drawn back to back while the camera moves (ENGINE.md's gain 17: what a frame's early work writes, its frame
// slot's own), on the host's Vulkan under the validation layer with synchronization validation (tests/gpu/
// gpu_fixture.hpp): every pass of the registry over the bundled packs in full mode, the noise fields bound and the
// home's two frosted pills over the map, so each frame runs the soft shadows, the ripple, the buildings' kept lists,
// the other passes' decide phases and the frosted backdrops, two frames in flight. The layer stays silent, the soft
// shadows' sets alternate between the slots, and the last frame drawn in motion is the frame drawn alone at its camera
// (once every other frame has finished), pixel for pixel. The same frames resized with a frame in flight (the phone
// turned): to another size with the same formats the frame stays, no shader module and no pipeline made, its targets
// alone made again; to another format (10 bits) the frame is made again; the layer silent throughout.
#if !defined(__ANDROID__)

#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "fx/FieldCache.hpp"
#include "fx/Fields.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Offscreen.hpp"
#include "gpu/PipelineCache.hpp"
#include "passes/Ground.hpp"
#include "scene/Cover.hpp"
#include "scene/World.hpp"
#include "tests/ground/ground_draw.hpp"
#include "tests/ground/ground_support.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <filesystem>
#include <format>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

using namespace atlas;

namespace {

/// The home's two frosted pills (VK bench's chrome: tests/gpu/standin_vk_test.cpp's).
style::CameraState with_pills(style::CameraState c) {
    const auto w = static_cast<float>(c.width_dp);
    const auto h = static_cast<float>(c.height_dp);
    c.keep_out_count = 2;
    c.keep_out[0] = style::KeepOut{.left = 16.0F,
                                   .top = 40.0F,
                                   .right = w - 16.0F,
                                   .bottom = 92.0F,
                                   .radius = 26.0F,
                                   .blur16 = 20 * 16,
                                   .saturation = 180,
                                   .flags = 1};
    c.keep_out[1] = style::KeepOut{.left = 16.0F,
                                   .top = h - 100.0F,
                                   .right = w - 16.0F,
                                   .bottom = h - 48.0F,
                                   .radius = 26.0F,
                                   .blur16 = 20 * 16,
                                   .saturation = 180,
                                   .flags = 1};
    return c;
}

/// `c` on its window turned a quarter (the phone on its side): its sizes swapped, its principal point where it was
/// across and as high up the window.
style::CameraState turned(style::CameraState c) {
    const double up = c.principal_y / c.height_dp;
    std::swap(c.width_px, c.height_px);
    std::swap(c.width_dp, c.height_dp);
    c.principal_x = c.width_dp / 2.0;
    c.principal_y = up * c.height_dp;
    return c;
}

/// Counts what a frame makes and lets go of (gpu/Pipelines.hpp's announcements), from every thread its passes
/// prepare on.
class Counting final : public gpu::PipelineWatch {
public:
    void before(const gpu::PipelineLabel& label) override {
        (void)label;
        ++pipelines;
    }
    void before_module(std::string_view module) override {
        (void)module;
        ++modules;
    }
    void before_release(const gpu::Release& release) override {
        const std::scoped_lock lock(mutex_);
        released_.emplace_back(release.kind);
    }
    void after() override {}
    /// How many things of `kind` went ("frame", "targets").
    [[nodiscard]] std::size_t released(std::string_view kind) const {
        const std::scoped_lock lock(mutex_);
        return static_cast<std::size_t>(std::ranges::count(released_, kind));
    }

    std::atomic<std::size_t> modules{0};
    std::atomic<std::size_t> pipelines{0};

private:
    mutable std::mutex mutex_;
    std::vector<std::string> released_;
};

} // namespace

TEST_CASE("frames back to back while the camera moves: each frame slot's own buffers, the validation layer silent, the "
          "last frame the frame drawn alone at its camera") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the map is not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    auto made = fx::FieldCache::create(device, {.threads = 3});
    ATLAS_REQUIRE(made.has_value(), made.error().text());
    const std::shared_ptr<fx::FieldCache> fields(std::move(*made));
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes();
    passes::Ground* ground = test::ground_of(passes);
    auto builder = frame::FrameBuilder::create(device, *cache, std::move(passes));
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    double now = 0;
    auto world = scene::World::create(device, (*builder)->passes(), {.workers = 3, .clock = [&now] { return now; }});
    ATLAS_REQUIRE(world.has_value(), world.error().text());
    ATLAS_REQUIRE((*world)->add_globals(fields).has_value(), "the fields' provider was not added");
    for (const char* name : {"amsterdam", "grachten"}) {
        const std::filesystem::path path = std::filesystem::path(dir) / (std::string(name) + ".atlas");
        // set, the folder must hold the pack (CI sets it only where it does): one missing fails, not skips
        ATLAS_REQUIRE(std::filesystem::exists(path), "ATLAS_TILES_DIR has no " << path.string());
        auto added = (*world)->store().add({.id = name, .kind = tiles::PackKind::bundled, .path = path.string()});
        ATLAS_REQUIRE(added.has_value(), tiles::describe(added.error()));
    }
    (*world)->set_active("amsterdam");
    const style::CameraState home = with_pills(test::home_camera());
    const style::ThemeBlock day = test::sunny();
    auto output = gpu::Offscreen::create(device, {.width = home.width_px, .height = home.height_px}, 2);
    ATLAS_REQUIRE(output.has_value(), output.error().text());
    const gpu::Configuration config{.config = gpu::FrameConfig::c,
                                    .route = gpu::Route::renderpass,
                                    .form = gpu::FrameForm::composite,
                                    .target = gpu::CompositeTarget::resolve};
    auto renderer = gpu::Renderer::create(device, **output, **builder, config);
    ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());

    // A frame at `camera`, the moment fixed (no animation runs on its seconds), the world's clock 50 ms on.
    const auto draw = [&](const style::CameraState& camera) {
        now += 0.05;
        (*builder)->set_inputs(
            {.camera = camera, .theme = day, .seconds = 0, .mode = frame::Mode::full, .scene = world->get()});
        auto report = (*renderer)->draw(0);
        if (!report) FAIL("a frame failed: " << report.error().text());
    };
    // Frames at `camera`, each alone (the one before it finished), until two in a row drew every tile the cover wants,
    // built and faded in with their sun bits, over the bound fields; whether they did.
    const auto settle = [&](const style::CameraState& camera) {
        int complete = 0;
        for (int n = 0; n < 120 && complete < 2; ++n) {
            draw(camera);
            (*renderer)->drain();
            const scene::Cover& cover = *(*world)->cover();
            const bool whole = cover.missing() == 0 && !cover.tiles().empty() && !(*world)->animating() &&
                               fields->bound_for(fx::size_of(camera.width_px, camera.height_px, camera.density));
            complete = whole ? complete + 1 : 0;
            (*world)->residency().wait_idle();
            fields->worker().wait_idle();
            if ((*world)->sun() != nullptr) (*world)->sun()->wait_idle();
        }
        return complete == 2;
    };
    // The camera of motion's frame f (of 12): turning a tenth of a degree and sliding half a metre north a frame. Its
    // last view settled first, then the home, so every tile the motion draws is resident and faded in before it starts.
    constexpr int motion = 12;
    const auto moved_to = [&home](int f) {
        style::CameraState c = test::moving(home);
        c.bearing = home.bearing + (0.1 * f);
        c.latitude = home.latitude + (0.5 * f / 110540.0);
        return c;
    };
    const style::CameraState last = moved_to(motion);
    REQUIRE(settle(last));
    REQUIRE(settle(home));

    // The frames of the motion back to back (each waits only for its slot's last frame): every frame's soft shadows and
    // ripple drawn, into alternate slots, with every tile drawn whole.
    std::vector<std::uint32_t> slots;
    for (int f = 1; f <= motion; ++f) {
        draw(moved_to(f));
        CHECK(ground->drew_soft());
        CHECK(ground->drew_ripple());
        REQUIRE(ground->soft_shadows() != nullptr);
        slots.push_back(ground->soft_shadows()->last);
        CHECK((*world)->cover()->missing() == 0);
        CHECK_FALSE((*world)->animating());
    }
    (*renderer)->drain();
    for (std::size_t i = 1; i < slots.size(); ++i) CHECK(slots[i] != slots[i - 1]);
    auto moved = (*output)->read((*output)->latest());
    ATLAS_REQUIRE(moved.has_value(), moved.error().text());

    // The last camera drawn alone: the same frame, pixel for pixel.
    REQUIRE(settle(last));
    auto alone = (*output)->read((*output)->latest());
    ATLAS_REQUIRE(alone.has_value(), alone.error().text());
    REQUIRE(moved->width == alone->width);
    REQUIRE(moved->height == alone->height);
    std::size_t differ = 0;
    for (std::size_t i = 0; i < moved->rgba.size(); i += 4) {
        if (moved->rgba[i] != alone->rgba[i] || moved->rgba[i + 1] != alone->rgba[i + 1] ||
            moved->rgba[i + 2] != alone->rgba[i + 2]) {
            ++differ;
        }
    }
    MESSAGE("the last frame in motion against the same camera drawn alone: " << differ << " pixels differ");
    CHECK(differ == 0);
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    renderer->reset();
}

TEST_CASE("a resize with a frame in flight: to another size with the same formats the frame stays, no shader module "
          "and no pipeline made, its targets alone made again; to another format the frame is made again; the "
          "validation layer silent") {
    const std::string dir = test::environment("ATLAS_TILES_DIR");
    if (dir.empty()) {
        MESSAGE("ATLAS_TILES_DIR is not set: the map is not drawn over the bundled packs here");
        return;
    }
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    auto made = fx::FieldCache::create(device, {.threads = 3});
    ATLAS_REQUIRE(made.has_value(), made.error().text());
    const std::shared_ptr<fx::FieldCache> fields(std::move(*made));
    Counting watch;
    std::vector<std::unique_ptr<frame::Pass>> passes = frame::make_passes();
    passes::Ground* ground = test::ground_of(passes);
    auto builder = frame::FrameBuilder::create(device, *cache, std::move(passes), {}, &watch);
    ATLAS_REQUIRE(builder.has_value(), builder.error().text());
    double now = 0;
    auto world = scene::World::create(device, (*builder)->passes(), {.workers = 3, .clock = [&now] { return now; }});
    ATLAS_REQUIRE(world.has_value(), world.error().text());
    ATLAS_REQUIRE((*world)->add_globals(fields).has_value(), "the fields' provider was not added");
    for (const char* name : {"amsterdam", "grachten"}) {
        const std::filesystem::path path = std::filesystem::path(dir) / (std::string(name) + ".atlas");
        ATLAS_REQUIRE(std::filesystem::exists(path), "ATLAS_TILES_DIR has no " << path.string());
        auto added = (*world)->store().add({.id = name, .kind = tiles::PackKind::bundled, .path = path.string()});
        ATLAS_REQUIRE(added.has_value(), tiles::describe(added.error()));
    }
    (*world)->set_active("amsterdam");
    const style::CameraState home = with_pills(test::home_camera());
    const style::CameraState side = with_pills(turned(test::home_camera()));
    const style::ThemeBlock day = test::sunny();
    // The window's images upright, on their side, and on their side at the deepest colour.
    auto upright = gpu::Offscreen::create(device, {.width = home.width_px, .height = home.height_px}, 2);
    ATLAS_REQUIRE(upright.has_value(), upright.error().text());
    auto on_side = gpu::Offscreen::create(device, {.width = side.width_px, .height = side.height_px}, 2);
    ATLAS_REQUIRE(on_side.has_value(), on_side.error().text());
    const bool ten_bit = device.facts().deepest.ten_bit();
    auto deep = gpu::Offscreen::create(device, {.width = side.width_px, .height = side.height_px}, 2,
                                       ten_bit ? gpu::Depth::deepest : gpu::default_depth);
    ATLAS_REQUIRE(deep.has_value(), deep.error().text());
    const gpu::Configuration config{.config = gpu::FrameConfig::c,
                                    .route = gpu::Route::renderpass,
                                    .form = gpu::FrameForm::composite,
                                    .target = gpu::CompositeTarget::resolve};
    auto renderer = gpu::Renderer::create(device, **upright, **builder, config, &watch);
    ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());

    const auto draw = [&](const style::CameraState& camera) {
        now += 0.05;
        (*builder)->set_inputs(
            {.camera = camera, .theme = day, .seconds = 0, .mode = frame::Mode::full, .scene = world->get()});
        auto report = (*renderer)->draw(0);
        if (!report) FAIL("a frame failed: " << report.error().text());
    };
    // Frames at `camera`, each alone, until two in a row drew every tile the cover wants over the bound fields.
    const auto settle = [&](const style::CameraState& camera) {
        int complete = 0;
        for (int n = 0; n < 120 && complete < 2; ++n) {
            draw(camera);
            (*renderer)->drain();
            const scene::Cover& cover = *(*world)->cover();
            const bool whole = cover.missing() == 0 && !cover.tiles().empty() && !(*world)->animating() &&
                               fields->bound_for(fx::size_of(camera.width_px, camera.height_px, camera.density));
            complete = whole ? complete + 1 : 0;
            (*world)->residency().wait_idle();
            fields->worker().wait_idle();
            if ((*world)->sun() != nullptr) (*world)->sun()->wait_idle();
        }
        return complete == 2;
    };
    REQUIRE(settle(home));
    const std::size_t modules = watch.modules.load();
    const std::size_t pipelines = watch.pipelines.load();
    CHECK(modules > 0);
    CHECK(pipelines > 0);
    CHECK(watch.released("frame") == 0);
    CHECK(watch.released("targets") == 0);

    // On its side with the home's frame in flight: the frame stays, its targets made again, and the frames at the new
    // size back to back draw the soft shadows and the ripple into them.
    draw(home);
    ATLAS_REQUIRE((*renderer)->retarget(**on_side).has_value(), "the resize failed");
    CHECK(watch.modules.load() == modules);
    CHECK(watch.pipelines.load() == pipelines);
    CHECK(watch.released("frame") == 0);
    CHECK(watch.released("targets") == 1);
    for (int f = 0; f < 3; ++f) {
        draw(test::moving(side));
        CHECK(ground->drew_soft());
        CHECK(ground->drew_ripple());
    }
    REQUIRE(settle(side));
    CHECK(watch.modules.load() == modules); // none in the frames at the new size either
    CHECK(watch.pipelines.load() == pipelines);
    CHECK(watch.released("targets") == 1);
    MESSAGE(std::format("resized from {}x{} to {}x{} with a frame in flight: {} modules and {} pipelines made before "
                        "it, none by it",
                        home.width_px, home.height_px, side.width_px, side.height_px, modules, pipelines));

    // At the deepest colour, again with a frame in flight: another format, the frame made again with its pipelines.
    if (ten_bit) {
        draw(side);
        ATLAS_REQUIRE((*renderer)->retarget(**deep).has_value(), "the move to 10 bits failed");
        CHECK(watch.modules.load() > modules);
        CHECK(watch.pipelines.load() > pipelines);
        CHECK(watch.released("frame") == 1);
        CHECK(watch.released("targets") == 1);
        for (int f = 0; f < 2; ++f) draw(side);
        (*renderer)->drain();
    } else {
        MESSAGE("no 10 bits here: the move to another format is not drawn");
    }
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
    renderer->reset();
}

#endif
