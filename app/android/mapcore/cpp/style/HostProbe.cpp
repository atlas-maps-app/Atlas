// light.slang's compute probes on a Vulkan device: one instance, one device, four storage buffers, one dispatch.
#include "style/HostProbe.hpp"

#include "gen/spirv/style/light_probe.hpp"     // shaders/style/light_probe.slang, embedded by the build
#include "gen/spirv/style/light_probe_f32.hpp" // shaders/style/light_probe_f32.slang

#if !defined(VK_NO_PROTOTYPES)
#define VK_NO_PROTOTYPES
#endif
#include <vulkan/vulkan.h>

#include <algorithm>
#include <array>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <filesystem>
#include <format>
#include <string_view>
#include <system_error>
#include <utility>

namespace atlas::style {

namespace {

#if !defined(__ANDROID__)
[[nodiscard]] std::string environment(const char* name) {
    // NOLINTNEXTLINE(concurrency-mt-unsafe): read on the probe's thread; nothing in the process writes it meanwhile
    const char* value = std::getenv(name);
    return value == nullptr ? std::string() : std::string(value);
}

/// Sets an environment variable the loader reads, unless it is set already.
void default_environment(const char* name, const std::string& value) {
    // NOLINTNEXTLINE(concurrency-mt-unsafe): only when the environment names no driver (atlas_tests, before threads)
    (void)::setenv(name, value.c_str(), 0);
}

/// The Android SDK's emulator package's Vulkan loader, with the loader pointed at its lavapipe and validation layer
/// unless the environment already names a driver or a layer path; empty without the package.
[[nodiscard]] std::string emulator_loader() {
    namespace fs = std::filesystem;
    std::vector<fs::path> sdks;
    for (const char* name : {"ANDROID_HOME", "ANDROID_SDK_ROOT"}) {
        if (std::string sdk = environment(name); !sdk.empty()) {
            sdks.emplace_back(std::move(sdk));
        }
    }
    if (const std::string home = environment("HOME"); !home.empty()) {
        sdks.push_back(fs::path(home) / "Library/Android/sdk");
        sdks.push_back(fs::path(home) / "Android/Sdk");
    }
    std::error_code ec;
    for (const fs::path& sdk : sdks) {
        const fs::path dir = sdk / "emulator/lib64/vulkan";
        if (!fs::exists(dir / "lvp_icd.json", ec)) {
            continue;
        }
        for (const char* name : {"libvulkan.dylib", "libvulkan.so.1", "libvulkan.so"}) {
            if (!fs::exists(dir / name, ec)) {
                continue;
            }
            if (environment("VK_DRIVER_FILES").empty() && environment("VK_ICD_FILENAMES").empty()) {
                default_environment("VK_DRIVER_FILES", (dir / "lvp_icd.json").string());
            }
            if (environment("VK_ADD_LAYER_PATH").empty() && environment("VK_LAYER_PATH").empty() &&
                fs::exists(dir / "layers", ec)) {
                default_environment("VK_ADD_LAYER_PATH", (dir / "layers").string());
            }
            return (dir / name).string();
        }
    }
    return {};
}
#endif

/// The loaders to try, in order.
[[nodiscard]] std::vector<std::string> loaders() {
#if defined(__ANDROID__)
    return {"libvulkan.so"};
#else
    if (std::string own = environment("ATLAS_VULKAN_LOADER"); !own.empty()) {
        return {own};
    }
    std::vector<std::string> paths;
    if (std::string emulator = emulator_loader(); !emulator.empty()) {
        paths.push_back(std::move(emulator));
    }
#if defined(__APPLE__)
    paths.emplace_back("libvulkan.1.dylib");
    paths.emplace_back("libvulkan.dylib");
#else
    paths.emplace_back("libvulkan.so.1");
    paths.emplace_back("libvulkan.so");
#endif
    return paths;
#endif
}

[[nodiscard]] ProbeError unavailable(std::string what) {
    return ProbeError{.unavailable = true, .what = std::move(what)};
}
[[nodiscard]] ProbeError failure(std::string what) { return ProbeError{.unavailable = false, .what = std::move(what)}; }
[[nodiscard]] ProbeError failed(std::string_view call, VkResult r) {
    return failure(std::format("{} failed ({})", call, static_cast<int>(r)));
}

/// The Vulkan loader, opened at run time and closed with the probe.
class Loader {
public:
    Loader() = default;
    Loader(const Loader&) = delete;
    Loader& operator=(const Loader&) = delete;
    Loader(Loader&&) = delete;
    Loader& operator=(Loader&&) = delete;
    ~Loader() {
        if (handle_ != nullptr) {
            ::dlclose(handle_);
        }
    }

