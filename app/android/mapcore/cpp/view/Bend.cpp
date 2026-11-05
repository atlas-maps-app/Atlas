// The bend's weights (view/Bend.hpp).
#include "view/Bend.hpp"

#include "base/pymath/PyMath.hpp"

namespace atlas::view {

namespace {

/// smoothstep's of log D from 0 at `from` to 1 at `to`.
[[nodiscard]] double eased(double distance, double from, double to) noexcept {
    if (!(distance > from)) return 0.0;
    if (distance >= to) return 1.0;
    const double t = pymath::log(distance / from) / pymath::log(to / from);
    return t * t * (3.0 - (2.0 * t));
}

} // namespace

double drop_weight(double distance) noexcept { return eased(distance, bend_from, bend_dropped); }

double sphere_weight(double distance) noexcept { return eased(distance, bend_dropped, bend_sphere); }

double drop_of(double distance) noexcept { return drop_weight(distance) / (2.0 * earth_radius); }

} // namespace atlas::view
