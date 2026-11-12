// The night's light on the ground as a map a tile, in the ground pass (LightMap.hpp; passes/Ground.hpp): which tiles'
// maps a frame draws and the bakes it asks for, the maps' words and their blocks' slots, and their one draw.
#include "base/log.hpp"
#include "frame/FrameBuilder.hpp"
#include "frame/FrameLayout.hpp"
#include "gen/layouts.hpp"
#include "passes/Ground.hpp"
#include "passes/LightMap.hpp"
#include "passes/Shopfronts.hpp"
#include "scene/Residency.hpp"
#include "sun/Samples.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <numeric>
#include <optional>
#include <span>
#include <vector>

namespace atlas::passes {

LightMaps::LightMaps() : free(light_slots) {
    // the first slots first
    std::iota(free.rbegin(), free.rend(), 0U);
}

namespace {

namespace geo = atlas::geometry;

/// A hash of what a map is baked from, value after value (FNV-1a over each value's eight bytes).
struct Key {
    std::uint64_t value = 0xCBF2'9CE4'8422'2325ULL;
    void add(std::uint64_t v) noexcept {
        for (int b = 0; b < 8; ++b) {
            value ^= (v >> (8U * static_cast<unsigned>(b))) & 0xFFU;
            value *= 0x0000'0100'0000'01B3ULL;
        }
    }
};

/// A tile's box in its frame's metres: x0, z0, x1, z1.
[[nodiscard]] std::array<double, 4> box_of(const geo::GroundTile& g) noexcept {
    return {std::min(g.origin.x, g.far_corner.x), std::min(g.origin.z, g.far_corner.z),
            std::max(g.origin.x, g.far_corner.x), std::max(g.origin.z, g.far_corner.z)};
}

/// A gradient's stop as the SVG parser keeps it (Pools.cpp's folded): its colour with the stop's opacity folded into
/// its alpha, RGBA8 packed.
[[nodiscard]] std::uint32_t folded(std::uint32_t rgb, float opacity) noexcept {
    const auto alpha = static_cast<std::uint32_t>(std::floor((255.0F * opacity) + 0.5F));
    return geo::Rgba{.r = static_cast<std::uint8_t>((rgb >> 16U) & 0xFFU),
                     .g = static_cast<std::uint8_t>((rgb >> 8U) & 0xFFU),
                     .b = static_cast<std::uint8_t>(rgb & 0xFFU),
                     .a = static_cast<std::uint8_t>(alpha)}
        .packed();
}

/// A map's words in the frame's (ground.slang's LIGHT_TILE_WORDS): the screen's CSS px to its grid's texels (nine
/// floats, row by row), its blocks across and down, and where its blocks' slots lie.
constexpr std::uint32_t light_tile_words = 12;

/// The inverse of 3 × 3 `m` (row by row), scaled so that its largest number is 1; nothing where it has none.
[[nodiscard]] std::optional<std::array<double, 9>> inverse(const std::array<double, 9>& m) noexcept {
    const std::array<double, 9> a{(m[4] * m[8]) - (m[5] * m[7]), (m[2] * m[7]) - (m[1] * m[8]),
                                  (m[1] * m[5]) - (m[2] * m[4]), (m[5] * m[6]) - (m[3] * m[8]),
                                  (m[0] * m[8]) - (m[2] * m[6]), (m[2] * m[3]) - (m[0] * m[5]),
                                  (m[3] * m[7]) - (m[4] * m[6]), (m[1] * m[6]) - (m[0] * m[7]),
                                  (m[0] * m[4]) - (m[1] * m[3])};
    const double det = (m[0] * a[0]) + (m[1] * a[3]) + (m[2] * a[6]);
    if (!(std::abs(det) > 0)) return std::nullopt;
    double most = 0;
    for (const double v : a) most = std::max(most, std::abs(v / det));
    if (!(most > 0) || !std::isfinite(most)) return std::nullopt;
    std::array<double, 9> out{};
    for (std::size_t i = 0; i < 9; ++i) out[i] = (a[i] / det) / most;
    return out;
}

} // namespace

LightStats Ground::light_stats() const {
    if (light_ == nullptr) return {};
    LightStats s = light_->stats;
    s.maps = 0;
    s.bytes = 0;
    for (const std::unique_ptr<LightEntry>& e : light_->entries) {
        if (e->map == nullptr) continue;
        ++s.maps;
        s.bytes += e->map->bytes();
    }
    s.drawn = static_cast<std::uint32_t>(frame_.light_tiles.size());
    s.slots = light_slots - static_cast<std::uint32_t>(light_->free.size() + light_->resting.size());
    s.pending = static_cast<std::uint32_t>(light_->baker.pending());
    return s;
}

void Ground::wait_for_light() const {
    if (light_ != nullptr) light_->baker.wait();
}

bool Ground::fading() const noexcept {
    // a bake under way for a tile the last frame drew: its map is due
    if (light_ == nullptr) return false;
    return std::ranges::any_of(light_->entries, [this](const std::unique_ptr<LightEntry>& e) {
        return e->asked != 0 && e->seen == light_->frame;
    });
}

void Ground::decide_light(const frame::FrameState& state) {
    GroundFrame& f = frame_;
    f.light_tiles.clear();
    f.light_mapped.assign(f.tiles.size(), 0);
    const bool lamps_on = !no_pools_ && !f.out(style::camera_part::lamp_pools);
    const bool open_on = state.theme.has(style::theme_flag::daylight) && !f.out(style::camera_part::open_glows);
    if (!f.night || no_light_ || only_setts_ || f.tiles.empty() || !state.view ||
        !state.camera.has(style::camera_flag::light_cascades) || (!lamps_on && !open_on)) {
        return;
    }
    if (light_ == nullptr) light_ = std::make_unique<LightMaps>();
    LightMaps& l = *light_;
    ++l.frame;
    // the slots let go long enough ago are free again
    while (!l.resting.empty() && l.resting.front().first + light_slot_rest <= l.frame) {
        l.free.push_back(l.resting.front().second);
        l.resting.pop_front();
    }
    const auto let_go = [&l](LightEntry& e) {
        for (const std::uint32_t slot : e.slots) l.resting.emplace_back(l.frame, slot);
        e.slots.clear();
        e.uploaded = false;
    };
    // the bakes done: each its tile's map from now on
    for (auto& [ticket, baked] : l.baker.take()) {
        ++l.stats.bakes;
        l.stats.ms += baked->ms;
        l.stats.longest_ms = std::max(l.stats.longest_ms, baked->ms);
        l.stats.pieces += baked->pieces;
        l.stats.lit += baked->lit();
        for (const std::unique_ptr<LightEntry>& e : l.entries) {
            if (e->asked != ticket) continue;
            let_go(*e);
            e->map = std::move(baked);
            e->key = e->asked_key;
            e->asked = 0;
            break;
        }
    }
    // where the camera looks on the ground: the tiles near it draw their maps, the nearest first
    struct Near {
        double distance = 0;
        std::uint32_t tile = 0;
    };
    std::vector<Near> near;
    for (std::uint32_t ti = 0; ti < f.tiles.size(); ++ti) {
        const DrawnTile& t = f.tiles[ti];
        const geo::GroundTile& g = *t.ground;
        if (t.under || t.cam == nullptr || t.tile == nullptr || (g.lamps.empty() && g.open_spots.empty())) continue;
        // in its own frame: the eye's line of sight where it meets the ground, no farther than 400 m ahead of the eye
        const view::Vec3 eye = t.cam->eye();
        const view::Vec3 fwd = t.cam->forward();
        const double along = fwd.y < 0 ? std::min(eye.y / -fwd.y, 400.0) : 400.0;
        const double tx = eye.x + (fwd.x * along);
        const double tz = eye.z + (fwd.z * along);
        const std::array<double, 4> box = box_of(g);
        const double dx = std::max({box[0] - tx, 0.0, tx - box[2]});
        const double dz = std::max({box[1] - tz, 0.0, tz - box[3]});
        const double d = std::hypot(dx, dz);
        if (d <= light_far) near.push_back({.distance = d, .tile = ti});
    }
    std::ranges::stable_sort(near, {}, &Near::distance);
    for (const Near& n : near) {
        const DrawnTile& t = f.tiles[n.tile];
        const geo::GroundTile& g = *t.ground;
        auto found = std::ranges::find(l.entries, static_cast<const void*>(&g), [](const auto& e) { return e->ground; });
        if (found == l.entries.end()) {
            l.entries.push_back(std::make_unique<LightEntry>());
            found = l.entries.end() - 1;
            (*found)->ground = &g;
            (*found)->serial = next_serial();
        }
        LightEntry& e = **found;
        e.seen = l.frame;
        const std::array<double, 4> box = box_of(g);
        // what its map is of: its lamps while they are drawn, its places open at the moment, and the neighbours whose
        // buildings stand near enough its sides to shade it
        Key key;
        key.add(lamps_on ? g.lamps.size() : 0U);
        const std::vector<std::int8_t>* answers = nullptr;
        if (open_on && !g.open_spots.empty()) {
            answers = &l.hours.of(e.serial, g.open_hours, state.theme);
            for (std::size_t k = 0; k < g.open_spots.size(); ++k) {
                const std::uint32_t h = g.open_spots[k].hours;
                if (h < answers->size() && (*answers)[h] == 1) key.add(k + 1);
            }
        }
        const double about = static_cast<double>(light_margin) + 24.0;
        std::vector<const geo::GroundTile*> read{&g};
        for (const DrawnTile& o : f.tiles) {
            if (o.ground == &g || o.under || o.tile == nullptr || o.tile->frame != t.tile->frame) continue;
            const std::array<double, 4> ob = box_of(*o.ground);
            if (ob[0] > box[2] + about || ob[2] < box[0] - about || ob[1] > box[3] + about || ob[3] < box[1] - about)
                continue;
            if (o.ground->casters == nullptr || o.ground->casters->solids.size() == 0) continue;
            read.push_back(o.ground);
            key.add(std::bit_cast<std::uintptr_t>(o.ground));
        }
        if ((e.map == nullptr || e.key != key.value) && (e.asked == 0 || e.asked_key != key.value)) {
            LightJob job;
            job.x0 = std::floor(box[0]) - light_margin;
            job.z0 = std::floor(box[1]) - light_margin;
            job.blocks_x =
                static_cast<std::int32_t>(std::ceil((box[2] + light_margin - job.x0) / light_block));
            job.blocks_z =
                static_cast<std::int32_t>(std::ceil((box[3] + light_margin - job.z0) / light_block));
            if (lamps_on) {
                for (const geo::Lamp& lamp : g.lamps) {
                    job.emitters.push_back({.x = static_cast<float>(lamp.at.x - job.x0),
                                            .z = static_cast<float>(lamp.at.z - job.z0),
                                            .kind = fx::light_lamp});
                }
            }
            if (answers != nullptr) {
                for (const geo::OpenSpot& spot : g.open_spots) {
                    if (spot.hours >= answers->size() || (*answers)[spot.hours] != 1) continue;
                    job.emitters.push_back({.x = static_cast<float>(spot.at.x - job.x0),
                                            .z = static_cast<float>(spot.at.z - job.z0),
                                            .kind = fx::light_open});
                }
            }
            // the footprints that stand on the ground within the grid and what the cascades read about it
            const double gx1 = job.x0 + (static_cast<double>(job.blocks_x) * light_block) + 24.0;
            const double gz1 = job.z0 + (static_cast<double>(job.blocks_z) * light_block) + 24.0;
            for (const geo::GroundTile* from : read) {
                if (from->casters == nullptr) continue;
                const sun::TileSolids& solids = from->casters->solids;
                for (std::uint32_t s = 0; s < solids.size(); ++s) {
                    if (solids.min[s] > 2.0) continue; // a part that begins overhead shades no ground light
                    const std::span<const double> xs = solids.xs(s);
                    const std::span<const double> zs = solids.zs(s);
                    if (xs.size() < 3) continue;
                    const auto [x_lo, x_hi] = std::ranges::minmax(xs);
                    const auto [z_lo, z_hi] = std::ranges::minmax(zs);
                    if (x_hi < job.x0 - 24.0 || x_lo > gx1 || z_hi < job.z0 - 24.0 || z_lo > gz1) continue;
                    for (std::size_t i = 0; i < xs.size(); ++i) {
                        job.ring_points.push_back(static_cast<float>(xs[i] - job.x0));
                        job.ring_points.push_back(static_cast<float>(zs[i] - job.z0));
                    }
                    job.ring_first.push_back(static_cast<std::uint32_t>(job.ring_points.size() / 2));
                }
            }
            e.asked = ++l.tickets;
            e.asked_key = key.value;
            l.baker.submit(e.asked, std::move(job));
        }
        if (e.map == nullptr) continue;
        // room for its lit blocks, taken from maps no longer drawn where there is none
        const std::uint32_t lit = e.map->lit();
        if (e.slots.size() != lit) {
            if (l.free.size() < lit) {
                for (const std::unique_ptr<LightEntry>& other : l.entries) {
                    if (other->seen != l.frame && !other->slots.empty()) let_go(*other);
                }
            }
            if (l.free.size() < lit) continue; // none yet: its lamps keep their ellipses this frame
            e.slots.assign(l.free.end() - lit, l.free.end());
            l.free.resize(l.free.size() - lit);
            e.uploaded = false;
        }
        f.light_tiles.push_back({.tile = n.tile, .entry = &e});
        f.light_mapped[n.tile] = 1;
    }
    // the maps of tiles long gone
    std::erase_if(l.entries, [&](const std::unique_ptr<LightEntry>& e) {
        if (e->seen + light_kept_frames > l.frame || e->asked != 0) return false;
        let_go(*e);
        return true;
    });
}

void Ground::write_light(std::vector<std::uint32_t>& words, const frame::FrameState& state) {
    Words& w = *words_;
    GroundFrame& f = frame_;
    w.light = static_cast<std::uint32_t>(words.size());
    w.light_count = 0;
    if (f.light_tiles.empty() || light_ == nullptr || pipelines_ == nullptr) return;
    Pipelines& p = *pipelines_;
    if (!p.light_blocks) {
        const vk::DeviceSize bytes = vk::DeviceSize{light_slots} * light_slot_words * 4;
        auto made = p.device->memory().buffer(bytes, vk::BufferUsageFlagBits::eStorageBuffer, gpu::MemoryUse::upload);
        if (!made) {
            log::error("vk ground: the light maps' blocks ({} bytes): {}", bytes, made.error().text());
            return;
        }
        p.light_blocks = std::move(*made);
        p.light_bound = {};
        for (const std::unique_ptr<LightEntry>& e : light_->entries) e->uploaded = false;
    }
    // this frame slot's set 3 (its previous frame has finished)
    const std::uint32_t slot = state.slot & 1U;
    if (!p.light_bound[slot]) {
        gpu::SetWriter(*p.soft_sets[slot])
            .buffer(8, vk::DescriptorType::eStorageBuffer, p.light_blocks.handle())
            .apply(p.device->handle());
        p.light_bound[slot] = true;
    }
    // the blocks not in the buffer yet, each into its slot (no frame in flight reads a slot it was not given)
    bool wrote = false;
    for (const GroundFrame::LightDrawn& d : f.light_tiles) {
        if (d.entry->uploaded) continue;
        LightEntry& e = *d.entry;
        const std::span<std::byte> to = p.light_blocks.bytes();
        for (std::size_t b = 0; b < e.slots.size(); ++b) {
            std::memcpy(to.subspan(std::size_t{e.slots[b]} * light_slot_words * 4).data(),
                        &e.map->blocks[b * light_slot_words], std::size_t{light_slot_words} * 4);
        }
        e.uploaded = true;
        wrote = true;
    }
    if (wrote) p.light_blocks.flush();
    // the head, eight words (ground.slang's LIGHT_HEAD_WORDS): the count, the two pools' stops and a spare
    words.push_back(static_cast<std::uint32_t>(f.light_tiles.size()));
    const std::array<fx::LightPool, fx::light_kinds>& pools = fx::light_pools;
    words.push_back(folded(0xFFB45EU, pools[fx::light_lamp].foot));
    words.push_back(folded(0xFFA848U, pools[fx::light_lamp].mid));
    words.push_back(folded(0xFFA848U, 0.0F));
    words.push_back(folded(open_pool_colours[0], open_pool_opacities[0]));
    words.push_back(folded(open_pool_colours[1], open_pool_opacities[1]));
    words.push_back(folded(open_pool_colours[2], open_pool_opacities[2]));
    words.push_back(0U);
    const std::size_t tiles_at = words.size();
    words.resize(words.size() + (f.light_tiles.size() * light_tile_words), 0U);
    std::uint32_t drawn = 0;
    for (const GroundFrame::LightDrawn& d : f.light_tiles) {
        const DrawnTile& t = f.tiles[d.tile];
        const LightTile& map = *d.entry->map;
        // the grid's texel (u, v) is the frame's point (x0 + u, 0, z0 + v): its camera coordinates are k0 + u ku +
        // v kv, and the screen's CSS point of camera coordinates k is c + (k.x ex + k.y ey) / k.z
        const view::Camera& cam = *t.cam;
        const view::Vec3 k0 = cam.cam({map.x0, 0.0, map.z0});
        const view::Vec3 k1 = cam.cam({map.x0 + 1.0, 0.0, map.z0});
        const view::Vec3 k2 = cam.cam({map.x0, 0.0, map.z0 + 1.0});
        const view::Vec3 ku{k1.x - k0.x, k1.y - k0.y, k1.z - k0.z};
        const view::Vec3 kv{k2.x - k0.x, k2.y - k0.y, k2.z - k0.z};
        const view::Vec2 c = cam.pc({0.0, 0.0, 1.0});
        const view::Vec2 px = cam.pc({1.0, 0.0, 1.0});
        const view::Vec2 py = cam.pc({0.0, 1.0, 1.0});
        const view::Vec2 ex{px.x - c.x, px.y - c.y};
        const view::Vec2 ey{py.x - c.x, py.y - c.y};
        const auto row = [&](const view::Vec3& k) {
            return std::array<double, 3>{(c.x * k.z) + (ex.x * k.x) + (ey.x * k.y),
                                         (c.y * k.z) + (ex.y * k.x) + (ey.y * k.y), k.z};
        };
        const std::array<double, 3> mu = row(ku);
        const std::array<double, 3> mv = row(kv);
        const std::array<double, 3> m0 = row(k0);
        // (css · w, w) = M (u, v, 1), so (u, v, 1) / k.z = M⁻¹ (css, 1): its third number is over 0 ahead of the eye
        const auto h = inverse({mu[0], mv[0], m0[0], mu[1], mv[1], m0[1], mu[2], mv[2], m0[2]});
        if (!h) continue;
        const std::size_t at = tiles_at + (std::size_t{drawn} * light_tile_words);
        for (std::size_t i = 0; i < 9; ++i) words[at + i] = std::bit_cast<std::uint32_t>(static_cast<float>((*h)[i]));
        words[at + 9] = static_cast<std::uint32_t>(map.blocks_x);
        words[at + 10] = static_cast<std::uint32_t>(map.blocks_z);
        words[at + 11] = static_cast<std::uint32_t>(words.size());
        for (const std::uint16_t place : map.index)
            words.push_back(place == 0 ? 0U : d.entry->slots[place - 1U] + 1U);
        ++drawn;
    }
    words[w.light] = drawn;
    w.light_count = drawn;
}

gpu::Result<void> Ground::make_light_pipelines(const frame::PassContext& context, Pipelines& p) const {
    gpu::GraphicsDesc light = ground_desc(context, p, "ground light maps", "screen_vs", "light_fs");
    translucent(light);
    ATLAS_GPU_LET(made, frame::make_pipeline(context, light, frame::Overdraw::translucent));
    p.light = std::move(made);
    return {};
}

void Ground::record_light(const frame::Recording& r) {
    const Words& w = *words_;
    if (w.light_count == 0 || !*pipelines_->light || !pipelines_->light_blocks) return;
    const vk::raii::CommandBuffer& cmd = r.frame.cmd;
    cmd.bindPipeline(vk::PipelineBindPoint::eGraphics, *pipelines_->light);
    cmd.bindDescriptorSets(vk::PipelineBindPoint::eGraphics, *pipelines_->layout, 3, *pipelines_->soft_sets[w.slot],
                           {});
    layouts::ground_ground::PushConstants pc{};
    pc.at = {w.tiles, w.slots, w.theme, w.runs};
    pc.mode = {0U, slot(geo::group::pools, 0, geo::pools_sub::lamps), w.light, 0U};
    cmd.pushConstants<layouts::ground_ground::PushConstants>(*pipelines_->layout, frame::push_stages, 0, pc);
    cmd.draw(3, 1, 0, 0);
    r.counters.draw("ground light maps", 1);
}

} // namespace atlas::passes
