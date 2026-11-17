#include "geometry/Seams.hpp"

#include "load/CleanAbi.h"

#include <cstddef>
#include <type_traits>

namespace atlas::geometry {

static_assert(std::is_standard_layout_v<V2> && sizeof(V2) == 2 * sizeof(double), "V2 is x then z, two doubles");

std::vector<std::uint8_t> seams(std::span<const V2> ring) {
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-reinterpret-cast): V2 is two doubles, x then z
    return seams_xz({reinterpret_cast<const double*>(ring.data()), 2 * ring.size()});
}

std::vector<std::uint8_t> seams_xz(std::span<const double> xz) {
    std::vector<std::uint8_t> out(xz.size() / 2);
    if (atlas_clean_seams(xz.data(), out.size(), out.data()) == 0) out.clear();
    return out;
}

} // namespace atlas::geometry
