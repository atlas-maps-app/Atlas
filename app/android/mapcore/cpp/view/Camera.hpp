// The engine's camera (plan-vector D4): render/city.py's Camera, as the port computes it (R/City.kt Camera, the
// anchor's SvgCamera), in double with the port's numerics (pymath's sin, cos and tan; IEEE square roots and divisions
// in the port's order, contraction off), so every value the frame derives from it is the port's. It is made from a
// camera's settings (ViewLadder.place's cfg: target, yaw, pitch, distance, field of view, the frame's W × H CSS px and
// its principal point; Amsterdam.cameraOf) and drawn on a device: CSS px times the density about the frame's top-left
// corner are the view's device px (plan-gpu D3's rule), turned by the output's pre-rotation.
//
// The GPU gets it in float, relative to the eye (block(), shaders/shared/camera.slang's FrameCamera): the basis, the
// principal point and the focal length; each tile's place relative to the eye is composed here in double
// (tile_place()), so float positions keep their 0.1 mm wherever the tile lies.
#pragma once

#include "base/hlsl.hpp"
#include "frame/FrameBlock.hpp"

#include <cstdint>
#include <optional>

namespace atlas::view {

/// A point or a vector of the engine's world: x east, y up, z south, metres (or CSS px for screen points).
struct Vec3 {
    double x = 0;
    double y = 0;
    double z = 0;
    constexpr Vec3() noexcept = default;
    constexpr Vec3(double east, double up, double south) noexcept : x(east), y(up), z(south) {}
    friend constexpr bool operator==(const Vec3&, const Vec3&) = default;
};

/// A screen point.
struct Vec2 {
    double x = 0;
    double y = 0;
    constexpr Vec2() noexcept = default;
    constexpr Vec2(double across, double down) noexcept : x(across), y(down) {}
    friend constexpr bool operator==(const Vec2&, const Vec2&) = default;
};

/// The engine's field of view, degrees (LiveCamera.FOV).
inline constexpr double field_of_view = 36.0;

/// The engine's near plane, metres (Camera's `near`).
inline constexpr double near_plane = 0.6;

/// A camera's settings, as a cfg names them (Amsterdam.cameraOf): target, yaw and pitch in degrees, the eye's distance
/// from the target in metres, the vertical field of view, the frame's width and height in CSS px, the principal point
/// (the frame's middle when absent) and the near plane.
struct CameraSpec {
    Vec3 target;
    double yaw = 0;
    double pitch = 0;
    double dist = 0;
    double fov = field_of_view;
    double width = 0;
    double height = 0;
    std::optional<double> cx;
    std::optional<double> cy;
    double near = near_plane;
};

/// render/city.py's Camera.
class Camera {
public:
    explicit Camera(const CameraSpec& spec) noexcept;

    /// Camera coordinates (right, up, forward) of world point p: Camera.cam.
    [[nodiscard]] Vec3 cam(Vec3 p) const noexcept;
    /// The screen point (CSS px) of camera coordinates k: Camera.pc.
    [[nodiscard]] Vec2 pc(Vec3 k) const noexcept;
    /// The screen point of world point p, or nothing when it lies nearer than the near plane: Camera.proj.
    [[nodiscard]] std::optional<Vec2> proj(Vec3 p) const noexcept;
    /// The depth of world point p along the view: Camera.depth.
    [[nodiscard]] double depth(Vec3 p) const noexcept { return cam(p).z; }
    /// The depth at which screen row sy meets the plane y = gy, or nothing at or above the horizon:
    /// Camera.ground_depth.
    [[nodiscard]] std::optional<double> ground_depth(double sy, double gy = 0.0) const noexcept;
    /// The screen row of the horizon: Camera.horizon_y.
    [[nodiscard]] double horizon_y() const noexcept;

    [[nodiscard]] const CameraSpec& spec() const noexcept { return spec_; }
    /// The eye: Camera.C.
    [[nodiscard]] const Vec3& eye() const noexcept { return eye_; }
    /// The view direction, right and up unit vectors: Camera.f, r, u.
    [[nodiscard]] const Vec3& forward() const noexcept { return f_; }
    [[nodiscard]] const Vec3& right() const noexcept { return r_; }
    [[nodiscard]] const Vec3& up() const noexcept { return u_; }
    /// Pixels per unit of tangent: (H / 2) / tan(fov / 2).
    [[nodiscard]] double focal() const noexcept { return focal_; }
    [[nodiscard]] double cx() const noexcept { return cx_; }
    [[nodiscard]] double cy() const noexcept { return cy_; }
    [[nodiscard]] double width() const noexcept { return spec_.width; }
    [[nodiscard]] double height() const noexcept { return spec_.height; }
    [[nodiscard]] double near() const noexcept { return spec_.near; }

private:
    CameraSpec spec_;
    double focal_ = 0;
    double cx_ = 0;
    double cy_ = 0;
    Vec3 eye_;
    Vec3 f_;
    Vec3 r_;
    Vec3 u_;
};

/// Where a frame is drawn: device px per CSS px, the view's size in device px (unturned) and the output's quarter
/// turns (its pre-rotation: the surface's current transform, 0 on hosts).
struct Device {
    double density = 1;
    std::uint32_t width_px = 0;
    std::uint32_t height_px = 0;
    std::uint32_t quarter_turns = 0;
};

/// The frame's view: the engine camera on a device.
class View {
public:
    View(const CameraSpec& spec, const Device& device) noexcept : camera_(spec), device_(device) {}

    [[nodiscard]] const Camera& camera() const noexcept { return camera_; }
    [[nodiscard]] const Device& device() const noexcept { return device_; }

    /// The view's device point of CSS point p: p times the density, from the frame's top-left corner.
    [[nodiscard]] Vec2 device_point(Vec2 p) const noexcept { return {p.x * device_.density, p.y * device_.density}; }
    /// The view's device point of world point p (Camera.pc times the density), or nothing nearer than the near plane.
    [[nodiscard]] std::optional<Vec2> device_proj(Vec3 p) const noexcept;

    /// The camera as the GPU reads it (camera.slang's FrameCamera): in float, relative to the eye. `rest` turns the
    /// snapping at rest on; `engine` the renders' radius cut.
    [[nodiscard]] shaders::FrameCamera block(bool rest, bool engine, std::uint64_t frame) const noexcept;

    /// The place relative to the eye of a tile whose south-west corner at street level is `origin` in the frame's
    /// metres, `scale` frame metres per tile metre along x (camera.slang's TilePlace), with fade `fade`: composed in
    /// double, then rounded to float.
    [[nodiscard]] shaders::TilePlace tile_place(Vec3 origin, double scale, double fade) const noexcept;

private:
    Camera camera_;
    Device device_;
};

/// The pre-rotation of `quarter_turns` (0-3) as camera.slang's turn: how the view's normalised coordinates map onto
/// the output's.
[[nodiscard]] hlsl::float4 turn_of(std::uint32_t quarter_turns) noexcept;

} // namespace atlas::view
