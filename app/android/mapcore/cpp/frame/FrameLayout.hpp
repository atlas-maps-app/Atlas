// The frame's descriptors and per-frame buffer (plan-vector D5.6). Set 0, per frame: the frame block (shaders/shared/
// frame.slang's FrameBlock, a uniform buffer), the rank table (each item's first painter slot, CP/frame/Ranks.cpp) and
// the selector table (per item kind, the frame's detail choices, CP/frame/Selectors.cpp), each in the frame's slice of
// one ring at a dynamic offset. Set 1, global: the geometry heaps, the sun bits, the noise fields, the byte tables, the
// glyph curves and bands, the sprite atlas, each a binding of its own (Global), written only when one of them changes
// (a heap grows, the fields are made), never per frame. Per draw, push constants of at most 128 bytes (the tile's
// place, base offsets, the pass's parameters). Every pass's pipeline layout begins with these two sets and that range
// (make_layout), so the frame binds them once for its scene and composite subpasses.
//
// A set the GPU may still read is never written: a change makes a new version of the set, which the next frame binds,
// and the old one is freed once the frames that used it have finished (two frames later). Until a resource is given,
// its binding holds a placeholder (a 256-byte buffer, a 1 × 1 image), so every binding of every version is valid.
#pragma once

#include "gpu/Device.hpp"
#include "gpu/Memory.hpp"
#include "gpu/Sync.hpp"

#include <array>
#include <cstdint>
#include <memory>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::frame {

/// Set 1's bindings (shaders read them at these numbers).
enum class Global : std::uint8_t {
    points = 0,        ///< the points heap (storage buffer)
    walls = 1,         ///< the walls heap
    ground = 2,        ///< the ground heap
    lines = 3,         ///< the lines heap
    instances = 4,     ///< the instances heap
    labels = 5,        ///< the labels heap
    sun_bits = 6,      ///< the sun bits of the resident tiles (storage buffer, K8's BitTiles texels)
    noise_rgba = 7,    ///< the screen-wide noise fields (combined image samplers, K17a)
    noise_rg = 8,      ///<
    noise_r = 9,       ///<
    byte_tables = 10,  ///< SvgFilterRaster's byte tables (storage buffer, K17a)
    glyph_curves = 11, ///< the glyphs' curves and bands (storage buffers, K13)
    glyph_bands = 12,  ///<
    sprites = 13,      ///< the sprite atlas: the board's labels (combined image sampler, K13)
};

/// The number of set 1's bindings.
inline constexpr std::uint32_t global_count = 14;

/// Whether a global binding is a storage buffer (else a combined image sampler).
[[nodiscard]] constexpr bool is_buffer(Global g) noexcept {
    return g != Global::noise_rgba && g != Global::noise_rg && g != Global::noise_r && g != Global::sprites;
}

/// The global binding's name.
[[nodiscard]] std::string_view name_of(Global g) noexcept;

/// The push constants' size: every pass's pipeline layout has this range, for its vertex and fragment stages.
inline constexpr std::uint32_t push_bytes = 128;
inline constexpr vk::ShaderStageFlags push_stages =
    vk::ShaderStageFlagBits::eVertex | vk::ShaderStageFlagBits::eFragment;

/// Where set 0's parts lie in one frame's slice of the ring.
struct RingSlice {
    vk::DeviceSize block = 0;     ///< the frame block
    vk::DeviceSize ranks = 0;     ///< the rank table: one uint per item
    vk::DeviceSize selectors = 0; ///< the selector table: one float4 per kind and band
    vk::DeviceSize bytes = 0;     ///< the slice, aligned for the next
};

/// The frame's descriptor layouts, its pipeline layout, set 0's ring and set 1's resources.
class FrameLayout {
public:
    /// Room for `items` items in the rank table and `kinds` selector rows (grown when a frame needs more).
    [[nodiscard]] static gpu::Result<std::unique_ptr<FrameLayout>>
    create(const gpu::Device& device, std::uint32_t items = 1U << 17U, std::uint32_t kinds = 256);
    FrameLayout(const FrameLayout&) = delete;
    FrameLayout& operator=(const FrameLayout&) = delete;
    FrameLayout(FrameLayout&&) = delete;
    FrameLayout& operator=(FrameLayout&&) = delete;
    ~FrameLayout();

    [[nodiscard]] vk::DescriptorSetLayout frame_set_layout() const noexcept { return *frame_set_layout_; }
    [[nodiscard]] vk::DescriptorSetLayout global_set_layout() const noexcept { return *global_set_layout_; }
    /// Sets 0 and 1 and the push constants: the layout the frame binds its sets with, and that of every pipeline that
    /// needs no set of its own.
    [[nodiscard]] vk::PipelineLayout pipeline_layout() const noexcept { return *pipeline_layout_; }
    /// A pipeline layout of sets 0 and 1, then `extra` (sets 2 on), and the push constants.
    [[nodiscard]] gpu::Result<vk::raii::PipelineLayout>
    make_layout(std::span<const vk::DescriptorSetLayout> extra) const;

