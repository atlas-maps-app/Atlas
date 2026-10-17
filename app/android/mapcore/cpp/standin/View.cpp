// The stand-in's camera (standin/View.hpp).
#include "standin/View.hpp"

#include "standin/Trig.hpp"

#include <algorithm>
#include <array>
#include <cmath>

namespace atlas::standin {

namespace {

using Row = std::array<double, 4>;
using Matrix = std::array<Row, 4>;

[[nodiscard]] Matrix multiply(const Matrix& a, const Matrix& b) noexcept {
    Matrix m{};
    for (std::size_t i = 0; i < 4; ++i) {
        for (std::size_t j = 0; j < 4; ++j) {
            double s = 0;
            for (std::size_t k = 0; k < 4; ++k) s += a[i][k] * b[k][j];
            m[i][j] = s;
        }
    }
    return m;
}

[[nodiscard]] std::array<float, 4> to_floats(const Row& r) noexcept {
    return {static_cast<float>(r[0]), static_cast<float>(r[1]), static_cast<float>(r[2]), static_cast<float>(r[3])};
}

[[nodiscard]] double dot3(const double a[3], const double b[3]) noexcept {
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
}

constexpr double metres_per_degree_latitude = 110574.0;
constexpr double metres_per_degree_longitude = 111320.0;

} // namespace

ViewBasis basis_of(const style::CameraState& camera) noexcept {
    ViewBasis v{};
    const double cos_lat = sincos(radians(origin_latitude)).cos;
    v.target[0] = (camera.longitude - origin_longitude) * cos_lat * metres_per_degree_longitude;
    v.target[1] = 0.0;
    v.target[2] = -(camera.latitude - origin_latitude) * metres_per_degree_latitude;
    const SinCos b = sincos(radians(camera.bearing));
    const SinCos p = sincos(radians(std::clamp(camera.pitch, 0.0, 89.0)));
    // Heading (sin b, 0, -cos b); right (cos b, 0, sin b); forward tilts from straight down towards the heading.
    v.forward[0] = b.sin * p.sin;
    v.forward[1] = -p.cos;
    v.forward[2] = -b.cos * p.sin;
    v.right[0] = b.cos;
    v.right[1] = 0.0;
    v.right[2] = b.sin;
    // up = right × forward; down = -up.
    const double up[3] = {v.right[1] * v.forward[2] - v.right[2] * v.forward[1],
                          v.right[2] * v.forward[0] - v.right[0] * v.forward[2],
                          v.right[0] * v.forward[1] - v.right[1] * v.forward[0]};
    v.down[0] = -up[0];
    v.down[1] = -up[1];
    v.down[2] = -up[2];
    for (std::size_t i = 0; i < 3; ++i) v.eye[i] = v.target[i] - v.forward[i] * camera.distance;
    return v;
}

FrameUniforms uniforms_of(const style::CameraState& camera, const City& city, std::uint32_t width, std::uint32_t height,
                          std::uint32_t quarter_turns, double seconds) noexcept {
    FrameUniforms u{};
    const ViewBasis v = basis_of(camera);
    // The view's own size (the output's, turned back by the pre-rotation).
    const bool turned = (quarter_turns & 1U) != 0;
    const double w = turned ? height : width;
    const double h = turned ? width : height;
    const double density = camera.density > 0 ? camera.density : 1.0;
    const double focal = (h / 2.0) / tan(radians(18.0));
    const double cx = camera.principal_x > 0 ? camera.principal_x * density : w / 2.0;
    const double cy = camera.principal_y > 0 ? camera.principal_y * density : h / 2.0;
    const Matrix view{{{v.right[0], v.right[1], v.right[2], -dot3(v.right, v.eye)},
                       {v.down[0], v.down[1], v.down[2], -dot3(v.down, v.eye)},
                       {v.forward[0], v.forward[1], v.forward[2], -dot3(v.forward, v.eye)},
                       {0, 0, 0, 1}}};
    // Pixels from the principal point, then clip space; z is the painter's slot, written by each vertex stage.
    const Matrix projection{{{2.0 * focal / w, 0, 2.0 * cx / w - 1.0, 0},
                             {0, 2.0 * focal / h, 2.0 * cy / h - 1.0, 0},
                             {0, 0, 0, 0},
                             {0, 0, 1, 0}}};
    const Matrix clip = multiply(projection, view);
    u.view0 = to_floats(clip[0]);
    u.view1 = to_floats(clip[1]);
    u.view2 = to_floats(clip[2]);
    u.view3 = to_floats(clip[3]);
    // The water's box: the lower part of the screen, its own image; the mirrored camera maps it to the whole image.
    constexpr std::array<double, 4> water{0.08, 0.52, 0.92, 0.88};
    const double x0 = water[0] * 2 - 1;
    const double x1 = water[2] * 2 - 1;
    const double y0 = water[1] * 2 - 1;
    const double y1 = water[3] * 2 - 1;
    const double sx = 2.0 / (x1 - x0);
    const double sy = 2.0 / (y1 - y0);
    const Matrix box{{{sx, 0, 0, -sx * (x0 + x1) / 2}, {0, sy, 0, -sy * (y0 + y1) / 2}, {0, 0, 1, 0}, {0, 0, 0, 1}}};
    const Matrix mirror = multiply(box, clip);
    u.mirror0 = to_floats(mirror[0]);
    u.mirror1 = to_floats(mirror[1]);
    u.mirror2 = to_floats(mirror[2]);
    u.mirror3 = to_floats(mirror[3]);
    u.water = {static_cast<float>(water[0]), static_cast<float>(water[1]), static_cast<float>(water[2]),
               static_cast<float>(water[3])};

    u.eye = {static_cast<float>(v.eye[0]), static_cast<float>(v.eye[1]), static_cast<float>(v.eye[2]),
             static_cast<float>(seconds)};
    // An afternoon sun from the south-west.
    const SinCos az = sincos(radians(220.0));
    const SinCos el = sincos(radians(38.0));
    const double sun[3] = {el.cos * az.sin, el.sin, -el.cos * az.cos};
    u.sun = {static_cast<float>(sun[0]), static_cast<float>(sun[1]), static_cast<float>(sun[2]), 0.55F};
    u.shadow = {static_cast<float>(-sun[0] / sun[1]), static_cast<float>(-sun[2] / sun[1]), 0.5F, 1200.0F};
    u.screen = {static_cast<float>(width), static_cast<float>(height), 1.0F / static_cast<float>(width),
                1.0F / static_cast<float>(height)};
    const SinCos r = sincos(radians(90.0 * static_cast<double>(quarter_turns & 3U)));
    u.rotation = {static_cast<float>(r.cos), static_cast<float>(-r.sin), static_cast<float>(r.sin),
                  static_cast<float>(r.cos)};
    u.vignette = {0.22F, 0.42F, 0.95F, 0.0F};
    // 17 haze stops from the top: a pale blue-grey that thins out by 55 % of the height.
    for (std::size_t i = 0; i < 17; ++i) {
        const double y = static_cast<double>(i) / 16.0;
        const double a = y < 0.55 ? 0.30 * (1.0 - y / 0.55) * (1.0 - y / 0.55) : 0.0;
        u.haze_colour[i] = {0.82F, 0.86F, 0.90F, static_cast<float>(a)};
        u.haze_stop[i / 4][i % 4] = static_cast<float>(y);
    }
    for (std::size_t i = 17; i < 20; ++i) u.haze_stop[i / 4][i % 4] = 1.0F;
    u.counts = {static_cast<std::uint32_t>(city.boxes.size()), static_cast<std::uint32_t>(city.trees.size()),
                city.items(), 0U};
    return u;
}

style::CameraState board_view(std::uint32_t width, std::uint32_t height, double density, double pitch, double bearing,
                              double distance) noexcept {
    style::CameraState c{};
    c.version = style::camera_state_version;
    c.flags = 0;
    c.sequence = 1;
    c.latitude = origin_latitude;
    c.longitude = origin_longitude;
    c.bearing = bearing;
    c.pitch = pitch;
    c.distance = distance;
    c.density = density;
    c.width_px = width;
    c.height_px = height;
    c.width_dp = static_cast<std::uint32_t>(std::lround(width / density));
    c.height_dp = static_cast<std::uint32_t>(std::lround(height / density));
    c.principal_x = c.width_dp / 2.0;
    c.principal_y = c.height_dp * 0.48;
    c.profile = style::Profile::none;
    return c;
}

} // namespace atlas::standin
