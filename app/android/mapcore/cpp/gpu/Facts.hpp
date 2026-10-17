// VkFacts (plan-vector T1, D5.3, A0): what the phone's Vulkan offers, read once from its physical device and printed
// as "name: value" lines (logcat's "vkfacts " lines, the VK facts screen, smoke.sh's vkfacts.txt). The facts also
// decide the frame: the frame configuration (A, B, C, or D), the frame route (dynamic rendering with local reads, or a
// render pass with two subpasses), where the composite writes and the frame's colour depth, each with its reason, so
// the owner's paste explains what the core would do on that phone.
//
// The colour depth (plan-vector D5, "Colour depth"): the output may take 10 bits a channel where the surface offers it
// in sRGB-nonlinear (rgb10a2, A2B10G10R10_UNORM_PACK32, else bgr10a2, A2R10G10B10_UNORM_PACK32) and the device can draw
// the frame in it, else 8 bits (rgba8, else bgra8) as before. The same 32 bits a pixel, so no memory or bandwidth more.
// The scene's and the composite's colour attachments follow the output, as a resolve needs: so the format must also be
// a blended colour and input attachment at the frame's sample counts, transient, in lazily allocated memory where
// configuration A needs it, and nothing may read the destination's alpha (rgb10a2 keeps 2 bits of it): no blend reads
// it (gpu/Pipelines.cpp refuses one that would), and the composite reads its samples' colour only. The gamut stays
// sRGB, the renders' palette: Display P3, extended sRGB and HDR are not taken.
//
// In this alpha the frame is 8 bits a channel on every phone (default_depth): the map, VK facts and VK bench's runs.
// 10 bits are drawn only by VK bench's colour stage, after its 8-bit runs are recorded, behind the crash guard's own
// stage (gpu/Guard.hpp's Stage::colour): a driver that crashes there has 10 bits turned off for it and this build of
// the core (Facts::ten_bit_off), and nothing else. Nothing of 10 bits reaches the driver before that stage, not even a
// query: the facts ask the device about the 8-bit formats only, and the 10-bit ones (their format features, their
// sample counts, a tiler's lazily allocated samples) are asked as the stage begins (Device::check_ten_bit). Until then
// VkFacts says so, and what the stage would draw is the surface's offer (decide_colour). 10 bits become the frame's
// default only when a later alpha's evidence shows them safe.
#pragma once

#include "gpu/Instance.hpp"

#include <array>
#include <cstdint>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::gpu {

/// The frame configurations (D5.3): A 4x with transient attachments in lazily allocated memory; B 2x; C 4x in
/// ordinary memory; D 1x with analytic coverage (only if FA is built); X1 is VK bench's 1x reference, never a choice.
enum class FrameConfig : std::uint8_t { a, b, c, d, x1 };

/// The two frame routes (T1): dynamic rendering with local reads, or a VkRenderPass with two subpasses.
enum class Route : std::uint8_t { renderpass, dynamic };

/// The frame forms: the composite subpass (D5.4), or one subpass with the effects per fragment (ONEPASS).
enum class FrameForm : std::uint8_t { composite, onepass };

/// Where the composite writes: into the samples, resolved on chip (tilers), or straight into the 1x image (a GPU that
/// is not a tiler; the render-pass route only).
enum class CompositeTarget : std::uint8_t { resolve, direct };

/// One way of drawing the frame: the configuration, the route, the form and the composite's target.
struct Configuration {
    FrameConfig config = FrameConfig::c;
    Route route = Route::renderpass;
    FrameForm form = FrameForm::composite;
    CompositeTarget target = CompositeTarget::resolve;

    [[nodiscard]] vk::SampleCountFlagBits samples() const noexcept;
    /// Transient attachments in lazily allocated memory (configuration A).
    [[nodiscard]] bool lazy() const noexcept { return config == FrameConfig::a; }
    friend bool operator==(const Configuration&, const Configuration&) = default;
};

