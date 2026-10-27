#include "routes/Routes.hpp"

#include <cmath>
#include <cstring>
#include <format>

namespace atlas::routes {

std::string_view describe(RouteError error) noexcept {
    switch (error) {
    case RouteError::size: return "a chip's pixels are not its width times its height, or a chip is empty";
    case RouteError::large: return "a chip is larger than 2048 px on a side";
    case RouteError::points: return "a route's points are fewer than two, too many, not places, or go back along it";
    case RouteError::chip: return "a route's chip is none of the set's";
    case RouteError::width: return "a route's width or an area's radius is not above 0";
    case RouteError::count: return "more than 64 routes or 256 areas";
    case RouteError::density: return "the density is not from 0.5 to 8";
    }
    return "?";
}

namespace {

/// Whether (latitude, longitude) is a place.
[[nodiscard]] bool place(double latitude, double longitude) noexcept {
    return std::isfinite(latitude) && std::isfinite(longitude) && std::fabs(latitude) <= 90.0 &&
           std::fabs(longitude) <= 360.0;
}

/// Whether `v` is a finite number above 0.
[[nodiscard]] bool positive(double v) noexcept { return std::isfinite(v) && v > 0.0; }

} // namespace

std::expected<std::shared_ptr<const RouteSet>, RouteError> RouteSet::make(std::vector<Area> areas,
                                                                          std::vector<Route> routes,
                                                                          std::vector<Image> chips, float density,
                                                                          std::uint64_t revision) {
    if (!std::isfinite(density) || density < 0.5F || density > 8.0F) return std::unexpected(RouteError::density);
    if (routes.size() > max_routes || areas.size() > max_areas) return std::unexpected(RouteError::count);
    std::uint64_t total = 0;
    for (const Image& chip : chips) {
        if (chip.empty() || chip.pixels.size() != std::uint64_t{chip.width} * chip.height) {
            return std::unexpected(RouteError::size);
        }
        if (chip.width > max_side || chip.height > max_side) return std::unexpected(RouteError::large);
        total += chip.pixels.size();
    }
    for (const Area& a : areas) {
        if (!place(a.latitude, a.longitude)) return std::unexpected(RouteError::points);
        if (!positive(static_cast<double>(a.radius))) return std::unexpected(RouteError::width);
    }
    for (const Route& r : routes) {
        if (r.points.size() < 2 || r.points.size() > max_points) return std::unexpected(RouteError::points);
        double along = 0.0;
        for (const auto& [latitude, longitude, s] : r.points) {
            if (!place(latitude, longitude) || !std::isfinite(s) || s < along) {
                return std::unexpected(RouteError::points);
            }
            along = s;
        }
        if (!positive(static_cast<double>(r.width)) || !std::isfinite(r.travelled)) {
            return std::unexpected(RouteError::width);
        }
        if (r.chip != no_chip && r.chip >= chips.size()) return std::unexpected(RouteError::chip);
    }
    std::shared_ptr<RouteSet> set(new RouteSet());
    set->density_ = density;
    set->revision_ = revision;
    set->texels_.reserve(static_cast<std::size_t>(total));
    set->chip_offsets_.reserve(chips.size());
    for (Image& chip : chips) {
        set->chip_offsets_.push_back(static_cast<std::uint32_t>(set->texels_.size()));
        set->texels_.insert(set->texels_.end(), chip.pixels.begin(), chip.pixels.end());
        chip.pixels.clear();
        chip.pixels.shrink_to_fit(); // the set keeps one copy of each texel
    }
    set->areas_ = std::move(areas);
    set->routes_ = std::move(routes);
    set->chips_ = std::move(chips);
    return std::shared_ptr<const RouteSet>(std::move(set));
}

std::expected<RouteBlock, std::string> read_route_block(std::span<const std::byte> block) {
    std::size_t at = 0;
    const auto take = [&](void* out, std::size_t bytes) {
        if (block.size() - at < bytes) return false;
        if (bytes > 0) std::memcpy(out, block.data() + at, bytes); // an empty vector's data() may be null
        at += bytes;
        return true;
    };
    std::uint32_t magic = 0;
    std::uint32_t areas = 0;
    std::uint32_t routes = 0;
    std::uint32_t chips = 0;
    RouteBlock out;
    if (!take(&magic, 4) || magic != route_block_magic) return std::unexpected("not a block of routes");
    if (!take(&out.density, 4) || !take(&areas, 4) || !take(&routes, 4) || !take(&chips, 4)) {
        return std::unexpected("the block ends before its counts");
    }
    if (areas > RouteSet::max_areas || routes > RouteSet::max_routes || chips > RouteSet::max_routes) {
        return std::unexpected(std::format("{} routes and {} areas are more than may be", routes, areas));
    }
    out.areas.resize(areas);
    for (std::uint32_t i = 0; i < areas; ++i) {
        Area& a = out.areas[i];
        if (!take(&a.latitude, 8) || !take(&a.longitude, 8) || !take(&a.radius, 4) || !take(&a.fill, 4) ||
            !take(&a.stroke, 4)) {
            return std::unexpected(std::format("the block ends in area {}", i));
        }
    }
    out.routes.resize(routes);
    for (std::uint32_t i = 0; i < routes; ++i) {
        Route& r = out.routes[i];
        std::uint32_t points = 0;
        std::uint8_t dashed = 0;
        std::array<std::uint8_t, 3> zero{};
        if (!take(&points, 4) || !take(&r.width, 4) || !take(&r.colour, 4) || !take(&r.faded, 4) ||
            !take(&r.casing, 4) || !take(&dashed, 1) || !take(zero.data(), 3) || !take(&r.travelled, 8) ||
            !take(&r.chip, 4)) {
            return std::unexpected(std::format("the block ends in route {}'s head", i));
        }
        if (dashed > 1 || zero != std::array<std::uint8_t, 3>{}) {
            return std::unexpected(std::format("route {}'s flags are none", i));
        }
        if (points > RouteSet::max_points || block.size() - at < std::size_t{points} * 24) {
            return std::unexpected(std::format("the block ends in route {}'s points", i));
        }
        r.dashed = dashed != 0;
        r.points.resize(points);
        for (std::array<double, 3>& p : r.points) (void)take(p.data(), 24);
    }
    out.chips.resize(chips);
    for (std::uint32_t i = 0; i < chips; ++i) {
        Image& c = out.chips[i];
        if (!take(&c.width, 4) || !take(&c.height, 4) || !take(&c.anchor_x, 4) || !take(&c.anchor_y, 4) ||
            c.width > RouteSet::max_side || c.height > RouteSet::max_side) {
            return std::unexpected(std::format("chip {} does not read", i));
        }
        c.pixels.resize(std::size_t{c.width} * c.height);
        if (!take(c.pixels.data(), c.pixels.size() * 4))
            return std::unexpected(std::format("chip {} does not read", i));
    }
    if (at != block.size()) return std::unexpected("the block runs on past its routes");
    return out;
}

} // namespace atlas::routes
