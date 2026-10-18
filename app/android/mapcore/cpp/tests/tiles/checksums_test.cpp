// The pack's checksums (tiles/Checksums.hpp): CRC-32 and SHA-256 against their published test vectors, in pieces and
// whole, and the CPU's instruction paths against the portable ones.
#include "tests/base/require.hpp"
#include "tiles/Bytes.hpp"
#include "tiles/Checksums.hpp"

#include <doctest/doctest.h>

#include <cstdint>
#include <random>
#include <string>
#include <vector>

namespace {

using namespace atlas::tiles;

std::vector<std::byte> random_bytes(std::size_t n, std::uint32_t seed) {
    std::mt19937 g(seed);
    std::vector<std::byte> out(n);
    for (auto& b : out) b = static_cast<std::byte>(g() & 0xFFU);
    return out;
}

TEST_CASE("tiles: CRC-32 is java.util.zip.CRC32's") {
    CHECK(crc32({}) == 0U);
    CHECK(crc32(as_bytes("123456789")) == 0xCBF4'3926U); // the check value of CRC-32/ISO-HDLC
    CHECK(crc32(as_bytes("The quick brown fox jumps over the lazy dog")) == 0x414F'A339U);
    CHECK(crc32(as_bytes("a")) == 0xE8B7'BE43U);
}

TEST_CASE("tiles: CRC-32 continues across pieces, and every path gives the same bits") {
    const auto data = random_bytes(10'000, 7);
    const std::uint32_t whole = crc32_portable(data);
    for (std::size_t cut : {0U, 1U, 7U, 8U, 9U, 63U, 4096U, 9999U}) {
        const std::span<const std::byte> s(data);
        CHECK(crc32(s.subspan(cut), crc32(s.first(cut))) == whole);
    }
    for (std::size_t n = 0; n < 300; ++n) {
        for (std::size_t offset = 0; offset < 9; ++offset) {
            const std::span<const std::byte> s = std::span<const std::byte>(data).subspan(offset, n);
            ATLAS_REQUIRE(crc32(s) == crc32_portable(s), n << " bytes from byte " << offset);
        }
    }
    MESSAGE("CRC-32 instructions: " << std::string(checksum_paths().crc32_instructions ? "used" : "absent"));
}

TEST_CASE("tiles: SHA-256 matches FIPS 180-4's examples") {
    CHECK(hex(Sha256::of({})) == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    CHECK(hex(Sha256::of(as_bytes("abc"))) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    CHECK(hex(Sha256::of(as_bytes("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"))) ==
          "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");
    CHECK(hex(Sha256::of(as_bytes("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmn"
                                  "opqrlmnopqrsmnopqrstnopqrstu"))) ==
          "cf5b16a778af8380036ce59e7b0492370b249b11e8f07a51afac45037afee9d1");
    const std::string million(1'000'000, 'a');
    CHECK(hex(Sha256::of(as_bytes(million))) == "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0");
}

TEST_CASE(
    "tiles: SHA-256 fed in pieces equals SHA-256 of the whole, and the instruction path equals the portable one") {
    const auto data = random_bytes(5'000, 11);
    const Sha256Digest whole = Sha256::of(data);
    for (std::size_t step : {1U, 3U, 55U, 56U, 63U, 64U, 65U, 1000U}) {
        Sha256 h;
        for (std::size_t at = 0; at < data.size(); at += step) {
            h.update(std::span<const std::byte>(data).subspan(at, std::min(step, data.size() - at)));
        }
        CHECK(h.finish() == whole);
    }
    // the compression function alone, on every path the CPU has
    std::array<std::uint32_t, 8> portable = {1, 2, 3, 4, 5, 6, 7, 8};
    sha256_blocks_portable(portable, data.data(), data.size() / 64);
    Sha256 h; // the dispatching path, through a whole-block update
    h.update(std::span<const std::byte>(data).first((data.size() / 64) * 64));
    (void)h.finish();
    for (std::size_t n = 0; n < 200; ++n) {
        const auto s = std::span<const std::byte>(data).first(n);
        Sha256 a;
        a.update(s);
        // a portable reference: blocks through sha256_blocks_portable, padded by hand
        std::array<std::uint32_t, 8> state = {0x6a09e667U, 0xbb67ae85U, 0x3c6ef372U, 0xa54ff53aU,
                                              0x510e527fU, 0x9b05688cU, 0x1f83d9abU, 0x5be0cd19U};
        std::vector<std::byte> padded(s.begin(), s.end());
        padded.push_back(std::byte{0x80});
        while (padded.size() % 64 != 56) padded.push_back(std::byte{0});
        const std::uint64_t bits = std::uint64_t{n} * 8U;
        for (int k = 7; k >= 0; --k) padded.push_back(static_cast<std::byte>(bits >> (8U * static_cast<unsigned>(k))));
        sha256_blocks_portable(state, padded.data(), padded.size() / 64);
        Sha256Digest want{};
        for (std::size_t i = 0; i < 8; ++i) {
            for (std::size_t k = 0; k < 4; ++k)
                want[(4 * i) + k] = static_cast<std::byte>(state[i] >> (24U - (8U * k)));
        }
        ATLAS_REQUIRE(a.finish() == want, "the first " << n << " bytes");
    }
    MESSAGE("SHA-256 instructions: " << std::string(checksum_paths().sha2_instructions ? "used" : "absent"));
}

} // namespace
