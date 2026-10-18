// Pipelines (plan-vector D5.6): immutable graphics and compute pipelines from the embedded SPIR-V, made through the
// pipeline cache, for either frame route. A pipeline's target is a subpass of a render pass, or a dynamic rendering
// scope with its formats and, for the composite, the local-read mapping of colour, depth and stencil to input
// attachment indices. Viewport and scissor are dynamic; everything else is fixed at creation.
//
// No pipeline takes specialization constants: the core's modules hold none (cmake/Shaders.cmake refuses them, after
// PowerVR's compiler crashed on one), so a variant is a module of its own and the pipeline picks the module. Each
// pipeline and each shader module is announced before it is made (announce, announce_module), and whatever the core
// destroys as a whole before it goes (announce_release: a frame's pipelines, passes and images, a swapchain, a pipeline
// cache, the stand-in, the device, the instance): the log names it, and the crash guard writes it into its marker, so a
// driver that crashes making or destroying it is known by it at the next start (D5.10), a crash in
// vkCreateShaderModule by its module, not by the pipeline made before it. An announcement lasts as long as the scope
// that holds what it returns (Announced), which is the call it names and no more: when the call returns the marker
// names its stage alone again, so the driver calls that follow, the next object's making among them, are never
// charged to it. A renderer also tells the watch whose frame its work is (PipelineWatch::frame_of), so that the crash
// guard tells VK bench's cells from the facts' own pick (D5.10).
#pragma once

