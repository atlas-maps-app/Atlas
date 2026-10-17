// light.slang (plan-vector D8, K5) against the port's light() and tohex (render/amsterdam.py:261, render/city.py:13),
// transcribed line for line below in binary64 with the port's own exp (pymath::strict::exp, which is StrictMath.exp):
// - the exact light, as C++ (the twin) and on the host's Vulkan device (lavapipe), equal to it bit for bit, every
//   value and every byte, over 100,000 random surfaces lit by random themes and over the edges (the port's near and
//   exact ties at a half level, TONE's 205, grazing sun, the clamps at 0 and 255, black and white, lit and unlit);
// - the GPU's own exact operations (exp, the divisions by 255 and 50, exp's division) equal to C++'s bits over 10^6
//   arguments each;
// - light_f32, the binary32 rule for GPUs without shaderFloat64, as C++ and on the device: within light_f32_bound of
//   the port's value, and its bytes the port's wherever that value is farther than the bound from a half level.
// The inputs are checked to tell a truncating tohex and a changed coefficient from the port's. MT/style/
// LightParityTest.kt holds the same to the port's own Kotlin (R/Amsterdam.kt).
//
// The device tests need Vulkan (style/HostProbe.hpp): without it they are skipped, unless ATLAS_REQUIRE_VULKAN=1 (CI
// and the presets), which also requires the validation layer.
#include "style/Light.hpp"

#include "base/pymath/PyMath.hpp"
#include "style/HostProbe.hpp"
#include "style/Theme.hpp"
#include "tests/base/require.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <random>
#include <string>
#include <vector>

