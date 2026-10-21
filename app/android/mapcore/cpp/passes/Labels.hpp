// The board's labels (plan-vector D13, K13; ENGINE.md design point 8): the app's sprites (the frame's LabelSet:
// MapCore.labels, each label drawn by LabelSprites as LabelPainter draws it, at its own size and at the band's top),
// placed in the core where LabelPainter would place them and kept there, growing and shrinking with the map within
// their band, fading where they meet another or leave it (labels/Placement, the map's scale the reciprocal of the
// camera's distance), and drawn in the composite subpass after the composite, so they never trail the map and are not
// hazed. A label's points are projected by the frame's camera onto the view's device pixels; the chrome's keep-out
// shapes (the camera state's) and the markers keep labels off them. At its own size each sprite stands on whole device
// pixels where LabelPainter would draw its text (labels/Sprites.hpp's sprite_at) and is copied texel for texel; grown,
// it is drawn from the label drawn at the band's top, so it stays sharp, and shrunk from its own image, each pixel the
// area it covers of the image (shaders/text/sprite.slang). Its texels are set 1's (SpriteGlobals, below); the frame
// slot's sprites to draw are the pass's own set (2). The log's line, `label place <ms> placed <n>`, comes when the
// labels placed change, at most once a second.
#pragma once

#include "frame/Globals.hpp"
#include "frame/Pass.hpp"
#include "gen/layouts.hpp"
#include "gpu/Memory.hpp"
#include "labels/Placement.hpp"

#include <array>
#include <cstdint>
#include <memory>
#include <utility>
#include <vector>

namespace atlas::labels {
struct LabelSet; // labels/Sprites.hpp
} // namespace atlas::labels

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
    /// Set 1's sprites (SpriteGlobals, below).
    [[nodiscard]] std::vector<std::shared_ptr<frame::GlobalProvider>>
    providers(const gpu::Device& device, const frame::Resources& resources) const override;

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

/// Set 1's sprites (frame/Globals.hpp; plan-vector D13): the texels of the frame's LabelSet, copied once a set into a
/// buffer of its own; the buffer of the set before kept for a few updates more than the frames in flight of the frame
/// builders that may read it. The world holds it (World::add_globals; the labels pass brings it).
class SpriteGlobals final : public frame::GlobalProvider {
public:
    explicit SpriteGlobals(const gpu::Device& device) noexcept : device_(device) {}

    [[nodiscard]] std::string_view name() const noexcept override { return "sprites"; }
    [[nodiscard]] std::span<const frame::Global> bindings() const noexcept override { return owned; }
    void update(const frame::FrameState& state, frame::Globals& globals) override;

    /// The texels of the set copied last (for tests).
    [[nodiscard]] std::size_t texels() const noexcept { return texels_; }

private:
    static constexpr std::array<frame::Global, 1> owned{frame::Global::sprites};
    /// Updates a let-go buffer is kept: two frame builders' frames in flight, and as many again.
    static constexpr std::uint64_t retire_after = 4ULL * gpu::Frames::in_flight;

    const gpu::Device& device_;
    const labels::LabelSet* set_ = nullptr; // the set copied last: its address and revision
    std::uint64_t revision_ = 0;
    gpu::Buffer buffer_;
    std::size_t texels_ = 0;
    std::vector<std::pair<gpu::Buffer, std::uint64_t>> retired_; // buffer, the update it was let go of
    std::uint64_t updates_ = 0;
};

} // namespace atlas::passes
