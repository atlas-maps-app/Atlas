// pymath's check (plan-vector K3b): see check/NumericsCheck.hpp. MT/numerics/NumericsCheckTest.kt mirrors this file
// line for line on the JVM with the port's own functions, so the two must change together: same parts in the same
// order, same inputs from the same generator, same results folded the same way. Arguments are always drawn into named
// values first, in order, since C++ leaves the order of a call's arguments open.
#include "check/NumericsCheck.hpp"

#include "base/pymath/PyHash.hpp"
#include "base/pymath/PyMath.hpp"
#include "base/pymath/PyRandom.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>

namespace atlas::check {

// The port's total over these inputs, from MT/numerics/NumericsCheckTest.kt on the JVM (Temurin 17, StrictMath).
const std::uint64_t numerics_expected = 0x77476B808456A6F0U;

namespace {

namespace pm = atlas::pymath;

constexpr std::uint64_t fnv_offset = 0xcbf29ce484222325U;
constexpr std::uint64_t fnv_prime = 0x100000001b3U;
constexpr std::uint64_t error_mark = 0xE5E5E5E5E5E5E5E5U; // a result Python raises for (a NaN pattern no result has)
constexpr std::uint64_t nan_bits = 0x7ff8000000000000U;   // every NaN, whatever its payload

/// FNV-1a over 64-bit words in little-endian order.
class Hasher {
public:
    void word(std::uint64_t w) noexcept {
        for (unsigned i = 0; i < 8; ++i) {
            hash_ ^= (w >> (8U * i)) & 0xffU;
            hash_ *= fnv_prime;
        }
        ++count_;
    }
    void real(double d) noexcept { word(std::isnan(d) ? nan_bits : std::bit_cast<std::uint64_t>(d)); }
    void integer(std::int64_t v) noexcept { word(static_cast<std::uint64_t>(v)); }
    void error() noexcept { word(error_mark); }
    void result(const pm::Result<double>& r) noexcept { r ? real(*r) : error(); }
    void result(const pm::Result<std::int64_t>& r) noexcept { r ? integer(*r) : error(); }

    [[nodiscard]] std::uint64_t hash() const noexcept { return hash_; }
    [[nodiscard]] std::uint32_t count() const noexcept { return count_; }

private:
    std::uint64_t hash_ = fnv_offset;
    std::uint32_t count_ = 0;
};

/// SplitMix64, the inputs' generator (reproducible with Kotlin's Long arithmetic).
class Inputs {
public:
    explicit Inputs(std::uint64_t seed) noexcept : state_(seed) {}

