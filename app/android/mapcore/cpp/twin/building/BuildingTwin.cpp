// The buildings' twin (twin/building/BuildingTwin.hpp).
#include "twin/building/BuildingTwin.hpp"

#include "twin/building/BuildingShader.hpp"

#include <algorithm>
#include <array>
#include <cstring>
#include <iterator>

namespace atlas::twin {

namespace {

using Words = std::array<std::uint32_t, 4>;

/// uint4 `i` of a range's words.
[[nodiscard]] hlsl::uint4 word4(std::span<const std::byte> bytes, std::size_t i) noexcept {
    Words w{};
    std::memcpy(w.data(), bytes.data() + (i * sizeof(Words)), sizeof(Words));
    hlsl::uint4 v = {w[0], w[1], w[2], w[3]};
    return v;
}

/// Texel `t`'s bits of one plane (samples 32t to 32t + 31, as BitTiles::texels writes them): half of a 64-bit word.
[[nodiscard]] std::uint32_t texel_word(std::span<const std::uint64_t> words, std::uint32_t t) noexcept {
    return t / 2 < words.size() ? static_cast<std::uint32_t>(words[t / 2] >> (32U * (t % 2))) : 0U;
}

/// The n-gon whose triangles begin at or before plain primitive `prim` (the rings are in their triangles' order), or
/// rings.size().
[[nodiscard]] std::size_t ring_of(std::span<const passes::BuildingRing> rings, std::uint32_t prim) noexcept {
    const auto it = std::ranges::upper_bound(rings, prim, {}, &passes::BuildingRing::prim);
    if (it == rings.begin()) return rings.size();
    const auto r = static_cast<std::size_t>(std::distance(rings.begin(), it) - 1);
    return prim < rings[r].prim + rings[r].prims ? r : rings.size();
}

/// A colour's argb from building_colour's 0-1 channels (whole levels / 255, so the rounding gives the levels back).
[[nodiscard]] std::uint32_t argb_of(hlsl::float3 c) noexcept {
    const auto level = [](float v) {
        const float l = (v * 255.0F) + 0.5F;
        return static_cast<std::uint32_t>(l < 0.0F ? 0.0F : (l > 255.0F ? 255.0F : l));
    };
    return 0xFF00'0000U | (level(c.x) << 16U) | (level(c.y) << 8U) | level(c.z);
}

/// An n-gon's ring as the frame draws its triangles' corners (the vertex stage's float maths: each point placed and
/// turned to the camera, the near plane's cut along each edge in the ring's order, the CSS point, snapped at rest),
/// into `out`: the polygon the port's cam.poly gives face().
void project_ring(const shaders::FrameCamera& cam, const shaders::TilePlace& place, std::span<const float> xyz,
                  std::vector<Point>& out) {
    out.clear();
    const std::size_t n = xyz.size() / 3;
    const float near_z = cam.frame.w;
    const bool rest = shaders::camera_rests(cam);
    const auto at = [&](std::size_t i) {
        const hlsl::float3 local = {xyz[3 * i], xyz[(3 * i) + 1], xyz[(3 * i) + 2]};
        return shaders::camera_of(cam, shaders::eye_relative(place, local));
    };
    const auto put = [&](hlsl::float3 k) {
        hlsl::float2 v = shaders::css_of(cam, k);
        if (rest) v = {shaders::snap_tenth(v.x), shaders::snap_tenth(v.y)};
        out.push_back({.x = v.x, .y = v.y});
    };
    for (std::size_t i = 0; i < n; ++i) {
        const hlsl::float3 a = at(i);
        const hlsl::float3 b = at((i + 1) % n);
        const bool ai = a.z >= near_z;
        const bool bi = b.z >= near_z;
        if (ai) put(a);
        if (ai != bi) {
            const float t = (near_z - a.z) / (b.z - a.z);
            const hlsl::float3 q = {a.x + ((b.x - a.x) * t), a.y + ((b.y - a.y) * t), near_z};
            put(q);
        }
    }
}

} // namespace

void BuildingTwin::tile(const passes::BuildingTile& b, std::span<const std::uint32_t> rows,
                        std::span<const std::byte> words, const shaders::TilePlace& place, std::uint64_t cells,
                        const SunTexels& sun) {
    const std::span<const std::uint32_t> table = ranks_;
    const shaders::FrameCamera& cam = block_.camera;
    const shaders::Theme& theme = block_.theme;
    const std::uint32_t theme_flags = theme.flags.x;
    items_.assign(b.items, none);
    ring_done_.assign(b.rings.size(), 0);
    // the roofs' edge colour (draw_building's `edge`), by day and at night
    const std::uint32_t edge = (theme_flags & shaders::THEME_NIGHT) != 0U ? 0xFF2E'3446U : 0xFF6F'695FU;
    // an element's style: its fill, and its stroke in the port's attributes (a cell's same-colour .3 px edge, a roof's
    // .25 or .45 px edge, a cabin roof's .4 px), joined round as the objects group joins them
    const auto style_of = [&](std::uint32_t flags, std::uint32_t argb) {
        Style style{.fill = Paint::colour(argb)};
        style.join = Join::round;
        style.stroke = Paint{};
        if ((flags & shaders::BUILDING_EDGE) != 0U) {
            style.stroke = Paint::colour(argb);
            style.stroke_width = 2.0F * shaders::BUILDING_EDGE_HALF;
        } else if ((flags & shaders::BUILDING_STROKE_FINE) != 0U) {
            style.stroke = Paint::colour(edge);
            style.stroke_width = 0.25F;
        } else if ((flags & shaders::BUILDING_STROKE_WIDE) != 0U) {
            style.stroke = Paint::colour(edge);
            style.stroke_width = 0.45F;
        } else if ((flags & shaders::BUILDING_STROKE_CABIN) != 0U) {
            style.stroke = Paint::colour(0xFF2A'2C30U);
            style.stroke_width = 0.4F;
        }
        return style;
    };
    // every face's light at the frame's moment, as the pass's CPU writes it
    colours_.resize(std::size_t{b.faces} * 2);
    passes::building_colours(theme, b.lights, colours_);
    const auto light_of = [&](std::uint32_t face, bool lit) {
        const std::size_t at = (std::size_t{face} * 2) + (lit ? 0 : 1);
        return at < colours_.size() ? colours_[at] : 0U;
    };
    const auto prim_at = [&](std::size_t at) {
        return shaders::building_prim(word4(words, at), word4(words, at + 1), word4(words, at + 2),
                                      word4(words, at + 3));
    };
    // a list of the tile's: its edged primitives, its near cells (cut from their walls), or its plain primitives
    enum class List : std::uint8_t { edged, cells, plain };
    const auto emit_list = [&](List list, std::uint32_t list_at, std::span<const std::uint32_t> list_first) {
        const bool edged = list != List::plain;
        runs_.clear();
        passes::building_runs(cells, list_first, runs_);
        for (const passes::BuildingRun& run : runs_) {
            for (std::uint32_t i = run.first; i < run.end; ++i) {
                shaders::BuildingPrim p{};
                if (list == List::cells) {
                    const hlsl::uint4 pair = word4(words, list_at + (i / 2));
                    const bool odd = (i & 1U) != 0U;
                    const std::uint32_t wall = odd ? pair.z : pair.x;
                    const std::uint32_t code = odd ? pair.w : pair.y;
                    p = shaders::building_cell_prim(
                        prim_at(b.prims_at + (std::size_t{wall} * shaders::BUILDING_PRIM_VECTORS)), code);
                } else {
                    p = prim_at(list_at + (std::size_t{i} * shaders::BUILDING_PRIM_VECTORS));
                }
                const shaders::BuildingFace f = shaders::building_face(word4(words, b.faces_at + (p.face * 2)),
                                                                       word4(words, b.faces_at + (p.face * 2) + 1));
                if (p.item >= rows.size()) continue;
                const std::uint32_t row = rows[p.item];
                const std::uint32_t rank = row < table.size() ? table[row] : shaders::RANK_NONE;
                shaders::BuildingShape s = shaders::building_shape(cam, theme_flags, place, p, f, rank);
                // an n-gon's triangles: the n-gon once, whole, as face() gets it, when the frame draws any of them
                std::size_t ring = b.rings.size();
                if (!edged && (p.flags & shaders::BUILDING_POLYGON) != 0U) {
                    ring = ring_of(b.rings, i);
                    if (ring >= b.rings.size() || ring_done_[ring] != 0) continue;
                    ring_done_[ring] = 1;
                    const passes::BuildingRing& r = b.rings[ring];
                    triangles_.clear();
                    for (std::uint32_t k = r.prim; k < r.prim + r.prims; ++k) {
                        const shaders::BuildingPrim t =
                            prim_at(list_at + (std::size_t{k} * shaders::BUILDING_PRIM_VECTORS));
                        const shaders::BuildingShape ts = shaders::building_shape(cam, theme_flags, place, t, f, rank);
                        if (ts.count == 0) continue;
                        if (s.count == 0) s = ts;
                        triangles_.push_back(ts);
                    }
                }
                if (s.count == 0) continue;
                // its colour: its face's light, lit by its sample's sun bit
                hlsl::uint2 texel = {0U, 0U};
                if (sun.has && p.sample != shaders::BUILDING_NO_SAMPLE) {
                    texel = {texel_word(sun.now, p.sample / 32), texel_word(sun.before, p.sample / 32)};
                }
                const float amount =
                    shaders::building_sun_amount(f.flags, p.sample, texel, sun.has ? 1U : 0U, sun.weight);
                if (amount > 0.0F && (f.flags & shaders::BUILDING_FACE_DAY) == 0U) ++sunlit_;
                const std::uint32_t argb =
                    argb_of(shaders::building_colour(light_of(p.face, true), light_of(p.face, false), amount));
                if (items_[p.item] == none) {
                    // an area by its id, as the likeness harness keys the port's owners (AnchorCompiler.osmOwner)
                    const std::uint8_t owner = b.owners[p.item];
                    items_[p.item] = out_.item(
                        {.owner = Owner::area, .id = b.ids[p.item], .kind = (owner & 1U) != 0 ? "part" : "building"});
                }
                out_.at(s.slot, group_, items_[p.item]);
                points_.clear();
                if (ring < b.rings.size()) {
                    const passes::BuildingRing& r = b.rings[ring];
                    project_ring(cam, place,
                                 std::span(b.ring_points).subspan(std::size_t{r.first} * 3, std::size_t{r.points} * 3),
                                 points_);
                } else {
                    for (std::uint32_t c = 0; c < s.count; ++c) {
                        const hlsl::float2 v = shaders::building_corner(s, c);
                        points_.push_back({.x = v.x, .y = v.y});
                    }
                }
                out_.path(style_of(p.flags, argb), points_, true);
                if (depths_ != nullptr) {
                    depths_->push_back({.shape = s, .edged = edged, .triangles = {}});
                    if (ring < b.rings.size()) depths_->back().triangles = triangles_;
                }
                ++elements_;
            }
        }
    };
    emit_list(List::edged, b.prims_at, b.edged_first);
    emit_list(List::cells, b.cells_at, b.cells_first);
    emit_list(List::plain, b.prims_at + (b.edged * shaders::BUILDING_PRIM_VECTORS), b.plain_first);
}

} // namespace atlas::twin