    /// Gives set 1's binding `g` a storage buffer range (a heap, the sun bits, ...); the next frame binds it.
    void set_buffer(Global g, vk::Buffer buffer, vk::DeviceSize offset = 0, vk::DeviceSize range = vk::WholeSize);
    /// Gives set 1's binding `g` a sampled image.
    void set_image(Global g, vk::ImageView view, vk::Sampler sampler,
                   vk::ImageLayout layout = vk::ImageLayout::eShaderReadOnlyOptimal);
    /// Puts binding `g`'s placeholder back.
    void reset(Global g);

    // ---- For the frame builder.

    /// Records what the layout needs once before its resources are read (the placeholder image's clear); the frame
    /// builder calls it as every frame begins, and it records nothing after the first.
    void record_setup(const vk::raii::CommandBuffer& cmd, const gpu::Sync& sync);

    /// Before frame `frame` records: frees the versions only frames before `finished` used, makes new versions of the
    /// sets whose resources changed, and grows the ring for `items` items and `kinds` selector rows where needed.
    [[nodiscard]] gpu::Result<void> begin_frame(std::uint64_t frame, std::uint64_t finished, std::uint32_t items,
                                                std::uint32_t kinds);
    /// The sets the frame binds, and its dynamic offsets (block, ranks, selectors) for frame slot `slot`.
    [[nodiscard]] std::array<vk::DescriptorSet, 2> sets() const noexcept;
    [[nodiscard]] std::array<std::uint32_t, 3> offsets(std::uint32_t slot) const noexcept;
    /// Frame slot `slot`'s bytes of the ring (mapped): its block, ranks and selectors at slice().
    [[nodiscard]] std::span<std::byte> ring(std::uint32_t slot) const noexcept;
    [[nodiscard]] const RingSlice& slice() const noexcept { return slice_; }
    /// Makes the CPU's writes into slot `slot`'s slice visible to the GPU (a no-op on coherent memory).
    void flush(std::uint32_t slot) const;
    /// The rank table's and the selector table's capacities.
    [[nodiscard]] std::uint32_t item_capacity() const noexcept { return items_; }
    [[nodiscard]] std::uint32_t kind_capacity() const noexcept { return kinds_; }
    /// Bytes of the ring and the placeholders.
    [[nodiscard]] vk::DeviceSize bytes() const noexcept;
    /// How many versions of each set were made (the tests' check that nothing is written per frame).
    [[nodiscard]] std::uint64_t frame_versions() const noexcept { return frame_versions_; }
    [[nodiscard]] std::uint64_t global_versions() const noexcept { return global_versions_; }

private:
    explicit FrameLayout(const gpu::Device& device) noexcept : device_(device) {}
    [[nodiscard]] gpu::Result<void> make_ring(std::uint32_t items, std::uint32_t kinds);
    [[nodiscard]] gpu::Result<void> write_frame_set();
    [[nodiscard]] gpu::Result<void> write_global_set();

    /// One version of a set, and the last frame that bound it.
    struct Version {
        vk::raii::DescriptorSet set{nullptr};
        std::uint64_t last_frame = 0;
    };
    /// A resource of set 1.
    struct Resource {
        vk::Buffer buffer;
        vk::DeviceSize offset = 0;
        vk::DeviceSize range = vk::WholeSize;
        vk::ImageView view;
        vk::Sampler sampler;
        vk::ImageLayout layout = vk::ImageLayout::eShaderReadOnlyOptimal;
    };
    /// A ring set 0's older versions still point at, kept until they go.
    struct OldRing {
        gpu::Buffer buffer;
        std::uint64_t last_frame = 0;
    };

    const gpu::Device& device_;
    vk::raii::DescriptorSetLayout frame_set_layout_{nullptr};
    vk::raii::DescriptorSetLayout global_set_layout_{nullptr};
    vk::raii::PipelineLayout pipeline_layout_{nullptr};
    vk::raii::DescriptorPool pool_{nullptr};
    std::vector<Version> frame_sets_;  // the newest last
    std::vector<Version> global_sets_; // the newest last
    gpu::Buffer ring_;
    std::vector<OldRing> old_rings_;
    RingSlice slice_;
    std::uint32_t items_ = 0;
    std::uint32_t kinds_ = 0;
    std::array<Resource, global_count> globals_{};
    bool globals_dirty_ = true;
    bool frame_dirty_ = true;
    std::uint64_t current_frame_ = 0;
    std::uint64_t frame_versions_ = 0;
    std::uint64_t global_versions_ = 0;
    // The placeholders.
    gpu::Buffer placeholder_buffer_;
    gpu::Image placeholder_image_;
    vk::raii::ImageView placeholder_view_{nullptr};
    vk::raii::Sampler placeholder_sampler_{nullptr};
    bool placeholder_ready_ = false; // the image is in its shader-read layout
};

} // namespace atlas::frame
