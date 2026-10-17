// The likeness harness's candidate stage in the host build (plan-vector K4, D16): one case drawn offscreen with the
// passes that exist, read back for :mapcorecheck's MT/likeness tests on the JVM. It binds to the Kotlin object
// app.atlas.mapcorecheck.likeness.TestCases by JNI's names; nothing of it is in the phone's library.
//
// Until the frame core (K7) registers passes, the frame is the main pass's clear colour, drawn on the host's Vulkan
// device (lavapipe on CI and on this Mac) with a few Vulkan calls of its own; once the device layer (CP/gpu, K1) and
// its offscreen host (CP/platform/host) exist, the case is drawn through them and the pass registry instead.
//
// The case buffer (256 bytes, little-endian; MT/likeness/Candidate.kt writes it): 0 magic "LKC1", 4 version, 8 width
// px, 12 height px, 16 density (f64), 24 clear colour (ARGB), 28 mode (0 structural, 1 full, 2 effect), 32 flags (bit
// 0: engine mode), 36 moment kind (0 named, 1 live), 40 lat, 48 lon, 56 zoom, 64 bearing, 72 map pitch, 80 principal
// point in dp (f64 each), 88 width dp, 92 height dp, 96 the moment's name or instant (64 bytes), 160 the pack (64
// bytes).
//
// Vulkan comes configured from base/vulkan.hpp (the core's CMake defines VK_NO_PROTOTYPES and
// VK_USE_PLATFORM_ANDROID_KHR for every unit); this file calls the C entry points it loads itself. The loader is opened
// at run time, as the light probe opens it (style/HostProbe.cpp): ATLAS_VULKAN_LOADER, else the system's, else on this
// Mac the Android emulator package's, pointed at its lavapipe and validation layer. Any message of the validation
// layer fails the case.
#include "base/vulkan.hpp"

#include <jni.h>

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <expected>
#include <format>
#include <span>
#include <string>
#include <string_view>
#include <unistd.h>
#include <vector>

