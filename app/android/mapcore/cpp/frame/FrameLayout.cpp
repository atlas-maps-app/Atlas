// The frame's descriptors and per-frame buffer (frame/FrameLayout.hpp).
#include "frame/FrameLayout.hpp"

#include "frame/FrameBlock.hpp"
#include "gpu/Descriptors.hpp"
#include "gpu/Frames.hpp"

#include <algorithm>
#include <cstring>

namespace atlas::frame {

namespace {

constexpr vk::ShaderStageFlags all_stages =
    vk::ShaderStageFlagBits::eVertex | vk::ShaderStageFlagBits::eFragment | vk::ShaderStageFlagBits::eCompute;

/// The most versions of a set alive at once: the newest, and the ones frames in flight may still read.
constexpr std::uint32_t max_versions = 8;

constexpr std::array frame_bindings{
    gpu::Binding{.slot = 0, .type = vk::DescriptorType::eUniformBufferDynamic, .stages = all_stages},
    gpu::Binding{.slot = 1, .type = vk::DescriptorType::eStorageBufferDynamic, .stages = all_stages},
    gpu::Binding{.slot = 2, .type = vk::DescriptorType::eStorageBufferDynamic, .stages = all_stages},
};

[[nodiscard]] std::array<gpu::Binding, global_count> global_bindings() {
    std::array<gpu::Binding, global_count> out{};
    for (std::uint32_t i = 0; i < global_count; ++i) {
        out[i] = {.slot = i,
                  .type = is_buffer(static_cast<Global>(i)) ? vk::DescriptorType::eStorageBuffer
                                                            : vk::DescriptorType::eCombinedImageSampler,
                  .stages = all_stages};
    }
    return out;
}

[[nodiscard]] vk::DeviceSize align_up(vk::DeviceSize v, vk::DeviceSize a) noexcept { return (v + a - 1) / a * a; }

} // namespace

std::string_view name_of(Global g) noexcept {
    switch (g) {
    case Global::points: return "points";
    case Global::walls: return "walls";
    case Global::ground: return "ground";
    case Global::lines: return "lines";
    case Global::instances: return "instances";
    case Global::labels: return "labels";
    case Global::sun_bits: return "sun_bits";
    case Global::noise_rgba: return "noise_rgba";
    case Global::noise_rg: return "noise_rg";
    case Global::noise_r: return "noise_r";
    case Global::byte_tables: return "byte_tables";
    case Global::glyph_curves: return "glyph_curves";
    case Global::glyph_bands: return "glyph_bands";
    case Global::sprites: return "sprites";
    case Global::noise_canopy: return "noise_canopy";
    }
    return "?";
}

gpu::Result<std::unique_ptr<FrameLayout>> FrameLayout::create(const gpu::Device& device, std::uint32_t items,
                                                              std::uint32_t kinds) {
    std::unique_ptr<FrameLayout> self(new FrameLayout(device)); // NOLINT(cppcoreguidelines-owning-memory)
    const vk::raii::Device& d = device.handle();
    const auto globals = global_bindings();
    ATLAS_GPU_LET(frame_layout, gpu::make_set_layout(d, frame_bindings));
    ATLAS_GPU_LET(global_layout, gpu::make_set_layout(d, globals));
    self->frame_set_layout_ = std::move(frame_layout);
    self->global_set_layout_ = std::move(global_layout);
    const std::array<vk::DescriptorSetLayout, 2> sets{*self->frame_set_layout_, *self->global_set_layout_};
    ATLAS_GPU_LET(pipeline_layout, gpu::make_layout(d, sets, push_bytes, push_stages));
    self->pipeline_layout_ = std::move(pipeline_layout);
    const std::array<std::span<const gpu::Binding>, 2> kinds_of_sets{frame_bindings, globals};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds_of_sets, max_versions));
    self->pool_ = std::move(pool);

    // The placeholders: a small storage buffer and a 1 × 1 image (cleared as the first frame begins: record_setup).
    ATLAS_GPU_LET(buffer, device.memory().buffer(256, vk::BufferUsageFlagBits::eStorageBuffer, gpu::MemoryUse::upload));
    std::memset(buffer.bytes().data(), 0, buffer.bytes().size());
    buffer.flush();
    self->placeholder_buffer_ = std::move(buffer);
    ATLAS_GPU_LET(
        image, device.memory().image({.imageType = vk::ImageType::e2D,
                                      .format = vk::Format::eR8G8B8A8Unorm,
                                      .extent = {.width = 1, .height = 1, .depth = 1},
                                      .mipLevels = 1,
                                      .arrayLayers = 1,
                                      .samples = vk::SampleCountFlagBits::e1,
                                      .tiling = vk::ImageTiling::eOptimal,
                                      .usage = vk::ImageUsageFlagBits::eSampled | vk::ImageUsageFlagBits::eTransferDst,
                                      .initialLayout = vk::ImageLayout::eUndefined},
                                     gpu::MemoryUse::gpu));
    self->placeholder_image_ = std::move(image);
    ATLAS_VK_LET(
        view,
        d.createImageView(
            {.image = self->placeholder_image_.handle(),
             .viewType = vk::ImageViewType::e2D,
             .format = vk::Format::eR8G8B8A8Unorm,
             .subresourceRange = {.aspectMask = vk::ImageAspectFlagBits::eColor, .levelCount = 1, .layerCount = 1}}),
        "the frame's placeholder image's view");
    self->placeholder_view_ = std::move(view);
    ATLAS_VK_LET(sampler,
                 d.createSampler({.magFilter = vk::Filter::eLinear,
                                  .minFilter = vk::Filter::eLinear,
                                  .mipmapMode = vk::SamplerMipmapMode::eNearest,
                                  .addressModeU = vk::SamplerAddressMode::eClampToEdge,
                                  .addressModeV = vk::SamplerAddressMode::eClampToEdge,
                                  .addressModeW = vk::SamplerAddressMode::eClampToEdge,
                                  .maxLod = 0.0F}),
                 "the frame's placeholder sampler");
    self->placeholder_sampler_ = std::move(sampler);
    for (std::uint32_t i = 0; i < global_count; ++i) self->reset(static_cast<Global>(i));
    ATLAS_GPU_CHECK(self->make_ring(items, kinds));
    return self;
}

