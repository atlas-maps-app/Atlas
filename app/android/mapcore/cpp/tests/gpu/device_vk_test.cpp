// The device layer on the host's Vulkan (plan-vector K1, T10): the instance and the device open on lavapipe under the
// validation layer, VkFacts are read and printed, and the frame they decide is one the device can draw. Presenting is
// the caller's choice: a device for offscreen work opens without VK_KHR_swapchain, as on the arm64 runner's lavapipe
// (Mesa without a window system), and one that presents needs it. Any message of the validation layer fails the test.
// Without Vulkan the tests are skipped, unless ATLAS_REQUIRE_VULKAN=1 (CI and the presets) makes that a failure.
#include "gpu/Device.hpp"
#include "gpu/Facts.hpp"
#include "gpu/Swapchain.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <string>
#include <string_view>
#include <vector>

using namespace atlas;

namespace {

[[nodiscard]] bool has_line(const std::vector<std::string>& lines, std::string_view prefix) {
    return std::ranges::any_of(lines, [&](const std::string& l) { return l.starts_with(prefix); });
}

} // namespace

TEST_CASE("the device opens on the host's Vulkan and prints its facts") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Facts& facts = session->device->facts();
    const std::vector<std::string> lines = gpu::facts_lines(facts);
    for (const std::string& l : lines) MESSAGE("vkfacts " << l);
    CHECK(facts.missing.empty());
    CHECK_FALSE(facts.presents);
    CHECK(facts.api_version >= VK_API_VERSION_1_1);
    CHECK(lines.size() > 60);
    for (const char* prefix : {"gpu: ",
                               "vendor: ",
                               "api: ",
                               "driver",
                               "hardware: ",
                               "tiler: ",
                               "lazily.allocated.memory: ",
                               "requirements: all met (offscreen)",
                               "config: ",
                               "route: ",
                               "composite: ",
                               "depth.stencil: ",
                               "samples: ",
                               "profiles: ",
                               "memory.heap0: ",
                               "memory.type0: ",
                               "queue0: ",
                               "ext.VK_KHR_swapchain: ",
                               "colour: ",
                               "colour.stage: ",
                               "colour.formats: ",
                               "feature.shaderFloat64: ",
                               "feature.shaderInt64: ",
                               "extensions: ",
                               "extensions.0: "}) {
        CHECK_MESSAGE(has_line(lines, prefix), "no line starts with " << std::string(prefix));
    }
    // lavapipe is a CPU implementation, not a tiler, without lazily allocated memory: configuration C (D5.3). It draws
    // the frame in 10 bits a channel, so VK bench's colour stage would take them offscreen (gpu/Facts.hpp's deepest)
    // while the frame keeps its 8 (its colour), and it has shaderFloat64 (K5's light in binary64).
    if (facts.driver_properties && facts.driver_id == vk::DriverId::eMesaLlvmpipe) {
        CHECK_FALSE(facts.hardware);
        CHECK_FALSE(facts.tiler);
        CHECK_FALSE(facts.lazy_memory);
        CHECK(facts.choice.config == gpu::FrameConfig::c);
        CHECK(facts.colour.format == vk::Format::eR8G8B8A8Unorm);
        CHECK(facts.deepest.format == vk::Format::eA2B10G10R10UnormPack32);
        CHECK(facts.ten_bit_off.empty());
        CHECK(std::ranges::find(lines, "feature.shaderFloat64: yes (K5's light: binary64, the port's bits (E0), where "
                                       "its probe finds binary64 exact)") != lines.end());
    }
    CHECK(facts.supports(facts.choice.config));
    CHECK_FALSE(session->device->enabled().swapchain);
    CHECK(session->take_messages().empty());
}

