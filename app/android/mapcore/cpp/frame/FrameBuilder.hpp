// The frame builder (plan-vector D1, D5.12, D6, K7): the map's frame, what the device layer's renderer draws
// (gpu::Content, gpu/Frames.hpp) in place of VK bench's stand-in. Each frame, on the render thread:
//   1. stage F's per-frame values from the gesture camera (Kotlin's CameraState for this vsync) and the moment: the
//      engine camera on the device (view/Camera.hpp, through the active pack's ViewLadder when there is one,
//      view/Ladder.hpp), the screen layers (view/ScreenLayers.hpp), the cover (scene/Cover.hpp) and the painter ranks
//      (frame/Ranks.hpp), then every pass's own update (its draw lists);
//   2. the frame's slice of set 0's ring: the frame block (camera, theme, screen layers, counts), the rank table and
//      the selector table, written once, flushed;
//   3. the command buffer, in D5.12's order: the offscreen phases; the main pass's scene subpass (objects front to
//      back, the opaque ground, translucent in painter's order) with sets 0 and 1 bound once; the composite subpass
//      (the composite, then the labels and overlays); the phases after the main pass; with the GPU timestamps
//      gpu::Frames reads (Mark::offscreen, main, after) and each pass's counts.
// In both frame routes (dynamic rendering with local reads, the render pass with two subpasses), in every frame
// configuration the device layer picks (A on tilers, B, C; X1, VK bench's reference) and at 8 or 10 bits a channel.
// The one-subpass form (ONEPASS) is a conditional package A0 did not build: the builder refuses it.
//
// Nothing allocates per frame: the passes, their phases in order and the counters are made once; per-frame lists live
// in the passes' and the ranks' reused buffers.
#pragma once

#include "frame/Counters.hpp"
#include "frame/FrameBlock.hpp"
#include "frame/FrameLayout.hpp"
#include "frame/Globals.hpp"
#include "frame/Pass.hpp"
#include "frame/PassRegistry.hpp"
#include "frame/Ranks.hpp"
#include "frame/Selectors.hpp"
#include "gpu/Frames.hpp"
#include "gpu/PipelineCache.hpp"
#include "style/CameraState.hpp"
#include "style/Theme.hpp"
#include "view/Camera.hpp"
#include "view/Ladder.hpp"
#include "view/ScreenLayers.hpp"

#include <cstdint>
#include <memory>
#include <optional>
#include <span>
#include <string>
#include <vector>

namespace atlas::scene {
class Cover;         // scene/Cover.hpp
class Heaps;         // scene/Heaps.hpp
class ItemTable;     // scene/ItemTable.hpp
struct ResidentPack; // scene/Packs.hpp
} // namespace atlas::scene

namespace atlas::labels {
struct LabelSet; // labels/ (K13): the app's label sprites for a frame (MapCore.labels, Appendix D)
} // namespace atlas::labels

namespace atlas::frame {

/// How the frame is drawn (D16's modes).
enum class Mode : std::uint8_t {
    structural, ///< the core's filters off (mat, grain, cloud shadows): the gates' primary mode until A4
    full,       ///< every effect on
};

/// What one frame derives before it records: every pass reads it, none writes it.
struct FrameState {
    /// The frame's number (the renderer's) and time (seconds, the clock fades run on).
    std::uint64_t number = 0;
    double seconds = 0;
    /// The gesture camera as Kotlin wrote it for this vsync.
    style::CameraState camera{};
    /// The engine camera on the device: through the active pack's ladder (placed), else the camera state alone.
    std::optional<view::View> view;
    /// ViewLadder.place's camera settings for the frame (the active pack's region), when there is a ladder.
    std::optional<view::Placed> placed;
    /// The screen layers' numbers (ScreenLayers.of).
    std::optional<view::Layers> layers;
    /// The moment's theme block and the shaders' Theme of it.
    style::ThemeBlock theme{};
    /// The camera rests (the camera state's rest flag): the vertex stage snaps.
    bool rest = false;
    Mode mode = Mode::structural;
    /// The block set 0 holds this frame.
    shaders::FrameBlock block{};
    /// The scene the frame draws (null: no tiles): the cover and the item table with their ranks and detail bands.
    const scene::Cover* cover = nullptr;
    const scene::ItemTable* items = nullptr;
    const Ranks* ranks = nullptr;
    const Selectors* selectors = nullptr;
    /// The ground's painter slots this frame, every pass's (Pass::ground_slots): slots 0 to this, before every item's.
    std::uint32_t ground_slots = 0;
    /// The frame drawn (the world's: view::Frames::current), the one its metres are in; none without a pack.
    std::optional<view::GroundFrame> ground_frame;
    /// The packs' own stage T (scene/Packs.hpp): what each pass kept of the active pack and of the coarser one (null
    /// until built, or without such a pack).
    const scene::ResidentPack* active_pack = nullptr;
    const scene::ResidentPack* coarser_pack = nullptr;
    /// The app's label sprites for this frame (FrameInputs::labels; null: none).
    const labels::LabelSet* labels = nullptr;
    /// The frame slot (0 or 1): set 0's slice and the passes' own per-frame buffers.
    std::uint32_t slot = 0;
};

/// The builder's choices, made once.
struct FrameOptions {
    /// The overdraw mode (Counters.hpp): every scene pipeline replaced by its overdraw twin, the composite passing the
    /// counts through, nothing after it.
    bool overdraw = false;
    /// The frame's clear colour where nothing draws, encoded 0-1 (the ground plate draws over it, K9).
    std::array<float, 4> clear{0.92F, 0.90F, 0.86F, 1.0F};
    /// The composite's steps the frame lets run (composite.slang's COMPOSITE_ bits, of those its mode runs): every one
    /// by default; 0 stubs them all, and the composite passes the colours through.
    std::uint32_t steps = ~0U;
    /// The owner mode (the likeness harness's owner map): every scene pipeline replaced by its owner twin, which
    /// writes the painter slot it draws at (shaders/frame/owner.slang), the composite passing it through, nothing after
    /// it; drawn at one sample under the painter switch, the frame read back gives each pixel's latest slot in
    /// painter's order (owner_map).
    bool owners = false;
    /// The painter switch: what stands up draws in the renderer's painter's order (whole items by one point each, as
    /// the renders sort them) instead of at its true depth, to compare the two (painter.slang).
    bool painter = false;
};

/// What the frame draws of the world (scene/World.hpp): asked once a frame, once the frame's view is known and before
/// the passes' selections, ranks and updates.
class FrameScene {
public:
    FrameScene() = default;
    FrameScene(const FrameScene&) = delete;
    FrameScene& operator=(const FrameScene&) = delete;
    FrameScene(FrameScene&&) = delete;
    FrameScene& operator=(FrameScene&&) = delete;
    virtual ~FrameScene() = default;

