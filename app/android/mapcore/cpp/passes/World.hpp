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
//
// Beyond 18 km the style ladder (ENGINE.md step 5, its step 8; scene/WorldLook.hpp) shades the world by the frame's
// look, a block in set 2 beside the palette, whose second half is the day's colours (the night's hand-over to the
// caps). After the opaque ground, in the translucent slot's first phase: space where the sky was, the atmosphere's ring
// and the outline about the planet's disc (a triangle over the frame under the world's depth), the shine and the rim
// over the disc (the same triangle over it), then the cities' lights on the night side, each tile's.
#pragma once

#include "frame/Pass.hpp"
#include "gpu/Memory.hpp"
#include "scene/WorldLook.hpp"

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
    /// One tile's draw, or one part's of a tile in parts (scene::WorldPart): where its words lie and how it is placed
    /// (world.slang's WorldPush). A tile's parts are drawn one after another, all their fills before their lines.
    struct Draw {
        std::array<float, 4> origin{};
        std::array<float, 4> scale{};
        std::array<std::uint32_t, 4> at{};
        std::array<float, 4> globe{};  ///< world.slang's push: the tile's edges less the target's, the radians a unit
        std::array<float, 4> sphere{}; ///< spans; the target's latitude, w₂ and the radius; the target from the eye
        std::array<float, 4> target{};
        std::uint32_t triangles = 0;
        std::uint32_t segments = 0;
        std::uint32_t coast = 0;       ///< the last of its segments, its coast's: drawn where the ramps have weight
        std::uint32_t lights = 0;      ///< its cities' lights, after its segments' words
        std::uint32_t light_cells = 0; ///< the cells whose lights it draws (scene::WorldDraw::light_cells)
        float fade = 1.0F;             ///< under 1 while the tile fades in (scene::WorldDraw::fade)
        std::uint8_t level = 0;        ///< its tile's level
        std::uint32_t lift = 0; ///< while it fades, its depth's lift above the world's (World.cpp's fading_depth)
        std::uint32_t tile = 0; ///< its tile's place among the frame's: the same for each of a tile's parts
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
    /// The palette the last frame drew with (scene::world_palette's entries, RGBA8, r in the low byte): the moment's,
    /// then the day's.
    [[nodiscard]] std::span<const std::uint32_t> palette() const noexcept { return palette_; }

    /// The palette of the moment's theme `theme` (by day, or at night where `night`) and the world's style `style`
    /// (null: its entries the land's).
    [[nodiscard]] static std::vector<std::uint32_t> palette_of(const style::ThemeBlock& theme, bool night,
                                                               const tiles::Style* style);
    /// How much of the moment's own ground, water and roads the far world's day palette has, by the sun's elevation at
    /// the moment's place: none at night, where the moment's are the night's, and none at the night flag's own
    /// threshold (style::night_below), all from 5 degrees up, smoothstep between. So the far world does not step
    /// when the flag flips (it stepped 5 to 7 levels from 75 km out, measured 11 November), and by day it is the
    /// moment's.
    static constexpr double day_share_whole = 5.0;
    [[nodiscard]] static double day_share(const style::ThemeBlock& theme) noexcept;
    /// The day's palette, which the ladder draws under the night's caps: palette_of's by day with, for its ground,
    /// water and roads, the ladder's where the moment is night and the moment's own by day (day_share between).
    [[nodiscard]] static std::vector<std::uint32_t> day_palette_of(const style::ThemeBlock& theme,
                                                                   const tiles::Style* style);
    /// Every world pass of the process paints all the world in `colour` (RGBA8, r in the low byte; 0: its palette
    /// again), so a test tells the world's pixels from the countries', whose colours are the world's by design.
    static void paint_for_tests(std::uint32_t colour) noexcept;
    /// Every world pass of the process draws without the style ladder (`on` false: its palette alone, the sky where
    /// no tile draws) or with it again, so a test of the tiles' cover tells a pixel no tile draws by the sky's colour.
    static void ladder_for_tests(bool on) noexcept;

private:
    /// The tiles, first of the opaque ground; then over it, before every other translucent layer, space, the glass
    /// and the lights.
    static constexpr std::uint32_t tiles_phase = 0;
    static constexpr std::uint32_t over_phase = 1;
    static constexpr std::array<frame::Phase, 2> phase_list{
        frame::Phase{.slot = frame::Slot::ground, .order = -1, .id = tiles_phase},
        frame::Phase{.slot = frame::Slot::translucent, .order = 50, .id = over_phase}};

    void record_tiles(const frame::Recording& recording);
    void record_over(const frame::Recording& recording);

    std::vector<Draw> draws_;
    std::vector<std::uint32_t> palette_;
    scene::WorldLook look_;
    bool space_ = false;
    bool glass_ = false;

    /// A frame slot's own: its set, the heap buffer bound to it and the bytes bound, and its draws.
    struct Slot {
        vk::raii::DescriptorSet set{nullptr};
        vk::Buffer bound;
        vk::DeviceSize bound_bytes = 0;
        bool drawing = false;
    };
    std::array<Slot, 2> slots_{};

    // ---- What prepare makes.
    const gpu::Device* device_ = nullptr;
    gpu::Buffer palette_buffer_; // two frame slots' palettes
    gpu::Buffer look_buffer_;    // two frame slots' look blocks
    vk::raii::DescriptorSetLayout set_layout_{nullptr};
    vk::raii::DescriptorPool pool_{nullptr};
    vk::raii::PipelineLayout layout_{nullptr};
    vk::raii::ShaderModule module_{nullptr};
    vk::raii::Pipeline fills_{nullptr};
    vk::raii::Pipeline fading_fills_{nullptr}; ///< a fading tile's fills: top first, GREATER, used only while one fades
    vk::raii::Pipeline lines_{nullptr};
    /// The same three for a frame the ladder shades (world.slang's look stages): a frame it leaves plain draws with
    /// the three above, as every frame up to 18 km does.
    vk::raii::Pipeline look_fills_{nullptr};
    vk::raii::Pipeline look_fading_fills_{nullptr};
    vk::raii::Pipeline look_lines_{nullptr};
    vk::raii::Pipeline lights_{nullptr};
    vk::raii::Pipeline globe_{nullptr}; ///< space and the glass: a triangle over the frame
};

} // namespace atlas::passes
