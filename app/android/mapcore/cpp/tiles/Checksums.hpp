// The pack's checksums (plan-vector D2, Appendix A): CRC-32 (java.util.zip.CRC32's, IEEE 802.3) of each section's
// stored bytes, and SHA-256 (FIPS 180-4) of each chunk's decompressed bytes. Both are the hashes Java computed when the
// pack was written, so the core recomputes them bit for bit. On arm64 the CPU's CRC32 and SHA-2 instructions are used
// when it has them (asked once at run time), else portable code; every path gives the same bytes (tests/tiles).
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>

namespace atlas::tiles {

/// CRC-32 of `bytes`, continuing from `crc` (0 to start), as java.util.zip.CRC32 computes it.
[[nodiscard]] std::uint32_t crc32(std::span<const std::byte> bytes, std::uint32_t crc = 0) noexcept;

/// A SHA-256 digest.
using Sha256Digest = std::array<std::byte, 32>;

/// SHA-256, fed in pieces.
class Sha256 {
public:
    Sha256() noexcept;

    /// Adds bytes.
    void update(std::span<const std::byte> bytes) noexcept;

    /// The digest of everything added; the hash is then reset.
    [[nodiscard]] Sha256Digest finish() noexcept;

    /// The digest of `bytes`.
    [[nodiscard]] static Sha256Digest of(std::span<const std::byte> bytes) noexcept;

private:
    void reset() noexcept;

    std::array<std::uint32_t, 8> state_{};
    std::array<std::byte, 64> block_{};
    std::size_t fill_ = 0;
    std::uint64_t total_ = 0;
};

/// The digest in lower-case hex.
[[nodiscard]] std::string hex(const Sha256Digest& digest);

/// Which checksum paths this CPU takes (for the tests and VK facts).
struct ChecksumPaths {
    bool crc32_instructions = false;
    bool sha2_instructions = false;
};

[[nodiscard]] ChecksumPaths checksum_paths() noexcept;

/// The portable paths, which every CPU can run: the tests hold the fast paths to them.
[[nodiscard]] std::uint32_t crc32_portable(std::span<const std::byte> bytes, std::uint32_t crc = 0) noexcept;
void sha256_blocks_portable(std::array<std::uint32_t, 8>& state, const std::byte* blocks, std::size_t count) noexcept;

} // namespace atlas::tiles
