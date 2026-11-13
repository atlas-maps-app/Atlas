// The relief's light on the ground (ENGINE.md part 6, terrain, step 2: relief light on the plane; shaders/ground/
// relief.slang): where the terrain packs hold heights, the ground a frame has drawn is multiplied by the light its
// slopes and hollows take, as a share of flat open ground's (shaders/shared/relief.slang, fx/Relief.hpp), so the map's
// palette holds on the flat.
//
// One deferred multiply, not light in every ground stage: the opaque ground draws top layer first with depth, so every
// layer on the plane shares one light, and lighting what they left in the frame once equals lighting each of them. It
// costs one fragment a pixel, and no stage of the ground changes: a frame without relief (the terrain off, no terrain
// pack, engine mode) records nothing of this pass and is byte for byte the frame before it. The phase lies in the
// translucent slot after the ground's shadows (ground_order::shadows) and before the quays, the night's pools and
// what stands up; its depth test passes on the ground's painter depths alone (relief.slang).
//
// The tiles are the scene's (scene/Relief.hpp: FrameState::relief, a cover of its own), each a quad. Their texels lie
// in one array (257 × 257, A2B10G10R10, a layer a raster, as many as the scene bakes at most), made when the first
// raster comes: a map without terrain takes none of its memory. A raster's texels go up in the frame that first draws
// it (the upload phase, before the main pass), at most max_uploads a frame; a tile whose raster waits its turn is lit
// by the coarser raster under it meanwhile (ReliefDraw::under), and a tile lit where none was fades in.
#pragma once

#include "frame/Pass.hpp"
#include "gen/layouts.hpp"
#include "gpu/Memory.hpp"
#include "scene/Relief.hpp"

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::passes {

class Relief final : public frame::Pass {
public:
    /// The array's layers, the most quads a frame draws, and the most rasters that go up in a frame.
    static constexpr std::uint32_t layers = static_cast<std::uint32_t>(scene::ReliefTiles::max_tiles);
    static constexpr std::uint32_t max_quads = 512;
    static constexpr std::uint32_t max_uploads = 2;
    /// A layer's side, texels: the largest raster's (tiles::heights_side_allowed).
    static constexpr std::uint32_t side = 257;
    /// Its place among the translucent phases: after the ground's shadows (700), before the quays (800).
    static constexpr std::int32_t light_order = 750;
    /// The share of the cover's far depth over which the light fades out before it.
    static constexpr double far_fade = 0.15;

    Relief() = default;
    Relief(const Relief&) = delete;
    Relief& operator=(const Relief&) = delete;
    Relief(Relief&&) = delete;
    Relief& operator=(Relief&&) = delete;
    ~Relief() override;

    [[nodiscard]] std::string_view name() const noexcept override { return "relief"; }
    [[nodiscard]] std::span<const frame::Phase> phases() const noexcept override { return phase_list; }
    [[nodiscard]] gpu::Result<void> prepare(const frame::PassContext& context) override;
    void release() noexcept override;
    void update(const frame::FrameState& state) override;
    [[nodiscard]] bool fading() const noexcept override { return fading_; }
    void record(const frame::Recording& recording, std::uint32_t phase) override;

    // ---- For tests.
    /// The quads the last update wrote for frame slot `slot`, and its draw's push constants.
    [[nodiscard]] std::span<const layouts::ground_relief::ReliefQuad> quads(std::uint32_t slot) const noexcept {
        return slots_.at(slot & 1U).quads;
    }
    [[nodiscard]] const layouts::ground_relief::ReliefPush& push(std::uint32_t slot) const noexcept {
        return slots_.at(slot & 1U).push;
    }
    /// The rasters whose texels went up, all told.
    [[nodiscard]] std::uint64_t uploads() const noexcept { return uploads_; }

private:
    static constexpr std::uint32_t upload_phase = 0;
    static constexpr std::uint32_t light_phase = 1;
    // the upload after every pass's decide phase and the ground's offscreen layers (orders 0 to 3): a copy alone
    static constexpr std::array<frame::Phase, 2> phase_list{
        frame::Phase{.slot = frame::Slot::offscreen, .order = 4, .id = upload_phase},
        frame::Phase{.slot = frame::Slot::translucent, .order = light_order, .id = light_phase}};

    /// A layer of the array: the raster it holds (its serial; 0: none), the last frame that drew it, and when it began
    /// to fade in (seconds; before every frame: drawn whole).
    struct Layer {
        std::uint64_t serial = 0;
        std::uint64_t drawn = 0;
        double since = -1.0e30;
    };
    /// A raster's texels on their way: from the frame slot's staging to a layer.
    struct Copy {
        std::uint32_t layer = 0;
        std::uint32_t side = 0;
        vk::DeviceSize offset = 0;
    };
    struct Slot {
        vk::raii::DescriptorSet set{nullptr};
        std::vector<layouts::ground_relief::ReliefQuad> quads;
        layouts::ground_relief::ReliefPush push{};
    };

    /// The array, its view and both slots' sets, made once a raster is to go up; false where the device has no room.
    [[nodiscard]] bool make_array();
    /// The layer holding raster `serial`, or none.
    [[nodiscard]] std::optional<std::uint32_t> layer_of(std::uint64_t serial) const noexcept;

    std::array<Slot, 2> slots_{};
    std::array<Layer, layers> layers_{};
    std::vector<Copy> copies_; ///< this frame's, for its upload phase (emptied there)
    std::uint64_t uploads_ = 0;
    bool fading_ = false;
    bool written_ = false; ///< the array has been written to (its layout: read by shaders between uploads)
    bool refused_ = false; ///< the array could not be made: not tried again until the next prepare

    // ---- What prepare makes (the array once needed).
    const gpu::Device* device_ = nullptr;
    gpu::Buffer quads_;   // two frame slots' quads
    gpu::Buffer staging_; // two frame slots' texels on their way, max_uploads rasters each
    gpu::Image array_;
    vk::raii::ImageView view_{nullptr};
    vk::raii::Sampler sampler_{nullptr};
    vk::raii::DescriptorSetLayout set_layout_{nullptr};
    vk::raii::DescriptorPool pool_{nullptr};
    vk::raii::PipelineLayout layout_{nullptr};
    vk::raii::ShaderModule module_{nullptr};
    vk::raii::Pipeline pipeline_{nullptr};
};

} // namespace atlas::passes
