// The app's routes and areas drawn by the core (passes/Routes.hpp).
#include "passes/Routes.hpp"

#include "base/log.hpp"
#include "base/pymath/PyMath.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/spirv/overlay/route.hpp"
#include "gen/spirv/overlay/route_loc1.hpp"
#include "gpu/Descriptors.hpp"
#include "routes/Routes.hpp"
#include "view/Camera.hpp"
#include "view/Ladder.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

namespace atlas::passes {

namespace {

namespace rt = layouts::overlay_route;

constexpr std::size_t draw_bytes = sizeof(rt::RouteDraw);
constexpr std::size_t slot_bytes = Routes::max_draws * draw_bytes;

/// OverlayPainter.drawGround's and RouteRibbons' measures, dp: the casing's width either side of the fill, the fill's
/// least half-width (a hairline far away) and how much the casing's exceeds it, an area's line, and how far in from the
/// view's edges a chip stands.
constexpr double casing_dp = 1.6;
constexpr double least_dp = 1.25;
constexpr double casing_least_dp = casing_dp * 0.6;
constexpr double area_line_dp = 1.5;
constexpr double chip_margin_dp = 24.0;
/// A dashed route's dash and period, its widths (RouteRibbons' walking legs).
constexpr double dash_widths = 1.3;
constexpr double period_widths = 2.6;
/// AreaPaths' circle: its points.
constexpr int circle_points = 72;
/// RouteRibbons.add: a run's points closer than 1.5 px on the view merge (a forced one replaces one within 0.5 px).
constexpr float merge_px2 = 2.25F;
constexpr float replace_px2 = 0.25F;

/// A box of the view's device px.
struct Box {
    double left = 0;
    double top = 0;
    double right = 0;
    double bottom = 0;
};

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

/// The box on the view of the street points `points` (frame metres: x, z); the whole view where one does not project
/// (it lies nearer than the near plane, and what is drawn of it may reach the view's edge).
template <class Points>
[[nodiscard]] Box box_of(const view::View& view, const Points& points, double width, double height) noexcept {
    Box b{.left = width, .top = height, .right = 0.0, .bottom = 0.0};
    for (const auto& [x, z] : points) {
        const auto p = view.device_proj({x, 0.0, z});
        if (!p) return {.left = 0.0, .top = 0.0, .right = width, .bottom = height};
        b = {.left = std::min(b.left, p->x),
             .top = std::min(b.top, p->y),
             .right = std::max(b.right, p->x),
             .bottom = std::max(b.bottom, p->y)};
    }
    return b;
}

/// Kotlin's Float.roundToInt: Math.round of the value (halves up), Int's limits beyond them.
[[nodiscard]] std::int32_t round_px(float v) noexcept {
    const std::int64_t r = pymath::java::round(static_cast<double>(v));
    constexpr std::int64_t lo = std::numeric_limits<std::int32_t>::min();
    constexpr std::int64_t hi = std::numeric_limits<std::int32_t>::max();
    return static_cast<std::int32_t>(std::clamp(r, lo, hi));
}

/// `b`, or a buffer for `use` of at least `bytes` with room to grow by half again where `b` is smaller; whether it was
/// made anew.
[[nodiscard]] gpu::Result<bool> fit(const gpu::Device& device, gpu::Buffer& b, vk::DeviceSize bytes,
                                    vk::BufferUsageFlags usage, gpu::MemoryUse use) {
    if (b && b.size() >= bytes) return false;
    ATLAS_GPU_LET(made, device.memory().buffer(std::max<vk::DeviceSize>(bytes + (bytes / 2), 1024), usage, use));
    b = std::move(made);
    return true;
}

} // namespace

Routes::~Routes() { Routes::release(); }

void Routes::update(const frame::FrameState& state) {
    const std::uint32_t slot = state.slot & 1U;
    Slot& s = slots_.at(slot);
    s.count = 0;
    draws_.clear();
    chips_.clear();
    const routes::RouteSet* set = state.routes;
    if (set == nullptr || !state.view.has_value() || !state.ground_frame.has_value() ||
        state.camera.leaves_out(style::camera_part::routes)) {
        return;
    }
    const view::View& view = *state.view;
    const view::Camera& camera = view.camera();
    const view::GroundFrame& ground = *state.ground_frame;
    const double density = view.device().density;
    const auto width = static_cast<double>(state.camera.width_px);
    const auto height = static_cast<double>(state.camera.height_px);
    const view::Vec3& eye = camera.eye();
    const double focal_px = camera.focal() * density;
    // The canvas's camera's measures (MapCamera): the metres a dp spans at the target (its distance over the focal
    // length), the near clip and the haze.
    const double distance = camera.spec().dist;
    const double mpdp = distance / camera.focal();
    const double near_clip = 1.5 * std::clamp(distance * 0.02, 0.5, 20.0);
    const double far = distance * 6.0;
    const auto relative = [&](double x, double z, double along) {
        return std::array<float, 4>{static_cast<float>(x - eye.x), static_cast<float>(-eye.y),
                                    static_cast<float>(z - eye.z), static_cast<float>(along)};
    };
    // The areas whose circles reach the view.
    for (const routes::Area& a : set->areas()) {
        const double cx = ground.x(a.latitude, a.longitude);
        const double cz = ground.z(a.latitude, a.longitude);
        const auto r = static_cast<double>(a.radius);
        std::array<std::array<double, 2>, circle_points> ring{};
        for (int k = 0; k < circle_points; ++k) {
            const double t = 2.0 * pymath::pi * k / circle_points;
            ring.at(static_cast<std::size_t>(k)) = {cx + (r * pymath::cos(t)), cz + (r * pymath::sin(t))};
        }
        const Box b = box_of(view, ring, width, height);
        if (!on_screen(b, width, height) || draws_.size() >= max_draws) continue;
        draws_.push_back({.a = relative(cx, cz, r),
                          .b = {},
                          .width = {static_cast<float>(area_line_dp / 2.0 * density), 0.0F, 0.0F, 0.0F},
                          .box = quad_of(b, 2.0, width, height),
                          .more = {},
                          .look = {a.fill, a.stroke, 0U, area}});
    }
    // Each route: its pieces between the near clip and the haze; its casing's draws, then its fill's.
    for (const routes::Route& route : set->routes()) {
        pieces_.clear();
        bool open = false; // the last piece reached its segment's end: a run goes on
        for (std::size_t i = 1; i < route.points.size(); ++i) {
            const auto& [lat0, lon0, s0] = route.points[i - 1];
            const auto& [lat1, lon1, s1] = route.points[i];
            const double x0 = ground.x(lat0, lon0);
            const double z0 = ground.z(lat0, lon0);
            const double x1 = ground.x(lat1, lon1);
            const double z1 = ground.z(lat1, lon1);
            const double d0 = camera.depth({x0, 0.0, z0});
            const double d1 = camera.depth({x1, 0.0, z1});
            // the part of the segment between the near clip and the haze (RouteRibbons.draw)
            double t0 = 0.0;
            double t1 = 1.0;
            if (d0 == d1) {
                if (d0 < near_clip || d0 > far) {
                    t0 = 1.0;
                    t1 = 0.0;
                }
            } else {
                const double ta = (near_clip - d0) / (d1 - d0);
                const double tb = (far - d0) / (d1 - d0);
                t0 = std::max(0.0, std::min(ta, tb));
                t1 = std::min(1.0, std::max(ta, tb));
            }
            if (t0 >= t1) {
                open = false;
                continue;
            }
            pieces_.push_back({.ax = x0 + ((x1 - x0) * t0),
                               .az = z0 + ((z1 - z0) * t0),
                               .sa = s0 + ((s1 - s0) * t0),
                               .bx = x0 + ((x1 - x0) * t1),
                               .bz = z0 + ((z1 - z0) * t1),
                               .sb = s0 + ((s1 - s0) * t1),
                               .joined = open && t0 == 0.0,
                               .ends = t1 < 1.0 || i + 1 == route.points.size()});
            open = t1 == 1.0;
        }
        if (pieces_.empty()) continue;
        const double half = static_cast<double>(route.width) / 2.0 * mpdp;
        const double period = route.dashed ? static_cast<double>(route.width) * period_widths * mpdp : 0.0;
        const double dash = static_cast<double>(route.width) * dash_widths * mpdp;
        const auto emit = [&](double h, double least, std::uint32_t colour, std::uint32_t faded) {
            // each piece's capsule's box on the view, its ends' half-widths no thinner than the least on the view
            quads_.resize(pieces_.size());
            shown_.resize(pieces_.size());
            for (std::size_t k = 0; k < pieces_.size(); ++k) {
                const Piece& p = pieces_[k];
                const double ha = std::max(h, least * camera.depth({p.ax, 0.0, p.az}) / focal_px);
                const double hb = std::max(h, least * camera.depth({p.bx, 0.0, p.bz}) / focal_px);
                const double len = pymath::hypot(p.bx - p.ax, p.bz - p.az);
                const double ux = len > 0.0 ? (p.bx - p.ax) / len : 1.0;
                const double uz = len > 0.0 ? (p.bz - p.az) / len : 0.0;
                const std::array<std::array<double, 2>, 4> corners{
                    std::array<double, 2>{p.ax - (ux * ha) - (uz * ha), p.az - (uz * ha) + (ux * ha)},
                    std::array<double, 2>{p.ax - (ux * ha) + (uz * ha), p.az - (uz * ha) - (ux * ha)},
                    std::array<double, 2>{p.bx + (ux * hb) + (uz * hb), p.bz + (uz * hb) - (ux * hb)},
                    std::array<double, 2>{p.bx + (ux * hb) - (uz * hb), p.bz + (uz * hb) + (ux * hb)}};
                const Box b = box_of(view, corners, width, height);
                shown_[k] = on_screen(b, width, height) ? 1 : 0;
                quads_[k] = quad_of(b, 2.0, width, height);
            }
            for (std::size_t k = 0; k < pieces_.size(); ++k) {
                if (shown_[k] == 0) continue;
                if (draws_.size() >= max_draws) return;
                const Piece& p = pieces_[k];
                // a piece meets the one drawn before it, or after it, where its run goes on into it
                const bool prev = p.joined && k > 0 && shown_[k - 1] != 0;
                const bool next = k + 1 < pieces_.size() && pieces_[k + 1].joined && shown_[k + 1] != 0;
                draws_.push_back({.a = relative(p.ax, p.az, p.sa),
                                  .b = relative(p.bx, p.bz, p.sb),
                                  .width = {static_cast<float>(h), static_cast<float>(least),
                                            static_cast<float>(period), static_cast<float>(dash)},
                                  .box = quads_[k],
                                  .more = {static_cast<float>(route.travelled), 0.0F, 0.0F, 0.0F},
                                  .look = {colour, faded, (prev ? joins_prev : 0U) | (next ? joins_next : 0U), piece}});
            }
        };
        emit(half + (casing_dp * mpdp), (least_dp + casing_least_dp) * density, route.casing, route.casing);
        emit(half, least_dp * density, route.colour, route.faded);
        if (route.chip == routes::no_chip) continue;
        // The chip: halfway along the part of the fill's runs on the view, 24 dp in from its edges (chipPoint), the
        // runs' points as RouteRibbons.add keeps them (closer than 1.5 px they merge; a run's ends are kept).
        run_.clear();
        run_breaks_.clear();
        std::size_t start = 0; // the run's first point in run_
        const auto add = [&](double x, double z, bool force) {
            const auto p = view.device_proj({x, 0.0, z});
            if (!p) return;
            const std::array<float, 2> q{static_cast<float>(p->x), static_cast<float>(p->y)};
            const std::size_t n = run_.size() - start;
            if (n > 0) {
                const float dx = q[0] - run_.back()[0];
                const float dy = q[1] - run_.back()[1];
                const float d2 = (dx * dx) + (dy * dy);
                if (!force && d2 < merge_px2) return;
                if (force && d2 < replace_px2 && n > 1) {
                    run_.pop_back();
                    run_breaks_.pop_back();
                }
            }
            run_breaks_.push_back(run_.size() == start ? 1 : 0);
            run_.push_back(q);
        };
        for (const Piece& p : pieces_) {
            if (!p.joined) {
                start = run_.size();
                add(p.ax, p.az, true);
            }
            add(p.bx, p.bz, p.ends);
        }
        const auto margin = static_cast<float>(chip_margin_dp * density);
        const auto w = static_cast<float>(width);
        const auto h = static_cast<float>(height);
        const auto inside = [&](std::size_t i) {
            return run_[i][0] >= margin && run_[i][0] <= w - margin && run_[i][1] >= margin && run_[i][1] <= h - margin;
        };
        const auto counts = [&](std::size_t i) { return run_breaks_[i] == 0 && inside(i) && inside(i - 1); };
        const auto length = [&](std::size_t i) {
            return pymath::hypot(static_cast<double>(run_[i][0] - run_[i - 1][0]),
                                 static_cast<double>(run_[i][1] - run_[i - 1][1]));
        };
        double total = 0.0;
        for (std::size_t i = 1; i < run_.size(); ++i) {
            if (counts(i)) total += length(i);
        }
        if (total <= 0.0) continue;
        double walked = 0.0;
        for (std::size_t i = 1; i < run_.size(); ++i) {
            if (!counts(i)) continue;
            const double seg = length(i);
            if (walked + seg >= total / 2.0) {
                const auto t = static_cast<float>(seg > 0.0 ? (total / 2.0 - walked) / seg : 0.0);
                const float x = run_[i - 1][0] + ((run_[i][0] - run_[i - 1][0]) * t);
                const float y = run_[i - 1][1] + ((run_[i][1] - run_[i - 1][1]) * t);
                const routes::Image& image = set->chips()[route.chip];
                chips_.push_back({.a = {},
                                  .b = {},
                                  .width = {},
                                  .box = {static_cast<float>(round_px(x) - image.anchor_x),
                                          static_cast<float>(round_px(y) - image.anchor_y),
                                          static_cast<float>(image.width), static_cast<float>(image.height)},
                                  .more = {},
                                  .look = {set->chip_offsets()[route.chip], image.width, image.height, chip}});
                break;
            }
            walked += seg;
        }
    }
    // The chips over every ribbon.
    for (const auto& c : chips_) {
        if (draws_.size() >= max_draws) break;
        draws_.push_back(c);
    }
    if (draws_.empty() || !draw_buffer_ || device_ == nullptr) return;
    // The slot's copy of the set's chips, made when it first draws this set (its previous frame has finished with the
    // copy it had).
    if (s.of != set || s.revision != set->revision() || !s.texels) {
        const auto texels = set->texels();
        auto made = fit(*device_, s.texels, std::max<vk::DeviceSize>(texels.size_bytes(), 4),
                        vk::BufferUsageFlagBits::eStorageBuffer, gpu::MemoryUse::upload);
        if (!made) {
            log::error("vk routes: their chips' buffer ({} texels): {}", texels.size(), made.error().text());
            s.of = nullptr;
            return;
        }
        if (!texels.empty()) std::memcpy(s.texels.bytes().data(), texels.data(), texels.size_bytes());
        s.texels.flush();
        if (*made) {
            gpu::SetWriter(*s.set)
                .buffer(rt::route_texels.binding, vk::DescriptorType::eStorageBuffer, s.texels.handle())
                .apply(device_->handle());
        }
        s.of = set;
        s.revision = set->revision();
    }
    std::memcpy(draw_buffer_.bytes().data() + (slot * slot_bytes), draws_.data(), draws_.size() * draw_bytes);
    draw_buffer_.flush();
    s.count = static_cast<std::uint32_t>(draws_.size());
}

gpu::Result<void> Routes::prepare(const frame::PassContext& context) {
    release();
    device_ = &context.device;
    const vk::raii::Device& d = context.device.handle();
    const gpu::PipelineTarget& target = context.pass.composite_target();
    // Where the composite writes the output beside the scene's colour (the dynamic route at 1x), so do the routes.
    const bool loc1 = target.colour_count == 2;
    const char* name = loc1 ? "overlay_route_loc1" : "overlay_route";
    ATLAS_GPU_LET(module, loc1 ? frame::make_module(context, name, spirv::overlay_route_loc1)
                               : frame::make_module(context, name, spirv::overlay_route));
    module_ = std::move(module);
    const std::array bindings{
        gpu::Binding{.slot = rt::route_draws.binding,
                     .type = vk::DescriptorType::eStorageBuffer,
                     .stages = vk::ShaderStageFlagBits::eVertex | vk::ShaderStageFlagBits::eFragment},
        gpu::Binding{.slot = rt::route_texels.binding,
                     .type = vk::DescriptorType::eStorageBuffer,
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
    ATLAS_GPU_LET(draws, context.device.memory().buffer(2 * slot_bytes, vk::BufferUsageFlagBits::eStorageBuffer,
                                                        gpu::MemoryUse::upload));
    draw_buffer_ = std::move(draws);
    for (std::size_t s = 0; s < slots_.size(); ++s) {
        gpu::SetWriter(*slots_.at(s).set)
            .buffer(rt::route_draws.binding, vk::DescriptorType::eStorageBuffer, draw_buffer_.handle(), s * slot_bytes,
                    slot_bytes)
            .apply(d);
    }
    const std::array<vk::DescriptorSetLayout, 1> extra{*set_layout_};
    ATLAS_GPU_LET(layout, context.layout.make_layout(extra));
    layout_ = std::move(layout);
    gpu::GraphicsDesc desc{.label = {.name = "routes", .module = name, .configuration = context.configuration},
                           .module = *module_,
                           .vertex = "route_vs",
                           .fragment = "route_fs",
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

void Routes::release() noexcept {
    pipeline_.clear();
    layout_.clear();
    for (Slot& s : slots_) {
        s.set.clear();
        s.texels = gpu::Buffer{};
        s.of = nullptr;
        s.revision = 0;
        s.count = 0;
    }
    pool_.clear();
    set_layout_.clear();
    module_.clear();
    draw_buffer_ = gpu::Buffer{};
    device_ = nullptr;
}

void Routes::record(const frame::Recording& recording, std::uint32_t phase) {
    (void)phase;
    const Slot& s = slots_.at(recording.state.slot & 1U);
    if (!*pipeline_ || s.count == 0) return;
    const vk::raii::CommandBuffer& cmd = recording.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipeline_);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *layout_, 2, *s.set, {});
    cmd.draw(6, s.count, 0, 0);
    recording.counters.draw("routes", 2ULL * s.count, s.count);
}

} // namespace atlas::passes
