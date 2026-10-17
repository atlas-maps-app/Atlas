// CPython's random.Random as the port computes it (R/py/PyRandom.kt): the MT19937 generator with Python's seeding
// (int seeds through init_by_array; float seeds through their hash, as CPython 3.9 seeds anything that is not an int)
// and the methods the renders draw from: random, uniform, gauss (with its cached second value), choice, shuffle,
// randrange, randint and getrandbits. Seeds made from hashes of tuples of floats come from base/pymath/PyHash.hpp.
#pragma once

#include "base/pymath/PyMath.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <source_location>
#include <span>
#include <utility>

namespace atlas::pymath {

/// One random.Random stream, bit for bit. Not thread-safe; each owner draws from its own (as the renders do).
class PyRandom {
public:
    /// `random.Random(n)` for an int seed.
    explicit PyRandom(std::int64_t n) noexcept { seed(n); }

    /// `seed(n)` for an int: its magnitude, as init_by_array's key.
    void seed(std::int64_t n) noexcept;
    /// `seed(n)` for a non-negative int up to 2^64 - 1.
    void seed_unsigned(std::uint64_t n) noexcept;
    /// `seed(n)` for an int of any size, given as its magnitude in 32-bit words, least significant first.
    void seed_words(std::span<const std::uint32_t> magnitude) noexcept;
    /// `seed(x)` for a float: its hash, as an unsigned 64-bit int (CPython 3.9); a NaN has no reproducible hash.
    [[nodiscard]] Result<void> seed_float(double x) noexcept;

    /// One 32-bit output of the twister.
    [[nodiscard]] std::uint32_t genrand_uint32() noexcept {
        if (index_ >= state_words) {
            twist();
        }
        std::uint32_t y = mt_[index_++];
        y ^= y >> 11U;
        y ^= (y << 7U) & 0x9d2c5680U;
        y ^= (y << 15U) & 0xefc60000U;
        y ^= y >> 18U;
        return y;
    }

    /// `random()`: a float in [0, 1) with 53 random bits.
    [[nodiscard]] double random() noexcept {
        const std::uint32_t a = genrand_uint32() >> 5U;
        const std::uint32_t b = genrand_uint32() >> 6U;
        return (static_cast<double>(a) * 67108864.0 + static_cast<double>(b)) * (1.0 / 9007199254740992.0);
    }

    /// `getrandbits(k)` for k in 0..64.
    [[nodiscard]] std::uint64_t getrandbits(int k) noexcept;

    /// `_randbelow(n)`: an int in [0, n), 0 when n is 0 (n must not be negative).
    [[nodiscard]] std::int64_t randbelow(std::int64_t n) noexcept;

    /// `randrange(stop)`.
    [[nodiscard]] Result<std::int64_t> checked_randrange(std::int64_t stop) noexcept;
    /// `randrange(start, stop, step)`.
    [[nodiscard]] Result<std::int64_t> checked_randrange(std::int64_t start, std::int64_t stop,
                                                         std::int64_t step = 1) noexcept;

    /// `randrange(stop)`, ending the process on an empty range as Python would raise.
    [[nodiscard]] std::int64_t randrange(std::int64_t stop,
                                         std::source_location where = std::source_location::current()) noexcept {
        return value_or_fail(checked_randrange(stop), where);
    }
    /// `randrange(start, stop, step)`, ending the process on an empty range as Python would raise.
    [[nodiscard]] std::int64_t randrange(std::int64_t start, std::int64_t stop, std::int64_t step,
                                         std::source_location where = std::source_location::current()) noexcept {
        return value_or_fail(checked_randrange(start, stop, step), where);
    }
    /// `randint(a, b)`: an int in [a, b].
    [[nodiscard]] std::int64_t randint(std::int64_t a, std::int64_t b,
                                       std::source_location where = std::source_location::current()) noexcept {
        return value_or_fail(checked_randrange(a, b + 1), where);
    }

    /// The index `choice` picks in a sequence of `size` items; Python raises for an empty sequence.
    [[nodiscard]] Result<std::size_t> checked_choice_index(std::size_t size) noexcept;

    /// `choice(seq)` of a non-empty sequence.
    template <class T, std::size_t Extent>
    [[nodiscard]] T& choice(std::span<T, Extent> seq,
                            std::source_location where = std::source_location::current()) noexcept {
        return seq[value_or_fail(checked_choice_index(seq.size()), where)];
    }

    /// `shuffle(x)` in place.
    template <class T, std::size_t Extent>
    void shuffle(std::span<T, Extent> x) noexcept {
        for (std::size_t i = x.size(); i-- > 1;) {
            const auto j = static_cast<std::size_t>(randbelow(static_cast<std::int64_t>(i) + 1));
            std::swap(x[i], x[j]);
        }
    }

    /// `uniform(a, b)`.
    [[nodiscard]] double uniform(double a, double b) noexcept { return a + (b - a) * random(); }

    /// `gauss(mu, sigma)`, with its cached second value.
    [[nodiscard]] double gauss(double mu = 0.0, double sigma = 1.0) noexcept;

private:
    static constexpr std::size_t state_words = 624; ///< MT19937's N
    static constexpr std::size_t middle = 397;      ///< MT19937's M

    void init_genrand(std::uint32_t s) noexcept;
    void init_by_array(std::span<const std::uint32_t> key) noexcept;
    void twist() noexcept;

    std::array<std::uint32_t, state_words> mt_{};
    std::size_t index_ = state_words + 1;
    double gauss_next_ = 0.0;
    bool has_gauss_next_ = false;
};

} // namespace atlas::pymath
