// The relief's light on the ground (passes/Relief.hpp).
#include "passes/Relief.hpp"

#include "base/log.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "fx/Relief.hpp"
#include "gen/spirv/ground/relief.hpp"
#include "gpu/Descriptors.hpp"
#include "view/Camera.hpp"

#include <algorithm>
#include <cstring>

namespace atlas::passes {

namespace {

namespace rs = layouts::ground_relief;

constexpr std::size_t quad_bytes = sizeof(rs::ReliefQuad);
constexpr std::size_t slot_bytes = Relief::max_quads * quad_bytes;
constexpr vk::DeviceSize raster_bytes = vk::DeviceSize{Relief::side} * Relief::side * sizeof(std::uint32_t);
constexpr vk::DeviceSize staging_bytes = Relief::max_uploads * raster_bytes;
constexpr vk::Format texel_format = vk::Format::eA2B10G10R10UnormPack32;

/// The stage that reads the array.
constexpr vk::PipelineStageFlags2 reading_stage = vk::PipelineStageFlagBits2::eFragmentShader;

} // namespace

Relief::~Relief() { Relief::release(); }

gpu::Result<void> Relief::prepare(const frame::PassContext& context) {
    release();
    // In the owner mode every scene pipeline writes its painter slot: the light has none of its own, and draws nothing.
    if (context.owners) return {};
    device_ = &context.device;
    const vk::raii::Device& d = context.device.handle();
    ATLAS_GPU_LET(module, frame::make_module(context, "ground_relief", spirv::ground_relief));
    module_ = std::move(module);
    const std::array bindings{gpu::Binding{.slot = rs::relief_quads.binding,
                                           .type = vk::DescriptorType::eStorageBuffer,
                                           .stages = vk::ShaderStageFlagBits::eVertex},
                              gpu::Binding{.slot = rs::relief_texels.binding,
                                           .type = vk::DescriptorType::eCombinedImageSampler,
                                           .stages = vk::ShaderStageFlagBits::eFragment}};
    ATLAS_GPU_LET(set_layout, gpu::make_set_layout(d, bindings));
    set_layout_ = std::move(set_layout);
    const std::array<std::span<const gpu::Binding>, 1> kinds{bindings};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, 2));
    pool_ = std::move(pool);
    for (Slot& s : slots_) {
        ATLAS_GPU_LET(made, gpu::allocate_set(d, pool_, set_layout_));
        s.set = std::move(made);
    }
    ATLAS_GPU_LET(storage, context.device.memory().buffer(2 * slot_bytes, vk::BufferUsageFlagBits::eStorageBuffer,
                                                          gpu::MemoryUse::upload));
    quads_ = std::move(storage);
    // between the samples of a raster, and never past its edge
    ATLAS_VK_LET(sampler,
                 d.createSampler({.magFilter = vk::Filter::eLinear,
                                  .minFilter = vk::Filter::eLinear,
                                  .mipmapMode = vk::SamplerMipmapMode::eNearest,
                                  .addressModeU = vk::SamplerAddressMode::eClampToEdge,
                                  .addressModeV = vk::SamplerAddressMode::eClampToEdge,
                                  .addressModeW = vk::SamplerAddressMode::eClampToEdge,
                                  .maxLod = 0.0F}),
                 "the relief's sampler");
    sampler_ = std::move(sampler);
    const std::array<vk::DescriptorSetLayout, 1> extra{*set_layout_};
    ATLAS_GPU_LET(layout, context.layout.make_layout(extra));
    layout_ = std::move(layout);
    // The ground times the light: DST_COLOR, SRC_ALPHA on the colour (relief.slang), the target's alpha left as it is;
    // half a slot above the ground's last painter depth, over everything the ground's passes drew, behind the items'
    // slots and every true depth; nothing written but colour.
    gpu::GraphicsDesc light{
        .label = {.name = "relief light", .module = "ground_relief", .configuration = context.configuration},
        .module = *module_,
        .vertex = "relief_vs",
        .fragment = "relief_fs",
        .layout = *layout_,
        .target = context.pass.scene_target(),
        .depth_test = true,
        .depth_write = false,
        .depth_op = vk::CompareOp::eGreaterOrEqual};
    light.blends[0] = {.enable = true,
                       .src_colour = vk::BlendFactor::eDstColor,
                       .dst_colour = vk::BlendFactor::eSrcAlpha,
                       .src_alpha = vk::BlendFactor::eZero,
                       .dst_alpha = vk::BlendFactor::eOne};
    if (light.target.colour_count == 2) light.blends[1] = gpu::Blend::untouched();
    ATLAS_GPU_LET(made, frame::make_pipeline(context, light, frame::Overdraw::translucent));
    pipeline_ = std::move(made);
    return {};
}