[[nodiscard]] std::string_view name_of(FrameConfig config) noexcept;
[[nodiscard]] std::string_view name_of(Route route) noexcept;
[[nodiscard]] std::string_view name_of(FrameForm form) noexcept;
[[nodiscard]] std::string_view name_of(CompositeTarget target) noexcept;
/// "<config> <route> <form> <target>", as VK bench and the log name a configuration ("A renderpass composite resolve").
[[nodiscard]] std::string name_of(const Configuration& configuration);

/// How deep the frame's colour may be: as deep as the surface and the device allow (10 bits a channel where they can:
/// VK bench's colour stage, the tests' 10-bit frames), or 8 bits.
enum class Depth : std::uint8_t { deepest, eight };

/// The frame's depth unless VK bench's colour stage asks for 10 bits: 8 bits a channel on every phone in this alpha,
/// until a later alpha's evidence shows 10 safe (the file's head).
inline constexpr Depth default_depth = Depth::eight;

/// What the device does with one of the frame's colour formats (Facts::colour_formats).
struct ColourFormat {
    vk::Format format = vk::Format::eUndefined;
    /// Asked of the device: the 8-bit formats as the device is chosen (collect_facts), the 10-bit ones only as VK
    /// bench's colour stage begins (Device::check_ten_bit). Until then nothing below is known.
    bool queried = false;
    /// The 1x output: a colour attachment with blending, a blit source with linear filtering (the frosted backdrops'
    /// copy), copied from and into (the host build's readback and fill).
    bool output = false;
    /// The scene's samples: a colour attachment with blending, and the main pass's image of it (colour, input and
    /// transient attachment) at `samples`.
    bool attachment = false;
    vk::SampleCountFlags samples;
    /// Lazily allocated memory holds its transient samples (configuration A): asked of the device by the memory types a
    /// 4x image may take, for the 8-bit formats when it opens and for the 10-bit ones as VK bench's colour stage begins
    /// (Device::check_ten_bit); assumed until then.
    bool lazy = true;
    bool lazy_checked = false;
    /// The format features it lacks for the output or the samples, in words ("" for none).
    std::string lacks;
};

/// The frame's colour: the output's format and colour space, which the scene's and the composite's colour attachments
/// follow (a resolve keeps one format), and why.
struct Colour {
    vk::Format format = vk::Format::eR8G8B8A8Unorm;
    vk::ColorSpaceKHR space = vk::ColorSpaceKHR::eSrgbNonlinear;
    std::string reason;

    /// 10 bits a channel (rgb10a2 or bgr10a2, with 2 bits of alpha).
    [[nodiscard]] bool ten_bit() const noexcept;
};

/// A colour format's short name: rgb10a2 (A2B10G10R10_UNORM_PACK32), bgr10a2 (A2R10G10B10_UNORM_PACK32), rgba8
/// (R8G8B8A8_UNORM), bgra8 (B8G8R8A8_UNORM), else Vulkan-Hpp's name.
[[nodiscard]] std::string colour_name(vk::Format format);

/// Whether `format` holds 10 bits a channel (the two packed formats above).
[[nodiscard]] bool ten_bit(vk::Format format) noexcept;

/// The features the core uses or reports (T1), as the device offers them.
struct FeatureFacts {
    bool sample_rate_shading = false;
    bool clip_distance = false;
    bool astc_ldr = false;
    bool etc2 = false;
    bool dual_src_blend = false;
    bool pipeline_statistics = false;
    bool shader_int16 = false;
    bool shader_int64 = false;
    bool shader_float64 = false; // K5's light in binary64, the port's bits (E0); without it binary32 (E1)
    bool independent_blend = false;
    bool fragment_stores = false;
    bool vertex_stores = false;
    bool sampler_anisotropy = false;
    bool read_without_format = false; // shaderStorageImageReadWithoutFormat
    bool draw_parameters = false;
    bool timeline_semaphore = false;
    bool host_query_reset = false;
    bool separate_depth_stencil_layouts = false;
    bool dynamic_rendering = false;
    bool local_read = false;
    bool synchronization2 = false;
    bool pipeline_cache_control = false;
    bool maintenance4 = false;
    bool shader_fma32 = false;
    bool swapchain_maintenance1 = false;
    bool rasterization_order = false;
    bool multisampled_to_single = false;
};