TEST_CASE("a device that presents opens with the swapchain where the host's Vulkan has a window system") {
    // As the Android façade opens its device: to present.
    test::Session s;
    s.instance = test::open_instance({.validation = true, .require_validation = test::vulkan_required()});
    if (!s.instance) return;
    const gpu::DeviceOptions presenting{.present = true};
    auto picked = gpu::Device::pick(*s.instance, presenting);
    ATLAS_REQUIRE(picked.has_value(), picked.error().text());
    if (!picked->facts.has_extension(vk::KHRSwapchainExtensionName)) {
        test::cannot_present(*s.instance, picked->facts.name + " has no VK_KHR_swapchain");
        return;
    }
    CHECK(picked->facts.presents);
    auto device = gpu::Device::open(*s.instance, std::move(*picked), presenting);
    ATLAS_REQUIRE(device.has_value(), device.error().text());
    s.device = std::move(*device);
    const std::vector<std::string> lines = gpu::facts_lines(s.device->facts());
    CHECK(std::ranges::find(lines, "requirements: all met") != lines.end()); // as the phone's facts say it
    CHECK(std::ranges::find(lines, "ext.VK_KHR_swapchain: yes") != lines.end());
    CHECK(s.device->enabled().swapchain);
    CHECK(s.take_messages().empty());
}

TEST_CASE("without a window system an offscreen device opens, and one that presents is refused with the reason") {
    // This host's Vulkan made to look like the arm64 runner's, Mesa built without a window system: no VK_KHR_surface on
    // the instance, no VK_KHR_swapchain on the device. Every surface extension builds on VK_KHR_surface, so none is
    // enabled without it; the validation layer would say so while the instance is made, and any message fails here.
    test::Session s;
    s.instance = test::open_instance({.validation = true,
                                      .require_validation = test::vulkan_required(),
                                      .hide = {vk::KHRSurfaceExtensionName, vk::KHRSwapchainExtensionName}});
    if (!s.instance) return;
    CHECK_FALSE(s.instance->has_extension(vk::KHRSurfaceExtensionName));
    CHECK_FALSE(s.instance->has_extension(vk::EXTHeadlessSurfaceExtensionName));
    CHECK_FALSE(s.instance->has_extension(vk::KHRGetSurfaceCapabilities2ExtensionName));
    CHECK_FALSE(s.instance->has_extension(vk::EXTSurfaceMaintenance1ExtensionName));
    CHECK_FALSE(test::presentation_expected(*s.instance));

    auto presenting = gpu::Device::open(*s.instance, {.present = true});
    ATLAS_REQUIRE(!presenting.has_value(), "a device without VK_KHR_swapchain opened to present");
    MESSAGE("a device that presents: " << presenting.error().text());
    CHECK(presenting.error().text().ends_with("lacks what the core needs: VK_KHR_swapchain"));
    // One picked for offscreen work and then opened to present is judged again.
    auto picked = gpu::Device::pick(*s.instance, {});
    ATLAS_REQUIRE(picked.has_value(), picked.error().text());
    CHECK(picked->facts.missing.empty());
    CHECK_FALSE(gpu::Device::open(*s.instance, std::move(*picked), {.present = true}).has_value());

    auto device = gpu::Device::open(*s.instance, {});
    ATLAS_REQUIRE(device.has_value(), device.error().text());
    s.device = std::move(*device);
    const gpu::Facts& facts = s.device->facts();
    CHECK(facts.missing.empty());
    CHECK_FALSE(facts.presents);
    CHECK_FALSE(facts.has_extension(vk::KHRSwapchainExtensionName));
    CHECK_FALSE(s.device->enabled().swapchain);
    CHECK_FALSE(s.device->enabled().swapchain_maintenance1);
    CHECK_FALSE(s.device->enabled().display_timing);
    CHECK_FALSE(s.device->enabled().incremental_present);
    // VkFacts still says whether the device has the swapchain, and what the tests hid.
    const std::vector<std::string> lines = gpu::facts_lines(facts);
    CHECK(std::ranges::find(lines, "requirements: all met (offscreen)") != lines.end());
    CHECK(std::ranges::find(lines, "ext.VK_KHR_swapchain: no") != lines.end());
    CHECK(has_line(lines, "hidden: VK_KHR_surface, VK_KHR_swapchain "));
    // It makes no swapchain, and says why.
    auto swapchain = gpu::Swapchain::create(*s.device, vk::SurfaceKHR{}, {.width = 64, .height = 64}, nullptr);
    ATLAS_REQUIRE(!swapchain.has_value(), "an offscreen device made a swapchain");
    CHECK(swapchain.error().text().ends_with("without VK_KHR_swapchain: it cannot present"));
    const auto messages = s.take_messages();
    CHECK_MESSAGE(messages.empty(), (messages.empty() ? std::string() : messages.front()));
}