namespace {

namespace flag = atlas::style::theme_flag;
using atlas::style::LightInput;
using atlas::style::ThemeBlock;
using Rgb = std::array<double, 3>;

// ---------------------------------------------------------------- the port, transcribed

/// The port's light() constants, so the sensitivity checks below can change one.
struct Rule {
    double sky = 0.18;
};

/// The port's light(), line for line (amsterdam.py:261-271), on the block's own doubles: Python's max (pymath::max),
/// IEEE division, StrictMath.exp.
Rgb port_light(const ThemeBlock& th, const Rgb& base, const Rgb& n, bool lit, Rule rule = {}) {
    using atlas::pymath::max;
    const Rgb amb_c = {static_cast<double>(th.ambient.r), static_cast<double>(th.ambient.g),
                       static_cast<double>(th.ambient.b)};
    const Rgb& L = th.sun_vector; // NOLINT(readability-identifier-naming): the port's name
    Rgb c{};
    if (th.has(flag::night)) {
        // c = mulc(mix(base, hx(th['amb']), 0.55), 0.4 + 0.08 * max(0.0, n[1]))
        const double k = 0.4 + (0.08 * max(0.0, n[1]));
        for (std::size_t i = 0; i < 3; ++i) {
            c.at(i) = (base.at(i) + ((amb_c.at(i) - base.at(i)) * 0.55)) * k;
        }
        return c;
    }
    const bool tone = th.has(flag::tone);
    // amb = mix(mulc(base, th['amb_k']), mulc(hx(th['amb']), th['amb_k'] * 0.55), 0.26 if TONE else 0.35)
    Rgb amb{};
    for (std::size_t i = 0; i < 3; ++i) {
        const double a = base.at(i) * th.amb_k;
        const double b = amb_c.at(i) * (th.amb_k * 0.55);
        amb.at(i) = a + ((b - a) * (tone ? 0.26 : 0.35));
    }
    // lam = max(0.0, vdot(n, L)) if lit else 0.0
    const double lam = lit ? max(0.0, (n[0] * L[0]) + (n[1] * L[1]) + (n[2] * L[2])) : 0.0;
    // sky = (0.18 + 0.12 * max(0.0, n[1])) * th.get('sky_k', 1.0)
    const double sky = (rule.sky + (0.12 * max(0.0, n[1]))) * (th.has(flag::overcast) ? th.sky_k : 1.0);
    const Rgb sun = {static_cast<double>(th.sun.r), static_cast<double>(th.sun.g), static_cast<double>(th.sun.b)};
    for (std::size_t i = 0; i < 3; ++i) {
        // amb[i] + base[i] * sky + base[i] * lam * 1.05 * th['sun_k'] * hx(th['sun'])[i] / 255
        c.at(i) = (amb.at(i) + (base.at(i) * sky)) + ((((base.at(i) * lam) * 1.05) * th.sun_k) * sun.at(i)) / 255;
        // v if v < 205 else 205 + 50 * (1 - math.exp(-(v - 205) / 50))
        if (tone && !(c.at(i) < 205)) {
            c.at(i) = 205 + (50 * (1 - atlas::pymath::strict::exp(-(c.at(i) - 205) / 50)));
        }
    }
    return c;
}

/// tohex's channel (city.py:13): max(0, min(255, int(round(v)))), Python's round to even.
int port_byte(double v) { return static_cast<int>(std::clamp<std::int64_t>(atlas::pymath::round(v), 0, 255)); }

/// A truncating tohex, which the inputs must tell from the port's.
int truncated_byte(double v) { return static_cast<int>(std::clamp<std::int64_t>(atlas::pymath::floor_int(v), 0, 255)); }

/// How far v lies from the nearest half level k + 1/2.
double from_half(double v) { return std::abs(v - (std::floor(v) + 0.5)); }

// ---------------------------------------------------------------- the inputs

/// A theme block the core's reader accepts, in the ranges the port's moments span: day or night, overcast or not,
/// TONE or not, with weather or without.
ThemeBlock random_theme(std::mt19937_64& rnd) {
    std::uniform_real_distribution<double> u(0.0, 1.0);
    auto byte = [&rnd] { return static_cast<std::uint8_t>(rnd() % 256); };
    auto rgb = [&byte] { return atlas::style::Rgb8{.r = byte(), .g = byte(), .b = byte(), .a = 255}; };
    ThemeBlock b{};
    b.version = 1;
    b.year = 2026;
    b.month = 10;
    b.day = 1;
    b.hour = 12;
    const bool night = u(rnd) < 0.2;
    const bool weather = u(rnd) < 0.5;
    b.flags = (night ? flag::night : 0U) | (u(rnd) < 0.5 ? flag::tone : 0U) | (u(rnd) < 0.4 ? flag::overcast : 0U) |
              (weather ? flag::weather : 0U);
    b.sky_top = rgb();
    b.sky_horizon = rgb();
    b.haze = rgb();
    b.sun = rgb();
    b.ambient = rgb();
    b.ground = rgb();
    b.road = rgb();
    b.water = rgb();
    b.shadow = rgb();
    b.haze_max = 0.6 * u(rnd);
    b.sun_k = night ? 0.0 : 1.2 * u(rnd);
    b.amb_k = 0.3 + (0.6 * u(rnd));
    b.sky_k = b.has(flag::overcast) ? 0.7 + u(rnd) : 1.0;
    b.shadow_opacity = 0.4 * u(rnd);
    b.weather_code = 255;
    if (weather) {
        b.cloud = u(rnd);
        b.cloud_low = u(rnd);
        b.wind = 30.0 * u(rnd);
        b.wind_direction = 360.0 * u(rnd);
        b.temperature = 14.0;
        b.pressure = 1013.0;
        b.visibility = 24000.0;
        b.humidity = 80.0;
        b.weather_code = 3;
    }
    b.sun_azimuth = 180.0;
    if (night) {
        b.sun_vector = {0.0, -1.0, 0.0};
        b.sun_elevation = -20.0;
    } else {
        // a direction above the horizon, uniform over the upper half sphere (no trigonometry: no libm in the core)
        std::normal_distribution<double> g(0.0, 1.0);
        const double x = g(rnd);
        const double y = std::abs(g(rnd)) + 1e-3;
        const double z = g(rnd);
        const double len = std::sqrt((x * x) + (y * y) + (z * z));
        b.sun_vector = {x / len, y / len, z / len};
        b.sun_elevation = 20.0;
    }
    return b;
}

/// A unit normal, uniform over the sphere.
Rgb random_normal(std::mt19937_64& rnd) {
    std::normal_distribution<double> g(0.0, 1.0);
    Rgb n = {g(rnd), g(rnd), g(rnd)};
    const double len = std::sqrt((n[0] * n[0]) + (n[1] * n[1]) + (n[2] * n[2]));
    for (double& v : n) {
        v /= len;
    }
    return n;
}

/// The surfaces and themes of a test, with the port's values and bytes.
struct Case {
    std::vector<ThemeBlock> blocks;
    std::vector<atlas::shaders::Theme> themes;
    std::vector<LightInput> inputs;
    std::vector<Rgb> port;
    std::size_t random = 0; // the first `random` inputs are the random ones, the rest the edges

