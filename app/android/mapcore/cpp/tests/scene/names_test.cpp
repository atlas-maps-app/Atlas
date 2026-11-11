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
