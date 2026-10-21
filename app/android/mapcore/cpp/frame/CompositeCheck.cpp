// VK facts' composite check (frame/CompositeCheck.hpp).
#include "frame/CompositeCheck.hpp"

#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "frame/PassRegistry.hpp"
#include "gen/layouts.hpp"
#include "gen/spirv/frame/test_samples.hpp"
#include "gpu/Frames.hpp"
#include "gpu/Offscreen.hpp"
#include "standin/View.hpp"

#include <format>
#include <memory>
#include <utility>
#include <vector>

namespace atlas::frame {

namespace {

/// The probe's frame: small, as the seam needs no more, and wider than high, as a phone's frame is not.
constexpr vk::Extent2D extent{.width = 96, .height = 72};

/// The probe's layers over each pixel.
constexpr std::uint32_t probe_layers = 5;

/// The probe through the composite with every step off, in `config`, read back as the image holds it.
[[nodiscard]] gpu::Result<gpu::Packed> draw_probe(const gpu::Device& device, const gpu::PipelineCache& cache,
                                                  const gpu::Configuration& config) {
    std::vector<std::unique_ptr<Pass>> passes = make_passes(std::array<std::string_view, 1>{"composite"});
    passes.push_back(std::make_unique<SamplesProbe>(probe_layers));
    ATLAS_GPU_LET(builder, FrameBuilder::create(device, cache, std::move(passes),
                                                {.overdraw = false, .clear = {0, 0, 0, 1}, .steps = 0}));
    builder->set_inputs({.camera = standin::board_view(extent.width, extent.height, 1.0),
                         .theme = {},
                         .seconds = 0.5,
                         .mode = Mode::structural,
                         .scene = nullptr});
    ATLAS_GPU_LET(output, gpu::Offscreen::create(device, extent, 2));
    ATLAS_GPU_LET(renderer, gpu::Renderer::create(device, *output, *builder, config));
    for (int f = 0; f < 2; ++f) {
        if (auto report = renderer->draw(0); !report) return std::unexpected(std::move(report.error()));
    }
    renderer->drain();
    return output->read_packed(output->latest());
}

} // namespace

gpu::Result<void> SamplesProbe::prepare(const PassContext& context) {
    release();
    ATLAS_GPU_LET(module, make_module(context, "frame_test_samples", spirv::frame_test_samples));
    module_ = std::move(module);
    gpu::GraphicsDesc desc{
        .label = {.name = "samples", .module = "frame_test_samples", .configuration = context.configuration},
        .module = *module_,
        .vertex = "samples_vs",
        .fragment = "samples_fs",
        .layout = context.layout.pipeline_layout(),
        .target = context.pass.scene_target()};
    if (desc.target.colour_count == 2) desc.blends[1] = gpu::Blend::untouched();
    ATLAS_GPU_LET(pipeline, make_pipeline(context, desc, Overdraw::opaque));
    pipeline_ = std::move(pipeline);
    samples_ = static_cast<std::uint32_t>(context.pass.spec().config.samples());
    levels_ = gpu::ten_bit(context.pass.spec().colour_format) ? 1023U : 255U;
    return {};
}

void SamplesProbe::release() noexcept {
    pipeline_.clear();
    module_.clear();
}

void SamplesProbe::record(const Recording& r, std::uint32_t phase) {
    (void)phase;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipeline_);
    for (std::uint32_t layer = 0; layer < layers_; ++layer) {
        layouts::frame_test_samples::SamplesPush p{};
        p.layer = {layer, levels_, samples_, 0U};
        cmd.pushConstants(r.layout.pipeline_layout(), push_stages, 0,
                          vk::ArrayProxy<const std::byte>(sizeof p, reinterpret_cast<const std::byte*>(&p))); // NOLINT
        cmd.draw(3, 1, 0, 0);
        r.counters.draw("probe", 1);
    }
}

bool on_seam(std::uint32_t x, std::uint32_t y, std::uint32_t w, std::uint32_t h) noexcept {
    // The seam y = x·h / w meets the pixel's square [x, x + 1) × [y, y + 1).
    const std::uint64_t wide = w;
    const std::uint64_t high = h;
    const std::uint64_t above = std::uint64_t{y} * wide; // the pixel's top edge, times w
    const std::uint64_t below = above + wide;            // its bottom edge
    const std::uint64_t left = std::uint64_t{x} * high;  // the seam's height at its left edge, times w
    const std::uint64_t right = left + high;             // and at its right edge
    return above < right && below > left;
}

gpu::Result<std::optional<CompositeCheck>> composite_check(const gpu::Device& device, const gpu::PipelineCache& cache) {
    const gpu::Configuration pick = device.facts().choice;
    if (pick.samples() == vk::SampleCountFlagBits::e1 || pick.form != gpu::FrameForm::composite) return std::nullopt;
    CompositeCheck out{
        .resolve = {.config = pick.config,
                    .route = pick.target == gpu::CompositeTarget::resolve ? pick.route : gpu::Route::renderpass,
                    .form = gpu::FrameForm::composite,
                    .target = gpu::CompositeTarget::resolve},
        .direct = {.config = pick.config,
                   .route = gpu::Route::renderpass,
                   .form = gpu::FrameForm::composite,
                   .target = gpu::CompositeTarget::direct},
        .width = extent.width,
        .height = extent.height};
    ATLAS_GPU_LET(resolved, draw_probe(device, cache, out.resolve));
    ATLAS_GPU_LET(direct, draw_probe(device, cache, out.direct));
    if (resolved.words.size() != direct.words.size() || resolved.format != direct.format) {
        return gpu::fail("the two forms read back in different formats");
    }
    const std::uint32_t words = gpu::words_per_pixel(resolved.format);
    for (std::uint32_t y = 0; y < extent.height; ++y) {
        for (std::uint32_t x = 0; x < extent.width; ++x) {
            const std::size_t at = ((std::size_t{y} * extent.width) + x) * words;
            bool alike = true;
            for (std::uint32_t k = 0; k < words; ++k) alike = alike && resolved.words[at + k] == direct.words[at + k];
            if (alike) continue;
            ++out.differ;
            if (on_seam(x, y, extent.width, extent.height)) ++out.on_seam;
        }
    }
    return out;
}

std::string composite_line(const gpu::Device& device, const gpu::PipelineCache& cache) {
    const gpu::Configuration pick = device.facts().choice;
    const auto check = composite_check(device, cache);
    if (!check) return std::format("composite.check: unavailable ({})", check.error().text());
    if (!check->has_value()) {
        return std::format("composite.check: skipped ({}: nothing resolves in place)",
                           pick.form != gpu::FrameForm::composite ? "one pass" : "one sample a pixel");
    }
    const CompositeCheck& c = **check;
    const std::string forms =
        std::format("{} against {}, {} × {}", gpu::name_of(c.resolve), gpu::name_of(c.direct), c.width, c.height);
    if (c.differ == 0) return std::format("composite.check: the resolve form draws as the direct form ({})", forms);
    if (c.differ == c.on_seam) {
        return std::format("composite.check: the resolve form differs on {} pixels, all on the diagonal: the driver "
                           "clips the full-screen triangle ({})",
                           c.differ, forms);
    }
    return std::format("composite.check: the resolve form differs on {} pixels, {} of them off the diagonal ({})",
                       c.differ, c.differ - c.on_seam, forms);
}

} // namespace atlas::frame