    void add(const Rgb& base, const Rgb& n, bool lit, std::uint32_t theme) {
        inputs.push_back(LightInput{.base = base, .normal = n, .lit = lit ? 1U : 0U, .theme = theme});
        port.push_back(port_light(blocks.at(theme), base, n, lit));
    }
};

/// A block through the core's own reader, as the phone gets it (as it was, when the reader refuses it, which fails the
/// test case).
ThemeBlock read_back(const ThemeBlock& b) {
    std::array<std::byte, 256> raw{};
    std::memcpy(raw.data(), &b, sizeof b);
    auto read = atlas::style::read_theme_block(raw);
    ATLAS_REQUIRE_OR_RETURN(b, read.has_value(),
                            "the reader refused a test theme: " << atlas::style::name_of(read.error()));
    return *read;
}

/// 100,000 random surfaces lit by 64 random themes, then the edges.
Case make_case() {
    std::mt19937_64 rnd(20261016); // NOLINT(cert-msc51-cpp): a fixed seed, so every run tests the same surfaces
    std::uniform_real_distribution<double> u(0.0, 1.0);
    Case c;
    for (int i = 0; i < 64; ++i) {
        c.blocks.push_back(read_back(random_theme(rnd)));
    }
    // the edges' own themes: night, and a TONE day with a strong sun, so the shoulder and the clamps are reached
    ThemeBlock night = random_theme(rnd);
    while (!night.has(flag::night)) {
        night = random_theme(rnd);
    }
    night.ambient = {.r = 0x3A, .g = 0x46, .b = 0x68, .a = 255}; // the night's amb (amsterdam.py:231)
    ThemeBlock bright = random_theme(rnd);
    while (bright.has(flag::night)) {
        bright = random_theme(rnd);
    }
    bright.flags |= flag::tone;
    bright.sun_k = 1.2;
    bright.amb_k = 0.9;
    bright.sun = {.r = 255, .g = 255, .b = 255, .a = 255};
    bright.ambient = {.r = 255, .g = 255, .b = 255, .a = 255};
    const auto night_index = static_cast<std::uint32_t>(c.blocks.size());
    c.blocks.push_back(read_back(night));
    const auto bright_index = static_cast<std::uint32_t>(c.blocks.size());
    c.blocks.push_back(read_back(bright));
    for (const ThemeBlock& b : c.blocks) {
        c.themes.push_back(atlas::style::shader_theme(b));
    }
    for (int i = 0; i < 100000; ++i) {
        // colours as hx reads them (integers), and scaled as the renders pass some (a window's 0.55, a crown's 0.6)
        Rgb base{};
        for (double& v : base) {
            v = static_cast<double>(rnd() % 256);
        }
        if (u(rnd) < 0.3) {
            const double k = u(rnd) < 0.5 ? 0.55 : 0.6;
            for (double& v : base) {
                v *= k;
            }
        }
        const Rgb n = random_normal(rnd);
        const bool lit = u(rnd) < 0.6;
        c.add(base, n, lit, static_cast<std::uint32_t>(rnd() % 64));
    }
    c.random = c.inputs.size();
    // walls (n.y = 0) at night with every grey from black to white: (b + (a - b) * 0.55) * 0.4 meets a half level
    // exactly in real numbers about once in fifty, and the port's double lands on one side of it
    for (int b = 0; b < 256; ++b) {
        const double v = b;
        c.add({v, v, v}, {1.0, 0.0, 0.0}, false, night_index);
        c.add({v, v, v}, {0.0, -0.0, 1.0}, true, night_index);
        c.add({v, 0.0, 255.0 - v}, {0.0, 1.0, 0.0}, true, night_index);
    }
    // every grey on walls, roofs and undersides in each theme, lit and unlit: integer colours meet the port's ties
    const std::array<Rgb, 4> normals = {Rgb{1.0, 0.0, 0.0}, Rgb{0.0, 1.0, 0.0}, Rgb{0.0, -1.0, 0.0},
                                        Rgb{0.6, 0.48, 0.64}};
    for (std::uint32_t t = 0; t < c.blocks.size(); t += 3) {
        for (int b = 0; b < 256; b += 3) {
            for (const Rgb& n : normals) {
                const double v = b;
                c.add({v, v, v}, n, true, t);
                c.add({v * 0.55, v * 0.6, v}, n, false, t);
            }
        }
    }
    // TONE's shoulder and the clamps: a bright white surface facing the sun, every level, and the sun just grazing
    const Rgb& sun = c.blocks.at(bright_index).sun_vector;
    for (int b = 0; b < 256; ++b) {
        const double v = b;
        c.add({v, v, v}, sun, true, bright_index);
        c.add({v, v, v}, {sun[0], -sun[1], sun[2]}, true, bright_index);
    }
    const Rgb grazing = {sun[2], 0.0, -sun[0]}; // perpendicular to the sun's direction in the ground plane
    c.add({255.0, 255.0, 255.0}, grazing, true, bright_index);
    c.add({0.0, 0.0, 0.0}, sun, true, bright_index);
    // near ties found by search: surfaces whose port value lies within 10^-6 of a half level, in every theme
    std::mt19937_64 search(7); // NOLINT(cert-msc51-cpp): a fixed seed
    std::size_t near_ties = 0;
    for (int i = 0; i < 400000 && near_ties < 3000; ++i) {
        Rgb base{};
        for (double& v : base) {
            v = static_cast<double>(search() % 256);
        }
        const Rgb n = (search() % 2 == 0) ? Rgb{1.0, 0.0, 0.0} : random_normal(search);
        const bool lit = search() % 2 == 0;
        const auto t = static_cast<std::uint32_t>(search() % c.blocks.size());
        const Rgb p = port_light(c.blocks.at(t), base, n, lit);
        if (from_half(p[0]) < 1e-6 || from_half(p[1]) < 1e-6 || from_half(p[2]) < 1e-6) {
            c.add(base, n, lit, t);
            ++near_ties;
        }
    }
    return c;
}

const Case& the_case() {
    static const Case c = make_case();
    return c;
}

std::array<int, 3> unpack(std::uint32_t v) {
    return {static_cast<int>(v & 0xFFU), static_cast<int>((v >> 8U) & 0xFFU), static_cast<int>((v >> 16U) & 0xFFU)};
}

std::array<int, 3> port_bytes(const Rgb& v) { return {port_byte(v[0]), port_byte(v[1]), port_byte(v[2])}; }

bool same_bits(double a, double b) { return std::bit_cast<std::uint64_t>(a) == std::bit_cast<std::uint64_t>(b); }

/// A probe that could not run: a skip where there is no Vulkan and none is required (ATLAS_REQUIRE_VULKAN), else a
/// failure.
void not_run(const atlas::style::ProbeError& e) {
    if (e.unavailable && !atlas::style::vulkan_required()) {
        MESSAGE("skipped, no Vulkan: " << e.what);
        return;
    }
    const std::string why = e.unavailable ? "Vulkan is required (ATLAS_REQUIRE_VULKAN=1) but: " : "the probe failed: ";
    FAIL_CHECK(why << e.what);
}

/// How the binary32 light compares with the port: its worst distance in levels, and the bytes that differ where the
/// port's value is farther than the bound from a half level (none may), or nearer (one level at most).
struct F32Stats {
    double worst = 0.0;
    std::size_t far_differ = 0;
    std::size_t near_differ = 0;
    std::size_t near = 0;
    int worst_level = 0;
};

F32Stats compare_f32(const Case& c, const std::vector<std::array<float, 3>>& values,
                     const std::vector<std::array<int, 3>>& bytes) {
    F32Stats s;
    for (std::size_t i = 0; i < c.inputs.size(); ++i) {
        const auto want = port_bytes(c.port[i]);
        for (std::size_t k = 0; k < 3; ++k) {
            const double d = c.port[i].at(k);
            s.worst = std::max(s.worst, std::abs(static_cast<double>(values[i].at(k)) - d));
            const int diff = std::abs(bytes[i].at(k) - want.at(k));
            s.worst_level = std::max(s.worst_level, diff);
            const bool near = from_half(d) <= atlas::shaders::light_f32_bound && d > 0.0 && d < 255.0; // 0.5 ... 254.5
            s.near += near ? 1U : 0U;
            if (diff != 0) {
                (near ? s.near_differ : s.far_differ) += 1;
            }
        }
    }
    return s;
}

} // namespace

