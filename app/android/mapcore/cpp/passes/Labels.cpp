// The board's labels (passes/Labels.hpp).
#include "passes/Labels.hpp"

#include "base/log.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/spirv/text/sprite.hpp"
#include "gen/spirv/text/sprite_loc1.hpp"
#include "gpu/Descriptors.hpp"
#include "labels/Sprites.hpp"

#include <cstring>
#include <limits>

namespace atlas::passes {

namespace {

namespace sp = layouts::text_sprite;

constexpr std::size_t draw_bytes = sizeof(sp::SpriteDraw);
constexpr std::size_t slot_bytes = Labels::max_draws * draw_bytes;
constexpr float no_point = std::numeric_limits<float>::quiet_NaN();

} // namespace

Labels::~Labels() { Labels::release(); }

void Labels::update(const frame::FrameState& state) {
    const std::uint32_t slot = state.slot & 1U;
    drawn_count_.at(slot) = 0;
    draws_.clear();
    drawn_.clear();
    const labels::LabelSet* set = state.labels;
    if (set == nullptr || !state.view.has_value() || !state.ground_frame.has_value()) return;
    // Each label's points on the view's device pixels (LabelPainter's GroundView: the ground at street level).
    const view::View& view = *state.view;
    const view::GroundFrame& ground = *state.ground_frame;
    const auto sprites = set->sprites();
    candidates_.resize(sprites.size());
    points_.resize(sprites.size());
    for (std::uint32_t i = 0; i < sprites.size(); ++i) {
        const labels::LabelSprite& s = sprites[i];
        std::vector<labels::Point>& points = points_[i];
        points.clear();
        for (const auto& [lat, lon] : s.points) {
            const auto p = view.device_proj({ground.x(lat, lon), 0.0, ground.z(lat, lon)});
            points.push_back(p ? labels::Point{.x = static_cast<float>(p->x), .y = static_cast<float>(p->y)}
                               : labels::Point{.x = no_point, .y = no_point});
        }
        candidates_[i] = {.id = s.id, .label = i, .priority = s.priority, .measures = s.measures, .points = points};
    }
    // The chrome's keep-out shapes, dp to px as LabelPainter's caller scales them (in float).
    const auto density = static_cast<float>(state.camera.density);
    keep_out_.clear();
    for (const style::KeepOut& k : state.camera.keep_outs()) {
        keep_out_.push_back(
            {.l = k.left * density, .t = k.top * density, .r = k.right * density, .b = k.bottom * density});
    }
    const labels::Frame frame{.width = static_cast<float>(state.camera.width_px),
                              .height = static_cast<float>(state.camera.height_px),
                              .keep_out = keep_out_,
                              .markers = {}};
    const auto drawn = placement_.update(candidates_, frame, state.rest, state.seconds);
    drawn_.assign(drawn.begin(), drawn.end());
    for (const labels::Drawn& d : drawn_) {
        if (draws_.size() >= max_draws) break;
        const std::uint32_t label = candidates_[d.candidate].label;
        const labels::LabelSprite& s = sprites[label];
        const labels::SpriteAt at = labels::sprite_at(s, d);
        draws_.push_back(
            {.target = {at.x, at.y, static_cast<std::int32_t>(s.width), static_cast<std::int32_t>(s.height)},
             .source = {set->offsets()[label], s.width, 0, 0},
             .fade = {d.alpha, 0.0F, 0.0F, 0.0F}});
    }
    if (draw_buffer_ && !draws_.empty()) {
        std::memcpy(draw_buffer_.bytes().data() + (slot * slot_bytes), draws_.data(), draws_.size() * draw_bytes);
        draw_buffer_.flush();
        drawn_count_.at(slot) = static_cast<std::uint32_t>(draws_.size());
    }
    // D16's line, when what is placed changes, at most once a second.
    if (placement_.placed() != logged_placed_ && state.seconds - logged_at_ >= 1.0) {
        log::info("{}", placement_.line());
        logged_placed_ = placement_.placed();
        logged_at_ = state.seconds;
    }
}

gpu::Result<void> Labels::prepare(const frame::PassContext& context) {
    release();
    const vk::raii::Device& d = context.device.handle();
    const gpu::PipelineTarget& target = context.pass.composite_target();
    // Where the composite writes the output beside the scene's colour (the dynamic route at 1x), so do the labels.
    const bool loc1 = target.colour_count == 2;
    ATLAS_GPU_LET(module, loc1 ? frame::make_module(context, "text_sprite_loc1", spirv::text_sprite_loc1)
                               : frame::make_module(context, "text_sprite", spirv::text_sprite));
    module_ = std::move(module);
    const std::array bindings{
        gpu::Binding{.slot = sp::sprite_draws.binding,
                     .type = vk::DescriptorType::eStorageBuffer,
                     .stages = vk::ShaderStageFlagBits::eVertex | vk::ShaderStageFlagBits::eFragment}};
    ATLAS_GPU_LET(set_layout, gpu::make_set_layout(d, bindings));
    set_layout_ = std::move(set_layout);
    const std::array<std::span<const gpu::Binding>, 1> kinds{bindings};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, 2));
    pool_ = std::move(pool);
    for (vk::raii::DescriptorSet& set : sets_) {
        ATLAS_GPU_LET(made, gpu::allocate_set(d, pool_, set_layout_));
        set = std::move(made);
    }
    ATLAS_GPU_LET(draws, context.device.memory().buffer(2 * slot_bytes, vk::BufferUsageFlagBits::eStorageBuffer,
                                                        gpu::MemoryUse::upload));
    draw_buffer_ = std::move(draws);
    for (std::size_t s = 0; s < sets_.size(); ++s) {
        gpu::SetWriter(*sets_.at(s))
            .buffer(sp::sprite_draws.binding, vk::DescriptorType::eStorageBuffer, draw_buffer_.handle(), s * slot_bytes,
                    slot_bytes)
            .apply(d);
    }
    const std::array<vk::DescriptorSetLayout, 1> extra{*set_layout_};
    ATLAS_GPU_LET(layout, context.layout.make_layout(extra));
    layout_ = std::move(layout);
    gpu::GraphicsDesc desc{.label = {.name = "labels",
                                     .module = loc1 ? "text_sprite_loc1" : "text_sprite",
                                     .configuration = context.configuration},
                           .module = *module_,
                           .vertex = "sprite_vs",
                           .fragment = "sprite_fs",
                           .layout = *layout_,
                           .target = target};
    if (loc1) {
        desc.blends[0] = gpu::Blend::untouched(); // the scene's colour, which the composite subpass only reads
        desc.blends[1] = gpu::Blend::over();
    } else {
        desc.blends[0] = gpu::Blend::over();
    }
    ATLAS_GPU_LET(pipeline, frame::make_pipeline(context, desc, frame::Overdraw::none));
    pipeline_ = std::move(pipeline);
    return {};
}