#include "gpu/Error.hpp"

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::gpu {

struct Configuration; // gpu/Facts.hpp

/// What a pipeline is, as the log and the crash guard name it: its name, its module or modules (the embedded SPIR-V's
/// symbols) and the frame configuration and colour it is made for ("A renderpass composite resolve rgb10a2"; "any"
/// for one made once for every configuration).
struct PipelineLabel {
    std::string_view name;
    std::string_view module;
    std::string_view configuration = "any";
};

/// "<name> (module <module>, configuration <configuration>)".
[[nodiscard]] std::string describe(const PipelineLabel& label);

/// What the core is about to destroy as a whole, as the log and the crash guard name it: "<kind> <detail>".
struct Release {
    /// What kind of thing goes, each a name of its own, which the crash guard's plants take (gpu/Guard.hpp):
    /// "frame" (a renderer's main pass and everything it prepared in its content: the configuration's pipelines,
    /// passes, framebuffers and images), "swapchain" (the window's output: a swapchain on the phone, the images that
    /// stand in for one on hosts), "cache" (a pipeline cache: the one on disk, VK bench's colour stage's own, its
    /// pipelines' timing's), "standin" (the stand-in's shared pipelines, modules, buffers and atlas), "device" (the
    /// device and its allocator), "instance".
    std::string_view kind;
    /// Which: "(configuration A renderpass composite resolve rgb10a2)", "rgb10a2", "(the colour stage's)", ….
    std::string_view detail;
    /// Whether it holds 10 bits a channel (a 10-bit frame or swapchain, the colour stage's cache).
    bool ten_bit = false;
};

/// "<kind> <detail>" ("frame (configuration C renderpass composite direct rgb10a2)", "swapchain rgba8").
[[nodiscard]] std::string describe(const Release& release);

/// Told about each pipeline and each shader module just before it is made, about what goes just before it is
/// destroyed, and when that call has returned (the crash guard, D5.10).
class PipelineWatch {
public:
    PipelineWatch() = default;
    PipelineWatch(const PipelineWatch&) = delete;
    PipelineWatch& operator=(const PipelineWatch&) = delete;
    PipelineWatch(PipelineWatch&&) = delete;
    PipelineWatch& operator=(PipelineWatch&&) = delete;
    virtual ~PipelineWatch() = default;
    virtual void before(const PipelineLabel& label) = 0;
    /// `module` is an embedded module's symbol (standin_composite).
    virtual void before_module(std::string_view module) = 0;
    virtual void before_release(const Release& release) = 0;
    /// The call announced last returned, made, refused or gone: what runs after it is not that call. Announcements
    /// nest (an inner one's end gives the outer one back), so each before has exactly one after.
    virtual void after() = 0;
    /// The work that follows (made, drawn, let go of) is the frame of `configuration` (a renderer's, gpu/Frames.hpp),
    /// or no frame's (nullptr: its renderer gone). The crash guard tells VK bench's cells, the configurations the facts
    /// would not pick, from the pick by it; other watches need not hear it.
    virtual void frame_of(const Configuration* configuration) { (void)configuration; }
};

/// The span of one announcement: from the announce call that returned it to the end of the scope that holds it, which
/// is the announced call and no more. Its end tells the watch that the call returned (PipelineWatch::after).
class [[nodiscard]] Announced {
public:
    explicit Announced(PipelineWatch* watch) noexcept : watch_(watch) {}
    Announced(const Announced&) = delete;
    Announced& operator=(const Announced&) = delete;
    Announced(Announced&&) = delete;
    Announced& operator=(Announced&&) = delete;
    ~Announced() {
        if (watch_ != nullptr) watch_->after();
    }

private:
    PipelineWatch* watch_;
};

/// Logs the pipeline about to be made ("vk pipeline <describe>", under the tag AtlasMap, so that a crash's last line in
/// logcat names it) and tells `watch`, if any, until the returned span ends: hold it around the call that makes it.
[[nodiscard]] Announced announce(const PipelineLabel& label, PipelineWatch* watch);

/// Logs the shader module about to be made ("vk module <module>") and tells `watch`, if any, until the span ends.
[[nodiscard]] Announced announce_module(std::string_view module, PipelineWatch* watch);

/// Logs what is about to be destroyed ("vk release <kind> <detail>") and tells `watch`, if any, until the span ends:
/// hold it around its destruction alone.
[[nodiscard]] Announced announce_release(const Release& release, PipelineWatch* watch);

/// Where a graphics pipeline draws.
struct PipelineTarget {
    /// A render pass and its subpass, or a null render pass for dynamic rendering.
    vk::RenderPass render_pass;
    std::uint32_t subpass = 0;
    vk::SampleCountFlagBits samples = vk::SampleCountFlagBits::e1;
    /// Dynamic rendering: the colour formats, in attachment order, and the depth and stencil formats.
    std::vector<vk::Format> colour_formats;
    vk::Format depth_format = vk::Format::eUndefined;
    vk::Format stencil_format = vk::Format::eUndefined;
    /// Dynamic rendering with local reads: each colour attachment's input attachment index (VK_ATTACHMENT_UNUSED for
    /// none), and the depth's and the stencil's (empty optional: none). An empty list keeps Vulkan's defaults.
    std::vector<std::uint32_t> colour_input_indices;
    std::optional<std::uint32_t> depth_input_index;
    std::optional<std::uint32_t> stencil_input_index;
    bool local_read = false;
    /// How many colour attachments the subpass or scope has (for the blend states).
    std::uint32_t colour_count = 1;
};

/// One colour attachment's blending.
struct Blend {
    bool enable = false;
    vk::BlendFactor src_colour = vk::BlendFactor::eOne;
    vk::BlendFactor dst_colour = vk::BlendFactor::eZero;
    vk::BlendFactor src_alpha = vk::BlendFactor::eOne;
    vk::BlendFactor dst_alpha = vk::BlendFactor::eZero;
    vk::ColorComponentFlags mask = vk::ColorComponentFlagBits::eR | vk::ColorComponentFlagBits::eG |
                                   vk::ColorComponentFlagBits::eB | vk::ColorComponentFlagBits::eA;

    /// Writes the fragment as it is.
    [[nodiscard]] static Blend opaque() noexcept { return {}; }
    /// Source over, premultiplied: the renders' compositing of translucent paint.
    [[nodiscard]] static Blend over() noexcept {
        return {.enable = true,
                .src_colour = vk::BlendFactor::eOne,
                .dst_colour = vk::BlendFactor::eOneMinusSrcAlpha,
                .src_alpha = vk::BlendFactor::eOne,
                .dst_alpha = vk::BlendFactor::eOneMinusSrcAlpha};
    }
    /// `mix-blend-mode: screen` on premultiplied colour: s + d - s·d (D5.4).
    [[nodiscard]] static Blend screen() noexcept {
        return {.enable = true,
                .src_colour = vk::BlendFactor::eOne,
                .dst_colour = vk::BlendFactor::eOneMinusSrcColor,
                .src_alpha = vk::BlendFactor::eOne,
                .dst_alpha = vk::BlendFactor::eOneMinusSrcAlpha};
    }
    /// Leaves the attachment untouched (an attachment this pipeline does not write).
    [[nodiscard]] static Blend untouched() noexcept { return {.mask = {}}; }

    /// Whether the colour it writes depends on the destination's alpha (DST_ALPHA, ONE_MINUS_DST_ALPHA,
    /// SRC_ALPHA_SATURATE): never in the core, whose frame may be rgb10a2 with 2 bits of alpha (gpu/Facts.hpp), so
    /// make_graphics refuses such a blend.
    [[nodiscard]] bool reads_destination_alpha() const noexcept;
};

/// A graphics pipeline.
struct GraphicsDesc {
    /// Its name in the log, the crash guard's marker and errors.
    PipelineLabel label;
    vk::ShaderModule module;
    const char* vertex = nullptr;
    const char* fragment = nullptr;
    /// The fragment stage's module when it is not `module` (a GLSL-route module, whose entry point is "main").
    vk::ShaderModule fragment_module;
    vk::PipelineLayout layout;
    PipelineTarget target;
    vk::PrimitiveTopology topology = vk::PrimitiveTopology::eTriangleList;
    vk::CullModeFlags cull = vk::CullModeFlagBits::eNone;
    vk::FrontFace front = vk::FrontFace::eCounterClockwise;
    bool alpha_to_coverage = false;
    bool depth_test = false;
    bool depth_write = false;
    vk::CompareOp depth_op = vk::CompareOp::eLess;
    bool stencil_test = false;
    vk::StencilOpState stencil{};
    /// The stencil state of back-facing primitives where it differs from the front's (`stencil`): a two-sided stencil,
    /// as a winding count increments on front faces and decrements on back faces (the water's nonzero clips, K9).
    /// Empty: the front's state on both faces.
    std::optional<vk::StencilOpState> stencil_back;
    std::array<Blend, 4> blends{};
};

/// The depth and stencil state make_graphics gives the driver for `desc`: its depth test, write and compare op, its
/// stencil test, the front faces' stencil state and the back faces' (stencil_back, else the front's).
[[nodiscard]] vk::PipelineDepthStencilStateCreateInfo depth_stencil_of(const GraphicsDesc& desc) noexcept;

/// Makes a shader module of `spirv` (its caller announced it).
[[nodiscard]] Result<vk::raii::ShaderModule> make_module(const vk::raii::Device& device,
                                                         std::span<const std::uint32_t> spirv);

[[nodiscard]] Result<vk::raii::PipelineLayout> make_layout(const vk::raii::Device& device,
                                                           std::span<const vk::DescriptorSetLayout> sets,
                                                           std::uint32_t push_bytes, vk::ShaderStageFlags push_stages);

/// Makes the pipeline `desc` describes (its caller announced it); refused, before the driver sees it, when a blend
/// reads the destination's alpha.
[[nodiscard]] Result<vk::raii::Pipeline> make_graphics(const vk::raii::Device& device,
                                                       const vk::raii::PipelineCache& cache, const GraphicsDesc& desc);

/// Makes the compute pipeline of `entry` in `module` (its caller announced it as `label`).
[[nodiscard]] Result<vk::raii::Pipeline> make_compute(const vk::raii::Device& device,
                                                      const vk::raii::PipelineCache& cache, const PipelineLabel& label,
                                                      vk::ShaderModule module, const char* entry,
                                                      vk::PipelineLayout layout);

} // namespace atlas::gpu
