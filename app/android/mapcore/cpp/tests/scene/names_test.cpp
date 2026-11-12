// The world's names (ENGINE.md step 5, "Names"; scene/WorldNames.hpp): a name's look by the camera's distance, the
// pick by rank from the tiles a frame draws (each place once, from the cells its tile names), its hysteresis (a name
// picked stays while it stands among the first by rank; one that leaves is handed on for a fade's length), its pace
// (at most ten times a second while the camera moves, once at rest), and where a place stands on the bent ground,
// none behind the horizon.
#include "base/pymath/PyMath.hpp"
#include "scene/WorldNames.hpp"
#include "scene/WorldTiles.hpp"
#include "tests/base/require.hpp"
#include "view/Bend.hpp"
#include "view/Camera.hpp"
#include "view/Ladder.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <cmath>
#include <limits>
#include <numbers>
#include <string>
#include <vector>

namespace {

using namespace atlas;
using scene::NameKind;
using scene::NameStyle;
using scene::PickedName;
using scene::WorldName;

constexpr double radian = std::numbers::pi / 180.0;
constexpr view::Device device{.density = 2.625, .width_px = 1079, .height_px = 2399, .quarter_turns = 0};

[[nodiscard]] view::CameraSpec spec_of(double pitch, double dist) {
    return {.target = {0, 0, 0}, .yaw = 0.0, .pitch = pitch, .dist = dist, .width = 411.0, .height = 914.0};
}

[[nodiscard]] view::GroundFrame centred(double lat, double lon) {
    return {.lat0 = lat, .lon0 = lon, .kx = 111'320.0 * pymath::cos(lat * radian), .kz = 110'540.0};
}

[[nodiscard]] WorldName name(std::string text, NameKind kind, double people, double lat, double lon,
                             std::uint8_t cell = 0, bool capital = false) {
    return {.kind = kind,
            .capital = capital,
            .cell = cell,
            .people = people,
            .lat = lat,
            .lon = lon,
            .text = std::move(text)};
}

/// A frame that draws `tiles`, each naming the places of `cells`.
[[nodiscard]] scene::WorldFrame frame_of(std::span<const scene::WorldTile> tiles, std::uint64_t cells = ~0ULL) {
    scene::WorldFrame f;
    for (const scene::WorldTile& t : tiles) {
        f.draws.push_back({.tile = &t, .cells = ~0ULL, .fade = 1.0F, .light_cells = cells});
    }
    return f;
}

[[nodiscard]] std::vector<std::string> texts(std::span<const PickedName> picked, bool leaving = false) {
    std::vector<std::string> out;
    for (const PickedName& p : picked) {
        if (p.leaving == leaving) out.emplace_back(p.text);
    }
    return out;
}

const scene::WorldNames::InView everywhere = [](double, double) { return true; };

} // namespace