    /// The active pack's ladder for the camera (none: the camera alone frames the view).
    [[nodiscard]] virtual const view::Ladder* ladder(const style::CameraState& camera) = 0;
    /// The frame's world for its view: the cover, the residency's requests, the tiles taken and let go of, the items.
    virtual void update(const FrameState& state) = 0;
    [[nodiscard]] virtual const scene::Cover* cover() const noexcept = 0;
    [[nodiscard]] virtual const scene::ItemTable* items() const noexcept = 0;
    /// The geometry heaps (null: none); set 1 binds them through their provider (globals).
    [[nodiscard]] virtual scene::Heaps* heaps() noexcept = 0;
    /// The frame drawn after update (view::Frames::current), or none.
    [[nodiscard]] virtual std::optional<view::GroundFrame> ground_frame() const noexcept { return std::nullopt; }
    /// The packs' stage T of the active pack and of the coarser one, once built (null otherwise).
    [[nodiscard]] virtual const scene::ResidentPack* active_pack() const noexcept { return nullptr; }
    [[nodiscard]] virtual const scene::ResidentPack* coarser_pack() const noexcept { return nullptr; }
    /// The providers of set 1's globals that no pass owns (frame/Globals.hpp), asked in this order each frame after
    /// update: the geometry heaps', the sun's, those added to the world.
    [[nodiscard]] virtual std::span<GlobalProvider* const> globals() noexcept { return {}; }
};

/// The frame's inputs for the next frame (the render thread's, from Kotlin and the scene).
struct FrameInputs {
    style::CameraState camera{};
    style::ThemeBlock theme{};
    double seconds = 0;
    Mode mode = Mode::structural;
    /// The world (null: nothing but the clear and the composite).
    FrameScene* scene = nullptr;
    /// The app's label sprites (MapCore.labels; null: none), read by K13's labels pass.
    const labels::LabelSet* labels = nullptr;
};

/// The map's frame.
class FrameBuilder final : public gpu::Content {
public:
    /// A builder of `passes` (the registry's, make_passes, or a test's), on `device`, its pipelines through `cache`,
    /// each module and pipeline announced to `watch` (the crash guard; may be null).
    [[nodiscard]] static gpu::Result<std::unique_ptr<FrameBuilder>>
    create(const gpu::Device& device, const gpu::PipelineCache& cache, std::vector<std::unique_ptr<Pass>> passes,
           FrameOptions options = {}, gpu::PipelineWatch* watch = nullptr);
    ~FrameBuilder() override;
    FrameBuilder(const FrameBuilder&) = delete;
    FrameBuilder& operator=(const FrameBuilder&) = delete;
    FrameBuilder(FrameBuilder&&) = delete;
    FrameBuilder& operator=(FrameBuilder&&) = delete;

    /// The next frame's inputs. The camera state must be read and checked (style::read_camera_state) and the theme
    /// block too (style::read_theme_block).
    void set_inputs(const FrameInputs& inputs) noexcept { inputs_ = inputs; }
    /// The painter switch (FrameOptions::painter), from the next frame on.
    void set_painter(bool painter) noexcept { options_.painter = painter; }
    /// The composite's steps the frame lets run from now on (FrameOptions::steps).
    void set_steps(std::uint32_t steps) noexcept { options_.steps = steps; }