namespace {

/// The case buffer's layout version; MT/likeness/Candidate.kt's TestCases.ABI.
constexpr jint case_abi = 1;
constexpr std::size_t case_bytes = 256;
constexpr std::uint32_t case_magic = 0x31434B4C; // "LKC1"

/// What render() filled, as bits of its result.
constexpr jint filled_pixels = 1;

/// render()'s errors.
enum class Failure : std::int8_t { bad_case = -1, no_device = -2, device = -3, memory = -4 };

/// One case, as the buffer gives it (only what the passes that exist read).
struct Case {
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    double density = 0;
    std::uint32_t clear_argb = 0;
    std::uint32_t mode = 0;
};

template <class T>
[[nodiscard]] T read_le(std::span<const std::byte> b, std::size_t at) {
    std::array<std::byte, sizeof(T)> raw{};
    std::memcpy(raw.data(), b.data() + at, sizeof(T));
    if constexpr (std::endian::native == std::endian::big) {
        std::ranges::reverse(raw);
    }
    return std::bit_cast<T>(raw);
}

[[nodiscard]] std::expected<Case, std::string> read_case(std::span<const std::byte> b) {
    if (b.size() < case_bytes) {
        return std::unexpected(std::format("a case is {} bytes, not {}", case_bytes, b.size()));
    }
    if (read_le<std::uint32_t>(b, 0) != case_magic) {
        return std::unexpected(std::string("not a likeness case (magic)"));
    }
    if (const auto v = read_le<std::uint32_t>(b, 4); v != static_cast<std::uint32_t>(case_abi)) {
        return std::unexpected(std::format("a case of layout {}, this library reads {}", v, case_abi));
    }
    Case c{.width = read_le<std::uint32_t>(b, 8),
           .height = read_le<std::uint32_t>(b, 12),
           .density = read_le<double>(b, 16),
           .clear_argb = read_le<std::uint32_t>(b, 24),
           .mode = read_le<std::uint32_t>(b, 28)};
    if (c.width == 0 || c.height == 0 || c.width > 16384 || c.height > 16384) {
        return std::unexpected(std::format("a frame of {} × {} px", c.width, c.height));
    }
    if (std::isnan(c.density) || c.density <= 0.25 || c.density >= 16.0) {
        return std::unexpected(std::format("a density of {}", c.density));
    }
    if (c.mode > 2) {
        return std::unexpected(std::format("mode {}", c.mode));
    }
    return c;
}

/// The last failure's words, for TestCases.lastError().
thread_local std::string last_error; // NOLINT(cppcoreguidelines-avoid-non-const-global-variables): per thread

/// The device the last case was drawn on (its name, Vulkan version, driver, whether the validation layer watched), for
/// TestCases.device().
thread_local std::string last_device; // NOLINT(cppcoreguidelines-avoid-non-const-global-variables): as last_error

/// An environment variable's value, or "" when it is not set.
[[nodiscard]] std::string environment(const char* name) {
    const char* value = std::getenv(name); // NOLINT(concurrency-mt-unsafe): read while opening the loader
    return value == nullptr ? std::string() : std::string(value);
}

#if defined(__APPLE__)
[[nodiscard]] bool exists(const std::string& path) { return ::access(path.c_str(), F_OK) == 0; }

/// The Android emulator package's Vulkan folder on this Mac (its loader, lavapipe and the validation layer), or "".
[[nodiscard]] std::string emulator_vulkan_dir() {
    for (const char* var : {"ANDROID_HOME", "ANDROID_SDK_ROOT"}) {
        if (const std::string sdk = environment(var); !sdk.empty()) {
            std::string dir = sdk + "/emulator/lib64/vulkan";
            if (exists(dir + "/lvp_icd.json")) {
                return dir;
            }
        }
    }
    if (const std::string home = environment("HOME"); !home.empty()) {
        std::string dir = home + "/Library/Android/sdk/emulator/lib64/vulkan";
        if (exists(dir + "/lvp_icd.json")) {
            return dir;
        }
    }
    return {};
}
#endif

/// The Vulkan loader, opened at run time and kept open for the process: drivers keep threads that outlive a session.
[[nodiscard]] std::expected<PFN_vkGetInstanceProcAddr, std::string> loader() {
    static void* handle = nullptr;
    if (handle == nullptr) {
        std::vector<std::string> tried;
        auto attempt = [&](const std::string& path) {
            tried.push_back(path);
            handle = ::dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
            return handle != nullptr;
        };
        bool ok = false;
        if (const std::string own = environment("ATLAS_VULKAN_LOADER"); !own.empty()) {
            ok = attempt(own);
        }
#if defined(__APPLE__)
        for (const char* name : {"libvulkan.1.dylib", "libvulkan.dylib"}) {
            ok = ok || attempt(name);
        }
        if (!ok) {
            if (const std::string dir = emulator_vulkan_dir(); !dir.empty()) {
                if (environment("VK_DRIVER_FILES").empty() && environment("VK_ICD_FILENAMES").empty()) {
                    ::setenv("VK_DRIVER_FILES", (dir + "/lvp_icd.json").c_str(), 0); // NOLINT(concurrency-mt-unsafe)
                }
                if (environment("VK_ADD_LAYER_PATH").empty() && environment("VK_LAYER_PATH").empty() &&
                    exists(dir + "/layers")) {
                    ::setenv("VK_ADD_LAYER_PATH", (dir + "/layers").c_str(), 0); // NOLINT(concurrency-mt-unsafe)
                }
                ok = attempt(dir + "/libvulkan.dylib");
            }
        }
#else
        for (const char* name : {"libvulkan.so.1", "libvulkan.so"}) {
            ok = ok || attempt(name);
        }
#endif
        if (!ok) {
            std::string all;
            for (const auto& t : tried) {
                all += (all.empty() ? "" : ", ") + t;
            }
            return std::unexpected("no Vulkan loader (tried " + all + ")");
        }
    }
    auto get = reinterpret_cast<PFN_vkGetInstanceProcAddr>(::dlsym(handle, "vkGetInstanceProcAddr"));
    if (get == nullptr) {
        return std::unexpected(std::string("the Vulkan loader has no vkGetInstanceProcAddr"));
    }
    return get;
}

VKAPI_ATTR VkBool32 VKAPI_CALL on_message(VkDebugUtilsMessageSeverityFlagBitsEXT, VkDebugUtilsMessageTypeFlagsEXT,
                                          const VkDebugUtilsMessengerCallbackDataEXT* data, void* user) {
    static_cast<std::vector<std::string>*>(user)->emplace_back(data->pMessage != nullptr ? data->pMessage
                                                                                         : "(no message)");
    return VK_FALSE;
}

/// Every handle one case makes, destroyed in reverse order however it ends.
struct Session {
    PFN_vkGetInstanceProcAddr gipa = nullptr;
    PFN_vkGetDeviceProcAddr gdpa = nullptr;
    VkInstance instance = VK_NULL_HANDLE;
    VkDebugUtilsMessengerEXT messenger = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkImage image = VK_NULL_HANDLE;
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory image_memory = VK_NULL_HANDLE;
    VkDeviceMemory buffer_memory = VK_NULL_HANDLE;
    VkCommandPool commands = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    std::vector<std::string> messages;

