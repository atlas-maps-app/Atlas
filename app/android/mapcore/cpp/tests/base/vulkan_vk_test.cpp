// The shader pipeline end to end on a real Vulkan implementation (plan-vector T6, T10): the embedded probe modules
// run on lavapipe under the Khronos validation layer with synchronization validation, and any message fails the test.
// The exact probe's fused multiply-add, emulated and (where the driver has VK_KHR_shader_fma) OpFmaKHR, must equal
// std::fma bit for bit over 10^6 inputs. VMA allocates the buffers and the Vulkan Profiles library checks the device
// against Android's profiles, so both build and work in the core's dialect.
//
// Where Vulkan comes from: ATLAS_VULKAN_LOADER (the loader library) with the loader's own variables (VK_DRIVER_FILES,
// VK_ADD_LAYER_PATH), else the Android SDK's emulator package, which carries the loader, lavapipe and the validation
// layer (plan-vector N8). Without either, the tests are skipped, unless ATLAS_REQUIRE_VULKAN=1 makes that a failure
// (CI and the presets set it).
#include "base/vulkan.hpp"

#define VMA_STATIC_VULKAN_FUNCTIONS 0
#define VMA_DYNAMIC_VULKAN_FUNCTIONS 1
// The allocator's implementation is the core's one copy, in gpu/Memory.cpp, compiled with the same two settings.
#include <vk_mem_alloc.h>

#define VP_USE_OBJECT
#include "gen/layouts.hpp"
#include "gen/spirv/modules.hpp"
#include "gen/spirv/probe/exact_probe.hpp"
#include "gen/spirv/probe/exact_probe_fmakhr.hpp"
#include "gen/spirv/probe/exact_probe_glsl_fma_probe.hpp"
#include "gen/spirv/probe/input_probe.hpp"
#include "gen/spirv/probe/layout_probe.hpp"
#include "gen/spirv/probe/probe.hpp"
#include "tests/base/end_of_run.hpp"
#include "tests/base/fma_inputs.hpp"
#include "tests/base/require.hpp"
#include "tools/nocontract/spirv.hpp"

#include <doctest/doctest.h>
#include <vulkan/vulkan_profiles.hpp>

#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <expected>
#include <filesystem>
#include <memory>
#include <mutex>
#include <optional>
#include <span>
#include <string>
#include <utility>
#include <vector>

namespace {

namespace fs = std::filesystem;

[[nodiscard]] std::string environment(const char* name) {
    const char* value = std::getenv(name); // NOLINT(concurrency-mt-unsafe): read before any thread starts
    return value == nullptr ? std::string() : std::string(value);
}

/// The Vulkan loader to open, after pointing the loader at lavapipe and the validation layer when they come from the
/// emulator package. Empty when there is none.
[[nodiscard]] std::string find_loader() {
    if (std::string loader = environment("ATLAS_VULKAN_LOADER"); !loader.empty()) return loader;
    std::vector<fs::path> sdks;
    for (const char* name : {"ANDROID_HOME", "ANDROID_SDK_ROOT"}) {
        if (const std::string sdk = environment(name); !sdk.empty()) sdks.emplace_back(sdk);
    }
    if (const std::string home = environment("HOME"); !home.empty()) {
        sdks.push_back(fs::path(home) / "Library/Android/sdk");
        sdks.push_back(fs::path(home) / "Android/Sdk");
    }
    for (const fs::path& sdk : sdks) {
        const fs::path dir = sdk / "emulator/lib64/vulkan";
        for (const char* name : {"libvulkan.dylib", "libvulkan.so.1", "libvulkan.so"}) {
            if (!fs::exists(dir / name) || !fs::exists(dir / "lvp_icd.json")) continue;
            if (environment("VK_DRIVER_FILES").empty() && environment("VK_ICD_FILENAMES").empty()) {
                ::setenv("VK_DRIVER_FILES", (dir / "lvp_icd.json").c_str(), 0); // NOLINT(concurrency-mt-unsafe)
            }
            if (environment("VK_ADD_LAYER_PATH").empty() && fs::exists(dir / "layers")) {
                ::setenv("VK_ADD_LAYER_PATH", (dir / "layers").c_str(), 0); // NOLINT(concurrency-mt-unsafe)
            }
            return (dir / name).string();
        }
    }
    return {};
}

[[nodiscard]] bool vulkan_required() { return environment("ATLAS_REQUIRE_VULKAN") == "1"; }

/// The validation layer's warnings and errors, from whichever thread reports them.
struct Messages {
    std::mutex mutex;
    std::vector<std::string> list;
};

VKAPI_ATTR vk::Bool32 VKAPI_CALL on_message(vk::DebugUtilsMessageSeverityFlagBitsEXT /*severity*/,
                                            vk::DebugUtilsMessageTypeFlagsEXT /*types*/,
                                            const vk::DebugUtilsMessengerCallbackDataEXT* data, void* user) {
    auto* messages = static_cast<Messages*>(user);
    const std::scoped_lock lock(messages->mutex);
    // The message with its id (the VUID), so a test can tell the validator's verdict from a device's limits.
    std::string text = data->pMessageIdName == nullptr ? std::string() : std::string(data->pMessageIdName) + ": ";
    text += data->pMessage == nullptr ? "(no message)" : data->pMessage;
    messages->list.push_back(std::move(text));
    return vk::False;
}

/// The Vulkan loader, opened by path and closed when done.
class Loader {
public:
    explicit Loader(const std::string& path) : handle_(::dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL)) {}
    Loader(const Loader&) = delete;
    Loader& operator=(const Loader&) = delete;
    Loader(Loader&&) = delete;
    Loader& operator=(Loader&&) = delete;
    ~Loader() {
        if (handle_ != nullptr) ::dlclose(handle_);
    }
    [[nodiscard]] PFN_vkGetInstanceProcAddr get_instance_proc_addr() const {
        return handle_ == nullptr
                   ? nullptr
                   : reinterpret_cast<PFN_vkGetInstanceProcAddr>(::dlsym(handle_, "vkGetInstanceProcAddr")); // NOLINT
    }

private:
    void* handle_;
};

