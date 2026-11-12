// The night's light on the ground as a map a tile: the bake and its thread (LightMap.hpp).
#include "passes/LightMap.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <utility>

namespace atlas::passes {

namespace {

/// A piece's side (texels), and what the cascades read about it: a light's reach past the cascades' coarsest probes'
/// spacing, to a multiple of that spacing.
constexpr std::int32_t piece_side = 128;
constexpr std::int32_t piece_about = 24;
constexpr std::int32_t window_side = piece_side + (2 * piece_about);
/// How near a piece a light must lie to light any of it (texels): its pool's reach and the cascades' spread.
constexpr float light_near = fx::light_reach + 6.0F;

/// A ring's box (texels).
struct RingBox {
    float x0 = 0;
    float z0 = 0;
    float x1 = 0;
    float z1 = 0;
};

/// Marks the texels of `grid` (a window whose corner is (wx, wz) in the job's texels) whose centres ring `ring` of
/// `job` holds, by the even-odd rule along each row.
void fill_ring(const LightJob& job, std::size_t ring, std::int32_t wx, std::int32_t wz, const RingBox& box,
               fx::LightGrid& grid, std::vector<float>& crossings) {
    const std::uint32_t first = job.ring_first[ring];
    const std::uint32_t count = job.ring_first[ring + 1] - first;
    if (count < 3) return;
    const std::int32_t row0 = std::max(0, static_cast<std::int32_t>(std::floor(box.z0 - 0.5F)) - wz);
    const std::int32_t row1 = std::min(grid.height - 1, static_cast<std::int32_t>(std::ceil(box.z1 - 0.5F)) - wz);
    for (std::int32_t row = row0; row <= row1; ++row) {
        const float z = static_cast<float>(row + wz) + 0.5F;
        crossings.clear();
        for (std::uint32_t i = 0; i < count; ++i) {
            const std::uint32_t a = first + i;
            const std::uint32_t b = first + ((i + 1) % count);
            const float az = job.ring_points[(std::size_t{2} * a) + 1];
            const float bz = job.ring_points[(std::size_t{2} * b) + 1];
            if ((az <= z) == (bz <= z)) continue;
            const float ax = job.ring_points[std::size_t{2} * a];
            const float bx = job.ring_points[std::size_t{2} * b];
            crossings.push_back(ax + ((bx - ax) * ((z - az) / (bz - az))));
        }
        std::ranges::sort(crossings);
        for (std::size_t c = 0; c + 1 < crossings.size(); c += 2) {
            // the texels whose centres lie in [crossings[c], crossings[c + 1])
            const std::int32_t t0 = std::max(0, static_cast<std::int32_t>(std::ceil(crossings[c] - 0.5F)) - wx);
            const std::int32_t t1 =
                std::min(grid.width - 1, static_cast<std::int32_t>(std::ceil(crossings[c + 1] - 0.5F)) - 1 - wx);
            for (std::int32_t t = t0; t <= t1; ++t)
                grid.solid[(static_cast<std::size_t>(row) * static_cast<std::size_t>(grid.width)) +
                           static_cast<std::size_t>(t)] = 1;
        }
    }
}

/// A depth's alpha as a byte.
[[nodiscard]] std::uint16_t byte_of(float depth) noexcept {
    return static_cast<std::uint16_t>(std::lround(std::clamp(fx::light_over(depth), 0.0F, 1.0F) * 255.0F));
}

} // namespace

std::array<std::uint8_t, 2> LightTile::at(std::int32_t x, std::int32_t z) const noexcept {
    if (x < 0 || z < 0 || x >= blocks_x * light_block || z >= blocks_z * light_block) return {0, 0};
    const std::uint16_t place = index[(static_cast<std::size_t>(z / light_block) * static_cast<std::size_t>(blocks_x)) +
                                      static_cast<std::size_t>(x / light_block)];
    if (place == 0) return {0, 0};
    const std::size_t texel = (static_cast<std::size_t>((z % light_block) + 1) * static_cast<std::size_t>(light_slot)) +
                              static_cast<std::size_t>((x % light_block) + 1);
    const std::uint32_t word = blocks[(std::size_t{place - 1U} * light_slot_words) + (texel / 2)];
    const std::uint32_t both = (texel % 2) != 0 ? word >> 16U : word & 0xFFFFU;
    return {static_cast<std::uint8_t>(both & 0xFFU), static_cast<std::uint8_t>(both >> 8U)};
}

LightTile bake_light(const LightJob& job) {
    const auto began = std::chrono::steady_clock::now();
    LightTile out;
    out.x0 = job.x0;
    out.z0 = job.z0;
    out.blocks_x = job.blocks_x;
    out.blocks_z = job.blocks_z;
    const std::int32_t width = job.blocks_x * light_block;
    const std::int32_t height = job.blocks_z * light_block;
    out.index.assign(static_cast<std::size_t>(job.blocks_x) * static_cast<std::size_t>(job.blocks_z), 0);
    for (const fx::LightEmitter& e : job.emitters) ++out.lights[e.kind];
    if (job.emitters.empty() || width <= 0 || height <= 0) return out;
    // each ring's box
    const std::size_t rings = job.ring_first.size() - 1;
    std::vector<RingBox> boxes(rings);
    for (std::size_t r = 0; r < rings; ++r) {
        RingBox b{.x0 = 1e30F, .z0 = 1e30F, .x1 = -1e30F, .z1 = -1e30F};
        for (std::uint32_t i = job.ring_first[r]; i < job.ring_first[r + 1]; ++i) {
            const float x = job.ring_points[std::size_t{2} * i];
            const float z = job.ring_points[(std::size_t{2} * i) + 1];
            b.x0 = std::min(b.x0, x);
            b.x1 = std::max(b.x1, x);
            b.z0 = std::min(b.z0, z);
            b.z1 = std::max(b.z1, z);
        }
        boxes[r] = b;
    }
    // the map whole, a texel the two alphas, while the pieces are walked
    std::vector<std::uint16_t> alphas(static_cast<std::size_t>(width) * static_cast<std::size_t>(height), 0);
    fx::LightGrid grid;
    grid.width = window_side;
    grid.height = window_side;
    std::vector<float> crossings;
    for (std::int32_t pz = 0; pz < height; pz += piece_side) {
        for (std::int32_t px = 0; px < width; px += piece_side) {
            const std::int32_t wx = px - piece_about;
            const std::int32_t wz = pz - piece_about;
            grid.emitters.clear();
            bool lit = false;
            for (const fx::LightEmitter& e : job.emitters) {
                const float x = e.x - static_cast<float>(wx);
                const float z = e.z - static_cast<float>(wz);
                if (x < 0 || z < 0 || x >= static_cast<float>(window_side) || z >= static_cast<float>(window_side))
                    continue;
                grid.emitters.push_back({.x = x, .z = z, .kind = e.kind});
                const auto about = static_cast<float>(piece_about);
                lit = lit || (x > about - light_near && z > about - light_near &&
                              x < about + static_cast<float>(piece_side) + light_near &&
                              z < about + static_cast<float>(piece_side) + light_near);
            }
            if (!lit) continue;
            grid.solid.assign(static_cast<std::size_t>(window_side) * window_side, 0);
            for (std::size_t r = 0; r < rings; ++r) {
                const RingBox& b = boxes[r];
                if (b.x1 < static_cast<float>(wx) || b.z1 < static_cast<float>(wz) ||
                    b.x0 > static_cast<float>(wx + window_side) || b.z0 > static_cast<float>(wz + window_side))
                    continue;
                fill_ring(job, r, wx, wz, b, grid, crossings);
            }
            fx::LightCost cost;
            const std::vector<float> depths = fx::light_depths(grid, &cost);
            out.cost.pairs += cost.pairs;
            out.cost.rays += cost.rays;
            out.cost.steps += cost.steps;
            ++out.pieces;
            const std::int32_t x1 = std::min(width, px + piece_side);
            const std::int32_t z1 = std::min(height, pz + piece_side);
            for (std::int32_t z = pz; z < z1; ++z) {
                for (std::int32_t x = px; x < x1; ++x) {
                    const std::size_t from =
                        ((static_cast<std::size_t>(z - wz) * window_side) + static_cast<std::size_t>(x - wx)) *
                        fx::light_kinds;
                    const std::uint16_t lamps = byte_of(depths[from + fx::light_lamp]);
                    const std::uint16_t open = byte_of(depths[from + fx::light_open]);
                    alphas[(static_cast<std::size_t>(z) * static_cast<std::size_t>(width)) +
                           static_cast<std::size_t>(x)] = static_cast<std::uint16_t>(lamps | (open << 8U));
                }
            }
        }
    }
    // the blocks some light reaches, each with a texel of what lies about it
    const auto alpha_at = [&](std::int32_t x, std::int32_t z) -> std::uint32_t {
        if (x < 0 || z < 0 || x >= width || z >= height) return 0;
        return alphas[(static_cast<std::size_t>(z) * static_cast<std::size_t>(width)) + static_cast<std::size_t>(x)];
    };
    for (std::int32_t bz = 0; bz < job.blocks_z; ++bz) {
        for (std::int32_t bx = 0; bx < job.blocks_x; ++bx) {
            bool lit = false;
            for (std::int32_t z = 0; z < light_block && !lit; ++z) {
                for (std::int32_t x = 0; x < light_block && !lit; ++x)
                    lit = alpha_at((bx * light_block) + x, (bz * light_block) + z) != 0;
            }
            if (!lit) continue;
            const std::size_t place = out.blocks.size() / light_slot_words;
            if (place >= 0xFFFFU) continue; // more lit blocks than an index holds: none are
            out.index[(static_cast<std::size_t>(bz) * static_cast<std::size_t>(job.blocks_x)) +
                      static_cast<std::size_t>(bx)] = static_cast<std::uint16_t>(place + 1);
            out.blocks.resize(out.blocks.size() + light_slot_words, 0);
            std::uint32_t* words = &out.blocks[place * light_slot_words];
            for (std::int32_t z = 0; z < light_slot; ++z) {
                for (std::int32_t x = 0; x < light_slot; ++x) {
                    const std::uint32_t both = alpha_at((bx * light_block) + x - 1, (bz * light_block) + z - 1);
                    const std::size_t texel =
                        (static_cast<std::size_t>(z) * static_cast<std::size_t>(light_slot)) + static_cast<std::size_t>(x);
                    words[texel / 2] |= both << ((texel % 2) * 16U);
                }
            }
        }
    }
    out.ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - began).count();
    return out;
}