FrameLayout::~FrameLayout() = default;

gpu::Result<vk::raii::PipelineLayout> FrameLayout::make_layout(std::span<const vk::DescriptorSetLayout> extra) const {
    std::vector<vk::DescriptorSetLayout> sets{*frame_set_layout_, *global_set_layout_};
    sets.insert(sets.end(), extra.begin(), extra.end());
    return gpu::make_layout(device_.handle(), sets, push_bytes, push_stages);
}

void FrameLayout::set_buffer(Global g, vk::Buffer buffer, vk::DeviceSize offset, vk::DeviceSize range) {
    Resource& r = globals_[static_cast<std::size_t>(g)];
    if (r.buffer == buffer && r.offset == offset && r.range == range) return;
    r = {.buffer = buffer, .offset = offset, .range = range, .view = {}, .sampler = {}, .layout = {}};
    globals_dirty_ = true;
}

void FrameLayout::set_image(Global g, vk::ImageView view, vk::Sampler sampler, vk::ImageLayout layout) {
    Resource& r = globals_[static_cast<std::size_t>(g)];
    if (r.view == view && r.sampler == sampler && r.layout == layout) return;
    r = {.buffer = {}, .offset = 0, .range = vk::WholeSize, .view = view, .sampler = sampler, .layout = layout};
    globals_dirty_ = true;
}

void FrameLayout::reset(Global g) {
    if (is_buffer(g)) {
        set_buffer(g, placeholder_buffer_.handle(), 0, vk::WholeSize);
    } else {
        set_image(g, *placeholder_view_, *placeholder_sampler_, vk::ImageLayout::eShaderReadOnlyOptimal);
    }
}

void FrameLayout::record_setup(const vk::raii::CommandBuffer& cmd, const gpu::Sync& sync) {
    if (placeholder_ready_) return;
    // The placeholder image, cleared to zero once and left for the shaders to read.
    const vk::ImageSubresourceRange all{
        .aspectMask = vk::ImageAspectFlagBits::eColor, .levelCount = 1, .layerCount = 1};
    sync.barrier(cmd, gpu::ImageDependency{.image = placeholder_image_.handle(),
                                           .src_stage = vk::PipelineStageFlagBits2::eTopOfPipe,
                                           .src_access = {},
                                           .dst_stage = vk::PipelineStageFlagBits2::eTransfer,
                                           .dst_access = vk::AccessFlagBits2::eTransferWrite,
                                           .old_layout = vk::ImageLayout::eUndefined,
                                           .new_layout = vk::ImageLayout::eTransferDstOptimal});
    cmd.clearColorImage(placeholder_image_.handle(), vk::ImageLayout::eTransferDstOptimal,
                        vk::ClearColorValue{std::array<float, 4>{0, 0, 0, 0}}, all);
    sync.barrier(cmd, gpu::ImageDependency{.image = placeholder_image_.handle(),
                                           .src_stage = vk::PipelineStageFlagBits2::eTransfer,
                                           .src_access = vk::AccessFlagBits2::eTransferWrite,
                                           .dst_stage = vk::PipelineStageFlagBits2::eVertexShader |
                                                        vk::PipelineStageFlagBits2::eFragmentShader |
                                                        vk::PipelineStageFlagBits2::eComputeShader,
                                           .dst_access = vk::AccessFlagBits2::eShaderRead,
                                           .old_layout = vk::ImageLayout::eTransferDstOptimal,
                                           .new_layout = vk::ImageLayout::eShaderReadOnlyOptimal});
    placeholder_ready_ = true;
}