    template <class F>
    F instance_fn(const char* name) const {
        return reinterpret_cast<F>(gipa(instance, name));
    }
    template <class F>
    F device_fn(const char* name) const {
        return reinterpret_cast<F>(gdpa(device, name));
    }

    Session() = default;
    Session(const Session&) = delete;
    Session& operator=(const Session&) = delete;
    Session(Session&&) = delete;
    Session& operator=(Session&&) = delete;
    ~Session() {
        if (device != VK_NULL_HANDLE) {
            device_fn<PFN_vkDeviceWaitIdle>("vkDeviceWaitIdle")(device);
            if (fence != VK_NULL_HANDLE) device_fn<PFN_vkDestroyFence>("vkDestroyFence")(device, fence, nullptr);
            if (commands != VK_NULL_HANDLE)
                device_fn<PFN_vkDestroyCommandPool>("vkDestroyCommandPool")(device, commands, nullptr);
            if (buffer != VK_NULL_HANDLE) device_fn<PFN_vkDestroyBuffer>("vkDestroyBuffer")(device, buffer, nullptr);
            if (image != VK_NULL_HANDLE) device_fn<PFN_vkDestroyImage>("vkDestroyImage")(device, image, nullptr);
            if (buffer_memory != VK_NULL_HANDLE)
                device_fn<PFN_vkFreeMemory>("vkFreeMemory")(device, buffer_memory, nullptr);
            if (image_memory != VK_NULL_HANDLE)
                device_fn<PFN_vkFreeMemory>("vkFreeMemory")(device, image_memory, nullptr);
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

struct Error {
    Failure kind;
    std::string why;
};

[[nodiscard]] Error vk_failed(std::string_view what, VkResult r) {
    const bool memory = r == VK_ERROR_OUT_OF_HOST_MEMORY || r == VK_ERROR_OUT_OF_DEVICE_MEMORY;
    return {.kind = memory ? Failure::memory : Failure::device,
            .why = std::format("{} failed ({})", what, static_cast<int>(r))};
}

/// The index of a memory type [bits] allows with every flag of [want], or UINT32_MAX.
[[nodiscard]] std::uint32_t memory_type(const VkPhysicalDeviceMemoryProperties& m, std::uint32_t bits,
                                        VkMemoryPropertyFlags want) {
    for (std::uint32_t i = 0; i < m.memoryTypeCount; ++i) {
        if ((bits & (1U << i)) != 0U && (m.memoryTypes[i].propertyFlags & want) == want) {
            return i;
        }
    }
    return UINT32_MAX;
}

/// Opens an instance (with the validation layer where the loader has it) and the first device with a graphics queue.
[[nodiscard]] std::expected<std::uint32_t, Error> open_device(Session& s) {
    auto gipa = loader();
    if (!gipa) {
        return std::unexpected(Error{.kind = Failure::no_device, .why = gipa.error()});
    }
    s.gipa = *gipa;
    auto enumerate_layers = s.instance_fn<PFN_vkEnumerateInstanceLayerProperties>("vkEnumerateInstanceLayerProperties");
    std::uint32_t n_layers = 0;
    enumerate_layers(&n_layers, nullptr);
    std::vector<VkLayerProperties> layers(n_layers);
    enumerate_layers(&n_layers, layers.data());
    const bool validate = std::ranges::any_of(layers, [](const VkLayerProperties& l) {
        return std::strcmp(l.layerName, "VK_LAYER_KHRONOS_validation") == 0;
    });
    const char* layer_names[] = {"VK_LAYER_KHRONOS_validation"};
    const char* extensions[] = {VK_EXT_DEBUG_UTILS_EXTENSION_NAME};
    const VkApplicationInfo app{.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
                                .pApplicationName = "atlas likeness case",
                                .apiVersion = VK_API_VERSION_1_1};
    const VkValidationFeatureEnableEXT enables[] = {VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT};
    const VkValidationFeaturesEXT features{.sType = VK_STRUCTURE_TYPE_VALIDATION_FEATURES_EXT,
                                           .enabledValidationFeatureCount = 1,
                                           .pEnabledValidationFeatures = enables};
    VkDebugUtilsMessengerCreateInfoEXT messenger_info{
        .sType = VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT,
        .pNext = &features,
        .messageSeverity =
            VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT,
        .messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT,
        .pfnUserCallback = on_message,
        .pUserData = &s.messages,
    };
    const VkInstanceCreateInfo instance_info{
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pNext = validate ? &messenger_info : nullptr,
        .pApplicationInfo = &app,
        .enabledLayerCount = validate ? 1U : 0U,
        .ppEnabledLayerNames = validate ? layer_names : nullptr,
        .enabledExtensionCount = validate ? 1U : 0U,
        .ppEnabledExtensionNames = validate ? extensions : nullptr,
    };
    if (VkResult r = s.instance_fn<PFN_vkCreateInstance>("vkCreateInstance")(&instance_info, nullptr, &s.instance);
        r != VK_SUCCESS) {
        return std::unexpected(
            Error{.kind = Failure::no_device, .why = std::format("vkCreateInstance failed ({})", static_cast<int>(r))});
    }
    if (validate) {
        messenger_info.pNext = nullptr;
        s.instance_fn<PFN_vkCreateDebugUtilsMessengerEXT>("vkCreateDebugUtilsMessengerEXT")(s.instance, &messenger_info,
                                                                                            nullptr, &s.messenger);
    }
    auto enumerate_devices = s.instance_fn<PFN_vkEnumeratePhysicalDevices>("vkEnumeratePhysicalDevices");
    std::uint32_t n_devices = 0;
    enumerate_devices(s.instance, &n_devices, nullptr);
    std::vector<VkPhysicalDevice> devices(n_devices);
    enumerate_devices(s.instance, &n_devices, devices.data());
    auto queue_props =
        s.instance_fn<PFN_vkGetPhysicalDeviceQueueFamilyProperties>("vkGetPhysicalDeviceQueueFamilyProperties");
    std::uint32_t family = UINT32_MAX;
    for (VkPhysicalDevice d : devices) {
        std::uint32_t n = 0;
        queue_props(d, &n, nullptr);
        std::vector<VkQueueFamilyProperties> fams(n);
        queue_props(d, &n, fams.data());
        for (std::uint32_t i = 0; i < n && family == UINT32_MAX; ++i) {
            if ((fams[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0U) {
                s.physical = d;
                family = i;
            }
        }
        if (family != UINT32_MAX) {
            break;
        }
    }
    if (family == UINT32_MAX) {
        return std::unexpected(Error{.kind = Failure::no_device, .why = "no Vulkan device with a graphics queue"});
    }
    const float priority = 1.0F;
    const VkDeviceQueueCreateInfo queue_info{.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
                                             .queueFamilyIndex = family,
                                             .queueCount = 1,
                                             .pQueuePriorities = &priority};
    const VkDeviceCreateInfo device_info{
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, .queueCreateInfoCount = 1, .pQueueCreateInfos = &queue_info};
    if (VkResult r = s.instance_fn<PFN_vkCreateDevice>("vkCreateDevice")(s.physical, &device_info, nullptr, &s.device);
        r != VK_SUCCESS) {
        return std::unexpected(vk_failed("vkCreateDevice", r));
    }
    s.gdpa = s.instance_fn<PFN_vkGetDeviceProcAddr>("vkGetDeviceProcAddr");
    VkPhysicalDeviceProperties props{};
    s.instance_fn<PFN_vkGetPhysicalDeviceProperties>("vkGetPhysicalDeviceProperties")(s.physical, &props);
    last_device =
        std::format("{} (Vulkan {}.{}.{}, driver {:#x}), {}", props.deviceName, VK_API_VERSION_MAJOR(props.apiVersion),
                    VK_API_VERSION_MINOR(props.apiVersion), VK_API_VERSION_PATCH(props.apiVersion), props.driverVersion,
                    validate ? "validation layer on" : "no validation layer");
    return family;
}

/// Draws [c] with the passes that exist (the main pass's clear) into an offscreen RGBA8 image and reads it into [out].
[[nodiscard]] std::expected<void, Error> draw(const Case& c, std::span<std::byte> out) {
    Session s;
    auto family = open_device(s);
    if (!family) {
        return std::unexpected(family.error());
    }
    VkQueue queue = VK_NULL_HANDLE;
    s.device_fn<PFN_vkGetDeviceQueue>("vkGetDeviceQueue")(s.device, *family, 0, &queue);
    VkPhysicalDeviceMemoryProperties memory{};
    s.instance_fn<PFN_vkGetPhysicalDeviceMemoryProperties>("vkGetPhysicalDeviceMemoryProperties")(s.physical, &memory);

    // the frame: an RGBA8 image the size of the case
    const VkImageCreateInfo ii{
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = VK_FORMAT_R8G8B8A8_UNORM,
        .extent = {c.width, c.height, 1},
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED,
    };
    if (VkResult r = s.device_fn<PFN_vkCreateImage>("vkCreateImage")(s.device, &ii, nullptr, &s.image);
        r != VK_SUCCESS) {
        return std::unexpected(vk_failed("vkCreateImage", r));
    }
    VkMemoryRequirements image_req{};
    s.device_fn<PFN_vkGetImageMemoryRequirements>("vkGetImageMemoryRequirements")(s.device, s.image, &image_req);
    std::uint32_t image_type = memory_type(memory, image_req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (image_type == UINT32_MAX) {
        image_type = memory_type(memory, image_req.memoryTypeBits, 0);
    }
    const VkMemoryAllocateInfo iai{.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                                   .allocationSize = image_req.size,
                                   .memoryTypeIndex = image_type};
    if (VkResult r = s.device_fn<PFN_vkAllocateMemory>("vkAllocateMemory")(s.device, &iai, nullptr, &s.image_memory);
        r != VK_SUCCESS) {
        return std::unexpected(vk_failed("vkAllocateMemory (image)", r));
    }
    s.device_fn<PFN_vkBindImageMemory>("vkBindImageMemory")(s.device, s.image, s.image_memory, 0);

    // the readback: a host-visible buffer of its rows
    const VkDeviceSize bytes = static_cast<VkDeviceSize>(c.width) * c.height * 4;
    const VkBufferCreateInfo bi{.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
                                .size = bytes,
                                .usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                .sharingMode = VK_SHARING_MODE_EXCLUSIVE};
    if (VkResult r = s.device_fn<PFN_vkCreateBuffer>("vkCreateBuffer")(s.device, &bi, nullptr, &s.buffer);
        r != VK_SUCCESS) {
        return std::unexpected(vk_failed("vkCreateBuffer", r));
    }
    VkMemoryRequirements buffer_req{};
    s.device_fn<PFN_vkGetBufferMemoryRequirements>("vkGetBufferMemoryRequirements")(s.device, s.buffer, &buffer_req);
    const std::uint32_t buffer_type = memory_type(
        memory, buffer_req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (buffer_type == UINT32_MAX) {
        return std::unexpected(Error{.kind = Failure::device, .why = "no host-visible coherent memory"});
    }
    const VkMemoryAllocateInfo bai{.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                                   .allocationSize = buffer_req.size,
                                   .memoryTypeIndex = buffer_type};
    if (VkResult r = s.device_fn<PFN_vkAllocateMemory>("vkAllocateMemory")(s.device, &bai, nullptr, &s.buffer_memory);
        r != VK_SUCCESS) {
        return std::unexpected(vk_failed("vkAllocateMemory (readback)", r));
    }
    s.device_fn<PFN_vkBindBufferMemory>("vkBindBufferMemory")(s.device, s.buffer, s.buffer_memory, 0);

    // the passes that exist: the main pass's clear; then the frame copied out and made visible to the host
    const VkCommandPoolCreateInfo cpi{.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, .queueFamilyIndex = *family};
    if (VkResult r = s.device_fn<PFN_vkCreateCommandPool>("vkCreateCommandPool")(s.device, &cpi, nullptr, &s.commands);
        r != VK_SUCCESS) {
        return std::unexpected(vk_failed("vkCreateCommandPool", r));
    }
    const VkCommandBufferAllocateInfo cai{.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
                                          .commandPool = s.commands,
                                          .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
                                          .commandBufferCount = 1};
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    if (VkResult r = s.device_fn<PFN_vkAllocateCommandBuffers>("vkAllocateCommandBuffers")(s.device, &cai, &cmd);
        r != VK_SUCCESS) {
        return std::unexpected(vk_failed("vkAllocateCommandBuffers", r));
    }
    const VkCommandBufferBeginInfo begin{.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
                                         .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT};
    s.device_fn<PFN_vkBeginCommandBuffer>("vkBeginCommandBuffer")(cmd, &begin);
    const VkImageSubresourceRange all{.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT, .levelCount = 1, .layerCount = 1};
    auto barrier = s.device_fn<PFN_vkCmdPipelineBarrier>("vkCmdPipelineBarrier");
    const VkImageMemoryBarrier to_clear{
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
        .oldLayout = VK_IMAGE_LAYOUT_UNDEFINED,
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = s.image,
        .subresourceRange = all,
    };
    barrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1,
            &to_clear);
    const auto channel = [&](int shift) { return static_cast<float>((c.clear_argb >> shift) & 0xFFU) / 255.0F; };
    const VkClearColorValue clear{.float32 = {channel(16), channel(8), channel(0), channel(24)}};
    s.device_fn<PFN_vkCmdClearColorImage>("vkCmdClearColorImage")(cmd, s.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                                                  &clear, 1, &all);
    const VkImageMemoryBarrier to_copy{
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT,
        .oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        .newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = s.image,
        .subresourceRange = all,
    };
    barrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1,
            &to_copy);
    const VkBufferImageCopy region{.imageSubresource = {.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT, .layerCount = 1},
                                   .imageExtent = {c.width, c.height, 1}};
    s.device_fn<PFN_vkCmdCopyImageToBuffer>("vkCmdCopyImageToBuffer")(
        cmd, s.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, s.buffer, 1, &region);
    const VkMemoryBarrier to_host{.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
                                  .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                                  .dstAccessMask = VK_ACCESS_HOST_READ_BIT};
    barrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &to_host, 0, nullptr, 0, nullptr);
    if (VkResult r = s.device_fn<PFN_vkEndCommandBuffer>("vkEndCommandBuffer")(cmd); r != VK_SUCCESS) {
        return std::unexpected(vk_failed("vkEndCommandBuffer", r));
    }
    const VkFenceCreateInfo fi{.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (VkResult r = s.device_fn<PFN_vkCreateFence>("vkCreateFence")(s.device, &fi, nullptr, &s.fence);
        r != VK_SUCCESS) {
        return std::unexpected(vk_failed("vkCreateFence", r));
    }
    const VkSubmitInfo si{.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1, .pCommandBuffers = &cmd};
    if (VkResult r = s.device_fn<PFN_vkQueueSubmit>("vkQueueSubmit")(queue, 1, &si, s.fence); r != VK_SUCCESS) {
        return std::unexpected(vk_failed("vkQueueSubmit", r));
    }
    if (VkResult r = s.device_fn<PFN_vkWaitForFences>("vkWaitForFences")(s.device, 1, &s.fence, VK_TRUE, UINT64_MAX);
        r != VK_SUCCESS) {
        return std::unexpected(vk_failed("vkWaitForFences", r));
    }
    void* mapped = nullptr;
    if (VkResult r =
            s.device_fn<PFN_vkMapMemory>("vkMapMemory")(s.device, s.buffer_memory, 0, VK_WHOLE_SIZE, 0, &mapped);
        r != VK_SUCCESS) {
        return std::unexpected(vk_failed("vkMapMemory", r));
    }
    std::memcpy(out.data(), mapped, static_cast<std::size_t>(bytes));
    s.device_fn<PFN_vkUnmapMemory>("vkUnmapMemory")(s.device, s.buffer_memory);
    if (!s.messages.empty()) {
        return std::unexpected(
            Error{.kind = Failure::device,
                  .why = "the validation layer: " + s.messages.front() +
                         (s.messages.size() > 1 ? std::format(" (and {} more)", s.messages.size() - 1) : "")});
    }
    return {};
}

/// A direct ByteBuffer's bytes, or an empty span.
[[nodiscard]] std::span<std::byte> bytes_of(JNIEnv* env, jobject buffer) {
    void* p = buffer != nullptr ? env->GetDirectBufferAddress(buffer) : nullptr;
    const jlong n = buffer != nullptr ? env->GetDirectBufferCapacity(buffer) : -1;
    if (p == nullptr || n < 0) {
        return {};
    }
    return {static_cast<std::byte*>(p), static_cast<std::size_t>(n)};
}

[[nodiscard]] jint fail(Failure f, std::string why) {
    last_error = std::move(why);
    return static_cast<jint>(f);
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_app_atlas_mapcorecheck_likeness_TestCases_abi(JNIEnv*, jclass) { return case_abi; }

/// The passes a case is drawn with, comma-separated: until the frame core (K7) registers its own, the clear.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_likeness_TestCases_passes(JNIEnv* env, jclass) {
    return env->NewStringUTF("clear");
}

/// Why the last render on this thread failed.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_likeness_TestCases_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(last_error.c_str());
}

/// The device the last case on this thread was drawn on.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_likeness_TestCases_device(JNIEnv* env, jclass) {
    return env->NewStringUTF(last_device.c_str());
}

/// Draws a case into `pixels` (R, G, B, A rows); `owners` stays untouched until the core has an ID pass (K26).
JNIEXPORT jint JNICALL Java_app_atlas_mapcorecheck_likeness_TestCases_render(JNIEnv* env, jclass, jobject case_buffer,
                                                                             jobject pixels, jobject /*owners*/) {
    const auto in = bytes_of(env, case_buffer);
    auto c = read_case(in);
    if (!c) {
        return fail(Failure::bad_case, c.error());
    }
    const auto out = bytes_of(env, pixels);
    if (out.size() < static_cast<std::size_t>(c->width) * c->height * 4) {
        return fail(Failure::bad_case, std::format("the pixel buffer holds {} bytes, the frame needs {}", out.size(),
                                                   static_cast<std::size_t>(c->width) * c->height * 4));
    }
    if (auto drawn = draw(*c, out); !drawn) {
        return fail(drawn.error().kind, drawn.error().why);
    }
    last_error.clear();
    return filled_pixels;
}

} // extern "C"