    std::uint64_t next() noexcept {
        state_ += 0x9E3779B97F4A7C15U;
        std::uint64_t z = state_;
        z = (z ^ (z >> 30U)) * 0xBF58476D1CE4E5B9U;
        z = (z ^ (z >> 27U)) * 0x94D049BB133111EBU;
        return z ^ (z >> 31U);
    }
    /// A double in [0, 1) with 53 random bits.
    double unit() noexcept { return static_cast<double>(next() >> 11U) * 0x1p-53; }
    /// lo + (hi - lo) * unit().
    double in(double lo, double hi) noexcept {
        const double u = unit();
        return lo + (hi - lo) * u;
    }
    /// Any finite double, its exponent uniform: every magnitude from the subnormals to the largest, either sign.
    double magnitude() noexcept {
        const std::uint64_t r = next();
        const std::uint64_t e = (r >> 52U) % 2047U;
        const std::uint64_t m = next() & 0xFFFFFFFFFFFFFU;
        return std::bit_cast<double>((r & 0x8000000000000000U) | (e << 52U) | m);
    }
    /// An int in [lo, hi].
    std::int64_t integer(std::int64_t lo, std::int64_t hi) noexcept {
        const auto span = static_cast<std::uint64_t>(hi - lo) + 1U;
        return lo + static_cast<std::int64_t>(next() % span);
    }

private:
    std::uint64_t state_;
};

/// The edges of fdlibm's branches and Python's checks, as bits.
constexpr std::array<std::uint64_t, 48> edges{
    0x0000000000000000U, 0x8000000000000000U, 0x0000000000000001U, 0x8000000000000001U, // zeros, smallest subnormals
    0x0010000000000000U, 0x8010000000000000U, 0x3FE0000000000000U, 0xBFE0000000000000U, // smallest normals, 0.5
    0x3FF0000000000000U, 0xBFF0000000000000U, 0x4000000000000000U, 0xC000000000000000U, // 1, 2
    0x3FE921FB54442D18U, 0xBFE921FB54442D18U, 0x3FF921FB54442D18U, 0xBFF921FB54442D18U, // pi/4, pi/2
    0x400921FB54442D18U, 0xC00921FB54442D18U, 0x4002D97C7F3321D2U, 0xC002D97C7F3321D2U, // pi, 3pi/4
    0x413921FB54442D18U, 0xC13921FB54442D18U, 0x3E40000000000000U, 0xBE40000000000000U, // 2^19 pi/2, 2^-27
    0x3E30000000000000U, 0xBE30000000000000U, 0x3C90000000000000U, 0xBC90000000000000U, // 2^-28, 2^-54
    0x3FDC000000000000U, 0xBFDC000000000000U, 0x3FEF333333333333U, 0xBFEF333333333333U, // 0.4375, 0.975
    0x40862E42FEFA39EFU, 0xC0862E42FEFA39EFU, 0x40874910D52D3051U, 0xC0874910D52D3051U, // exp's thresholds
    0x7E37E43C8800759CU, 0xFE37E43C8800759CU, 0x7FEFFFFFFFFFFFFFU, 0xFFEFFFFFFFFFFFFFU, // 1e300, the largest
    0x7FF0000000000000U, 0xFFF0000000000000U, 0x7FF8000000000000U, 0x3FF0000000000001U, // infinities, NaN, 1+ulp
    0x3FEFFFFFFFFFFFFFU, 0x4340000000000000U, 0x41E0000000000000U, 0x43F0000000000000U, // 1-ulp/2, 2^53, 2^31, 2^64
};

/// Sixteen of them for the pairs of two-argument functions.
constexpr std::array<std::uint64_t, 16> pair_edges{
    0x0000000000000000U, 0x8000000000000000U, 0x0000000000000001U, 0x3FE0000000000000U,
    0x3FF0000000000000U, 0xBFF0000000000000U, 0x4000000000000000U, 0xC000000000000000U,
    0x3FF921FB54442D18U, 0x40862E42FEFA39EFU, 0x7FEFFFFFFFFFFFFFU, 0x7FF0000000000000U,
    0xFFF0000000000000U, 0x7FF8000000000000U, 0x4340000000000000U, 0x3FF0000000000001U,
};

static_assert(std::bit_cast<std::uint64_t>(1e300) == 0x7E37E43C8800759CU);
static_assert(std::bit_cast<std::uint64_t>(3 * pm::pi / 4) == 0x4002D97C7F3321D2U);

[[nodiscard]] double edge(std::size_t i) noexcept { return std::bit_cast<double>(edges[i]); }
[[nodiscard]] double pair_edge(std::size_t i) noexcept { return std::bit_cast<double>(pair_edges[i]); }

constexpr double two_pi = 2 * pm::pi;

/// Folds a double function over its ranges and the edges.
template <class F>
void unary(Hasher& h, Inputs& in, const F& f, double lo, double hi, int ranged, double lo2, double hi2, int ranged2,
           int magnitudes) {
    for (int i = 0; i < ranged; ++i) {
        const double x = in.in(lo, hi);
        h.real(f(x));
    }
    for (int i = 0; i < ranged2; ++i) {
        const double x = in.in(lo2, hi2);
        h.real(f(x));
    }
    for (int i = 0; i < magnitudes; ++i) {
        const double x = in.magnitude();
        h.real(f(x));
    }
    for (std::size_t i = 0; i < edges.size(); ++i) {
        h.real(f(edge(i)));
    }
}

/// Folds a two-argument function's results (each a Result or a double) over pairs of edges.
template <class F>
void edge_pairs(Hasher& h, const F& f) {
    for (std::size_t i = 0; i < pair_edges.size(); ++i) {
        for (std::size_t j = 0; j < pair_edges.size(); ++j) {
            f(h, pair_edge(i), pair_edge(j));
        }
    }
}

void strict_trig(Hasher& h, Inputs& in, double (*f)(double)) {
    unary(h, in, f, -two_pi, two_pi, 2048, -1e6, 1e6, 1024, 1024);
}

void hypot2(Hasher& h, Inputs& in, pm::Compat c) {
    for (int i = 0; i < 2048; ++i) {
        const double x = in.in(-1e3, 1e3);
        const double y = in.in(-1e3, 1e3);
        h.real(pm::hypot(x, y, c));
    }
    for (int i = 0; i < 512; ++i) {
        const double x = in.magnitude();
        const double y = in.magnitude();
        h.real(pm::hypot(x, y, c));
    }
    edge_pairs(h, [c](Hasher& hh, double x, double y) { hh.real(pm::hypot(x, y, c)); });
}

void hypotn(Hasher& h, Inputs& in, pm::Compat c) {
    std::array<double, 4> v{};
    for (int i = 0; i < 512; ++i) {
        for (std::size_t k = 0; k < 3; ++k) {
            v[k] = in.in(-1e3, 1e3);
        }
        h.real(pm::hypot(std::span<const double>(v.data(), 3), c));
    }
    for (int i = 0; i < 512; ++i) {
        for (double& x : v) {
            x = in.magnitude();
        }
        h.real(pm::hypot(std::span<const double>(v), c));
    }
    for (std::size_t i = 0; i < edges.size(); ++i) {
        const std::array<double, 3> e3{edge(i), 3.0, edge((i + 7) % edges.size())};
        h.real(pm::hypot(std::span<const double>(e3), c));
    }
}

void dist(Hasher& h, Inputs& in, pm::Compat c) {
    std::array<double, 3> p{};
    std::array<double, 3> q{};
    for (int i = 0; i < 512; ++i) {
        for (std::size_t k = 0; k < 2; ++k) {
            p[k] = in.in(-1e3, 1e3);
            q[k] = in.in(-1e3, 1e3);
        }
        h.result(pm::checked::dist(std::span<const double>(p.data(), 2), std::span<const double>(q.data(), 2), c));
    }
    for (int i = 0; i < 512; ++i) {
        for (std::size_t k = 0; k < 3; ++k) {
            p[k] = in.magnitude();
            q[k] = in.magnitude();
        }
        h.result(pm::checked::dist(p, q, c));
    }
    h.result(pm::checked::dist(std::span<const double>(p.data(), 2), q, c)); // Python's ValueError
}

void sums(Hasher& h, Inputs& in, pm::Compat c) {
    std::array<double, 32> v{};
    for (int g = 0; g < 256; ++g) {
        const auto n = static_cast<std::size_t>(in.integer(0, 32));
        for (std::size_t k = 0; k < n; ++k) {
            v[k] = g % 2 == 0 ? in.in(-1e3, 1e3) : in.magnitude();
        }
        h.real(pm::sum(std::span<const double>(v.data(), n), c));
        pm::Summer s(c);
        for (std::size_t k = 0; k < n; ++k) {
            s.add(v[k]);
        }
        h.real(s.value());
    }
    for (std::size_t i = 0; i < edges.size(); ++i) {
        const std::array<double, 3> e3{1.0, edge(i), -1.0};
        h.real(pm::sum(e3, c));
    }
}

/// x % y, x // y or math.fmod(x, y) over pairs.
template <class F>
void binary_py(Hasher& h, Inputs& in, const F& f) {
    for (int i = 0; i < 2048; ++i) {
        const double x = in.in(-1e3, 1e3);
        const double y = in.in(-50, 50);
        h.result(f(x, y));
    }
    for (int i = 0; i < 512; ++i) {
        const double x = in.magnitude();
        const double y = in.magnitude();
        h.result(f(x, y));
    }
    edge_pairs(h, [&f](Hasher& hh, double x, double y) { hh.result(f(x, y)); });
}

void int_ops(Hasher& h, Inputs& in) {
    for (int i = 0; i < 1024; ++i) {
        const auto a = static_cast<std::int64_t>(in.next());
        const std::int64_t b = in.integer(-1000, 1000);
        h.result(pm::checked::mod(a, b));
        h.result(pm::checked::floor_div(a, b));
    }
    constexpr std::array<std::int64_t, 6> as{INT64_MIN, INT64_MIN + 1, -1, 0, 1, INT64_MAX};
    constexpr std::array<std::int64_t, 6> bs{INT64_MIN, -7, -1, 1, 7, INT64_MAX};
    for (const std::int64_t a : as) {
        for (const std::int64_t b : bs) {
            h.result(pm::checked::mod(a, b));
            h.result(pm::checked::floor_div(a, b));
        }
    }
}

void rounds(Hasher& h, Inputs& in) {
    for (int i = 0; i < 2048; ++i) {
        const double x = in.in(-1e6, 1e6);
        h.result(pm::checked::round(x));
    }
    for (int i = 0; i < 512; ++i) {
        const auto k = static_cast<double>(in.integer(-1000000, 1000000));
        h.result(pm::checked::round(k + 0.5));
    }
    for (int i = 0; i < 512; ++i) {
        const double x = in.magnitude();
        h.result(pm::checked::round(x));
    }
    for (std::size_t i = 0; i < edges.size(); ++i) {
        h.result(pm::checked::round(edge(i)));
    }
}

void round_digits(Hasher& h, Inputs& in) {
    for (int i = 0; i < 2048; ++i) {
        const double x = in.in(-1e4, 1e4);
        const auto n = static_cast<int>(in.integer(-5, 17));
        h.real(pm::round(x, n));
    }
    constexpr std::array<double, 7> scale{1.0, 10.0, 100.0, 1000.0, 10000.0, 100000.0, 1000000.0};
    for (int i = 0; i < 512; ++i) {
        const auto k = static_cast<double>(in.integer(-1000000, 1000000));
        const auto d = static_cast<std::size_t>(in.integer(0, 6));
        const double x = (k + 0.5) / scale[d]; // ties at the decimal place, as the double nearest them
        h.real(pm::round(x, static_cast<int>(d)));
    }
    for (int i = 0; i < 512; ++i) {
        const double x = in.magnitude();
        const auto n = static_cast<int>(in.integer(-330, 340));
        h.real(pm::round(x, n));
    }
    constexpr std::array<int, 4> ns{0, 1, 2, -1};
    for (std::size_t i = 0; i < edges.size(); ++i) {
        for (const int n : ns) {
            h.real(pm::round(edge(i), n));
        }
    }
}

void round_ints(Hasher& h, Inputs& in) {
    for (int i = 0; i < 512; ++i) {
        const auto n = static_cast<std::int64_t>(in.next());
        const auto k = static_cast<int>(in.integer(-20, 2));
        h.integer(pm::round(n, k));
    }
    constexpr std::array<std::int64_t, 8> ns{INT64_MIN,           INT64_MIN + 1, -5000000000000000000, -15, 15, 25,
                                             5000000000000000001, INT64_MAX};
    constexpr std::array<int, 4> ks{-1, -18, -19, -20};
    for (const std::int64_t n : ns) {
        for (const int k : ks) {
            h.integer(pm::round(n, k));
        }
    }
}

void to_ints(Hasher& h, Inputs& in) {
    const auto all = [&h](double x) {
        h.result(pm::checked::to_int(x));
        h.result(pm::checked::floor_int(x));
        h.result(pm::checked::ceil_int(x));
    };
    for (int i = 0; i < 1024; ++i) {
        const double x = in.in(-1e19, 1e19);
        all(x);
    }
    for (int i = 0; i < 512; ++i) {
        const double x = in.magnitude();
        all(x);
    }
    for (std::size_t i = 0; i < edges.size(); ++i) {
        all(edge(i));
    }
}

void checked_math(Hasher& h, Inputs& in) {
    for (int i = 0; i < 512; ++i) {
        const double a = in.in(-1, 100);
        const double b = in.in(-1.5, 1.5);
        const double c = in.in(-800, 800);
        const double d = in.magnitude();
        const double base = in.in(-1, 10);
        const double px = in.in(-10, 10);
        const double py = in.in(-5, 5);
        h.result(pm::checked::sqrt(a));
        h.result(pm::checked::asin(b));
        h.result(pm::checked::acos(b));
        h.result(pm::checked::exp(c));
        h.result(pm::checked::log(a));
        h.result(pm::checked::log10(a));
        h.result(pm::checked::log2(d));
        h.result(pm::checked::log2(std::ldexp(1.0, static_cast<int>(in.integer(-1074, 1023)))));
        h.result(pm::checked::log(a, base));
        h.result(pm::checked::pow(px, py));
        h.result(pm::checked::pow(px, std::floor(py)));
    }
    for (std::size_t i = 0; i < edges.size(); ++i) {
        const double x = edge(i);
        h.result(pm::checked::sqrt(x));
        h.result(pm::checked::asin(x));
        h.result(pm::checked::acos(x));
        h.result(pm::checked::exp(x));
        h.result(pm::checked::log(x));
        h.result(pm::checked::log10(x));
        h.result(pm::checked::log2(x));
    }
    edge_pairs(h, [](Hasher& hh, double x, double y) {
        hh.result(pm::checked::pow(x, y));
        hh.result(pm::checked::log(x, y));
    });
}

void misc(Hasher& h, Inputs& in) {
    for (int i = 0; i < 512; ++i) {
        const double a = in.in(-720, 720);
        const double b = in.in(-10, 10);
        const double m = in.magnitude();
        const double m2 = in.magnitude();
        const auto e = static_cast<int>(in.integer(-1100, 1100));
        const double r = in.in(-1e-8, 1e-8);
        h.real(pm::radians(a));
        h.real(pm::degrees(b));
        const pm::Frexp f = pm::frexp(m);
        h.real(f.mantissa);
        h.integer(f.exponent);
        h.real(pm::ldexp(m, e));
        h.integer(pm::isclose(a, a * (1 + r)) ? 1 : 0);
        h.real(pm::copysign(m, m2));
        h.real(pm::floor(m));
        h.real(pm::ceil(m));
        h.real(pm::fabs(m2));
        h.real(pm::max(m, m2));
        h.real(pm::min(m, m2));
    }
    for (std::size_t i = 0; i < edges.size(); ++i) {
        const double x = edge(i);
        const pm::Frexp f = pm::frexp(x);
        h.real(f.mantissa);
        h.integer(f.exponent);
        h.real(pm::ldexp(x, -1075));
        h.real(pm::ldexp(x, 1075));
        h.integer(pm::isclose(x, 1.0) ? 1 : 0);
        h.real(pm::max(x, 0.0));
        h.real(pm::min(0.0, x));
    }
}

/// One stream's script: 48 draws of every kind, their arguments from the inputs.
void draws(Hasher& h, Inputs& in, pm::PyRandom& r) {
    constexpr std::array<std::int64_t, 6> steps{1, 2, 3, -1, -2, 5};
    for (int i = 0; i < 48; ++i) {
        switch (i % 12) {
        case 0:
        case 11: h.real(r.random()); break;
        case 1: {
            const double a = in.in(-10, 10);
            const double b = in.in(-10, 10);
            h.real(r.uniform(a, b));
            break;
        }
        case 2: h.real(r.gauss()); break;
        case 3: {
            const double mu = in.in(-5, 5);
            const double sigma = in.in(0, 3);
            h.real(r.gauss(mu, sigma));
            break;
        }
        case 4: h.integer(r.randbelow(in.integer(0, 1000))); break;
        case 5: h.integer(r.randbelow(static_cast<std::int64_t>(in.next() >> 2U))); break;
        case 6: {
            const std::int64_t start = in.integer(-100, 100);
            const std::int64_t stop = in.integer(-100, 100);
            const std::int64_t step = steps[static_cast<std::size_t>(in.integer(0, 5))];
            h.result(r.checked_randrange(start, stop, step));
            break;
        }
        case 7: {
            const std::int64_t a = in.integer(-50, 0);
            const std::int64_t b = in.integer(0, 50);
            h.integer(r.randint(a, b));
            break;
        }
        case 8: h.word(r.getrandbits(static_cast<int>(in.integer(0, 63)))); break;
        case 9: {
            const auto size = static_cast<std::size_t>(in.integer(0, 20));
            const auto index = r.checked_choice_index(size);
            index ? h.integer(static_cast<std::int64_t>(*index)) : h.error();
            break;
        }
        default: {
            std::array<std::int64_t, 8> deck{0, 1, 2, 3, 4, 5, 6, 7};
            r.shuffle(std::span(deck));
            for (const std::int64_t card : deck) {
                h.integer(card);
            }
            break;
        }
        }
    }
}

void random_int_seeds(Hasher& h, Inputs& in) {
    for (std::int64_t s = 0; s < 64; ++s) {
        pm::PyRandom r(s);
        draws(h, in, r);
    }
    for (int i = 0; i < 64; ++i) {
        pm::PyRandom r(static_cast<std::int64_t>(in.next()));
        draws(h, in, r);
    }
    constexpr std::array<std::int64_t, 4> extremes{INT64_MIN, INT64_MAX, -1, -12345};
    for (const std::int64_t s : extremes) {
        pm::PyRandom r(s);
        draws(h, in, r);
    }
}

void random_hashed_seeds(Hasher& h, Inputs& in) {
    // The renders' seeds: Random(hash((x0, z0)) & 0xffff), Random(hash(("roof", x0, z0)) & 0xffff),
    // Random(hash((round(x, 1), round(z, 1))) & 0xffffff), and rngFor's & 0xffffffff.
    for (int i = 0; i < 64; ++i) {
        const double x = in.in(-500, 500);
        const double z = in.in(-500, 500);
        std::int64_t seed = 0;
        switch (i % 4) {
        case 0: seed = pm::hash_tuple(x, z) & 0xffff; break;
        case 1: seed = pm::hash_tuple("roof", x, z) & 0xffff; break;
        case 2: seed = pm::hash_tuple(pm::round(x, 1), pm::round(z, 1)) & 0xffffff; break;
        default: seed = pm::hash_tuple(x, z, std::int64_t{7}) & 0xffffffffLL; break;
        }
        h.integer(seed);
        pm::PyRandom r(seed);
        draws(h, in, r);
    }
    for (int i = 0; i < 16; ++i) {
        const double x = i % 2 == 0 ? in.in(-1e6, 1e6) : in.magnitude();
        pm::PyRandom r(0);
        if (r.seed_float(x)) {
            draws(h, in, r);
        } else {
            h.error();
        }
    }
    for (int i = 0; i < 8; ++i) {
        std::array<std::uint32_t, 5> words{};
        const auto used = static_cast<std::size_t>(in.integer(1, 5));
        for (std::size_t k = 0; k < used; ++k) {
            words[k] = static_cast<std::uint32_t>(in.next());
        }
        pm::PyRandom r(0);
        r.seed_words(std::span<const std::uint32_t>(words.data(), used));
        draws(h, in, r);
    }
}

void hash_numbers(Hasher& h, Inputs& in) {
    for (int i = 0; i < 512; ++i) {
        h.integer(pm::hash_int(static_cast<std::int64_t>(in.next())));
    }
    constexpr std::array<std::int64_t, 9> ints{
        INT64_MIN, INT64_MIN + 1, -2, -1, 0, 1, (std::int64_t{1} << 61) - 1, std::int64_t{1} << 61, INT64_MAX};
    for (const std::int64_t n : ints) {
        h.integer(pm::hash_int(n));
    }
    for (int i = 0; i < 512; ++i) {
        h.result(pm::hash_float(in.magnitude()));
    }
    for (int i = 0; i < 256; ++i) {
        h.result(pm::hash_float(static_cast<double>(in.integer(-1000000000000LL, 1000000000000LL))));
    }
    for (int i = 0; i < 256; ++i) {
        const double x = in.in(-500, 500);
        h.result(pm::hash_float(pm::round(x, 1)));
    }
    for (std::size_t i = 0; i < edges.size(); ++i) {
        h.result(pm::hash_float(edge(i)));
    }
}

void hash_texts(Hasher& h, Inputs& in) {
    constexpr std::array<std::string_view, 16> texts{
        "",           "a",
        "roof",       "bk",
        "tree_pick",  "café",
        "Ⅻ",          "名字",
        "\U0001F600", "a\U0001F600b",
        "Jordaan",    "IJ",
        "Westerkerk", "ÿĀ",
        "12345678",   "abcdefghijabcdefghijabcdefghijabcdefghijabcdefghij"};
    for (const int minor : {9, 12}) {
        const pm::Compat c{minor};
        for (const std::string_view t : texts) {
            h.result(pm::hash_str(t, c));
            h.integer(pm::hash_bytes(std::as_bytes(std::span(t.data(), t.size())), c));
        }
        h.result(pm::hash_str("\xff", c)); // not UTF-8
    }
    for (int i = 0; i < 256; ++i) {
        const double x = pm::round(in.in(-500, 500), 1);
        const double z = pm::round(in.in(-500, 500), 1);
        h.result(pm::hash_tuple(pm::renders, x, z));
    }
    for (int i = 0; i < 128; ++i) {
        const double x = in.in(-500, 500);
        const double z = in.in(-500, 500);
        h.result(pm::hash_tuple(pm::renders, "roof", x, z));
        h.result(pm::hash_tuple(pm::Compat{12}, "bk", x, z));
    }
    for (int i = 0; i < 128; ++i) {
        const auto n = static_cast<std::int64_t>(in.next() >> 20U);
        const double m = in.magnitude();
        h.result(pm::hash_tuple(pm::renders, n, m, "bk", true));
    }
    h.result(pm::hash_tuple(pm::renders));                // ()
    h.result(pm::hash_tuple(pm::renders, 1.0, edge(42))); // a NaN: not hashable
}

[[nodiscard]] Numerics run() noexcept {
    Numerics out;
    std::size_t at = 0;
    const auto part = [&out, &at](std::string_view name, auto&& body) {
        Hasher h;
        Inputs in(1000003U * (at + 1U));
        body(h, in);
        out.parts[at] = NumericsPart{.name = name, .hash = h.hash(), .results = h.count()};
        ++at;
    };
    const auto sweep = [](double (*f)(double), double lo, double hi, int ranged, double lo2, double hi2, int ranged2,
                          int magnitudes) {
        return [=](Hasher& h, Inputs& in) { unary(h, in, f, lo, hi, ranged, lo2, hi2, ranged2, magnitudes); };
    };
    part("strict.sin", [](Hasher& h, Inputs& in) { strict_trig(h, in, pm::strict::sin); });
    part("strict.cos", [](Hasher& h, Inputs& in) { strict_trig(h, in, pm::strict::cos); });
    part("strict.tan", [](Hasher& h, Inputs& in) { strict_trig(h, in, pm::strict::tan); });
    part("strict.asin", sweep(pm::strict::asin, -1, 1, 2048, -1.25, 1.25, 512, 512));
    part("strict.acos", sweep(pm::strict::acos, -1, 1, 2048, -1.25, 1.25, 512, 512));
    part("strict.atan", sweep(pm::strict::atan, -10, 10, 2048, -1e6, 1e6, 512, 1024));
    part("strict.atan2", [](Hasher& h, Inputs& in) {
        for (int i = 0; i < 2048; ++i) {
            const double y = in.in(-100, 100);
            const double x = in.in(-100, 100);
            h.real(pm::strict::atan2(y, x));
        }
        for (int i = 0; i < 1024; ++i) {
            const double y = in.magnitude();
            const double x = in.magnitude();
            h.real(pm::strict::atan2(y, x));
        }
        edge_pairs(h, [](Hasher& hh, double y, double x) { hh.real(pm::strict::atan2(y, x)); });
    });
    part("strict.exp", sweep(pm::strict::exp, -750, 712, 2048, -1, 1, 512, 1024));
    part("strict.log", sweep(pm::strict::log, 0, 2, 1024, 0, 1e6, 1024, 2048));
    part("strict.log10", sweep(pm::strict::log10, 0, 2, 1024, 0, 1e6, 1024, 2048));
    part("strict.pow", [](Hasher& h, Inputs& in) {
        for (int i = 0; i < 2048; ++i) {
            const double x = in.in(0, 100);
            const double y = in.in(-20, 20);
            h.real(pm::strict::pow(x, y));
        }
        for (int i = 0; i < 1024; ++i) {
            const double x = in.in(-10, 10);
            const auto y = static_cast<double>(in.integer(-30, 30));
            h.real(pm::strict::pow(x, y));
        }
        for (int i = 0; i < 1024; ++i) {
            const double x = in.magnitude();
            const double y = in.magnitude();
            h.real(pm::strict::pow(x, y));
        }
        for (int i = 0; i < 512; ++i) {
            const double x = in.in(0.9999995, 1.0000005); // the |y| > 2^31 branch and its neighbours
            const double y = in.in(-1e19, 1e19);
            h.real(pm::strict::pow(x, y));
            h.real(pm::strict::pow(-x, std::floor(y)));
        }
        edge_pairs(h, [](Hasher& hh, double x, double y) { hh.real(pm::strict::pow(x, y)); });
    });
    part("strict.cbrt", sweep(pm::strict::cbrt, -1e3, 1e3, 1024, -1, 1, 512, 2048));
    part("strict.expm1", sweep(pm::strict::expm1, -40, 40, 1024, -1, 1, 512, 1024));
    part("strict.log1p", sweep(pm::strict::log1p, -1, 10, 1024, -1e-5, 1e-5, 512, 1024));
    part("strict.sinh", [](Hasher& h, Inputs& in) {
        // K3's row edges take |x| <= pi; then fdlibm's branch points: 2^-28, 1, 22, log(maxdouble), the overflow.
        unary(h, in, pm::strict::sinh, -pm::pi, pm::pi, 2048, -30, 30, 1024, 512);
        for (int i = 0; i < 512; ++i) {
            const double x = in.in(-712, 712);
            h.real(pm::strict::sinh(x));
        }
        constexpr std::array<std::uint64_t, 6> branches{0x3E30000000000000U, 0x3FF0000000000000U, 0x4036000000000000U,
                                                        0x40862E4200000000U, 0x40862E42FEFA39EFU, 0x408633CE8FB9F87DU};
        for (const std::uint64_t b : branches) {
            for (std::uint64_t d = 0; d < 7; ++d) {
                const auto x = std::bit_cast<double>(b + d - 3U);
                h.real(pm::strict::sinh(x));
                h.real(pm::strict::sinh(-x));
            }
        }
    });
    part("py.hypot.39", [](Hasher& h, Inputs& in) { hypot2(h, in, pm::Compat{9}); });
    part("py.hypot.312", [](Hasher& h, Inputs& in) { hypot2(h, in, pm::Compat{12}); });
    part("py.hypotn.39", [](Hasher& h, Inputs& in) { hypotn(h, in, pm::Compat{9}); });
    part("py.hypotn.312", [](Hasher& h, Inputs& in) { hypotn(h, in, pm::Compat{12}); });
    part("py.dist.39", [](Hasher& h, Inputs& in) { dist(h, in, pm::Compat{9}); });
    part("py.dist.312", [](Hasher& h, Inputs& in) { dist(h, in, pm::Compat{12}); });
    part("py.sum.39", [](Hasher& h, Inputs& in) { sums(h, in, pm::Compat{9}); });
    part("py.sum.312", [](Hasher& h, Inputs& in) { sums(h, in, pm::Compat{12}); });
    part("py.mod",
         [](Hasher& h, Inputs& in) { binary_py(h, in, [](double x, double y) { return pm::checked::mod(x, y); }); });
    part("py.floor_div", [](Hasher& h, Inputs& in) {
        binary_py(h, in, [](double x, double y) { return pm::checked::floor_div(x, y); });
    });
    part("py.fmod",
         [](Hasher& h, Inputs& in) { binary_py(h, in, [](double x, double y) { return pm::checked::fmod(x, y); }); });
    part("py.int_ops", int_ops);
    part("py.round", rounds);
    part("py.round_digits", round_digits);
    part("py.round_int", round_ints);
    part("java.round", [](Hasher& h, Inputs& in) {
        // Kotlin's Math.round: the writer's grid and columns. Halves, 0.49999999999999994, 2^52 and 2^63 at their
        // edges.
        for (int i = 0; i < 2048; ++i) {
            const double x = in.magnitude();
            h.integer(pm::java::round(x));
        }
        for (int i = 0; i < 2048; ++i) {
            const double x = in.in(-1e9, 1e9);
            h.integer(pm::java::round(x));
        }
        for (int i = 0; i < 1024; ++i) {
            const auto k = static_cast<double>(in.integer(-1000000, 1000000));
            h.integer(pm::java::round(k + 0.5));
        }
        constexpr std::array<std::uint64_t, 6> centres{0x3FDFFFFFFFFFFFFFU, 0x3FE0000000000000U, 0x4330000000000000U,
                                                       0x4340000000000000U, 0x43E0000000000000U, 0x0000000000000001U};
        for (const std::uint64_t c : centres) {
            for (std::uint64_t d = 0; d < 7; ++d) {
                const auto x = std::bit_cast<double>(c + d - 3U);
                for (const double y : {x, x + 0.5, x + 1.0, x - 0.5, x - 1.0}) {
                    h.integer(pm::java::round(y));
                    h.integer(pm::java::round(-y));
                }
            }
        }
        for (std::size_t i = 0; i < edges.size(); ++i) {
            h.integer(pm::java::round(edge(i)));
        }
    });
    part("java.min_max", [](Hasher& h, Inputs& in) {
        // Kotlin's minOf and maxOf (Java's Math.min and max): NaN wins, -0.0 below +0.0; the zeros as constants and
        // as values, the shapes clang would fold into fminnm and fmaxnm.
        for (int i = 0; i < 1024; ++i) {
            const double a = in.magnitude();
            const double b = i % 2 == 0 ? in.magnitude() : -a;
            h.real(pm::java::max(a, b));
            h.real(pm::java::min(a, b));
            h.real(pm::java::max(a, 0.0));
            h.real(pm::java::min(-0.0, a));
            h.real(pm::max(a, -0.0));
            h.real(pm::min(0.0, a));
        }
        edge_pairs(h, [](Hasher& hh, double x, double y) {
            hh.real(pm::java::max(x, y));
            hh.real(pm::java::min(x, y));
            hh.real(pm::max(x, y));
            hh.real(pm::min(x, y));
        });
    });
    part("py.to_int", to_ints);
    part("py.checked", checked_math);
    part("py.misc", misc);
    part("random.int_seeds", random_int_seeds);
    part("random.hashed_seeds", random_hashed_seeds);
    part("hash.numbers", hash_numbers);
    part("hash.texts", hash_texts);

    Hasher total;
    for (const NumericsPart& p : out.parts) {
        total.word(p.hash);
    }
    out.total = total.hash();
    return out;
}

} // namespace

Numerics numerics() noexcept { return run(); }

std::string_view numerics_line() noexcept {
    // "pymath " + 16 + " ok" or " FAIL expected " + 16, computed on first use.
    static const std::array<char, 64> line = [] {
        std::array<char, 64> text{};
        const auto hex = [](char* at, std::uint64_t v) {
            for (int i = 15; i >= 0; --i) {
                at[i] = "0123456789abcdef"[v & 0xfU];
                v >>= 4U;
            }
            return at + 16;
        };
        const std::uint64_t total = numerics().total;
        constexpr std::string_view head = "pymath ";
        char* at = std::ranges::copy(head, text.data()).out;
        at = hex(at, total);
        if (total == numerics_expected) {
            constexpr std::string_view ok = " ok";
            std::ranges::copy(ok, at);
        } else {
            constexpr std::string_view fail = " FAIL expected ";
            at = std::ranges::copy(fail, at).out;
            hex(at, numerics_expected);
        }
        return text;
    }();
    return {line.data()};
}

} // namespace atlas::check

const char* atlas_numerics_line() noexcept { return atlas::check::numerics_line().data(); }
