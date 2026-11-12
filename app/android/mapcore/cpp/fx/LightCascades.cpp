// The night's light on the ground by radiance cascades (LightCascades.hpp).
#include "fx/LightCascades.hpp"

#include <algorithm>
#include <cmath>
#include <numbers>

namespace atlas::fx {

namespace {

constexpr float pi = std::numbers::pi_v<float>;
constexpr float two_pi = 2.0F * pi;
/// The emitters' buckets' side (texels).
constexpr std::int32_t bucket_side = 8;
/// A ray's step (texels).
constexpr float ray_step = 0.5F;

/// How much of a light `d` texels away cascade `i` and those above it lay between them: all of it from the first
/// down, and from the others what has risen of their share.
[[nodiscard]] float risen(std::size_t i, float d) noexcept {
    if (i == 0) return 1.0F;
    if (i >= light_cascades) return 0.0F;
    const std::array<float, 2>& r = light_rises[i];
    return std::clamp((d - r[0]) / (r[1] - r[0]), 0.0F, 1.0F);
}

/// A lone light's share of the directions about a place `d` texels from its foot.
[[nodiscard]] float seen(float d) noexcept { return d <= light_radius ? 1.0F : std::asin(light_radius / d) / pi; }

/// The optical depth a kind's pool lays `d` texels from its light's foot.
[[nodiscard]] float depth_of(std::uint8_t kind, float d) noexcept { return -std::log1p(-light_alpha(kind, d)); }

/// The scene, with its lights in buckets.
struct Scene {
    const LightGrid& grid;
    std::int32_t bw = 0;
    std::int32_t bh = 0;
    std::vector<std::uint32_t> first; // a bucket's first light in `order`, and one past the last
    std::vector<std::uint32_t> order;
    LightCost cost;

    explicit Scene(const LightGrid& g) : grid(g) {
        bw = (g.width + bucket_side - 1) / bucket_side;
        bh = (g.height + bucket_side - 1) / bucket_side;
        first.assign((static_cast<std::size_t>(bw) * static_cast<std::size_t>(bh)) + 1, 0);
        const auto bucket = [this](const LightEmitter& e) {
            const std::int32_t bx = std::clamp(static_cast<std::int32_t>(std::floor(e.x)) / bucket_side, 0, bw - 1);
            const std::int32_t bz = std::clamp(static_cast<std::int32_t>(std::floor(e.z)) / bucket_side, 0, bh - 1);
            return (static_cast<std::size_t>(bz) * static_cast<std::size_t>(bw)) + static_cast<std::size_t>(bx);
        };
        for (const LightEmitter& e : g.emitters) ++first[bucket(e) + 1];
        for (std::size_t b = 1; b < first.size(); ++b) first[b] += first[b - 1];
        order.resize(g.emitters.size());
        std::vector<std::uint32_t> at(first.begin(), first.end() - 1);
        for (std::uint32_t k = 0; k < g.emitters.size(); ++k) order[at[bucket(g.emitters[k])]++] = k;
    }

    /// Whether a building holds the texel under (x, z); none beyond the grid.
    [[nodiscard]] bool solid(float x, float z) const noexcept {
        const auto tx = static_cast<std::int32_t>(std::floor(x));
        const auto tz = static_cast<std::int32_t>(std::floor(z));
        if (tx < 0 || tz < 0 || tx >= grid.width || tz >= grid.height) return false;
        return grid.solid[(static_cast<std::size_t>(tz) * static_cast<std::size_t>(grid.width)) +
                          static_cast<std::size_t>(tx)] != 0;
    }