/// A buffer from VMA, mapped, destroyed with its allocator. One that could not be made has failed the test case, and
/// writes nothing and reads zeros.
class Buffer {
public:
    Buffer(VmaAllocator allocator, vk::DeviceSize size) : allocator_(allocator), size_(size) {
        const VkBufferCreateInfo info{.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
                                      .size = size,
                                      .usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                                      .sharingMode = VK_SHARING_MODE_EXCLUSIVE};
        const VmaAllocationCreateInfo allocation{.flags = VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT |
                                                          VMA_ALLOCATION_CREATE_MAPPED_BIT,
                                                 .usage = VMA_MEMORY_USAGE_AUTO};
        VmaAllocationInfo result{};
        const VkResult made = vmaCreateBuffer(allocator, &info, &allocation, &buffer_, &allocation_, &result);
        ATLAS_REQUIRE(made == VK_SUCCESS, "vmaCreateBuffer: " << vk::to_string(static_cast<vk::Result>(made)));
        ATLAS_REQUIRE(result.pMappedData != nullptr, "VMA mapped no memory");
        mapped_ = result.pMappedData;
    }
    Buffer(const Buffer&) = delete;
    Buffer& operator=(const Buffer&) = delete;
    Buffer(Buffer&&) = delete;
    Buffer& operator=(Buffer&&) = delete;
    ~Buffer() { vmaDestroyBuffer(allocator_, buffer_, allocation_); }

    template <class T>
    void write(std::span<const T> values) {
        ATLAS_REQUIRE(mapped_ != nullptr, "the buffer was not made");
        ATLAS_REQUIRE(values.size_bytes() <= size_, values.size_bytes() << " bytes into a buffer of " << size_);
        std::memcpy(mapped_, values.data(), values.size_bytes());
        ATLAS_REQUIRE(vmaFlushAllocation(allocator_, allocation_, 0, VK_WHOLE_SIZE) == VK_SUCCESS);
    }
    template <class T>
    std::vector<T> read(std::size_t count) {
        ATLAS_REQUIRE_OR_RETURN(std::vector<T>(count), mapped_ != nullptr, "the buffer was not made");
        ATLAS_REQUIRE_OR_RETURN(std::vector<T>(count), count * sizeof(T) <= size_,
                                count * sizeof(T) << " bytes from a buffer of " << size_);
        ATLAS_REQUIRE_OR_RETURN(std::vector<T>(count),
                                vmaInvalidateAllocation(allocator_, allocation_, 0, VK_WHOLE_SIZE) == VK_SUCCESS);
        std::vector<T> out(count);
        std::memcpy(out.data(), mapped_, count * sizeof(T));
        return out;
    }
    [[nodiscard]] vk::Buffer handle() const noexcept { return buffer_; }

private:
    VmaAllocator allocator_;
    vk::DeviceSize size_;
    VkBuffer buffer_ = VK_NULL_HANDLE;
    VmaAllocation allocation_ = nullptr;
    void* mapped_ = nullptr;
};

/// One Vulkan device for the tests: lavapipe's, under the validation layer, with a compute queue and VMA.
class Gpu {
public:
    /// The device, or why there is none.
    static std::expected<std::unique_ptr<Gpu>, std::string> open() {
        const std::string loader = find_loader();
        if (loader.empty())
            return std::unexpected("no Vulkan loader (set ATLAS_VULKAN_LOADER or install the emulator)");
        auto gpu =
            std::unique_ptr<Gpu>(new Gpu(loader)); // NOLINT(cppcoreguidelines-owning-memory): private constructor
        if (std::string why = gpu->start(); !why.empty()) return std::unexpected(why);
        return gpu;
    }