void Relief::release() noexcept {
    pipeline_.clear();
    layout_.clear();
    for (Slot& s : slots_) {
        s.set.clear();
        s.quads.clear();
    }
    pool_.clear();
    set_layout_.clear();
    sampler_.clear();
    view_.clear();
    array_ = gpu::Image{};
    staging_ = gpu::Buffer{};
    quads_ = gpu::Buffer{};
    module_.clear();
    layers_ = {};
    copies_.clear();
    written_ = false;
    refused_ = false;
    fading_ = false;
    device_ = nullptr;
}

bool Relief::make_array() {
    if (array_) return true;
    if (refused_ || device_ == nullptr) return false;
    const auto made = [this]() -> gpu::Result<void> {
        const vk::raii::Device& d = device_->handle();
        ATLAS_GPU_LET(image, device_->memory().image(
                                 {.imageType = vk::ImageType::e2D,
                                  .format = texel_format,
                                  .extent = {.width = side, .height = side, .depth = 1},
                                  .mipLevels = 1,
                                  .arrayLayers = layers,
                                  .samples = vk::SampleCountFlagBits::e1,
                                  .tiling = vk::ImageTiling::eOptimal,
                                  .usage = vk::ImageUsageFlagBits::eSampled | vk::ImageUsageFlagBits::eTransferDst,
                                  .initialLayout = vk::ImageLayout::eUndefined},
                                 gpu::MemoryUse::gpu));
        ATLAS_VK_LET(view,
                     d.createImageView({.image = image.handle(),
                                        .viewType = vk::ImageViewType::e2DArray,
                                        .format = texel_format,
                                        .subresourceRange = {.aspectMask = vk::ImageAspectFlagBits::eColor,
                                                             .levelCount = 1,
                                                             .layerCount = layers}}),
                     "the relief's array's view");
        ATLAS_GPU_LET(staging, device_->memory().buffer(2 * staging_bytes, vk::BufferUsageFlagBits::eTransferSrc,
                                                        gpu::MemoryUse::upload));
        array_ = std::move(image);
        view_ = std::move(view);
        staging_ = std::move(staging);
        // no frame has bound these sets yet: the pass draws nothing without the array
        for (std::size_t s = 0; s < slots_.size(); ++s) {
            gpu::SetWriter(*slots_.at(s).set)
                .buffer(rs::relief_quads.binding, vk::DescriptorType::eStorageBuffer, quads_.handle(), s * slot_bytes,
                        slot_bytes)
                .image(rs::relief_texels.binding, vk::DescriptorType::eCombinedImageSampler, *view_,
                       vk::ImageLayout::eShaderReadOnlyOptimal, *sampler_)
                .apply(d);
        }
        return {};
    }();
    if (!made) {
        log::error("vk relief: its array: {}", made.error().text());
        refused_ = true;
        return false;
    }
    written_ = false;
    return true;
}

std::optional<std::uint32_t> Relief::layer_of(std::uint64_t serial) const noexcept {
    for (std::uint32_t i = 0; i < layers; ++i) {
        if (layers_.at(i).serial == serial) return i;
    }
    return std::nullopt;
}

