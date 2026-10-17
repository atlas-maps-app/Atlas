// The device: choosing it, creating it with what the frame may use (gpu/Device.hpp).
#include "gpu/Device.hpp"

#include <algorithm>
#include <format>
#include <string_view>
#include <tuple>
#include <vector>

namespace atlas::gpu {

namespace {

/// How a candidate ranks: the requirements met first, then a hardware GPU, then the newer API.
[[nodiscard]] std::tuple<bool, bool, std::uint32_t> rank_of(const Facts& f) noexcept {
    return {f.missing.empty(), f.hardware, f.api_version};
}

/// The first queue family that draws and computes, and presents to `surface` when there is one.
[[nodiscard]] std::optional<std::uint32_t> pick_queue(const vk::raii::PhysicalDevice& device, const Facts& facts,
                                                      vk::SurfaceKHR surface) {
    for (std::uint32_t i = 0; i < facts.queues.size(); ++i) {
        const vk::QueueFlags flags = facts.queues[i].queueFlags;
        if (!(flags & vk::QueueFlagBits::eGraphics) || !(flags & vk::QueueFlagBits::eCompute)) continue;
        if (surface) {
            auto ok = device.getSurfaceSupportKHR(i, surface);
            if (!ok || *ok != vk::True) continue;
        }
        return i;
    }
    return std::nullopt;
}

} // namespace

Device::~Device() {
    memory_.reset();
    queue_.clear();
    device_.clear();
}

bool Device::can_present(vk::SurfaceKHR surface) const {
    auto ok = physical_.getSurfaceSupportKHR(queue_family_, surface);
    return ok && *ok == vk::True;
}

void Device::wait_idle() const { std::ignore = device_.waitIdle(); } // a lost device has nothing left to wait for

Result<Picked> Device::pick(Instance& instance, const DeviceOptions& options) {
    ATLAS_VK_LET(devices, instance.handle().enumeratePhysicalDevices(), "vkEnumeratePhysicalDevices");
    if (devices.empty()) return fail("the Vulkan loader lists no device");
    std::size_t best = 0;
    std::vector<Facts> candidates;
    candidates.reserve(devices.size());
    for (std::size_t i = 0; i < devices.size(); ++i) {
        candidates.push_back(collect_facts(instance, devices[i], devices.size()));
        if (options.surface && !pick_queue(devices[i], candidates.back(), options.surface)) {
            candidates.back().missing.emplace_back("a queue that presents to the surface");
        }
        if (rank_of(candidates[i]) > rank_of(candidates[best])) best = i;
    }
    Picked picked;
    picked.facts = std::move(candidates[best]);
    if (options.msaa2_close) choose_configuration(picked.facts, true);
    picked.physical = std::move(devices[best]);
    return picked;
}

Result<std::unique_ptr<Device>> Device::open(Instance& instance, const DeviceOptions& options) {
    ATLAS_GPU_LET(picked, pick(instance, options));
    return open(instance, std::move(picked), options);
}

Result<std::unique_ptr<Device>> Device::open(Instance& instance, Picked picked, const DeviceOptions& options) {
    std::unique_ptr<Device> self(new Device()); // NOLINT(cppcoreguidelines-owning-memory): private constructor
    self->instance_ = &instance;
    Facts& facts = picked.facts;
    if (!facts.missing.empty()) {
        std::string missing;
        for (const std::string& m : facts.missing) missing += (missing.empty() ? "" : ", ") + m;
        return fail(std::format("{} lacks what the core needs: {}", facts.name, missing));
    }
    self->physical_ = std::move(picked.physical);
    const vk::raii::PhysicalDevice& physical = self->physical_;
    self->version_ = std::min(facts.api_version, instance.api_version());
    const std::uint32_t version = self->version_;

    const std::optional<std::uint32_t> family = pick_queue(physical, facts, options.surface);
    if (!family) return fail(facts.name + " has no queue that draws and computes");
    self->queue_family_ = *family;
    self->timestamp_bits_ = facts.queues[*family].timestampValidBits;

    // The extensions: the ones that are core at the device's version are not named; their features are.
    Enabled& on = self->enabled_;
    std::vector<const char*> extensions;
    auto enable = [&](const char* name) {
        if (!facts.has_extension(name)) return false;
        if (std::ranges::find(extensions, std::string_view(name)) == extensions.end()) extensions.push_back(name);
        return true;
    };
    on.swapchain = enable(vk::KHRSwapchainExtensionName);
    if (version < VK_API_VERSION_1_3) (void)enable(vk::KHRFormatFeatureFlags2ExtensionName);
    if (version < VK_API_VERSION_1_2) {
        on.create_renderpass2 = enable(vk::KHRCreateRenderpass2ExtensionName);
        (void)enable(vk::KHRDriverPropertiesExtensionName);
    } else {
        on.create_renderpass2 = true;
    }
    const FeatureFacts& has = facts.features;
    if (version < VK_API_VERSION_1_3) {
        // VK_KHR_dynamic_rendering needs VK_KHR_depth_stencil_resolve, which needs VK_KHR_create_renderpass2.
        if (has.dynamic_rendering && on.create_renderpass2 &&
            (version >= VK_API_VERSION_1_2 || enable(vk::KHRDepthStencilResolveExtensionName))) {
            on.dynamic_rendering = enable(vk::KHRDynamicRenderingExtensionName);
        }
        if (has.synchronization2) on.synchronization2 = enable(vk::KHRSynchronization2ExtensionName);
        if (has.pipeline_cache_control)
            on.pipeline_cache_control = enable(vk::EXTPipelineCreationCacheControlExtensionName);
    } else {
        on.dynamic_rendering = has.dynamic_rendering;
        on.synchronization2 = has.synchronization2;
        on.pipeline_cache_control = has.pipeline_cache_control;
    }
    if (version < VK_API_VERSION_1_4) {
        if (on.dynamic_rendering && has.local_read)
            on.local_read = enable(vk::KHRDynamicRenderingLocalReadExtensionName);
    } else {
        on.local_read = on.dynamic_rendering && has.local_read;
    }
    if (on.swapchain && has.swapchain_maintenance1) {
        const bool surface_khr = instance.has_extension(vk::KHRSurfaceMaintenance1ExtensionName);
        const bool surface_ext = instance.has_extension(vk::EXTSurfaceMaintenance1ExtensionName);
        if (surface_khr && facts.has_extension(vk::KHRSwapchainMaintenance1ExtensionName)) {
            on.swapchain_maintenance1 = enable(vk::KHRSwapchainMaintenance1ExtensionName);
        } else if (surface_ext && facts.has_extension(vk::EXTSwapchainMaintenance1ExtensionName)) {
            on.swapchain_maintenance1 = enable(vk::EXTSwapchainMaintenance1ExtensionName);
        }
    }
    if (on.swapchain) {
        on.display_timing = enable(vk::GOOGLEDisplayTimingExtensionName);
        on.incremental_present = enable(vk::KHRIncrementalPresentExtensionName);
    }
    on.calibrated_timestamps =
        enable(vk::KHRCalibratedTimestampsExtensionName) || enable(vk::EXTCalibratedTimestampsExtensionName);
    on.memory_budget = enable(vk::EXTMemoryBudgetExtensionName);
    if (has.shader_fma32) on.shader_fma = enable(vk::KHRShaderFmaExtensionName);
    on.pipeline_statistics = has.pipeline_statistics;
    on.independent_blend = has.independent_blend;

    // The features, through the structures of the device's version.
    vk::PhysicalDeviceFeatures2 features2{};
    features2.features.independentBlend = on.independent_blend ? vk::True : vk::False;
    features2.features.pipelineStatisticsQuery = on.pipeline_statistics ? vk::True : vk::False;
    features2.features.shaderStorageImageReadWithoutFormat = has.read_without_format ? vk::True : vk::False;
    void** tail = &features2.pNext;
    auto link = [&tail](auto& s) {
        *tail = &s;
        tail = &s.pNext;
    };
    vk::PhysicalDeviceVulkan13Features v13{};
    vk::PhysicalDeviceVulkan14Features v14{};
    vk::PhysicalDeviceDynamicRenderingFeatures dynamic{.dynamicRendering = vk::True};
    vk::PhysicalDeviceSynchronization2Features sync2{.synchronization2 = vk::True};
    vk::PhysicalDevicePipelineCreationCacheControlFeatures cache_control{.pipelineCreationCacheControl = vk::True};
    vk::PhysicalDeviceDynamicRenderingLocalReadFeatures local_read{.dynamicRenderingLocalRead = vk::True};
    vk::PhysicalDeviceShaderFmaFeaturesKHR fma{.shaderFmaFloat32 = vk::True};
    vk::PhysicalDeviceSwapchainMaintenance1FeaturesKHR swapchain1{.swapchainMaintenance1 = vk::True};
    if (version >= VK_API_VERSION_1_3) {
        v13.dynamicRendering = on.dynamic_rendering ? vk::True : vk::False;
        v13.synchronization2 = on.synchronization2 ? vk::True : vk::False;
        v13.pipelineCreationCacheControl = on.pipeline_cache_control ? vk::True : vk::False;
        link(v13);
    } else {
        if (on.dynamic_rendering) link(dynamic);
        if (on.synchronization2) link(sync2);
        if (on.pipeline_cache_control) link(cache_control);
    }
    if (version >= VK_API_VERSION_1_4) {
        v14.dynamicRenderingLocalRead = on.local_read ? vk::True : vk::False;
        link(v14);
    } else if (on.local_read) {
        link(local_read);
    }
    if (on.shader_fma) link(fma);
    if (on.swapchain_maintenance1) link(swapchain1);

    const float priority = 1.0F;
    const vk::DeviceQueueCreateInfo queue{.queueFamilyIndex = *family, .queueCount = 1, .pQueuePriorities = &priority};
    ATLAS_VK_LET(device,
                 physical.createDevice({.pNext = &features2,
                                        .queueCreateInfoCount = 1,
                                        .pQueueCreateInfos = &queue,
                                        .enabledExtensionCount = static_cast<std::uint32_t>(extensions.size()),
                                        .ppEnabledExtensionNames = extensions.data()}),
                 std::format("vkCreateDevice on {}", facts.name));
    self->device_ = std::move(device);
    self->queue_ = self->device_.getQueue(*family, 0);
    ATLAS_GPU_LET(memory, Memory::create(instance, physical, self->device_, version, on.memory_budget));
    self->memory_ = std::move(memory);
    self->facts_ = std::move(facts);
    // The choice may only use what was enabled.
    if (self->facts_.choice.route == Route::dynamic && !on.local_read) {
        self->facts_.choice.route = Route::renderpass;
        self->facts_.route_reason = "local reads could not be enabled: a render pass with two subpasses";
    }
    return self;
}

} // namespace atlas::gpu
