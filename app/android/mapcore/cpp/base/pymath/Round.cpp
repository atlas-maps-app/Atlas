// Python's round() and int() as the port computes them (R/py/PyMath.kt): round(x) ties to even, round(x, n) correctly
// rounded to n decimals, round(n, k) for ints, int(x) by truncation; and Java's Math.round. round(x, n) takes the
// port's fast path when its result is certain, else rounds the exact binary value of x at its decimal place (the port's
// BigDecimal path) and converts that decimal to the nearest double (the port's parseDouble), both exactly, with a small
// fixed-size integer: no text, and no std::from_chars (which Apple's libc++ has only from macOS 26).
#include "base/assert.hpp"
#include "base/pymath/PyMath.hpp"
#include "base/pymath/fdlibm/Fdlibm.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <limits>
#include <source_location>

namespace atlas::pymath {

namespace {

/// 10^n for n in 0..22, each exact (the port's POW10, built by repeated multiplication).
constexpr std::array<double, 23> pow10 = [] {
    std::array<double, 23> p{};
    double v = 1.0;
    for (double& x : p) {
        x = v;
        v *= 10.0;
    }
    return p;
}();

/// Java's Math.ulp for a finite double: the distance to the next double away from zero.
[[nodiscard]] double ulp(double d) noexcept {
    const int exp = static_cast<int>((std::bit_cast<std::uint64_t>(d) >> 52U) & 0x7ffU) - 1023;
    if (exp == 1024) {
        return std::fabs(d);
    }
    if (exp == -1023) {
        return std::numeric_limits<double>::denorm_min();
    }
    const int e = exp - 52;
    if (e >= -1022) {
        return std::bit_cast<double>(static_cast<std::uint64_t>(e + 1023) << 52U);
    }
    return std::bit_cast<double>(std::uint64_t{1} << static_cast<unsigned>(e + 1074));
}

/// A non-negative integer of up to 3,200 bits in 32-bit limbs, least significant first: enough for every double scaled
/// by the powers of ten round(x, n) can need (at most about 2,600 bits). Nothing allocates.
class Big {
public:
    explicit Big(std::uint64_t v) noexcept : size_((v >> 32U) != 0 ? 2 : (v != 0 ? 1 : 0)) {
        limb_[0] = static_cast<std::uint32_t>(v);
        limb_[1] = static_cast<std::uint32_t>(v >> 32U);
    }

    [[nodiscard]] bool is_zero() const noexcept { return size_ == 0; }

    /// The number of significant bits (0 for zero).
    [[nodiscard]] int bit_length() const noexcept {
        if (size_ == 0) {
            return 0;
        }
        return static_cast<int>(32 * (size_ - 1)) + std::bit_width(limb_[size_ - 1]);
    }

    /// The low 64 bits.
    [[nodiscard]] std::uint64_t low64() const noexcept {
        const std::uint64_t lo = size_ > 0 ? limb_[0] : 0U;
        const std::uint64_t hi = size_ > 1 ? limb_[1] : 0U;
        return lo | (hi << 32U);
    }

    /// this *= m.
    void mul(std::uint32_t m) noexcept {
        std::uint64_t carry = 0;
        for (std::size_t i = 0; i < size_; ++i) {
            const std::uint64_t p = static_cast<std::uint64_t>(limb_[i]) * m + carry;
            limb_[i] = static_cast<std::uint32_t>(p);
            carry = p >> 32U;
        }
        if (carry != 0) {
            ATLAS_ASSERT(size_ < capacity, "round(x, n) needs more digits than it holds");
            limb_[size_++] = static_cast<std::uint32_t>(carry);
        }
    }

    /// this *= 5^k.
    void mul_pow5(int k) noexcept {
        for (; k >= 13; k -= 13) {
            mul(1220703125U); // 5^13
        }
        std::uint32_t rest = 1;
        for (; k > 0; --k) {
            rest *= 5U;
        }
        mul(rest);
    }