gpu::Result<void> FrameLayout::make_ring(std::uint32_t items, std::uint32_t kinds) {
    const vk::PhysicalDeviceLimits& limits = device_.facts().limits;
    const auto align =
        std::max<vk::DeviceSize>({limits.minUniformBufferOffsetAlignment, limits.minStorageBufferOffsetAlignment, 256});
    RingSlice s;
    s.block = 0;
    s.ranks = align_up(sizeof(shaders::FrameBlock), align);
    s.selectors = s.ranks + align_up(std::max<vk::DeviceSize>(4, vk::DeviceSize{items} * 4), align);
    s.bytes = s.selectors + align_up(std::max<vk::DeviceSize>(16, vk::DeviceSize{kinds} * 16), align);
    ATLAS_GPU_LET(
        ring, device_.memory().buffer(s.bytes * gpu::Frames::in_flight,
                                      vk::BufferUsageFlagBits::eUniformBuffer | vk::BufferUsageFlagBits::eStorageBuffer,
                                      gpu::MemoryUse::upload));
    if (ring_) old_rings_.push_back({.buffer = std::move(ring_), .last_frame = current_frame_});
    ring_ = std::move(ring);
    slice_ = s;
    items_ = items;
    kinds_ = kinds;
    frame_dirty_ = true;
    return {};
}

gpu::Result<void> FrameLayout::write_frame_set() {
    const vk::raii::Device& d = device_.handle();
    ATLAS_GPU_LET(set, gpu::allocate_set(d, pool_, frame_set_layout_));
    using T = vk::DescriptorType;
    gpu::SetWriter(*set)
        .buffer(0, T::eUniformBufferDynamic, ring_.handle(), slice_.block, sizeof(shaders::FrameBlock))
        .buffer(1, T::eStorageBufferDynamic, ring_.handle(), slice_.ranks, slice_.selectors - slice_.ranks)
        .buffer(2, T::eStorageBufferDynamic, ring_.handle(), slice_.selectors, slice_.bytes - slice_.selectors)
        .apply(d);
    frame_sets_.push_back({.set = std::move(set), .last_frame = current_frame_});
    ++frame_versions_;
    frame_dirty_ = false;
    return {};
}

gpu::Result<void> FrameLayout::write_global_set() {
    const vk::raii::Device& d = device_.handle();
    ATLAS_GPU_LET(set, gpu::allocate_set(d, pool_, global_set_layout_));
    gpu::SetWriter w(*set);
    for (std::uint32_t i = 0; i < global_count; ++i) {
        const Resource& r = globals_[i];
        if (is_buffer(static_cast<Global>(i))) {
            w.buffer(i, vk::DescriptorType::eStorageBuffer, r.buffer, r.offset, r.range);
        } else {
            w.image(i, vk::DescriptorType::eCombinedImageSampler, r.view, r.layout, r.sampler);
        }
    }
    w.apply(d);
    global_sets_.push_back({.set = std::move(set), .last_frame = current_frame_});
    ++global_versions_;
    globals_dirty_ = false;
    return {};
}

gpu::Result<void> FrameLayout::begin_frame(std::uint64_t frame, std::uint64_t finished, std::uint32_t items,
                                           std::uint32_t kinds) {
    current_frame_ = frame;
    // The versions only finished frames bound, and the rings they pointed at, go (the newest of each stays).
    auto drop = [finished](std::vector<Version>& versions) {
        if (versions.size() < 2) return;
        Version newest = std::move(versions.back());
        versions.pop_back();
        std::erase_if(versions, [finished](const Version& v) { return v.last_frame <= finished; });
        versions.push_back(std::move(newest));
    };
    drop(frame_sets_);
    drop(global_sets_);
    std::erase_if(old_rings_, [finished](const OldRing& r) { return r.last_frame <= finished; });
    if (items > items_ || kinds > kinds_) {
        ATLAS_GPU_CHECK(make_ring(std::max(items, items_ + items_ / 2), std::max(kinds, kinds_)));
    }
    if (frame_dirty_ || frame_sets_.empty()) ATLAS_GPU_CHECK(write_frame_set());
    if (globals_dirty_ || global_sets_.empty()) ATLAS_GPU_CHECK(write_global_set());
    frame_sets_.back().last_frame = frame;
    global_sets_.back().last_frame = frame;
    if (frame_sets_.size() > max_versions || global_sets_.size() > max_versions) {
        return gpu::fail("more versions of the frame's descriptor sets than frames in flight need");
    }
    return {};
}

std::array<vk::DescriptorSet, 2> FrameLayout::sets() const noexcept {
    return {*frame_sets_.back().set, *global_sets_.back().set};
}

std::array<std::uint32_t, 3> FrameLayout::offsets(std::uint32_t slot) const noexcept {
    const auto at = static_cast<std::uint32_t>(slice_.bytes * slot);
    return {at, at, at};
}

std::span<std::byte> FrameLayout::ring(std::uint32_t slot) const noexcept {
    return ring_.bytes().subspan(static_cast<std::size_t>(slice_.bytes * slot), static_cast<std::size_t>(slice_.bytes));
}

void FrameLayout::flush(std::uint32_t slot) const {
    (void)slot;
    ring_.flush();
}

vk::DeviceSize FrameLayout::bytes() const noexcept {
    return ring_.size() + placeholder_buffer_.size() + placeholder_image_.bytes();
}

} // namespace atlas::frame
