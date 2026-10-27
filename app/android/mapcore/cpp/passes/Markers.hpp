// The app's markers drawn by the core (ENGINE.md step 6): the frame's MarkerSet (MapCore.markers, markers/Markers.hpp)
// placed each frame where MarkerLayout would place them (markers/Layout.hpp) on the view the frame's camera sees, so
// they move with the map in the same frame instead of being drawn by the canvas over it a frame apart. Each marker's
// place is projected onto the view's device pixels at the street (as GroundView projects it); the markers within 60 dp
// of the view are placed by priority, folding into the ones placed before them; and the boxes they take (with a named
// pin's name) go to the labels, which update after the markers and keep clear of them (FrameState::marker_boxes).
// Drawn in the composite subpass after the labels and before the location's puck, as the canvas draws them
// (shaders/overlay/marker.slang): a marker as itself its look's sprite, its place on a whole pixel and copied texel for
// texel; a cluster its bubble, drawn by the shader at the place's pixel, and its count's sprite centred on it.
//
// The pass's own set (2) holds a frame slot's draws and the slot's copy of the set's texels, copied when the slot first
// draws a new set (its previous frame has finished with the old one).
#pragma once

#include "frame/Pass.hpp"
#include "gen/layouts.hpp"
#include "gpu/Memory.hpp"
#include "markers/Layout.hpp"

#include <array>
#include <cstdint>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::passes {

/// The markers pass.
class Markers final : public frame::Pass {
public:
    /// What a frame draws at most: a marker's sprite, or a cluster's bubble and count.
    static constexpr std::uint32_t max_draws = 4096;

    Markers() = default;
    Markers(const Markers&) = delete;
    Markers& operator=(const Markers&) = delete;
    Markers(Markers&&) = delete;
    Markers& operator=(Markers&&) = delete;
    ~Markers() override;

    [[nodiscard]] std::string_view name() const noexcept override { return "markers"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& context) override;
    void release() noexcept override;
    void update(const frame::FrameState& state) override;
    void record(const frame::Recording& recording, std::uint32_t phase) override;

    // ---- For tests.

    /// The last frame's placement, its markers' places on the view and what it drew.
    [[nodiscard]] const markers::Layout& layout() const noexcept { return layout_; }
    [[nodiscard]] std::span<const float> xs() const noexcept { return xs_; }
    [[nodiscard]] std::span<const float> ys() const noexcept { return ys_; }
    [[nodiscard]] std::span<const layouts::overlay_marker::MarkerDraw> draws() const noexcept { return draws_; }

private:
    static constexpr std::array<frame::Phase, 1> phase_list{
        frame::Phase{.slot = frame::Slot::labels, .order = 1, .id = 0}};

    markers::Layout layout_;
    std::vector<float> xs_;
    std::vector<float> ys_;
    std::vector<std::uint8_t> visible_;
    std::vector<layouts::overlay_marker::MarkerDraw> draws_;

    /// A frame slot's own: its set, its copy of the set's texels and which set they are, and its draws' count.
    struct Slot {
        vk::raii::DescriptorSet set{nullptr};
        gpu::Buffer texels;
        const markers::MarkerSet* of = nullptr;
        std::uint64_t revision = 0;
        std::uint32_t count = 0;
    };
    std::array<Slot, 2> slots_{};

    // ---- What prepare makes.
    const gpu::Device* device_ = nullptr;
    gpu::Buffer draw_buffer_; // two frame slots' draws
    vk::raii::DescriptorSetLayout set_layout_{nullptr};
    vk::raii::DescriptorPool pool_{nullptr};
    vk::raii::PipelineLayout layout_handle_{nullptr};
    vk::raii::ShaderModule module_{nullptr};
    vk::raii::Pipeline pipeline_{nullptr};
};

} // namespace atlas::passes
