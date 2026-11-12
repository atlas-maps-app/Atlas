// The board's labels (passes/Labels.hpp).
#include "passes/Labels.hpp"

#include "base/log.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "frame/MarkerBoxes.hpp"
#include "gen/spirv/text/sprite.hpp"
#include "gen/spirv/text/sprite_loc1.hpp"
#include "gpu/Descriptors.hpp"
#include "labels/Names.hpp"
#include "labels/Sprites.hpp"
#include "scene/WorldLook.hpp"
#include "scene/WorldTiles.hpp"

#include <algorithm>
#include <bit>
#include <cstring>
#include <limits>
#include <optional>
#include <string>

namespace atlas::passes {

namespace {

namespace sp = layouts::text_sprite;

constexpr std::size_t draw_bytes = sizeof(sp::SpriteDraw);
constexpr std::size_t slot_bytes = Labels::max_draws * draw_bytes;
constexpr float no_point = std::numeric_limits<float>::quiet_NaN();
/// Updates a names' buffer let go of is kept: two frame builders' frames in flight, and as many again.
constexpr std::uint64_t retire_after = 4ULL * gpu::Frames::in_flight;

} // namespace

Labels::~Labels() { Labels::release(); }

void Labels::pick_names(const frame::FrameState& state, const view::View& view, const view::GroundFrame& frame,
                        std::size_t first) {
    picked_.clear();
    name_sprites_.clear();
    name_waters_.clear();
    name_points_.clear();
    names_least_ = 0.0F;
    labels::NameStore* store = state.names;
    if (store == nullptr) return;
    // The bent ground the names stand on: the world's this frame (beyond 18 km, and under the cover where it is drawn
    // there); without it only a test's names.
    const scene::WorldBend bend = state.world != nullptr ? state.world->bend : scene::world_bend(view, frame);
    const view::GroundFrame& ground = state.world != nullptr ? state.world->frame : frame;
    const auto width = static_cast<double>(state.camera.width_px);
    const auto height = static_cast<double>(state.camera.height_px);
    const auto point = [&](double lat, double lon) {
        return scene::name_point(bend, ground, view, lat, lon, name_least_facing);
    };
    const scene::WorldNames::InView in_view = [&](double lat, double lon) {
        const std::optional<view::Vec2> p = point(lat, lon);
        return p && p->x >= 0.0 && p->y >= 0.0 && p->x <= width && p->y <= height;
    };
    const std::array<double, 7> camera{state.camera.latitude,
                                       state.camera.longitude,
                                       state.camera.bearing,
                                       state.camera.pitch,
                                       state.camera.distance,
                                       width,
                                       height};
    const bool changed = camera != picked_camera_;
    picked_camera_ = camera;
    // a dp of the screen at the camera's target, metres: the waters are ranked by their size on the screen
    const double focal = view.camera().focal();
    const scene::NameScale scale{.metres_a_dp = focal > 0.0 ? state.camera.distance / focal : 0.0,
                                 .lat = state.camera.latitude,
                                 .lon = state.camera.longitude};
    const auto picked = world_names_.update(state.world, state.extra_names, state.camera.distance, in_view,
                                            state.seconds, state.rest, changed, scale);
    picked_.assign(picked.begin(), picked.end());
    // What a lit place's light follows this frame (the world pass's look of it): the ladder's weights at the camera's
    // distance and the point the sun is overhead of. Its name stands clear of it (scene::name_rise).
    const tiles::WorldLadder* ladder =
        state.world != nullptr && state.world->style != nullptr && state.world->style->ladder
            ? &*state.world->style->ladder
            : nullptr;
    scene::LadderWeights weights;
    scene::GeoPoint sun;
    if (ladder != nullptr) {
        weights = scene::ladder_weights(*ladder, state.camera.distance);
        sun = scene::sub_solar(state.theme.sun_azimuth, state.theme.sun_elevation,
                               static_cast<double>(state.theme.place_latitude),
                               static_cast<double>(state.theme.place_longitude));
    }
    // Those with a sprite are candidates; the others, by rank, are what the app is asked for.
    std::vector<labels::NameWant> wanted;
    name_points_.reserve(picked_.size());
    for (const scene::PickedName& name : picked_) {
        const labels::NameSprite* sprite = store->find(name.id, state.seconds);
        if (sprite == nullptr) {
            if (!name.leaving) {
                wanted.push_back(
                    {.id = name.id, .style = static_cast<std::uint8_t>(name.style), .text = std::string(name.text)});
            }
            continue;
        }
        if (sprite->skipped) continue;
        const std::optional<view::Vec2> p = point(name.lat, name.lon);
        name_points_.push_back(p ? labels::Point{.x = static_cast<float>(p->x), .y = static_cast<float>(p->y)}
                                 : labels::Point{.x = no_point, .y = no_point});
        const double rise =
            name.lit && ladder != nullptr ? scene::name_rise(*ladder, weights, sun, name.lat, name.lon) : 0.0;
        candidates_.push_back(
            {.id = name.id,
             .label = static_cast<std::uint32_t>(first + name_sprites_.size()),
             .priority = name.priority,
             .measures = sprite->sprite.measures,
             .points = std::span<const labels::Point>(&name_points_.back(), 1),
             .hidden = name.leaving,
             .rise = static_cast<float>(rise),
             .mark = rise > 0.0 ? static_cast<float>(scene::light_halo_dp(*ladder, name.people) * state.camera.density)
                                : 0.0F});
        name_sprites_.push_back(sprite);
        name_waters_.push_back(name.style == scene::NameStyle::water ? 1 : 0);
        // the readable floor over the look's own size: the least a name of it is drawn at
        names_least_ = std::max(
            names_least_, static_cast<float>(static_cast<double>(store->floor_dp()) / scene::name_size_dp(name.style)));
    }
    store->want(std::move(wanted));
}

bool Labels::fresh_names_buffer(std::uint32_t texels) {
    if (device_ == nullptr) return false;
    auto made = device_->memory().buffer(std::size_t{texels} * sizeof(std::uint32_t),
                                         vk::BufferUsageFlagBits::eStorageBuffer, gpu::MemoryUse::upload);
    if (!made) {
        log::error("labels: the names' buffer ({} texels): {}", texels, made.error().text());
        return false;
    }
    if (arena_) retired_.emplace_back(std::move(arena_), updates_);
    arena_ = std::move(*made);
    arena_capacity_ = texels;
    arena_used_ = 0;
    arena_places_.clear();
    ++arena_generation_;
    return true;
}

const std::array<std::uint32_t, 2>* Labels::name_offsets(const labels::NameSprite& sprite) {
    if (const auto at = arena_places_.find(sprite.serial); at != arena_places_.end()) return &at->second;
    const labels::LabelSprite& s = sprite.sprite;
    const std::size_t need = s.own.pixels.size() + s.large.pixels.size();
    if (!arena_ || need > std::size_t{arena_capacity_} - arena_used_) return nullptr;
    std::array<std::uint32_t, 2> places{arena_used_, 0};
    std::byte* to = arena_.bytes().data() + (std::size_t{arena_used_} * sizeof(std::uint32_t));
    std::memcpy(to, s.own.pixels.data(), s.own.pixels.size() * sizeof(std::uint32_t));
    arena_used_ += static_cast<std::uint32_t>(s.own.pixels.size());
    if (!s.large.empty()) {
        places[1] = arena_used_;
        std::memcpy(to + (s.own.pixels.size() * sizeof(std::uint32_t)), s.large.pixels.data(),
                    s.large.pixels.size() * sizeof(std::uint32_t));
        arena_used_ += static_cast<std::uint32_t>(s.large.pixels.size());
    }
    arena_dirty_ = true;
    return &arena_places_.emplace(sprite.serial, places).first->second;
}

void Labels::update(const frame::FrameState& state) {
    const std::uint32_t slot = state.slot & 1U;
    drawn_count_.at(slot) = 0;
    draws_.clear();
    drawn_.clear();
    names_drawn_ = 0;
    waters_drawn_ = 0;
    fading_ = false; // a frame that places no labels leaves none fading
    ++updates_;
    std::erase_if(retired_, [&](const auto& r) { return r.second + retire_after <= updates_; });
    const labels::LabelSet* set = state.labels;
    // The frame the labels' places are in: the packs', or where no pack is drawn (the world alone) the world's.
    const view::GroundFrame* frame_drawn = state.ground_frame.has_value()
                                               ? &*state.ground_frame
                                               : (state.world != nullptr ? &state.world->frame : nullptr);
    if ((set == nullptr && state.names == nullptr) || !state.view.has_value() || frame_drawn == nullptr ||
        state.camera.leaves_out(style::camera_part::labels)) {
        return;
    }
    // Each label's points on the view's device pixels (LabelPainter's GroundView: the ground at street level).
    const view::View& view = *state.view;
    const view::GroundFrame& ground = *frame_drawn;
    const auto sprites = set != nullptr ? set->sprites() : std::span<const labels::LabelSprite>();
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
    // The world's names after them (theirs are the lower priorities).
    pick_names(state, view, ground, sprites.size());
    // The chrome's keep-out shapes, dp to px as LabelPainter's caller scales them (in float).
    const auto density = static_cast<float>(state.camera.density);
    keep_out_.clear();
    for (const style::KeepOut& k : state.camera.keep_outs()) {
        keep_out_.push_back(
            {.l = k.left * density, .t = k.top * density, .r = k.right * density, .b = k.bottom * density});
    }
    const float names_large = state.names != nullptr ? state.names->large_scale() : 0.0F;
    const labels::Frame frame{.width = static_cast<float>(state.camera.width_px),
                              .height = static_cast<float>(state.camera.height_px),
                              .keep_out = keep_out_,
                              // the markers' boxes this frame (passes/Markers, which updates before the labels)
                              .markers = state.marker_boxes != nullptr
                                             ? std::span<const labels::Box>(state.marker_boxes->boxes)
                                             : std::span<const labels::Box>(),
                              // the readable floor: the set's, and the names' where the frame has any
                              .least = std::max(set != nullptr ? set->least() : 0.0F, names_least_)};
    // The map's scale: the reciprocal of the camera's distance (a tilted view keeps the zoom's size).
    const double scale = state.camera.distance > 0.0 ? 1.0 / state.camera.distance : 0.0;
    const auto drawn = placement_.update(candidates_, frame, scale, state.seconds);
    fading_ = placement_.fading();
    drawn_.assign(drawn.begin(), drawn.end());
    // The sprites to draw: a board's label from the set's texels (set 1), a name from the names' buffer. Where that
    // has no room for a name's texels, a buffer for this frame's names (twice their texels, so the next ones have
    // room) takes its place and the frame's sprites are laid out once more.
    for (int pass = 0; pass < 2; ++pass) {
        draws_.clear();
        names_drawn_ = 0;
        waters_drawn_ = 0;
        std::uint64_t need = 0;
        bool full = false;
        for (const labels::Drawn& d : drawn_) {
            if (draws_.size() >= max_draws) break;
            const std::uint32_t label = candidates_[d.candidate].label;
            if (label < sprites.size()) {
                const labels::LabelSprite& s = sprites[label];
                const auto [image, at] = labels::image_at(s, set->large_scale(), d);
                const labels::SpriteImage& from = image == 0 ? s.own : s.large;
                draws_.push_back({.target = {at.x, at.y, at.width, at.height},
                                  .source = {set->offsets()[label].at(image), from.width, from.height, 0},
                                  .fade = {d.alpha, at.scale, 0.0F, 0.0F}});
                continue;
            }
            const labels::NameSprite& name = *name_sprites_[label - sprites.size()];
            const labels::LabelSprite& s = name.sprite;
            need += s.own.pixels.size() + s.large.pixels.size();
            const std::array<std::uint32_t, 2>* places = name_offsets(name);
            if (places == nullptr) {
                full = true;
                continue;
            }
            const auto [image, at] = labels::image_at(s, names_large, d);
            const labels::SpriteImage& from = image == 0 ? s.own : s.large;
            draws_.push_back({.target = {at.x, at.y, at.width, at.height},
                              .source = {places->at(image), from.width, from.height, 1},
                              .fade = {d.alpha, at.scale, 0.0F, 0.0F}});
            ++names_drawn_;
            waters_drawn_ += name_waters_[label - sprites.size()];
        }
        if (!full || pass == 1) break;
        const std::uint64_t texels =
            std::clamp<std::uint64_t>(std::bit_ceil(2 * need), first_name_texels, max_name_texels);
        if (!fresh_names_buffer(static_cast<std::uint32_t>(texels))) break;
    }
    if (arena_ && arena_dirty_) {
        arena_.flush();
        arena_dirty_ = false;
    }
    if (arena_ && set_generation_.at(slot) != arena_generation_ && *sets_.at(slot) && device_ != nullptr) {
        gpu::SetWriter(*sets_.at(slot))
            .buffer(sp::name_texels.binding, vk::DescriptorType::eStorageBuffer, arena_.handle(), 0,
                    std::size_t{arena_capacity_} * sizeof(std::uint32_t))
            .apply(device_->handle());
        set_generation_.at(slot) = arena_generation_;
    }
    if (draw_buffer_ && !draws_.empty()) {
        std::memcpy(draw_buffer_.bytes().data() + (slot * slot_bytes), draws_.data(), draws_.size() * draw_bytes);
        draw_buffer_.flush();
        drawn_count_.at(slot) = static_cast<std::uint32_t>(draws_.size());
    }
    // The names' line, when what the frame picks or draws of them changes: at most once a second while the camera
    // moves, and at rest at once (its frames are few, and the last of them says what stands).
    const auto picked_names =
        static_cast<std::uint32_t>(std::ranges::count(picked_, false, &scene::PickedName::leaving));
    if ((picked_names != logged_names_.at(0) || names_drawn_ != logged_names_.at(1)) &&
        (state.rest || state.seconds - names_logged_at_ >= 1.0)) {
        // (the waters among them said where the frame picks any: the line is the places' alone otherwise)
        const auto picked_waters = static_cast<std::uint32_t>(std::ranges::count_if(
            picked_, [](const auto& n) { return !n.leaving && n.style == scene::NameStyle::water; }));
        if (picked_waters > 0) {
            log::info("vk names: picked {} with sprites {} drawn {}, waters picked {} drawn {}", picked_names,
                      name_sprites_.size(), names_drawn_, picked_waters, waters_drawn_);
        } else {
            log::info("vk names: picked {} with sprites {} drawn {}", picked_names, name_sprites_.size(), names_drawn_);
        }
        logged_names_ = {picked_names, names_drawn_};
        names_logged_at_ = state.seconds;
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
                     .stages = vk::ShaderStageFlagBits::eVertex | vk::ShaderStageFlagBits::eFragment},
        gpu::Binding{.slot = sp::name_texels.binding,
                     .type = vk::DescriptorType::eStorageBuffer,
                     .stages = vk::ShaderStageFlagBits::eFragment}};
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
    device_ = &context.device;
    if (!fresh_names_buffer(first_name_texels)) return gpu::fail("the names' buffer");
    for (std::size_t s = 0; s < sets_.size(); ++s) {
        gpu::SetWriter(*sets_.at(s))
            .buffer(sp::sprite_draws.binding, vk::DescriptorType::eStorageBuffer, draw_buffer_.handle(), s * slot_bytes,
                    slot_bytes)
            .buffer(sp::name_texels.binding, vk::DescriptorType::eStorageBuffer, arena_.handle(), 0,
                    std::size_t{arena_capacity_} * sizeof(std::uint32_t))
            .apply(d);
        set_generation_.at(s) = arena_generation_;
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
    arena_ = gpu::Buffer{};
    retired_.clear();
    arena_places_.clear();
    arena_capacity_ = 0;
    arena_used_ = 0;
    set_generation_ = {};
    device_ = nullptr;
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
