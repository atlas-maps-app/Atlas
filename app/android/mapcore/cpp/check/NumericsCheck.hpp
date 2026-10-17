// pymath's check (plan-vector K3b, Appendix C): every pymath function over a fixed set of inputs (its used ranges,
// the whole double range and the edges of its branches), the random streams from int, hashed and float seeds, and the
// hashes, folded into one 64-bit hash. The emulator and VK check print it as `pymath <hash>`, and it must equal the
// host build's, which equals the port's: MT/numerics/NumericsCheckTest.kt computes the same check from the port's own
// Kotlin (StrictMath, PyMath, PyRandom, PyHash) and holds numerics_expected to it.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string_view>

namespace atlas::check {

/// One part of the check: a family of functions and the FNV-1a hash of its results' bits, in order.
struct NumericsPart {
    std::string_view name;
    std::uint64_t hash = 0;
    std::uint32_t results = 0;
};

/// How many parts the check has.
inline constexpr std::size_t numerics_parts = 39;

/// The check's results: each part, and the FNV-1a hash of the parts' hashes.
struct Numerics {
    std::array<NumericsPart, numerics_parts> parts{};
    std::uint64_t total = 0;
};

/// Runs the check: 39 parts, about 134,000 results, about 6 ms on one core of this Mac.
[[nodiscard]] Numerics numerics() noexcept;

/// The total the port computes over the same inputs, on the JVM (MT/numerics/NumericsCheckTest.kt fails until this
/// equals it): what every host, the emulator and each phone must reproduce.
extern const std::uint64_t numerics_expected;

/// The log line, computed once: `pymath <16 hex digits> ok`, or `pymath <16 hex digits> FAIL expected <16 hex digits>`.
[[nodiscard]] std::string_view numerics_line() noexcept;

} // namespace atlas::check

/// The same line for C callers (exported from libatlasmap.so; K1's bridge logs it under the AtlasMap tag).
extern "C" [[gnu::visibility("default")]] const char* atlas_numerics_line() noexcept;
