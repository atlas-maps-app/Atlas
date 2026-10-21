// The phone's frame path on the host (plan-vector K1): the swapchain code on a headless surface
// (VK_EXT_headless_surface, lavapipe's), acquiring, drawing and presenting through the frames in flight at the frame's
// 8 bits, remade as a resize remakes it, and remade as deep as the surface allows as VK bench's colour stage remakes it
// (the surface's formats and the frame's colour on it are printed first); and the stand-in drawn at Vulkan 1.1, what
// the oldest phones offer, on a device opened as such a phone opens it, to present, at 8 bits and at 10; both under
// the validation layer, silent.
// Where the host's Vulkan cannot present (no window system, no VK_KHR_swapchain on the device, or for the first no
// VK_EXT_headless_surface), the first skips with the reason and the second opens its device for offscreen work and
// draws all the same; where presentation is demanded of it (a Vulkan with a window system, Vulkan required:
// tests/gpu/gpu_fixture.hpp), both fail instead. The arm64 runner's lavapipe has no window system.
#include "gpu/Frames.hpp"
#include "gpu/Passes.hpp"
#include "gpu/PipelineCache.hpp"
#include "gpu/Swapchain.hpp"
#include "standin/StandIn.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <doctest/doctest.h>

#include <memory>
#include <string>

#if !defined(__ANDROID__)
#include "gpu/Offscreen.hpp"
#endif

using namespace atlas;

TEST_CASE("the swapchain presents the stand-in on a headless surface and is remade at another size") {
    test::Session s;
    s.instance = test::open_instance({.validation = true, .require_validation = test::vulkan_required()});
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
        const gpu::DeviceOptions presenting{.surface = **surface};
        auto picked = gpu::Device::pick(*s.instance, presenting);
        ATLAS_REQUIRE(picked.has_value(), picked.error().text());
        if (const std::string why = test::why_no_presenting(*s.instance, picked->facts); !why.empty()) {
            test::cannot_present(*s.instance, why);
            return;
        }
        auto device = gpu::Device::open(*s.instance, std::move(*picked), presenting);
        ATLAS_REQUIRE(device.has_value(), device.error().text());
        s.device = std::move(*device);
        gpu::collect_surface(s.device->facts(), s.device->physical(), **surface, s.device->queue_family());
        for (const std::string& l : gpu::facts_lines(s.device->facts())) {
            if (l.starts_with("surface.") || l.starts_with("colour")) MESSAGE("vkfacts " << l);
        }
#if defined(__APPLE__)
        // The Android emulator package's lavapipe for macOS aborts in Mesa's headless WSI (wsi_common.c,
        // get_blit_type: "Invalid image type") whatever the swapchain asks; the Linux runners' lavapipe runs the rest.
        MESSAGE("the rest skipped on macOS: Mesa's headless WSI in the emulator package's lavapipe asserts making a "
                "swapchain; the Linux runners run it");
        s.device->wait_idle();
        s.device.reset();
    }
    surface = gpu::fail("closed");
#else
        auto swapchain = gpu::Swapchain::create(*s.device, **surface, {.width = 360, .height = 640}, nullptr);
        ATLAS_REQUIRE(swapchain.has_value(), swapchain.error().text());
        CHECK((*swapchain)->format() == s.device->facts().colour.format);
        MESSAGE("the swapchain: " << gpu::colour_name((*swapchain)->format()) << " (" << (*swapchain)->colour().reason
                                  << ")");
        auto cache = gpu::PipelineCache::open(s.device->handle(), s.device->facts(), {}, 0, false);
        ATLAS_REQUIRE(cache.has_value(), cache.error().text());
        auto city = std::make_shared<const standin::City>(standin::City::generate(64, 13.0));
        auto standin = standin::StandIn::create(*s.device, *cache, city);
        ATLAS_REQUIRE(standin.has_value(), standin.error().text());
        (*standin)->set_camera(standin::board_view(360, 640, 1.0));
        auto renderer = gpu::Renderer::create(*s.device, **swapchain, **standin, s.device->facts().choice);
        ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
        int presented = 0;
        for (int i = 0; i < 12; ++i) {
            auto report = (*renderer)->draw(0);
            ATLAS_REQUIRE(report.has_value(), report.error().text());
            presented += report->status == gpu::Acquired::ok ? 1 : 0;
        }
        CHECK(presented == 12);
        // A resize: the frames drained, the swapchain remade from the old one (retired by the call, then let go of),
        // the content remade for it.
        (*renderer)->drain();
        auto bigger = gpu::Swapchain::create(*s.device, **surface, {.width = 480, .height = 800}, swapchain->get());
        swapchain->reset();
        ATLAS_REQUIRE(bigger.has_value(), bigger.error().text());
        auto retargeted = (*renderer)->retarget(**bigger);
        ATLAS_REQUIRE(retargeted.has_value(), retargeted.error().text());
        for (int i = 0; i < 6; ++i) {
            auto report = (*renderer)->draw(0);
            ATLAS_REQUIRE(report.has_value(), report.error().text());
            CHECK(report->status == gpu::Acquired::ok);
        }
        CHECK_FALSE((*bigger)->colour().ten_bit()); // the frame's 8 bits
        // As deep as the surface allows, as VK bench's colour stage remakes it, the device asked about 10 bits first
        // (lavapipe's headless surface offers 8 bits only, so 8 again, saying why).
        (*renderer)->drain();
        s.device->check_ten_bit();
        auto deep = gpu::Swapchain::create(*s.device, **surface, {.width = 480, .height = 800}, bigger->get(),
                                           gpu::Depth::deepest);
        bigger->reset();
        ATLAS_REQUIRE(deep.has_value(), deep.error().text());
        CHECK((*deep)->format() == s.device->facts().deepest.format);
        MESSAGE("the colour stage's swapchain: " << gpu::colour_name((*deep)->format()) << " ("
                                                 << (*deep)->colour().reason << ")");
        auto at_deep = (*renderer)->retarget(**deep);
        ATLAS_REQUIRE(at_deep.has_value(), at_deep.error().text());
        for (int i = 0; i < 6; ++i) {
            auto report = (*renderer)->draw(0);
            ATLAS_REQUIRE(report.has_value(), report.error().text());
            CHECK(report->status == gpu::Acquired::ok);
        }
        (*renderer)->drain();
        const auto messages = s.take_messages();
        CHECK_MESSAGE(messages.empty(), (messages.empty() ? std::string() : messages.front()));
        renderer->reset();
        standin->reset();
        deep->reset();
        cache = gpu::fail("closed");
        s.device->wait_idle();
        s.device.reset();
    }
    surface = gpu::fail("closed");