    Gpu(const Gpu&) = delete;
    Gpu& operator=(const Gpu&) = delete;
    Gpu(Gpu&&) = delete;
    Gpu& operator=(Gpu&&) = delete;
    ~Gpu() {
        if (allocator_ != nullptr) vmaDestroyAllocator(allocator_);
    }

    [[nodiscard]] const vk::raii::Device& device() const { return device_; }
    [[nodiscard]] VmaAllocator allocator() const { return allocator_; }
    [[nodiscard]] bool has_fma() const { return has_fma_; }
    [[nodiscard]] const std::string& name() const { return name_; }
    [[nodiscard]] std::vector<std::string> take_messages() {
        const std::scoped_lock lock(messages_.mutex);
        return std::exchange(messages_.list, {});
    }
    [[nodiscard]] std::vector<std::string> profiles() const { return profiles_; }
    [[nodiscard]] const std::string& profile_status() const { return profile_status_; }

    /// Runs a compute entry point over storage buffers bound at 0, 1, ...; waits for it. A step that fails fails the
    /// test case and ends the run there.
    void run(std::span<const std::uint32_t> spirv, const char* entry, std::span<const std::byte> push,
             std::span<Buffer* const> buffers, std::uint32_t groups) {
        // A buffer that could not be made has failed the test case already; it is never bound.
        for (const Buffer* b : buffers) ATLAS_REQUIRE(b->handle(), "a buffer to bind was not made");
        std::vector<vk::DescriptorSetLayoutBinding> bindings;
        bindings.reserve(buffers.size());
        for (std::uint32_t i = 0; i < buffers.size(); ++i) {
            bindings.push_back({.binding = i,
                                .descriptorType = vk::DescriptorType::eStorageBuffer,
                                .descriptorCount = 1,
                                .stageFlags = vk::ShaderStageFlagBits::eCompute});
        }
        auto set_layout = device_.createDescriptorSetLayout(
            {.bindingCount = static_cast<std::uint32_t>(bindings.size()), .pBindings = bindings.data()});
        ATLAS_REQUIRE(set_layout.has_value(), vk::to_string(set_layout.error()));
        const vk::PushConstantRange range{.stageFlags = vk::ShaderStageFlagBits::eCompute,
                                          .offset = 0,
                                          .size = static_cast<std::uint32_t>(push.size())};
        auto layout = device_.createPipelineLayout({.setLayoutCount = 1,
                                                    .pSetLayouts = &**set_layout,
                                                    .pushConstantRangeCount = 1,
                                                    .pPushConstantRanges = &range});
        ATLAS_REQUIRE(layout.has_value(), vk::to_string(layout.error()));
        auto module = device_.createShaderModule({.codeSize = spirv.size_bytes(), .pCode = spirv.data()});
        ATLAS_REQUIRE(module.has_value(), vk::to_string(module.error()));
        auto pipeline = device_.createComputePipeline(
            nullptr, {.stage = {.stage = vk::ShaderStageFlagBits::eCompute, .module = **module, .pName = entry},
                      .layout = **layout});
        ATLAS_REQUIRE(pipeline.has_value(), vk::to_string(pipeline.error()));
        const vk::DescriptorPoolSize pool_size{.type = vk::DescriptorType::eStorageBuffer,
                                               .descriptorCount = static_cast<std::uint32_t>(buffers.size())};
        auto pool = device_.createDescriptorPool({.flags = vk::DescriptorPoolCreateFlagBits::eFreeDescriptorSet,
                                                  .maxSets = 1,
                                                  .poolSizeCount = 1,
                                                  .pPoolSizes = &pool_size});
        ATLAS_REQUIRE(pool.has_value(), vk::to_string(pool.error()));
        auto sets = device_.allocateDescriptorSets(
            {.descriptorPool = **pool, .descriptorSetCount = 1, .pSetLayouts = &**set_layout});
        ATLAS_REQUIRE(sets.has_value(), vk::to_string(sets.error()));
        std::vector<vk::DescriptorBufferInfo> infos;
        infos.reserve(buffers.size());
        for (Buffer* b : buffers) infos.push_back({.buffer = b->handle(), .offset = 0, .range = vk::WholeSize});
        std::vector<vk::WriteDescriptorSet> writes;
        writes.reserve(buffers.size());
        for (std::uint32_t i = 0; i < buffers.size(); ++i) {
            writes.push_back({.dstSet = *sets->front(),
                              .dstBinding = i,
                              .descriptorCount = 1,
                              .descriptorType = vk::DescriptorType::eStorageBuffer,
                              .pBufferInfo = &infos[i]});
        }
        device_.updateDescriptorSets(writes, {});

        auto command_pool = device_.createCommandPool({.queueFamilyIndex = queue_family_});
        ATLAS_REQUIRE(command_pool.has_value(), vk::to_string(command_pool.error()));
        auto commands = device_.allocateCommandBuffers(
            {.commandPool = **command_pool, .level = vk::CommandBufferLevel::ePrimary, .commandBufferCount = 1});
        ATLAS_REQUIRE(commands.has_value(), vk::to_string(commands.error()));
        const vk::raii::CommandBuffer& cmd = commands->front();
        auto begun = cmd.begin({.flags = vk::CommandBufferUsageFlagBits::eOneTimeSubmit});
        ATLAS_REQUIRE(begun.has_value(), vk::to_string(begun.error()));
        cmd.bindPipeline(vk::PipelineBindPoint::eCompute, **pipeline);
        cmd.bindDescriptorSets(vk::PipelineBindPoint::eCompute, **layout, 0, *sets->front(), {});
        cmd.pushConstants(**layout, vk::ShaderStageFlagBits::eCompute, 0,
                          vk::ArrayProxy<const std::byte>(static_cast<std::uint32_t>(push.size()), push.data()));
        cmd.dispatch(groups, 1, 1);
        const vk::MemoryBarrier to_host{.srcAccessMask = vk::AccessFlagBits::eShaderWrite,
                                        .dstAccessMask = vk::AccessFlagBits::eHostRead};
        cmd.pipelineBarrier(vk::PipelineStageFlagBits::eComputeShader, vk::PipelineStageFlagBits::eHost, {}, to_host,
                            {}, {});
        auto ended = cmd.end();
        ATLAS_REQUIRE(ended.has_value(), vk::to_string(ended.error()));
        auto fence = device_.createFence({});
        ATLAS_REQUIRE(fence.has_value(), vk::to_string(fence.error()));
        const vk::CommandBuffer raw = *cmd;
        auto submitted = queue_.submit(vk::SubmitInfo{.commandBufferCount = 1, .pCommandBuffers = &raw}, **fence);
        ATLAS_REQUIRE(submitted.has_value(), vk::to_string(submitted.error()));
        const vk::Result waited = device_.waitForFences(**fence, vk::True, UINT64_MAX);
        ATLAS_REQUIRE(waited == vk::Result::eSuccess, vk::to_string(waited));
    }

private:
    explicit Gpu(const std::string& loader) : library_(loader) {}