void Labels::release() noexcept {
    pipeline_.clear();
    layout_.clear();
    for (vk::raii::DescriptorSet& set : sets_) set.clear();
    pool_.clear();
    set_layout_.clear();
    module_.clear();
    draw_buffer_ = gpu::Buffer{};
    drawn_count_ = {};
}

void Labels::record(const frame::Recording& recording, std::uint32_t phase) {
    (void)phase;
    const std::uint32_t slot = recording.state.slot & 1U;
    const std::uint32_t count = drawn_count_.at(slot);
    if (!*pipeline_ || count == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipeline_);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *sets_.at(slot), {});
    cmd.draw(6, count, 0, 0);
    recording.counters.draw("label sprites", 2ULL * count, count);
}

// ---- Set 1's sprites.

std::vector<std::shared_ptr<frame::GlobalProvider>> Labels::providers(const gpu::Device& device,
                                                                      const frame::Resources& resources) const {
    (void)resources;
    return {std::make_shared<SpriteGlobals>(device)};
}

void SpriteGlobals::update(const frame::FrameState& state, frame::Globals& globals) {
    ++updates_;
    std::erase_if(retired_, [&](const auto& r) { return r.second + retire_after <= updates_; });
    const labels::LabelSet* set = state.labels;
    const std::uint64_t revision = set != nullptr ? set->revision() : 0;
    if (set != set_ || revision != revision_) {
        if (buffer_) retired_.emplace_back(std::move(buffer_), updates_);
        buffer_ = gpu::Buffer{};
        texels_ = 0;
        set_ = set;
        revision_ = revision;
        if (set != nullptr && !set->texels().empty()) {
            auto made = device_.memory().buffer(set->texels().size_bytes(), vk::BufferUsageFlagBits::eStorageBuffer,
                                                gpu::MemoryUse::upload);
            if (!made) {
                log::error("labels: the sprites' buffer ({} texels): {}", set->texels().size(), made.error().text());
            } else {
                buffer_ = std::move(*made);
                std::memcpy(buffer_.bytes().data(), set->texels().data(), set->texels().size_bytes());
                buffer_.flush();
                texels_ = set->texels().size();
            }
        }
    }
    // Every frame (each frame builder drawing the world has its own set 1).
    if (buffer_) {
        globals.buffer(frame::Global::sprites, buffer_.handle());
    } else {
        globals.reset(frame::Global::sprites);
    }
}

} // namespace atlas::passes