void Relief::update(const frame::FrameState& state) {
    const std::uint32_t slot = state.slot & 1U;
    Slot& s = slots_.at(slot);
    s.quads.clear();
    fading_ = false;
    // texels staged for a frame that never recorded them (the pass left out of it, FrameOptions::passes): their layers
    // hold nothing, and while it is left out nothing of its own keeps the frames coming
    const bool left_out = !copies_.empty();
    for (const Copy& c : copies_) layers_.at(c.layer) = {};
    copies_.clear();
    const scene::ReliefFrame* relief = state.relief;
    if (relief == nullptr || relief->draws.empty() || !state.view.has_value() || !*pipeline_) return;
    const fx::ReliefStyle& style = fx::relief_style();
    const double distance = state.placed ? state.placed->dist() : state.camera.distance;
    const double strength = fx::relief_strength(style, distance);
    if (strength <= 0.0) return;

    // the layers this frame's rasters hold already keep them: a raster that comes takes another's
    const std::uint64_t number = state.number;
    for (const scene::ReliefDraw& draw : relief->draws) {
        for (const scene::ReliefPart* p : {&draw.part, &draw.under}) {
            if (p->tile == nullptr) continue;
            if (const auto l = layer_of(p->tile->serial)) layers_.at(*l).drawn = number;
        }
    }
    const auto take_layer = [this, number]() -> std::optional<std::uint32_t> {
        std::optional<std::uint32_t> best;
        for (std::uint32_t i = 0; i < layers; ++i) {
            const Layer& l = layers_.at(i);
            if (l.serial == 0) return i;
            if (l.drawn == number) continue;
            if (!best || l.drawn < layers_.at(*best).drawn) best = i;
        }
        return best;
    };
    // the layer of a raster's texels, sent up where they are not there yet and the frame has a turn left
    const auto ready = [&](const scene::ReliefPart& part, bool fade) -> std::optional<std::uint32_t> {
        const scene::ReliefTile& tile = *part.tile;
        if (const auto l = layer_of(tile.serial)) return l;
        const std::uint32_t n = tile.bake.side;
        if (copies_.size() >= max_uploads || n > side || tile.bake.texels.size() != std::size_t{n} * n) return {};
        if (!make_array()) return {};
        const std::optional<std::uint32_t> l = take_layer();
        if (!l) return {};
        const vk::DeviceSize offset = (slot * staging_bytes) + (copies_.size() * raster_bytes);
        std::memcpy(staging_.bytes().data() + offset, tile.bake.texels.data(),
                    tile.bake.texels.size() * sizeof(std::uint32_t));
        copies_.push_back({.layer = *l, .side = n, .offset = offset});
        layers_.at(*l) = {.serial = tile.serial, .drawn = number, .since = fade ? state.seconds : -1.0e30};
        return l;
    };
    bool waiting = false;
    for (const scene::ReliefDraw& draw : relief->draws) {
        if (s.quads.size() >= max_quads) break;
        const scene::ReliefPart* part = &draw.part;
        // a tile lit where nothing lit it fades in; one that takes over from a coarser raster is its light at once
        std::optional<std::uint32_t> layer = ready(*part, draw.under.tile == nullptr);
        if (!layer) {
            waiting = true;
            if (draw.under.tile == nullptr) continue;
            part = &draw.under;
            layer = layer_of(part->tile->serial);
            if (!layer) continue;
        }
        const Layer& l = layers_.at(*layer);
        const double shown = style.fade > 0.0 ? std::clamp((state.seconds - l.since) / style.fade, 0.0, 1.0) : 1.0;
        if (shown < 1.0) fading_ = true;
        const shaders::TilePlace place = state.view->tile_place(draw.corner, 1.0, 1.0);
        // a raster's samples lie at its texels' centres: its edge samples half a texel inside the layer's edge
        const auto span = static_cast<float>(part->tile->bake.side - 1U);
        const float scale = span / static_cast<float>(side);
        const float half = 0.5F / static_cast<float>(side);
        s.quads.push_back(
            {.corner = {place.offset.x, place.offset.y, place.offset.z, static_cast<float>(shown)},
             .reach = {static_cast<float>(draw.east), static_cast<float>(draw.south), static_cast<float>(*layer), 0.0F},
             .part = {(part->x * scale) + half, (part->y * scale) + half, part->extent * scale, part->extent * scale}});
    }
    // a raster waiting its turn goes up in the next frame
    if (waiting) fading_ = true;
    if (left_out) fading_ = false;
    if (!copies_.empty()) staging_.flush();
    if (s.quads.empty()) return;
    const shaders::ReliefLight light = fx::relief_light(state.block.theme, style, state.sky_view, strength);
    static_assert(sizeof(light) == sizeof(s.push.light), "relief.slang's ReliefLight, as the twin and as the layout");
    std::memcpy(&s.push.light, &light, sizeof(light));
    const double far = relief->far;
    s.push.far = {static_cast<float>(far * (1.0 - far_fade)), static_cast<float>(1.0 / (far * far_fade)), 0.0F, 0.0F};
    s.push.at = {state.ground_slots, 0U, 0U, 0U};
    std::memcpy(quads_.bytes().data() + (slot * slot_bytes), s.quads.data(), s.quads.size() * quad_bytes);
    quads_.flush();
}