    std::string start() {
        const PFN_vkGetInstanceProcAddr get_instance_proc_addr = library_.get_instance_proc_addr();
        if (get_instance_proc_addr == nullptr) return "cannot open the Vulkan loader";
        context_.emplace(get_instance_proc_addr);

        bool has_validation = false;
        if (auto layers = context_->enumerateInstanceLayerProperties()) {
            for (const auto& l : *layers)
                has_validation |= std::string_view(l.layerName) == "VK_LAYER_KHRONOS_validation";
        }
        if (!has_validation && vulkan_required()) return "the Khronos validation layer is missing";
        const std::array<const char*, 1> layer_names{"VK_LAYER_KHRONOS_validation"};
        const std::array<const char*, 1> extensions{vk::EXTDebugUtilsExtensionName};
        const std::array<vk::ValidationFeatureEnableEXT, 1> enables{
            vk::ValidationFeatureEnableEXT::eSynchronizationValidation};
        const vk::ValidationFeaturesEXT features{.enabledValidationFeatureCount =
                                                     static_cast<std::uint32_t>(enables.size()),
                                                 .pEnabledValidationFeatures = enables.data()};
        const vk::ApplicationInfo app{.pApplicationName = "atlas_tests", .apiVersion = vk::ApiVersion11};
        auto instance = context_->createInstance({.pNext = has_validation ? &features : nullptr,
                                                  .pApplicationInfo = &app,
                                                  .enabledLayerCount = has_validation ? 1U : 0U,
                                                  .ppEnabledLayerNames = layer_names.data(),
                                                  .enabledExtensionCount = has_validation ? 1U : 0U,
                                                  .ppEnabledExtensionNames = extensions.data()});
        if (!instance) return "vkCreateInstance failed: " + vk::to_string(instance.error());
        instance_ = std::move(*instance);
        if (has_validation) {
            auto messenger = instance_.createDebugUtilsMessengerEXT(
                {.messageSeverity = vk::DebugUtilsMessageSeverityFlagBitsEXT::eWarning |
                                    vk::DebugUtilsMessageSeverityFlagBitsEXT::eError,
                 .messageType = vk::DebugUtilsMessageTypeFlagBitsEXT::eGeneral |
                                vk::DebugUtilsMessageTypeFlagBitsEXT::eValidation |
                                vk::DebugUtilsMessageTypeFlagBitsEXT::ePerformance,
                 .pfnUserCallback = &on_message,
                 .pUserData = &messages_});
            if (!messenger) return "no debug messenger";
            messenger_ = std::move(*messenger);
        }

        auto devices = instance_.enumeratePhysicalDevices();
        if (!devices || devices->empty()) return "no Vulkan device";
        physical_ = std::move(devices->front());
        const vk::PhysicalDeviceProperties properties = physical_.getProperties();
        name_ = std::string(properties.deviceName.data());

        // VK_KHR_shader_fma, for the OpFmaKHR variant.
        bool has_fma_extension = false;
        if (auto exts = physical_.enumerateDeviceExtensionProperties()) {
            for (const auto& e : *exts)
                has_fma_extension |= std::string_view(e.extensionName) == vk::KHRShaderFmaExtensionName;
        }
        if (has_fma_extension) {
            const auto chain =
                physical_.getFeatures2<vk::PhysicalDeviceFeatures2, vk::PhysicalDeviceShaderFmaFeaturesKHR>();
            has_fma_ = chain.get<vk::PhysicalDeviceShaderFmaFeaturesKHR>().shaderFmaFloat32 == vk::True;
        }

        const auto draw =
            physical_.getFeatures2<vk::PhysicalDeviceFeatures2, vk::PhysicalDeviceShaderDrawParametersFeatures>();
        const bool draw_parameters =
            draw.get<vk::PhysicalDeviceShaderDrawParametersFeatures>().shaderDrawParameters == vk::True;
        if (!draw_parameters) return "no shaderDrawParameters (the probe's vertex entry needs it)";

        const auto families = physical_.getQueueFamilyProperties();
        bool found = false;
        for (std::uint32_t i = 0; i < families.size(); ++i) {
            if (families[i].queueFlags & vk::QueueFlagBits::eCompute) {
                queue_family_ = i;
                found = true;
                break;
            }
        }
        if (!found) return "no compute queue";
        const float priority = 1.0F;
        const vk::DeviceQueueCreateInfo queue_info{
            .queueFamilyIndex = queue_family_, .queueCount = 1, .pQueuePriorities = &priority};
        vk::PhysicalDeviceShaderFmaFeaturesKHR enable_fma{.shaderFmaFloat32 = vk::True};
        vk::PhysicalDeviceShaderDrawParametersFeatures enable_draw{.pNext = has_fma_ ? &enable_fma : nullptr,
                                                                   .shaderDrawParameters = vk::True};
        const std::array<const char*, 1> device_extensions{vk::KHRShaderFmaExtensionName};
        auto device = physical_.createDevice({.pNext = &enable_draw,
                                              .queueCreateInfoCount = 1,
                                              .pQueueCreateInfos = &queue_info,
                                              .enabledExtensionCount = has_fma_ ? 1U : 0U,
                                              .ppEnabledExtensionNames = device_extensions.data()});
        if (!device) return "vkCreateDevice failed: " + vk::to_string(device.error());
        device_ = std::move(*device);
        queue_ = device_.getQueue(queue_family_, 0);

        VmaVulkanFunctions functions{};
        functions.vkGetInstanceProcAddr = instance_.getDispatcher()->vkGetInstanceProcAddr;
        functions.vkGetDeviceProcAddr = device_.getDispatcher()->vkGetDeviceProcAddr;
        const VmaAllocatorCreateInfo allocator_info{.physicalDevice = *physical_,
                                                    .device = *device_,
                                                    .pVulkanFunctions = &functions,
                                                    .instance = *instance_,
                                                    .vulkanApiVersion = VK_API_VERSION_1_1};
        if (vmaCreateAllocator(&allocator_info, &allocator_) != VK_SUCCESS) return "vmaCreateAllocator failed";

        // Android's Vulkan profiles, through the Profiles library's object API (no global state).
        // The library checks the global entry points when the object is made, so they come with it.
        VpFunctionsCreateInfo vp_info{};
        vp_info.GetInstanceProcAddr = context_->getDispatcher()->vkGetInstanceProcAddr;
        vp_info.EnumerateInstanceVersion = context_->getDispatcher()->vkEnumerateInstanceVersion;
        vp_info.EnumerateInstanceExtensionProperties =
            context_->getDispatcher()->vkEnumerateInstanceExtensionProperties;
        vp_info.CreateInstance = context_->getDispatcher()->vkCreateInstance;
        VpFunctions vp = nullptr;
        const VkResult created = vpCreateFunctions(&vp_info, nullptr, &vp);
        if (created != VK_SUCCESS) profile_status_ = "vpCreateFunctions: " + std::to_string(created);
        if (created == VK_SUCCESS) {
            const VkResult global = vpInitializeGlobalFunctions(vp, vp_info.GetInstanceProcAddr);
            const VkResult instance_functions = vpInitializeInstanceFunctions(vp, *instance_, 0);
            std::uint32_t count = 0;
            const VkResult listed = vpGetProfiles(vp, &count, nullptr);
            profile_status_ = "global " + std::to_string(global) + ", instance " + std::to_string(instance_functions) +
                              ", list " + std::to_string(listed) + ", " + std::to_string(count) + " profiles";
            std::vector<VpProfileProperties> all(count);
            (void)vpGetProfiles(vp, &count, all.data());
            for (const VpProfileProperties& p : all) {
                VkBool32 supported = VK_FALSE;
                const VkResult r = vpGetPhysicalDeviceProfileSupport(vp, *instance_, *physical_, &p, &supported);
                profiles_.push_back(std::string(p.profileName) + (r != VK_SUCCESS   ? " (error)"
                                                                  : supported != 0U ? " yes"
                                                                                    : " no"));
            }
        }
        if (vp != nullptr) vpDestroyFunctions(vp, nullptr);
        return {};
    }

