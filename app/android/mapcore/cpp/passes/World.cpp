// The world pass (passes/World.hpp).
#include "passes/World.hpp"

#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/layouts.hpp"
#include "gen/spirv/world/world.hpp"
#include "geometry/Ground.hpp"
#include "gpu/Descriptors.hpp"
#include "scene/WorldTiles.hpp"
#include "style/Theme.hpp"
#include "tiles/Style.hpp"
#include "view/Camera.hpp"
#include "view/Ladder.hpp"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <numbers>
#include <utility>

namespace atlas::passes {

namespace {

namespace wl = layouts::world_world;
namespace wp = scene::world_palette;

/// A frame slot's palette: the moment's entries, then the day's (world.slang's PALETTE_DAY).
constexpr std::size_t palette_bytes = 2 * std::size_t{wp::size} * sizeof(std::uint32_t);

/// A frame slot's look block's place in the looks' buffer: a stride every device's uniform offsets align to.
constexpr std::size_t look_stride = 1024;
static_assert(sizeof(shaders::WorldLookBlock) <= look_stride);

/// A theme colour as the palette's word, opaque.
[[nodiscard]] constexpr std::uint32_t word_of(const style::Rgb8& c) noexcept {
    return geometry::Rgba{.r = c.r, .g = c.g, .b = c.b, .a = 255}.packed();
}

/// A style colour by day or at night as the palette's word.
[[nodiscard]] constexpr std::uint32_t word_of(const tiles::DayNight& c, bool night) noexcept {
    return geometry::rgba(night ? c.night : c.day).packed();
}

/// The lawns, drawn as their areas are (geometry/Ground.cpp's lawn_kinds).
constexpr std::array<std::string_view, 3> lawn_kinds = {"park", "garden", "grass"};

/// The painter depth of the world: between the sky's painter slots (0 to 2, depths 1 to 3 times the scale) and the
/// ground's first (4 times), as painter.slang's scale is (2^-60, or 2^-20 on a 24-bit depth attachment).
[[nodiscard]] float world_depth(std::uint32_t camera_flags) noexcept {
    const float scale = (camera_flags & shaders::CAMERA_DEPTH24) != 0U ? 0x1p-20F : 0x1p-60F;
    return 3.5F * scale;
}

/// The depth of a tile fading in, `lift` sixteenths of the scale above the world's (1 to 7: its level's rank among the
/// levels fading, coarse to fine), so it lies over its ancestors' cells, a finer fading tile over a coarser one, and
/// all under the ground's first slot; a sixteenth is the 24-bit attachment's step at the scale 2^-20.
[[nodiscard]] float fading_depth(std::uint32_t camera_flags, std::uint32_t lift) noexcept {
    const float scale = (camera_flags & shaders::CAMERA_DEPTH24) != 0U ? 0x1p-20F : 0x1p-60F;
    return (3.5F + (static_cast<float>(lift) / 16.0F)) * scale;
}

/// The most levels whose fading tiles lie at depths of their own (a finer one shares the finest's).
constexpr std::uint32_t fading_lifts = 7;

/// Space's depth: over the sky's slots and under the world's, so it draws only where nothing of the world does.
[[nodiscard]] float space_depth(std::uint32_t camera_flags) noexcept {
    const float scale = (camera_flags & shaders::CAMERA_DEPTH24) != 0U ? 0x1p-20F : 0x1p-60F;
    return 3.25F * scale;
}

/// The depth of what lies over the world (the glass, the lights): the topmost fading tile's, under the ground's first
/// slot, tested without a write.
[[nodiscard]] float over_depth(std::uint32_t camera_flags) noexcept { return fading_depth(camera_flags, fading_lifts); }

/// The palette of a ground, a water and a road (the palette's words) and the world's style by day or at night.
[[nodiscard]] std::vector<std::uint32_t> palette_from(std::uint32_t ground, std::uint32_t water, std::uint32_t road,
                                                      bool night, const tiles::Style* style) {
    std::vector<std::uint32_t> out(wp::size, ground);
    out[wp::water] = water;
    out[wp::road] = road;
    if (style == nullptr) return out;
    out[wp::rail] = word_of(style->rails, night);
    out[wp::asphalt] = word_of(style->asphalt, night);
    out[wp::runway] = word_of(style->runway_pavement, night);
    // the land cover as the ground's land is: LAND's colour, five times darker at night; a lawn AREA's, five and a
    // half times
    for (std::size_t k = 0; k < style->land_kinds.size() && k < style->land_colours.size(); ++k) {
        if (k + wp::kinds >= wp::size) break;
        const std::string_view kind = style->land_kinds[k];
        const bool lawn = std::ranges::find(lawn_kinds, kind) != lawn_kinds.end();
        const geometry::Rgba c = geometry::rgba(lawn ? style->area_colour(kind) : style->land_colours[k]);
        out[k + wp::kinds] = (night ? geometry::darken(c, lawn ? 0.18 : 0.2) : c).packed();
    }
    // the coast's line, the ladder's (the world pass draws it by the ladder's weight alone)
    if (style->ladder) out[wp::coast] = geometry::rgba(style->ladder->coast.colour).packed();
    // the ice (glaciers, ice shelves, the south pole's cap): the ladder's, at night darkened as the land cover is;
    // without the ladder the style's glacier where it has one (else the land's)
    if (style->ladder) {
        const geometry::Rgba c = geometry::rgba(style->ladder->ice);
        out[wp::ice] = (night ? geometry::darken(c, 0.2) : c).packed();
    } else if (const std::optional<std::uint16_t> g = style->land_kind("glacier");
               g && std::cmp_less(*g + wp::kinds, wp::size)) {
        out[wp::ice] = out[*g + wp::kinds];
    }
    return out;
}

/// World::paint_for_tests's colour (0: none).
[[nodiscard]] std::atomic<std::uint32_t>& test_paint() noexcept {
    static std::atomic<std::uint32_t> colour{0};
    return colour;
}

/// World::ladder_for_tests's switch.
[[nodiscard]] std::atomic<bool>& test_ladder() noexcept {
    static std::atomic<bool> on{true};
    return on;
}

} // namespace

void World::paint_for_tests(std::uint32_t colour) noexcept { test_paint().store(colour, std::memory_order_relaxed); }

void World::ladder_for_tests(bool on) noexcept { test_ladder().store(on, std::memory_order_relaxed); }

World::~World() { World::release(); }

std::vector<std::uint32_t> World::palette_of(const style::ThemeBlock& theme, bool night, const tiles::Style* style) {
    return palette_from(word_of(theme.ground), word_of(theme.water), word_of(theme.road), night, style);
}

double World::day_share(const style::ThemeBlock& theme) noexcept {
    if (theme.has(style::theme_flag::night)) return 0.0;
    const double t =
        std::clamp((theme.sun_elevation - style::night_below) / (day_share_whole - style::night_below), 0.0, 1.0);
    return t * t * (3.0 - (2.0 * t));
}

std::vector<std::uint32_t> World::day_palette_of(const style::ThemeBlock& theme, const tiles::Style* style) {
    const bool night = theme.has(style::theme_flag::night);
    if (style == nullptr || !style->ladder) return palette_of(theme, night, style);
    const double share = day_share(theme);
    if (share >= 1.0) return palette_of(theme, false, style);
    // a word between the ladder's day colour and the moment's own, a channel at a time, rounded
    const auto between = [share](std::uint32_t ladder, const style::Rgb8& own) {
        const geometry::Rgba a = geometry::rgba(ladder);
        const auto channel = [share](std::uint8_t from, std::uint8_t to) {
            const double v =
                static_cast<double>(from) + ((static_cast<double>(to) - static_cast<double>(from)) * share);
            return static_cast<std::uint8_t>(std::floor(v + 0.5));
        };
        return geometry::Rgba{.r = channel(a.r, own.r), .g = channel(a.g, own.g), .b = channel(a.b, own.b), .a = 255}
            .packed();
    };
    const tiles::WorldLadder& l = *style->ladder;
    return palette_from(between(l.day_ground, theme.ground), between(l.day_water, theme.water),
                        between(l.day_road, theme.road), false, style);
}

void World::update(const frame::FrameState& state) {
    Slot& s = slots_.at(state.slot & 1U);
    s.drawing = false;
    draws_.clear();
    const scene::WorldFrame* world = state.world;
    space_ = false;
    glass_ = false;
    look_ = {};
    if (world == nullptr || world->draws.empty() || !state.view.has_value() || !*fills_ || !palette_buffer_ ||
        !look_buffer_) {
        return;
    }
    const bool night = state.theme.has(style::theme_flag::night);
    palette_ = palette_of(state.theme, night, world->style);
    const std::vector<std::uint32_t> day = day_palette_of(state.theme, world->style);
    palette_.insert(palette_.end(), day.begin(), day.end());
    const std::uint32_t paint = test_paint().load(std::memory_order_relaxed);
    if (paint != 0) std::ranges::fill(palette_, paint);
    std::memcpy(palette_buffer_.bytes().data() + ((state.slot & 1U) * palette_bytes), palette_.data(), palette_bytes);
    palette_buffer_.flush();
    if (s.bound != world->buffer || s.bound_bytes != world->buffer_bytes) {
        // the slot's last frame has finished with its set (the frame slots' fences); the world's heap grown, a new
        // buffer and its bytes
        gpu::SetWriter(*s.set)
            .buffer(wl::world_words.binding, vk::DescriptorType::eStorageBuffer, world->buffer, 0, world->buffer_bytes)
            .apply(device_->handle());
        s.bound = world->buffer;
        s.bound_bytes = world->buffer_bytes;
    }
    const view::Camera& camera = state.view->camera();
    const view::Vec3& eye = camera.eye();
    const view::GroundFrame& f = world->frame;
    const scene::WorldBend& bend = world->bend;
    // the frame's look by the style ladder (none where the world's style has no ladder, or a test paints the world)
    if (world->style != nullptr && world->style->ladder && paint == 0 &&
        test_ladder().load(std::memory_order_relaxed)) {
        const view::Device& device = state.view->device();
        look_ = scene::world_look(*world->style->ladder, camera.spec().dist, state.theme, bend, eye, device.density,
                                  static_cast<double>(device.width_px), static_cast<double>(device.height_px));
        space_ = look_.weights.space > 0.0;
        glass_ = look_.weights.ring > 0.0;
    }
    std::memcpy(look_buffer_.bytes().data() + ((state.slot & 1U) * look_stride), &look_.block, sizeof(look_.block));
    look_buffer_.flush();
    constexpr double radian = std::numbers::pi / 180.0;
    const std::array<float, 4> sphere{static_cast<float>(bend.sin_lat), static_cast<float>(bend.cos_lat),
                                      static_cast<float>(bend.sphere), static_cast<float>(view::earth_radius)};
    const std::array<float, 4> target{static_cast<float>(bend.target.x - eye.x),
                                      static_cast<float>(bend.target.y - eye.y),
                                      static_cast<float>(bend.target.z - eye.z), 0.0F};
    std::uint32_t drawn = 0; // the tiles drawn so far: each draw's tile (a tile in parts has a draw for each)
    for (const scene::WorldDraw& d : world->draws) {
        const scene::WorldTile& t = *d.tile;
        if (t.parts.empty() || (t.triangles == 0 && t.segments == 0)) continue;
        constexpr double grid = 1e-7;
        // a cap's box is the frame's and the plain land's its draw's; a tile's longitudes within 180° of the target's
        const scene::DegreeBox box = t.blank      ? d.box
                                     : t.cap != 0 ? scene::world_cap_box(t.cap, bend.lon)
                                                  : scene::DegreeBox{.north = t.box[2] * grid,
                                                                     .south = t.box[0] * grid,
                                                                     .west = t.box[1] * grid,
                                                                     .east = t.box[3] * grid};
        const double shift = t.cap != 0 ? 0.0 : scene::wrap_shift(bend, (box.west + box.east) / 2.0);
        const double west = box.west + shift;
        const double east = box.east + shift;
        const double x0 = f.x(box.north, west);
        const double z0 = f.z(box.north, west);
        Draw out;
        out.origin = {static_cast<float>(x0 - eye.x), static_cast<float>(-eye.y), static_cast<float>(z0 - eye.z), 0.0F};
        out.scale = {static_cast<float>((f.x(box.north, east) - x0) / 65'535.0),
                     static_cast<float>((f.z(box.south, west) - z0) / 65'535.0), d.fade, d.fade < 1.0F ? 1.0F : 0.0F};
        out.globe = {static_cast<float>((box.north - bend.lat) * radian),
                     static_cast<float>(-(box.north - box.south) * radian / 65'535.0),
                     static_cast<float>((west - bend.lon) * radian),
                     static_cast<float>((box.east - box.west) * radian / 65'535.0)};
        out.sphere = sphere;
        out.target = target;
        out.fade = d.fade;
        out.level = t.key.z;
        out.light_cells = static_cast<std::uint32_t>(d.light_cells & 0xFFFFU);
        out.tile = drawn++;
        // a draw for each of its parts, each laid out as a tile (scene::WorldPart)
        for (const scene::WorldPart& p : t.parts) {
            out.at = {p.range.offset / 4U, static_cast<std::uint32_t>(d.cells & 0xFFFFU), 3U * p.triangles,
                      4U * p.triangles};
            out.triangles = p.triangles;
            out.segments = p.segments;
            out.coast = p.coast;
            out.lights = p.lights;
            draws_.push_back(out);
        }
    }
    // the fading tiles' depths: their levels' ranks, coarse to fine
    std::array<bool, 32> fading{};
    for (const Draw& d : draws_) {
        if (d.fade < 1.0F && d.level < fading.size()) fading.at(d.level) = true;
    }
    std::array<std::uint32_t, 32> lift{};
    std::uint32_t rank = 0;
    for (std::size_t z = 0; z < fading.size(); ++z) {
        if (fading.at(z)) rank = std::min(rank + 1, fading_lifts);
        lift.at(z) = rank;
    }
    for (Draw& d : draws_) {
        if (d.fade < 1.0F) d.lift = lift.at(std::min<std::size_t>(d.level, lift.size() - 1));
    }
    s.drawing = !draws_.empty();
}

gpu::Result<void> World::prepare(const frame::PassContext& context) {
    release();
    device_ = &context.device;
    const vk::raii::Device& d = context.device.handle();
    ATLAS_GPU_LET(module, frame::make_module(context, "world_world", spirv::world_world));
    module_ = std::move(module);
    const std::array bindings{
        gpu::Binding{.slot = wl::world_words.binding,
                     .type = vk::DescriptorType::eStorageBuffer,
                     .stages = vk::ShaderStageFlagBits::eVertex},
        gpu::Binding{.slot = wl::world_palette.binding,
                     .type = vk::DescriptorType::eStorageBuffer,
                     .stages = vk::ShaderStageFlagBits::eVertex | vk::ShaderStageFlagBits::eFragment},
        gpu::Binding{.slot = wl::look.binding,
                     .type = vk::DescriptorType::eUniformBuffer,
                     .stages = vk::ShaderStageFlagBits::eVertex | vk::ShaderStageFlagBits::eFragment}};
    ATLAS_GPU_LET(set_layout, gpu::make_set_layout(d, bindings));
    set_layout_ = std::move(set_layout);
    const std::array<std::span<const gpu::Binding>, 1> kinds{bindings};
    ATLAS_GPU_LET(pool, gpu::make_pool(d, kinds, 2));
    pool_ = std::move(pool);
    for (Slot& s : slots_) {
        ATLAS_GPU_LET(made, gpu::allocate_set(d, pool_, set_layout_));
        s.set = std::move(made);
    }
    ATLAS_GPU_LET(palette, context.device.memory().buffer(2 * palette_bytes, vk::BufferUsageFlagBits::eStorageBuffer,
                                                          gpu::MemoryUse::upload));
    palette_buffer_ = std::move(palette);
    ATLAS_GPU_LET(looks, context.device.memory().buffer(2 * look_stride, vk::BufferUsageFlagBits::eUniformBuffer,
                                                        gpu::MemoryUse::upload));
    look_buffer_ = std::move(looks);
    for (std::size_t s = 0; s < slots_.size(); ++s) {
        gpu::SetWriter(*slots_.at(s).set)
            .buffer(wl::look.binding, vk::DescriptorType::eUniformBuffer, look_buffer_.handle(), s * look_stride,
                    sizeof(shaders::WorldLookBlock))
            .buffer(wl::world_palette.binding, vk::DescriptorType::eStorageBuffer, palette_buffer_.handle(),
                    s * palette_bytes, palette_bytes)
            // the words' binding until a world draws: the palette's buffer, never read
            .buffer(wl::world_words.binding, vk::DescriptorType::eStorageBuffer, palette_buffer_.handle(), 0,
                    palette_bytes)
            .apply(d);
        slots_.at(s).bound = palette_buffer_.handle();
        slots_.at(s).bound_bytes = palette_bytes;
    }
    const std::array<vk::DescriptorSetLayout, 1> extra{*set_layout_};
    ATLAS_GPU_LET(layout, context.layout.make_layout(extra));
    layout_ = std::move(layout);
    const auto desc = [&](std::string_view name, const char* vertex, const char* fragment = "colour_fs") {
        gpu::GraphicsDesc g{.label = {.name = name, .module = "world_world", .configuration = context.configuration},
                            .module = *module_,
                            .vertex = vertex,
                            .fragment = fragment,
                            .layout = *layout_,
                            .target = context.pass.scene_target()};
        g.blends[0] = gpu::Blend::over();
        for (std::uint32_t i = 1; i < g.target.colour_count && i < g.blends.size(); ++i)
            g.blends[i] = gpu::Blend::untouched();
        // a later draw of the world over an earlier one; the countries' ground over it, the sky nowhere
        g.depth_test = true;
        g.depth_write = true;
        g.depth_op = vk::CompareOp::eGreaterOrEqual;
        return g;
    };
    // the fills' back faces culled, so on the bent ground what lies beyond the horizon draws nowhere: a tile's
    // triangles are counter-clockwise with north up (earcut's winding), and the fading pipeline takes them backwards
    gpu::GraphicsDesc whole = desc("world fills", "fill_vs");
    whole.cull = vk::CullModeFlagBits::eBack;
    whole.front = vk::FrontFace::eCounterClockwise;
    ATLAS_GPU_LET(fills, frame::make_pipeline(context, whole, frame::Overdraw::opaque));
    fills_ = std::move(fills);
    // a fading tile's: each pixel its topmost fill alone (top first, GREATER), blended over its ancestor's cells
    gpu::GraphicsDesc fading = desc("world fills fading", "fill_vs");
    fading.depth_op = vk::CompareOp::eGreater;
    fading.cull = vk::CullModeFlagBits::eBack;
    fading.front = vk::FrontFace::eClockwise;
    ATLAS_GPU_LET(fading_fills, frame::make_pipeline(context, fading, frame::Overdraw::opaque));
    fading_fills_ = std::move(fading_fills);
    ATLAS_GPU_LET(lines, frame::make_pipeline(context, desc("world lines", "line_vs"), frame::Overdraw::opaque));
    lines_ = std::move(lines);
    // the same three with the look's stages, for the frames the ladder shades
    whole.label.name = "world fills look";
    whole.vertex = "fill_look_vs";
    whole.fragment = "look_fs";
    ATLAS_GPU_LET(look_fills, frame::make_pipeline(context, whole, frame::Overdraw::opaque));
    look_fills_ = std::move(look_fills);
    fading.label.name = "world fills fading look";
    fading.vertex = "fill_look_vs";
    fading.fragment = "look_fs";
    ATLAS_GPU_LET(look_fading, frame::make_pipeline(context, fading, frame::Overdraw::opaque));
    look_fading_fills_ = std::move(look_fading);
    ATLAS_GPU_LET(look_lines, frame::make_pipeline(context, desc("world lines look", "line_look_vs", "look_fs"),
                                                   frame::Overdraw::opaque));
    look_lines_ = std::move(look_lines);
    // over the world, tested against its depth without a write: the lights, and the triangle over the frame
    gpu::GraphicsDesc light = desc("world lights", "light_vs", "light_fs");
    light.depth_write = false;
    ATLAS_GPU_LET(lights, frame::make_pipeline(context, light, frame::Overdraw::translucent));
    lights_ = std::move(lights);
    gpu::GraphicsDesc star = desc("world stars", "star_vs", "star_fs");
    star.depth_write = false;
    ATLAS_GPU_LET(stars, frame::make_pipeline(context, star, frame::Overdraw::translucent));
    stars_ = std::move(stars);
    gpu::GraphicsDesc over = desc("world globe", "globe_vs", "globe_fs");
    over.depth_write = false;
    ATLAS_GPU_LET(globe, frame::make_pipeline(context, over, frame::Overdraw::translucent));
    globe_ = std::move(globe);
    return {};
}

void World::release() noexcept {
    fills_.clear();
    fading_fills_.clear();
    lines_.clear();
    look_fills_.clear();
    look_fading_fills_.clear();
    look_lines_.clear();
    lights_.clear();
    stars_.clear();
    globe_.clear();
    layout_.clear();
    for (Slot& s : slots_) {
        s.set.clear();
        s.bound = vk::Buffer{};
        s.bound_bytes = 0;
        s.drawing = false;
    }
    pool_.clear();
    set_layout_.clear();
    module_.clear();
    palette_buffer_ = gpu::Buffer{};
    look_buffer_ = gpu::Buffer{};
    device_ = nullptr;
    draws_.clear();
    space_ = false;
    glass_ = false;
}

void World::record(const frame::Recording& recording, std::uint32_t phase) {
    if (phase == over_phase) {
        record_over(recording);
    } else {
        record_tiles(recording);
    }
}

void World::record_over(const frame::Recording& recording) {
    const Slot& s = slots_.at(recording.state.slot & 1U);
    if (!*globe_ || !*lights_ || !s.drawing) return;
    const bool lit = look_.weights.caps > 0.0 && !look_.plain &&
                     std::ranges::any_of(draws_, [](const Draw& d) { return d.lights > 0 && d.light_cells != 0; });
    if (!space_ && !glass_ && !lit) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    const std::uint32_t flags = recording.state.block.camera.flags.x;
    const vk::Extent2D extent = recording.frame.output.extent();
    // each draw at its depth exactly, as the tiles' (record_tiles)
    const auto viewport = [&](float near, float far) {
        cmd.setViewport(0, vk::Viewport{.x = 0,
                                        .y = 0,
                                        .width = static_cast<float>(extent.width),
                                        .height = static_cast<float>(extent.height),
                                        .minDepth = near,
                                        .maxDepth = far});
    };
    std::uint64_t triangles = 0;
    std::uint32_t count = 0;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *globe_);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *s.set, {});
    const auto over_frame = [&](std::uint32_t what, float depth) {
        viewport(depth, depth);
        wl::PushConstants pc{};
        pc.origin = {0.0F, 0.0F, 0.0F, depth};
        pc.at = {what, 0U, 0U, 0U};
        cmd.pushConstants<wl::PushConstants>(*layout_, frame::push_stages, 0, pc);
        cmd.draw(3, 1, 0, 0);
        ++triangles;
        ++count;
    };
    // space where nothing of the world drew, the board's stars over it where its look has come (from 2,000 km), then
    // the glass over the disc
    if (space_) over_frame(0U, space_depth(flags));
    if (space_ && glass_ && *stars_) {
        const float under = space_depth(flags);
        viewport(under, under);
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *stars_);
        wl::PushConstants pc{};
        pc.origin = {0.0F, 0.0F, 0.0F, under};
        cmd.pushConstants<wl::PushConstants>(*layout_, frame::push_stages, 0, pc);
        cmd.draw(6, star_count, 0, 0);
        triangles += 2ULL * star_count;
        ++count;
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *globe_);
    }
    const float depth = over_depth(flags);
    if (glass_) over_frame(1U, depth);
    if (lit) {
        viewport(depth, depth);
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *lights_);
        for (const Draw& d : draws_) {
            if (d.lights == 0 || d.light_cells == 0) continue;
            const wl::PushConstants pc{.origin = {d.origin[0], d.origin[1], d.origin[2], depth},
                                       .scale = d.scale,
                                       .at = {d.at[0], d.light_cells, d.at[2], d.at[3] + (3U * d.segments)},
                                       .globe = d.globe,
                                       .sphere = d.sphere,
                                       .target = d.target};
            cmd.pushConstants<wl::PushConstants>(*layout_, frame::push_stages, 0, pc);
            cmd.draw(6, d.lights, 0, 0);
            triangles += 2ULL * d.lights;
            ++count;
        }
    }
    viewport(0.0F, 1.0F);
    recording.counters.draw("world over", triangles, count);
}

void World::record_tiles(const frame::Recording& recording) {
    const Slot& s = slots_.at(recording.state.slot & 1U);
    if (!*fills_ || !*lines_ || !s.drawing) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    std::uint64_t triangles = 0;
    const std::uint32_t flags = recording.state.block.camera.flags.x;
    // each tile, coarse to fine: its fills, then its lines over them; a fading tile over its ancestor's cells
    const vk::Pipeline* bound = nullptr;
    const auto bind = [&](const vk::raii::Pipeline& p) {
        if (bound == &*p) return;
        cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *p);
        if (bound == nullptr) cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *s.set, {});
        bound = &*p;
    };
    // every fragment of a draw at the draw's depth exactly: the viewport's depth range is that depth alone. The fills'
    // z·w / w comes out of the rasteriser a unit or two of the last place either way on a float attachment, and which
    // way turns with the camera, so a later fill at the same depth fell under an earlier one in shards that changed
    // from frame to frame (the far shimmer, the audit of 4 November); the frame's viewport (FrameBuilder's) after them
    const vk::Extent2D extent = recording.frame.output.extent();
    const auto viewport = [&](float near, float far) {
        cmd.setViewport(0, vk::Viewport{.x = 0,
                                        .y = 0,
                                        .width = static_cast<float>(extent.width),
                                        .height = static_cast<float>(extent.height),
                                        .minDepth = near,
                                        .maxDepth = far});
    };
    // the look's stages where the ladder shades the frame; the plain ones where it leaves the palette's colours
    const bool looked = look_.block.weights.x > 0.0F && *look_fills_ && *look_fading_fills_ && *look_lines_;
    const vk::raii::Pipeline& fills = looked ? look_fills_ : fills_;
    const vk::raii::Pipeline& fading_fills = looked ? look_fading_fills_ : fading_fills_;
    const vk::raii::Pipeline& lines = looked ? look_lines_ : lines_;
    const bool coasts = looked && look_.block.weights.y > 0.0F;
    float at = -1.0F;
    for (std::size_t first = 0; first < draws_.size();) {
        // a tile's draws: one, or one for each of its parts
        std::size_t end = first + 1;
        while (end < draws_.size() && draws_[end].tile == draws_[first].tile) ++end;
        const bool fading = draws_[first].fade < 1.0F;
        const float depth = fading ? fading_depth(flags, draws_[first].lift) : world_depth(flags);
        if (depth != at) {
            viewport(depth, depth);
            at = depth;
        }
        const auto push = [&](const Draw& d) {
            const wl::PushConstants pc{.origin = {d.origin[0], d.origin[1], d.origin[2], depth},
                                       .scale = d.scale,
                                       .at = d.at,
                                       .globe = d.globe,
                                       .sphere = d.sphere,
                                       .target = d.target};
            cmd.pushConstants<wl::PushConstants>(*layout_, frame::push_stages, 0, pc);
        };
        // its fills, part by part; a fading tile's last part first, as within each its triangles are drawn top first
        // (world.slang's fill_vs), so they are across its parts too
        for (std::size_t i = 0; i < end - first; ++i) {
            const Draw& d = draws_[fading ? end - 1 - i : first + i];
            if (d.triangles == 0) continue;
            bind(fading ? fading_fills : fills);
            push(d);
            cmd.draw(3 * d.triangles, 1, 0, 0);
            triangles += d.triangles;
        }
        // then its lines over them, part by part, its coast's (the last of them) only where the ramps give the coast
        // a weight
        for (std::size_t i = first; i < end; ++i) {
            const Draw& d = draws_[i];
            const std::uint32_t segments = coasts ? d.segments : d.segments - std::min(d.coast, d.segments);
            if (segments == 0) continue;
            bind(lines);
            push(d);
            cmd.draw(6, segments, 0, 0);
            triangles += 2ULL * segments;
        }
        first = end;
    }
    if (at >= 0.0F) viewport(0.0F, 1.0F);
    recording.counters.draw("world", triangles, static_cast<std::uint32_t>(draws_.size()));
}

} // namespace atlas::passes
