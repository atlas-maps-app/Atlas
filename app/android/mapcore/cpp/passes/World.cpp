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
#include <cstring>

namespace atlas::passes {

namespace {

namespace wl = layouts::world_world;
namespace wp = scene::world_palette;

constexpr std::size_t palette_bytes = std::size_t{wp::size} * sizeof(std::uint32_t);

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

/// World::paint_for_tests's colour (0: none).
[[nodiscard]] std::atomic<std::uint32_t>& test_paint() noexcept {
    static std::atomic<std::uint32_t> colour{0};
    return colour;
}

} // namespace

void World::paint_for_tests(std::uint32_t colour) noexcept { test_paint().store(colour, std::memory_order_relaxed); }

World::~World() { World::release(); }

std::vector<std::uint32_t> World::palette_of(const style::ThemeBlock& theme, bool night, const tiles::Style* style) {
    std::vector<std::uint32_t> out(wp::size, word_of(theme.ground));
    out[wp::water] = word_of(theme.water);
    out[wp::road] = word_of(theme.road);
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
    return out;
}

void World::update(const frame::FrameState& state) {
    Slot& s = slots_.at(state.slot & 1U);
    s.drawing = false;
    draws_.clear();
    const scene::WorldFrame* world = state.world;
    if (world == nullptr || world->draws.empty() || !state.view.has_value() || !*fills_ || !palette_buffer_) return;
    const bool night = state.theme.has(style::theme_flag::night);
    palette_ = palette_of(state.theme, night, world->style);
    if (const std::uint32_t paint = test_paint().load(std::memory_order_relaxed); paint != 0) {
        std::ranges::fill(palette_, paint);
    }
    std::memcpy(palette_buffer_.bytes().data() + ((state.slot & 1U) * palette_bytes), palette_.data(), palette_bytes);
    palette_buffer_.flush();
    if (s.bound != world->buffer) {
        // the slot's last frame has finished with its set (the frame slots' fences)
        gpu::SetWriter(*s.set)
            .buffer(wl::world_words.binding, vk::DescriptorType::eStorageBuffer, world->buffer, 0, world->buffer_bytes)
            .apply(device_->handle());
        s.bound = world->buffer;
    }
    const view::Camera& camera = state.view->camera();
    const view::Vec3& eye = camera.eye();
    const view::GroundFrame& f = world->frame;
    for (const scene::WorldDraw& d : world->draws) {
        const scene::WorldTile& t = *d.tile;
        if (!t.range || (t.triangles == 0 && t.segments == 0)) continue;
        constexpr double grid = 1e-7;
        const double south = t.box[0] * grid;
        const double west = t.box[1] * grid;
        const double north = t.box[2] * grid;
        const double east = t.box[3] * grid;
        const double x0 = f.x(north, west);
        const double z0 = f.z(north, west);
        Draw out;
        out.origin = {static_cast<float>(x0 - eye.x), static_cast<float>(-eye.y), static_cast<float>(z0 - eye.z), 0.0F};
        out.scale = {static_cast<float>((f.x(north, east) - x0) / 65'535.0),
                     static_cast<float>((f.z(south, west) - z0) / 65'535.0), d.fade, d.fade < 1.0F ? 1.0F : 0.0F};
        out.fade = d.fade;
        out.level = t.key.z;
        out.at = {t.range.offset / 4U, static_cast<std::uint32_t>(d.cells & 0xFFFFU), 3U * t.triangles,
                  4U * t.triangles};
        out.triangles = t.triangles;
        out.segments = t.segments;
        draws_.push_back(out);
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
    const std::array bindings{gpu::Binding{.slot = wl::world_words.binding,
                                           .type = vk::DescriptorType::eStorageBuffer,
                                           .stages = vk::ShaderStageFlagBits::eVertex},
                              gpu::Binding{.slot = wl::world_palette.binding,
                                           .type = vk::DescriptorType::eStorageBuffer,
                                           .stages = vk::ShaderStageFlagBits::eVertex}};
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
    for (std::size_t s = 0; s < slots_.size(); ++s) {
        gpu::SetWriter(*slots_.at(s).set)
            .buffer(wl::world_palette.binding, vk::DescriptorType::eStorageBuffer, palette_buffer_.handle(),
                    s * palette_bytes, palette_bytes)
            // the words' binding until a world draws: the palette's buffer, never read
            .buffer(wl::world_words.binding, vk::DescriptorType::eStorageBuffer, palette_buffer_.handle(), 0,
                    palette_bytes)
            .apply(d);
        slots_.at(s).bound = palette_buffer_.handle();
    }
    const std::array<vk::DescriptorSetLayout, 1> extra{*set_layout_};
    ATLAS_GPU_LET(layout, context.layout.make_layout(extra));
    layout_ = std::move(layout);
    const auto desc = [&](std::string_view name, const char* vertex) {
        gpu::GraphicsDesc g{.label = {.name = name, .module = "world_world", .configuration = context.configuration},
                            .module = *module_,
                            .vertex = vertex,
                            .fragment = "colour_fs",
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
    ATLAS_GPU_LET(fills, frame::make_pipeline(context, desc("world fills", "fill_vs"), frame::Overdraw::opaque));
    fills_ = std::move(fills);
    // a fading tile's: each pixel its topmost fill alone (top first, GREATER), blended over its ancestor's cells
    gpu::GraphicsDesc fading = desc("world fills fading", "fill_vs");
    fading.depth_op = vk::CompareOp::eGreater;
    ATLAS_GPU_LET(fading_fills, frame::make_pipeline(context, fading, frame::Overdraw::opaque));
    fading_fills_ = std::move(fading_fills);
    ATLAS_GPU_LET(lines, frame::make_pipeline(context, desc("world lines", "line_vs"), frame::Overdraw::opaque));
    lines_ = std::move(lines);
    return {};
}

void World::release() noexcept {
    fills_.clear();
    fading_fills_.clear();
    lines_.clear();
    layout_.clear();
    for (Slot& s : slots_) {
        s.set.clear();
        s.bound = vk::Buffer{};
        s.drawing = false;
    }
    pool_.clear();
    set_layout_.clear();
    module_.clear();
    palette_buffer_ = gpu::Buffer{};
    device_ = nullptr;
    draws_.clear();
}

void World::record(const frame::Recording& recording, std::uint32_t phase) {
    (void)phase;
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
    float at = -1.0F;
    for (const Draw& d : draws_) {
        const bool fading = d.fade < 1.0F;
        const float depth = fading ? fading_depth(flags, d.lift) : world_depth(flags);
        if (depth != at) {
            viewport(depth, depth);
            at = depth;
        }
        const wl::PushConstants pc{
            .origin = {d.origin[0], d.origin[1], d.origin[2], depth}, .scale = d.scale, .at = d.at};
        if (d.triangles > 0) {
            bind(fading ? fading_fills_ : fills_);
            cmd.pushConstants<wl::PushConstants>(*layout_, frame::push_stages, 0, pc);
            cmd.draw(3 * d.triangles, 1, 0, 0);
            triangles += d.triangles;
        }
        if (d.segments > 0) {
            bind(lines_);
            cmd.pushConstants<wl::PushConstants>(*layout_, frame::push_stages, 0, pc);
            cmd.draw(6, d.segments, 0, 0);
            triangles += 2ULL * d.segments;
        }
    }
    if (at >= 0.0F) viewport(0.0F, 1.0F);
    recording.counters.draw("world", triangles, static_cast<std::uint32_t>(draws_.size()));
}

} // namespace atlas::passes