    [[nodiscard]] std::expected<PFN_vkGetInstanceProcAddr, ProbeError> open() {
        std::string tried;
        for (const std::string& path : loaders()) {
            tried += (tried.empty() ? "" : ", ") + path;
            handle_ = ::dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
            if (handle_ != nullptr) {
                break;
            }
        }
        if (handle_ == nullptr) {
            return std::unexpected(unavailable("no Vulkan loader (tried " + tried + ")"));
        }
        auto* get = reinterpret_cast<PFN_vkGetInstanceProcAddr>(::dlsym(handle_, "vkGetInstanceProcAddr"));
        if (get == nullptr) {
            return std::unexpected(failure("the Vulkan loader has no vkGetInstanceProcAddr"));
        }
        return get;
    }

private:
    void* handle_ = nullptr;
};

/// What the validation layer said: any warning or error fails the run.
VKAPI_ATTR VkBool32 VKAPI_CALL on_message(VkDebugUtilsMessageSeverityFlagBitsEXT /*severity*/,
                                          VkDebugUtilsMessageTypeFlagsEXT /*type*/,
                                          const VkDebugUtilsMessengerCallbackDataEXT* data, void* user) {
    static_cast<std::vector<std::string>*>(user)->emplace_back(data->pMessage != nullptr ? data->pMessage
                                                                                         : "(no message)");
    return VK_FALSE;
}

/// Every handle a probe makes, destroyed in reverse order however the run ends.
class Session {
public:
    Session() = default;
    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;
    Session(Session&&) = delete;
    Session& operator=(Session&&) = delete;
    ~Session() { release(); }

    PFN_vkGetInstanceProcAddr gipa = nullptr;
    PFN_vkGetDeviceProcAddr gdpa = nullptr;
    VkInstance instance = VK_NULL_HANDLE;
    VkDebugUtilsMessengerEXT messenger = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    std::uint32_t family = 0;
    std::vector<VkBuffer> buffers;
    std::vector<VkDeviceMemory> memories;
    VkShaderModule module = VK_NULL_HANDLE;
    VkDescriptorSetLayout set_layout = VK_NULL_HANDLE;
    VkPipelineLayout pipeline_layout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkDescriptorPool pool = VK_NULL_HANDLE;
    VkCommandPool commands = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    bool validated = false;
    std::vector<std::string>* messages = nullptr; // the validation layer's, kept by the caller past the session's end

