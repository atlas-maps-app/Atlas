// The frame's passes (plan-vector D19, D5.6, D5.12). Every pass of the renderer is a Pass in its own CP/passes/ file,
// registered by one line in CP/frame/PassRegistry.cpp (the coordinator's lines), and owns its pipelines, its shaders,
// its stage-T builder (build: a tile's geometry, once, on a worker) and its twin emitter (emit: the primitives it
// draws, on the CPU, for L1 and VK check), so feature packages never edit each other's files or the core's base.
//
// A frame (CP/frame/FrameBuilder.cpp) records every pass's phases in D5.12's order: the offscreen passes; the main
// pass's scene subpass, opaque objects front to back with rank depth and the objects bit, then the opaque ground (top
// layer first), then translucent primitives in painter's order (depth test on, writes off); the composite subpass, the
// composite, then the board's labels and the overlays without depth; then what follows the main pass (the frosted
// backdrops). Within a slot the phases go by their `order` (the renders' layer order across passes), ties in the
// registry's order. Sets 0 (the frame: its block, rank and selector tables) and 1 (the globals: the geometry heaps, the
// sun bits, the noise fields, the byte tables, the glyphs, the sprites) are bound once for the scene and the composite
// subpasses with the frame's pipeline layout (FrameLayout); a pass's pipeline layout starts with those two sets and the
// frame's push-constant range, so its pipelines keep them bound, and may add sets of its own from set 2.
//
// Everything a pass makes for a main pass and an output (pipelines, images, its descriptor sets) is made in prepare and
// let go of in release; every shader module and pipeline is announced to the crash guard for its making alone
// (make_module, make_pipeline: gpu/Pipelines.hpp's Announced), so a driver crashing in it is known by it (D5.10).
#pragma once

#include "frame/Counters.hpp"
#include "gpu/Device.hpp"
#include "gpu/Frames.hpp"
#include "gpu/PipelineCache.hpp"
#include "gpu/Pipelines.hpp"

#include <cstdint>
#include <span>
#include <string_view>

namespace atlas::scene {
class TileBuild; // scene/Residency.hpp
} // namespace atlas::scene

namespace atlas::twin {
class Stream; // twin/Twin.hpp
} // namespace atlas::twin

namespace atlas::frame {

class FrameBuilder; // frame/FrameBuilder.hpp
class FrameLayout;  // frame/FrameLayout.hpp
class Selectors;    // frame/Selectors.hpp
struct FrameState;  // frame/FrameBuilder.hpp

/// Where in the frame a phase records (D5.12's order).
enum class Slot : std::uint8_t {
    offscreen = 0,   ///< its own render passes or dispatches before the main pass (D5.5)
    objects = 1,     ///< scene subpass: opaque objects, front to back, rank depth, the objects bit (D6)
    ground = 2,      ///< scene subpass: the opaque ground, top layer first
    translucent = 3, ///< scene subpass: translucent primitives in painter's order, depth test on, writes off
    composite = 4,   ///< composite subpass: the composite (passes/Composite.cpp alone)
    labels = 5,      ///< composite subpass, after the composite: the board's labels, then the overlays, no depth
    after = 6,       ///< after the main pass: the frosted backdrops (K11b)
};

/// The number of slots.
inline constexpr std::size_t slot_count = 7;

/// The slot's name, for logs and tests.
[[nodiscard]] std::string_view name_of(Slot slot) noexcept;

/// One phase of a pass: its slot, its place in the slot (ascending; the renders' layer order across the passes of the
/// translucent slot, top first in the ground's), and the pass's own number for it, given back to record and emit.
struct Phase {
    Slot slot = Slot::objects;
    std::int32_t order = 0;
    std::uint32_t id = 0;
};

/// How a pipeline counts in the overdraw mode (Counters.hpp): its fragments shaded as opaque ones, as translucent ones,
/// or not at all (a pipeline outside the scene subpass).
enum class Overdraw : std::uint8_t { opaque, translucent, none };

/// What the pipelines a prepare made cost the driver: milliseconds and how many (VK bench's pipelines line).
struct PipelineStats {
    double ms = 0;
    std::uint32_t count = 0;
};

/// What a pass makes its pipelines and images for: the main pass of one configuration on one output.
struct PassContext {
    const gpu::Device& device;
    /// The pipeline cache its pipelines go through (make_pipeline notes each it makes, so the cache is written back).
    const gpu::PipelineCache& cache;
    /// The crash guard, told about each module and pipeline as it is made (null in tests without a guard).
    gpu::PipelineWatch* watch;
    const gpu::MainPass& pass;
    const gpu::Output& output;
    const FrameLayout& layout;
    /// How the log and the crash guard name the configuration ("A renderpass composite resolve rgba8").
    std::string_view configuration;
    /// The overdraw mode: make_pipeline makes each scene pipeline's overdraw twin in its place.
    bool overdraw = false;
    /// Where make_pipeline adds the driver's time and its count (may be null).
    PipelineStats* stats = nullptr;
    /// The frame's overdraw fragment module (the frame builder's; null outside the overdraw mode).
    vk::ShaderModule overdraw_module;
    /// The owner mode (FrameOptions::owners): make_pipeline makes each scene pipeline's owner twin in its place, which
    /// writes the painter slot it draws at; and the frame's owner fragment module (null outside it).
    bool owners = false;
    vk::ShaderModule owner_module;
};

/// What a pass records a phase into.
struct Recording {
    const gpu::FrameContext& frame;
    const FrameState& state;
    const FrameLayout& layout;
    PassCounters& counters;
};

/// A pass of the renderer.
class Pass {
public:
    Pass() = default;
    Pass(const Pass&) = delete;
    Pass& operator=(const Pass&) = delete;
    Pass(Pass&&) = delete;
    Pass& operator=(Pass&&) = delete;
    virtual ~Pass() = default;

