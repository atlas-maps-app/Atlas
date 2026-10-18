// The map's frame through the phone's frame path on the host (the device survey of 18 October: on about 95 % of phones
// VK_EXT_swapchain_maintenance1's feature is off, so D5.2's fallback is the main path): the frame builder drawn into a
// swapchain on a headless surface, presented, remade at another size from the old one (retired by the call, then let go
// of) and retargeted, with the swapchain maintenance extensions hidden (the main case) and as the device has them,
// under the validation layer, silent. Where the host's Vulkan cannot present it skips with the reason, as
// tests/gpu/swapchain_vk_test.cpp does (macOS's emulator lavapipe asserts in Mesa's headless WSI; the Linux runners
// run it).
#include "frame/FrameBuilder.hpp"
#include "frame/PassRegistry.hpp"
#include "gpu/Frames.hpp"
#include "gpu/PipelineCache.hpp"
#include "gpu/Swapchain.hpp"
#include "standin/View.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <doctest/doctest.h>

#include <memory>
#include <string>
#include <vector>

using namespace atlas;

namespace {

/// Draws the frame builder through a swapchain on a headless surface, `hide` hidden from the instance and its devices.
void present_frames(const std::vector<std::string>& hide, bool maintenance_expected_off) {
    test::Session s;
    s.instance = test::open_instance({.validation = true, .require_validation = test::vulkan_required(), .hide = hide});
    if (!s.instance) return;
    if (!test::presentation_expected(*s.instance)) {
        MESSAGE("skipped: this Vulkan has no window system (no VK_KHR_surface), so nothing presents on it");
        return;
    }
    if (!s.instance->has_extension(vk::EXTHeadlessSurfaceExtensionName)) {
        test::cannot_present(*s.instance, "the loader offers no VK_EXT_headless_surface");
        return;
    }
    auto surface = gpu::make_headless_surface(*s.instance);
    ATLAS_REQUIRE(surface.has_value(), surface.error().text());
    {
        const gpu::DeviceOptions presenting{.surface = **surface, .force_local_read = true};
        auto picked = gpu::Device::pick(*s.instance, presenting);
        ATLAS_REQUIRE(picked.has_value(), picked.error().text());
        if (const std::string why = test::why_no_presenting(*s.instance, picked->facts); !why.empty()) {
            test::cannot_present(*s.instance, why);
            return;
        }
        auto device = gpu::Device::open(*s.instance, std::move(*picked), presenting);
        ATLAS_REQUIRE(device.has_value(), device.error().text());
        s.device = std::move(*device);
        if (maintenance_expected_off) CHECK_FALSE(s.device->enabled().swapchain_maintenance1);
        MESSAGE("swapchain maintenance: "
                << std::string(s.device->enabled().swapchain_maintenance1 ? "on" : "off (the fallback)"));
#if defined(__APPLE__)
        MESSAGE("the rest skipped on macOS: Mesa's headless WSI in the emulator package's lavapipe asserts making a "
                "swapchain; the Linux runners run it");
        s.device->wait_idle();
        s.device.reset();
    }
    surface = gpu::fail("closed");
#else
        auto swapchain = gpu::Swapchain::create(*s.device, **surface, {.width = 360, .height = 640}, nullptr);
        ATLAS_REQUIRE(swapchain.has_value(), swapchain.error().text());
        auto cache = gpu::PipelineCache::open(s.device->handle(), s.device->facts(), {}, 0, false);
        ATLAS_REQUIRE(cache.has_value(), cache.error().text());
        auto builder = frame::FrameBuilder::create(*s.device, *cache, frame::make_passes());
        ATLAS_REQUIRE(builder.has_value(), builder.error().text());
        (*builder)->set_inputs({.camera = standin::board_view(360, 640, 1.0)});
        // Both routes the device draws, the facts' pick first; each presents, is resized and presents again.
        std::vector<gpu::Configuration> configs{s.device->facts().choice};
        gpu::Configuration other = s.device->facts().choice;
        other.route = other.route == gpu::Route::dynamic ? gpu::Route::renderpass : gpu::Route::dynamic;
        if (other.route == gpu::Route::renderpass || s.device->enabled().local_read) {
            if (other.route == gpu::Route::dynamic && other.config != gpu::FrameConfig::x1)
                other.target = gpu::CompositeTarget::resolve;
            configs.push_back(other);
        }
        for (const gpu::Configuration& config : configs) {
            auto renderer = gpu::Renderer::create(*s.device, **swapchain, **builder, config);
            ATLAS_REQUIRE(renderer.has_value(), gpu::name_of(config) << ": " << renderer.error().text());
            int presented = 0;
            for (int i = 0; i < 8; ++i) {
                auto report = (*renderer)->draw(0);
                ATLAS_REQUIRE(report.has_value(), report.error().text());
                presented += report->status == gpu::Acquired::ok ? 1 : 0;
            }
            CHECK(presented == 8);
            // A resize: the frames drained, the swapchain remade from the old one, the frame prepared for it again.
            (*renderer)->drain();
            auto bigger = gpu::Swapchain::create(*s.device, **surface, {.width = 480, .height = 800}, swapchain->get());
            swapchain->reset();
            ATLAS_REQUIRE(bigger.has_value(), bigger.error().text());
            (*builder)->set_inputs({.camera = standin::board_view(480, 800, 1.0)});
            auto retargeted = (*renderer)->retarget(**bigger);
            ATLAS_REQUIRE(retargeted.has_value(), retargeted.error().text());
            for (int i = 0; i < 6; ++i) {
                auto report = (*renderer)->draw(0);
                ATLAS_REQUIRE(report.has_value(), report.error().text());
                CHECK(report->status == gpu::Acquired::ok);
            }
            (*renderer)->drain();
            renderer->reset();
            swapchain = std::move(bigger);
            (*builder)->set_inputs({.camera = standin::board_view(480, 800, 1.0)});
            MESSAGE(gpu::name_of(config) << ": 14 frames presented across a resize");
        }
        const auto messages = s.take_messages();
        CHECK_MESSAGE(messages.empty(), (messages.empty() ? std::string() : messages.front()));
        builder->reset();
        swapchain->reset();
        cache = gpu::fail("closed");
        s.device->wait_idle();
        s.device.reset();
    }
    surface = gpu::fail("closed");
#endif
}

} // namespace

TEST_CASE("the map's frame presents, resizes and retires its swapchain without swapchain maintenance, the main path") {
    present_frames({"VK_EXT_swapchain_maintenance1", "VK_KHR_swapchain_maintenance1", "VK_EXT_surface_maintenance1",
                    "VK_KHR_surface_maintenance1"},
                   true);
}

TEST_CASE("the map's frame presents, resizes and retires its swapchain with swapchain maintenance where it has it") {
    present_frames({}, false);
}
