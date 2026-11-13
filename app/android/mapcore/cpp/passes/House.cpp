// The house the app asks about, in the roof it asks about (passes/House.hpp).
#include "passes/House.hpp"

#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/spirv/overlay/house.hpp"
#include "gpu/Descriptors.hpp"
#include "scene/House.hpp"
#include "style/Light.hpp"
#include "view/Camera.hpp"

#include <algorithm>
#include <cstring>

namespace atlas::passes {

namespace {

namespace hs = layouts::overlay_house;

constexpr std::size_t point_bytes = sizeof(hs::HousePoint);
constexpr std::size_t slot_bytes = House::max_points * point_bytes;

/// 0xRRGGBB as a colour's levels, sRGB 0 to 1.
[[nodiscard]] std::array<float, 3> levels(std::uint32_t rgb) noexcept {
    return {static_cast<float>((rgb >> 16U) & 0xFFU) / 255.0F, static_cast<float>((rgb >> 8U) & 0xFFU) / 255.0F,
            static_cast<float>(rgb & 0xFFU) / 255.0F};
}

} // namespace

House::~House() { House::release(); }

void House::update(const frame::FrameState& state) {
    Slot& s = slots_.at(state.slot & 1U);
    s.points.clear();
    s.corners = 0;
    const scene::House* house = state.house;
    if (house == nullptr || !buffer_ || !*fill_) return;
    const shaders::TilePlace& place = state.house_place;
    s.push.offset = {place.offset.x, place.offset.y, place.offset.z, 0.0F};
    s.push.scale = {place.scale.x, place.scale.y, place.scale.z, 1.0F};
    // The faces' colours for the frame: the walls flat, a slope by how far it turns to the sun, a flat top white.
    const std::array<float, 3> wall = levels(state.house_wall);
    const float hatched = state.house_hatched ? 1.0F : 0.0F;
    const std::array<float, 3> shade = levels(slope_shade);
    const std::array<float, 3> sun = levels(slope_sun);
    const std::array<float, 3> flat = levels(top);
    const bool night = shaders::theme_has(state.block.theme, shaders::THEME_NIGHT);
    const shaders::double3 l = shaders::theme_sun_vector(state.block.theme);
    for (const scene::HouseFace& f : house->faces) {
        std::array<float, 3> colour = wall;
        float hatch = hatched;
        if (f.part == scene::HousePart::slope) {
            const double lam =
                night ? 0.0 : std::clamp((f.normal[0] * l.x) + (f.normal[1] * l.y) + (f.normal[2] * l.z), 0.0, 1.0);
            const auto t = static_cast<float>(lam);
            colour = {shade[0] + ((sun[0] - shade[0]) * t), shade[1] + ((sun[1] - shade[1]) * t),
                      shade[2] + ((sun[2] - shade[2]) * t)};
            hatch = 0.0F; // the board hatches the walls alone
        } else if (f.part == scene::HousePart::top) {
            colour = flat;
            hatch = 0.0F;
        }
        if (s.points.size() + f.count > max_points) break;
        for (std::uint32_t k = 0; k < f.count; ++k) {
            const std::array<float, 3>& p = house->points[f.first + k];
            s.points.push_back({.at = {p[0], p[1], p[2], hatch}, .colour = {colour[0], colour[1], colour[2], 1.0F}});
        }
    }
    s.corners = static_cast<std::uint32_t>(s.points.size());
    // The flat top's outline: a segment an edge, one device px wide, as much of it as .45 CSS px cover (premultiplied).
    if (*rim_ && house->rim.size() >= 2 && state.view.has_value()) {
        const auto a = static_cast<float>(std::min(1.0, rim_css * state.view->device().density));
        const std::array<float, 3> c = levels(rim);
        const std::array<float, 4> colour{c[0] * a, c[1] * a, c[2] * a, a};
        for (std::size_t k = 0; k + 1 < house->rim.size() && s.points.size() + 2 <= max_points; ++k) {
            for (const std::array<float, 3>& p : {house->rim[k], house->rim[k + 1]}) {
                s.points.push_back({.at = {p[0], p[1], p[2], 0.0F}, .colour = colour});
            }
        }
    }
    if (s.points.empty()) return;
    std::memcpy(buffer_.bytes().data() + ((state.slot & 1U) * slot_bytes), s.points.data(),
                s.points.size() * point_bytes);
    buffer_.flush();
}

gpu::Result<void> House::prepare(const frame::PassContext& context) {
    release();
    // In the owner mode every scene pipeline writes its painter slot: the house has none (the frame builder sets no
    // house under the painter switch), so the pass makes nothing there.
    if (context.owners) return {};
    const vk::raii::Device& d = context.device.handle();
    ATLAS_GPU_LET(module, frame::make_module(context, "overlay_house", spirv::overlay_house));
    module_ = std::move(module);
    const std::array bindings{gpu::Binding{.slot = hs::house_points.binding,
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
    ATLAS_GPU_LET(storage, context.device.memory().buffer(2 * slot_bytes, vk::BufferUsageFlagBits::eStorageBuffer,
                                                          gpu::MemoryUse::upload));
    buffer_ = std::move(storage);
    for (std::size_t s = 0; s < slots_.size(); ++s) {
        gpu::SetWriter(*slots_.at(s).set)
            .buffer(hs::house_points.binding, vk::DescriptorType::eStorageBuffer, buffer_.handle(), s * slot_bytes,
                    slot_bytes)
            .apply(d);
    }
    const std::array<vk::DescriptorSetLayout, 1> extra{*set_layout_};
    ATLAS_GPU_LET(layout, context.layout.make_layout(extra));
    layout_ = std::move(layout);
    // The triangles: opaque among what stands up, at their true depth, writing it and the objects bit (as
    // passes::building_desc has the walls and roofs).
    gpu::GraphicsDesc fill{
        .label = {.name = "house", .module = "overlay_house", .configuration = context.configuration},
        .module = *module_,
        .vertex = "house_vs",
        .fragment = "house_fs",
        .layout = *layout_,
        .target = context.pass.scene_target(),
        .depth_test = true,
        .depth_write = true,
        .depth_op = vk::CompareOp::eGreater,
        .stencil_test = true,
        .stencil = {.failOp = vk::StencilOp::eKeep,
                    .passOp = vk::StencilOp::eReplace,
                    .depthFailOp = vk::StencilOp::eKeep,
                    .compareOp = vk::CompareOp::eAlways,
                    .compareMask = 0,
                    .writeMask = shaders::OBJECTS_BIT,
                    .reference = shaders::OBJECTS_BIT}};
    if (fill.target.colour_count == 2) fill.blends[1] = gpu::Blend::untouched();
    ATLAS_GPU_LET(fill_made, frame::make_pipeline(context, fill, frame::Overdraw::opaque));
    fill_ = std::move(fill_made);
    // The outline: lines over what the frame drew, at their own depth (over the top they outline, under whatever
    // stands nearer), nothing written but colour.
    gpu::GraphicsDesc rim_desc = fill;
    rim_desc.label.name = "house rim";
    rim_desc.topology = vk::PrimitiveTopology::eLineList;
    rim_desc.depth_write = false;
    rim_desc.depth_op = vk::CompareOp::eGreaterOrEqual;
    rim_desc.stencil_test = false;
    rim_desc.stencil = vk::StencilOpState{};
    rim_desc.blends[0] = gpu::Blend::over();
    ATLAS_GPU_LET(rim_made, frame::make_pipeline(context, rim_desc, frame::Overdraw::translucent));
    rim_ = std::move(rim_made);
    return {};
}

void House::release() noexcept {
    rim_.clear();
    fill_.clear();
    layout_.clear();
    for (Slot& s : slots_) {
        s.set.clear();
        s.points.clear();
        s.corners = 0;
    }
    pool_.clear();
    set_layout_.clear();
    module_.clear();
    buffer_ = gpu::Buffer{};
}

void House::record(const frame::Recording& recording, std::uint32_t phase) {
    const Slot& s = slots_.at(recording.state.slot & 1U);
    if (s.points.empty() || !*fill_) return;
    const bool outline = phase == rim_phase;
    const auto count = static_cast<std::uint32_t>(s.points.size());
    const std::uint32_t first = outline ? s.corners : 0U;
    const std::uint32_t drawn = outline ? count - s.corners : s.corners;
    if (drawn == 0 || (outline && !*rim_)) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, outline ? *rim_ : *fill_);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *s.set, {});
    cmd.pushConstants<hs::HousePush>(*layout_, frame::push_stages, 0, s.push);
    cmd.draw(drawn, 1, first, 0);
    recording.counters.draw(outline ? "house rim" : "house", outline ? drawn / 2ULL : drawn / 3ULL, 1);
}

} // namespace atlas::passes
