// The house the app asks about, in the roof it asks about (ENGINE.md step 6, the user's own edits drawn at once:
// FillIn's roof question). While FillIn asks what shape a building's roof is, the frame draws the building as the
// FillIn board's house, in the tile's own building's place (FrameState::house, scene/House.hpp; the ranks leave the
// building's own items out): its walls in the asked building's flat colour, unlit, under the board's hatch before the
// colour's answer (frame/Asked.hpp's, as the buildings pass draws them); a pitched roof's slopes and hips in the
// board's grey, lighter as they turn to the frame's sun (#A2A5A9 turned away, #BDBFC4 square to it; no shadow is cast
// on them, and at night none is lit); a flat top in the board's white under its thin outline (#6F695F, .45 CSS px,
// drawn one device px wide, fainter where a device px is wider than that).
//
// Drawn in the scene subpass among what stands up, at its true depth (shaders/overlay/house.slang): the triangles
// opaque in the objects slot, writing the depth and the objects bit as the buildings do; the outline with the roofs'
// thin edges, over what the frame drew there. Not under the painter switch (the frame builder sets no house there),
// nor in the owner mode. The pass's own set (2) holds each frame slot's points, written each frame the house is drawn.
#pragma once

#include "frame/Pass.hpp"
#include "gen/layouts.hpp"
#include "gpu/Memory.hpp"

#include <array>
#include <cstdint>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::passes {

/// The house's pass.
class House final : public frame::Pass {
public:
    /// The most points a frame draws (a house of a few hundred walls): one beyond is drawn without its last faces.
    static constexpr std::uint32_t max_points = 8192;

    /// The board's colours (0xRRGGBB): a slope turned away from the sun and one square to it, a flat top, its outline;
    /// and the outline's width, CSS px.
    static constexpr std::uint32_t slope_shade = 0xA2A5A9;
    static constexpr std::uint32_t slope_sun = 0xBDBFC4;
    static constexpr std::uint32_t top = 0xFFFFFF;
    static constexpr std::uint32_t rim = 0x6F695F;
    static constexpr double rim_css = 0.45;

    House() = default;
    House(const House&) = delete;
    House& operator=(const House&) = delete;
    House(House&&) = delete;
    House& operator=(House&&) = delete;
    ~House() override;

    [[nodiscard]] std::string_view name() const noexcept override { return "house"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& context) override;
    void release() noexcept override;
    void update(const frame::FrameState& state) override;
    void record(const frame::Recording& recording, std::uint32_t phase) override;

    // ---- For tests.

    /// What the last frame's slot `slot` draws: its triangles' corners, then its outline's segments' ends.
    [[nodiscard]] std::span<const layouts::overlay_house::HousePoint> points(std::uint32_t slot) const noexcept {
        return slots_.at(slot & 1U).points;
    }
    /// How many of them are its triangles' corners.
    [[nodiscard]] std::uint32_t corners(std::uint32_t slot) const noexcept { return slots_.at(slot & 1U).corners; }

private:
    static constexpr std::uint32_t fill_phase = 0;
    static constexpr std::uint32_t rim_phase = 1;
    /// The outline with the roofs' thin edges (passes::Buildings::strokes_order), after every element of the ground.
    static constexpr std::array<frame::Phase, 2> phase_list{
        frame::Phase{.slot = frame::Slot::objects, .order = 1, .id = fill_phase},
        frame::Phase{.slot = frame::Slot::translucent, .order = 1000, .id = rim_phase}};

    /// A frame slot's own: its set, its points this frame and how many are the triangles', and its tile's place.
    struct Slot {
        vk::raii::DescriptorSet set{nullptr};
        std::vector<layouts::overlay_house::HousePoint> points;
        std::uint32_t corners = 0;
        layouts::overlay_house::HousePush push{};
    };
    std::array<Slot, 2> slots_{};

    // ---- What prepare makes.
    gpu::Buffer buffer_; // two frame slots' points
    vk::raii::DescriptorSetLayout set_layout_{nullptr};
    vk::raii::DescriptorPool pool_{nullptr};
    vk::raii::PipelineLayout layout_{nullptr};
    vk::raii::ShaderModule module_{nullptr};
    vk::raii::Pipeline fill_{nullptr};
    vk::raii::Pipeline rim_{nullptr};
};

} // namespace atlas::passes