    Loader library_;
    std::optional<vk::raii::Context> context_;
    vk::raii::Instance instance_{nullptr};
    vk::raii::DebugUtilsMessengerEXT messenger_{nullptr};
    vk::raii::PhysicalDevice physical_{nullptr};
    vk::raii::Device device_{nullptr};
    vk::raii::Queue queue_{nullptr};
    VmaAllocator allocator_ = nullptr;
    std::uint32_t queue_family_ = 0;
    bool has_fma_ = false;
    std::string name_;
    std::vector<std::string> profiles_;
    std::string profile_status_ = "vpCreateFunctions failed";
    Messages messages_;
};

/// The device the tests here share, opened by the first of them and released when the run ends (not by a static
/// destructor, which would run into the validation layer's own exit-time teardown).
std::expected<std::unique_ptr<Gpu>, std::string>& shared_gpu() {
    static std::expected<std::unique_ptr<Gpu>, std::string> opened = std::unexpected(std::string("not opened"));
    return opened;
}

/// The shared device; nullptr when Vulkan is missing (and not required).
Gpu* gpu() {
    static const bool opened_once = [] {
        shared_gpu() = Gpu::open();
        atlas::test::at_end_of_run([] { shared_gpu() = std::unexpected(std::string("released")); });
        return true;
    }();
    (void)opened_once;
    auto& opened = shared_gpu();
    if (!opened) {
        if (vulkan_required()) {
            FAIL("Vulkan is required (ATLAS_REQUIRE_VULKAN=1) but: " << opened.error());
        } else {
            MESSAGE("skipped, no Vulkan: " << opened.error());
        }
        return nullptr;
    }
    return opened->get();
}

