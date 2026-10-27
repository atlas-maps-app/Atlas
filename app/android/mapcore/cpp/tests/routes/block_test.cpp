// The app's routes and areas as the core takes them (routes/Routes.hpp): a block as MK/state/RouteBlock.kt writes it
// read back field for field, the blocks it refuses (another magic, one cut short or running on, flags that are none,
// more routes than may be), and the sets RouteSet::make refuses (a route of one point, distances going back, a chip
// that is none of the set's, a width or radius of none, a density out of its range); a set's chips one after another.
#include "routes/Routes.hpp"

#include <doctest/doctest.h>

#include <optional>
#include <vector>

using namespace atlas::routes;

namespace {

/// A block as RouteBlock.write writes it.
class Writer {
public:
    template <class T>
    Writer& put(T v) {
        const auto* p = reinterpret_cast<const std::byte*>(&v); // NOLINT: the value's bytes, little-endian here
        bytes.insert(bytes.end(), p, p + sizeof v);
        return *this;
    }
    Writer& route(const Route& r) {
        put(static_cast<std::uint32_t>(r.points.size())).put(r.width).put(r.colour).put(r.faded).put(r.casing);
        put(static_cast<std::uint8_t>(r.dashed ? 1 : 0)).put(std::uint8_t{0}).put(std::uint8_t{0}).put(std::uint8_t{0});
        put(r.travelled).put(r.chip);
        for (const auto& [lat, lon, s] : r.points) put(lat).put(lon).put(s);
        return *this;
    }
    std::vector<std::byte> bytes;
};

Image image(std::uint32_t width, std::uint32_t height, std::uint32_t seed) {
    Image m{.width = width,
            .height = height,
            .anchor_x = static_cast<std::int32_t>(width / 2),
            .anchor_y = static_cast<std::int32_t>(height / 2)};
    for (std::uint32_t i = 0; i < width * height; ++i) m.pixels.push_back((seed * 0x9E37'79B9U) + i);
    return m;
}

const Route walk{.points = {{52.37, 4.88, 0.0}, {52.371, 4.881, 131.25}, {52.372, 4.881, 242.5}},
                 .width = 4.92F,
                 .colour = 0xFF2E'5BFFU,
                 .faded = 0xFF8A'9BD9U,
                 .casing = 0xFFFF'FFFFU,
                 .dashed = true,
                 .travelled = 40.0,
                 .chip = 0};

/// One area, a dashed route with its chip, and the chip, as a block.
std::vector<std::byte> sample() {
    Writer w;
    w.put(route_block_magic).put(2.625F).put(std::uint32_t{1}).put(std::uint32_t{1}).put(std::uint32_t{1});
    w.put(52.36).put(4.87).put(200.0F).put(0x332E'5BFFU).put(0xFF2E'5BFFU);
    w.route(walk);
    const Image chip = image(7, 3, 5);
    w.put(chip.width).put(chip.height).put(chip.anchor_x).put(chip.anchor_y);
    for (const std::uint32_t t : chip.pixels) w.put(t);
    return w.bytes;
}

} // namespace

TEST_CASE("routes: a block read back as the app wrote it") {
    const auto bytes = sample();
    const auto read = read_route_block(bytes);
    REQUIRE_MESSAGE(read.has_value(), read.error());
    CHECK(read->density == 2.625F);
    REQUIRE(read->areas.size() == 1);
    CHECK(read->areas[0].latitude == 52.36);
    CHECK(read->areas[0].radius == 200.0F);
    CHECK(read->areas[0].fill == 0x332E'5BFFU);
    CHECK(read->areas[0].stroke == 0xFF2E'5BFFU);
    REQUIRE(read->routes.size() == 1);
    const Route& r = read->routes[0];
    CHECK(r.points == walk.points);
    CHECK(r.width == 4.92F);
    CHECK(r.colour == walk.colour);
    CHECK(r.faded == walk.faded);
    CHECK(r.casing == walk.casing);
    CHECK(r.dashed);
    CHECK(r.travelled == 40.0);
    CHECK(r.chip == 0);
    REQUIRE(read->chips.size() == 1);
    CHECK(read->chips[0].pixels == image(7, 3, 5).pixels);
}

TEST_CASE("routes: the blocks the core refuses") {
    const auto good = sample();
    const auto refused = [](std::vector<std::byte> b) { return !read_route_block(b).has_value(); };
    auto b = good;
    b[0] = std::byte{0};
    CHECK(refused(b));
    CHECK(refused({good.begin(), good.end() - 1}));
    b = good;
    b.push_back(std::byte{0});
    CHECK(refused(b));
    // the route's dashed flag that is neither 0 nor 1, and a zero byte that is not (after the header, the area and the
    // route's count, width and colours)
    const std::size_t flags = 20 + 28 + 20;
    b = good;
    b[flags] = std::byte{2};
    CHECK(refused(b));
    b = good;
    b[flags + 2] = std::byte{1};
    CHECK(refused(b));
    // more routes than may be
    Writer w;
    w.put(route_block_magic).put(1.0F).put(std::uint32_t{0}).put(std::uint32_t{65}).put(std::uint32_t{0});
    CHECK(refused(w.bytes));
}

TEST_CASE("routes: the sets the core refuses, and a set's chips") {
    const auto make = [](std::vector<Route> routes, std::vector<Image> chips = {}, std::vector<Area> areas = {},
                         float density = 2.0F) {
        return RouteSet::make(std::move(areas), std::move(routes), std::move(chips), density, 1);
    };
    const auto why = [](const auto& made) { return made ? std::optional<RouteError>() : std::optional(made.error()); };
    Route plain = walk;
    plain.chip = no_chip;
    CHECK(make({plain}).has_value());
    CHECK(why(make({walk})) == RouteError::chip);
    Route one = plain;
    one.points.resize(1);
    CHECK(why(make({one})) == RouteError::points);
    Route back = plain;
    back.points[2][2] = 100.0;
    CHECK(why(make({back})) == RouteError::points);
    Route nowhere = plain;
    nowhere.points[1][0] = 95.0;
    CHECK(why(make({nowhere})) == RouteError::points);
    Route thin = plain;
    thin.width = 0.0F;
    CHECK(why(make({thin})) == RouteError::width);
    CHECK(why(make({}, {}, {Area{.latitude = 52.0, .longitude = 4.0, .radius = -1.0F}})) == RouteError::width);
    CHECK(why(make({plain}, {}, {}, 9.0F)) == RouteError::density);
    Image torn = image(2, 2, 1);
    torn.pixels.pop_back();
    CHECK(why(make({walk}, {torn})) == RouteError::size);
    // the chips' texels one after another
    Route second = walk;
    second.chip = 1;
    const auto set = make({walk, second}, {image(2, 2, 1), image(3, 1, 2)});
    REQUIRE(set.has_value());
    CHECK((*set)->texels().size() == 7);
    CHECK((*set)->chip_offsets()[1] == 4);
    CHECK((*set)->texels()[4] == image(3, 1, 2).pixels[0]);
}