void Relief::record(const frame::Recording& recording, std::uint32_t phase) {
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    if (phase == upload_phase) {
        if (copies_.empty() || !array_) return;
        const vk::Image image = array_.handle();
        recording.frame.sync.barrier(
            cmd, gpu::ImageDependency{
                     .image = image,
                     .src_stage = reading_stage,
                     .src_access = written_ ? vk::AccessFlags2{vk::AccessFlagBits2::eShaderRead} : vk::AccessFlags2{},
                     .dst_stage = vk::PipelineStageFlagBits2::eTransfer,
                     .dst_access = vk::AccessFlagBits2::eTransferWrite,
                     .old_layout = written_ ? vk::ImageLayout::eShaderReadOnlyOptimal : vk::ImageLayout::eUndefined,
                     .new_layout = vk::ImageLayout::eTransferDstOptimal});
        std::array<vk::BufferImageCopy, max_uploads> regions{};
        std::uint32_t count = 0;
        for (const Copy& c : copies_) {
            regions.at(count++) =
                vk::BufferImageCopy{.bufferOffset = c.offset,
                                    .bufferRowLength = c.side,
                                    .bufferImageHeight = c.side,
                                    .imageSubresource = {.aspectMask = vk::ImageAspectFlagBits::eColor,
                                                         .mipLevel = 0,
                                                         .baseArrayLayer = c.layer,
                                                         .layerCount = 1},
                                    .imageExtent = {.width = c.side, .height = c.side, .depth = 1}};
        }
        cmd.copyBufferToImage(staging_.handle(), image, vk::ImageLayout::eTransferDstOptimal,
                              vk::ArrayProxy<const vk::BufferImageCopy>(count, regions.data()));
        recording.frame.sync.barrier(cmd, gpu::ImageDependency{.image = image,
                                                               .src_stage = vk::PipelineStageFlagBits2::eTransfer,
                                                               .src_access = vk::AccessFlagBits2::eTransferWrite,
                                                               .dst_stage = reading_stage,
                                                               .dst_access = vk::AccessFlagBits2::eShaderRead,
                                                               .old_layout = vk::ImageLayout::eTransferDstOptimal,
                                                               .new_layout = vk::ImageLayout::eShaderReadOnlyOptimal});
        written_ = true;
        uploads_ += copies_.size();
        copies_.clear();
        return;
    }
    const Slot& s = slots_.at(recording.state.slot & 1U);
    // texels still staged: the upload phase did not record (the frame's order holds it before this one)
    if (s.quads.empty() || !*pipeline_ || !array_ || !written_ || !copies_.empty()) return;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipeline_);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *s.set, {});
    cmd.pushConstants<rs::ReliefPush>(*layout_, frame::push_stages, 0, s.push);
    const auto count = static_cast<std::uint32_t>(s.quads.size());
    cmd.draw(6, count, 0, 0);
    recording.counters.draw("relief light", std::uint64_t{count} * 2, count);
}

} // namespace atlas::passes
