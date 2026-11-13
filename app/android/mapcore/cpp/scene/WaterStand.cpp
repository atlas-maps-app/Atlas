// Where a water's name stands when its own point does not show (scene/WaterStand.hpp).
#include "scene/WaterStand.hpp"

#include "base/pymath/PyMath.hpp"

#include <algorithm>
#include <cmath>
#include <numbers>

namespace atlas::scene {

namespace {

constexpr double metres_a_degree_lat = 110'540.0;
constexpr double metres_a_degree_lon = 111'320.0;
constexpr double last_latitude = 85.0;
/// The least cosine of a latitude a degree of longitude is measured by.
constexpr double least_cosine = 0.05;

} // namespace

void WaterStands::begin(const StandFrame& frame, const Point* point, const Water* water) {
    frame_ = frame;
    point_ = point;
    water_ = water;
}

bool WaterStands::on_frame(double lat, double lon, double clear) const {
    if (point_ == nullptr) return false;
    const std::optional<view::Vec2> p = (*point_)(lat, lon);
    return p && p->x >= clear && p->y >= clear && p->x <= frame_.width - clear && p->y <= frame_.height - clear;
}

std::optional<NamePlace> WaterStands::find(const WorldName& water, double half, double tall) {
    if (point_ == nullptr || water_ == nullptr || !(water.extent > 0.0) || !(frame_.step > 0.0)) return std::nullopt;
    if (frame_.width - (2.0 * half) < 0.0 || frame_.height - (2.0 * tall) < 0.0) return std::nullopt;
    const double far = radii * pymath::sqrt(water.extent / std::numbers::pi);
    // too far from where its name could stand to reach it (the file's head)
    if (frame_.px > 0.0) {
        if (const std::optional<view::Vec2> own = (*point_)(water.lat, water.lon)) {
            const double dx = std::max({half - own->x, own->x - (frame_.width - half), 0.0});
            const double dy = std::max({tall - own->y, own->y - (frame_.height - tall), 0.0});
            const double reach = 2.0 * far * frame_.px;
            if ((dx * dx) + (dy * dy) > reach * reach) return std::nullopt;
        }
    }
    // the ground about the water's point, metres east and north of it, and the way from it to the camera's target
    const double east_metres = metres_a_degree_lon * std::max(pymath::cos(pymath::radians(water.lat)), least_cosine);
    double dlon = frame_.lon - water.lon;
    if (dlon > 180.0) dlon -= 360.0;
    if (dlon < -180.0) dlon += 360.0;
    const double to_east = dlon * east_metres;
    const double to_north = (frame_.lat - water.lat) * metres_a_degree_lat;
    const double away = pymath::sqrt((to_east * to_east) + (to_north * to_north));
    if (!(away > 0.0)) return std::nullopt;
    // a line's step: the frame's, or as long as lets `most` samples reach `far`
    const double step = std::max(frame_.step, far / static_cast<double>(most));
    const int count = std::min(most, static_cast<int>(std::ceil(far / step)));
    std::optional<NamePlace> best;
    int nearest = count + 1;
    for (int ray = 0; ray < rays; ++ray) {
        constexpr int middle = (rays - 1) / 2;
        const double turn = pymath::radians(spread * static_cast<double>(ray - middle));
        const double c = pymath::cos(turn);
        const double s = pymath::sin(turn);
        const double east = ((to_east * c) - (to_north * s)) / away;
        const double north = ((to_east * s) + (to_north * c)) / away;
        // (no line's end beyond the nearest so far can be nearer)
        for (int i = 1; i <= count && i < nearest; ++i) {
            const double along = step * static_cast<double>(i);
            const double lat = water.lat + (north * along / metres_a_degree_lat);
            const double lon = water.lon + (east * along / east_metres);
            if (std::abs(lat) > last_latitude) break;
            ++samples_;
            const std::optional<bool> wet = (*water_)(lat, lon);
            if (wet && !*wet) break; // the shore: the line has left the water
            if (!wet) continue;
            const std::optional<view::Vec2> p = (*point_)(lat, lon);
            if (!p || p->x < half || p->x > frame_.width - half || p->y < tall || p->y > frame_.height - tall) continue;
            best = NamePlace{.lat = lat, .lon = lon, .stand = true};
            nearest = i;
            break;
        }
    }
    return best;
}

} // namespace atlas::scene
