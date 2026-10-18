// The engine's camera (view/Camera.hpp): render/city.py:20-69, line for line in the port's order.
#include "view/Camera.hpp"

#include "base/pymath/PyMath.hpp"

#include <cmath>

namespace atlas::view {

namespace {

/// City.vdot: summed left to right.
[[nodiscard]] double vdot(const Vec3& a, const Vec3& b) noexcept { return a.x * b.x + a.y * b.y + a.z * b.z; }

/// City.vnorm: a over its length (PyMath.sqrt, an IEEE square root).
[[nodiscard]] Vec3 vnorm(const Vec3& a) noexcept {
    const double l = std::sqrt(vdot(a, a));
    return {a.x / l, a.y / l, a.z / l};
}

/// City.vcross.
[[nodiscard]] Vec3 vcross(const Vec3& a, const Vec3& b) noexcept {
    return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}

[[nodiscard]] float f32(double v) noexcept { return static_cast<float>(v); }

} // namespace

Camera::Camera(const CameraSpec& spec) noexcept
    : spec_(spec), focal_((spec.height / 2) / pymath::tan(pymath::radians(spec.fov) / 2)),
      cx_(spec.cx.value_or(spec.width / 2)), cy_(spec.cy.value_or(spec.height / 2)) {
    // render/city.py:21 Camera.__init__
    const double yr = pymath::radians(spec.yaw);
    const double pr = pymath::radians(spec.pitch);
    const Vec3 d{pymath::sin(yr) * pymath::cos(pr), -pymath::sin(pr), -pymath::cos(yr) * pymath::cos(pr)};
    eye_ = {spec.target.x - d.x * spec.dist, spec.target.y - d.y * spec.dist, spec.target.z - d.z * spec.dist};
    f_ = vnorm(d);
    r_ = vnorm({-f_.z, 0.0, f_.x});
    u_ = vcross(r_, f_);
}

Vec3 Camera::cam(Vec3 p) const noexcept {
    // render/city.py:33 cam
    const Vec3 d{p.x - eye_.x, p.y - eye_.y, p.z - eye_.z};
    return {vdot(d, r_), vdot(d, u_), vdot(d, f_)};
}

Vec2 Camera::pc(Vec3 k) const noexcept {
    // render/city.py:36 pc
    return {cx_ + focal_ * k.x / k.z, cy_ - focal_ * k.y / k.z};
}

std::optional<Vec2> Camera::proj(Vec3 p) const noexcept {
    // render/city.py:38 proj
    const Vec3 k = cam(p);
    if (k.z < spec_.near) return std::nullopt;
    return pc(k);
}

std::optional<double> Camera::ground_depth(double sy, double gy) const noexcept {
    // render/city.py:61 ground_depth
    const double b = (cy_ - sy) / focal_;
    const double den = f_.y + b * u_.y;
    if (den >= -1e-6) return std::nullopt;
    return -(eye_.y - gy) / den;
}

double Camera::horizon_y() const noexcept {
    // render/city.py:67 horizon_y
    const double b = -f_.y / u_.y;
    return cy_ - b * focal_;
}

std::optional<Vec2> View::device_proj(Vec3 p) const noexcept {
    const std::optional<Vec2> s = camera_.proj(p);
    if (!s) return std::nullopt;
    return device_point(*s);
}

hlsl::float4 turn_of(std::uint32_t quarter_turns) noexcept {
    // The view's normalised coordinates onto the output's (the stand-in's convention, standin/StandIn.cpp's pills).
    switch (quarter_turns & 3U) {
    case 1: return {0.0F, -1.0F, 1.0F, 0.0F};
    case 2: return {-1.0F, 0.0F, 0.0F, -1.0F};
    case 3: return {0.0F, 1.0F, -1.0F, 0.0F};
    default: return {1.0F, 0.0F, 0.0F, 1.0F};
    }
}

shaders::FrameCamera View::block(bool rest, bool engine, std::uint64_t frame) const noexcept {
    const Camera& c = camera_;
    shaders::FrameCamera b{};
    b.right = {f32(c.right().x), f32(c.right().y), f32(c.right().z), f32(c.cx())};
    b.up = {f32(c.up().x), f32(c.up().y), f32(c.up().z), f32(c.cy())};
    b.forward = {f32(c.forward().x), f32(c.forward().y), f32(c.forward().z), f32(c.focal())};
    b.frame = {f32(c.width()), f32(c.height()), f32(device_.density), f32(c.near())};
    const double w = device_.width_px > 0 ? static_cast<double>(device_.width_px) : 1.0;
    const double h = device_.height_px > 0 ? static_cast<double>(device_.height_px) : 1.0;
    b.device = {f32(2.0 / w), f32(2.0 / h), f32(1.0 / device_.density), 0.0F};
    b.turn = turn_of(device_.quarter_turns);
    b.flags = {(rest ? shaders::CAMERA_REST : 0U) | (engine ? shaders::CAMERA_ENGINE_MODE : 0U),
               static_cast<std::uint32_t>(frame & 0xFFFF'FFFFU), 0U, 0U};
    return b;
}

shaders::TilePlace View::tile_place(Vec3 origin, double scale, double fade) const noexcept {
    const Vec3& e = camera_.eye();
    shaders::TilePlace t{};
    t.offset = {f32(origin.x - e.x), f32(origin.y - e.y), f32(origin.z - e.z), 0.0F};
    t.scale = {f32(scale), 1.0F, 1.0F, f32(fade)};
    return t;
}

} // namespace atlas::view