/// The surface's facts, once a surface exists (the VK facts screen and VK bench attach one).
struct SurfaceFacts {
    bool present = false;
    vk::SurfaceCapabilitiesKHR capabilities{};
    std::vector<vk::SurfaceFormatKHR> formats;
    std::vector<vk::PresentModeKHR> present_modes;
};

/// Everything VK facts prints, read from one physical device.
struct Facts {
    // The loader and the instance.
    std::string loader_path;
    std::uint32_t loader_version = 0;
    std::uint32_t instance_version = 0;
    bool validation = false;
    // The device.
    std::string name;
    vk::PhysicalDeviceType type = vk::PhysicalDeviceType::eOther;
    std::uint32_t vendor_id = 0;
    std::uint32_t device_id = 0;
    std::uint32_t api_version = 0;
    std::uint32_t driver_version = 0;
    bool driver_properties = false;
    vk::DriverId driver_id{};
    std::string driver_name;
    std::string driver_info;
    vk::ConformanceVersion conformance{};
    std::array<std::uint8_t, VK_UUID_SIZE> pipeline_cache_uuid{};
    std::array<std::uint8_t, VK_UUID_SIZE> driver_uuid{};
    std::size_t devices = 0; // how many physical devices the loader listed
    // Its memory, queues, limits, formats, features, extensions and profiles.
    vk::PhysicalDeviceMemoryProperties memory{};
    std::vector<vk::QueueFamilyProperties> queues;
    vk::PhysicalDeviceLimits limits{};
    vk::Format depth_stencil = vk::Format::eUndefined;
    bool d24s8 = false;
    bool d32s8 = false;
    bool rgba8_input = false; // R8G8B8A8_UNORM as a colour and input attachment (the composite)
    /// The frame's colour formats, in the order the choice takes them: rgb10a2, bgr10a2, rgba8, bgra8.
    std::vector<ColourFormat> colour_formats;
    FeatureFacts features;
    std::vector<std::string> extensions; // without the ones the host tests hide (Instance::hidden)
    std::vector<std::string> hidden;     // the extensions the host tests hide, which read as absent
    std::vector<std::pair<std::string, bool>> profiles;
    std::string profiles_status;
    // What the facts decide.
    bool hardware = false;
    bool tiler = false;
    std::string tiler_reason;
    bool lazy_memory = false;
    bool host_visible_device_local = false;
    bool presents = false; // judged for a device that presents (VK_KHR_swapchain needed), not for offscreen work
    std::vector<std::string> missing; // the requirements of T1 the device lacks (empty: the core can run)
    Configuration choice;
    std::string config_reason;
    std::string route_reason;
    std::string target_reason;
    std::string quirks; // the quirk table's entries for this device ("none")
    /// The frame's colour at default_depth (8 bits a channel in this alpha): on the surface once one is known
    /// (collect_surface), else offscreen (colour_offscreen).
    Colour colour;
    /// The deepest colour the surface (or offscreen, the device) allows: what VK bench's colour stage draws, 10 bits a
    /// channel where they can be had, else 8 with the reason (none offered, of no use to the frame, or off). Until the
    /// stage has asked the device about its 10-bit formats, the surface's offer, saying so (decide_colour).
    Colour deepest;
    /// The crash guard's `vk guard colour …` line when VK bench's colour stage crashed under this driver and this build
    /// of the core: 10 bits are off for them (D5.10), and the frame keeps its 8. Empty otherwise.
    std::string ten_bit_off;
    SurfaceFacts surface;

    [[nodiscard]] bool has_extension(std::string_view extension) const noexcept;
    [[nodiscard]] bool supports(FrameConfig config) const noexcept;
    /// What the device does with `format`, if it is one of the frame's colour formats (else nullptr).
    [[nodiscard]] const ColourFormat* colour_format(vk::Format format) const noexcept;
    /// Why the device cannot draw the frame in `format` ("" when it can): not asked of the device yet (a 10-bit format
    /// before VK bench's colour stage), the output's and the samples' features, the frame's sample counts as an input
    /// attachment, lazily allocated memory for configuration A.
    [[nodiscard]] std::string unfit_for_frame(vk::Format format) const;
    /// Whether the 10-bit formats were asked of the device (Device::check_ten_bit, as VK bench's colour stage begins).
    [[nodiscard]] bool ten_bit_queried() const noexcept;
    /// "vendor 0x13b5 device 0x... driver 2 version 0x..." for the guard and the cache key.
    [[nodiscard]] std::string driver_key() const;
    /// Whether the device takes SPIR-V declaring StorageImageReadWithoutFormat (Slang's subpass loads do): Vulkan 1.3,
    /// VK_KHR_format_feature_flags2 or the feature. Else the composite goes through Slang's GLSL route.
    [[nodiscard]] bool takes_read_without_format() const noexcept;
};

