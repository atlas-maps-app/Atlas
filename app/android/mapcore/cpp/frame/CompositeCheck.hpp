// VK facts' composite check (ENGINE.md step 2): whether the device's resolve form of the composite draws the frame as
// its direct form does. In the resolve form the composite writes each pixel's result into its samples in place, which
// are then resolved; a driver that clips the composite's full-screen triangle to the frame draws the rectangle as two
// triangles, so each pixel on their shared diagonal gets two fragments, and the second reads what the first wrote. A
// GPU's guard band draws the triangle whole, once a pixel. The check draws a probe of unlike samples (SamplesProbe:
// shaders/frame/test_samples.slang) through the composite with every step off, at the facts' configuration's samples,
// in both forms (the resolve form by the facts' route where it resolves, else the render pass's; the direct form by the
// render pass, as one dynamic rendering scope cannot write the 1x image from the samples) into offscreen images
// (gpu/Offscreen.hpp), and compares them pixel for pixel. Where the forms differ on the diagonal alone the driver clips
// the triangle, and the quirk table gives it the direct form (gpu/Quirks.hpp's target). lavapipe clips (the hosts'
// tests, CI's emulator), so its line is the check's planted failure. VK facts runs it behind the crash guard's checks
// stage (standin/Session.hpp's check): a crash in it turns it off alone.
#pragma once

#include "frame/Pass.hpp"
#include "gpu/Device.hpp"
#include "gpu/Facts.hpp"
#include "gpu/PipelineCache.hpp"

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>

namespace atlas::frame {

/// The probe pass: `layers` full-screen triangles, each writing its colour into the samples its coverage picks (layer 0
/// every sample; each later layer's colour and samples a hash of its pixel and layer, test_samples.slang's test_hash).
class SamplesProbe final : public Pass {
public:
    explicit SamplesProbe(std::uint32_t layers) noexcept : layers_(layers) {}
    [[nodiscard]] std::string_view name() const noexcept override { return "samples"; }
    [[nodiscard]] std::span<const Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const PassContext& context) override;
    void release() noexcept override;
    void record(const Recording& recording, std::uint32_t phase) override;

private:
    static constexpr std::array<Phase, 1> phase_list{Phase{.slot = Slot::objects}};
    std::uint32_t layers_;
    std::uint32_t samples_ = 1;
    std::uint32_t levels_ = 255;
    vk::raii::ShaderModule module_{nullptr};
    vk::raii::Pipeline pipeline_{nullptr};
};

/// Whether pixel (x, y) of a w × h frame lies on the diagonal from its top-left corner to its bottom-right one, where a
/// driver that clips the full-screen triangle to the frame joins the two triangles it draws it as.
[[nodiscard]] bool on_seam(std::uint32_t x, std::uint32_t y, std::uint32_t w, std::uint32_t h) noexcept;

/// What the check found.
struct CompositeCheck {
    gpu::Configuration resolve; ///< the resolve form it drew
    gpu::Configuration direct;  ///< the direct form it drew
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint32_t differ = 0;  ///< pixels the two forms draw differently
    std::uint32_t on_seam = 0; ///< of them, those on the diagonal
};

/// The check on `device`, its pipelines through `cache`, at the facts' configuration's samples; none where the facts'
/// frame resolves nothing in place (one sample a pixel, or one pass without a composite subpass).
[[nodiscard]] gpu::Result<std::optional<CompositeCheck>> composite_check(const gpu::Device& device,
                                                                         const gpu::PipelineCache& cache);

/// VK facts' line: `composite.check: …`, the two forms alike, differing on the diagonal alone (the driver clips the
/// full-screen triangle), differing off it too, skipped (why) or unavailable (why).
[[nodiscard]] std::string composite_line(const gpu::Device& device, const gpu::PipelineCache& cache);

} // namespace atlas::frame
