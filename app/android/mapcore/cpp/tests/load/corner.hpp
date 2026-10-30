// Four windows around a corner where four frame cells meet (ENGINE.md one pack per country, step 4: every cell in
// view drawn), for the world's and the app's map's tests: the L14 places on either side of the corner of L10 cells
// 525/336, 526/336, 525/337 and 526/337 (by Amsterdam), each built anywhere's way (load::build_window) from a made-up
// answer of Overpass's and written to a file of its own, as the app keeps the windows on the phone. By default each
// holds one building of four floors, 30 m square, 120 m from the corner diagonally; a test may make up its own ways.
#pragma once

#include "base/pymath/PyMath.hpp"
#include "load/Window.hpp"
#include "tiles/Grid.hpp"
#include "tiles/Store.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <expected>
#include <filesystem>
#include <format>
#include <fstream>
#include <functional>
#include <ios>
#include <span>
#include <string>
#include <string_view>
#include <system_error>
#include <utility>
#include <vector>

namespace atlas::test {

/// The corner's windows, north-west, north-east, south-west, then south-east.
struct CornerWindows {
    /// The south-east window's place, whose north-west corner is the cells' corner, and that corner (degrees).
    tiles::TileKey corner;
    double lat0 = 0;
    double lon0 = 0;
    /// Metres a degree of longitude there (111,320 · cos lat0).
    double kx = 0;
    /// Each window's id (the app's name for it, MapCore.PackRef: `window/14/<x>/<y>`) and its file.
    std::vector<std::pair<std::string, std::filesystem::path>> windows;
    /// Each window's place, and its frame cell's pack (tiles::Store::cell_id), which the store draws it from.
    std::vector<tiles::TileKey> places;
    std::vector<std::string> cells;

    /// The point `east` and `north` metres of the corner: {lat, lon}.
    [[nodiscard]] std::array<double, 2> at(double east, double north) const noexcept {
        return {lat0 + (north / 110540.0), lon0 + (east / kx)};
    }
};

/// A way as Overpass's `out geom` gives it (its points inline, on OSM's grid of 1e-7 degrees: stage P reads a way's
/// geometry, not its nodes), its id and its nodes' from `id` on (advanced past them), `tags` a JSON object's members,
/// its points metres east and north of the corner (a closed way's last point its first).
[[nodiscard]] inline std::string made_way(std::int64_t& id, std::string_view tags,
                                          std::span<const std::array<double, 2>> points, const CornerWindows& c) {
    const std::int64_t way = id++;
    const std::int64_t first = id;
    std::string nodes;
    std::string geometry;
    for (std::size_t i = 0; i < points.size(); ++i) {
        const bool closing = i > 0 && i + 1 == points.size() && points[i] == points[0];
        nodes += std::format("{}{}", i == 0 ? "" : ",", closing ? first : id++);
        const std::array<double, 2> p = c.at(points[i][0], points[i][1]);
        geometry += std::format(R"({}{{"lat":{:.7f},"lon":{:.7f}}})", i == 0 ? "" : ",", p[0], p[1]);
    }
    return std::format(R"({{"type":"way","id":{},"nodes":[{}],"tags":{{{}}},"geometry":[{}]}})", way, nodes, tags,
                       geometry);
}

/// A building over the rectangle [west, east] × [south, north] (metres east and north of the corner), `tags` its tags.
[[nodiscard]] inline std::string made_building(std::int64_t& id, std::string_view tags, double west, double south,
                                               double east, double north, const CornerWindows& c) {
    const std::array<std::array<double, 2>, 5> ring{
        {{west, south}, {east, south}, {east, north}, {west, north}, {west, south}}};
    return made_way(id, tags, ring, c);
}

/// The ways of window `window` (0 north-west, 1 north-east, 2 south-west, 3 south-east) as a JSON list's members, ids
/// from `id` on; empty: the default building.
using CornerWays = std::function<std::string(std::size_t window, std::int64_t& id, const CornerWindows& c)>;

/// The corner's windows, written under `folder`, each from `ways` (or its default building); or why one was not built.
[[nodiscard]] inline std::expected<CornerWindows, std::string> corner_windows(const std::filesystem::path& folder,
                                                                              const CornerWays& ways = {}) {
    CornerWindows out;
    out.corner = tiles::TileKey(14, 526U << 4U, 337U << 4U);
    const std::array<std::int32_t, 4> box = tiles::tile_box(out.corner);
    out.lat0 = static_cast<double>(box[2]) / 1e7;
    out.lon0 = static_cast<double>(box[1]) / 1e7;
    out.kx = 111320.0 * pymath::cos(pymath::radians(out.lat0));
    std::error_code ignored;
    std::filesystem::create_directories(folder, ignored);
    std::int64_t id = 1;
    std::size_t window = 0;
    for (const auto& [east, south] : {std::pair{0U, 0U}, {1U, 0U}, {0U, 1U}, {1U, 1U}}) {
        const tiles::TileKey k(14, out.corner.x - 1 + east, out.corner.y - 1 + south);
        std::string elements = ways ? ways(window, id, out) : std::string();
        if (elements.empty()) {
            // the default building: four floors, 30 m square, 120 m from the corner diagonally
            const double x = east == 1U ? 120.0 : -120.0;
            const double z = south == 1U ? -120.0 : 120.0;
            elements = made_building(id, R"("building":"yes","building:levels":"4")", x - 15.0, z - 15.0, x + 15.0,
                                     z + 15.0, out);
        }
        ++window;
        const std::string json = std::format(R"({{"version":0.6,"elements":[{}]}})", elements);
        const auto built = load::build_window(std::as_bytes(std::span(json.data(), json.size())), k, load::place_of(k));
        if (!built) return std::unexpected(std::format("{}/{}: {}", k.x, k.y, built.error().what));
        // a window without a tile joins no cell (tiles::Store::add)
        if (built->features == 0) return std::unexpected(std::format("{}/{}: its ways built nothing", k.x, k.y));
        const std::filesystem::path file = folder / std::format("{}-{}.atlas", k.x, k.y);
        std::ofstream o(file, std::ios::binary | std::ios::trunc);
        o.write(reinterpret_cast<const char*>(built->pack.data()), // NOLINT: the pack's bytes as chars
                static_cast<std::streamsize>(built->pack.size()));
        o.close();
        if (!o) return std::unexpected(std::format("{}/{}: not written to {}", k.x, k.y, file.string()));
        out.windows.emplace_back("window/" + load::place_of(k).name, file);
        out.places.push_back(k);
        out.cells.push_back(
            tiles::Store::cell_id(tiles::TileKey(tiles::Store::cell_level, k.x >> (14U - tiles::Store::cell_level),
                                                 k.y >> (14U - tiles::Store::cell_level))));
    }
    return out;
}

} // namespace atlas::test
