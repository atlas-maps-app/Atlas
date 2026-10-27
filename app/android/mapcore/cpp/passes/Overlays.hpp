// The map's overlays drawn by the core (ENGINE.md step 6): for now where the user is, the location's puck, from the
// camera state (style/CameraState.hpp's location, version 2), so it moves with the map in the same frame instead of
// being drawn by the canvas over it a frame apart. Drawn in the composite subpass after the labels and the markers
// (passes/Markers.hpp), as mapview's OverlayPainter.drawLocation draws it (shaders/overlay/location.slang): where the
// fix lies in front of the eye, its accuracy's circle on the street where it is wider than 20 dp at its depth and on
// screen, and the puck where its box of 40 dp about the dot reaches the screen, with the heading's cone turned toward a
// point 15 m ahead of the fix on screen (Geo.destination's, on a sphere of 6,371,008.8 m). The push constants carry
// it: no set of its own.
#pragma once

#include "frame/Pass.hpp"
#include "gen/layouts.hpp"

#include <array>
#include <cstdint>
#include <span>
#include <string_view>

namespace atlas::passes {

/// The overlays pass.
class Overlays final : public frame::Pass {
public:
    /// Its parts, a bit each (the shader's instance numbers).
    static constexpr std::uint32_t accuracy = 1U;
    static constexpr std::uint32_t puck = 2U;

    Overlays() = default;
    Overlays(const Overlays&) = delete;
    Overlays& operator=(const Overlays&) = delete;
    Overlays(Overlays&&) = delete;
    Overlays& operator=(Overlays&&) = delete;
    ~Overlays() override;

    [[nodiscard]] std::string_view name() const noexcept override { return "overlays"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& context) override;
    void release() noexcept override;
    void update(const frame::FrameState& state) override;
    void record(const frame::Recording& recording, std::uint32_t phase) override;

    // ---- For tests.

    /// What the last frame's slot `slot` draws: its parts (accuracy, puck) and the push constants.
    [[nodiscard]] std::uint32_t parts(std::uint32_t slot) const noexcept { return slots_.at(slot & 1U).parts; }
    [[nodiscard]] const layouts::overlay_location::LocationPush& location(std::uint32_t slot) const noexcept {
        return slots_.at(slot & 1U).push;
    }

private:
    static constexpr std::array<frame::Phase, 1> phase_list{
        frame::Phase{.slot = frame::Slot::labels, .order = 2, .id = 0}};

    /// A frame slot's location.
    struct Placed {
        std::uint32_t parts = 0;
        layouts::overlay_location::LocationPush push{};
    };
    std::array<Placed, 2> slots_{};

    // ---- What prepare makes.
    vk::raii::ShaderModule module_{nullptr};
    vk::PipelineLayout layout_; // the frame layout's own (sets 0 and 1, the push constants)
    vk::raii::Pipeline pipeline_{nullptr};
};

} // namespace atlas::passes
