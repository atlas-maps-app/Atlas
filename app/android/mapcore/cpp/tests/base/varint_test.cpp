#include "base/varint.hpp"
#include "tests/base/fma_inputs.hpp"

#include <doctest/doctest.h>

#include <array>
#include <bit>
#include <cstddef>
#include <cstdint>
#include <initializer_list>
#include <limits>
#include <span>
#include <vector>

namespace {

std::vector<std::byte> bytes(std::initializer_list<unsigned> values) {
    std::vector<std::byte> out;
    for (const unsigned v : values) out.push_back(static_cast<std::byte>(v));
    return out;
}

} // namespace

TEST_CASE("zigzag keeps small magnitudes small and round-trips") {
    CHECK(atlas::zigzag(0) == 0U);
    CHECK(atlas::zigzag(-1) == 1U);
    CHECK(atlas::zigzag(1) == 2U);
    CHECK(atlas::zigzag(-2) == 3U);
    CHECK(atlas::zigzag(std::numeric_limits<std::int64_t>::max()) == std::numeric_limits<std::uint64_t>::max() - 1);
    CHECK(atlas::zigzag(std::numeric_limits<std::int64_t>::min()) == std::numeric_limits<std::uint64_t>::max());
    // std::int64_t is long on Android and Linux, long long on macOS: the values are written in its own type.
    constexpr std::array<std::int64_t, 8> values{
        0, 1, -1, 63, -64, std::int64_t{1} << 40, -(std::int64_t{1} << 40), std::numeric_limits<std::int64_t>::min()};
    for (const std::int64_t v : values) {
        CHECK(atlas::unzigzag(atlas::zigzag(v)) == v);
    }
}

TEST_CASE("varints decode as LEB128, strictly") {
    const auto ok =
        bytes({0x00, 0x01, 0x7F, 0x80, 0x01, 0x96, 0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01});
    atlas::VarintReader reader(ok);
    CHECK((reader.next() == 0U));
    CHECK((reader.next() == 1U));
    CHECK((reader.next() == 127U));
    CHECK((reader.next() == 128U));
    CHECK((reader.next() == 150U));
    CHECK((reader.next() == std::numeric_limits<std::uint64_t>::max()));
    CHECK(reader.done());
    CHECK((reader.next().error() == atlas::VarintError::truncated));

    const auto overlong = bytes({0x80, 0x00});
    CHECK((atlas::VarintReader(overlong).next().error() == atlas::VarintError::overlong));
    const auto too_big = bytes({0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x02});
    CHECK((atlas::VarintReader(too_big).next().error() == atlas::VarintError::overflow));
    const auto eleven = bytes({0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x81, 0x00});
    CHECK((atlas::VarintReader(eleven).next().error() == atlas::VarintError::overflow));
    const auto cut = bytes({0x96});
    CHECK((atlas::VarintReader(cut).next().error() == atlas::VarintError::truncated));

    const auto signed_values = bytes({0x03, 0x04});
    atlas::VarintReader s(signed_values);
    CHECK((s.next_signed() == -2));
    CHECK((s.next_signed() == 2));
}

TEST_CASE("writing then reading gives every value back, in its shortest form") {
    atlas::test::SplitMix64 random(77);
    std::vector<std::byte> stream;
    std::vector<std::uint64_t> values;
    for (int i = 0; i < 100'000; ++i) {
        const std::uint64_t v = random.next() >> random.below(64);
        values.push_back(v);
        std::array<std::byte, atlas::varint_max_bytes> buffer{};
        const std::size_t n = atlas::write_varint(v, buffer);
        CHECK(n == (v == 0 ? 1 : ((64 - static_cast<std::size_t>(std::countl_zero(v))) + 6) / 7));
        stream.insert(stream.end(), buffer.begin(), buffer.begin() + static_cast<std::ptrdiff_t>(n));
    }
    atlas::VarintReader reader(stream);
    for (const std::uint64_t v : values) REQUIRE((reader.next() == v));
    CHECK(reader.done());
}

static_assert(atlas::unzigzag(atlas::zigzag(-12345)) == -12345);