/// Reads the facts of `device` (one of `instance`'s), and what they decide for a device that presents (`present`) or
/// one that works offscreen. Of the frame's colour formats it asks about the 8-bit ones only (the file's head).
[[nodiscard]] Facts collect_facts(const Instance& instance, const vk::raii::PhysicalDevice& device,
                                  std::size_t device_count, bool present);

/// Asks `device` what it does with the frame's 10-bit colour formats (`ten`) or its 8-bit ones: their format features
/// and their sample counts as the main pass's transient input attachments (Facts::colour_formats).
void query_colour_formats(Facts& facts, const vk::raii::PhysicalDevice& device, bool ten);

/// Sets `facts.missing` to the requirements of T1 the device lacks, for presenting (`present`: VK_KHR_swapchain too)
/// or for offscreen work, and `facts.presents` to `present`.
void check_requirements(Facts& facts, bool present);

/// Adds the surface's facts, and the frame's colour on it (decide_colour).
void collect_surface(Facts& facts, const vk::raii::PhysicalDevice& device, vk::SurfaceKHR surface,
                     std::uint32_t queue_family);

/// The frame's colour on a surface that offers `offered`, at `depth`. `Depth::deepest`: rgb10a2, else bgr10a2, in
/// sRGB-nonlinear, where the surface offers it, the device, asked (Device::check_ten_bit), can draw the frame in it
/// and the crash guard has not turned 10 bits off (Facts::ten_bit_off); else, as `Depth::eight` always, rgba8, else
/// bgra8 in sRGB-nonlinear, else the surface's first format (as before).
[[nodiscard]] Colour colour_for_surface(const Facts& facts, std::span<const vk::SurfaceFormatKHR> offered, Depth depth);

/// The frame's colour offscreen (the host build's images): the same choice, as if a surface offered every format.
[[nodiscard]] Colour colour_offscreen(const Facts& facts, Depth depth);

/// Sets the facts' colour (at default_depth) and deepest colour: on the surface when its formats are known, else
/// offscreen. Before the 10-bit formats are asked of the device, the deepest colour is what VK bench's colour stage
/// would draw by the surface's offer, its reason saying that the stage asks the device first.
void decide_colour(Facts& facts);

/// What VK bench's colour stage does on this device, as VkFacts' `colour.stage:` line gives it: the 10-bit colour it
/// draws ("rgb10a2 (…)"), "off (…)" where the crash guard turned 10 bits off for this driver and build, or "none (…)"
/// where they cannot be had; each with its reason.
[[nodiscard]] std::string colour_stage(const Facts& facts);

/// The facts as "name: value" lines, in a fixed order.
[[nodiscard]] std::vector<std::string> facts_lines(const Facts& facts);

/// Whether a GPU draws in tiles, from its driver (where known) or its vendor, with the reason.
struct TilerVerdict {
    bool tiler;
    std::string_view reason;
};
[[nodiscard]] TilerVerdict classify_tiler(std::uint32_t vendor_id, bool has_driver_id, vk::DriverId driver_id,
                                          vk::PhysicalDeviceType type) noexcept;

/// The vendor's name for a PCI or Khronos vendor ID ("unknown" otherwise).
[[nodiscard]] std::string_view vendor_name(std::uint32_t vendor_id) noexcept;

/// The configuration the core picks from the facts (D5.3), with the reasons. `msaa2_close` is K6's answer to
/// configuration B's condition (2x within 0.15 MAD of 4x in both classes); until K6 answers, false.
void choose_configuration(Facts& facts, bool msaa2_close);

} // namespace atlas::gpu