#endif
}

// The hosts' only: it draws into offscreen images (gpu/Offscreen.hpp), where the phones draw into their windows.
#if !defined(__ANDROID__)
TEST_CASE("the stand-in draws at Vulkan 1.1 under the validation layer, silent") {
    // What a Vulkan 1.1 phone allows: no 1.2-1.4 core structures, the composite's SPIR-V without the without-format
    // capabilities a 1.1 device without shaderStorageImageReadWithoutFormat refuses. Its device is opened as the
    // phone's is, to present (with the swapchain and what goes with it), and draws into offscreen images. Where the
    // host's Vulkan cannot present (the arm64 runner's), it is opened for offscreen work and draws all the same; where
    // presentation is demanded, that fails instead.
    test::Session s;
    s.instance = test::open_instance(
        {.validation = true, .require_validation = test::vulkan_required(), .max_version = VK_API_VERSION_1_1});
    if (!s.instance) return;
    gpu::DeviceOptions phone{.present = true};
    auto picked = gpu::Device::pick(*s.instance, phone);
    ATLAS_REQUIRE(picked.has_value(), picked.error().text());
    if (const std::string why = test::why_no_presenting(*s.instance, picked->facts); !why.empty()) {
        if (test::presentation_demanded(*s.instance)) {
            test::cannot_present(*s.instance, why + ", which every phone's device has and opens");
            return;
        }
        MESSAGE("opened for offscreen work, unlike a phone's: " << why);
        phone.present = false;
    }
    auto device = gpu::Device::open(*s.instance, std::move(*picked), phone);
    ATLAS_REQUIRE(device.has_value(), device.error().text());
    s.device = std::move(*device);
    s.device->check_ten_bit(); // it draws at 10 bits too
    CHECK(s.device->version() == VK_API_VERSION_1_1);
    CHECK(s.device->enabled().swapchain == phone.present);
    auto cache = gpu::PipelineCache::open(s.device->handle(), s.device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    auto city = std::make_shared<const standin::City>(standin::City::generate(64, 13.0));
    const vk::Extent2D extent{.width = 270, .height = 600};
    for (const gpu::Depth depth : {gpu::Depth::eight, gpu::Depth::deepest}) {
        auto output = gpu::Offscreen::create(*s.device, extent, 2, depth);
        ATLAS_REQUIRE(output.has_value(), output.error().text());
        MESSAGE("at Vulkan 1.1 in " << gpu::colour_name((*output)->format()));
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
            ATLAS_REQUIRE(standin.has_value(), standin.error().text());
            (*standin)->set_camera(standin::board_view(extent.width, extent.height, 0.6559));
            auto renderer = gpu::Renderer::create(*s.device, **output, **standin, config);
            ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
            for (int i = 0; i < 3; ++i) {
                auto report = (*renderer)->draw(0);
                ATLAS_REQUIRE(report.has_value(), report.error().text());
            }
            (*renderer)->drain();
            const auto messages = s.take_messages();
            CHECK_MESSAGE(messages.empty(), gpu::colour_name((*output)->format())
                                                << ": " << (messages.empty() ? std::string() : messages.front()));
            renderer->reset();
        }
    }
    cache = gpu::fail("closed");
}

#endif
