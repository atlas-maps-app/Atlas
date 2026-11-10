// The world pass (ENGINE.md step 5's step 2, "The world pass and its cover, flat"): the world's tiles beyond the
// countries' reach (scene/WorldTiles.hpp), drawn once the camera is more than 18 km from its target, flat on the
// frame's ground and under every country's tile, where today's map said "Zoom in to see the map". Each frame, the tiles
// the world's cover draws, coarse to fine, each its cells the cover asks for: its fills in its painter's order (the
// land, the land cover by LAND's order, the water, the areas over the water), then its lines as the renders' terrain
// lines (the rivers, the roads by class, the railways, the big roads, the runways), from the world's own heap buffer,
// beside the heaps' two (shaders/world/world.slang).
//
// Drawn first in the ground's slot, at a depth between the sky's painter slots and the ground's first, so every
// country's ground lies over it and the sky, drawn after it, nowhere does. Its colours are the moment's theme's (the
// land the ground's, as the plate is, the water and the roads the theme's) and the world's style's (the land cover by
// day and at night as the ground's land is, the railways', the big roads' asphalt and the runways' pavement): the
// palette in set 2, a copy for each frame slot. Glaciers and ice shelves have no colour of the style: they are drawn as
// the land, for the user's pick.
#pragma once

#include "frame/Pass.hpp"
#include "gpu/Memory.hpp"

#include <array>
#include <cstdint>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::tiles {
struct Style; // tiles/Style.hpp
} // namespace atlas::tiles

namespace atlas::style {
struct ThemeBlock; // style/Theme.hpp
} // namespace atlas::style

namespace atlas::passes {

/// The world pass.
class World final : public frame::Pass {
public:
    /// One tile's draw: where its words lie and how it is placed (world.slang's WorldPush).
    struct Draw {
        std::array<float, 4> origin{};
        std::array<float, 4> scale{};
        std::array<std::uint32_t, 4> at{};
        std::array<float, 4> globe{};  ///< world.slang's push: the tile's edges less the target's, the radians a unit
        std::array<float, 4> sphere{}; ///< spans; the target's latitude, w₂ and the radius; the target from the eye
        std::array<float, 4> target{};
        std::uint32_t triangles = 0;
        std::uint32_t segments = 0;
        float fade = 1.0F;      ///< under 1 while the tile fades in (scene::WorldDraw::fade)
        std::uint8_t level = 0; ///< its tile's level
        std::uint32_t lift = 0; ///< while it fades, its depth's lift above the world's (World.cpp's fading_depth)
    };

    World() = default;
    World(const World&) = delete;
    World& operator=(const World&) = delete;
    World(World&&) = delete;
    World& operator=(World&&) = delete;
    ~World() override;

    [[nodiscard]] std::string_view name() const noexcept override { return "world"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& context) override;
    void release() noexcept override;
    void update(const frame::FrameState& state) override;
    void record(const frame::Recording& recording, std::uint32_t phase) override;

    // ---- For tests.

    /// What the last frame drew, coarse to fine.
    [[nodiscard]] std::span<const Draw> draws() const noexcept { return draws_; }
    /// The palette the last frame drew with (scene::world_palette's entries, RGBA8, r in the low byte).
    [[nodiscard]] std::span<const std::uint32_t> palette() const noexcept { return palette_; }

    /// The palette of the moment's theme `theme` (by day, or at night where `night`) and the world's style `style`
    /// (null: its entries the land's).
    [[nodiscard]] static std::vector<std::uint32_t> palette_of(const style::ThemeBlock& theme, bool night,
                                                               const tiles::Style* style);
    /// Every world pass of the process paints all the world in `colour` (RGBA8, r in the low byte; 0: its palette
    /// again), so a test tells the world's pixels from the countries', whose colours are the world's by design.
    static void paint_for_tests(std::uint32_t colour) noexcept;

private:
    static constexpr std::array<frame::Phase, 1> phase_list{
        frame::Phase{.slot = frame::Slot::ground, .order = -1, .id = 0}};

    std::vector<Draw> draws_;
    std::vector<std::uint32_t> palette_;

    /// A frame slot's own: its set, the heap buffer bound to it, and its draws.
    struct Slot {
        vk::raii::DescriptorSet set{nullptr};
        vk::Buffer bound;
        bool drawing = false;
    };
    std::array<Slot, 2> slots_{};

    // ---- What prepare makes.
    const gpu::Device* device_ = nullptr;
    gpu::Buffer palette_buffer_; // two frame slots' palettes
    vk::raii::DescriptorSetLayout set_layout_{nullptr};
    vk::raii::DescriptorPool pool_{nullptr};
    vk::raii::PipelineLayout layout_{nullptr};
    vk::raii::ShaderModule module_{nullptr};
    vk::raii::Pipeline fills_{nullptr};
    vk::raii::Pipeline fading_fills_{nullptr}; ///< a fading tile's fills: top first, GREATER, used only while one fades
    vk::raii::Pipeline lines_{nullptr};
};

} // namespace atlas::passes
