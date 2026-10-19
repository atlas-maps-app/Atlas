// Set 1's globals that no pass owns (plan-vector D5.6): the geometry heaps, the sun's bits (K8), the noise fields and
// the byte tables (K17a), the glyphs' curves and bands and the sprite atlas (K13). Each is filled by its own provider,
// which makes and fills its GPU resources on its own schedule (stage T's workers, its own threads, the render thread)
// and, once a frame, brings its bindings up to date through Globals: the frame layout makes a new version of set 1
// only when a binding's resource changes (a new buffer, a grown one, a new image), never per frame. The world holds the
// providers (frame::FrameScene::globals: its heaps' and its sun's, and those added to it, World::add_globals); the
// frame builder asks each one per frame, after the scene's update and before the passes' selections, in the world's
// order. K7 owns the hook; each provider owns its resources.
#pragma once

#include "frame/FrameLayout.hpp"
#include "gpu/Frames.hpp"

#include <cstdint>
#include <span>
#include <string_view>

namespace atlas::frame {

struct FrameState; // frame/FrameBuilder.hpp
class GlobalProvider;

/// What a provider sets its bindings through: the frame layout's set 1, its own bindings alone.
class Globals {
public:
    Globals(FrameLayout& layout, const GlobalProvider& provider) noexcept : layout_(layout), provider_(provider) {}

    /// Binding `g` reads `buffer` (from `offset`, `range` bytes) from the next frame on.
    void buffer(Global g, vk::Buffer buffer, vk::DeviceSize offset = 0, vk::DeviceSize range = vk::WholeSize);
    /// Binding `g` samples `view` with `sampler`, in `layout`.
    void image(Global g, vk::ImageView view, vk::Sampler sampler,
               vk::ImageLayout layout = vk::ImageLayout::eShaderReadOnlyOptimal);
    /// Binding `g` reads its placeholder again (the provider has nothing for it).
    void reset(Global g);

private:
    [[nodiscard]] bool owns(Global g) const noexcept;

    FrameLayout& layout_;
    const GlobalProvider& provider_;
};

/// A provider of some of set 1's global bindings.
class GlobalProvider {
public:
    GlobalProvider() = default;
    GlobalProvider(const GlobalProvider&) = delete;
    GlobalProvider& operator=(const GlobalProvider&) = delete;
    GlobalProvider(GlobalProvider&&) = delete;
    GlobalProvider& operator=(GlobalProvider&&) = delete;
    virtual ~GlobalProvider() = default;

    /// Its name, for logs and errors.
    [[nodiscard]] virtual std::string_view name() const noexcept = 0;
    /// The bindings it fills; no two providers of a world fill the same one.
    [[nodiscard]] virtual std::span<const Global> bindings() const noexcept = 0;

    /// Once a frame, on the render thread, after the scene's update and before the passes' selections: uploads what
    /// the frame reads into memory no frame still in flight reads (its slot's part of a ring, say) and, where a
    /// binding's resource changed, gives it to `globals` (only then).
    virtual void update(const FrameState& state, Globals& globals) = 0;

    /// The frame block's flags its resources allow this frame (frame.slang's FRAME_FIELDS: the fields and byte tables
    /// are bound), asked after update; none by default. The frame keeps FRAME_FIELDS in full mode alone.
    [[nodiscard]] virtual std::uint32_t frame_flags(const FrameState& state) const noexcept {
        (void)state;
        return 0;
    }

    /// Records what the GPU must do before the frame's passes read its resources (an image's upload, its layout's
    /// change), into the frame's command buffer before the offscreen phases; nothing by default.
    virtual void record(const gpu::FrameContext& frame) { (void)frame; }
};

} // namespace atlas::frame