TEST_CASE("the inputs tell a truncating tohex and a changed coefficient from the port's light") {
    const Case& c = the_case();
    std::size_t truncated = 0;
    std::size_t changed = 0;
    for (std::size_t i = 0; i < c.random; ++i) {
        const auto want = port_bytes(c.port[i]);
        const LightInput& p = c.inputs[i];
        const Rgb other = port_light(c.blocks.at(p.theme), p.base, p.normal, p.lit != 0U, Rule{.sky = 0.181});
        bool t = false;
        for (std::size_t k = 0; k < 3; ++k) {
            t = t || truncated_byte(c.port[i].at(k)) != want.at(k);
        }
        truncated += t ? 1U : 0U;
        changed += (!same_bits(other[0], c.port[i][0]) || !same_bits(other[1], c.port[i][1]) ||
                    !same_bits(other[2], c.port[i][2]))
                       ? 1U
                       : 0U;
    }
    MESSAGE("of " << c.random << " random surfaces, a truncating tohex changes " << truncated
                  << " and the sky's 0.18 made 0.181 changes " << changed);
    CHECK(truncated > c.random / 2);
    CHECK(changed > c.random / 2);
}

TEST_CASE("the twin's light is the port's, bit for bit, over 100,000 random surfaces and the edges") {
    const Case& c = the_case();
    std::size_t values_differ = 0;
    std::size_t bytes_differ = 0;
    for (std::size_t i = 0; i < c.inputs.size(); ++i) {
        const LightInput& p = c.inputs[i];
        const atlas::shaders::double3 base = {p.base[0], p.base[1], p.base[2]};
        const atlas::shaders::double3 n = {p.normal[0], p.normal[1], p.normal[2]};
        const atlas::shaders::double3 v = atlas::shaders::light(c.themes.at(p.theme), base, n, p.lit != 0U);
        const atlas::shaders::uint3 b = atlas::shaders::tohex3(v);
        const auto want = port_bytes(c.port[i]);
        values_differ +=
            (!same_bits(v.x, c.port[i][0]) || !same_bits(v.y, c.port[i][1]) || !same_bits(v.z, c.port[i][2])) ? 1U : 0U;
        const std::array<int, 3> got = {static_cast<int>(b.x), static_cast<int>(b.y), static_cast<int>(b.z)};
        bytes_differ += got != want ? 1U : 0U;
    }
    MESSAGE("twin: " << c.inputs.size() << " surfaces (" << c.random << " random, " << (c.inputs.size() - c.random)
                     << " edges): " << values_differ << " values and " << bytes_differ
                     << " bytes differ from the port");
    CHECK(values_differ == 0);
    CHECK(bytes_differ == 0);
}