template <class T>
std::span<const std::byte> bytes_of(const T& value) {
    return std::as_bytes(std::span<const T, 1>(&value, 1));
}

/// The exact probe's results for the inputs, from one of its modules.
std::vector<float> run_fma(Gpu& g, std::span<const std::uint32_t> module,
                           const std::vector<std::array<float, 4>>& inputs, const char* entry = "fma_probe") {
    Buffer operands(g.allocator(), inputs.size() * sizeof(std::array<float, 4>));
    Buffer results(g.allocator(), inputs.size() * sizeof(float));
    operands.write(std::span<const std::array<float, 4>>(inputs));
    atlas::layouts::probe_exact_probe::PushConstants push{};
    push.count = static_cast<std::uint32_t>(inputs.size());
    const std::array<Buffer*, 2> buffers{&operands, &results};
    g.run(module, entry, bytes_of(push), buffers, static_cast<std::uint32_t>((inputs.size() + 63) / 64));
    return results.read<float>(inputs.size());
}

/// How many results differ from std::fma, bit for bit.
std::size_t mismatches(const std::vector<std::array<float, 4>>& inputs, const std::vector<float>& results) {
    std::size_t n = 0;
    for (std::size_t i = 0; i < inputs.size(); ++i) {
        const float expected = std::fma(inputs[i][0], inputs[i][1], inputs[i][2]);
        if (std::bit_cast<std::uint32_t>(results[i]) != std::bit_cast<std::uint32_t>(expected)) {
            if (n++ < 3)
                MESSAGE("fma(" << inputs[i][0] << ", " << inputs[i][1] << ", " << inputs[i][2] << "): " << results[i]
                               << " on the GPU, " << expected << " by std::fma");
        }
    }
    return n;
}

/// The module without its NoContraction decorations: what an ordinary compile would have given the driver (the module
/// as it is when it cannot be read, which fails the test case).
std::vector<std::uint32_t> contractible(std::span<const std::uint32_t> module) {
    const auto code = atlas::spirv::instructions(module);
    ATLAS_REQUIRE_OR_RETURN(std::vector<std::uint32_t>(module.begin(), module.end()), code.has_value(),
                            "the module cannot be read: error " << static_cast<int>(code.error()));
    std::vector<std::uint32_t> out(module.begin(),
                                   module.begin() + static_cast<std::ptrdiff_t>(atlas::spirv::header_words));
    for (const auto& in : *code) {
        const auto ops = in.operands();
        if (in.opcode == atlas::spirv::op_decorate && ops.size() == 2 &&
            ops[1] == atlas::spirv::decoration_no_contraction)
            continue;
        out.insert(out.end(), in.words.begin(), in.words.end());
    }
    return out;
}

} // namespace

