// pymath's speed (plan-vector K3b): 10^7 sin calls on one core within 0.3 s on this Mac, and each StrictMath function,
// the Python layer and the random stream per call, against the platform's libm for scale.
#include "base/pymath/PyHash.hpp"
#include "base/pymath/PyMath.hpp"
#include "base/pymath/PyRandom.hpp"

#include <nanobench.h>

#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdio>
#include <vector>

namespace {

namespace pm = atlas::pymath;

/// Arguments spread evenly over [lo, hi].
std::vector<double> spread(std::size_t n, double lo, double hi) {
    std::vector<double> v(n);
    for (std::size_t i = 0; i < n; ++i) {
        v[i] = lo + (hi - lo) * static_cast<double>(i) / static_cast<double>(n);
    }
    return v;
}

} // namespace

int main() {
    // The acceptance: 10^7 calls of sin over the renders' angles on one core.
    {
        const std::vector<double> x = spread(1U << 16U, -2 * pm::pi, 2 * pm::pi);
        double best = 1e9;
        for (int run = 0; run < 5; ++run) {
            double sum = 0.0;
            const auto start = std::chrono::steady_clock::now();
            for (std::size_t i = 0; i < 10'000'000U; ++i) {
                sum += pm::strict::sin(x[i & 0xffffU]);
            }
            const std::chrono::duration<double> took = std::chrono::steady_clock::now() - start;
            ankerl::nanobench::doNotOptimizeAway(sum);
            best = took.count() < best ? took.count() : best;
        }
        (void)std::printf("pymath sin: 10^7 calls in %.3f s on one core (budget 0.3 s): %s\n", best,
                          best <= 0.3 ? "ok" : "OVER");
    }

    constexpr std::size_t n = 1U << 14U;
    const std::vector<double> angles = spread(n, -2 * pm::pi, 2 * pm::pi);
    const std::vector<double> units = spread(n, -1.0, 1.0);
    const std::vector<double> wide = spread(n, -700.0, 700.0);
    const std::vector<double> positive = spread(n, 1e-3, 1e4);
    const std::vector<double> small = spread(n, -pm::pi, pm::pi);
    ankerl::nanobench::Bench bench;
    bench.title("pymath").unit("call").batch(n).minEpochIterations(20).warmup(3);
    const auto each = [&](const char* name, const std::vector<double>& in, auto f) {
        bench.run(name, [&] {
            double sum = 0.0;
            for (const double x : in) {
                sum += f(x);
            }
            ankerl::nanobench::doNotOptimizeAway(sum);
        });
    };
    each("strict::sin", angles, [](double x) { return pm::strict::sin(x); });
    each("std::sin (this libm, for scale)", angles, [](double x) {
        return std::sin(x); // NOLINT(bugprone-unsafe-functions): for scale only
    });
    each("strict::cos", angles, [](double x) { return pm::strict::cos(x); });
    each("strict::tan", angles, [](double x) { return pm::strict::tan(x); });
    each("strict::asin", units, [](double x) { return pm::strict::asin(x); });
    each("strict::acos", units, [](double x) { return pm::strict::acos(x); });
    each("strict::atan", wide, [](double x) { return pm::strict::atan(x); });
    each("strict::atan2", angles, [](double x) { return pm::strict::atan2(x, 0.75); });
    each("strict::exp", wide, [](double x) { return pm::strict::exp(x); });
    each("strict::log", positive, [](double x) { return pm::strict::log(x); });
    each("strict::log10", positive, [](double x) { return pm::strict::log10(x); });
    each("strict::pow (x ** 1.3)", positive, [](double x) { return pm::strict::pow(x, 1.3); });
    each("strict::cbrt", wide, [](double x) { return pm::strict::cbrt(x); });
    each("strict::expm1", units, [](double x) { return pm::strict::expm1(x); });
    each("strict::log1p", positive, [](double x) { return pm::strict::log1p(x); });
    each("strict::sinh", small, [](double x) { return pm::strict::sinh(x); });
    each("hypot(x, y) 3.9", wide, [](double x) { return pm::hypot(x, 3.25); });
    each("hypot(x, y) 3.12", wide, [](double x) { return pm::hypot(x, 3.25, pm::Compat{12}); });
    each("round(x, 1)", wide, [](double x) { return pm::round(x, 1); });
    each("java::round", wide, [](double x) { return static_cast<double>(pm::java::round(x * 1e7)); });
    each("x % y", wide, [](double x) { return pm::mod(x, 7.5); });
    each("hash((x, z))", wide, [](double x) { return static_cast<double>(pm::hash_tuple(x, -x)); });

    pm::PyRandom r(42);
    bench.run("PyRandom::random", [&] {
        double sum = 0.0;
        for (std::size_t i = 0; i < n; ++i) {
            sum += r.random();
        }
        ankerl::nanobench::doNotOptimizeAway(sum);
    });
    bench.run("PyRandom::gauss", [&] {
        double sum = 0.0;
        for (std::size_t i = 0; i < n; ++i) {
            sum += r.gauss();
        }
        ankerl::nanobench::doNotOptimizeAway(sum);
    });
    bench.unit("tree").batch(n / 64);
    bench.run("PyRandom(seed) + 36 uniforms (a tree's clumps)", [&] {
        double sum = 0.0;
        for (std::size_t i = 0; i < n / 64; ++i) {
            pm::PyRandom t(static_cast<std::int64_t>(i));
            for (int k = 0; k < 36; ++k) {
                sum += t.uniform(-1.0, 1.0);
            }
        }
        ankerl::nanobench::doNotOptimizeAway(sum);
    });
    return 0;
}
