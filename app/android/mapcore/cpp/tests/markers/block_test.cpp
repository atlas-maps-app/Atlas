// The app's markers as the core takes them (markers/Markers.hpp): a block as MK/state/MarkerBlock.kt writes it read
// back field for field, the blocks it refuses (another magic, one cut short or running on, a style or flag that is
// none, an image larger than may be), and the sets MarkerSet::make refuses (a look that is none of the set's, a
// cluster with one, a count of none, a place that is none, a density out of its range, images past the budget); a
// set's texels one after another, and the clusters' counts found by their ink and the count's key.
#include "markers/Markers.hpp"

#include <doctest/doctest.h>

#include <cstring>
#include <optional>
#include <vector>

using namespace atlas::markers;

namespace {

/// A block as MarkerBlock.write writes it.
class Writer {
public:
    template <class T>
    Writer& put(T v) {
        const auto* p = reinterpret_cast<const std::byte*>(&v); // NOLINT: the value's bytes, little-endian here
        bytes.insert(bytes.end(), p, p + sizeof v);
        return *this;
    }
    Writer& image(const Image& m) {
        put(m.width).put(m.height).put(m.anchor_x).put(m.anchor_y);
        for (const std::uint32_t t : m.pixels) put(t);
        return *this;
    }
    Writer& marker(const Marker& m) {
        put(m.latitude).put(m.longitude).put(m.priority);
        put(static_cast<std::uint8_t>(m.style)).put(static_cast<std::uint8_t>(m.selected ? 1 : 0));
        put(static_cast<std::uint8_t>(m.value ? 1 : 0)).put(std::uint8_t{0});
        put(m.count).put(m.chip_text).put(m.name).put(m.colour).put(m.ink).put(m.look).put(m.look_left);
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

/// Two looks, three markers (a pin with a name, a dot and a cluster) and three counts in two inks, as a block.
std::vector<std::byte> sample(std::vector<Marker>* markers = nullptr) {
    const std::vector<Marker> list{
        {.latitude = 52.37,
         .longitude = 4.88,
         .priority = 7,
         .style = Style::pin,
         .selected = true,
         .value = true,
         .count = 1,
         .chip_text = 21.5F,
         .name = 80.25F,
         .colour = 0xFFE8'612BU,
         .ink = 0xFFFF'FFFFU,
         .look = 0,
         .look_left = 1},
        {.latitude = 52.38, .longitude = 4.89, .style = Style::dot, .colour = 0xFFF2'F2F2U, .ink = 0xFF14'161BU},
        {.latitude = -33.9, .longitude = 151.2, .priority = -3, .style = Style::cluster, .count = 12},
    };
    if (markers != nullptr) *markers = list;
    Writer w;
    w.put(marker_block_magic).put(2.625F).put(std::uint32_t{2}).put(std::uint32_t{3}).put(std::uint32_t{3});
    w.image(image(5, 4, 1)).image(image(9, 4, 2));
    Marker dot = list[1];
    dot.look = 0;
    dot.look_left = 0;
    Marker cluster = list[2];
    cluster.look = no_look;
    cluster.look_left = no_look;
    w.marker(list[0]).marker(dot).marker(cluster);
    w.put(0xFF14'161BU).put(std::uint32_t{12}).image(image(3, 2, 3));
    w.put(0xFFFF'FFFFU).put(std::uint32_t{2}).image(image(2, 2, 4));
    w.put(0xFFFF'FFFFU).put(std::uint32_t{1200}).image(image(4, 2, 5));
    return w.bytes;
}

} // namespace

TEST_CASE("markers: a block read back as the app wrote it") {
    std::vector<Marker> want;
    const auto bytes = sample(&want);
    const auto read = read_marker_block(bytes);
    REQUIRE_MESSAGE(read.has_value(), read.error());
    CHECK(read->density == 2.625F);
    REQUIRE(read->looks.size() == 2);
    CHECK(read->looks[1].width == 9);
    CHECK(read->looks[1].pixels == image(9, 4, 2).pixels);
    REQUIRE(read->markers.size() == 3);
    const Marker& pin = read->markers[0];
    CHECK(pin.latitude == want[0].latitude);
    CHECK(pin.longitude == want[0].longitude);
    CHECK(pin.priority == 7);
    CHECK(pin.style == Style::pin);
    CHECK(pin.selected);
    CHECK(pin.value);
    CHECK(pin.chip_text == 21.5F);
    CHECK(pin.name == 80.25F);
    CHECK(pin.colour == 0xFFE8'612BU);
    CHECK(pin.look == 0);
    CHECK(pin.look_left == 1);
    CHECK(read->markers[1].style == Style::dot);
    CHECK(read->markers[2].count == 12);
    CHECK(read->markers[2].look == no_look);
    REQUIRE(read->counts.size() == 3);
    CHECK(read->counts[2].key == 1200);
}

TEST_CASE("markers: the blocks the core refuses") {
    const auto good = sample();
    const auto refused = [](std::vector<std::byte> b) { return !read_marker_block(b).has_value(); };
    // another magic; cut short; running on
    auto b = good;
    b[0] = std::byte{0};
    CHECK(refused(b));
    CHECK(refused({good.begin(), good.end() - 1}));
    b = good;
    b.push_back(std::byte{0});
    CHECK(refused(b));
    // a style that is none (the first marker's style byte: after the header, the looks and its place and priority)
    const std::size_t first = 20 + (16 + (5 * 4 * 4)) + (16 + (9 * 4 * 4));
    b = good;
    b[first + 20] = std::byte{5};
    CHECK(refused(b));
    // a chosen flag that is neither 0 nor 1, and the zero byte that is not
    b = good;
    b[first + 21] = std::byte{2};
    CHECK(refused(b));
    b = good;
    b[first + 23] = std::byte{1};
    CHECK(refused(b));
    // an image larger than may be
    Writer w;
    w.put(marker_block_magic).put(1.0F).put(std::uint32_t{1}).put(std::uint32_t{0}).put(std::uint32_t{0});
    w.put(MarkerSet::max_side + 1).put(std::uint32_t{1}).put(0).put(0);
    CHECK(refused(w.bytes));
}

TEST_CASE("markers: the sets the core refuses, and a set's texels and counts") {
    const auto make = [](std::vector<Marker> markers, std::vector<Image> looks, std::vector<CountImage> counts = {},
                         float density = 2.0F) {
        return MarkerSet::make(std::move(markers), std::move(looks), std::move(counts), density, 1);
    };
    // why a set was refused; none where it was made
    const auto why = [](const auto& made) { return made ? std::optional<MarkerError>() : std::optional(made.error()); };
    const Marker pin{.style = Style::pin, .look = 0, .look_left = 0};
    const Marker cluster{.style = Style::cluster, .count = 3};
    CHECK(make({pin, cluster}, {image(2, 2, 1)}).has_value());
    // a look that is none of the set's; a cluster with one; a pin with none
    Marker other = pin;
    other.look_left = 1;
    CHECK(why(make({other}, {image(2, 2, 1)})) == MarkerError::look);
    Marker looked = cluster;
    looked.look = 0;
    CHECK(why(make({looked}, {image(2, 2, 1)})) == MarkerError::look);
    Marker bare = pin;
    bare.look = no_look;
    CHECK(why(make({bare}, {image(2, 2, 1)})) == MarkerError::look);
    // a count of none; a place that is none
    Marker none = cluster;
    none.count = 0;
    CHECK(why(make({none}, {})) == MarkerError::markers);
    Marker nowhere = pin;
    nowhere.latitude = 91.0;
    CHECK(why(make({nowhere}, {image(2, 2, 1)})) == MarkerError::markers);
    // a density out of its range; an image whose pixels are not its size; images past the budget
    CHECK(why(make({pin}, {image(2, 2, 1)}, {}, 0.0F)) == MarkerError::density);
    Image torn = image(2, 2, 1);
    torn.pixels.pop_back();
    CHECK(why(make({pin}, {torn})) == MarkerError::size);
    Image huge{.width = 2048, .height = 2048};
    huge.pixels.assign(std::size_t{2048} * 2048, 0U);
    CHECK(why(make({pin}, {huge, huge, huge})) == MarkerError::budget);
    // the texels one after another, the looks' first, then the counts' by ink and key
    const auto set = make({pin, cluster}, {image(2, 2, 1), image(3, 1, 2)},
                          {{.ink = 7, .key = 12, .image = image(1, 1, 3)},
                           {.ink = 5, .key = 2, .image = image(2, 1, 4)},
                           {.ink = 7, .key = 1200, .image = image(1, 2, 5)},
                           {.ink = 7, .key = 12, .image = image(4, 4, 6)}});
    REQUIRE(set.has_value());
    const MarkerSet& s = **set;
    CHECK(s.texels().size() == 4 + 3 + 2 + 1 + 2); // the second count of 12 in ink 7 is let go
    CHECK(s.look_offsets()[1] == 4);
    CHECK(s.texels()[4] == image(3, 1, 2).pixels[0]);
    REQUIRE(s.counts().size() == 3);
    const auto at = [&](std::uint32_t ink, std::uint64_t n) {
        const auto c = s.count_of(ink, n);
        return c ? s.texels()[s.count_offsets()[*c]] : 0U;
    };
    CHECK(at(7, 12) == image(1, 1, 3).pixels[0]);
    CHECK(at(5, 2) == image(2, 1, 4).pixels[0]);
    // 1,234 and 1,299 are written "1.2k": the key 1,200's image
    CHECK(at(7, 1234) == image(1, 2, 5).pixels[0]);
    CHECK(at(7, 1299) == image(1, 2, 5).pixels[0]);
    CHECK(!s.count_of(7, 13).has_value());
    CHECK(!s.count_of(6, 12).has_value());
}
