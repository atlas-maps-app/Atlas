#include "tiles/Checksums.hpp"

#include <algorithm>
#include <bit>
#include <cstring>

#if defined(__aarch64__)
#include <arm_acle.h>
#include <arm_neon.h>
#if defined(__linux__)
#include <asm/hwcap.h>
#include <sys/auxv.h>
#endif
#endif

namespace atlas::tiles {
namespace {

// ---- CRC-32: the reflected polynomial 0xEDB88320, eight tables for eight bytes a step (slice-by-8).

using CrcTables = std::array<std::array<std::uint32_t, 256>, 8>;

consteval CrcTables make_crc_tables() {
    CrcTables t{};
    for (std::uint32_t i = 0; i < 256; ++i) {
        std::uint32_t c = i;
        for (int k = 0; k < 8; ++k) c = (c & 1U) != 0 ? 0xEDB8'8320U ^ (c >> 1U) : c >> 1U;
        t[0][i] = c;
    }
    for (std::size_t s = 1; s < 8; ++s) {
        for (std::size_t i = 0; i < 256; ++i) t[s][i] = (t[s - 1][i] >> 8U) ^ t[0][t[s - 1][i] & 0xFFU];
    }
    return t;
}

constexpr CrcTables crc_tables = make_crc_tables();

[[nodiscard]] std::uint32_t load32le(const std::byte* p) noexcept {
    std::uint32_t v = 0;
    std::memcpy(&v, p, sizeof v);
    return v; // the core is little-endian (base/std_check.hpp)
}

[[nodiscard]] std::uint32_t load32be(const std::byte* p) noexcept { return std::byteswap(load32le(p)); }

// ---- SHA-256 (FIPS 180-4).

constexpr std::array<std::uint32_t, 64> k256 = {
    0x428a2f98U, 0x71374491U, 0xb5c0fbcfU, 0xe9b5dba5U, 0x3956c25bU, 0x59f111f1U, 0x923f82a4U, 0xab1c5ed5U,
    0xd807aa98U, 0x12835b01U, 0x243185beU, 0x550c7dc3U, 0x72be5d74U, 0x80deb1feU, 0x9bdc06a7U, 0xc19bf174U,
    0xe49b69c1U, 0xefbe4786U, 0x0fc19dc6U, 0x240ca1ccU, 0x2de92c6fU, 0x4a7484aaU, 0x5cb0a9dcU, 0x76f988daU,
    0x983e5152U, 0xa831c66dU, 0xb00327c8U, 0xbf597fc7U, 0xc6e00bf3U, 0xd5a79147U, 0x06ca6351U, 0x14292967U,
    0x27b70a85U, 0x2e1b2138U, 0x4d2c6dfcU, 0x53380d13U, 0x650a7354U, 0x766a0abbU, 0x81c2c92eU, 0x92722c85U,
    0xa2bfe8a1U, 0xa81a664bU, 0xc24b8b70U, 0xc76c51a3U, 0xd192e819U, 0xd6990624U, 0xf40e3585U, 0x106aa070U,
    0x19a4c116U, 0x1e376c08U, 0x2748774cU, 0x34b0bcb5U, 0x391c0cb3U, 0x4ed8aa4aU, 0x5b9cca4fU, 0x682e6ff3U,
    0x748f82eeU, 0x78a5636fU, 0x84c87814U, 0x8cc70208U, 0x90befffaU, 0xa4506cebU, 0xbef9a3f7U, 0xc67178f2U,
};

constexpr std::array<std::uint32_t, 8> sha256_initial = {0x6a09e667U, 0xbb67ae85U, 0x3c6ef372U, 0xa54ff53aU,
                                                         0x510e527fU, 0x9b05688cU, 0x1f83d9abU, 0x5be0cd19U};

#if defined(__aarch64__)

__attribute__((target("crc"))) std::uint32_t crc32_instructions(std::span<const std::byte> bytes,
                                                                std::uint32_t crc) noexcept {
    std::uint32_t c = ~crc;
    const std::byte* p = bytes.data();
    std::size_t n = bytes.size();
    while (n >= 8) {
        std::uint64_t v = 0;
        std::memcpy(&v, p, sizeof v);
        c = __crc32d(c, v);
        p += 8;
        n -= 8;
    }
    for (; n > 0; --n) c = __crc32b(c, std::to_integer<std::uint8_t>(*p++));
    return ~c;
}

__attribute__((target("sha2"))) void sha256_blocks_instructions(std::array<std::uint32_t, 8>& state,
                                                                const std::byte* blocks, std::size_t count) noexcept {
    uint32x4_t abcd = vld1q_u32(state.data());
    uint32x4_t efgh = vld1q_u32(state.data() + 4);
    for (; count > 0; --count, blocks += 64) {
        const uint32x4_t abcd0 = abcd;
        const uint32x4_t efgh0 = efgh;
        const auto* bytes = reinterpret_cast<const std::uint8_t*>(blocks); // NOLINT: the block's bytes
        std::array<uint32x4_t, 4> m{};
        for (std::size_t j = 0; j < 4; ++j) m[j] = vreinterpretq_u32_u8(vrev32q_u8(vld1q_u8(bytes + (16 * j))));
        for (std::size_t i = 0; i < 16; ++i) {
            const uint32x4_t wk = vaddq_u32(m[i % 4], vld1q_u32(k256.data() + (4 * i)));
            const uint32x4_t before = abcd;
            abcd = vsha256hq_u32(abcd, efgh, wk);
            efgh = vsha256h2q_u32(efgh, before, wk);
            // the next four words of the schedule, from the sixteen before them
            if (i < 12)
                m[i % 4] = vsha256su1q_u32(vsha256su0q_u32(m[i % 4], m[(i + 1) % 4]), m[(i + 2) % 4], m[(i + 3) % 4]);
        }
        abcd = vaddq_u32(abcd, abcd0);
        efgh = vaddq_u32(efgh, efgh0);
    }
    vst1q_u32(state.data(), abcd);
    vst1q_u32(state.data() + 4, efgh);
}

#endif

[[nodiscard]] ChecksumPaths detect_paths() noexcept {
    ChecksumPaths paths;
#if defined(__aarch64__) && defined(__APPLE__)
    // Every Apple arm64 core implements ARMv8.4-A or later, CRC32 and SHA-256 included.
    paths.crc32_instructions = true;
    paths.sha2_instructions = true;
#elif defined(__aarch64__) && defined(__linux__)
    const unsigned long caps = getauxval(AT_HWCAP);
    paths.crc32_instructions = (caps & HWCAP_CRC32) != 0;
    paths.sha2_instructions = (caps & HWCAP_SHA2) != 0;
#endif
    return paths;
}

void sha256_blocks(std::array<std::uint32_t, 8>& state, const std::byte* blocks, std::size_t count) noexcept {
#if defined(__aarch64__)
    if (checksum_paths().sha2_instructions) {
        sha256_blocks_instructions(state, blocks, count);
        return;
    }
#endif
    sha256_blocks_portable(state, blocks, count);
}

} // namespace

ChecksumPaths checksum_paths() noexcept {
    static const ChecksumPaths paths = detect_paths();
    return paths;
}

std::uint32_t crc32_portable(std::span<const std::byte> bytes, std::uint32_t crc) noexcept {
    const auto& t = crc_tables;
    std::uint32_t c = ~crc;
    const std::byte* p = bytes.data();
    std::size_t n = bytes.size();
    while (n >= 8) {
        const std::uint32_t lo = load32le(p) ^ c;
        const std::uint32_t hi = load32le(p + 4);
        c = t[7][lo & 0xFFU] ^ t[6][(lo >> 8U) & 0xFFU] ^ t[5][(lo >> 16U) & 0xFFU] ^ t[4][lo >> 24U] ^
            t[3][hi & 0xFFU] ^ t[2][(hi >> 8U) & 0xFFU] ^ t[1][(hi >> 16U) & 0xFFU] ^ t[0][hi >> 24U];
        p += 8;
        n -= 8;
    }
    for (; n > 0; --n) c = t[0][(c ^ std::to_integer<std::uint32_t>(*p++)) & 0xFFU] ^ (c >> 8U);
    return ~c;
}

std::uint32_t crc32(std::span<const std::byte> bytes, std::uint32_t crc) noexcept {
#if defined(__aarch64__)
    if (checksum_paths().crc32_instructions) return crc32_instructions(bytes, crc);
#endif
    return crc32_portable(bytes, crc);
}

void sha256_blocks_portable(std::array<std::uint32_t, 8>& state, const std::byte* blocks, std::size_t count) noexcept {
    for (; count > 0; --count, blocks += 64) {
        std::array<std::uint32_t, 64> w{};
        for (std::size_t i = 0; i < 16; ++i) w[i] = load32be(blocks + (4 * i));
        for (std::size_t i = 16; i < 64; ++i) {
            const std::uint32_t s0 = std::rotr(w[i - 15], 7) ^ std::rotr(w[i - 15], 18) ^ (w[i - 15] >> 3U);
            const std::uint32_t s1 = std::rotr(w[i - 2], 17) ^ std::rotr(w[i - 2], 19) ^ (w[i - 2] >> 10U);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        std::uint32_t a = state[0];
        std::uint32_t b = state[1];
        std::uint32_t c = state[2];
        std::uint32_t d = state[3];
        std::uint32_t e = state[4];
        std::uint32_t f = state[5];
        std::uint32_t g = state[6];
        std::uint32_t h = state[7];
        for (std::size_t i = 0; i < 64; ++i) {
            const std::uint32_t big1 = std::rotr(e, 6) ^ std::rotr(e, 11) ^ std::rotr(e, 25);
            const std::uint32_t choose = (e & f) ^ (~e & g);
            const std::uint32_t t1 = h + big1 + choose + k256[i] + w[i];
            const std::uint32_t big0 = std::rotr(a, 2) ^ std::rotr(a, 13) ^ std::rotr(a, 22);
            const std::uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
            const std::uint32_t t2 = big0 + majority;
            h = g;
            g = f;
            f = e;
            e = d + t1;
            d = c;
            c = b;
            b = a;
            a = t1 + t2;
        }
        state[0] += a;
        state[1] += b;
        state[2] += c;
        state[3] += d;
        state[4] += e;
        state[5] += f;
        state[6] += g;
        state[7] += h;
    }
}

Sha256::Sha256() noexcept { reset(); }

void Sha256::reset() noexcept {
    state_ = sha256_initial;
    fill_ = 0;
    total_ = 0;
}

void Sha256::update(std::span<const std::byte> bytes) noexcept {
    total_ += bytes.size();
    if (fill_ > 0) {
        const std::size_t n = std::min(bytes.size(), block_.size() - fill_);
        std::ranges::copy(bytes.first(n), block_.begin() + static_cast<std::ptrdiff_t>(fill_));
        fill_ += n;
        bytes = bytes.subspan(n);
        if (fill_ < block_.size()) return;
        sha256_blocks(state_, block_.data(), 1);
        fill_ = 0;
    }
    const std::size_t whole = bytes.size() / 64;
    if (whole > 0) sha256_blocks(state_, bytes.data(), whole);
    bytes = bytes.subspan(whole * 64);
    std::ranges::copy(bytes, block_.begin());
    fill_ = bytes.size();
}

Sha256Digest Sha256::finish() noexcept {
    const std::uint64_t bits = total_ * 8U;
    block_[fill_++] = std::byte{0x80};
    if (fill_ > 56) {
        std::fill(block_.begin() + static_cast<std::ptrdiff_t>(fill_), block_.end(), std::byte{0});
        sha256_blocks(state_, block_.data(), 1);
        fill_ = 0;
    }
    std::fill(block_.begin() + static_cast<std::ptrdiff_t>(fill_), block_.begin() + 56, std::byte{0});
    for (std::size_t k = 0; k < 8; ++k) block_[56 + k] = static_cast<std::byte>(bits >> (56U - (8U * k)));
    sha256_blocks(state_, block_.data(), 1);
    Sha256Digest out{};
    for (std::size_t i = 0; i < 8; ++i) {
        for (std::size_t k = 0; k < 4; ++k) out[(4 * i) + k] = static_cast<std::byte>(state_[i] >> (24U - (8U * k)));
    }
    reset();
    return out;
}

Sha256Digest Sha256::of(std::span<const std::byte> bytes) noexcept {
    Sha256 h;
    h.update(bytes);
    return h.finish();
}

std::string hex(const Sha256Digest& digest) {
    constexpr std::string_view digits = "0123456789abcdef";
    std::string out(64, '0');
    for (std::size_t i = 0; i < digest.size(); ++i) {
        const auto b = std::to_integer<unsigned>(digest[i]);
        out[2 * i] = digits[b >> 4U];
        out[(2 * i) + 1] = digits[b & 0xFU];
    }
    return out;
}

} // namespace atlas::tiles