    /// this += a.
    void add(std::uint32_t a) noexcept {
        std::uint64_t carry = a;
        for (std::size_t i = 0; i < size_ && carry != 0; ++i) {
            const std::uint64_t s = static_cast<std::uint64_t>(limb_[i]) + carry;
            limb_[i] = static_cast<std::uint32_t>(s);
            carry = s >> 32U;
        }
        if (carry != 0) {
            ATLAS_ASSERT(size_ < capacity, "round(x, n) needs more digits than it holds");
            limb_[size_++] = static_cast<std::uint32_t>(carry);
        }
    }

    /// this /= d, returning the remainder.
    std::uint32_t div(std::uint32_t d) noexcept {
        std::uint64_t rem = 0;
        for (std::size_t i = size_; i-- > 0;) {
            const std::uint64_t cur = (rem << 32U) | limb_[i];
            limb_[i] = static_cast<std::uint32_t>(cur / d);
            rem = cur % d;
        }
        trim();
        return static_cast<std::uint32_t>(rem);
    }

    /// this <<= bits.
    void shift_left(int bits) noexcept {
        const auto limbs = static_cast<std::size_t>(bits / 32);
        const auto rest = static_cast<unsigned>(bits % 32);
        if (is_zero()) {
            return;
        }
        ATLAS_ASSERT(size_ + limbs + 1 <= capacity, "round(x, n) needs more digits than it holds");
        if (rest != 0) {
            limb_[size_] = 0;
            for (std::size_t i = size_ + 1; i-- > 1;) {
                limb_[i] = (limb_[i] << rest) | (limb_[i - 1] >> (32U - rest));
            }
            limb_[0] <<= rest;
            ++size_;
        }
        if (limbs != 0) {
            for (std::size_t i = size_; i-- > 0;) {
                limb_[i + limbs] = limb_[i];
            }
            std::fill_n(limb_.begin(), limbs, 0U);
            size_ += limbs;
        }
        trim();
    }

    /// Whether any of the bits below position `bit` is set.
    [[nodiscard]] bool any_below(int bit) const noexcept {
        const auto whole = static_cast<std::size_t>(bit / 32);
        for (std::size_t i = 0; i < std::min(whole, size_); ++i) {
            if (limb_[i] != 0) {
                return true;
            }
        }
        const auto rest = static_cast<unsigned>(bit % 32);
        return rest != 0 && whole < size_ && (limb_[whole] & ((1U << rest) - 1U)) != 0;
    }

    /// Bit `bit` (0 is the least significant).
    [[nodiscard]] bool test(int bit) const noexcept {
        const auto whole = static_cast<std::size_t>(bit / 32);
        return whole < size_ && ((limb_[whole] >> static_cast<unsigned>(bit % 32)) & 1U) != 0;
    }

    /// this >>= bits, dropping the shifted-out bits.
    void shift_right(int bits) noexcept {
        const auto limbs = static_cast<std::size_t>(bits / 32);
        const auto rest = static_cast<unsigned>(bits % 32);
        if (limbs >= size_) {
            size_ = 0;
            return;
        }
        for (std::size_t i = 0; i + limbs < size_; ++i) {
            limb_[i] = limb_[i + limbs];
        }
        size_ -= limbs;
        if (rest != 0) {
            for (std::size_t i = 0; i < size_; ++i) {
                const std::uint32_t next = i + 1 < size_ ? limb_[i + 1] : 0U;
                limb_[i] = (limb_[i] >> rest) | (next << (32U - rest));
            }
        }
        trim();
    }

    /// -1, 0 or 1 as this is below, equal to or above `o`.
    [[nodiscard]] int compare(const Big& o) const noexcept {
        if (size_ != o.size_) {
            return size_ < o.size_ ? -1 : 1;
        }
        for (std::size_t i = size_; i-- > 0;) {
            if (limb_[i] != o.limb_[i]) {
                return limb_[i] < o.limb_[i] ? -1 : 1;
            }
        }
        return 0;
    }

    /// this -= o, for o <= this.
    void subtract(const Big& o) noexcept {
        std::int64_t borrow = 0;
        for (std::size_t i = 0; i < size_; ++i) {
            const std::int64_t d = static_cast<std::int64_t>(limb_[i]) - (i < o.size_ ? o.limb_[i] : 0U) - borrow;
            borrow = d < 0 ? 1 : 0;
            limb_[i] = static_cast<std::uint32_t>(d + (borrow << 32));
        }
        trim();
    }