    template <class F>
    [[nodiscard]] F instance_fn(const char* name) const {
        return reinterpret_cast<F>(gipa(instance, name));
    }
    template <class F>
    [[nodiscard]] F device_fn(const char* name) const {
        return reinterpret_cast<F>(gdpa(device, name));
    }

private:
    void release() noexcept {
        if (device != VK_NULL_HANDLE) {
            (void)device_fn<PFN_vkDeviceWaitIdle>("vkDeviceWaitIdle")(device);
            if (fence != VK_NULL_HANDLE) {
                device_fn<PFN_vkDestroyFence>("vkDestroyFence")(device, fence, nullptr);
            }
            if (commands != VK_NULL_HANDLE) {
                device_fn<PFN_vkDestroyCommandPool>("vkDestroyCommandPool")(device, commands, nullptr);
            }
            if (pool != VK_NULL_HANDLE) {
                device_fn<PFN_vkDestroyDescriptorPool>("vkDestroyDescriptorPool")(device, pool, nullptr);
            }
            if (pipeline != VK_NULL_HANDLE) {
                device_fn<PFN_vkDestroyPipeline>("vkDestroyPipeline")(device, pipeline, nullptr);
            }
            if (pipeline_layout != VK_NULL_HANDLE) {
                device_fn<PFN_vkDestroyPipelineLayout>("vkDestroyPipelineLayout")(device, pipeline_layout, nullptr);
            }
            if (set_layout != VK_NULL_HANDLE) {
                device_fn<PFN_vkDestroyDescriptorSetLayout>("vkDestroyDescriptorSetLayout")(device, set_layout,
                                                                                            nullptr);
            }
            if (module != VK_NULL_HANDLE) {
                device_fn<PFN_vkDestroyShaderModule>("vkDestroyShaderModule")(device, module, nullptr);
            }
            for (VkBuffer b : buffers) {
                device_fn<PFN_vkDestroyBuffer>("vkDestroyBuffer")(device, b, nullptr);
            }
            for (VkDeviceMemory m : memories) {
                device_fn<PFN_vkFreeMemory>("vkFreeMemory")(device, m, nullptr);
            }
            device_fn<PFN_vkDestroyDevice>("vkDestroyDevice")(device, nullptr);
        }
        if (messenger != VK_NULL_HANDLE) {
            instance_fn<PFN_vkDestroyDebugUtilsMessengerEXT>("vkDestroyDebugUtilsMessengerEXT")(instance, messenger,
                                                                                                nullptr);
        }
        if (instance != VK_NULL_HANDLE) {
            instance_fn<PFN_vkDestroyInstance>("vkDestroyInstance")(instance, nullptr);
        }
    }
};

/// The instance, with the Khronos validation layer and its synchronization checks wherever the loader has them; when
/// Vulkan is required, the layer must be there.
[[nodiscard]] std::expected<void, ProbeError> make_instance(Session& s) {
    auto enumerate_layers = s.instance_fn<PFN_vkEnumerateInstanceLayerProperties>("vkEnumerateInstanceLayerProperties");
    std::uint32_t n_layers = 0;
    (void)enumerate_layers(&n_layers, nullptr);
    std::vector<VkLayerProperties> layers(n_layers);
    (void)enumerate_layers(&n_layers, layers.data());
    layers.resize(n_layers);
    s.validated = std::ranges::any_of(layers, [](const VkLayerProperties& l) {
        return std::string_view(static_cast<const char*>(l.layerName)) == "VK_LAYER_KHRONOS_validation";
    });
    if (!s.validated && vulkan_required()) {
        return std::unexpected(failure("the Khronos validation layer is missing (ATLAS_REQUIRE_VULKAN=1)"));
    }
    const std::array<const char*, 1> layer_names = {"VK_LAYER_KHRONOS_validation"};
    const std::array<const char*, 1> extensions = {VK_EXT_DEBUG_UTILS_EXTENSION_NAME};
    const VkApplicationInfo app{.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
                                .pApplicationName = "atlas light probe",
                                .apiVersion = VK_API_VERSION_1_1};
    const std::array<VkValidationFeatureEnableEXT, 1> enables = {
        VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT};
    const VkValidationFeaturesEXT features{.sType = VK_STRUCTURE_TYPE_VALIDATION_FEATURES_EXT,
                                           .enabledValidationFeatureCount = static_cast<std::uint32_t>(enables.size()),
                                           .pEnabledValidationFeatures = enables.data()};
    VkDebugUtilsMessengerCreateInfoEXT messenger{.sType = VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT,
                                                 .pNext = &features,
                                                 .messageSeverity = VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT |
                                                                    VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT,
                                                 .messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                                                                VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT,
                                                 .pfnUserCallback = on_message,
                                                 .pUserData = s.messages};
    const VkInstanceCreateInfo info{.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
                                    .pNext = s.validated ? &messenger : nullptr, // instance creation's messages too
                                    .pApplicationInfo = &app,
                                    .enabledLayerCount = s.validated ? 1U : 0U,
                                    .ppEnabledLayerNames = s.validated ? layer_names.data() : nullptr,
                                    .enabledExtensionCount = s.validated ? 1U : 0U,
                                    .ppEnabledExtensionNames = s.validated ? extensions.data() : nullptr};
    if (VkResult r = s.instance_fn<PFN_vkCreateInstance>("vkCreateInstance")(&info, nullptr, &s.instance);
        r != VK_SUCCESS) {
        return std::unexpected(r == VK_ERROR_INCOMPATIBLE_DRIVER ? unavailable("no Vulkan driver")
                                                                 : failed("vkCreateInstance", r));
    }
    if (s.validated) {
        messenger.pNext = nullptr;
        auto create = s.instance_fn<PFN_vkCreateDebugUtilsMessengerEXT>("vkCreateDebugUtilsMessengerEXT");
        if (VkResult r = create(s.instance, &messenger, nullptr, &s.messenger); r != VK_SUCCESS) {
            return std::unexpected(failed("vkCreateDebugUtilsMessengerEXT", r));
        }
    }
    return {};
}

/// The first device with a compute queue (and shaderFloat64 where the probe needs it), and its logical device with
/// that feature on.
[[nodiscard]] std::expected<std::string, ProbeError> make_device(Session& s, bool float64) {
    auto enumerate = s.instance_fn<PFN_vkEnumeratePhysicalDevices>("vkEnumeratePhysicalDevices");
    std::uint32_t n = 0;
    (void)enumerate(s.instance, &n, nullptr);
    std::vector<VkPhysicalDevice> devices(n);
    (void)enumerate(s.instance, &n, devices.data());
    devices.resize(n);
    auto queues =
        s.instance_fn<PFN_vkGetPhysicalDeviceQueueFamilyProperties>("vkGetPhysicalDeviceQueueFamilyProperties");
    auto features_of = s.instance_fn<PFN_vkGetPhysicalDeviceFeatures>("vkGetPhysicalDeviceFeatures");
    bool compute_somewhere = false;
    for (VkPhysicalDevice d : devices) {
        std::uint32_t families = 0;
        queues(d, &families, nullptr);
        std::vector<VkQueueFamilyProperties> props(families);
        queues(d, &families, props.data());
        const auto compute = std::ranges::find_if(
            props, [](const VkQueueFamilyProperties& p) { return (p.queueFlags & VK_QUEUE_COMPUTE_BIT) != 0U; });
        if (compute == props.end()) {
            continue;
        }
        compute_somewhere = true;
        VkPhysicalDeviceFeatures supported{};
        features_of(d, &supported);
        if (float64 && supported.shaderFloat64 != VK_TRUE) {
            continue;
        }
        s.physical = d;
        s.family = static_cast<std::uint32_t>(compute - props.begin());
        break;
    }
    if (s.physical == VK_NULL_HANDLE) {
        return std::unexpected(unavailable(compute_somewhere ? "no Vulkan device with shaderFloat64"
                                                             : "no Vulkan device with a compute queue"));
    }
    VkPhysicalDeviceProperties props{};
    s.instance_fn<PFN_vkGetPhysicalDeviceProperties>("vkGetPhysicalDeviceProperties")(s.physical, &props);
    const float priority = 1.0F;
    const VkDeviceQueueCreateInfo queue{.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                                        .queueFamilyIndex = s.family,
                                        .queueCount = 1,
                                        .pQueuePriorities = &priority};
    const VkPhysicalDeviceFeatures enabled{.shaderFloat64 = float64 ? VK_TRUE : VK_FALSE};
    const VkDeviceCreateInfo info{.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
                                  .queueCreateInfoCount = 1,
                                  .pQueueCreateInfos = &queue,
                                  .pEnabledFeatures = &enabled};
    if (VkResult r = s.instance_fn<PFN_vkCreateDevice>("vkCreateDevice")(s.physical, &info, nullptr, &s.device);
        r != VK_SUCCESS) {
        return std::unexpected(failed("vkCreateDevice", r));
    }
    s.gdpa = s.instance_fn<PFN_vkGetDeviceProcAddr>("vkGetDeviceProcAddr");
    return std::format("{} (Vulkan {}.{}.{}, driver {:#x})",
                       std::string_view(static_cast<const char*>(props.deviceName)),
                       VK_API_VERSION_MAJOR(props.apiVersion), VK_API_VERSION_MINOR(props.apiVersion),
                       VK_API_VERSION_PATCH(props.apiVersion), props.driverVersion);
}

/// A storage buffer in host-visible, coherent memory, mapped.
[[nodiscard]] std::expected<void*, ProbeError> make_buffer(Session& s, VkDeviceSize size) {
    VkPhysicalDeviceMemoryProperties memory{};
    s.instance_fn<PFN_vkGetPhysicalDeviceMemoryProperties>("vkGetPhysicalDeviceMemoryProperties")(s.physical, &memory);
    const VkBufferCreateInfo bi{.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
                                .size = size,
                                .usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                                .sharingMode = VK_SHARING_MODE_EXCLUSIVE};
    VkBuffer buffer = VK_NULL_HANDLE;
    if (VkResult r = s.device_fn<PFN_vkCreateBuffer>("vkCreateBuffer")(s.device, &bi, nullptr, &buffer);
        r != VK_SUCCESS) {
        return std::unexpected(failed("vkCreateBuffer", r));
    }
    s.buffers.push_back(buffer);
    VkMemoryRequirements req{};
    s.device_fn<PFN_vkGetBufferMemoryRequirements>("vkGetBufferMemoryRequirements")(s.device, buffer, &req);
    const VkMemoryPropertyFlags want = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    std::uint32_t type = UINT32_MAX;
    for (std::uint32_t i = 0; i < memory.memoryTypeCount; ++i) {
        if ((req.memoryTypeBits & (1U << i)) != 0U && (memory.memoryTypes[i].propertyFlags & want) == want) {
            type = i;
            break;
        }
    }
    if (type == UINT32_MAX) {
        return std::unexpected(failure("no host-visible coherent memory"));
    }
    const VkMemoryAllocateInfo ai{
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = req.size, .memoryTypeIndex = type};
    VkDeviceMemory mem = VK_NULL_HANDLE;
    if (VkResult r = s.device_fn<PFN_vkAllocateMemory>("vkAllocateMemory")(s.device, &ai, nullptr, &mem);
        r != VK_SUCCESS) {
        return std::unexpected(failed("vkAllocateMemory", r));
    }
    s.memories.push_back(mem);
    if (VkResult r = s.device_fn<PFN_vkBindBufferMemory>("vkBindBufferMemory")(s.device, buffer, mem, 0);
        r != VK_SUCCESS) {
        return std::unexpected(failed("vkBindBufferMemory", r));
    }
    void* p = nullptr;
    if (VkResult r = s.device_fn<PFN_vkMapMemory>("vkMapMemory")(s.device, mem, 0, VK_WHOLE_SIZE, 0, &p);
        r != VK_SUCCESS) {
        return std::unexpected(failed("vkMapMemory", r));
    }
    return p;
}

/// One run of a probe module: binding 0 the inputs, 1 the themes, 2 and 3 the outputs (sizes in bytes), and the push
/// constants; `float64` when the module needs shaderFloat64.
struct Run {
    std::span<const std::uint32_t> spirv;
    std::span<const std::byte> inputs;
    std::span<const std::byte> themes;
    std::size_t bytes_size = 0;
    std::size_t values_size = 0;
    std::span<const std::byte> push;
    std::uint32_t count = 0;
    bool float64 = false;
};

/// What a run made: the two outputs' bytes and the device's description.
struct Made {
    std::vector<std::byte> bytes;
    std::vector<std::byte> values;
    std::string device;
};

[[nodiscard]] std::expected<void, ProbeError> make_pipeline(Session& s, const Run& run) {
    const VkShaderModuleCreateInfo mi{.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
                                      .codeSize = run.spirv.size_bytes(),
                                      .pCode = run.spirv.data()};
    if (VkResult r = s.device_fn<PFN_vkCreateShaderModule>("vkCreateShaderModule")(s.device, &mi, nullptr, &s.module);
        r != VK_SUCCESS) {
        return std::unexpected(failed("vkCreateShaderModule", r));
    }
    std::array<VkDescriptorSetLayoutBinding, 4> bindings{};
    for (std::uint32_t i = 0; i < bindings.size(); ++i) {
        bindings.at(i) = VkDescriptorSetLayoutBinding{.binding = i,
                                                      .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                                                      .descriptorCount = 1,
                                                      .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT};
    }
    const VkDescriptorSetLayoutCreateInfo li{.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
                                             .bindingCount = static_cast<std::uint32_t>(bindings.size()),
                                             .pBindings = bindings.data()};
    auto create_layout = s.device_fn<PFN_vkCreateDescriptorSetLayout>("vkCreateDescriptorSetLayout");
    if (VkResult r = create_layout(s.device, &li, nullptr, &s.set_layout); r != VK_SUCCESS) {
        return std::unexpected(failed("vkCreateDescriptorSetLayout", r));
    }
    const VkPushConstantRange push{
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT, .offset = 0, .size = static_cast<std::uint32_t>(run.push.size())};
    const VkPipelineLayoutCreateInfo pli{.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
                                         .setLayoutCount = 1,
                                         .pSetLayouts = &s.set_layout,
                                         .pushConstantRangeCount = 1,
                                         .pPushConstantRanges = &push};
    auto create_pipeline_layout = s.device_fn<PFN_vkCreatePipelineLayout>("vkCreatePipelineLayout");
    if (VkResult r = create_pipeline_layout(s.device, &pli, nullptr, &s.pipeline_layout); r != VK_SUCCESS) {
        return std::unexpected(failed("vkCreatePipelineLayout", r));
    }
    const VkComputePipelineCreateInfo ci{.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
                                         .stage = {.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                                                   .stage = VK_SHADER_STAGE_COMPUTE_BIT,
                                                   .module = s.module,
                                                   .pName = "main"},
                                         .layout = s.pipeline_layout};
    auto create_pipeline = s.device_fn<PFN_vkCreateComputePipelines>("vkCreateComputePipelines");
    if (VkResult r = create_pipeline(s.device, VK_NULL_HANDLE, 1, &ci, nullptr, &s.pipeline); r != VK_SUCCESS) {
        return std::unexpected(failed("vkCreateComputePipelines", r));
    }
    return {};
}

[[nodiscard]] std::expected<VkDescriptorSet, ProbeError> make_descriptors(Session& s) {
    const VkDescriptorPoolSize pool_size{.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, .descriptorCount = 4};
    const VkDescriptorPoolCreateInfo dpi{.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
                                         .maxSets = 1,
                                         .poolSizeCount = 1,
                                         .pPoolSizes = &pool_size};
    if (VkResult r =
            s.device_fn<PFN_vkCreateDescriptorPool>("vkCreateDescriptorPool")(s.device, &dpi, nullptr, &s.pool);
        r != VK_SUCCESS) {
        return std::unexpected(failed("vkCreateDescriptorPool", r));
    }
    const VkDescriptorSetAllocateInfo dai{.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
                                          .descriptorPool = s.pool,
                                          .descriptorSetCount = 1,
                                          .pSetLayouts = &s.set_layout};
    VkDescriptorSet set = VK_NULL_HANDLE;
    if (VkResult r = s.device_fn<PFN_vkAllocateDescriptorSets>("vkAllocateDescriptorSets")(s.device, &dai, &set);
        r != VK_SUCCESS) {
        return std::unexpected(failed("vkAllocateDescriptorSets", r));
    }
    std::array<VkDescriptorBufferInfo, 4> infos{};
    std::array<VkWriteDescriptorSet, 4> writes{};
    for (std::size_t i = 0; i < writes.size(); ++i) {
        infos.at(i) = VkDescriptorBufferInfo{.buffer = s.buffers.at(i), .offset = 0, .range = VK_WHOLE_SIZE};
        writes.at(i) = VkWriteDescriptorSet{.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,
                                            .dstSet = set,
                                            .dstBinding = static_cast<std::uint32_t>(i),
                                            .descriptorCount = 1,
                                            .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                                            .pBufferInfo = &infos.at(i)};
    }
    s.device_fn<PFN_vkUpdateDescriptorSets>("vkUpdateDescriptorSets")(
        s.device, static_cast<std::uint32_t>(writes.size()), writes.data(), 0, nullptr);
    return set;
}

/// Records and submits one dispatch over the inputs, then waits for it.
[[nodiscard]] std::expected<void, ProbeError> dispatch(Session& s, VkDescriptorSet set, const Run& run) {
    const VkCommandPoolCreateInfo cpi{.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
                                      .queueFamilyIndex = s.family};
    if (VkResult r = s.device_fn<PFN_vkCreateCommandPool>("vkCreateCommandPool")(s.device, &cpi, nullptr, &s.commands);
        r != VK_SUCCESS) {
        return std::unexpected(failed("vkCreateCommandPool", r));
    }
    const VkCommandBufferAllocateInfo cai{.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                                          .commandPool = s.commands,
                                          .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
                                          .commandBufferCount = 1};
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    if (VkResult r = s.device_fn<PFN_vkAllocateCommandBuffers>("vkAllocateCommandBuffers")(s.device, &cai, &cmd);
        r != VK_SUCCESS) {
        return std::unexpected(failed("vkAllocateCommandBuffers", r));
    }
    const VkCommandBufferBeginInfo begin{.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                                         .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    if (VkResult r = s.device_fn<PFN_vkBeginCommandBuffer>("vkBeginCommandBuffer")(cmd, &begin); r != VK_SUCCESS) {
        return std::unexpected(failed("vkBeginCommandBuffer", r));
    }
    s.device_fn<PFN_vkCmdBindPipeline>("vkCmdBindPipeline")(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, s.pipeline);
    s.device_fn<PFN_vkCmdBindDescriptorSets>("vkCmdBindDescriptorSets")(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                                                                        s.pipeline_layout, 0, 1, &set, 0, nullptr);
    s.device_fn<PFN_vkCmdPushConstants>("vkCmdPushConstants")(cmd, s.pipeline_layout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                                                              static_cast<std::uint32_t>(run.push.size()),
                                                              run.push.data());
    s.device_fn<PFN_vkCmdDispatch>("vkCmdDispatch")(cmd, (run.count + 63U) / 64U, 1, 1);
    const VkMemoryBarrier barrier{.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
                                  .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
                                  .dstAccessMask = VK_ACCESS_HOST_READ_BIT};
    s.device_fn<PFN_vkCmdPipelineBarrier>("vkCmdPipelineBarrier")(
        cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &barrier, 0, nullptr, 0, nullptr);
    if (VkResult r = s.device_fn<PFN_vkEndCommandBuffer>("vkEndCommandBuffer")(cmd); r != VK_SUCCESS) {
        return std::unexpected(failed("vkEndCommandBuffer", r));
    }
    const VkFenceCreateInfo fi{.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (VkResult r = s.device_fn<PFN_vkCreateFence>("vkCreateFence")(s.device, &fi, nullptr, &s.fence);
        r != VK_SUCCESS) {
        return std::unexpected(failed("vkCreateFence", r));
    }
    VkQueue queue = VK_NULL_HANDLE;
    s.device_fn<PFN_vkGetDeviceQueue>("vkGetDeviceQueue")(s.device, s.family, 0, &queue);
    const VkSubmitInfo si{.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1, .pCommandBuffers = &cmd};
    if (VkResult r = s.device_fn<PFN_vkQueueSubmit>("vkQueueSubmit")(queue, 1, &si, s.fence); r != VK_SUCCESS) {
        return std::unexpected(failed("vkQueueSubmit", r));
    }
    if (VkResult r = s.device_fn<PFN_vkWaitForFences>("vkWaitForFences")(s.device, 1, &s.fence, VK_TRUE, UINT64_MAX);
        r != VK_SUCCESS) {
        return std::unexpected(failed("vkWaitForFences", r));
    }
    return {};
}

[[nodiscard]] std::expected<Made, ProbeError> run_probe(const Run& run) {
    if (run.count == 0 || run.themes.empty()) {
        return std::unexpected(failure("nothing to run"));
    }
    Loader loader;
    auto gipa = loader.open();
    if (!gipa) {
        return std::unexpected(gipa.error());
    }
    Made made;
    std::vector<std::string> messages;
    bool validated = false;
    {
        Session s;
        s.gipa = *gipa;
        s.messages = &messages;
        if (auto instance = make_instance(s); !instance) {
            return std::unexpected(instance.error());
        }
        auto device = make_device(s, run.float64);
        if (!device) {
            return std::unexpected(device.error());
        }
        made.device = std::move(*device);
        const std::array<std::size_t, 4> sizes = {run.inputs.size(), run.themes.size(), run.bytes_size,
                                                  run.values_size};
        std::array<void*, 4> mapped{};
        for (std::size_t i = 0; i < sizes.size(); ++i) {
            auto p = make_buffer(s, sizes.at(i));
            if (!p) {
                return std::unexpected(p.error());
            }
            mapped.at(i) = *p;
        }
        std::memcpy(mapped[0], run.inputs.data(), run.inputs.size());
        std::memcpy(mapped[1], run.themes.data(), run.themes.size());
        std::memset(mapped[2], 0xFF, run.bytes_size);
        std::memset(mapped[3], 0, run.values_size);
        if (auto pipeline = make_pipeline(s, run); !pipeline) {
            return std::unexpected(pipeline.error());
        }
        auto set = make_descriptors(s);
        if (!set) {
            return std::unexpected(set.error());
        }
        if (auto done = dispatch(s, *set, run); !done) {
            return std::unexpected(done.error());
        }
        made.bytes.resize(run.bytes_size);
        made.values.resize(run.values_size);
        std::memcpy(made.bytes.data(), mapped[2], run.bytes_size);
        std::memcpy(made.values.data(), mapped[3], run.values_size);
        validated = s.validated;
    } // the session ends here, so the messages of its destruction count too
    if (!messages.empty()) {
        return std::unexpected(
            failure("the validation layer: " + messages.front() +
                    (messages.size() > 1 ? std::format(" (and {} more)", messages.size() - 1) : std::string())));
    }
    made.device += validated ? ", validation layer on" : ", no validation layer";
    return made;
}

template <class T>
[[nodiscard]] std::vector<T> as(const std::vector<std::byte>& raw) {
    std::vector<T> out(raw.size() / sizeof(T));
    std::memcpy(out.data(), raw.data(), out.size() * sizeof(T));
    return out;
}

} // namespace

bool vulkan_required() noexcept {
    // NOLINTNEXTLINE(concurrency-mt-unsafe): read on the probe's thread; nothing in the process writes it meanwhile
    const char* value = std::getenv("ATLAS_REQUIRE_VULKAN");
    return value != nullptr && std::string_view(value) == "1";
}

std::expected<LightProbeOutput, ProbeError> run_light_probe(std::span<const LightInput> inputs,
                                                            std::span<const shaders::Theme> themes, ProbeMode mode) {
    struct Params {
        std::uint32_t count;
        std::uint32_t mode;
    };
    const Params params{.count = static_cast<std::uint32_t>(inputs.size()), .mode = static_cast<std::uint32_t>(mode)};
    for (const LightInput& in : inputs) {
        if (mode == ProbeMode::light && in.theme >= themes.size()) {
            return std::unexpected(failure("an input names a theme that is not there"));
        }
    }
    const Run run{.spirv = spirv::style_light_probe,
                  .inputs = std::as_bytes(inputs),
                  .themes = std::as_bytes(themes),
                  .bytes_size = inputs.size() * sizeof(std::uint32_t),
                  .values_size = inputs.size() * 3 * sizeof(double),
                  .push = std::as_bytes(std::span<const Params, 1>(&params, 1)),
                  .count = params.count,
                  .float64 = true};
    auto made = run_probe(run);
    if (!made) {
        return std::unexpected(made.error());
    }
    return LightProbeOutput{
        .bytes = as<std::uint32_t>(made->bytes), .values = as<double>(made->values), .device = std::move(made->device)};
}

std::expected<LightProbeF32Output, ProbeError> run_light_probe_f32(std::span<const LightInputF32> inputs,
                                                                   std::span<const shaders::Theme> themes) {
    const auto count = static_cast<std::uint32_t>(inputs.size());
    for (const LightInputF32& in : inputs) {
        if (!(in.normal.w >= 0.0F) || static_cast<std::size_t>(in.normal.w) >= themes.size()) {
            return std::unexpected(failure("an input names a theme that is not there"));
        }
    }
    const Run run{.spirv = spirv::style_light_probe_f32,
                  .inputs = std::as_bytes(inputs),
                  .themes = std::as_bytes(themes),
                  .bytes_size = inputs.size() * sizeof(std::uint32_t),
                  .values_size = inputs.size() * sizeof(shaders::float4),
                  .push = std::as_bytes(std::span<const std::uint32_t, 1>(&count, 1)),
                  .count = count,
                  .float64 = false};
    auto made = run_probe(run);
    if (!made) {
        return std::unexpected(made.error());
    }
    return LightProbeF32Output{.bytes = as<std::uint32_t>(made->bytes),
                               .values = as<shaders::float4>(made->values),
                               .device = std::move(made->device)};
}

} // namespace atlas::style
