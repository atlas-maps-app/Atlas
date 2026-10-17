// The phone's frame path on the host (plan-vector K1): the swapchain code on a headless surface
// (VK_EXT_headless_surface, lavapipe's), acquiring, drawing and presenting through the frames in flight and remade as a
// resize remakes it; and the stand-in drawn at Vulkan 1.1, what the oldest phones offer, both under the validation
// layer, silent.
#include "gpu/Frames.hpp"
#include "gpu/Passes.hpp"
#include "gpu/PipelineCache.hpp"
#include "gpu/Swapchain.hpp"
#include "standin/StandIn.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <doctest/doctest.h>

#include <memory>

#if !defined(__ANDROID__)
#include "platform/host/Offscreen.hpp"
#endif

using namespace atlas;

TEST_CASE("the swapchain presents the stand-in on a headless surface and is remade at another size") {
    auto instance = gpu::Instance::open({.validation = true, .require_validation = test::vulkan_required()});
    if (!instance) {
        if (test::vulkan_required()) FAIL(instance.error().text());
        MESSAGE("skipped: " << instance.error().text());
        return;
    }
    if (!(*instance)->has_extension(vk::EXTHeadlessSurfaceExtensionName)) {
        MESSAGE("skipped: the loader offers no VK_EXT_headless_surface");
        return;
    }
#if defined(__APPLE__)
    // The Android emulator package's lavapipe for macOS aborts in Mesa's headless WSI (wsi_common.c, get_blit_type:
    // "Invalid image type") whatever the swapchain asks; the Linux runners' lavapipe runs this test.
    MESSAGE(
        "skipped on macOS: Mesa's headless WSI in the emulator package's lavapipe asserts; the Linux runners run it");
#else
    test::Session s;
    s.instance = std::move(*instance);
    auto surface = gpu::make_headless_surface(*s.instance);
    REQUIRE_MESSAGE(surface.has_value(), surface.error().text());
    {
        auto device = gpu::Device::open(*s.instance, {.surface = **surface});
        REQUIRE_MESSAGE(device.has_value(), device.error().text());
        s.device = std::move(*device);
        gpu::collect_surface(s.device->facts(), s.device->physical(), **surface, s.device->queue_family());
        for (const std::string& l : gpu::facts_lines(s.device->facts())) {
            if (l.starts_with("surface.")) MESSAGE("vkfacts " << l);
        }
        auto swapchain = gpu::Swapchain::create(*s.device, **surface, {.width = 360, .height = 640}, nullptr);
        REQUIRE_MESSAGE(swapchain.has_value(), swapchain.error().text());
        auto cache = gpu::PipelineCache::open(s.device->handle(), s.device->facts(), {}, 0, false);
        REQUIRE(cache.has_value());
        auto city = std::make_shared<const standin::City>(standin::City::generate(64, 13.0));
        auto standin = standin::StandIn::create(*s.device, *cache, city);
        REQUIRE(standin.has_value());
        (*standin)->set_camera(standin::board_view(360, 640, 1.0));
        auto renderer = gpu::Renderer::create(*s.device, **swapchain, **standin, s.device->facts().choice);
        REQUIRE_MESSAGE(renderer.has_value(), renderer.error().text());
        int presented = 0;
        for (int i = 0; i < 12; ++i) {
            auto report = (*renderer)->draw(0);
            REQUIRE_MESSAGE(report.has_value(), report.error().text());
            presented += report->status == gpu::Acquired::ok ? 1 : 0;
        }
        CHECK(presented == 12);
        // A resize: the frames drained, the swapchain remade from the old one, the content remade for it.
        (*renderer)->drain();
        auto bigger =
            gpu::Swapchain::create(*s.device, **surface, {.width = 480, .height = 800}, std::move(*swapchain));
        REQUIRE_MESSAGE(bigger.has_value(), bigger.error().text());
        REQUIRE((*renderer)->retarget(**bigger).has_value());
        for (int i = 0; i < 6; ++i) {
            auto report = (*renderer)->draw(0);
            REQUIRE(report.has_value());
            CHECK(report->status == gpu::Acquired::ok);
        }
        (*renderer)->drain();
        const auto messages = s.take_messages();
        CHECK_MESSAGE(messages.empty(), (messages.empty() ? std::string() : messages.front()));
        renderer->reset();
        standin->reset();
        bigger->reset();
        cache = gpu::fail("closed");
        s.device->wait_idle();
        s.device.reset();
    }
    surface = gpu::fail("closed");
#endif
}

// The hosts' only: it draws into the host build's offscreen images (platform/host), which the Android build has not.
#if !defined(__ANDROID__)
TEST_CASE("the stand-in draws at Vulkan 1.1 under the validation layer, silent") {
    // What a Vulkan 1.1 phone allows: no 1.2-1.4 core structures, the composite's SPIR-V without the without-format
    // capabilities a 1.1 device without shaderStorageImageReadWithoutFormat refuses.
    auto instance = gpu::Instance::open(
        {.validation = true, .require_validation = test::vulkan_required(), .max_version = VK_API_VERSION_1_1});
    if (!instance) {
        if (test::vulkan_required()) FAIL(instance.error().text());
        MESSAGE("skipped: " << instance.error().text());
        return;
    }
    test::Session s;
    s.instance = std::move(*instance);
    auto device = gpu::Device::open(*s.instance, {});
    REQUIRE_MESSAGE(device.has_value(), device.error().text());
    s.device = std::move(*device);
    CHECK(s.device->version() == VK_API_VERSION_1_1);
    auto cache = gpu::PipelineCache::open(s.device->handle(), s.device->facts(), {}, 0, false);
    REQUIRE(cache.has_value());
    auto city = std::make_shared<const standin::City>(standin::City::generate(64, 13.0));
    const vk::Extent2D extent{.width = 270, .height = 600};
    auto output = platform::Offscreen::create(*s.device, extent, 2);
    REQUIRE(output.has_value());
    for (const gpu::Configuration config : {gpu::Configuration{.config = gpu::FrameConfig::c,
                                                               .route = gpu::Route::renderpass,
                                                               .form = gpu::FrameForm::composite,
                                                               .target = gpu::CompositeTarget::direct},
                                            gpu::Configuration{.config = gpu::FrameConfig::c,
                                                               .route = gpu::Route::renderpass,
                                                               .form = gpu::FrameForm::composite,
                                                               .target = gpu::CompositeTarget::resolve},
                                            gpu::Configuration{.config = gpu::FrameConfig::x1,
                                                               .route = gpu::Route::renderpass,
                                                               .form = gpu::FrameForm::composite,
                                                               .target = gpu::CompositeTarget::direct}}) {
        auto standin = standin::StandIn::create(*s.device, *cache, city);
        REQUIRE(standin.has_value());
        (*standin)->set_camera(standin::board_view(extent.width, extent.height, 0.6559));
        auto renderer = gpu::Renderer::create(*s.device, **output, **standin, config);
        REQUIRE_MESSAGE(renderer.has_value(), renderer.error().text());
        for (int i = 0; i < 3; ++i) REQUIRE((*renderer)->draw(0).has_value());
        (*renderer)->drain();
        const auto messages = s.take_messages();
        CHECK_MESSAGE(messages.empty(), (messages.empty() ? std::string() : messages.front()));
        renderer->reset();
    }
    cache = gpu::fail("closed");
}

#endif