TEST_CASE("Vulkan: the device, its validation layer and Android's profiles") {
    Gpu* g = gpu();
    if (g == nullptr) return;
    MESSAGE("device: " << g->name() << ", VK_KHR_shader_fma: " << std::string(g->has_fma() ? "yes" : "no"));
    MESSAGE("Vulkan profiles library: " << g->profile_status());
    for (const auto& p : g->profiles()) MESSAGE("profile " << p);
    CHECK(g->profiles().size() >= 3); // AVP 2021, 2022 and 2025 at least
    // Every probe module is accepted by the driver and the validation layer (which runs spirv-val on it).
    for (const std::span<const std::uint32_t> spirv :
         {std::span<const std::uint32_t>(atlas::spirv::probe_probe),
          std::span<const std::uint32_t>(atlas::spirv::probe_exact_probe),
          std::span<const std::uint32_t>(atlas::spirv::probe_exact_probe_glsl_fma_probe),
          std::span<const std::uint32_t>(atlas::spirv::probe_input_probe),
          std::span<const std::uint32_t>(atlas::spirv::probe_layout_probe)}) {
        CHECK(g->device().createShaderModule({.codeSize = spirv.size_bytes(), .pCode = spirv.data()}).has_value());
    }
    const auto messages = g->take_messages();
    for (const auto& m : messages) MESSAGE("validation: " << m);
    CHECK(messages.empty());
}

TEST_CASE("Vulkan: the probe's compute entry runs, with its push constants laid out as reflected") {
    Gpu* g = gpu();
    if (g == nullptr) return;
    constexpr std::uint32_t count = 4096;
    std::vector<float> values(count);
    for (std::uint32_t i = 0; i < count; ++i) values[i] = static_cast<float>(i);
    Buffer buffer(g->allocator(), count * sizeof(float));
    buffer.write(std::span<const float>(values));
    atlas::layouts::probe_probe::PushConstants push{};
    push.scale = 2.0F;
    push.bias = 1.0F;
    push.count = count - 1; // the last value must stay as it is
    const std::array<Buffer*, 1> buffers{&buffer};
    g->run(atlas::spirv::probe_probe, "scale_bias", bytes_of(push), buffers, count / 64);
    const auto out = buffer.read<float>(count);
    bool all = true;
    for (std::uint32_t i = 0; i + 1 < count; ++i) all &= out[i] == (2.0F * static_cast<float>(i)) + 1.0F;
    CHECK(all);
    CHECK(out[count - 1] == static_cast<float>(count - 1));
    const auto messages = g->take_messages();
    for (const auto& m : messages) MESSAGE("validation: " << m);
    CHECK(messages.empty());
}

namespace {

// The layer's findings when it is handed `words` as a shader module: its SPIR-V validator's verdict and the rest.
struct Verdict {
    bool created = false;
    std::vector<std::string> invalid; ///< SPIRV-Tools rejected the module (VUID-VkShaderModuleCreateInfo-pCode-08737)
    std::vector<std::string> limits;  ///< a capability, extension or feature the test device does not enable
    std::vector<std::string> other;   ///< anything else
};

Verdict validate(Gpu& g, std::span<const std::uint32_t> words) {
    (void)g.take_messages();
    Verdict v;
    v.created = g.device().createShaderModule({.codeSize = words.size_bytes(), .pCode = words.data()}).has_value();
    for (auto& m : g.take_messages()) {
        if (m.contains("pCode-08737")) {
            v.invalid.push_back(std::move(m));
        } else if (m.contains("pCode-08740") || m.contains("pCode-08742") || m.contains(" was not enabled")) {
            v.limits.push_back(std::move(m));
        } else {
            v.other.push_back(std::move(m));
        }
    }
    return v;
}

} // namespace