    /// Whether the line from (x, z) along the unit (ux, uz) is free of buildings from `from` to `to` texels.
    [[nodiscard]] bool free(float x, float z, float ux, float uz, float from, float to) noexcept {
        for (float t = from; t < to; t += ray_step) { // NOLINT(cert-flp30-c): whole halves, exact in a float
            ++cost.steps;
            if (solid(x + (ux * t), z + (uz * t))) return false;
        }
        return true;
    }
};

/// A cascade: its probes' spacing, its probes and wedges, each probe's radiance by wedge and kind, and whether the
/// probe stands in the open.
struct Cascade {
    std::int32_t spacing = 1;
    std::int32_t pw = 0;
    std::int32_t ph = 0;
    std::uint32_t wedges = 0;
    std::vector<float> radiance;
    std::vector<std::uint8_t> open;

    [[nodiscard]] std::size_t at(std::int32_t px, std::int32_t pz, std::uint32_t k) const noexcept {
        return (((static_cast<std::size_t>(pz) * static_cast<std::size_t>(pw)) + static_cast<std::size_t>(px)) *
                wedges + k) * light_kinds;
    }
};

/// The four probes of the cascade above about probe (px, pz) of the one below, and their weights: bilinear between
/// those that stand in the open, summing to one (all nothing where none does).
struct Above {
    std::array<std::int32_t, 4> x{};
    std::array<std::int32_t, 4> z{};
    std::array<float, 4> weight{};
    bool any = false;
};
[[nodiscard]] Above above_of(const Cascade& up, std::int32_t px, std::int32_t pz) noexcept {
    Above a;
    const std::int32_t qx = (px - 1) >> 1; // floor((px - 1) / 2), -1 at the first column
    const std::int32_t qz = (pz - 1) >> 1;
    const float fx = (px & 1) != 0 ? 0.25F : 0.75F;
    const float fz = (pz & 1) != 0 ? 0.25F : 0.75F;
    float total = 0.0F;
    for (std::uint32_t corner = 0; corner < 4; ++corner) {
        a.x[corner] = std::clamp(qx + static_cast<std::int32_t>(corner & 1U), 0, up.pw - 1);
        a.z[corner] = std::clamp(qz + static_cast<std::int32_t>(corner >> 1U), 0, up.ph - 1);
        const std::size_t q = (static_cast<std::size_t>(a.z[corner]) * static_cast<std::size_t>(up.pw)) +
                              static_cast<std::size_t>(a.x[corner]);
        const float w = ((corner & 1U) != 0 ? fx : 1.0F - fx) * ((corner >> 1U) != 0 ? fz : 1.0F - fz);
        a.weight[corner] = up.open[q] != 0 ? w : 0.0F;
        total += a.weight[corner];
    }
    a.any = total > 0.0F;
    if (a.any) {
        for (float& w : a.weight) w /= total;
    }
    return a;
}

/// Cascade `i`'s own lights into `c`, under the cascade above (`up`, none for the last): at each probe in the open,
/// of each light near enough, what the pool's depth there (the part that is this cascade's and those above's) lacks
/// of what the cascade above lays there in the open, where the straight line to the light is free.
void own_lights(Scene& s, std::size_t i, Cascade& c, const Cascade* up) {
    // a cascade above spreads a light up to its probes' spacing past the pool's reach, which this one takes back
    const float past = up != nullptr ? static_cast<float>(up->spacing) * 1.5F : 0.0F;
    const float wedge = two_pi / static_cast<float>(c.wedges);
    const auto step = static_cast<float>(c.spacing);
    for (std::int32_t pz = 0; pz < c.ph; ++pz) {
        for (std::int32_t px = 0; px < c.pw; ++px) {
            const float x = (static_cast<float>(px) + 0.5F) * step;
            const float z = (static_cast<float>(pz) + 0.5F) * step;
            const bool open = !s.solid(x, z);
            c.open[(static_cast<std::size_t>(pz) * static_cast<std::size_t>(c.pw)) + static_cast<std::size_t>(px)] =
                open ? 1 : 0;
            if (!open) continue;
            const Above above = up != nullptr ? above_of(*up, px, pz) : Above{};
            const float reach = light_reach + past;
            const std::int32_t b0x = std::max(0, static_cast<std::int32_t>(std::floor(x - reach)) / bucket_side);
            const std::int32_t b0z = std::max(0, static_cast<std::int32_t>(std::floor(z - reach)) / bucket_side);
            const std::int32_t b1x = std::min(s.bw - 1, static_cast<std::int32_t>(std::floor(x + reach)) / bucket_side);
            const std::int32_t b1z = std::min(s.bh - 1, static_cast<std::int32_t>(std::floor(z + reach)) / bucket_side);
            for (std::int32_t bz = b0z; bz <= b1z; ++bz) {
                for (std::int32_t bx = b0x; bx <= b1x; ++bx) {
                    const std::size_t b =
                        (static_cast<std::size_t>(bz) * static_cast<std::size_t>(s.bw)) + static_cast<std::size_t>(bx);
                    for (std::uint32_t o = s.first[b]; o < s.first[b + 1]; ++o) {
                        const LightEmitter& e = s.grid.emitters[s.order[o]];
                        const float dx = e.x - x;
                        const float dz = e.z - z;
                        const float d = std::sqrt((dx * dx) + (dz * dz));
                        if (d >= light_pools[e.kind].reach + past) continue;
                        float own = risen(i, d) * depth_of(e.kind, d);
                        if (above.any) {
                            const auto up_step = static_cast<float>(up->spacing);
                            for (std::uint32_t corner = 0; corner < 4; ++corner) {
                                if (above.weight[corner] <= 0.0F) continue;
                                const float ux = e.x - ((static_cast<float>(above.x[corner]) + 0.5F) * up_step);
                                const float uz = e.z - ((static_cast<float>(above.z[corner]) + 0.5F) * up_step);
                                const float ud = std::sqrt((ux * ux) + (uz * uz));
                                own -= above.weight[corner] * risen(i + 1, ud) * depth_of(e.kind, ud);
                            }
                        }
                        if (own == 0.0F) continue;
                        ++s.cost.pairs;
                        if (d <= light_radius) {
                            // within the disc: light from every side
                            for (std::uint32_t k = 0; k < c.wedges; ++k) c.radiance[c.at(px, pz, k) + e.kind] += own;
                            continue;
                        }
                        if (!s.free(x, z, dx / d, dz / d, ray_step, d - light_radius)) continue;
                        // over the wedges the disc fills, as the radiance whose mean over all of them is `own`
                        const float w = own / seen(d);
                        const float half = std::asin(light_radius / d);
                        float mid = std::atan2(dz, dx);
                        if (mid < 0.0F) mid += two_pi;
                        const float lo = mid - half;
                        const float hi = mid + half;
                        const auto k0 = static_cast<std::int32_t>(std::floor(lo / wedge));
                        const auto k1 = static_cast<std::int32_t>(std::floor(hi / wedge));
                        for (std::int32_t k = k0; k <= k1; ++k) {
                            const float covered = std::min(hi, static_cast<float>(k + 1) * wedge) -
                                                  std::max(lo, static_cast<float>(k) * wedge);
                            if (covered <= 0.0F) continue;
                            const auto n = static_cast<std::int32_t>(c.wedges);
                            const auto kk = static_cast<std::uint32_t>(((k % n) + n) % n);
                            c.radiance[c.at(px, pz, kk) + e.kind] += w * (covered / wedge);
                        }
                    }
                }
            }
        }
    }
}

/// The cascade above (`up`) into `c` (cascade `i`): in each wedge whose four finer wedges above hold light between
/// the probe's nearest open probes there, that light where the wedge's middle ray crosses the interval free.
void merge(Scene& s, std::size_t i, Cascade& c, const Cascade& up) {
    const float wedge = two_pi / static_cast<float>(c.wedges);
    const auto step = static_cast<float>(c.spacing);
    for (std::int32_t pz = 0; pz < c.ph; ++pz) {
        for (std::int32_t px = 0; px < c.pw; ++px) {
            if (c.open[(static_cast<std::size_t>(pz) * static_cast<std::size_t>(c.pw)) + static_cast<std::size_t>(px)] ==
                0)
                continue;
            const Above above = above_of(up, px, pz);
            if (!above.any) continue;
            const float x = (static_cast<float>(px) + 0.5F) * step;
            const float z = (static_cast<float>(pz) + 0.5F) * step;
            for (std::uint32_t k = 0; k < c.wedges; ++k) {
                std::array<float, light_kinds> light{};
                for (std::uint32_t corner = 0; corner < 4; ++corner) {
                    if (above.weight[corner] <= 0.0F) continue;
                    const std::size_t base = up.at(above.x[corner], above.z[corner], k * 4U);
                    for (std::size_t j = 0; j < 4 * light_kinds; ++j)
                        light[j % light_kinds] += above.weight[corner] * up.radiance[base + j];
                }
                bool any = false;
                for (float& a : light) {
                    a /= 4.0F;
                    any = any || a != 0.0F;
                }
                if (!any) continue;
                const float angle = (static_cast<float>(k) + 0.5F) * wedge;
                ++s.cost.rays;
                if (!s.free(x, z, std::cos(angle), std::sin(angle), light_bounds[i] + ray_step, light_bounds[i + 1]))
                    continue;
                const std::size_t at = c.at(px, pz, k);
                for (std::size_t kind = 0; kind < light_kinds; ++kind) c.radiance[at + kind] += light[kind];
            }
        }
    }
}

} // namespace

std::vector<float> light_depths(const LightGrid& grid, LightCost* cost) {
    const auto w = static_cast<std::size_t>(grid.width);
    const auto h = static_cast<std::size_t>(grid.height);
    std::vector<float> out(w * h * light_kinds, 0.0F);
    if (grid.emitters.empty() || w == 0 || h == 0) return out;
    Scene s(grid);
    std::array<Cascade, light_cascades> cascades;
    for (std::size_t i = light_cascades; i-- > 0;) {
        Cascade& c = cascades[i];
        c.spacing = 1 << i;
        c.pw = grid.width >> i;
        c.ph = grid.height >> i;
        c.wedges = light_wedges << (2 * i);
        c.radiance.assign(static_cast<std::size_t>(c.pw) * static_cast<std::size_t>(c.ph) * c.wedges * light_kinds,
                          0.0F);
        c.open.assign(static_cast<std::size_t>(c.pw) * static_cast<std::size_t>(c.ph), 0);
        const Cascade* up = i + 1 < light_cascades ? &cascades[i + 1] : nullptr;
        own_lights(s, i, c, up);
        if (up != nullptr) merge(s, i, c, *up);
    }
    const Cascade& c = cascades[0];
    for (std::size_t t = 0; t < w * h; ++t) {
        if (c.open[t] == 0) continue;
        for (std::size_t kind = 0; kind < light_kinds; ++kind) {
            float sum = 0.0F;
            for (std::uint32_t k = 0; k < c.wedges; ++k) sum += c.radiance[(((t * c.wedges) + k) * light_kinds) + kind];
            out[(t * light_kinds) + kind] = std::max(0.0F, sum / static_cast<float>(c.wedges));
        }
    }
    if (cost != nullptr) *cost = s.cost;
    return out;
}

float light_alpha(std::uint8_t kind, float d) noexcept {
    const LightPool& p = light_pools[kind];
    const float t = d / p.reach;
    if (t >= 1.0F) return 0.0F;
    if (t <= p.middle) return p.foot + ((p.mid - p.foot) * (t / p.middle));
    return p.mid * (1.0F - ((t - p.middle) / (1.0F - p.middle)));
}

float light_over(float depth) noexcept { return -std::expm1(-depth); }

} // namespace atlas::fx