TEST_CASE("scene: a name's look is its kind's up to 6,000 km, the Globe board's beyond, none beyond 20,000 km") {
    using scene::name_style;
    CHECK(name_style(NameKind::city, 100'000.0) == NameStyle::city);
    CHECK(name_style(NameKind::country, 100'000.0) == NameStyle::city);
    CHECK(name_style(NameKind::town, 100'000.0) == NameStyle::town);
    CHECK(name_style(NameKind::village, 30'000.0) == NameStyle::village);
    CHECK(name_style(NameKind::suburb, 30'000.0) == NameStyle::village);
    CHECK(name_style(NameKind::city, 6'000'000.0) == NameStyle::city);
    CHECK(name_style(NameKind::city, 6'000'001.0) == NameStyle::globe);
    CHECK(name_style(NameKind::town, 19'000'000.0) == NameStyle::globe);
    CHECK(name_style(NameKind::city, 20'000'000.0) == NameStyle::globe);
    CHECK_FALSE(name_style(NameKind::city, 20'000'001.0).has_value());
    CHECK(scene::name_kind("city") == NameKind::city);
    CHECK(scene::name_kind("country") == NameKind::country);
    CHECK_FALSE(scene::name_kind("hamlet").has_value());
    CHECK_FALSE(scene::name_kind("").has_value());
}

TEST_CASE("scene: the pick takes the names of the tiles a frame draws by rank, each place once, from the cells its "
          "tile names, in view") {
    std::vector<scene::WorldTile> tiles(2);
    // a coarse tile and a fine one both hold Amsterdam (a place apart by less than half a degree is the same place);
    // another Amsterdam far away is another place
    tiles[0].names = {name("Nederland", NameKind::country, 17e6, 52.2, 5.3, 0),
                      name("Amsterdam", NameKind::city, 9e5, 52.37, 4.89, 0, true),
                      name("Rotterdam", NameKind::city, 6.5e5, 51.92, 4.48, 1),
                      name("Haarlem", NameKind::city, 1.6e5, 52.38, 4.64, 0),
                      name("", NameKind::city, 5e6, 52.0, 5.0, 0),
                      // a village this tile holds too is one place; one of its name three kilometres on is another
                      name("Westzaan", NameKind::village, 4.5e3, 52.45, 4.77, 1),
                      name("Westzaan", NameKind::village, 300.0, 52.48, 4.77, 0)};
    tiles[1].names = {name("Amsterdam", NameKind::city, 9e5, 52.3731, 4.8924, 0, true),
                      name("Zaandam", NameKind::town, 7.2e4, 52.44, 4.83, 0),
                      name("Westzaan", NameKind::village, 4.5e3, 52.45, 4.77, 0),
                      name("Amsterdam", NameKind::town, 1.8e4, 42.94, -74.19, 0),
                      name("Hidden", NameKind::city, 3e6, 10.0, 10.0, 2)};
    scene::WorldFrame frame = frame_of(tiles, 0b011);
    scene::WorldNames names;
    const auto picked = names.update(&frame, {}, 100'000.0, everywhere, 0.0, false, true);
    // by rank: the country, the capital, the cities by their people, the towns, the village; the nameless one and
    // the one in a cell its tile does not name are left out
    CHECK(texts(picked) == std::vector<std::string>{"Nederland", "Amsterdam", "Rotterdam", "Haarlem", "Zaandam",
                                                    "Amsterdam", "Westzaan", "Westzaan"});
    CHECK(picked[6].lat != picked[7].lat);
    for (const PickedName& p : picked) {
        CHECK((p.id & scene::name_id_bit) != 0);
        CHECK(p.priority < 0);
    }
    CHECK(picked[0].style == NameStyle::city);
    CHECK(picked[4].style == NameStyle::town);
    CHECK(picked[6].style == NameStyle::village);
    CHECK(picked[1].id != picked[5].id);
    // only what stands in view
    scene::WorldNames some;
    const scene::WorldNames::InView north = [](double lat, double) { return lat > 52.3; };
    CHECK(texts(some.update(&frame, {}, 100'000.0, north, 0.0, false, true)) ==
          std::vector<std::string>{"Amsterdam", "Haarlem", "Zaandam", "Westzaan", "Westzaan"});
    // beyond 6,000 km every name is the globe's, another id; beyond 20,000 km none
    const std::uint32_t city_id = picked[1].id;
    const auto globe = names.update(&frame, {}, 7'000'000.0, everywhere, 1.0, false, true);
    REQUIRE(texts(globe).size() == 8);
    for (const PickedName& p : globe) {
        if (!p.leaving) {
            CHECK(p.style == NameStyle::globe);
            CHECK(p.id != city_id);
        }
    }
    CHECK(texts(names.update(&frame, {}, 21'000'000.0, everywhere, 2.0, false, true)).empty());
    // a test's names, which no tile holds, are picked as a tile's are
    scene::WorldNames extra;
    const std::vector<WorldName> more = {name("\xe6\x9d\xb1\xe4\xba\xac", NameKind::city, 3.7e7, 35.68, 139.77)};
    CHECK(texts(extra.update(nullptr, more, 100'000.0, everywhere, 0.0, true, true)) ==
          std::vector<std::string>{"\xe6\x9d\xb1\xe4\xba\xac"});
    // a name stands at its place in the tile that draws it, as that tile's light of it does: once the fine tile
    // alone draws Amsterdam, the name (the same one, by its id) is at the fine tile's place, and says it is lit there
    // and of how many people (its light's size)
    scene::WorldNames moved;
    std::uint32_t amsterdam = 0;
    {
        const auto coarse = moved.update(&frame, {}, 100'000.0, everywhere, 0.0, false, true);
        REQUIRE(coarse.size() == 8);
        CHECK(coarse[1].lat == 52.37);
        CHECK_FALSE(coarse[1].lit);
        amsterdam = coarse[1].id;
    }
    tiles[1].names[0].lit = true;
    scene::WorldFrame fine_alone = frame_of(std::span<const scene::WorldTile>(tiles).subspan(1), 0b011);
    const auto fine = moved.update(&fine_alone, {}, 100'000.0, everywhere, 0.5, false, true);
    REQUIRE(!fine.empty());
    CHECK(fine[0].id == amsterdam);
    CHECK(fine[0].lat == 52.3731);
    CHECK(fine[0].lon == 4.8924);
    CHECK(fine[0].lit);
    CHECK(fine[0].people == 9e5);
}

TEST_CASE("scene: a name picked keeps its id and stays picked while it stands among the first by rank; one that "
          "leaves is handed on for a fade's length; the pick is made at most ten times a second, and once at rest") {
    std::vector<scene::WorldTile> tiles(1);
    for (int i = 0; i < 200; ++i) {
        tiles[0].names.push_back(
            name("town " + std::to_string(i), NameKind::town, 100'000.0 - (100.0 * i), 40.0 + (i * 0.6), 0.0));
    }
    scene::WorldFrame frame = frame_of(tiles);
    scene::WorldNames names;
    // the first 64 by rank, of 200
    const auto first = names.update(&frame, {}, 100'000.0, everywhere, 0.0, false, true);
    REQUIRE(first.size() == scene::WorldNames::most);
    CHECK(first.front().text == "town 0");
    CHECK(first.back().text == "town 63");
    const std::uint32_t id0 = first.front().id;
    const std::uint32_t id63 = first.back().id;
    CHECK(names.picks() == 1);
    // moving: no pick before a tenth of a second has gone, whatever stands in view
    const scene::WorldNames::InView later = [](double lat, double) { return lat >= 40.0 + (20 * 0.6) - 1e-9; };
    CHECK(names.update(&frame, {}, 100'000.0, later, 0.05, false, true).size() == scene::WorldNames::most);
    CHECK(names.picks() == 1);
    // then: towns 0 to 19 have left the view; 20 to 63 stay, and the room goes to the next by rank, 64 to 83
    const auto moved = names.update(&frame, {}, 100'000.0, later, 0.1, false, true);
    CHECK(names.picks() == 2);
    const auto staying = texts(moved);
    REQUIRE(staying.size() == scene::WorldNames::most);
    CHECK(staying.front() == "town 20");
    CHECK(staying.back() == "town 83");
    CHECK(std::ranges::find_if(moved, [&](const PickedName& p) { return p.id == id63; })->text == "town 63");
    // those that left are handed on, leaving, with their ids
    const auto gone = texts(moved, true);
    CHECK(gone.size() == 20);
    CHECK(std::ranges::any_of(moved, [&](const PickedName& p) { return p.leaving && p.id == id0; }));
    // hysteresis: with towns 0 to 19 back in view (the first by rank, so picked), towns 64 to 83 are no longer among
    // the first 64, but they were picked and stand among the first 96: they stay
    const auto back = names.update(&frame, {}, 100'000.0, everywhere, 0.2, false, true);
    const auto kept = texts(back);
    REQUIRE(kept.size() == 84);
    CHECK(kept.front() == "town 0");
    CHECK(kept.back() == "town 83");
    // and leave once they stand beyond the first 96: with thirteen more towns before them, 83 is the 97th
    std::vector<scene::WorldTile> grown(1);
    for (int i = 0; i < 13; ++i) {
        grown[0].names.push_back(name("first " + std::to_string(i), NameKind::town, 200'000.0 - i, 10.0 + i, 50.0));
    }
    scene::WorldFrame both = frame_of(tiles);
    both.draws.push_back({.tile = &grown[0], .cells = ~0ULL, .fade = 1.0F, .light_cells = ~0ULL});
    const auto pushed = names.update(&both, {}, 100'000.0, everywhere, 0.35, false, true);
    const auto stay = texts(pushed);
    CHECK(stay.size() == 96);
    CHECK(std::ranges::find(stay, "town 82") != stay.end());
    CHECK(std::ranges::find(stay, "town 83") == stay.end());
    CHECK(std::ranges::any_of(pushed, [](const PickedName& p) { return p.leaving && p.text == "town 83"; }));
    // a fade's length after it left, the leaving one is gone
    const auto after = names.update(&both, {}, 100'000.0, everywhere, 0.65, false, true);
    CHECK(texts(after, true).empty());
    // a name back in the pick has the id it had
    scene::WorldNames again;
    const std::uint32_t was = again.update(&frame, {}, 100'000.0, everywhere, 0.0, false, true).front().id;
    (void)again.update(&frame, {}, 100'000.0, later, 1.0, false, true);
    CHECK(again.update(&frame, {}, 100'000.0, everywhere, 2.0, false, true).back().id != 0);
    const auto returned =
        again.update(&frame, {}, 100'000.0, [](double lat, double) { return lat < 41.0; }, 3.0, false, true);
    CHECK(returned.front().text == "town 0");
    CHECK(returned.front().id == was);
    // at rest: one pick where the camera rests, none after while nothing changes, one more where it has moved
    scene::WorldNames rest;
    (void)rest.update(&frame, {}, 100'000.0, everywhere, 0.0, true, true);
    (void)rest.update(&frame, {}, 100'000.0, everywhere, 5.0, true, false);
    (void)rest.update(&frame, {}, 100'000.0, everywhere, 9.0, true, false);
    CHECK(rest.picks() == 1);
    (void)rest.update(&frame, {}, 100'000.0, everywhere, 9.01, true, true);
    CHECK(rest.picks() == 2);
    // at rest when a tile that names places comes: at once, and once
    std::vector<scene::WorldTile> more(1);
    more[0].key = tiles::TileKey(9, 262, 168);
    more[0].names = {name("Late", NameKind::city, 5e6, 45.0, 1.0)};
    frame.draws.push_back({.tile = &more[0], .cells = ~0ULL, .fade = 1.0F, .light_cells = ~0ULL});
    const auto late = rest.update(&frame, {}, 100'000.0, everywhere, 9.02, true, false);
    CHECK(rest.picks() == 3);
    CHECK(std::ranges::any_of(late, [](const PickedName& p) { return p.text == "Late" && !p.leaving; }));
    (void)rest.update(&frame, {}, 100'000.0, everywhere, 9.5, true, false);
    CHECK(rest.picks() == 3);
}

TEST_CASE("scene: a place stands where the world pass draws its ground, and nowhere behind the horizon") {
    const double lat = 52.3746;
    const double lon = 4.884;
    const view::GroundFrame frame = centred(lat, lon);
    // up to 18 km: the flat ground's projection, bit for bit
    {
        const view::View view(spec_of(60.0, 5'000.0), device);
        const scene::WorldBend bend = scene::world_bend(view, frame);
        const auto p = scene::name_point(bend, frame, view, lat + 0.01, lon + 0.01);
        const auto flat = view.device_proj({frame.x(lat + 0.01, lon + 0.01), 0.0, frame.z(lat + 0.01, lon + 0.01)});
        ATLAS_REQUIRE(p.has_value());
        ATLAS_REQUIRE(flat.has_value());
        CHECK(p->x == flat->x);
        CHECK(p->y == flat->y);
    }
    // the target stands at the frame's middle at every distance
    for (const double dist : {30'000.0, 300'000.0, 3'000'000.0, 20'000'000.0}) {
        const view::View view(spec_of(90.0, dist), device);
        const scene::WorldBend bend = scene::world_bend(view, frame);
        const auto p = scene::name_point(bend, frame, view, lat, lon);
        ATLAS_REQUIRE(p.has_value());
        CHECK(p->x == doctest::Approx(1079.0 / 2.0).epsilon(0.002));
        CHECK(p->y == doctest::Approx(2399.0 / 2.0).epsilon(0.002));
    }
    // on the sphere: a place 80° of arc away faces the eye from 20,000 km and stands inside the disc; its antipode
    // and a place just behind the limb stand nowhere
    {
        const view::View view(spec_of(90.0, 20'000'000.0), device);
        const scene::WorldBend bend = scene::world_bend(view, frame);
        // the horizon from h over the sphere: acos(R / (R + h)) of arc
        const double horizon = pymath::acos(view::earth_radius / (view::earth_radius + 20'000'000.0)) / radian;
        CHECK(horizon == doctest::Approx(76.0).epsilon(0.01));
        CHECK(scene::name_point(bend, frame, view, lat - 60.0, lon).has_value());
        CHECK(scene::name_point(bend, frame, view, lat - (horizon - 1.0), lon).has_value());
        CHECK_FALSE(scene::name_point(bend, frame, view, lat - (horizon + 1.0), lon).has_value());
        CHECK_FALSE(scene::name_point(bend, frame, view, -lat, lon + 180.0).has_value());
        // and from a facing's least: nearer the limb than it, nowhere
        CHECK_FALSE(scene::name_point(bend, frame, view, lat - (horizon - 1.0), lon, 0.1).has_value());
        CHECK(scene::name_point(bend, frame, view, lat - 60.0, lon, 0.1).has_value());
        // across the antimeridian a place is taken within 180° of the target
        const view::GroundFrame east = centred(0.0, 179.0);
        const scene::WorldBend over = scene::world_bend(view, east);
        const auto a = scene::name_point(over, east, view, 0.0, -179.0);
        const auto b = scene::name_point(over, east, view, 0.0, 181.0);
        ATLAS_REQUIRE(a.has_value());
        ATLAS_REQUIRE(b.has_value());
        CHECK(a->x == doctest::Approx(b->x));
        CHECK(a->x > 1079.0 / 2.0);
    }
}

namespace {

/// A water of `kind` and `area` m² (a river: its length in the tile, m) at (`lat`, `lon`).
[[nodiscard]] WorldName water(std::string text, NameKind kind, double extent, double lat, double lon,
                              std::uint8_t cell = 0) {
    WorldName n{.kind = kind, .cell = cell, .lat = lat, .lon = lon, .text = std::move(text)};
    n.extent = extent;
    return n;
}

} // namespace

TEST_CASE("scene: a water's name is in the board's water look up to 6,000 km and has none beyond; its rank is its size "
          "on the screen's, in one rank with the places") {
    for (const NameKind kind : {NameKind::ocean, NameKind::sea, NameKind::bay, NameKind::strait, NameKind::lake,
                                NameKind::reservoir, NameKind::river}) {
        CHECK(scene::is_water(kind));
        CHECK(name_style(kind, 25'000.0) == NameStyle::water);
        CHECK(name_style(kind, 6'000'000.0) == NameStyle::water);
        CHECK_FALSE(name_style(kind, 6'000'001.0).has_value());
    }
    CHECK_FALSE(scene::is_water(NameKind::suburb));
    CHECK(scene::name_size_dp(NameStyle::water) == 21.0);
    CHECK(scene::water_kind("lake") == NameKind::lake);
    CHECK(scene::water_kind("reservoir") == NameKind::reservoir);
    CHECK(scene::water_kind("bay") == NameKind::bay);
    CHECK(scene::water_kind("strait") == NameKind::strait);
    CHECK(scene::water_kind("sea") == NameKind::sea);
    CHECK(scene::water_kind("ocean") == NameKind::ocean);
    CHECK_FALSE(scene::water_kind("river").has_value());
    CHECK_FALSE(scene::water_kind("pond").has_value());

    using scene::name_priority;
    using scene::water_priority;
    // a water's rank where it has a name, under every rank where it has none
    const auto rank = [](const WorldName& n, double metres_a_dp) {
        return water_priority(n, metres_a_dp).value_or(std::numeric_limits<std::int32_t>::min());
    };
    const WorldName country = name("Nederland", NameKind::country, 17e6, 52.2, 5.3);
    const WorldName small_country = name("Luxembourg", NameKind::country, 6.4e5, 49.8, 6.1);
    const WorldName capital = name("Amsterdam", NameKind::city, 9e5, 52.37, 4.89, 0, true);
    const WorldName city = name("Almere", NameKind::city, 2.2e5, 52.37, 5.22);
    const WorldName town = name("Hoorn", NameKind::town, 7.3e4, 52.64, 5.06);
    const WorldName village = name("Marken", NameKind::village, 1.8e3, 52.46, 5.10);
    const WorldName suburb = name("Jordaan", NameKind::suburb, 0.0, 52.37, 4.88);
    // the IJsselmeer, 1,125 km²: 33.5 km a side
    const WorldName lake = water("IJsselmeer", NameKind::lake, 1.125e9, 52.8, 5.4);
    // 71 m a dp (a camera 100 km out): 223,000 dp², with the cities, before them all but a capital
    ATLAS_REQUIRE(water_priority(lake, 71.0).has_value());
    CHECK(rank(lake, 71.0) < name_priority(capital));
    CHECK(rank(lake, 71.0) > name_priority(city));
    // 300 m a dp: 12,500 dp², with the towns, after every city
    CHECK(rank(lake, 300.0) < name_priority(city));
    CHECK(rank(lake, 300.0) > name_priority(town));
    // 600 m a dp: 3,125 dp², with the villages, after every town and every river
    const WorldName river = water("Rijn", NameKind::river, 60'000.0, 51.9, 5.9);
    CHECK(rank(lake, 600.0) < name_priority(town));
    CHECK(rank(lake, 600.0) < rank(river, 600.0));
    CHECK(rank(lake, 600.0) > name_priority(village));
    // 800 m a dp: 1,758 dp², under the least: no name (no place is lost to a pond)
    CHECK_FALSE(water_priority(lake, 800.0).has_value());
    CHECK(water_priority(lake, 750.0).has_value()); // 2,000 dp² exactly
    // the larger of two waters of a class first
    const WorldName smaller = water("Markermeer", NameKind::reservoir, 5.93e8, 52.5, 5.2);
    CHECK(rank(lake, 71.0) > rank(smaller, 71.0));
    // a node's bay says no size: named, with the villages, after every water of a known size there
    const WorldName bay = water("Hoornse Hop", NameKind::bay, 0.0, 52.6, 5.1);
    for (const double m : {5.0, 71.0, 5'000.0}) {
        ATLAS_REQUIRE(water_priority(bay, m).has_value());
        CHECK(rank(bay, m) < name_priority(town));
        CHECK(rank(bay, m) > name_priority(suburb));
    }
    CHECK(rank(bay, 600.0) < rank(lake, 600.0));
    // an ocean and a sea with the countries, by their size: before the capitals; a sea too small on the screen none
    const WorldName ocean = water("Atlantic Ocean", NameKind::ocean, 8.5e13, 30.0, -40.0);
    const WorldName sea = water("Noordzee", NameKind::sea, 5.7e11, 56.0, 3.0);
    const WorldName wad = water("Waddenzee", NameKind::sea, 1.0e10, 53.3, 5.4);
    CHECK(rank(ocean, 4'266.0) > rank(sea, 4'266.0));
    CHECK(rank(sea, 4'266.0) > name_priority(capital));
    CHECK(rank(sea, 4'266.0) < name_priority(country));                                      // a country of 17 million
    CHECK(rank(sea, 4'266.0) > name_priority(small_country));                                // one of 640,000
    CHECK_FALSE(water_priority(wad, 4'266.0).has_value());                                   // 550 dp² from 6,000 km
    CHECK(water_priority(wad, 711.0).has_value());                                           // 19,800 dp² from 1,000 km
    CHECK(water_priority(water("Zee", NameKind::sea, 0.0, 53.0, 4.0), 4'266.0).has_value()); // a node's: no size
    // a river after the towns and before the villages, the longer in its tile first; under 96 dp on the screen none
    CHECK(rank(river, 71.0) < name_priority(town));
    CHECK(rank(river, 71.0) > name_priority(village));
    CHECK(rank(river, 71.0) > rank(water("Linge", NameKind::river, 30'000.0, 51.9, 5.3), 71.0));
    CHECK(water_priority(river, 625.0).has_value());       // 96 dp
    CHECK_FALSE(water_priority(river, 640.0).has_value()); // 94 dp
    // with no scale known every water is as large as can be
    CHECK(rank(lake, 0.0) > name_priority(city));
    CHECK(water_priority(river, 0.0).has_value());
    // the places' order among themselves is as it was
    CHECK(name_priority(country) > name_priority(capital));
    CHECK(name_priority(capital) > name_priority(city));
    CHECK(name_priority(city) > name_priority(town));
    CHECK(name_priority(town) > name_priority(village));
    CHECK(name_priority(village) > name_priority(suburb));
}

TEST_CASE("scene: the pick names a frame's waters among its places by their size on the screen, leaves a pond out, "
          "and names the same places where no water stands") {
    std::vector<scene::WorldTile> tiles(1);
    const std::vector<WorldName> places = {name("Amsterdam", NameKind::city, 9e5, 52.37, 4.89, 0, true),
                                           name("Almere", NameKind::city, 2.2e5, 52.37, 5.22),
                                           name("Hoorn", NameKind::town, 7.3e4, 52.64, 5.06),
                                           name("Marken", NameKind::village, 1.8e3, 52.46, 5.10)};
    tiles[0].names = places;
    scene::WorldFrame frame = frame_of(tiles);
    const scene::NameScale scale{.metres_a_dp = 71.0, .lat = 52.5, .lon = 5.1};
    scene::WorldNames before;
    const std::vector<std::string> named =
        texts(before.update(&frame, {}, 100'000.0, everywhere, 0.0, false, true, scale));
    CHECK(named == std::vector<std::string>{"Amsterdam", "Almere", "Hoorn", "Marken"});
    // with the tile's waters after its places: the lakes by their size, a pond none, the places as they were
    tiles[0].names.push_back(water("IJsselmeer", NameKind::lake, 1.125e9, 52.8, 5.4));
    tiles[0].names.push_back(water("Markermeer", NameKind::lake, 5.93e8, 52.5, 5.2));
    tiles[0].names.push_back(water("Gouwzee", NameKind::bay, 2.0e7, 52.44, 5.07));     // 3,970 dp²: with the villages
    tiles[0].names.push_back(water("Kinselmeer", NameKind::lake, 7.0e5, 52.42, 4.99)); // 139 dp²: a pond here
    scene::WorldFrame with = frame_of(tiles);
    scene::WorldNames names;
    const auto picked = names.update(&with, {}, 100'000.0, everywhere, 0.0, false, true, scale);
    CHECK(texts(picked) ==
          std::vector<std::string>{"Amsterdam", "IJsselmeer", "Markermeer", "Almere", "Hoorn", "Gouwzee", "Marken"});
    CHECK(picked[1].style == NameStyle::water);
    CHECK_FALSE(picked[1].lit);
    CHECK((picked[1].id & scene::name_id_bit) != 0);
    CHECK(picked[1].priority < picked[0].priority);
    CHECK(picked[1].priority > picked[3].priority);
    // the places among themselves stand in the order they had
    std::vector<std::string> only;
    for (const PickedName& p : picked) {
        if (p.style != NameStyle::water) only.emplace_back(p.text);
    }
    CHECK(only == named);
    // nearer, the pond is a lake on the screen and has its name; from 7,000 km no water has one
    const scene::NameScale near_by{.metres_a_dp = 17.8, .lat = 52.42, .lon = 4.99};
    const std::vector<std::string> close =
        texts(names.update(&with, {}, 25'000.0, everywhere, 1.0, false, true, near_by));
    CHECK(std::ranges::find(close, "Kinselmeer") != close.end());
    scene::WorldNames far;
    for (const PickedName& p : far.update(&with, {}, 7'000'000.0, everywhere, 0.0, false, true,
                                          {.metres_a_dp = 4'977.0, .lat = 52.5, .lon = 5.1})) {
        CHECK(p.style == NameStyle::globe);
    }
    // a water two rows name (a bay's node and its area, as OpenStreetMap holds some) is named once: at the row of the
    // higher rank, and there still when the other's rank passes it; two waters of one name far apart are both named
    std::vector<scene::WorldTile> twice(1);
    twice[0].names = {water("Gouwzee", NameKind::bay, 0.0, 52.45, 5.06),    // the node: with the villages
                      water("Gouwzee", NameKind::lake, 2.0e7, 52.44, 5.07), // the area, 1.3 km from it
                      water("Grote Meer", NameKind::lake, 3.0e8, 52.9, 5.3),
                      water("Grote Meer", NameKind::lake, 2.0e8, 51.5, 5.3)}; // 155 km south of its namesake
    scene::WorldFrame doubled = frame_of(twice);
    scene::WorldNames once;
    const auto count_of = [](std::span<const PickedName> p, std::string_view text) {
        return std::ranges::count_if(p, [text](const PickedName& n) { return !n.leaving && n.text == text; });
    };
    const auto lat_of = [](std::span<const PickedName> p, std::string_view text) {
        const auto at = std::ranges::find_if(p, [text](const PickedName& n) { return !n.leaving && n.text == text; });
        return at != p.end() ? at->lat : 0.0;
    };
    // 300 m a dp: the area is 222 dp² on the screen, no name; the node's stands
    auto one = once.update(&doubled, {}, 400'000.0, everywhere, 0.0, false, true, {.metres_a_dp = 300.0});
    CHECK(count_of(one, "Gouwzee") == 1);
    CHECK(lat_of(one, "Gouwzee") == 52.45);
    CHECK(count_of(one, "Grote Meer") == 2);
    // 20 m a dp: the area ranks with the cities now, over the node's row; the name stays where it stands
    one = once.update(&doubled, {}, 28'000.0, everywhere, 0.2, false, true, {.metres_a_dp = 20.0});
    CHECK(count_of(one, "Gouwzee") == 1);
    CHECK(lat_of(one, "Gouwzee") == 52.45);
    // a fresh pick there takes the area's row
    scene::WorldNames anew;
    one = anew.update(&doubled, {}, 28'000.0, everywhere, 0.0, false, true, {.metres_a_dp = 20.0});
    CHECK(count_of(one, "Gouwzee") == 1);
    CHECK(lat_of(one, "Gouwzee") == 52.44);
    // a water's name keeps its id, as a place's does
    scene::WorldNames again;
    const std::uint32_t id = [&] {
        const auto first = again.update(&with, {}, 100'000.0, everywhere, 0.0, false, true, scale);
        return first[1].id;
    }();
    CHECK(again.update(&with, {}, 100'000.0, everywhere, 0.5, false, true, scale)[1].id == id);
}

TEST_CASE("scene: a river, which every tile it crosses names, is named once within 400 dp on the screen: where it was "
          "named while that row stays picked, else at its row nearest the camera's target") {
    // three tiles along the Rijn, each with its row at the middle of its piece, 20 km apart; another river of the same
    // name far off; a short piece
    std::vector<scene::WorldTile> tiles(4);
    tiles[0].names = {water("Rijn", NameKind::river, 21'000.0, 51.95, 5.60)};
    tiles[1].names = {water("Rijn", NameKind::river, 24'000.0, 51.95, 5.90), // 20.6 km east of the first
                      water("Linge", NameKind::river, 30'000.0, 51.90, 5.90)};
    tiles[2].names = {water("Rijn", NameKind::river, 19'000.0, 51.95, 6.20)};
    tiles[3].names = {water("Rijn", NameKind::river, 15'000.0, 47.60, 8.20), // the Hochrhein, 500 km on
                      water("Beek", NameKind::river, 3'000.0, 51.94, 5.88)}; // 42 dp at 71 m a dp: none
    scene::WorldFrame frame = frame_of(tiles);
    const auto rijn_at = [](std::span<const PickedName> picked) {
        std::vector<double> lons;
        for (const PickedName& p : picked) {
            if (!p.leaving && p.text == "Rijn") lons.push_back(p.lon);
        }
        std::ranges::sort(lons);
        return lons;
    };
    // 100 km out (71 m a dp: 400 dp are 28.4 km), the camera over the middle row: the Rijn once near, at the row
    // nearest the target, and once far off; the other river; the short piece not
    scene::WorldNames names;
    scene::NameScale scale{.metres_a_dp = 71.0, .lat = 51.95, .lon = 5.92};
    auto picked = names.update(&frame, {}, 100'000.0, everywhere, 0.0, false, true, scale);
    CHECK(rijn_at(picked) == std::vector<double>{5.90, 8.20});
    CHECK(std::ranges::count(texts(picked), "Linge") == 1);
    CHECK(std::ranges::count(texts(picked), "Beek") == 0);
    for (const PickedName& p : picked) CHECK(p.style == NameStyle::water);
    // the camera moves west, nearer the first row: the name stays where it stands
    scale.lon = 5.70;
    picked = names.update(&frame, {}, 100'000.0, everywhere, 0.2, false, true, scale);
    CHECK(rijn_at(picked) == std::vector<double>{5.90, 8.20});
    // its row leaves the view: the river is named at the row nearest the target that is left, the old one leaving
    const scene::WorldNames::InView west = [](double, double lon) { return lon < 5.8 || lon > 8.0; };
    picked = names.update(&frame, {}, 100'000.0, west, 0.4, false, true, scale);
    CHECK(rijn_at(picked) == std::vector<double>{5.60, 8.20});
    CHECK(std::ranges::count(texts(picked, true), "Rijn") == 1);
    // nearer (17.8 m a dp: 400 dp are 7.1 km) the rows are farther apart than that on the screen: each is named
    scene::WorldNames close;
    picked = close.update(&frame, {}, 25'000.0, everywhere, 0.0, false, true,
                          {.metres_a_dp = 17.8, .lat = 51.95, .lon = 5.92});
    CHECK(rijn_at(picked) == std::vector<double>{5.60, 5.90, 6.20, 8.20});
    // a fresh pick from the west takes the western row
    scene::WorldNames fresh;
    picked = fresh.update(&frame, {}, 100'000.0, everywhere, 0.0, false, true,
                          {.metres_a_dp = 71.0, .lat = 51.95, .lon = 5.55});
    CHECK(rijn_at(picked) == std::vector<double>{5.60, 6.20, 8.20});
}
