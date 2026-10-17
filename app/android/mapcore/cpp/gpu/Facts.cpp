// VkFacts: reading a physical device, deciding the frame from it, and printing it (gpu/Facts.hpp).
#include "gpu/Facts.hpp"

#include "gpu/Quirks.hpp"

#define VP_USE_OBJECT
#include <vulkan/vulkan_profiles.hpp>

#include <algorithm>
#include <format>
#include <utility>

namespace atlas::gpu {

namespace {

[[nodiscard]] std::string yes_no(bool b) { return b ? "yes" : "no"; }

/// The device's effective version: what both the device and the instance offer.
[[nodiscard]] std::uint32_t effective_version(std::uint32_t device, std::uint32_t instance) noexcept {
    return std::min(device, instance);
}

[[nodiscard]] std::string memory_flags(vk::MemoryPropertyFlags flags) {
    std::string out;
    auto add = [&](vk::MemoryPropertyFlagBits bit, std::string_view name) {
        if (flags & bit) out += std::format("{}{}", out.empty() ? "" : " ", name);
    };
    add(vk::MemoryPropertyFlagBits::eDeviceLocal, "device-local");
    add(vk::MemoryPropertyFlagBits::eHostVisible, "host-visible");
    add(vk::MemoryPropertyFlagBits::eHostCoherent, "host-coherent");
    add(vk::MemoryPropertyFlagBits::eHostCached, "host-cached");
    add(vk::MemoryPropertyFlagBits::eLazilyAllocated, "lazily-allocated");
    add(vk::MemoryPropertyFlagBits::eProtected, "protected");
    add(vk::MemoryPropertyFlagBits::eDeviceCoherentAMD, "device-coherent");
    add(vk::MemoryPropertyFlagBits::eDeviceUncachedAMD, "device-uncached");
    return out.empty() ? "none" : out;
}

[[nodiscard]] std::string sample_counts(vk::SampleCountFlags flags) {
    std::string out;
    for (std::uint32_t n = 1; n <= 64; n <<= 1) {
        if (flags & static_cast<vk::SampleCountFlagBits>(n)) out += std::format("{}{}", out.empty() ? "" : ",", n);
    }
    return out.empty() ? "none" : out;
}

[[nodiscard]] std::string queue_flags(vk::QueueFlags flags) {
    std::string out;
    auto add = [&](vk::QueueFlagBits bit, std::string_view name) {
        if (flags & bit) out += std::format("{}{}", out.empty() ? "" : " ", name);
    };
    add(vk::QueueFlagBits::eGraphics, "graphics");
    add(vk::QueueFlagBits::eCompute, "compute");
    add(vk::QueueFlagBits::eTransfer, "transfer");
    add(vk::QueueFlagBits::eSparseBinding, "sparse");
    add(vk::QueueFlagBits::eProtected, "protected");
    return out.empty() ? "none" : out;
}

[[nodiscard]] std::string_view type_name(vk::PhysicalDeviceType type) noexcept {
    switch (type) {
    case vk::PhysicalDeviceType::eIntegratedGpu: return "integrated GPU";
    case vk::PhysicalDeviceType::eDiscreteGpu: return "discrete GPU";
    case vk::PhysicalDeviceType::eVirtualGpu: return "virtual GPU";
    case vk::PhysicalDeviceType::eCpu: return "CPU";
    case vk::PhysicalDeviceType::eOther: return "other";
    }
    return "unknown";
}

[[nodiscard]] std::string uuid_text(const std::array<std::uint8_t, VK_UUID_SIZE>& uuid) {
    std::string out;
    for (const std::uint8_t b : uuid) out += std::format("{:02x}", b);
    return out;
}

/// Android's Vulkan profiles through the Profiles library's object API (no global state): name and support.
void read_profiles(Facts& facts, const Instance& instance, const vk::raii::PhysicalDevice& device) {
    const auto* global = instance.context().getDispatcher();
    VpFunctionsCreateInfo info{};
    info.GetInstanceProcAddr = global->vkGetInstanceProcAddr;
    info.EnumerateInstanceVersion = global->vkEnumerateInstanceVersion;
    info.EnumerateInstanceExtensionProperties = global->vkEnumerateInstanceExtensionProperties;
    info.CreateInstance = global->vkCreateInstance;
    VpFunctions vp = nullptr;
    if (const VkResult created = vpCreateFunctions(&info, nullptr, &vp); created != VK_SUCCESS) {
        facts.profiles_status = std::format("the profiles library could not start ({})", static_cast<int>(created));
        return;
    }
    const VkResult globals = vpInitializeGlobalFunctions(vp, info.GetInstanceProcAddr);
    const VkResult instance_functions = vpInitializeInstanceFunctions(vp, *instance.handle(), 0);
    std::uint32_t count = 0;
    if (globals != VK_SUCCESS || instance_functions != VK_SUCCESS || vpGetProfiles(vp, &count, nullptr) != VK_SUCCESS) {
        facts.profiles_status = "the profiles library could not read the device";
        vpDestroyFunctions(vp, nullptr);
        return;
    }
    std::vector<VpProfileProperties> all(count);
    (void)vpGetProfiles(vp, &count, all.data());
    for (const VpProfileProperties& p : all) {
        VkBool32 supported = VK_FALSE;
        const VkResult r = vpGetPhysicalDeviceProfileSupport(vp, *instance.handle(), *device, &p, &supported);
        facts.profiles.emplace_back(p.profileName, r == VK_SUCCESS && supported == VK_TRUE);
    }
    std::ranges::sort(facts.profiles);
    facts.profiles_status = "read";
    vpDestroyFunctions(vp, nullptr);
}

/// The reason of the frame's 8-bit colour (default_depth).
constexpr std::string_view eight_bits = "the frame's default in this alpha, 8 bits a channel";

/// The reason of an 8-bit colour where 10 bits were asked for and the crash guard has them off, before its line.
constexpr std::string_view ten_bits_off = "10 bits are off for this driver and build: ";

/// The frame's colour formats, in the order the choice takes them (10 bits first).
constexpr std::array<vk::Format, 4> colour_candidates{vk::Format::eA2B10G10R10UnormPack32,
                                                      vk::Format::eA2R10G10B10UnormPack32, vk::Format::eR8G8B8A8Unorm,
                                                      vk::Format::eB8G8R8A8Unorm};

/// The main pass's image of the scene's samples (gpu/Passes.cpp): drawn, read by the composite, never stored.
constexpr vk::ImageUsageFlags sample_usage = vk::ImageUsageFlagBits::eColorAttachment |
                                             vk::ImageUsageFlagBits::eInputAttachment |
                                             vk::ImageUsageFlagBits::eTransientAttachment;

/// What the device does with each of the frame's colour formats.
void read_colour_formats(Facts& facts, const vk::raii::PhysicalDevice& device) {
    using F = vk::FormatFeatureFlagBits;
    const vk::FormatFeatureFlags drawn = F::eColorAttachment | F::eColorAttachmentBlend;
    const vk::FormatFeatureFlags output =
        drawn | F::eBlitSrc | F::eSampledImageFilterLinear | F::eTransferSrc | F::eTransferDst;
    facts.colour_formats.clear();
    for (const vk::Format format : colour_candidates) {
        ColourFormat c{.format = format};
        const vk::FormatFeatureFlags have = device.getFormatProperties(format).optimalTilingFeatures;
        auto lack = [&](vk::FormatFeatureFlags need, std::string_view what) {
            if ((have & need) != need) c.lacks += std::format("{}{}", c.lacks.empty() ? "" : ", ", what);
        };
        lack(F::eColorAttachment, "colour attachment");
        lack(F::eColorAttachmentBlend, "blending");
        lack(F::eBlitSrc | F::eSampledImageFilterLinear, "linearly filtered blits");
        lack(F::eTransferSrc | F::eTransferDst, "copies");
        c.output = (have & output) == output;
        if (auto image = device.getImageFormatProperties(format, vk::ImageType::e2D, vk::ImageTiling::eOptimal,
                                                         sample_usage, {})) {
            c.samples = image->sampleCounts;
        }
        if (!(c.samples & vk::SampleCountFlagBits::e1))
            c.lacks += std::format("{}{}", c.lacks.empty() ? "" : ", ", "input attachment");
        c.attachment = (have & drawn) == drawn && static_cast<bool>(c.samples & vk::SampleCountFlagBits::e1);
        facts.colour_formats.push_back(std::move(c));
    }
}

/// The 8-bit colour of the frame on a surface offering `offered` (as before the colour depth): rgba8, else bgra8, in
/// sRGB-nonlinear, else the surface's first format.
[[nodiscard]] Colour eight_bit(std::span<const vk::SurfaceFormatKHR> offered, std::string why) {
    for (const vk::Format wanted : {vk::Format::eR8G8B8A8Unorm, vk::Format::eB8G8R8A8Unorm}) {
        for (const vk::SurfaceFormatKHR& f : offered) {
            if (f.format == wanted && f.colorSpace == vk::ColorSpaceKHR::eSrgbNonlinear)
                return {.format = f.format, .space = f.colorSpace, .reason = std::move(why)};
        }
    }
    if (offered.empty()) return {.reason = std::move(why)};
    return {.format = offered.front().format,
            .space = offered.front().colorSpace,
            .reason = why + "; the surface offers neither rgba8 nor bgra8 in sRGB: its first format"};
}

/// The device's features, through the structures its version and extensions allow.
void read_features(Facts& facts, const vk::raii::PhysicalDevice& device, std::uint32_t version) {
    vk::PhysicalDeviceFeatures2 features2{};
    void** tail = &features2.pNext;
    auto link = [&tail](auto& s) {
        *tail = &s;
        tail = &s.pNext;
    };
    vk::PhysicalDeviceVulkan11Features v11{};
    vk::PhysicalDeviceVulkan12Features v12{};
    vk::PhysicalDeviceVulkan13Features v13{};
    vk::PhysicalDeviceVulkan14Features v14{};
    vk::PhysicalDeviceShaderDrawParametersFeatures draw{};
    vk::PhysicalDeviceTimelineSemaphoreFeatures timeline{};
    vk::PhysicalDeviceHostQueryResetFeatures query_reset{};
    vk::PhysicalDeviceSeparateDepthStencilLayoutsFeatures separate{};
    vk::PhysicalDeviceDynamicRenderingFeatures dynamic{};
    vk::PhysicalDeviceSynchronization2Features sync2{};
    vk::PhysicalDevicePipelineCreationCacheControlFeatures cache_control{};
    vk::PhysicalDeviceMaintenance4Features maintenance4{};
    vk::PhysicalDeviceDynamicRenderingLocalReadFeatures local_read{};
    vk::PhysicalDeviceShaderFmaFeaturesKHR fma{};
    vk::PhysicalDeviceSwapchainMaintenance1FeaturesKHR swapchain1{};
    vk::PhysicalDeviceRasterizationOrderAttachmentAccessFeaturesEXT raster_order{};
    vk::PhysicalDeviceMultisampledRenderToSingleSampledFeaturesEXT to_single{};
    auto has = [&](std::string_view name) { return facts.has_extension(name); };

    if (version >= VK_API_VERSION_1_2) {
        link(v11);
        link(v12);
    } else {
        link(draw);
        if (has(vk::KHRTimelineSemaphoreExtensionName)) link(timeline);
        if (has(vk::EXTHostQueryResetExtensionName)) link(query_reset);
        if (has(vk::KHRSeparateDepthStencilLayoutsExtensionName)) link(separate);
    }
    if (version >= VK_API_VERSION_1_3) {
        link(v13);
    } else {
        if (has(vk::KHRDynamicRenderingExtensionName)) link(dynamic);
        if (has(vk::KHRSynchronization2ExtensionName)) link(sync2);
        if (has(vk::EXTPipelineCreationCacheControlExtensionName)) link(cache_control);
        if (has(vk::KHRMaintenance4ExtensionName)) link(maintenance4);
    }
    if (version >= VK_API_VERSION_1_4) {
        link(v14);
    } else if (has(vk::KHRDynamicRenderingLocalReadExtensionName)) {
        link(local_read);
    }
    if (has(vk::KHRShaderFmaExtensionName)) link(fma);
    if (has(vk::KHRSwapchainMaintenance1ExtensionName) || has(vk::EXTSwapchainMaintenance1ExtensionName))
        link(swapchain1);
    if (has(vk::EXTRasterizationOrderAttachmentAccessExtensionName) ||
        has(vk::ARMRasterizationOrderAttachmentAccessExtensionName)) {
        link(raster_order);
    }
    if (has(vk::EXTMultisampledRenderToSingleSampledExtensionName)) link(to_single);

    const auto* dispatcher = device.getDispatcher();
    if (dispatcher->vkGetPhysicalDeviceFeatures2 != nullptr) {
        dispatcher->vkGetPhysicalDeviceFeatures2(*device,
                                                 reinterpret_cast<VkPhysicalDeviceFeatures2*>(&features2)); // NOLINT
    } else {
        features2.features = device.getFeatures();
    }
    const vk::PhysicalDeviceFeatures& f = features2.features;
    FeatureFacts& out = facts.features;
    out.sample_rate_shading = f.sampleRateShading == vk::True;
    out.clip_distance = f.shaderClipDistance == vk::True;
    out.astc_ldr = f.textureCompressionASTC_LDR == vk::True;
    out.etc2 = f.textureCompressionETC2 == vk::True;
    out.dual_src_blend = f.dualSrcBlend == vk::True;
    out.pipeline_statistics = f.pipelineStatisticsQuery == vk::True;
    out.shader_int16 = f.shaderInt16 == vk::True;
    out.shader_int64 = f.shaderInt64 == vk::True;
    out.shader_float64 = f.shaderFloat64 == vk::True;
    out.independent_blend = f.independentBlend == vk::True;
    out.fragment_stores = f.fragmentStoresAndAtomics == vk::True;
    out.vertex_stores = f.vertexPipelineStoresAndAtomics == vk::True;
    out.sampler_anisotropy = f.samplerAnisotropy == vk::True;
    out.read_without_format = f.shaderStorageImageReadWithoutFormat == vk::True;
    if (version >= VK_API_VERSION_1_2) {
        out.draw_parameters = v11.shaderDrawParameters == vk::True;
        out.timeline_semaphore = v12.timelineSemaphore == vk::True;
        out.host_query_reset = v12.hostQueryReset == vk::True;
        out.separate_depth_stencil_layouts = v12.separateDepthStencilLayouts == vk::True;
    } else {
        out.draw_parameters = draw.shaderDrawParameters == vk::True;
        out.timeline_semaphore = timeline.timelineSemaphore == vk::True;
        out.host_query_reset = query_reset.hostQueryReset == vk::True;
        out.separate_depth_stencil_layouts = separate.separateDepthStencilLayouts == vk::True;
    }
    if (version >= VK_API_VERSION_1_3) {
        out.dynamic_rendering = v13.dynamicRendering == vk::True;
        out.synchronization2 = v13.synchronization2 == vk::True;
        out.pipeline_cache_control = v13.pipelineCreationCacheControl == vk::True;
        out.maintenance4 = v13.maintenance4 == vk::True;
    } else {
        out.dynamic_rendering = dynamic.dynamicRendering == vk::True;
        out.synchronization2 = sync2.synchronization2 == vk::True;
        out.pipeline_cache_control = cache_control.pipelineCreationCacheControl == vk::True;
        out.maintenance4 = maintenance4.maintenance4 == vk::True;
    }
    out.local_read = version >= VK_API_VERSION_1_4 ? v14.dynamicRenderingLocalRead == vk::True
                                                   : local_read.dynamicRenderingLocalRead == vk::True;
    out.shader_fma32 = fma.shaderFmaFloat32 == vk::True;
    out.swapchain_maintenance1 = swapchain1.swapchainMaintenance1 == vk::True;
    out.rasterization_order = raster_order.rasterizationOrderColorAttachmentAccess == vk::True;
    out.multisampled_to_single = to_single.multisampledRenderToSingleSampled == vk::True;
}

} // namespace

void check_requirements(Facts& facts, bool present) {
    facts.presents = present;
    facts.missing.clear();
    auto need = [&](bool ok, std::string what) {
        if (!ok) facts.missing.push_back(std::move(what));
    };
    need(facts.api_version >= VK_API_VERSION_1_1, "Vulkan 1.1");
    // Only presenting needs the swapchain: the core's offscreen work (the host tests, the likeness candidate, the
    // light probe, VK check) runs on a device without it, as on the arm64 runner's lavapipe.
    need(!present || facts.has_extension(vk::KHRSwapchainExtensionName), "VK_KHR_swapchain");
    const vk::SampleCountFlags four = vk::SampleCountFlagBits::e4;
    need((facts.limits.framebufferColorSampleCounts & four) && (facts.limits.framebufferDepthSampleCounts & four) &&
             (facts.limits.framebufferStencilSampleCounts & four),
         "4x samples for colour, depth and stencil");
    need(facts.depth_stencil != vk::Format::eUndefined, "D24_UNORM_S8_UINT or D32_SFLOAT_S8_UINT as an attachment");
    need(facts.rgba8_input, "R8G8B8A8_UNORM as a colour attachment");
    need(facts.limits.maxPerStageDescriptorInputAttachments >= 2, "two input attachments");
    bool graphics = false;
    for (const vk::QueueFamilyProperties& q : facts.queues)
        graphics |= static_cast<bool>(q.queueFlags & vk::QueueFlagBits::eGraphics);
    need(graphics, "a graphics queue");
}

vk::SampleCountFlagBits Configuration::samples() const noexcept {
    switch (config) {
    case FrameConfig::a:
    case FrameConfig::c: return vk::SampleCountFlagBits::e4;
    case FrameConfig::b: return vk::SampleCountFlagBits::e2;
    case FrameConfig::d:
    case FrameConfig::x1: return vk::SampleCountFlagBits::e1;
    }
    return vk::SampleCountFlagBits::e1;
}

std::string_view name_of(FrameConfig config) noexcept {
    switch (config) {
    case FrameConfig::a: return "A";
    case FrameConfig::b: return "B";
    case FrameConfig::c: return "C";
    case FrameConfig::d: return "D";
    case FrameConfig::x1: return "1x";
    }
    return "?";
}

std::string_view name_of(Route route) noexcept { return route == Route::dynamic ? "dynamic" : "renderpass"; }
std::string_view name_of(FrameForm form) noexcept { return form == FrameForm::onepass ? "onepass" : "composite"; }
std::string_view name_of(CompositeTarget target) noexcept {
    return target == CompositeTarget::direct ? "direct" : "resolve";
}

std::string name_of(const Configuration& configuration) {
    return std::format("{} {} {} {}", name_of(configuration.config), name_of(configuration.route),
                       name_of(configuration.form), name_of(configuration.target));
}

bool Facts::has_extension(std::string_view extension) const noexcept {
    return std::ranges::binary_search(extensions, extension, std::less<>{});
}

bool Facts::supports(FrameConfig config) const noexcept {
    const vk::SampleCountFlagBits samples = Configuration{.config = config}.samples();
    const bool counts = (limits.framebufferColorSampleCounts & samples) &&
                        (limits.framebufferDepthSampleCounts & samples) &&
                        (limits.framebufferStencilSampleCounts & samples);
    switch (config) {
    case FrameConfig::a: return counts && lazy_memory;
    case FrameConfig::b:
    case FrameConfig::c:
    case FrameConfig::x1: return counts;
    case FrameConfig::d: return false; // FA is not built
    }
    return false;
}

bool Facts::takes_read_without_format() const noexcept {
    return std::min(api_version, instance_version) >= VK_API_VERSION_1_3 || features.read_without_format ||
           has_extension(vk::KHRFormatFeatureFlags2ExtensionName);
}

bool ten_bit(vk::Format format) noexcept {
    return format == vk::Format::eA2B10G10R10UnormPack32 || format == vk::Format::eA2R10G10B10UnormPack32;
}

bool Colour::ten_bit() const noexcept { return gpu::ten_bit(format); }

std::string colour_name(vk::Format format) {
    switch (format) {
    case vk::Format::eA2B10G10R10UnormPack32: return "rgb10a2";
    case vk::Format::eA2R10G10B10UnormPack32: return "bgr10a2";
    case vk::Format::eR8G8B8A8Unorm: return "rgba8";
    case vk::Format::eB8G8R8A8Unorm: return "bgra8";
    default: return vk::to_string(format);
    }
}

const ColourFormat* Facts::colour_format(vk::Format format) const noexcept {
    const auto it = std::ranges::find(colour_formats, format, &ColourFormat::format);
    return it == colour_formats.end() ? nullptr : &*it;
}

std::string Facts::unfit_for_frame(vk::Format format) const {
    const ColourFormat* c = colour_format(format);
    if (c == nullptr) return "not one of the frame's colour formats";
    if (!c->lacks.empty()) return "no " + c->lacks;
    // Every sample count of a configuration the device can draw (D5.3).
    vk::SampleCountFlags needed = vk::SampleCountFlagBits::e1 | vk::SampleCountFlagBits::e4;
    if (supports(FrameConfig::b)) needed |= vk::SampleCountFlagBits::e2;
    std::string counts;
    for (const auto n : {vk::SampleCountFlagBits::e1, vk::SampleCountFlagBits::e2, vk::SampleCountFlagBits::e4}) {
        if ((needed & n) && !(c->samples & n))
            counts += std::format("{}{}x", counts.empty() ? "" : ", ", static_cast<int>(n));
    }
    if (!counts.empty()) return std::format("no {} samples as a transient input attachment", counts);
    if (lazy_memory && c->lazy_checked && !c->lazy)
        return "lazily allocated memory cannot hold its transient samples (configuration A)";
    return {};
}

Colour colour_for_surface(const Facts& facts, std::span<const vk::SurfaceFormatKHR> offered, Depth depth) {
    if (depth == Depth::eight) return eight_bit(offered, std::string(eight_bits));
    if (!facts.ten_bit_off.empty()) return eight_bit(offered, std::string(ten_bits_off) + facts.ten_bit_off);
    std::string refused;
    for (const vk::Format format : {vk::Format::eA2B10G10R10UnormPack32, vk::Format::eA2R10G10B10UnormPack32}) {
        const bool offers = std::ranges::any_of(offered, [&](const vk::SurfaceFormatKHR& f) {
            return f.format == format && f.colorSpace == vk::ColorSpaceKHR::eSrgbNonlinear;
        });
        if (!offers) continue;
        if (const std::string unfit = facts.unfit_for_frame(format); !unfit.empty()) {
            refused += std::format("{}{} ({})", refused.empty() ? "" : ", ", colour_name(format), unfit);
            continue;
        }
        return {.format = format,
                .space = vk::ColorSpaceKHR::eSrgbNonlinear,
                .reason = "the surface offers it in sRGB and the frame's attachments follow it"};
    }
    if (!refused.empty())
        return eight_bit(offered, "the surface's 10-bit sRGB formats are of no use to the frame: " + refused);
    const bool ten_elsewhere =
        std::ranges::any_of(offered, [](const vk::SurfaceFormatKHR& f) { return ten_bit(f.format); });
    return eight_bit(offered, ten_elsewhere ? "the surface offers 10 bits only outside sRGB (the gamut stays sRGB)"
                                            : "the surface offers no 10-bit format");
}

Colour colour_offscreen(const Facts& facts, Depth depth) {
    if (depth == Depth::eight) return {.reason = std::format("offscreen: {}", eight_bits)};
    if (!facts.ten_bit_off.empty()) return {.reason = std::format("offscreen: {}{}", ten_bits_off, facts.ten_bit_off)};
    std::string refused;
    for (const vk::Format format : {vk::Format::eA2B10G10R10UnormPack32, vk::Format::eA2R10G10B10UnormPack32}) {
        if (const std::string unfit = facts.unfit_for_frame(format); !unfit.empty()) {
            refused += std::format("{}{} ({})", refused.empty() ? "" : ", ", colour_name(format), unfit);
            continue;
        }
        return {.format = format,
                .space = vk::ColorSpaceKHR::eSrgbNonlinear,
                .reason = "offscreen: the device draws the frame in 10 bits"};
    }
    return {.reason = "offscreen: the device cannot draw the frame in 10 bits: " + refused};
}

void decide_colour(Facts& facts) {
    if (facts.surface.formats.empty()) {
        facts.colour = colour_offscreen(facts, default_depth);
        facts.deepest = colour_offscreen(facts, Depth::deepest);
    } else {
        facts.colour = colour_for_surface(facts, facts.surface.formats, default_depth);
        facts.deepest = colour_for_surface(facts, facts.surface.formats, Depth::deepest);
    }
}

std::string colour_stage(const Facts& facts) {
    if (!facts.ten_bit_off.empty()) return std::format("off ({}{})", ten_bits_off, facts.ten_bit_off);
    const Colour& d = facts.deepest;
    if (!d.ten_bit()) return std::format("none ({})", d.reason);
    return std::format("{} ({} in {}: {})", colour_name(d.format), vk::to_string(d.format), vk::to_string(d.space),
                       d.reason);
}

std::string Facts::driver_key() const {
    return std::format("vendor 0x{:04x} device 0x{:04x} driver {} version 0x{:08x}", vendor_id, device_id,
                       driver_properties ? static_cast<std::uint32_t>(driver_id) : 0U, driver_version);
}

std::string_view vendor_name(std::uint32_t vendor_id) noexcept {
    switch (vendor_id) {
    case 0x13B5: return "ARM";
    case 0x5143: return "Qualcomm";
    case 0x1010: return "Imagination";
    case 0x144D: return "Samsung";
    case 0x1002: return "AMD";
    case 0x10DE: return "NVIDIA";
    case 0x8086: return "Intel";
    case 0x106B: return "Apple";
    case 0x14E4: return "Broadcom";
    case 0x1AE0: return "Google";
    case 0x10002: return "VeriSilicon";
    case 0x10005: return "Mesa";
    case 0x19E5: return "Huawei";
    default: return "unknown";
    }
}

TilerVerdict classify_tiler(std::uint32_t vendor_id, bool has_driver_id, vk::DriverId driver_id,
                            vk::PhysicalDeviceType type) noexcept {
    if (type == vk::PhysicalDeviceType::eCpu) return {.tiler = false, .reason = "a CPU implementation"};
    if (has_driver_id) {
        switch (driver_id) {
        case vk::DriverId::eArmProprietary:
        case vk::DriverId::eMesaPanvk: return {.tiler = true, .reason = "Arm Mali draws in tiles"};
        case vk::DriverId::eQualcommProprietary:
        case vk::DriverId::eMesaTurnip: return {.tiler = true, .reason = "Qualcomm Adreno draws in bins"};
        case vk::DriverId::eImaginationProprietary:
        case vk::DriverId::eImaginationOpenSourceMESA:
            return {.tiler = true, .reason = "Imagination PowerVR draws in tiles"};
        case vk::DriverId::eBroadcomProprietary:
        case vk::DriverId::eMesaV3Dv: return {.tiler = true, .reason = "Broadcom VideoCore draws in tiles"};
        case vk::DriverId::eMoltenvk:
        case vk::DriverId::eMesaHoneykrisp: return {.tiler = true, .reason = "Apple GPUs draw in tiles"};
        case vk::DriverId::eSamsungProprietary:
            return {.tiler = false, .reason = "Samsung Xclipse is AMD RDNA, not a tiler"};
        case vk::DriverId::eAmdProprietary:
        case vk::DriverId::eAmdOpenSource:
        case vk::DriverId::eMesaRadv: return {.tiler = false, .reason = "AMD GPUs are not tilers"};
        case vk::DriverId::eNvidiaProprietary:
        case vk::DriverId::eMesaNvk: return {.tiler = false, .reason = "NVIDIA GPUs are not tilers"};
        case vk::DriverId::eIntelProprietaryWindows:
        case vk::DriverId::eIntelOpenSourceMESA: return {.tiler = false, .reason = "Intel GPUs are not tilers"};
        case vk::DriverId::eMesaLlvmpipe:
        case vk::DriverId::eGoogleSwiftshader: return {.tiler = false, .reason = "a CPU implementation"};
        default: break;
        }
    }
    switch (vendor_id) {
    case 0x13B5: return {.tiler = true, .reason = "Arm Mali draws in tiles (by vendor)"};
    case 0x5143: return {.tiler = true, .reason = "Qualcomm Adreno draws in bins (by vendor)"};
    case 0x1010: return {.tiler = true, .reason = "Imagination PowerVR draws in tiles (by vendor)"};
    case 0x14E4: return {.tiler = true, .reason = "Broadcom VideoCore draws in tiles (by vendor)"};
    case 0x106B: return {.tiler = true, .reason = "Apple GPUs draw in tiles (by vendor)"};
    case 0x144D: return {.tiler = false, .reason = "Samsung Xclipse is AMD RDNA, not a tiler (by vendor)"};
    default: return {.tiler = false, .reason = "not known to draw in tiles"};
    }
}

void choose_configuration(Facts& facts, bool msaa2_close) {
    const QuirkSet quirks =
        quirks_for(facts.vendor_id, facts.driver_properties, facts.driver_id, facts.device_id, facts.driver_version);
    facts.quirks = quirks.text;
    Configuration& c = facts.choice;
    // The configuration (D5.3).
    if (quirks.config && facts.supports(*quirks.config)) {
        c.config = *quirks.config;
        facts.config_reason = "the quirk table: " + quirks.text;
    } else if (facts.tiler && facts.supports(FrameConfig::a)) {
        c.config = FrameConfig::a;
        facts.config_reason = "a tiler with lazily allocated memory: 4x on chip, no memory";
    } else {
        const std::string why = !facts.tiler ? std::string("not a tiler") : std::string("no lazily allocated memory");
        if (msaa2_close && facts.supports(FrameConfig::b)) {
            c.config = FrameConfig::b;
            facts.config_reason = why + ", and 2x is within 0.15 MAD of 4x (K6)";
        } else {
            c.config = FrameConfig::c;
            facts.config_reason =
                why + (facts.supports(FrameConfig::b) ? ", and K6 has not shown 2x within 0.15 MAD of 4x"
                                                      : ", and the device has no 2x samples");
        }
    }
    // The route (T1): dynamic rendering with local reads where the device has them; but a GPU that is not a tiler draws
    // its multisampled frames through the render pass, whose composite writes the 1x image straight from the samples
    // (D5.3's configurations B and C), which one dynamic rendering scope cannot (it keeps one sample count).
    const bool dynamic_ok = facts.features.dynamic_rendering && facts.features.local_read;
    const bool direct_wanted = !facts.tiler && c.samples() != vk::SampleCountFlagBits::e1;
    if (quirks.route && (*quirks.route == Route::renderpass || dynamic_ok)) {
        c.route = *quirks.route;
        facts.route_reason = "the quirk table: " + quirks.text;
    } else if (dynamic_ok && !direct_wanted) {
        c.route = Route::dynamic;
        facts.route_reason = "dynamic rendering with local reads";
    } else {
        c.route = Route::renderpass;
        facts.route_reason = direct_wanted
                                 ? "not a tiler: a render pass with two subpasses, the composite writing the 1x image"
                             : facts.features.dynamic_rendering
                                 ? "dynamic rendering without local reads: a render pass with two subpasses"
                                 : "no dynamic rendering: a render pass with two subpasses";
    }
    // The form (ONEPASS only by the quirk table, from A0's bench).
    c.form = quirks.form.value_or(FrameForm::composite);
    // The composite's target.
    if (c.samples() == vk::SampleCountFlagBits::e1) {
        c.target = CompositeTarget::direct;
        facts.target_reason = "one sample: the composite writes the image";
    } else if (facts.tiler) {
        c.target = CompositeTarget::resolve;
        facts.target_reason = "the samples, resolved on chip";
    } else if (c.route == Route::dynamic) {
        c.target = CompositeTarget::resolve;
        facts.target_reason = "the samples, resolved (dynamic rendering keeps one sample count)";
    } else {
        c.target = CompositeTarget::direct;
        facts.target_reason = "the 1x image straight from the samples (not a tiler)";
    }
}

Facts collect_facts(const Instance& instance, const vk::raii::PhysicalDevice& device, std::size_t device_count,
                    bool present) {
    Facts facts;
    facts.loader_path = instance.loader_path();
    facts.loader_version = instance.loader_version();
    facts.instance_version = instance.api_version();
    facts.validation = instance.validation();
    facts.devices = device_count;
    facts.hidden = instance.hidden();

    const vk::PhysicalDeviceProperties properties = device.getProperties();
    facts.name = properties.deviceName.data();
    facts.type = properties.deviceType;
    facts.vendor_id = properties.vendorID;
    facts.device_id = properties.deviceID;
    facts.api_version = properties.apiVersion;
    facts.driver_version = properties.driverVersion;
    facts.limits = properties.limits;
    std::ranges::copy(properties.pipelineCacheUUID, facts.pipeline_cache_uuid.begin());

    if (auto list = device.enumerateDeviceExtensionProperties()) {
        for (const vk::ExtensionProperties& e : *list) {
            if (!instance.hides(e.extensionName.data())) facts.extensions.emplace_back(e.extensionName.data());
        }
    }
    std::ranges::sort(facts.extensions);

    const std::uint32_t version = effective_version(facts.api_version, facts.instance_version);
    if (version >= VK_API_VERSION_1_2 || facts.has_extension(vk::KHRDriverPropertiesExtensionName)) {
        vk::PhysicalDeviceDriverProperties driver{};
        vk::PhysicalDeviceIDProperties ids{.pNext = &driver};
        vk::PhysicalDeviceProperties2 properties2{.pNext = &ids};
        device.getDispatcher()->vkGetPhysicalDeviceProperties2(
            *device, reinterpret_cast<VkPhysicalDeviceProperties2*>(&properties2)); // NOLINT
        facts.driver_properties = true;
        facts.driver_id = driver.driverID;
        facts.driver_name = driver.driverName.data();
        facts.driver_info = driver.driverInfo.data();
        facts.conformance = driver.conformanceVersion;
        std::ranges::copy(ids.driverUUID, facts.driver_uuid.begin());
    }

    facts.memory = device.getMemoryProperties();
    for (std::uint32_t i = 0; i < facts.memory.memoryTypeCount; ++i) {
        const vk::MemoryPropertyFlags flags = facts.memory.memoryTypes[i].propertyFlags;
        if (flags & vk::MemoryPropertyFlagBits::eLazilyAllocated) facts.lazy_memory = true;
        if ((flags & vk::MemoryPropertyFlagBits::eDeviceLocal) && (flags & vk::MemoryPropertyFlagBits::eHostVisible)) {
            facts.host_visible_device_local = true;
        }
    }
    facts.queues = device.getQueueFamilyProperties();

    auto attachment = [&](vk::Format format, vk::FormatFeatureFlags need) {
        return (device.getFormatProperties(format).optimalTilingFeatures & need) == need;
    };
    facts.d24s8 = attachment(vk::Format::eD24UnormS8Uint, vk::FormatFeatureFlagBits::eDepthStencilAttachment);
    facts.d32s8 = attachment(vk::Format::eD32SfloatS8Uint, vk::FormatFeatureFlagBits::eDepthStencilAttachment);
    facts.depth_stencil = facts.d24s8   ? vk::Format::eD24UnormS8Uint
                          : facts.d32s8 ? vk::Format::eD32SfloatS8Uint
                                        : vk::Format::eUndefined;
    facts.rgba8_input = attachment(vk::Format::eR8G8B8A8Unorm, vk::FormatFeatureFlagBits::eColorAttachment |
                                                                   vk::FormatFeatureFlagBits::eColorAttachmentBlend);
    read_colour_formats(facts, device);

    read_features(facts, device, version);
    read_profiles(facts, instance, device);

    const TilerVerdict tiler = classify_tiler(facts.vendor_id, facts.driver_properties, facts.driver_id, facts.type);
    facts.tiler = tiler.tiler;
    facts.tiler_reason = std::string(tiler.reason);
    facts.hardware = facts.type != vk::PhysicalDeviceType::eCpu &&
                     (!facts.driver_properties || (facts.driver_id != vk::DriverId::eMesaLlvmpipe &&
                                                   facts.driver_id != vk::DriverId::eGoogleSwiftshader));
    check_requirements(facts, present);
    choose_configuration(facts, false);
    decide_colour(facts);
    return facts;
}

void collect_surface(Facts& facts, const vk::raii::PhysicalDevice& device, vk::SurfaceKHR surface,
                     std::uint32_t queue_family) {
    SurfaceFacts& s = facts.surface;
    s = {};
    if (auto ok = device.getSurfaceSupportKHR(queue_family, surface)) s.present = *ok == vk::True;
    if (auto caps = device.getSurfaceCapabilitiesKHR(surface)) s.capabilities = *caps;
    if (auto formats = device.getSurfaceFormatsKHR(surface)) s.formats = std::move(*formats);
    if (auto modes = device.getSurfacePresentModesKHR(surface)) s.present_modes = std::move(*modes);
    decide_colour(facts);
}

std::vector<std::string> facts_lines(const Facts& f) {
    std::vector<std::string> out;
    auto line = [&out]<class... Args>(std::format_string<Args...> format, Args&&... args) {
        out.push_back(std::format(format, std::forward<Args>(args)...));
    };
    line("gpu: {} ({}; {} listed)", f.name, type_name(f.type), f.devices);
    line("vendor: 0x{:04x} {}, device 0x{:04x}", f.vendor_id, vendor_name(f.vendor_id), f.device_id);
    line("api: {} (device), instance {}, loader {}", version_text(f.api_version), version_text(f.instance_version),
         version_text(f.loader_version));
    if (f.driver_properties) {
        line("driver: {} ({}), id {} ({})", f.driver_name, f.driver_info, vk::to_string(f.driver_id),
             static_cast<int>(f.driver_id));
        line("conformance: {}.{}.{}.{}", f.conformance.major, f.conformance.minor, f.conformance.subminor,
             f.conformance.patch);
    } else {
        line("driver: no driver properties (Vulkan 1.1 without VK_KHR_driver_properties)");
    }
    line("driver.version: 0x{:08x} (as Vulkan's major.minor.patch: {})", f.driver_version,
         version_text(f.driver_version));
    line("pipeline.cache.uuid: {}", uuid_text(f.pipeline_cache_uuid));
    line("hardware: {}",
         f.hardware ? "yes" : "no (a CPU implementation: Android's design notes advise against software Vulkan)");
    line("tiler: {} ({})", yes_no(f.tiler), f.tiler_reason);
    line("lazily.allocated.memory: {}", yes_no(f.lazy_memory));
    line("host.visible.device.local: {}", yes_no(f.host_visible_device_local));
    // A device for offscreen work says so (it needs no VK_KHR_swapchain); the phone's device presents.
    line(
        "requirements: {}{}", f.missing.empty() ? std::string("all met") : [&] {
            std::string m;
            for (const std::string& s : f.missing) m += (m.empty() ? "missing " : ", ") + s;
            return m;
        }(),
        f.presents ? "" : " (offscreen)");
    line("config: {} ({})", name_of(f.choice.config), f.config_reason);
    line("route: {} ({})", name_of(f.choice.route), f.route_reason);
    line("composite: {} ({})", name_of(f.choice.target), f.target_reason);
    line("form: {}", name_of(f.choice.form));
    // The frame's colour, and what becomes of 10 bits: drawn by VK bench's colour stage only, off after a crash there,
    // or not to be had; `colour.stage:` says the same for VK bench to read.
    std::string ten;
    if (!f.ten_bit_off.empty()) {
        ten = std::format("off, {}{}", ten_bits_off, f.ten_bit_off);
    } else if (f.deepest.ten_bit()) {
        ten = std::format("{}, drawn only in VK bench's colour stage", colour_name(f.deepest.format));
    } else {
        ten = std::format("none, {}", f.deepest.reason);
    }
    line("colour: {} ({} in {}: {}; 10 bits: {})", colour_name(f.colour.format), vk::to_string(f.colour.format),
         vk::to_string(f.colour.space), f.colour.reason, ten);
    line("colour.stage: {}", colour_stage(f));
    std::string drawable;
    for (const ColourFormat& c : f.colour_formats) {
        const std::string unfit = f.unfit_for_frame(c.format);
        // A 10-bit format's lazily allocated samples are asked of the device only in VK bench's colour stage.
        const bool unasked = unfit.empty() && f.lazy_memory && !c.lazy_checked && ten_bit(c.format);
        drawable += std::format("{}{} {}", drawable.empty() ? "" : ", ", colour_name(c.format),
                                !unfit.empty() ? "no (" + unfit + ")"
                                : unasked ? std::string("yes (its lazily allocated samples asked in the colour stage)")
                                          : std::string("yes"));
    }
    line("colour.formats: {} (the frame drawn in each)", drawable);
    line("configs.available: A {}, B {}, C {}, 1x {}", yes_no(f.supports(FrameConfig::a)),
         yes_no(f.supports(FrameConfig::b)), yes_no(f.supports(FrameConfig::c)), yes_no(f.supports(FrameConfig::x1)));
    line("depth.stencil: {} (D24S8 {}, D32S8 {})",
         f.depth_stencil == vk::Format::eD24UnormS8Uint    ? "d24s8"
         : f.depth_stencil == vk::Format::eD32SfloatS8Uint ? "d32s8"
                                                           : "none",
         yes_no(f.d24s8), yes_no(f.d32s8));
    line("samples: colour {}, depth {}, stencil {}, sampled colour {}, standard locations {}",
         sample_counts(f.limits.framebufferColorSampleCounts), sample_counts(f.limits.framebufferDepthSampleCounts),
         sample_counts(f.limits.framebufferStencilSampleCounts), sample_counts(f.limits.sampledImageColorSampleCounts),
         yes_no(f.limits.standardSampleLocations == vk::True));
    line("quirks: {} ({} rows in the table)", f.quirks, quirk_rows());
    std::string profiles;
    for (const auto& [name, ok] : f.profiles)
        profiles += std::format("{}{} {}", profiles.empty() ? "" : ", ", name, yes_no(ok));
    line("profiles: {}", profiles.empty() ? f.profiles_status : profiles);
    line("validation: {}", f.validation ? "on (synchronization validation)" : "off");
    line("loader: {}", f.loader_path);
    if (!f.hidden.empty()) {
        std::string names;
        for (const std::string& h : f.hidden) names += (names.empty() ? "" : ", ") + h;
        line("hidden: {} (by the host tests: they read as absent)", names);
    }

    const FeatureFacts& x = f.features;
    line("feature.dynamicRendering: {}", yes_no(x.dynamic_rendering));
    line("feature.dynamicRenderingLocalRead: {}", yes_no(x.local_read));
    line("feature.synchronization2: {}", yes_no(x.synchronization2));
    line("feature.timelineSemaphore: {}", yes_no(x.timeline_semaphore));
    line("feature.shaderFmaFloat32: {}", yes_no(x.shader_fma32));
    line("feature.swapchainMaintenance1: {}", yes_no(x.swapchain_maintenance1));
    line("feature.pipelineCreationCacheControl: {}", yes_no(x.pipeline_cache_control));
    line("feature.shaderClipDistance: {}", yes_no(x.clip_distance));
    line("feature.sampleRateShading: {}", yes_no(x.sample_rate_shading));
    line("feature.shaderStorageImageReadWithoutFormat: {} (Slang's subpass loads: {})", yes_no(x.read_without_format),
         f.takes_read_without_format() ? "Slang's own SPIR-V" : "Slang's GLSL route");
    line("feature.textureCompressionASTC_LDR: {}", yes_no(x.astc_ldr));
    line("feature.textureCompressionETC2: {}", yes_no(x.etc2));
    line("feature.pipelineStatisticsQuery: {}", yes_no(x.pipeline_statistics));
    line("feature.dualSrcBlend: {}", yes_no(x.dual_src_blend));
    line("feature.shaderInt16: {}", yes_no(x.shader_int16));
    line("feature.shaderInt64: {}", yes_no(x.shader_int64));
    line("feature.shaderFloat64: {} (K5's light: {})", yes_no(x.shader_float64),
         x.shader_float64 ? "binary64, the port's bits (E0), where its probe finds binary64 exact"
                          : "binary32 (E1), within 6e-4 of a level of the port's");
    line("feature.shaderDrawParameters: {}", yes_no(x.draw_parameters));
    line("feature.independentBlend: {}", yes_no(x.independent_blend));
    line("feature.fragmentStoresAndAtomics: {}", yes_no(x.fragment_stores));
    line("feature.hostQueryReset: {}", yes_no(x.host_query_reset));
    line("feature.separateDepthStencilLayouts: {}", yes_no(x.separate_depth_stencil_layouts));
    line("feature.maintenance4: {}", yes_no(x.maintenance4));
    line("feature.rasterizationOrderColorAttachmentAccess: {}", yes_no(x.rasterization_order));
    line("feature.multisampledRenderToSingleSampled: {}", yes_no(x.multisampled_to_single));

    for (const char* name : {vk::KHRDynamicRenderingExtensionName,
                             vk::KHRDynamicRenderingLocalReadExtensionName,
                             vk::KHRSynchronization2ExtensionName,
                             vk::KHRCreateRenderpass2ExtensionName,
                             vk::KHRShaderFmaExtensionName,
                             vk::GOOGLEDisplayTimingExtensionName,
                             vk::KHRIncrementalPresentExtensionName,
                             vk::KHRSwapchainMaintenance1ExtensionName,
                             vk::EXTSwapchainMaintenance1ExtensionName,
                             vk::KHRDriverPropertiesExtensionName,
                             vk::KHRCalibratedTimestampsExtensionName,
                             vk::EXTCalibratedTimestampsExtensionName,
                             vk::EXTMemoryBudgetExtensionName,
                             vk::EXTPipelineCreationCacheControlExtensionName,
                             vk::EXTMultisampledRenderToSingleSampledExtensionName,
                             vk::EXTRasterizationOrderAttachmentAccessExtensionName,
                             vk::ARMRasterizationOrderAttachmentAccessExtensionName,
                             vk::EXTShaderObjectExtensionName,
                             vk::EXTGraphicsPipelineLibraryExtensionName,
                             vk::KHRPipelineBinaryExtensionName,
                             vk::KHRPresentIdExtensionName,
                             vk::KHRPresentWaitExtensionName,
                             vk::KHRPresentId2ExtensionName,
                             vk::KHRPresentWait2ExtensionName,
                             vk::ANDROIDExternalMemoryAndroidHardwareBufferExtensionName,
                             vk::KHRSwapchainExtensionName}) {
        line("ext.{}: {}", name, yes_no(f.has_extension(name)));
    }

    for (std::uint32_t i = 0; i < f.memory.memoryHeapCount; ++i) {
        const vk::MemoryHeap& h = f.memory.memoryHeaps[i];
        line("memory.heap{}: {} MB{}", i, h.size / 1'000'000,
             (h.flags & vk::MemoryHeapFlagBits::eDeviceLocal) ? " device-local" : "");
    }
    for (std::uint32_t i = 0; i < f.memory.memoryTypeCount; ++i) {
        const vk::MemoryType& t = f.memory.memoryTypes[i];
        line("memory.type{}: heap {}, {}", i, t.heapIndex, memory_flags(t.propertyFlags));
    }
    for (std::size_t i = 0; i < f.queues.size(); ++i) {
        const vk::QueueFamilyProperties& q = f.queues[i];
        line("queue{}: {}, {} queue{}, timestamps {} bits", i, queue_flags(q.queueFlags), q.queueCount,
             q.queueCount == 1 ? "" : "s", q.timestampValidBits);
    }
    const vk::PhysicalDeviceLimits& l = f.limits;
    line("limit.timestampPeriod: {} ns, compute and graphics {}", l.timestampPeriod,
         yes_no(l.timestampComputeAndGraphics == vk::True));
    line("limit.maxImageDimension2D: {}", l.maxImageDimension2D);
    line("limit.maxFramebuffer: {} x {}", l.maxFramebufferWidth, l.maxFramebufferHeight);
    line("limit.maxColorAttachments: {}", l.maxColorAttachments);
    line("limit.maxPerStageDescriptorInputAttachments: {}", l.maxPerStageDescriptorInputAttachments);
    line("limit.maxPushConstantsSize: {}", l.maxPushConstantsSize);
    line("limit.maxStorageBufferRange: {}", l.maxStorageBufferRange);
    line("limit.maxBoundDescriptorSets: {}", l.maxBoundDescriptorSets);
    line("limit.maxComputeSharedMemorySize: {}", l.maxComputeSharedMemorySize);
    line("limit.maxComputeWorkGroupInvocations: {}", l.maxComputeWorkGroupInvocations);
    line("limit.maxSamplerAnisotropy: {}", l.maxSamplerAnisotropy);
    line("limit.subPixelPrecisionBits: {}", l.subPixelPrecisionBits);
    line("limit.maxMemoryAllocationCount: {}", l.maxMemoryAllocationCount);
    line("limit.bufferImageGranularity: {}", l.bufferImageGranularity);
    line("limit.nonCoherentAtomSize: {}", l.nonCoherentAtomSize);
    line("limit.minUniformBufferOffsetAlignment: {}", l.minUniformBufferOffsetAlignment);
    line("limit.minStorageBufferOffsetAlignment: {}", l.minStorageBufferOffsetAlignment);

    if (f.surface.present || !f.surface.formats.empty()) {
        const vk::SurfaceCapabilitiesKHR& c = f.surface.capabilities;
        line("surface.present: {}", yes_no(f.surface.present));
        line("surface.images: min {}, max {}", c.minImageCount, c.maxImageCount);
        line("surface.extent: {} x {}", c.currentExtent.width, c.currentExtent.height);
        line("surface.transform: {} (supported {})", vk::to_string(c.currentTransform),
             vk::to_string(c.supportedTransforms));
        line("surface.usage: {}", vk::to_string(c.supportedUsageFlags));
        line("surface.alpha: {}", vk::to_string(c.supportedCompositeAlpha));
        std::string formats;
        for (const vk::SurfaceFormatKHR& s : f.surface.formats) {
            formats += std::format("{}{} {}", formats.empty() ? "" : ", ", vk::to_string(s.format),
                                   vk::to_string(s.colorSpace));
        }
        line("surface.formats: {}", formats);
        std::string modes;
        for (const vk::PresentModeKHR m : f.surface.present_modes)
            modes += std::format("{}{}", modes.empty() ? "" : ", ", vk::to_string(m));
        line("surface.present.modes: {}", modes);
    }

    line("extensions: {}", f.extensions.size());
    // Every extension, a few to a line, so a paste carries them all and no line is cut.
    std::string chunk;
    std::size_t part = 0;
    for (const std::string& e : f.extensions) {
        if (chunk.size() + e.size() > 700) {
            line("extensions.{}: {}", part++, chunk);
            chunk.clear();
        }
        chunk += (chunk.empty() ? "" : " ") + e;
    }
    if (!chunk.empty()) line("extensions.{}: {}", part, chunk);
    return out;
}

} // namespace atlas::gpu
