// The Vulkan instance (plan-vector D5.11): the loader opened at run time, the instance at the highest version the
// loader offers up to 1.4, the surface extensions of the platform, and, on request, the Khronos validation layer with
// synchronization validation, whose messages are kept for the tests (which fail on any) and logged on a phone.
#pragma once

#include "gpu/Error.hpp"

#include <array>
#include <cstdint>
#include <memory>
#include <mutex>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::gpu {

/// How to open Vulkan.
struct InstanceOptions {
    /// Turn on the Khronos validation layer (with synchronization validation) when the loader has it.
    bool validation = false;
    /// Fail when validation is asked for and the layer is missing (the host tests and CI).
    bool require_validation = false;
    /// The loader library; empty for the platform's own (libvulkan.so on Android; on hosts ATLAS_VULKAN_LOADER, else
    /// the Android emulator package's loader pointed at its lavapipe and validation layer, else the system's).
    std::string loader;
    /// The application name the driver sees.
    std::string app_name = "Atlas";
    /// The highest version to ask for (VK_API_VERSION_1_4 by default; the tests ask for 1.1 to check what a Vulkan 1.1
    /// phone allows).
    std::uint32_t max_version = VK_API_VERSION_1_4;
    /// Extensions to treat as absent, the instance's and its devices': the host tests' stand-in for a Vulkan without
    /// them. {"VK_KHR_surface", "VK_KHR_swapchain"} makes this Mac's lavapipe look like the arm64 runner's, which is
    /// Mesa built without a window system. On a host, ATLAS_VULKAN_HIDE (names separated by commas) adds to them for a
    /// whole run; the phone never reads it.
    std::vector<std::string> hide;
};

/// The validation layer's warnings and errors, from whichever thread reports them.
class Messages {
public:
    void add(std::string message);
    /// Everything reported since the last take.
    [[nodiscard]] std::vector<std::string> take();
    [[nodiscard]] std::size_t count() const;
    /// Counts a message a QuietMessages scope dropped, and how many were.
    void quiet();
    [[nodiscard]] std::size_t quieted() const;

private:
    mutable std::mutex mutex_;
    std::vector<std::string> list_;
    std::size_t total_ = 0;
    std::size_t quieted_ = 0;
};

/// While it lives, the validation layer's messages with these ids that the thread making it causes are dropped: not
/// kept for the tests, not logged, only counted (Messages::quieted). For a pipeline whose stages are mismatched by
/// design: the owner and overdraw modes' twins, whose fragment stage reads none of the vertex stage's outputs, which
/// frame/FrameBuilder.cpp's make_pipeline makes in one. The layer checks a pipeline's interface while it is created, on
/// the creating thread, so other threads' messages, and all of them once the scope is gone, are reported as ever.
/// Scopes nest.
class QuietMessages {
public:
    explicit QuietMessages(std::span<const std::string_view> ids) noexcept;
    ~QuietMessages();
    QuietMessages(const QuietMessages&) = delete;
    QuietMessages& operator=(const QuietMessages&) = delete;
    QuietMessages(QuietMessages&&) = delete;
    QuietMessages& operator=(QuietMessages&&) = delete;

    /// Whether a scope alive on this thread drops `id`.
    [[nodiscard]] static bool drops(std::string_view id) noexcept;

private:
    std::span<const std::string_view> ids_;
    const QuietMessages* outer_;
};

/// The layer's ids for an output of the vertex stage that the fragment stage does not read, by the layer's version.
inline constexpr std::array<std::string_view, 3> unconsumed_outputs{
    "WARNING-Shader-OutputNotConsumed", "Undefined-Value-ShaderOutputNotConsumed",
    "Undefined-Value-ShaderOutputNotConsumed-DynamicRendering"};

/// The loader library, opened by path and closed last.
class Loader {
public:
    Loader() = default;
    Loader(const Loader&) = delete;
    Loader& operator=(const Loader&) = delete;
    Loader(Loader&& other) noexcept;
    Loader& operator=(Loader&& other) noexcept;
    ~Loader();

    /// Opens `path`, or the platform's loader when it is empty.
    [[nodiscard]] static Result<Loader> open(const std::string& path);

    [[nodiscard]] PFN_vkGetInstanceProcAddr get_instance_proc_addr() const noexcept { return gipa_; }
    [[nodiscard]] const std::string& path() const noexcept { return path_; }

private:
    void* handle_ = nullptr;
    PFN_vkGetInstanceProcAddr gipa_ = nullptr;
    std::string path_;
};

/// The instance and what it was opened with.
class Instance {
public:
    [[nodiscard]] static Result<std::unique_ptr<Instance>> open(const InstanceOptions& options);

    Instance(const Instance&) = delete;
    Instance& operator=(const Instance&) = delete;
    Instance(Instance&&) = delete;
    Instance& operator=(Instance&&) = delete;
    ~Instance();

    [[nodiscard]] const vk::raii::Context& context() const noexcept { return *context_; }
    [[nodiscard]] const vk::raii::Instance& handle() const noexcept { return instance_; }
    [[nodiscard]] PFN_vkGetInstanceProcAddr get_instance_proc_addr() const noexcept {
        return loader_.get_instance_proc_addr();
    }
    /// The loader's own version (vkEnumerateInstanceVersion).
    [[nodiscard]] std::uint32_t loader_version() const noexcept { return loader_version_; }
    /// The version the instance was created for: the loader's, at most 1.4.
    [[nodiscard]] std::uint32_t api_version() const noexcept { return api_version_; }
    [[nodiscard]] bool validation() const noexcept { return validation_; }
    [[nodiscard]] bool has_extension(std::string_view name) const noexcept;
    [[nodiscard]] const std::vector<std::string>& extensions() const noexcept { return extensions_; }
    /// The extensions hidden from the core (InstanceOptions::hide and ATLAS_VULKAN_HIDE), which read as absent.
    [[nodiscard]] const std::vector<std::string>& hidden() const noexcept { return hidden_; }
    [[nodiscard]] bool hides(std::string_view name) const noexcept;
    [[nodiscard]] const std::string& loader_path() const noexcept { return loader_.path(); }
    [[nodiscard]] Messages& messages() noexcept { return messages_; }

private:
    Instance() = default;

    Loader loader_;
    std::unique_ptr<vk::raii::Context> context_; // Vulkan-Hpp's Context has no empty state
    vk::raii::Instance instance_{nullptr};
    vk::raii::DebugUtilsMessengerEXT messenger_{nullptr};
    Messages messages_;
    std::vector<std::string> extensions_;
    std::vector<std::string> hidden_;
    std::uint32_t loader_version_ = VK_API_VERSION_1_0;
    std::uint32_t api_version_ = VK_API_VERSION_1_0;
    bool validation_ = false;
};

/// A Vulkan version as "1.3.276".
[[nodiscard]] std::string version_text(std::uint32_t version);

} // namespace atlas::gpu