LightBaker::LightBaker() : thread_([this](const std::stop_token& stop) { run(stop); }) {}

LightBaker::~LightBaker() {
    thread_.request_stop();
    changed_.notify_all();
}

void LightBaker::submit(std::uint64_t ticket, LightJob job) {
    {
        const std::scoped_lock lock(mutex_);
        jobs_.emplace_back(ticket, std::move(job));
    }
    changed_.notify_all();
}

std::vector<std::pair<std::uint64_t, std::shared_ptr<const LightTile>>> LightBaker::take() {
    const std::scoped_lock lock(mutex_);
    return std::exchange(done_, {});
}

std::size_t LightBaker::pending() const {
    const std::scoped_lock lock(mutex_);
    return jobs_.size() + baking_ + done_.size();
}

std::size_t LightBaker::ready() const {
    const std::scoped_lock lock(mutex_);
    return done_.size();
}

void LightBaker::wait() const {
    std::unique_lock lock(mutex_);
    changed_.wait(lock, [this] { return jobs_.empty() && baking_ == 0; });
}

void LightBaker::run(const std::stop_token& stop) {
    for (;;) {
        std::pair<std::uint64_t, LightJob> job;
        {
            std::unique_lock lock(mutex_);
            if (!changed_.wait(lock, stop, [this] { return !jobs_.empty(); })) return;
            job = std::move(jobs_.front());
            jobs_.pop_front();
            ++baking_;
        }
        auto baked = std::make_shared<const LightTile>(bake_light(job.second));
        {
            const std::scoped_lock lock(mutex_);
            done_.emplace_back(job.first, std::move(baked));
            --baking_;
        }
        changed_.notify_all();
    }
}

} // namespace atlas::passes
