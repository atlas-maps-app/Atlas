// The frame drawn (view/Frames.hpp).
#include "view/Frames.hpp"

#include "base/pymath/PyMath.hpp"

namespace atlas::view {

Vec3 convert(const GroundFrame& from, const GroundFrame& to, Vec3 p) noexcept {
    const double lon = from.lon0 + p.x / from.kx;
    const double lat = from.lat0 - p.z / from.kz;
    return {to.x(lat, lon), p.y, to.z(lat, lon)};
}

void Frames::set(const GroundFrame& frame, double seconds) noexcept {
    if (current_ && *current_ == frame) return;
    previous_ = current_;
    current_ = frame;
    changed_ = previous_ ? seconds : -1.0e9;
}

double Frames::weight(double seconds) const noexcept {
    if (!previous_) return 1.0;
    return pymath::max(0.0, pymath::min(1.0, (seconds - changed_) / frame_blend_seconds));
}

Placement Frames::place(const GroundFrame& from, Vec3 origin, Vec3 target, double seconds) const noexcept {
    if (!current_) return {.origin = origin, .scale = 1.0};
    const GroundFrame& to = *current_;
    const Vec3 o = convert(from, to, origin);
    double scale = to.kx / from.kx;
    const double w = weight(seconds);
    if (w >= 1.0 || !previous_) return {.origin = o, .scale = scale};
    // Geometry about the target keeps the old frame's x scale at the change and eases to the new one's.
    const double s = previous_->kx / to.kx + (1.0 - previous_->kx / to.kx) * w;
    scale *= s;
    return {.origin = {target.x + (o.x - target.x) * s, o.y, o.z}, .scale = scale};
}

} // namespace atlas::view
