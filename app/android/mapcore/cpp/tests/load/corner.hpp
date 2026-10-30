// Four windows around a corner where four frame cells meet (ENGINE.md one pack per country, step 4: every cell in
// view drawn), for the world's and the app's map's tests: the L14 places on either side of the corner of L10 cells
// 525/336, 526/336, 525/337 and 526/337 (by Amsterdam), each holding one building of four floors, 30 m square, 120 m
// from the corner diagonally, each built anywhere's way (load::build_window) from a made-up answer of Overpass's and
// written to a file of its own, as the app keeps the windows on the phone.
#pragma once

#include "base/pymath/PyMath.hpp"
#include "load/Window.hpp"
#include "tiles/Grid.hpp"
#include "tiles/Store.hpp"

#include <array>
#include <cstdint>
#include <expected>
#include <filesystem>
#include <format>
#include <fstream>
#include <ios>
#include <span>
#include <string>
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
};

/// The corner's windows, written under `folder`; or why one was not built.
[[nodiscard]] inline std::expected<CornerWindows, std::string> corner_windows(const std::filesystem::path& folder) {
    CornerWindows out;
    out.corner = tiles::TileKey(14, 526U << 4U, 337U << 4U);
    const std::array<std::int32_t, 4> box = tiles::tile_box(out.corner);
    out.lat0 = static_cast<double>(box[2]) / 1e7;
    out.lon0 = static_cast<double>(box[1]) / 1e7;
    out.kx = 111320.0 * pymath::cos(pymath::radians(out.lat0));
    std::error_code ignored;
    std::filesystem::create_directories(folder, ignored);
    std::int64_t id = 1;
    for (const auto& [east, south] : {std::pair{0U, 0U}, {1U, 0U}, {0U, 1U}, {1U, 1U}}) {
        const tiles::TileKey k(14, out.corner.x - 1 + east, out.corner.y - 1 + south);
        const double lat = out.lat0 + ((south == 1U ? -120.0 : 120.0) / 110540.0);
        const double lon = out.lon0 + ((east == 1U ? 120.0 : -120.0) / out.kx);
        const double dlat = 15.0 / 110540.0;
        const double dlon = 15.0 / out.kx;
        // a way as Overpass's `out geom` gives it, its points inline (stage P reads a way's geometry, not its nodes)
        // and on OSM's grid of 1e-7 degrees
        const std::string json = std::format(
            R"({{"version":0.6,"elements":[{{"type":"way","id":{0},"nodes":[{1},{2},{3},{4},{1}],)"
            R"("tags":{{"building":"yes","building:levels":"4"}},"geometry":[)"
            R"({{"lat":{5:.7f},"lon":{7:.7f}}},{{"lat":{5:.7f},"lon":{8:.7f}}},{{"lat":{6:.7f},"lon":{8:.7f}}},)"
            R"({{"lat":{6:.7f},"lon":{7:.7f}}},{{"lat":{5:.7f},"lon":{7:.7f}}}]}}]}})",
            id, id + 1, id + 2, id + 3, id + 4, lat - dlat, lat + dlat, lon - dlon, lon + dlon);
        id += 5;
        const auto built = load::build_window(std::as_bytes(std::span(json.data(), json.size())), k, load::place_of(k));
        if (!built) return std::unexpected(std::format("{}/{}: {}", k.x, k.y, built.error().what));
        // a window without a tile joins no cell (tiles::Store::add)
        if (built->features == 0) return std::unexpected(std::format("{}/{}: its building built nothing", k.x, k.y));
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