TEST_CASE("tohex rounds as Python's round: half to even, the largest double under a half down") {
    for (const double v : {0.49999999999999994, 0.5, 1.5, 2.5, 24.5, 24.500000000000004, 254.5, 255.5,
                           255.49999999999997, -0.0, -0.4, -0.5, -0.6, 300.0, 1e15}) {
        CHECK_MESSAGE(atlas::shaders::tohex(v) == static_cast<double>(port_byte(v)), "tohex(" << v << ")");
    }
    std::mt19937_64 rnd(11); // NOLINT(cert-msc51-cpp): a fixed seed
    std::uniform_real_distribution<double> u(-2.0, 260.0);
    for (int i = 0; i < 100000; ++i) {
        const double v = u(rnd);
        ATLAS_REQUIRE(atlas::shaders::tohex(v) == static_cast<double>(port_byte(v)),
                      "tohex(" << v << ") is " << atlas::shaders::tohex(v) << ", the port's " << port_byte(v));
    }
}

TEST_CASE("the exact light on the host's Vulkan device is the port's, bit for bit") {
    const Case& c = the_case();
    auto out = atlas::style::run_light_probe(c.inputs, c.themes);
    if (!out) {
        not_run(out.error());
        return;
    }
    ATLAS_REQUIRE(out->bytes.size() == c.inputs.size() && out->values.size() == 3 * c.inputs.size(),
                  out->bytes.size() << " bytes and " << out->values.size() << " values for " << c.inputs.size()
                                    << " surfaces");
    std::size_t values_differ = 0;
    std::size_t bytes_differ = 0;
    std::size_t first = c.inputs.size();
    for (std::size_t i = 0; i < c.inputs.size(); ++i) {
        const auto got = unpack(out->bytes[i]);
        const auto want = port_bytes(c.port[i]);
        const bool values = same_bits(out->values[3 * i], c.port[i][0]) &&
                            same_bits(out->values[(3 * i) + 1], c.port[i][1]) &&
                            same_bits(out->values[(3 * i) + 2], c.port[i][2]);
        values_differ += values ? 0U : 1U;
        bytes_differ += got == want ? 0U : 1U;
        if ((!values || got != want) && first == c.inputs.size()) {
            first = i;
        }
    }
    MESSAGE("GPU on " << out->device << ": " << c.inputs.size() << " surfaces, " << values_differ << " values and "
                      << bytes_differ << " bytes differ from the port");
    if (first < c.inputs.size()) {
        MESSAGE("first difference: surface " << first << ", port " << c.port[first][0] << " " << c.port[first][1] << " "
                                             << c.port[first][2] << ", GPU " << out->values[3 * first] << " "
                                             << out->values[(3 * first) + 1] << " " << out->values[(3 * first) + 2]);
    }
    CHECK(values_differ == 0);
    CHECK(bytes_differ == 0);
}

