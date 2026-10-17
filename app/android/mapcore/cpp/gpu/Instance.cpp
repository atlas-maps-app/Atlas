// The Vulkan instance: the loader, the instance, the validation layer's messenger (gpu/Instance.hpp).
#include "gpu/Instance.hpp"

#include "base/log.hpp"

#include <algorithm>
#include <array>
#include <cstdlib>
#include <dlfcn.h>
#include <filesystem>
#include <format>
#include <utility>

namespace atlas::gpu {

namespace {

#if !defined(__ANDROID__)
namespace fs = std::filesystem;

[[nodiscard]] std::string environment(const char* name) {
    const char* value =
        std::getenv(name); // NOLINT(concurrency-mt-unsafe): read while opening, before the core's threads
    return value == nullptr ? std::string() : std::string(value);
}

/// On a host without ATLAS_VULKAN_LOADER: the Android emulator package's Vulkan folder (its loader, lavapipe and the
/// validation layer, plan-vector N8), with the loader pointed at lavapipe and the layer unless the environment already
/// names a driver or a layer path. Empty when there is no emulator package.
[[nodiscard]] std::string emulator_loader() {
    std::vector<fs::path> sdks;
    for (const char* name : {"ANDROID_HOME", "ANDROID_SDK_ROOT"}) {
        if (std::string sdk = environment(name); !sdk.empty()) sdks.emplace_back(std::move(sdk));
    }
    if (const std::string home = environment("HOME"); !home.empty()) {
        sdks.push_back(fs::path(home) / "Library/Android/sdk");
        sdks.push_back(fs::path(home) / "Android/Sdk");
    }
    std::error_code ec;
    for (const fs::path& sdk : sdks) {
        const fs::path dir = sdk / "emulator/lib64/vulkan";
        if (!fs::exists(dir / "lvp_icd.json", ec)) continue;
        for (const char* name : {"libvulkan.dylib", "libvulkan.so.1", "libvulkan.so"}) {
            if (!fs::exists(dir / name, ec)) continue;
            if (environment("VK_DRIVER_FILES").empty() && environment("VK_ICD_FILENAMES").empty()) {
                ::setenv("VK_DRIVER_FILES", (dir / "lvp_icd.json").c_str(), 0); // NOLINT(concurrency-mt-unsafe)
            }
            if (environment("VK_ADD_LAYER_PATH").empty() && environment("VK_LAYER_PATH").empty() &&
                fs::exists(dir / "layers", ec)) {
                ::setenv("VK_ADD_LAYER_PATH", (dir / "layers").c_str(), 0); // NOLINT(concurrency-mt-unsafe)
            }
            return (dir / name).string();
        }
    }
    return {};
}
#endif

/// The loaders to try, in order, for an empty path.
[[nodiscard]] std::vector<std::string> platform_loaders() {
#if defined(__ANDROID__)
    return {"libvulkan.so"};
#else
    std::vector<std::string> paths;
    if (std::string own = environment("ATLAS_VULKAN_LOADER"); !own.empty()) return {own};
    if (std::string emulator = emulator_loader(); !emulator.empty()) paths.push_back(std::move(emulator));
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

VKAPI_ATTR vk::Bool32 VKAPI_CALL on_message(vk::DebugUtilsMessageSeverityFlagBitsEXT severity,
                                            vk::DebugUtilsMessageTypeFlagsEXT /*types*/,
                                            const vk::DebugUtilsMessengerCallbackDataEXT* data, void* user) {
    const char* text = data != nullptr && data->pMessage != nullptr ? data->pMessage : "(no message)";
    const char* id = data != nullptr && data->pMessageIdName != nullptr ? data->pMessageIdName : "";
    static_cast<Messages*>(user)->add(std::format("{}: {}", id, text));
    log::write(severity == vk::DebugUtilsMessageSeverityFlagBitsEXT::eError ? log::Level::error : log::Level::warn,
               log::Line("vk validation {}: {}", id, text));
    return vk::False;
}

} // namespace

void Messages::add(std::string message) {
    const std::scoped_lock lock(mutex_);
    list_.push_back(std::move(message));
    ++total_;
}

std::vector<std::string> Messages::take() {
    const std::scoped_lock lock(mutex_);
    return std::exchange(list_, {});
}

std::size_t Messages::count() const {
    const std::scoped_lock lock(mutex_);
    return total_;
}

Loader::Loader(Loader&& other) noexcept
    : handle_(std::exchange(other.handle_, nullptr)), gipa_(std::exchange(other.gipa_, nullptr)),
      path_(std::move(other.path_)) {}

Loader& Loader::operator=(Loader&& other) noexcept {
    if (this != &other) {
        if (handle_ != nullptr) ::dlclose(handle_);
        handle_ = std::exchange(other.handle_, nullptr);
        gipa_ = std::exchange(other.gipa_, nullptr);
        path_ = std::move(other.path_);
    }
    return *this;
}

Loader::~Loader() {
    if (handle_ != nullptr) ::dlclose(handle_);
}

Result<Loader> Loader::open(const std::string& path) {
    const std::vector<std::string> candidates = path.empty() ? platform_loaders() : std::vector<std::string>{path};
    std::string tried;
    for (const std::string& candidate : candidates) {
        void* handle = ::dlopen(candidate.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (handle == nullptr) {
            const char* why =
                ::dlerror(); // NOLINT(concurrency-mt-unsafe): dlerror is per thread (Bionic, glibc, macOS)
            tried += std::format("{}{} ({})", tried.empty() ? "" : "; ", candidate, why == nullptr ? "not found" : why);
            continue;
        }
        Loader loader;
        loader.handle_ = handle;
        loader.path_ = candidate;
        loader.gipa_ = reinterpret_cast<PFN_vkGetInstanceProcAddr>(::dlsym(handle, "vkGetInstanceProcAddr")); // NOLINT
        if (loader.gipa_ == nullptr) return fail(candidate + " has no vkGetInstanceProcAddr");
        return loader;
    }
    return fail("no Vulkan loader: " + (tried.empty() ? std::string("nothing to try") : tried));
}

std::string version_text(std::uint32_t version) {
    return std::format("{}.{}.{}", VK_API_VERSION_MAJOR(version), VK_API_VERSION_MINOR(version),
                       VK_API_VERSION_PATCH(version));
}

bool Instance::has_extension(std::string_view name) const noexcept {
    return std::ranges::find(extensions_, name) != extensions_.end();
}

Instance::~Instance() {
    // The messenger and the instance go before the loader that holds their code.
    messenger_.clear();
    instance_.clear();
    context_.reset();
}

Result<std::unique_ptr<Instance>> Instance::open(const InstanceOptions& options) {
    std::unique_ptr<Instance> self(new Instance()); // NOLINT(cppcoreguidelines-owning-memory): private constructor
    ATLAS_GPU_LET(loader, Loader::open(options.loader));
    self->loader_ = std::move(loader);
    self->context_ = std::make_unique<vk::raii::Context>(self->loader_.get_instance_proc_addr());
    const vk::raii::Context& context = *self->context_;

    // The loader's version: 1.0 loaders have no vkEnumerateInstanceVersion.
    if (context.getDispatcher()->vkEnumerateInstanceVersion != nullptr) {
        if (auto version = context.enumerateInstanceVersion()) self->loader_version_ = *version;
    }
    if (self->loader_version_ < VK_API_VERSION_1_1) {
        return fail(
            std::format("the Vulkan loader is {}; the core needs Vulkan 1.1", version_text(self->loader_version_)));
    }
    self->api_version_ = std::min({self->loader_version_, VK_API_VERSION_1_4, options.max_version});
    // Patch levels are the loader's; the instance asks for the minor version.
    self->api_version_ =
        VK_MAKE_API_VERSION(0, VK_API_VERSION_MAJOR(self->api_version_), VK_API_VERSION_MINOR(self->api_version_), 0);

    std::vector<std::string> available;
    if (auto list = context.enumerateInstanceExtensionProperties()) {
        for (const vk::ExtensionProperties& e : *list) available.emplace_back(e.extensionName.data());
    }
    auto offered = [&](std::string_view name) { return std::ranges::find(available, name) != available.end(); };

    bool layer = false;
    if (options.validation) {
        if (auto layers = context.enumerateInstanceLayerProperties()) {
            for (const vk::LayerProperties& l : *layers)
                layer |= std::string_view(l.layerName.data()) == "VK_LAYER_KHRONOS_validation";
        }
        if (!layer && options.require_validation)
            return fail("the Khronos validation layer was asked for and is missing");
    }

    // Surfaces (the phone's, and the headless one the host tests present to), surface queries for the swapchain's
    // maintenance, and the messenger when validating.
    std::vector<const char*> enable;
    for (const char* name : {vk::KHRSurfaceExtensionName, vk::KHRAndroidSurfaceExtensionName,
                             vk::EXTHeadlessSurfaceExtensionName, vk::KHRGetSurfaceCapabilities2ExtensionName,
                             vk::EXTSurfaceMaintenance1ExtensionName, vk::KHRSurfaceMaintenance1ExtensionName}) {
        if (offered(name)) enable.push_back(name);
    }
    if (layer && offered(vk::EXTDebugUtilsExtensionName)) enable.push_back(vk::EXTDebugUtilsExtensionName);
    const bool debug_utils =
        std::ranges::find(enable, std::string_view(vk::EXTDebugUtilsExtensionName)) != enable.end();

    const std::array<const char*, 1> layer_names{"VK_LAYER_KHRONOS_validation"};
    const std::array<vk::ValidationFeatureEnableEXT, 1> checks{
        vk::ValidationFeatureEnableEXT::eSynchronizationValidation};
    const vk::ValidationFeaturesEXT features{.enabledValidationFeatureCount = static_cast<std::uint32_t>(checks.size()),
                                             .pEnabledValidationFeatures = checks.data()};
    const vk::ApplicationInfo app{.pApplicationName = options.app_name.c_str(),
                                  .applicationVersion = 1,
                                  .pEngineName = "atlasmap",
                                  .engineVersion = 1,
                                  .apiVersion = self->api_version_};
    ATLAS_VK_LET(instance,
                 context.createInstance({.pNext = layer ? &features : nullptr,
                                         .pApplicationInfo = &app,
                                         .enabledLayerCount = layer ? 1U : 0U,
                                         .ppEnabledLayerNames = layer_names.data(),
                                         .enabledExtensionCount = static_cast<std::uint32_t>(enable.size()),
                                         .ppEnabledExtensionNames = enable.data()}),
                 "vkCreateInstance");
    self->instance_ = std::move(instance);
    self->validation_ = layer;
    for (const char* name : enable) self->extensions_.emplace_back(name);

    if (layer && debug_utils) {
        ATLAS_VK_LET(messenger,
                     self->instance_.createDebugUtilsMessengerEXT(
                         {.messageSeverity = vk::DebugUtilsMessageSeverityFlagBitsEXT::eWarning |
                                             vk::DebugUtilsMessageSeverityFlagBitsEXT::eError,
                          .messageType = vk::DebugUtilsMessageTypeFlagBitsEXT::eGeneral |
                                         vk::DebugUtilsMessageTypeFlagBitsEXT::eValidation |
                                         vk::DebugUtilsMessageTypeFlagBitsEXT::ePerformance,
                          .pfnUserCallback = &on_message,
                          .pUserData = &self->messages_}),
                     "the validation layer's messenger");
        self->messenger_ = std::move(messenger);
    }
    return self;
}

} // namespace atlas::gpu
