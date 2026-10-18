// The frame route's guard (plan-vector T1, D5.11; the device survey of 18 October): dynamic rendering is the frame's
// route only where its local reads can read what the composite reads, multisampled colour and the depth-stencil's
// stencil. VK_KHR_dynamic_rendering_local_read implies both; Vulkan 1.4's core makes them properties
// (VkPhysicalDeviceVulkan14Properties::dynamicRenderingLocalReadMultisampledAttachments and
// ...DepthStencilAttachments), which the facts read and the choice requires. Every 1.4 phone driver in the survey has
// both; the rule is for the one that will not.
#include "gpu/Facts.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <string>

using namespace atlas;

namespace {

/// A Mali-like tiler with lazily allocated memory and every sample count, its local reads as given.
[[nodiscard]] gpu::Facts tiler(bool dynamic, bool local_read, bool depth_stencil, bool multisampled) {
    gpu::Facts f;
    f.vendor_id = 0x13B5;
    f.tiler = true;
    f.lazy_memory = true;
    const vk::SampleCountFlags counts =
        vk::SampleCountFlagBits::e1 | vk::SampleCountFlagBits::e2 | vk::SampleCountFlagBits::e4;
    f.limits.framebufferColorSampleCounts = counts;
    f.limits.framebufferDepthSampleCounts = counts;
    f.limits.framebufferStencilSampleCounts = counts;
    f.features.dynamic_rendering = dynamic;
    f.features.local_read = local_read;
    f.features.local_read_depth_stencil = depth_stencil;
    f.features.local_read_multisampled = multisampled;
    return f;
}

} // namespace

TEST_CASE("dynamic rendering is the route only where local reads read multisampled and depth-stencil attachments") {
    gpu::Facts all = tiler(true, true, true, true);
    gpu::choose_configuration(all, false);
    CHECK(all.choice.config == gpu::FrameConfig::a);
    CHECK(all.choice.route == gpu::Route::dynamic);

    for (const auto& [ds, ms] : {std::pair{false, true}, std::pair{true, false}, std::pair{false, false}}) {
        gpu::Facts f = tiler(true, true, ds, ms);
        gpu::choose_configuration(f, false);
        CHECK(f.choice.route == gpu::Route::renderpass);
        CHECK_MESSAGE(f.route_reason.find("multisampled or depth-stencil") != std::string::npos, f.route_reason);
        CHECK_FALSE(f.features.frame_local_read());
    }
    gpu::Facts without = tiler(true, false, false, false);
    gpu::choose_configuration(without, false);
    CHECK(without.choice.route == gpu::Route::renderpass);
    CHECK(without.route_reason.find("without local reads") != std::string::npos);
    gpu::Facts none = tiler(false, false, false, false);
    gpu::choose_configuration(none, false);
    CHECK(none.choice.route == gpu::Route::renderpass);
    CHECK(none.route_reason.find("no dynamic rendering") != std::string::npos);
}

TEST_CASE(
    "the host's device reports what its local reads may read, and enables them only where the frame can use them") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    const gpu::FeatureFacts& x = device.facts().features;
    MESSAGE(device.facts().name << ": dynamicRenderingLocalRead " << x.local_read << ", depth-stencil attachments "
                                << x.local_read_depth_stencil << ", multisampled attachments "
                                << x.local_read_multisampled);
    // Without local reads neither property can hold; with the extension (before 1.4) both are implied. The device
    // still enables the local reads it has (the tests draw both routes wherever the device can draw them, lavapipe's
    // included, whose 1.4 properties deny both: the frame's own choice never takes it there).
    if (!x.local_read) CHECK((!x.local_read_depth_stencil && !x.local_read_multisampled));
    if (device.facts().choice.route == gpu::Route::dynamic) CHECK(x.frame_local_read());
    if (!x.frame_local_read()) CHECK(device.facts().choice.route == gpu::Route::renderpass);
    const auto lines = gpu::facts_lines(device.facts());
    CHECK(std::ranges::any_of(lines, [](const std::string& l) {
        return l.starts_with("feature.dynamicRenderingLocalReadMultisampledAttachments: ");
    }));
    CHECK(std::ranges::any_of(lines, [](const std::string& l) {
        return l.starts_with("feature.dynamicRenderingLocalReadDepthStencilAttachments: ");
    }));
    (void)session->take_messages();
}

TEST_CASE("the facts read an extension's feature only where the device lists the extension") {
    // VK_EXT and VK_ARM_rasterization_order_attachment_access, VK_EXT_multisampled_render_to_single_sampled: hidden,
    // the device lists none, so their structures are not chained and their features read false, whatever the driver
    // has.
    auto instance = test::open_instance(
        {.validation = true,
         .require_validation = test::vulkan_required(),
         .hide = {"VK_EXT_rasterization_order_attachment_access", "VK_ARM_rasterization_order_attachment_access",
                  "VK_EXT_multisampled_render_to_single_sampled", "VK_KHR_dynamic_rendering_local_read"}});
    if (!instance) return;
    auto picked = gpu::Device::pick(*instance, {});
    ATLAS_REQUIRE(picked.has_value(), picked.error().text());
    const gpu::Facts& f = picked->facts;
    CHECK_FALSE(f.has_extension("VK_EXT_rasterization_order_attachment_access"));
    CHECK_FALSE(f.features.rasterization_order);
    CHECK_FALSE(f.features.multisampled_to_single);
    // Before Vulkan 1.4 local reads come with their extension alone; at 1.4 they are core and read through 1.4's.
    if (std::min(f.api_version, f.instance_version) < VK_API_VERSION_1_4) {
        CHECK_FALSE(f.features.local_read);
    }
    MESSAGE(f.name << " with the extensions hidden: rasterizationOrderColorAttachmentAccess "
                   << f.features.rasterization_order << ", multisampledRenderToSingleSampled "
                   << f.features.multisampled_to_single);
    (void)instance->messages().take();
}