    /// Whether the lowest bit is set.
    [[nodiscard]] bool odd() const noexcept { return size_ != 0 && (limb_[0] & 1U) != 0; }

    static constexpr std::size_t capacity = 100;

private:
    void trim() noexcept {
        while (size_ != 0 && limb_[size_ - 1] == 0) {
            --size_;
        }
    }

    std::array<std::uint32_t, capacity> limb_{};
    std::size_t size_ = 0;
};

/// `n` / `d` rounded to the nearest integer, ties to even.
[[nodiscard]] Big divide_half_even(Big n, int shift, int fives) noexcept {
    // d = 2^shift * 5^fives. q = floor(n / d) through the shift and repeated division by 5; then the remainder
    // r = n - q * d decides the rounding against d / 2.
    Big q = n;
    q.shift_right(shift);
    for (int i = 0; i < fives; ++i) {
        (void)q.div(5U);
    }
    Big back = q;
    back.mul_pow5(fives);
    back.shift_left(shift);
    Big r = n;
    r.subtract(back);
    Big twice_r = r;
    twice_r.shift_left(1);
    Big d(1);
    d.mul_pow5(fives);
    d.shift_left(shift);
    const int c = twice_r.compare(d);
    if (c > 0 || (c == 0 && q.odd())) {
        q.add(1U);
    }
    return q;
}

/// The double nearest q * 10^e10 (q > 0), ties to even: what Java's parseDouble reads from the port's decimal text.
[[nodiscard]] double nearest_double(Big q, int e10) noexcept {
    // value = (q + f) * 2^e2 with q an integer and f in [0, 1) known only as zero or not (sticky); q has at least 64
    // bits where a division made it, so one rounding at the right bit is the correct one.
    int e2 = 0;
    bool sticky = false;
    if (e10 >= 0) {
        q.mul_pow5(e10); // q * 10^e10 = (q * 5^e10) * 2^e10, exact
        e2 = e10;
    } else {
        const int k = -e10; // q / 10^k = q / 5^k * 2^-k
        Big five_k(1);
        five_k.mul_pow5(k);
        const int s = std::max(0, 65 + five_k.bit_length() - q.bit_length());
        q.shift_left(s);
        Big quotient = q;
        for (int i = 0; i < k; ++i) {
            (void)quotient.div(5U); // floor(q * 2^s / 5^k), one exact floor division at a time
        }
        Big back = quotient;
        back.mul_pow5(k);
        sticky = back.compare(q) != 0;
        q = quotient;
        e2 = -k - s;
    }
    // Keep 53 bits, or fewer where the result is below 2^-1022: its last bit is then 2^-1074's place.
    int drop = q.bit_length() - 53;
    if (e2 + drop < -1074) {
        drop = -1074 - e2;
    }
    if (drop <= 0) {
        return fdlibm::scalbn(static_cast<double>(q.low64()), e2); // fewer than 54 bits: exact (sticky is false here)
    }
    const bool half = q.test(drop - 1);
    const bool rest = sticky || q.any_below(drop - 1);
    q.shift_right(drop);
    if (half && (rest || q.odd())) {
        q.add(1U);
    }
    // q now fits in 54 bits (2^53 after a carry is a power of two), so its double is exact, and the scaling rounds
    // nothing but an overflow to infinity.
    return fdlibm::scalbn(static_cast<double>(q.low64()), e2 + drop);
}

/// The significant digits decimal_to_double keeps: more than the 767 a double's exact decimal can have.
constexpr std::size_t kept_digits = 780;

/// The port's BigDecimal path: BigDecimal(x).setScale(n, HALF_EVEN), then parseDouble of its text.
[[nodiscard]] double round_exact(double x, int n) noexcept {
    // A double has at most 1,074 binary, so 1,074 decimal, places: at n >= 1,075 the scale keeps x exactly. Every
    // finite double is below 10^309 / 2, so at n <= -309 it rounds to zero.
    if (n >= 1075) {
        return x;
    }
    if (n <= -309) {
        return std::copysign(0.0, x);
    }
    const auto bits = std::bit_cast<std::uint64_t>(x);
    const int biased = static_cast<int>((bits >> 52U) & 0x7ffU);
    std::uint64_t mant = bits & ((std::uint64_t{1} << 52U) - 1U);
    int exp = 0; // |x| = mant * 2^exp
    if (biased == 0) {
        exp = -1074;
    } else {
        mant |= std::uint64_t{1} << 52U;
        exp = biased - 1075;
    }
    // x * 10^n = mant * 2^(exp + n) * 5^n.
    Big q(0);
    if (n >= 0) {
        if (exp + n >= 0) {
            return x; // x * 10^n is already an integer: the decimal is exact
        }
        Big num(mant);
        num.mul_pow5(n);
        q = divide_half_even(num, -(exp + n), 0);
    } else {
        Big num(mant);
        if (exp + n >= 0) {
            num.shift_left(exp + n);
            q = divide_half_even(num, 0, -n);
        } else {
            q = divide_half_even(num, -(exp + n), -n);
        }
    }
    if (q.is_zero()) {
        return std::copysign(0.0, x);
    }
    return std::copysign(nearest_double(q, -n), x); // a zero keeps x's sign, as the port's copySign
}

/// The int nearest a whole double, if it fits in 64 bits (the port's toLong, or longValueExact past 9.2e18).
[[nodiscard]] Result<std::int64_t> whole_to_int(double r) noexcept {
    if (!std::isfinite(r)) {
        return std::unexpected(Error::not_finite);
    }
    if (r >= -9223372036854775808.0 && r < 9223372036854775808.0) {
        return static_cast<std::int64_t>(r);
    }
    return std::unexpected(Error::too_large);
}

} // namespace

Result<std::int64_t> checked::round(double x) noexcept {
    if (!std::isfinite(x)) {
        return std::unexpected(Error::not_finite);
    }
    return whole_to_int(std::rint(x)); // Math.rint: ties to even
}

Result<std::int64_t> checked::to_int(double x) noexcept {
    if (!std::isfinite(x)) {
        return std::unexpected(Error::not_finite);
    }
    return whole_to_int(std::trunc(x));
}

Result<std::int64_t> checked::floor_int(double x) noexcept { return to_int(std::floor(x)); }
Result<std::int64_t> checked::ceil_int(double x) noexcept { return to_int(std::ceil(x)); }

std::int64_t round(double x, std::source_location where) noexcept { return value_or_fail(checked::round(x), where); }
std::int64_t to_int(double x, std::source_location where) noexcept { return value_or_fail(checked::to_int(x), where); }

std::int64_t floor_int(double x, std::source_location where) noexcept {
    return value_or_fail(checked::floor_int(x), where);
}

std::int64_t ceil_int(double x, std::source_location where) noexcept {
    return value_or_fail(checked::ceil_int(x), where);
}

double round(double x, int n) noexcept {
    if (!std::isfinite(x) || x == 0.0) {
        return x;
    }
    if (n >= 0 && n <= 15) {
        const double p = pow10[static_cast<std::size_t>(n)];
        const double s = x * p;
        if (std::fabs(s) < 1e15) {
            const double r = std::floor(s);
            const double f = s - r;
            const double tol = ulp(s) * 2;
            if (std::fabs(f - 0.5) > tol) {
                const double k = f < 0.5 ? r : r + 1.0;
                const double out = k / p;
                return out == 0.0 ? std::copysign(0.0, x) : out;
            }
        }
    }
    return round_exact(x, n);
}

double decimal_to_double(std::string_view digits, std::int64_t e10) noexcept {
    // the significant digits: no leading zeros, the trailing ones moved into the exponent
    const std::size_t first = digits.find_first_not_of('0');
    if (first == std::string_view::npos) {
        return 0.0;
    }
    digits.remove_prefix(first);
    const std::size_t last = digits.find_last_not_of('0');
    e10 += static_cast<std::int64_t>(digits.size() - 1 - last);
    digits = digits.substr(0, last + 1);
    // the value lies in [10^(n - 1 + e10), 10^(n + e10)): past the doubles, or below half the least of them
    const auto n = static_cast<std::int64_t>(digits.size());
    if (n + e10 > 310) {
        return std::numeric_limits<double>::infinity();
    }
    if (n + e10 < -324) {
        return 0.0;
    }
    if (n <= 15 && e10 >= -22 && e10 <= 22) {
        // below 10^15 < 2^53 and a power of ten up to 10^22: both exact, so one IEEE operation rounds once, correctly
        std::uint64_t m = 0;
        for (const char c : digits) {
            m = (m * 10) + static_cast<std::uint64_t>(c - '0');
        }
        const auto v = static_cast<double>(m);
        return e10 >= 0 ? v * pow10[static_cast<std::size_t>(e10)] : v / pow10[static_cast<std::size_t>(-e10)];
    }
    // past the digits a rounding can need, whether any more follow (the last digit is not 0, so some does): a 1 below
    // the kept ones stands for them
    bool more = false;
    if (digits.size() > kept_digits) {
        e10 += static_cast<std::int64_t>(digits.size() - kept_digits);
        digits = digits.substr(0, kept_digits);
        more = true;
    }
    Big q(0);
    for (std::size_t i = 0; i < digits.size(); i += 9) {
        const std::size_t len = std::min<std::size_t>(9, digits.size() - i);
        std::uint32_t chunk = 0;
        std::uint32_t scale = 1;
        for (std::size_t j = 0; j < len; ++j) {
            chunk = (chunk * 10) + static_cast<std::uint32_t>(digits[i + j] - '0');
            scale *= 10;
        }
        q.mul(scale);
        q.add(chunk);
    }
    if (more) {
        q.mul(10);
        q.add(1);
        e10 -= 1;
    }
    return nearest_double(q, static_cast<int>(e10));
}

std::int64_t java::round(double x) noexcept {
    // Math.round's own form is a shift of the significand; floor(x) + (x - floor(x) >= 0.5) is the same exact
    // floor(x + 1/2): x - floor(x) is exact wherever it decides (it can round only for x in (-0.5, 0), where it stays
    // at or above 0.5 either way), and x + 0.5 itself is never formed, so it cannot round up past a half.
    if (std::isnan(x)) {
        return 0;
    }
    if (x >= 9223372036854775808.0) {
        return std::numeric_limits<std::int64_t>::max();
    }
    if (x <= -9223372036854775808.0) {
        return std::numeric_limits<std::int64_t>::min();
    }
    const double f = std::floor(x); // within Long's range: |x| < 2^63, and at 2^52 and above x is whole
    return static_cast<std::int64_t>(f) + (x - f >= 0.5 ? 1 : 0);
}

std::int64_t round(std::int64_t n, int k) noexcept {
    // BigDecimal.valueOf(n).setScale(k, HALF_EVEN).toLong(): toLong keeps the low 64 bits of a multiple that overflows.
    if (k >= 0) {
        return n;
    }
    if (k < -19) {
        return 0; // |n| < 2^63 < 10^20 / 2
    }
    const std::uint64_t magnitude = n < 0 ? 0U - static_cast<std::uint64_t>(n) : static_cast<std::uint64_t>(n);
    if (k == -19) {
        constexpr std::uint64_t ten19 = 10'000'000'000'000'000'000ULL;
        if (magnitude <= ten19 / 2) {
            return 0; // a tie rounds to the even multiple, 0
        }
        return static_cast<std::int64_t>(n < 0 ? 0U - ten19 : ten19);
    }
    std::uint64_t p = 1;
    for (int i = 0; i < -k; ++i) {
        p *= 10U;
    }
    std::uint64_t q = magnitude / p;
    const std::uint64_t twice = 2 * (magnitude % p);
    if (twice > p || (twice == p && (q & 1U) != 0)) {
        ++q;
    }
    const std::uint64_t value = q * p; // wraps past 2^64 as toLong's low 64 bits do
    return static_cast<std::int64_t>(n < 0 ? 0U - value : value);
}

} // namespace atlas::pymath
