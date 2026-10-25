// A theme park's window for the ground's tests of draw_over's park shadows (tests/ground/park_shadows_test.cpp and
// park_shadows_vk_test.cpp): items_vk_test's park, built by the core's stage P into a window's pack.
#pragma once

#include "load/Amsterdam.hpp"
#include "load/Coasters.hpp"
#include "load/Window.hpp"
#include "load/Woods.hpp"
#include "tests/base/require.hpp"
#include "tiles/Grid.hpp"

#include <filesystem>
#include <fstream>
#include <span>
#include <string>

namespace atlas::test {

namespace tl = atlas::tiles;

/// items_vk_test's theme park about the Westerkerk: a steel coaster's loop (a lift hill, a run at layer 2, a brake
/// run), a wooden coaster's run, a monorail and a wood; its pack written to the tests' scratch folder
/// (`name`'s folder).
[[nodiscard]] inline std::string park_window(const std::string& name) {
    const tl::TileKey k = tl::tile_of(14, 523'746'000, 48'840'000);
    const std::string data = R"({"version": 0.6, "osm3s": {"timestamp_osm_base": "2026-10-22T18:00:00Z"}, "elements": [
{"type": "node", "id": 1, "lat": 52.37455, "lon": 4.88385, "tags": {"attraction": "roller_coaster", "name": "Big One"}},
{"type": "node", "id": 2, "lat": 52.37405, "lon": 4.88445, "tags": {"attraction": "roller_coaster", "name": "Woodie"}},
{"type": "way", "id": 10, "tags": {"roller_coaster": "track", "roller_coaster:track": "lift_hill"}, "geometry": [
  {"lat": 52.3744, "lon": 4.8836}, {"lat": 52.3744, "lon": 4.8841}, {"lat": 52.3747, "lon": 4.8841}]},
{"type": "way", "id": 11, "tags": {"roller_coaster": "track", "layer": "2"}, "geometry": [
  {"lat": 52.3747, "lon": 4.8841}, {"lat": 52.37472, "lon": 4.88385}, {"lat": 52.3747, "lon": 4.8836}]},
{"type": "way", "id": 12, "tags": {"roller_coaster": "track", "roller_coaster:track": "brake_run"}, "geometry": [
  {"lat": 52.3747, "lon": 4.8836}, {"lat": 52.3744, "lon": 4.8836}]},
{"type": "way", "id": 13, "tags": {"roller_coaster": "track", "layer": "1"}, "geometry": [
  {"lat": 52.3740, "lon": 4.8842}, {"lat": 52.3741, "lon": 4.8847}, {"lat": 52.3740, "lon": 4.8852}]},
{"type": "way", "id": 14, "tags": {"railway": "monorail"}, "geometry": [
  {"lat": 52.3738, "lon": 4.8830}, {"lat": 52.3739, "lon": 4.8838}]},
{"type": "way", "id": 20, "tags": {"landuse": "forest"}, "geometry": [
  {"lat": 52.3748, "lon": 4.8843}, {"lat": 52.3748, "lon": 4.8852}, {"lat": 52.3753, "lon": 4.8852},
  {"lat": 52.3753, "lon": 4.8843}, {"lat": 52.3748, "lon": 4.8843}]}
]})";
    static const atlas::load::RideProfiles rides = [] {
        atlas::load::RideProfiles r;
        r.heights = {{"Big One", 30.0}, {"Woodie", 20.0}};
        r.wooden = {{"Woodie", true}};
        return r;
    }();
    atlas::load::WindowPlace place = atlas::load::place_of(k);
    place.park = &rides;
    const atlas::load::V2 centre = atlas::load::xz(place.frame, 52.3745, 4.8842);
    place.woods = atlas::load::WoodsAnchor{.cx = centre.x, .cz = centre.z, .reach = 1200.0};
    const auto built = atlas::load::build_window(std::as_bytes(std::span(data.data(), data.size())), k, place);
    ATLAS_REQUIRE_OR_RETURN(std::string(), built.has_value(), built.error().what);
    const std::filesystem::path folder = std::filesystem::path(ATLAS_TEST_SCRATCH) / name;
    std::filesystem::create_directories(folder);
    const std::filesystem::path file = folder / "park.atlas";
    std::ofstream out(file, std::ios::binary | std::ios::trunc);
    out.write(reinterpret_cast<const char*>(built->pack.data()), // NOLINT: the pack's bytes as chars
              static_cast<std::streamsize>(built->pack.size()));
    return file.string();
}

} // namespace atlas::test
