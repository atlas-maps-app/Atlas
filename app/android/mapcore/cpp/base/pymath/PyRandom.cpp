// CPython's random.Random as the port computes it (R/py/PyRandom.kt): Modules/_randommodule.c's MT19937 and seeding,
// and Lib/random.py's methods of CPython 3.9.
#include "base/pymath/PyRandom.hpp"

#include "base/assert.hpp"
#include "base/pymath/PyHash.hpp"

#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <span>

namespace atlas::pymath {

namespace {

constexpr std::uint32_t matrix_a = 0x9908b0dfU;
constexpr std::uint32_t upper = 0x80000000U;
constexpr std::uint32_t lower = 0x7fffffffU;

/// An unsigned 64-bit int's magnitude as init_by_array's key: its 32-bit words, at least one.
struct Key {
    std::array<std::uint32_t, 2> words{};
    std::size_t used = 1;
};

[[nodiscard]] Key key_of(std::uint64_t magnitude) noexcept {
    Key k;
    k.words[0] = static_cast<std::uint32_t>(magnitude);
    k.words[1] = static_cast<std::uint32_t>(magnitude >> 32U);
    k.used = k.words[1] != 0 ? 2 : 1;
    return k;
}

/// init_genrand(s): the state MT19937 grows from one word.
template <std::size_t N>
[[nodiscard]] constexpr std::array<std::uint32_t, N> genrand_state(std::uint32_t s) noexcept {
    std::array<std::uint32_t, N> mt{};
    mt[0] = s;
    for (std::size_t i = 1; i < N; ++i) {
        mt[i] = 1812433253U * (mt[i - 1] ^ (mt[i - 1] >> 30U)) + static_cast<std::uint32_t>(i);
    }
    return mt;
}

} // namespace

void PyRandom::init_genrand(std::uint32_t s) noexcept {
    // Every seed starts from init_genrand(19650218); that state is computed once, when the core compiles.
    static constexpr std::array<std::uint32_t, state_words> from_19650218 = genrand_state<state_words>(19650218U);
    mt_ = s == 19650218U ? from_19650218 : genrand_state<state_words>(s);
    index_ = state_words;
}

void PyRandom::init_by_array(std::span<const std::uint32_t> key) noexcept {
    init_genrand(19650218U);
    std::size_t i = 1;
    std::size_t j = 0;
    for (std::size_t k = state_words > key.size() ? state_words : key.size(); k > 0; --k) {
        mt_[i] = (mt_[i] ^ ((mt_[i - 1] ^ (mt_[i - 1] >> 30U)) * 1664525U)) + key[j] + static_cast<std::uint32_t>(j);
        ++i;
        ++j;
        if (i >= state_words) {
            mt_[0] = mt_[state_words - 1];
            i = 1;
        }
        if (j >= key.size()) {
            j = 0;
        }
    }
    for (std::size_t k = state_words - 1; k > 0; --k) {
        mt_[i] = (mt_[i] ^ ((mt_[i - 1] ^ (mt_[i - 1] >> 30U)) * 1566083941U)) - static_cast<std::uint32_t>(i);
        ++i;
        if (i >= state_words) {
            mt_[0] = mt_[state_words - 1];
            i = 1;
        }
    }
    mt_[0] = 0x80000000U; // MSB is 1, assuring a non-zero initial array
}

void PyRandom::twist() noexcept {
    std::size_t kk = 0;
    for (; kk < state_words - middle; ++kk) {
        const std::uint32_t y = (mt_[kk] & upper) | (mt_[kk + 1] & lower);
        mt_[kk] = mt_[kk + middle] ^ (y >> 1U) ^ ((y & 1U) != 0 ? matrix_a : 0U);
    }
    for (; kk < state_words - 1; ++kk) {
        const std::uint32_t y = (mt_[kk] & upper) | (mt_[kk + 1] & lower);
        mt_[kk] = mt_[kk + middle - state_words] ^ (y >> 1U) ^ ((y & 1U) != 0 ? matrix_a : 0U);
    }
    const std::uint32_t y = (mt_[state_words - 1] & upper) | (mt_[0] & lower);
    mt_[state_words - 1] = mt_[middle - 1] ^ (y >> 1U) ^ ((y & 1U) != 0 ? matrix_a : 0U);
    index_ = 0;
}

void PyRandom::seed(std::int64_t n) noexcept {
    seed_unsigned(n < 0 ? 0U - static_cast<std::uint64_t>(n) : static_cast<std::uint64_t>(n));
}

void PyRandom::seed_unsigned(std::uint64_t n) noexcept {
    const Key k = key_of(n);
    init_by_array(std::span(k.words.data(), k.used));
    has_gauss_next_ = false;
}

void PyRandom::seed_words(std::span<const std::uint32_t> magnitude) noexcept {
    std::size_t used = magnitude.size();
    while (used > 1 && magnitude[used - 1] == 0) {
        --used; // the int's own length: no leading zero words
    }
    if (used == 0) {
        seed_unsigned(0);
        return;
    }
    init_by_array(magnitude.first(used));
    has_gauss_next_ = false;
}

Result<void> PyRandom::seed_float(double x) noexcept {
    const auto h = hash_float(x);
    if (!h) {
        return std::unexpected(h.error());
    }
    seed_unsigned(static_cast<std::uint64_t>(*h)); // the hash as a size_t
    return {};
}

std::uint64_t PyRandom::getrandbits(int k) noexcept {
    ATLAS_ASSERT(k >= 0 && k <= 64, "getrandbits takes 0 to 64 bits here");
    if (k == 0) {
        return 0;
    }
    if (k <= 32) {
        return genrand_uint32() >> static_cast<unsigned>(32 - k);
    }
    const std::uint64_t lo = genrand_uint32();
    const std::uint64_t hi = genrand_uint32() >> static_cast<unsigned>(64 - k);
    return lo | (hi << 32U);
}

std::int64_t PyRandom::randbelow(std::int64_t n) noexcept {
    ATLAS_ASSERT(n >= 0, "randbelow of a negative bound");
    if (n == 0) {
        return 0;
    }
    const auto bound = static_cast<std::uint64_t>(n);
    const int k = std::bit_width(bound);
    std::uint64_t r = getrandbits(k);
    while (r >= bound) {
        r = getrandbits(k);
    }
    return static_cast<std::int64_t>(r);
}

Result<std::int64_t> PyRandom::checked_randrange(std::int64_t stop) noexcept {
    if (stop <= 0) {
        return std::unexpected(Error::empty_range);
    }
    return randbelow(stop);
}

Result<std::int64_t> PyRandom::checked_randrange(std::int64_t start, std::int64_t stop, std::int64_t step) noexcept {
    const std::int64_t width = stop - start;
    if (step == 1) {
        if (width <= 0) {
            return std::unexpected(Error::empty_range);
        }
        return start + randbelow(width);
    }
    std::int64_t count = 0;
    if (step > 0) {
        count = floor_div(width + step - 1, step);
    } else if (step < 0) {
        count = floor_div(width + step + 1, step);
    } else {
        return std::unexpected(Error::empty_range); // zero step for randrange()
    }
    if (count <= 0) {
        return std::unexpected(Error::empty_range);
    }
    return start + step * randbelow(count);
}

Result<std::size_t> PyRandom::checked_choice_index(std::size_t size) noexcept {
    if (size == 0) {
        return std::unexpected(Error::empty_sequence);
    }
    return static_cast<std::size_t>(randbelow(static_cast<std::int64_t>(size)));
}

double PyRandom::gauss(double mu, double sigma) noexcept {
    double z = gauss_next_;
    const bool cached = has_gauss_next_;
    has_gauss_next_ = false;
    if (!cached) {
        const double x2pi = random() * tau;
        // math.log(1.0 - random()): 1.0 - random() is in (0, 1], so Python's check never fires.
        const double g2rad = std::sqrt(-2.0 * strict::log(1.0 - random()));
        z = strict::cos(x2pi) * g2rad;
        gauss_next_ = strict::sin(x2pi) * g2rad;
        has_gauss_next_ = true;
    }
    return mu + z * sigma;
}

} // namespace atlas::pymath