TEST_CASE("Vulkan: every embedded module passes the validation layer's SPIR-V validator, as embedded") {
    // slangc validates each module as it compiles it, but nocontract rewrites it afterwards (capabilities,
    // decorations), and what is embedded is the rewritten module. The validation layer runs a current SPIRV-Tools on
    // every module it is given (the NDK's spirv-val, v2022.4, is too old: it knows neither Slang's source language nor
    // FmaKHR), so each module of every package goes through it here. A capability the test device does not enable
    // (FmaKHR on lavapipe) is reported, not failed: the per-module tests own those, and the validator still judges the
    // module.
    Gpu* g = gpu();
    if (g == nullptr) return;
    // First the validator itself: a module with a float type of 13 bits must be rejected.
    std::vector<std::uint32_t> broken(atlas::spirv::probe_probe.begin(), atlas::spirv::probe_probe.end());
    const auto code = atlas::spirv::instructions(broken);
    ATLAS_REQUIRE(code.has_value(), "the probe module cannot be read: error " << static_cast<int>(code.error()));
    bool planted = false;
    std::size_t at = atlas::spirv::header_words;
    for (const auto& in : *code) {
        if (in.opcode == 22 /* OpTypeFloat */ && !planted) {
            broken[at + 2] = 13;
            planted = true;
        }
        at += in.words.size();
    }
    ATLAS_REQUIRE(planted, "the probe module declares no float type");
    const Verdict rejected = validate(*g, broken);
    CHECK(!rejected.invalid.empty());

    std::size_t validated = 0;
    for (const atlas::spirv::Module& m : atlas::spirv::modules) {
        const Verdict v = validate(*g, m.words);
        CHECK_MESSAGE(v.created, m.name);
        for (const auto& message : v.invalid) FAIL_CHECK(m.name << ": " << message);
        for (const auto& message : v.other) FAIL_CHECK(m.name << ": " << message);
        for (const auto& message : v.limits) MESSAGE(m.name << ", a device limit: " << message);
        ++validated;
    }
    MESSAGE(validated << " of " << atlas::spirv::modules.size() << " modules validated");
    CHECK(validated > 0);
}

TEST_CASE("Vulkan: the GPU reads and writes the generated layouts' bytes as C++ wrote them") {
    Gpu* g = gpu();
    if (g == nullptr) return;
    namespace lp = atlas::layouts::probe_layout_probe;
    lp::PushConstants push{};
    push.scale = 1.5F;
    push.cell = {-7, 9};
    push.tint = {0.25F, 0.5F, 0.75F};
    push.flags = 42;
    push.inner.offset = {3.0F, -4.0F};
    push.inner.id = 77;
    push.weights = {10.0F, 20.0F, 30.0F, 40.0F};
    std::array<lp::Record, 2> records{};
    records[0].position = {1.0F, 2.0F, 3.0F};
    records[0].kind = 5;
    records[0].uv = {0.125F, 0.0625F};
    Buffer record_buffer(g->allocator(), sizeof(records));
    Buffer echo_buffer(g->allocator(), 20 * sizeof(float));
    record_buffer.write(std::span<const lp::Record>(records));
    const std::array<Buffer*, 2> buffers{&record_buffer, &echo_buffer};
    g->run(atlas::spirv::probe_layout_probe, "echo_layout", bytes_of(push), buffers, 1);
    const auto echo = echo_buffer.read<float>(20);
    const std::array<float, 20> expected{1.5F,  -7.0F, 9.0F,  0.25F, 0.5F, 0.75F, 42.0F, 3.0F, -4.0F,  77.0F,
                                         10.0F, 20.0F, 30.0F, 40.0F, 1.0F, 2.0F,  3.0F,  5.0F, 0.125F, 0.0625F};
    for (std::size_t i = 0; i < expected.size(); ++i) CHECK(echo[i] == expected[i]);
    const auto back = record_buffer.read<lp::Record>(2);
    CHECK(back[1].position == push.tint);
    CHECK(back[1].kind == 42U);
    CHECK(back[1].uv == push.inner.offset);
    const auto messages = g->take_messages();
    for (const auto& m : messages) MESSAGE("validation: " << m);
    CHECK(messages.empty());
}

TEST_CASE("Vulkan: the exact probe's fused multiply-add equals std::fma over 10^6 inputs") {
    Gpu* g = gpu();
    if (g == nullptr) return;
    const auto inputs = atlas::test::fma_inputs(1'000'000, 0xA71A5);
    const auto emulated = run_fma(*g, atlas::spirv::probe_exact_probe, inputs);
    CHECK(mismatches(inputs, emulated) == 0);
    // The same module through Slang's GLSL route (the quirk table's fallback), its entry point GLSL's main.
    CHECK(mismatches(inputs, run_fma(*g, atlas::spirv::probe_exact_probe_glsl_fma_probe, inputs, "main")) == 0);
    if (g->has_fma()) {
        CHECK(mismatches(inputs, run_fma(*g, atlas::spirv::probe_exact_probe_fmakhr, inputs)) == 0);
    } else {
        MESSAGE("OpFmaKHR not run: " << g->name() << " lacks VK_KHR_shader_fma");
    }
    // Without its NoContraction decorations the same module is at the driver's mercy: report what this one does.
    const auto loose = run_fma(*g, contractible(atlas::spirv::probe_exact_probe), inputs);
    std::size_t differ = 0;
    for (std::size_t i = 0; i < inputs.size(); ++i) {
        differ += std::bit_cast<std::uint32_t>(loose[i]) != std::bit_cast<std::uint32_t>(emulated[i]) ? 1U : 0U;
    }
    MESSAGE("without NoContraction, " << differ << " of " << inputs.size() << " results change on " << g->name());
    const auto messages = g->take_messages();
    for (const auto& m : messages) MESSAGE("validation: " << m);
    CHECK(messages.empty());
}
