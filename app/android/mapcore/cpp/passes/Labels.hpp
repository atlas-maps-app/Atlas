// The board's labels (plan-vector D13, K13): the app's sprites (the frame's LabelSet: MapCore.labels, each label drawn
// once by LabelSprites as LabelPainter draws it), placed by LabelPainter's algorithm in the core (labels/Placement: at
// rest LabelPainter's own placement, kept while moving, fading over 250 ms) and drawn in the composite subpass after
// the composite, so they never trail the map and are not hazed. A label's points are projected by the frame's camera
// onto the view's device pixels; the chrome's keep-out shapes (the camera state's) and the markers keep labels off
// them; each sprite stands on whole device pixels where LabelPainter would draw its text (labels/Sprites.hpp's
// sprite_at) and is copied texel for texel (shaders/text/sprite.slang). Its texels are set 1's (passes/GroundText.hpp's
// TextGlobals); the frame slot's sprites to draw are the pass's own set (2). The log's line, `label place <ms> placed
// <n>`, comes when the labels placed change, at most once a second.
#pragma once

#include "frame/Pass.hpp"
#include "gen/layouts.hpp"
#include "gpu/Memory.hpp"
#include "labels/Placement.hpp"

#include <array>
#include <cstdint>
#include <vector>

namespace atlas::passes {

/// The board's labels pass.
class Labels final : public frame::Pass {
public:
    /// Sprites a frame draws at most (with the fading places of labels that moved).
    static constexpr std::uint32_t max_draws = 512;

    Labels() = default;
    Labels(const Labels&) = delete;
    Labels& operator=(const Labels&) = delete;
    Labels(Labels&&) = delete;
    Labels& operator=(Labels&&) = delete;
    ~Labels() override;

    [[nodiscard]] std::string_view name() const noexcept override { return "labels"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& context) override;
    void release() noexcept override;
    void update(const frame::FrameState& state) override;
    void record(const frame::Recording& recording, std::uint32_t phase) override;

    // ---- For tests and logs.

    /// The labels the last frame drew, as the placement left them, and their sprites' places.
    [[nodiscard]] std::span<const labels::Drawn> drawn() const noexcept { return drawn_; }
    [[nodiscard]] std::span<const layouts::text_sprite::SpriteDraw> draws() const noexcept { return draws_; }
    [[nodiscard]] const labels::Placement& placement() const noexcept { return placement_; }

private:
    static constexpr std::array<frame::Phase, 1> phase_list{
        frame::Phase{.slot = frame::Slot::labels, .order = 0, .id = 0}};

    labels::Placement placement_; // by the labels' ids, which hold across the app's sets
    std::vector<labels::Candidate> candidates_;
    std::vector<std::vector<labels::Point>> points_;
    std::vector<labels::Box> keep_out_;
    std::vector<labels::Drawn> drawn_;
    std::vector<layouts::text_sprite::SpriteDraw> draws_;
    std::array<std::uint32_t, 2> drawn_count_{};
    std::uint32_t logged_placed_ = 0xFFFF'FFFFU;
    double logged_at_ = -1e9;

    // ---- What prepare makes.
    gpu::Buffer draw_buffer_; // two frame slots' sprites
    vk::raii::DescriptorSetLayout set_layout_{nullptr};
    vk::raii::DescriptorPool pool_{nullptr};
    std::array<vk::raii::DescriptorSet, 2> sets_{vk::raii::DescriptorSet{nullptr}, vk::raii::DescriptorSet{nullptr}};
    vk::raii::PipelineLayout layout_{nullptr};
    vk::raii::ShaderModule module_{nullptr};
    vk::raii::Pipeline pipeline_{nullptr};
};

} // namespace atlas::passes