    [[nodiscard]] gpu::Result<void> prepare(const gpu::MainPass& pass, const gpu::Output& output) override;
    void release() override;
    void record(const gpu::FrameContext& frame) override;

    /// The last frame's state and counts.
    [[nodiscard]] const FrameState& state() const noexcept { return state_; }
    [[nodiscard]] std::span<const PassCounters> counters() const noexcept { return counters_; }
    /// The last frame's counts over every pass.
    [[nodiscard]] Count total() const noexcept;
    /// CPU milliseconds of the last frame: stage F (camera, layers, cover, ranks, the passes' updates) and recording.
    [[nodiscard]] double stage_f_ms() const noexcept { return stage_f_ms_; }
    [[nodiscard]] double record_ms() const noexcept { return record_ms_; }
    /// The passes, in the registry's order.
    [[nodiscard]] std::span<const std::unique_ptr<Pass>> passes() const noexcept { return passes_; }
    [[nodiscard]] FrameLayout& layout() noexcept { return *layout_; }
    [[nodiscard]] const FrameOptions& options() const noexcept { return options_; }
    /// Milliseconds the driver spent making the pipelines the last prepare made, and how many it made.
    [[nodiscard]] double pipelines_ms() const noexcept { return pipelines_ms_; }
    [[nodiscard]] std::uint32_t pipeline_count() const noexcept { return pipeline_count_; }
    /// Whether it holds anything prepare made.
    [[nodiscard]] bool holds_prepared() const noexcept { return prepared_; }

    /// Derives the frame's state from the inputs, as record does before it records (also the twin's entry, which draws
    /// nothing): the view, the placed camera, the layers, the block. False when the camera state names no frame size.
    bool derive(std::uint64_t number, std::uint32_t slot, const gpu::Output* output);

    /// The twin of frame `number` (D16's L1): stage F as record runs it (the world's update, set 1's globals, the
    /// selections, ground slots, ranks and the passes' updates), then every pass's phases emitted in the frame's order
    /// into `out` (cleared first), under the frame's groups (twin::Stream::root, the SVG's clip to the frame;
    /// objects, the objects' shared group; ground, the ground's). It records nothing; `out` is left empty when the
    /// camera state names no frame size.
    void emit(twin::Stream& out, std::uint64_t number);

    /// The owner of painter slot `slot` in the last frame (its item's ItemSpec::owner): 0 for a ground slot, a slot no
    /// item holds, or no items.
    [[nodiscard]] std::uint64_t owner_of(std::uint32_t slot) const noexcept;
    /// The owner map of an owner-mode frame read back as 8-bit RGBA rows (`rgba`, 4 bytes a pixel) into `out`, a key
    /// per pixel (owner_of its slot; 0 where nothing drew).
    void owner_map(std::span<const std::uint8_t> rgba, std::span<std::uint64_t> out) const noexcept;

private:
    FrameBuilder(const gpu::Device& device, const gpu::PipelineCache& cache, FrameOptions options,
                 gpu::PipelineWatch* watch) noexcept;
    void record_slot(const gpu::FrameContext& frame, Slot slot);
    /// Stage F after derive: the world's update, set 1's globals, the selections, ground slots, ranks, the updates.
    void stage_f();
    void write_ring(std::uint32_t slot);

    /// One phase of one pass, in the frame's order.
    struct Step {
        Slot slot;
        std::int32_t order;
        std::uint32_t pass;
        std::uint32_t phase;
    };

    const gpu::Device& device_;
    const gpu::PipelineCache& cache_;
    FrameOptions options_;
    gpu::PipelineWatch* watch_ = nullptr;
    std::unique_ptr<FrameLayout> layout_;
    std::vector<std::unique_ptr<Pass>> passes_;
    std::vector<Step> steps_; ///< every phase, sorted: slot, order, registry
    std::array<std::pair<std::uint32_t, std::uint32_t>, slot_count> slot_steps_{}; ///< each slot's [first, last)
    std::vector<PassCounters> counters_;
    Selectors selectors_;
    Ranks ranks_;
    std::string configuration_;
    vk::raii::ShaderModule overdraw_module_{nullptr};
    vk::raii::ShaderModule owner_module_{nullptr};
    FrameInputs inputs_;
    FrameState state_;
    std::uint32_t quarter_turns_ = 0;
    std::uint32_t levels_ = 255;
    bool depth24_ = false; ///< the main pass's depth is 24-bit fixed point (painter.slang's scale)
    bool prepared_ = false;
    double stage_f_ms_ = 0;
    double record_ms_ = 0;
    double pipelines_ms_ = 0;
    std::uint32_t pipeline_count_ = 0;
};

} // namespace atlas::frame