TEST_CASE("the GPU's exact exp and divisions are StrictMath.exp's and IEEE division's bits") {
    std::mt19937_64 rnd(5); // NOLINT(cert-msc51-cpp): a fixed seed
    std::uniform_real_distribution<double> shoulder(-12.0, 0.0);
    std::uniform_real_distribution<double> wide(-708.39, 709.78);
    std::uniform_real_distribution<double> exponent(-900.0, 900.0);
    std::uniform_real_distribution<double> unit(1.0, 2.0);
    std::uniform_real_distribution<double> numerator(-0.2, 0.2);
    std::uniform_real_distribution<double> divisor(1.5, 2.5);
    // exp's arguments: TONE's shoulder (-(v - 205) / 50 for v up to 700), every normal result, and the edges
    std::vector<double> xs = {0.0,
                              -0.0,
                              1e-300,
                              -1e-300,
                              0x1p-28,
                              -0x1p-28,
                              0x1p-29,
                              0.34657359027997264,
                              -0.34657359027997264,
                              0.3465735902799727,
                              1.0397207708399179,
                              -1.0397207708399179,
                              1.03972077083991818,
                              709.78,
                              709.782712893384,
                              -708.39,
                              -1.0,
                              1.0,
                              -0.5,
                              -9.9};
    std::vector<LightInput> ops;
    for (std::size_t i = 0; i < 1000000; ++i) {
        const double x = i < 500000 ? shoulder(rnd) : wide(rnd);
        // light's numerators: base * lam * 1.05 * sun_k * sun (0 to 10^5) and -(v - 205) (-500 to 0), and any normal
        const double scale = std::ldexp(unit(rnd), static_cast<int>(exponent(rnd)));
        const double d255 = i % 3 == 0 ? 1e5 * unit(rnd) - 1e5 : (i % 3 == 1 ? scale : -scale);
        const double d50 = i % 2 == 0 ? -500.0 * (unit(rnd) - 1.0) : scale;
        ops.push_back(LightInput{.base = {i < xs.size() ? xs.at(i) : x, d255, d50},
                                 .normal = {numerator(rnd), divisor(rnd), 0.0},
                                 .lit = 0,
                                 .theme = 0});
    }
    const std::vector<atlas::shaders::Theme> theme = {atlas::style::shader_theme(the_case().blocks.front())};
    auto out = atlas::style::run_light_probe(ops, theme, atlas::style::ProbeMode::operations);
    if (!out) {
        not_run(out.error());
        return;
    }
    ATLAS_REQUIRE(out->values.size() == 3 * ops.size(), out->values.size()
                                                            << " values for " << ops.size() << " inputs");
    std::size_t exp_differ = 0;
    std::size_t div_differ = 0;
    for (std::size_t i = 0; i < ops.size(); ++i) {
        const LightInput& p = ops[i];
        if (!same_bits(out->values[3 * i], atlas::pymath::strict::exp(p.base[0]))) {
            if (exp_differ == 0) {
                MESSAGE("exp(" << p.base[0] << "): GPU " << out->values[3 * i] << ", StrictMath "
                               << atlas::pymath::strict::exp(p.base[0]));
            }
            ++exp_differ;
        }
        const bool divisions = same_bits(out->values[(3 * i) + 1], p.base[1] / 255.0) &&
                               same_bits(out->values[(3 * i) + 2], p.base[2] / 50.0);
        div_differ += divisions ? 0U : 1U;
    }
    auto quotients = atlas::style::run_light_probe(ops, theme, atlas::style::ProbeMode::quotient);
    ATLAS_REQUIRE(quotients.has_value(), quotients.error().what);
    ATLAS_REQUIRE(quotients->values.size() == 3 * ops.size(), quotients->values.size()
                                                                  << " values for " << ops.size() << " inputs");
    std::size_t quotient_differ = 0;
    for (std::size_t i = 0; i < ops.size(); ++i) {
        quotient_differ += same_bits(quotients->values[3 * i], ops[i].normal[0] / ops[i].normal[1]) ? 0U : 1U;
    }
    MESSAGE("GPU on " << out->device << ", over " << ops.size() << " arguments each: exp differs from StrictMath.exp "
                      << exp_differ << " times, x/255 and x/50 from IEEE division " << div_differ
                      << " times, exp's quotient " << quotient_differ << " times");
    CHECK(exp_differ == 0);
    CHECK(div_differ == 0);
    CHECK(quotient_differ == 0);
}