    /// Its name in logs, counters and the crash guard's labels ("composite", "ground", "walls").
    [[nodiscard]] virtual std::string_view name() const noexcept = 0;
    /// Its phases, in any order; fixed for the pass's life.
    [[nodiscard]] virtual std::span<const Phase> phases() const noexcept = 0;

    /// Makes what depends on the main pass and the output (pipelines, images of the output's size, descriptor sets),
    /// after the GPU has finished every frame that used the last ones; what an earlier prepare made goes first.
    [[nodiscard]] virtual gpu::Result<void> prepare(const PassContext& context) = 0;
    /// Lets go of everything prepare made (the GPU has finished every frame that used it).
    virtual void release() noexcept = 0;

    /// Registers the item kinds the pass's items take (Selectors::kind), once, as the frame builder is made: before
    /// any tile is built, so stage T can write each item's row (ItemSpec::kind) from what the pass keeps of them. The
    /// rows never change.
    virtual void kinds(Selectors& selectors) { (void)selectors; }
    /// The detail thresholds of the item kinds the pass draws, for the frame (Selectors.hpp: from the frame's settings,
    /// win_d and the like), on the render thread before the ranks.
    virtual void select(const FrameState& state, Selectors& selectors) {
        (void)state;
        (void)selectors;
    }
    /// The painter slots the pass's ground takes this frame (D6: the ground is drawn before every item, so its slots
    /// come first), asked on the render thread after the selections and before the ranks. The frame lays the passes'
    /// ground slots out from slot 0 in the registry's order, a pass's own from first_ground_slot(), and the items'
    /// after all of them (RankOptions::ground_slots; FrameState::ground_slots; the frame block's info.y counts both).
    [[nodiscard]] virtual std::uint32_t ground_slots(const FrameState& state) const {
        (void)state;
        return 0;
    }
    /// Stage F of the pass, on the render thread, before the frame records: its draw lists for the frame's cover and
    /// ranks, written into its own per-frame buffers.
    virtual void update(const FrameState& state) { (void)state; }
    /// Records phase `phase` of the frame.
    virtual void record(const Recording& recording, std::uint32_t phase) = 0;

    /// Stage T of the pass: what it draws of a tile, built once as the tile loads, on a worker, into the geometry heaps
    /// (scene/Residency.hpp). Many tiles build at once: it must not touch the pass's own state.
    [[nodiscard]] virtual gpu::Result<void> build(scene::TileBuild& build) const {
        (void)build;
        return {};
    }

    /// The twin (D16's L1): the primitives phase `phase` draws at the frame, in its order, as G1's model, on the CPU.
    virtual void emit(const FrameState& state, twin::Stream& out, std::uint32_t phase) const {
        (void)state;
        (void)out;
        (void)phase;
    }

    /// The pass's first ground slot this frame (where ground_slots' count starts), from its update on; 0 until then.
    [[nodiscard]] std::uint32_t first_ground_slot() const noexcept { return first_ground_slot_; }

private:
    friend class FrameBuilder; // lays the ground slots out
    std::uint32_t first_ground_slot_ = 0;
};

/// A pass's shader module of embedded SPIR-V `words`, its making announced as `name` (the module's symbol) alone.
[[nodiscard]] gpu::Result<vk::raii::ShaderModule> make_module(const PassContext& context, std::string_view name,
                                                              std::span<const std::uint32_t> words);

/// A pass's pipeline, made through the context's cache with its making announced alone; for `overdraw` other than none
/// (a scene pipeline), in the owner mode its owner twin (the frame's owner fragment stage, writing the painter slot it
/// draws at, blending off), and in the overdraw mode its overdraw twin instead: its vertex stage, layout, target,
/// rasterisation and depth and stencil state, with the frame's overdraw fragment stage adding 1/255 of red for each
/// opaque fragment
/// shaded, of green for each translucent one.
[[nodiscard]] gpu::Result<vk::raii::Pipeline> make_pipeline(const PassContext& context, gpu::GraphicsDesc desc,
                                                            Overdraw overdraw);

} // namespace atlas::frame
