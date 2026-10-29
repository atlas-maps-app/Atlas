// The map's overlays drawn by the core (passes/Overlays.hpp).
#include "passes/Overlays.hpp"

#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/spirv/overlay/location.hpp"
#include "gen/spirv/overlay/location_loc1.hpp"
#include "style/CameraState.hpp"
#include "view/Camera.hpp"
#include "view/Ladder.hpp"

#include <algorithm>
#include <array>
#include <optional>

namespace atlas::passes {

namespace {

namespace ol = layouts::overlay_location;

/// The canvas's circle: AreaPaths' 72 points about the fix.
constexpr int circle_points = 72;

/// Geo.EARTH_RADIUS, metres.
constexpr double earth_radius = 6'371'008.8;

/// A box of the view's device px.
struct Box {
    double left = 0;
    double top = 0;
    double right = 0;
    double bottom = 0;
};

/// Geo.destination: the point `metres` from (lat, lon) along `bearing` (degrees clockwise from north), on the sphere.
[[nodiscard]] std::pair<double, double> destination(double lat, double lon, double bearing, double metres) noexcept {
    const double d = metres / earth_radius;
    const double t = pymath::radians(bearing);
    const double f1 = pymath::radians(lat);
    const double f2 =
        pymath::asin((pymath::sin(f1) * pymath::cos(d)) + (pymath::cos(f1) * pymath::sin(d) * pymath::cos(t)));
    const double l2 = pymath::radians(lon) + pymath::atan2(pymath::sin(t) * pymath::sin(d) * pymath::cos(f1),
                                                           pymath::cos(d) - (pymath::sin(f1) * pymath::sin(f2)));
    return {pymath::degrees(f2), pymath::fmod(pymath::degrees(l2) + 540.0, 360.0) - 180.0};
}

/// GroundView.onScreen: whether `b` reaches the view (`width` x `height` px).
[[nodiscard]] bool on_screen(const Box& b, double width, double height) noexcept {
    return b.right >= 0.0 && b.left <= width && b.bottom >= 0.0 && b.top <= height;
}

/// `b` grown by `margin` and cut to the view, as the shader's quad.
[[nodiscard]] std::array<float, 4> quad_of(const Box& b, double margin, double width, double height) noexcept {
    return {static_cast<float>(std::clamp(b.left - margin, 0.0, width)),
            static_cast<float>(std::clamp(b.top - margin, 0.0, height)),
            static_cast<float>(std::clamp(b.right + margin, 0.0, width)),
            static_cast<float>(std::clamp(b.bottom + margin, 0.0, height))};
}

/// The box of the accuracy's circle (radius `r` frame metres about `fix` on the street) on the view: its 72 points'
/// where each lies in front of the eye; the whole view where one does not (the circle reaches past the near plane, and
/// the canvas's clipped path reaches the view's edge).
[[nodiscard]] Box circle_box(const view::View& view, const view::Vec3& fix, double r, double width,
                             double height) noexcept {
    Box b{.left = width, .top = height, .right = 0.0, .bottom = 0.0};
    for (int k = 0; k < circle_points; ++k) {
        const double a = 2.0 * pymath::pi * k / circle_points;
        const auto p = view.device_proj({fix.x + (r * pymath::cos(a)), 0.0, fix.z + (r * pymath::sin(a))});
        if (!p) return {.left = 0.0, .top = 0.0, .right = width, .bottom = height};
        b = {.left = std::min(b.left, p->x),
             .top = std::min(b.top, p->y),
             .right = std::max(b.right, p->x),
             .bottom = std::max(b.bottom, p->y)};
    }
    return b;
}

} // namespace

Overlays::~Overlays() { Overlays::release(); }

gpu::Result<void> Overlays::prepare(const frame::PassContext& context) {
    release();
    const gpu::PipelineTarget& target = context.pass.composite_target();
    // Where the composite writes the output beside the scene's colour (the dynamic route at 1x), so does the puck.
    const bool loc1 = target.colour_count == 2;
    const char* name = loc1 ? "overlay_location_loc1" : "overlay_location";
    ATLAS_GPU_LET(module, loc1 ? frame::make_module(context, name, spirv::overlay_location_loc1)
                               : frame::make_module(context, name, spirv::overlay_location));
    module_ = std::move(module);
    layout_ = context.layout.pipeline_layout();
    gpu::GraphicsDesc desc{.label = {.name = "overlays", .module = name, .configuration = context.configuration},
                           .module = *module_,
                           .vertex = "location_vs",
                           .fragment = "location_fs",
                           .layout = layout_,
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

void Overlays::release() noexcept {
    pipeline_.clear();
    layout_ = nullptr;
    module_.clear();
    slots_ = {};
}

void Overlays::update(const frame::FrameState& state) {
    Placed& placed = slots_.at(state.slot & 1U);
    placed = {};
    if (!state.camera.has(style::camera_flag::location) || !state.view.has_value() || !state.ground_frame.has_value() ||
        state.camera.leaves_out(style::camera_part::location)) {
        return;
    }
    const view::View& view = *state.view;
    const view::Camera& camera = view.camera();
    const view::GroundFrame& ground = *state.ground_frame;
    const style::Location& l = state.camera.location;
    const double density = view.device().density;
    const auto width = static_cast<double>(state.camera.width_px);
    const auto height = static_cast<double>(state.camera.height_px);
    // The fix on the street; behind the near plane, nothing (GroundView.project).
    const view::Vec3 fix{ground.x(l.latitude, l.longitude), 0.0, ground.z(l.latitude, l.longitude)};
    const auto dot = view.device_proj(fix);
    if (!dot) return;
    ol::LocationPush& p = placed.push;
    const view::Vec3& eye = camera.eye();
    // A region's frame keeps the metre (the canvas's scaleAt is 1 there): the accuracy is the circle's radius.
    const auto r = static_cast<double>(l.accuracy);
    p.ground = {static_cast<float>(fix.x - eye.x), static_cast<float>(fix.y - eye.y), static_cast<float>(fix.z - eye.z),
                static_cast<float>(r)};
    // The accuracy where it is wider than 20 dp at the fix's depth (AreaPaths.circle's measure) and on the view.
    const double across = r * camera.focal() * density / camera.depth(fix);
    if (across > 20.0 * density) {
        const Box b = circle_box(view, fix, r, width, height);
        if (on_screen(b, width, height)) {
            placed.parts |= accuracy;
            p.boxes[0] = quad_of(b, 2.0, width, height);
        }
    }
    // The puck where its 40 dp about the dot reach the view, the cone turned toward a point 15 m ahead.
    const double d40 = 40.0 * density;
    if (!on_screen({.left = dot->x - d40, .top = dot->y - d40, .right = dot->x + d40, .bottom = dot->y + d40}, width,
                   height)) {
        return;
    }
    placed.parts |= puck;
    const double d44 = 44.0 * density;
    p.boxes[1] = quad_of({.left = dot->x, .top = dot->y, .right = dot->x, .bottom = dot->y}, d44, width, height);
    double turn = 0.0;
    std::uint32_t flags = 0;
    if (state.camera.has(style::camera_flag::heading)) {
        const auto [lat, lon] = destination(l.latitude, l.longitude, static_cast<double>(l.heading), 15.0);
        if (const auto ahead = view.device_proj({ground.x(lat, lon), 0.0, ground.z(lat, lon)})) {
            turn = pymath::atan2(ahead->y - dot->y, ahead->x - dot->x) + (pymath::pi / 2.0);
            flags = 1U; // LOCATION_HEADING
        }
    }
    p.dot = {static_cast<float>(dot->x), static_cast<float>(dot->y), static_cast<float>(density),
             static_cast<float>(turn)};
    p.flags = {flags, 0U, 0U, 0U};
}

void Overlays::record(const frame::Recording& recording, std::uint32_t phase) {
    (void)phase;
    const Placed& placed = slots_.at(recording.state.slot & 1U);
    if (!*pipeline_ || placed.parts == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipeline_);
    cmd.pushConstants<ol::LocationPush>(layout_, frame::push_stages, 0, placed.push);
    std::uint32_t draws = 0;
    for (std::uint32_t part = 0; part < 2; ++part) {
        if ((placed.parts & (1U << part)) == 0U) continue;
        cmd.draw(6, 1, 0, part); // the instance is the part
        ++draws;
    }
    recording.counters.draw("location", 2ULL * draws, draws);
}

} // namespace atlas::passes