TEST_CASE("light_f32 is within its bound of the port's light, as C++ and on the host's Vulkan device") {
    const Case& c = the_case();
    std::vector<atlas::style::LightInputF32> in(c.inputs.size());
    for (std::size_t i = 0; i < c.inputs.size(); ++i) {
        const LightInput& p = c.inputs[i];
        const atlas::shaders::float4 base = {static_cast<float>(p.base[0]), static_cast<float>(p.base[1]),
                                             static_cast<float>(p.base[2]), p.lit != 0U ? 1.0F : 0.0F};
        const atlas::shaders::float4 normal = {static_cast<float>(p.normal[0]), static_cast<float>(p.normal[1]),
                                               static_cast<float>(p.normal[2]), static_cast<float>(p.theme)};
        in[i] = atlas::style::LightInputF32{.base = base, .normal = normal};
    }
    std::vector<std::array<float, 3>> values(in.size());
    std::vector<std::array<int, 3>> bytes(in.size());
    for (std::size_t i = 0; i < in.size(); ++i) {
        const auto v = atlas::shaders::light_f32(c.themes.at(c.inputs[i].theme), in[i].base.xyz, in[i].normal.xyz,
                                                 in[i].base.w != 0.0F);
        const auto b = atlas::shaders::tohex3_f32(v);
        values[i] = {v.x, v.y, v.z};
        bytes[i] = {static_cast<int>(b.x), static_cast<int>(b.y), static_cast<int>(b.z)};
    }
    const F32Stats twin = compare_f32(c, values, bytes);
    MESSAGE("light_f32 as C++: worst " << twin.worst << " levels from the port (bound "
                                       << atlas::shaders::light_f32_bound << "); bytes differ " << twin.far_differ
                                       << " times far from a half level and " << twin.near_differ << " times of "
                                       << twin.near << " channels within the bound of one, worst " << twin.worst_level);
    CHECK(twin.worst <= atlas::shaders::light_f32_bound);
    CHECK(twin.far_differ == 0);
    CHECK(twin.worst_level <= 1);

    auto out = atlas::style::run_light_probe_f32(in, c.themes);
    if (!out) {
        not_run(out.error());
        return;
    }
    ATLAS_REQUIRE(out->values.size() == in.size() && out->bytes.size() == in.size(),
                  out->bytes.size() << " bytes and " << out->values.size() << " values for " << in.size()
                                    << " surfaces");
    for (std::size_t i = 0; i < in.size(); ++i) {
        values[i] = {out->values[i].x, out->values[i].y, out->values[i].z};
        bytes[i] = unpack(out->bytes[i]);
    }
    const F32Stats gpu = compare_f32(c, values, bytes);
    MESSAGE("light_f32 on " << out->device << ": worst " << gpu.worst << " levels from the port; bytes differ "
                            << gpu.far_differ << " times far from a half level and " << gpu.near_differ << " times of "
                            << gpu.near << " channels within the bound of one, worst " << gpu.worst_level);
    CHECK(gpu.worst <= atlas::shaders::light_f32_bound);
    CHECK(gpu.far_differ == 0);
    CHECK(gpu.worst_level <= 1);
}
