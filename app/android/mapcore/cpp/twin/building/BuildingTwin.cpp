// The buildings' twin (twin/building/BuildingTwin.hpp).
#include "twin/building/BuildingTwin.hpp"

#include "twin/building/BuildingShader.hpp"

#include <algorithm>
#include <array>
#include <cmath>
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

/// A colour of the frame block (sRGB 0 to 1 at 8-bit levels) as ARGB, opaque.
[[nodiscard]] std::uint32_t argb_of(const hlsl::float4& c) noexcept {
    const auto byte = [](float v) {
        return static_cast<std::uint32_t>(std::lround(std::clamp(v, 0.0F, 1.0F) * 255.0F));
    };
    return 0xFF00'0000U | (byte(c.x) << 16U) | (byte(c.y) << 8U) | byte(c.z);
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
    // the frame's strokes (its pack's style): the roofs' edge colour (draw_building's `edge`), by day and at night
    const shaders::FrameStrokes& strokes = block_.strokes;
    const std::uint32_t edge =
        argb_of((theme_flags & shaders::THEME_NIGHT) != 0U ? strokes.roof_night : strokes.roof_day);
    // an element's style: its fill, and its stroke in the port's attributes (a cell's same-colour edge, a roof's edge
    // on its slopes or its tops, a cabin roof's), joined round as the objects group joins them
    const auto style_of = [&](std::uint32_t flags, std::uint32_t argb) {
        Style style{.fill = Paint::colour(argb)};
        style.join = Join::round;
        style.stroke = Paint{};
        if ((flags & shaders::BUILDING_EDGE) != 0U) {
            style.stroke = Paint::colour(argb);
            style.stroke_width = strokes.widths.w;
        } else if ((flags & shaders::BUILDING_STROKE_FINE) != 0U) {
            style.stroke = Paint::colour(edge);
            style.stroke_width = strokes.widths.x;
        } else if ((flags & shaders::BUILDING_STROKE_WIDE) != 0U) {
            style.stroke = Paint::colour(edge);
            style.stroke_width = strokes.widths.y;
        } else if ((flags & shaders::BUILDING_STROKE_CABIN) != 0U) {
            style.stroke = Paint::colour(argb_of(strokes.cabin));
            style.stroke_width = strokes.widths.z;
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
                out_.at(s.slot, group_, item_of(b, p.item));
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
                    depths_->push_back({.shape = s, .edged = edged, .triangles = {}, .tile = tile_});
                    if (ring < b.rings.size()) depths_->back().triangles = triangles_;
                }
                ++elements_;
            }
        }
    };
    emit_list(List::edged, b.prims_at, b.edged_first);
    emit_list(List::cells, b.cells_at, b.cells_first);
    emit_list(List::plain, b.prims_at + (b.edged * shaders::BUILDING_PRIM_VECTORS), b.plain_first);
    windows(b, rows, words, place, cells, sun);
}

std::int32_t BuildingTwin::item_of(const passes::BuildingTile& b, std::uint32_t item) {
    if (items_[item] == none) {
        // an area by its id, as the likeness harness keys the port's owners (AnchorCompiler.osmOwner)
        const std::uint8_t owner = b.owners[item];
        items_[item] =
            out_.item({.owner = Owner::area, .id = b.ids[item], .kind = (owner & 1U) != 0 ? "part" : "building"});
    }
    return items_[item];
}

void BuildingTwin::windows(const passes::BuildingTile& b, std::span<const std::uint32_t> rows,
                           std::span<const std::byte> words, const shaders::TilePlace& place, std::uint64_t cells,
                           const SunTexels& sun) {
    if (b.window_walls == 0) return;
    const shaders::FrameCamera& cam = block_.camera;
    const std::uint32_t theme_flags = block_.theme.flags.x;
    const bool night = (theme_flags & shaders::THEME_NIGHT) != 0U;
    const bool weather = (theme_flags & shaders::THEME_WEATHER) != 0U;
    const shaders::FrameWindows& paints = block_.windows;
    const auto frame_width = static_cast<double>(shaders::building_window_frame_width(paints));
    const hlsl::float3 frame = shaders::building_window_frame(paints, theme_flags);
    const hlsl::float3 sill_colour = shaders::building_window_sill_colour(paints, theme_flags);
    const auto lit_at = [&](std::uint32_t sample) {
        return !night && sun.has && sample / 64 < sun.now.size() && ((sun.now[sample / 64] >> (sample % 64)) & 1U) != 0;
    };
    // the walls' reveals' colours at the frame's moment, as the pass's CPU writes them
    reveals_.assign(b.window_walls, 0U);
    passes::building_reveals(block_.theme, b.lights, b.window_faces, reveals_);
    const hlsl::float3 shop_frame = shaders::building_shop_frame(paints, theme_flags);
    const float shop_width = shaders::building_shop_frame_width(paints);
    // a building's plinths' path, then its cornices', at its slots after its walls, once its walls are done (they
    // follow each other in the list)
    constexpr std::uint32_t no_item = 0xFFFF'FFFFU;
    std::uint32_t facade_item = no_item;
    std::uint32_t facade_slot = 0;
    const auto flush_facades = [&]() {
        const auto emit = [&](std::vector<PartsDepth>& parts, std::uint32_t slot, hlsl::float3 colour) {
            if (parts.empty()) return;
            points_.clear();
            subpaths_.clear();
            for (const PartsDepth& pd : parts) {
                const shaders::BuildingWindowShape& sh = pd.shapes.front();
                for (std::uint32_t k = 0; k < sh.count; ++k) {
                    const hlsl::float2 v = shaders::building_window_corner(sh, k);
                    points_.push_back({.x = v.x, .y = v.y});
                }
            }
            std::size_t first = 0;
            for (const PartsDepth& pd : parts) {
                const std::uint32_t count = pd.shapes.front().count;
                subpaths_.push_back({.points = std::span<const Point>(points_).subspan(first, count), .closed = true});
                first += count;
            }
            Style style{};
            style.fill = Paint::colour(argb_of(colour));
            style.join = Join::round;
            out_.at(slot, group_, item_of(b, facade_item));
            out_.path(style, subpaths_);
            if (depths_ != nullptr) {
                ElementDepth d{.edged = true, .tile = tile_};
                for (PartsDepth& pd : parts) pd.slot = slot;
                d.parts = parts;
                depths_->push_back(std::move(d));
            }
            ++elements_;
            parts.clear();
        };
        if (facade_item != no_item) {
            emit(plinths_, facade_slot, shaders::building_plinth_colour(paints, theme_flags));
            emit(cornices_, facade_slot + 1, shaders::building_cornice_colour(paints, theme_flags));
        }
        plinths_.clear();
        cornices_.clear();
        facade_item = no_item;
    };
    runs_.clear();
    passes::building_runs(cells, b.windows_first, runs_);
    for (const passes::BuildingRun& run : runs_) {
        for (std::uint32_t i = run.first; i < run.end; ++i) {
            const shaders::BuildingWindowWall w = shaders::building_window_wall(word4(words, b.windows_at + i));
            const std::size_t at = b.prims_at + (std::size_t{w.wall} * shaders::BUILDING_PRIM_VECTORS);
            const shaders::BuildingPrim wall = shaders::building_prim(word4(words, at), word4(words, at + 1),
                                                                      word4(words, at + 2), word4(words, at + 3));
            const shaders::BuildingFace f = shaders::building_face(word4(words, b.faces_at + (wall.face * 2)),
                                                                   word4(words, b.faces_at + (wall.face * 2) + 1));
            if (wall.item >= rows.size()) continue;
            if (wall.item != facade_item) flush_facades();
            const std::uint32_t row = rows[wall.item];
            const std::uint32_t rank = row < ranks_.size() ? ranks_[row] : shaders::RANK_NONE;
            const bool windows_drawn = shaders::building_windows_drawn(theme_flags, place, w, f, rank);
            const bool facades = shaders::building_facades_drawn(theme_flags, place, f, rank);
            if (!windows_drawn && !facades) continue;
            const std::uint32_t detail = shaders::rank_detail(rank);
            const bool near_band = (detail & shaders::BUILDING_DETAIL_NEAR) != 0U;
            // the wall's first slot (its shop glazing's where it has one), then its windows'
            const std::uint32_t first_slot = shaders::rank_slot(rank) + (near_band ? w.near_slot : w.far_slot);
            const std::uint32_t window_slot =
                first_slot + ((w.flags & shaders::BUILDING_WINDOW_GLAZING) != 0U ? 1U : 0U);
            const hlsl::float4 wall_plane = shaders::building_window_plane(cam, place, f);
            if (facades) {
                // its shop glazing by day (its own path, after its cells), its plinth and cornice for its building's
                if ((w.flags & shaders::BUILDING_WINDOW_GLAZING) != 0U && !night) {
                    const shaders::BuildingWindowShape g = shaders::building_window_over(
                        shaders::building_window_cut(cam, place, shaders::building_glazing(wall, f.normal)),
                        shaders::BUILDING_GLAZING_AREA);
                    if (g.count != 0) {
                        points_.clear();
                        subpaths_.clear();
                        float top = 0.0F;
                        float bottom = 0.0F;
                        for (std::uint32_t k = 0; k < g.count; ++k) {
                            const hlsl::float2 v = shaders::building_window_corner(g, k);
                            top = k == 0 || v.y < top ? v.y : top;
                            bottom = k == 0 || v.y > bottom ? v.y : bottom;
                            points_.push_back({.x = v.x, .y = v.y});
                        }
                        subpaths_.push_back({.points = points_, .closed = true});
                        const shaders::BuildingGlass glass =
                            shaders::building_glass(paints, theme_flags, shaders::BUILDING_WINDOW_PLAIN, 0U, false);
                        Style style{};
                        style.fill =
                            glass.gradient ? Paint::gradient(gradients_.glass) : Paint::colour(argb_of(glass.top));
                        style.join = Join::round;
                        style.stroke = Paint::colour(argb_of(shop_frame));
                        style.stroke_width = shop_width;
                        out_.at(first_slot, group_, item_of(b, wall.item));
                        out_.path(style, subpaths_);
                        if (depths_ != nullptr) {
                            ElementDepth d{.edged = true, .tile = tile_};
                            d.windows.push_back({.windows = {g},
                                                 .slot = first_slot,
                                                 .plane = wall_plane,
                                                 .half = 0.5F * shop_width,
                                                 .frame = shop_frame,
                                                 .glass = glass,
                                                 .top = top,
                                                 .bottom = bottom});
                            depths_->push_back(std::move(d));
                        }
                        ++elements_;
                    }
                }
                const std::uint32_t slots =
                    wall.item < b.item_facades.size() ? b.item_facades[wall.item] : shaders::BUILDING_NO_FACADE;
                if (slots != shaders::BUILDING_NO_FACADE) {
                    facade_item = wall.item;
                    facade_slot = shaders::rank_slot(rank) + (near_band ? (slots & 0xFFFFU) : (slots >> 16U));
                    if ((w.flags & shaders::BUILDING_WINDOW_PLINTH) != 0U) {
                        const bool low = (detail & shaders::BUILDING_DETAIL_LOW_PLINTH) != 0U;
                        const shaders::BuildingWindowShape pl = shaders::building_window_on_plane(
                            cam, wall_plane,
                            shaders::building_window_over(shaders::building_window_cut_in(
                                                              cam, place, shaders::building_plinth(wall, f.normal, low),
                                                              shaders::BUILDING_FACADE_MARGIN),
                                                          shaders::BUILDING_FACADE_AREA));
                        if (pl.count != 0) {
                            plinths_.push_back({.shapes = {pl},
                                                .slot = 0,
                                                .plane = wall_plane,
                                                .lead = shaders::BUILDING_SILL_LEAD,
                                                .colour = shaders::building_plinth_colour(paints, theme_flags)});
                        }
                    }
                    if ((w.flags & shaders::BUILDING_WINDOW_CORNICE) != 0U &&
                        (detail & shaders::BUILDING_DETAIL_CORNICES) != 0U) {
                        const shaders::BuildingWindowShape co = shaders::building_window_on_plane(
                            cam, wall_plane,
                            shaders::building_window_over(
                                shaders::building_window_cut_in(cam, place, shaders::building_cornice(wall, f.normal),
                                                                shaders::BUILDING_FACADE_MARGIN),
                                shaders::BUILDING_FACADE_AREA));
                        if (co.count != 0) {
                            cornices_.push_back({.shapes = {co},
                                                 .slot = 0,
                                                 .plane = wall_plane,
                                                 .lead = shaders::BUILDING_SILL_LEAD,
                                                 .colour = shaders::building_cornice_colour(paints, theme_flags)});
                        }
                    }
                }
            }
            if (!windows_drawn) continue;
            const bool deep = shaders::building_windows_deep(theme_flags, rank);
            const bool sashed = shaders::building_windows_sashed(rank);
            const passes::WindowWidths widths = passes::window_widths(
                shaders::rank_detail(rank), weather, row < distances_.depth.size() ? distances_.depth[row] : 0.0,
                distances_.focal, frame_width);
            const float width = shaders::building_tenths(widths.frame);
            const float sash_width = shaders::building_tenths(widths.sash);
            const hlsl::float4 plane = wall_plane;
            // the wall's windows, each as the frame draws it (windows_cs's): by their sample's bit dark or lit, and
            // close up their reveals, sash bars and sills
            windows_[0].clear();
            windows_[1].clear();
            reveal_shapes_.clear();
            sill_shapes_.clear();
            sashes_.clear();
            for (std::uint32_t r = shaders::building_windows_first_row(theme_flags, w); r < w.levels; ++r) {
                for (std::uint32_t c = 0; c < w.cols; ++c) {
                    const shaders::BuildingWindowBox box = shaders::building_window_box(wall, w, r, c);
                    shaders::BuildingWindowShape sp = shaders::building_window_cut(
                        cam, place, shaders::building_window_quad(wall, f.normal, box, shaders::BUILDING_WINDOW_OUT));
                    float rec = shaders::BUILDING_WINDOW_OUT;
                    if (deep && sp.count != 0 && sp.area > shaders::BUILDING_DEEP_AREA) {
                        reveal_shapes_.push_back(shaders::building_window_on_plane(cam, plane, sp));
                        rec = shaders::BUILDING_GLASS_BACK;
                        sp = shaders::building_window_on_plane(
                            cam, plane,
                            shaders::building_window_cut(cam, place,
                                                         shaders::building_window_quad(wall, f.normal, box, rec)));
                        const shaders::BuildingWindowShape sill = shaders::building_window_on_plane(
                            cam, plane,
                            shaders::building_window_cut(cam, place,
                                                         shaders::building_window_sill(wall, f.normal, box)));
                        if (sill.count != 0) sill_shapes_.push_back(sill);
                    }
                    const shaders::BuildingWindowShape s =
                        shaders::building_window_over(sp, shaders::BUILDING_WINDOW_MIN_AREA);
                    if (s.count != 0) {
                        const bool lit = lit_at(sun.windows_from + w.first + (r * w.cols) + c);
                        windows_[lit ? 1 : 0].push_back(s);
                    }
                    if (sashed && sp.count != 0 && sp.area > shaders::BUILDING_SASH_AREA) {
                        const shaders::BuildingSash bars = shaders::building_sash_on_plane(
                            cam, plane,
                            shaders::building_sash(
                                cam, place,
                                shaders::building_window_quad(wall, f.normal, box, rec + shaders::BUILDING_SASH_OUT)));
                        if (bars.drawn) sashes_.push_back(bars);
                    }
                }
            }
            const std::uint32_t slot = window_slot;
            const std::int32_t item = item_of(b, wall.item);
            // a part filled in one colour: each shape a closed subpath (the reveals, the sills)
            const auto fill = [&](std::span<const shaders::BuildingWindowShape> shapes, std::uint32_t part,
                                  hlsl::float3 colour, float lead) {
                if (shapes.empty()) return;
                points_.clear();
                subpaths_.clear();
                for (const shaders::BuildingWindowShape& sh : shapes) {
                    for (std::uint32_t k = 0; k < sh.count; ++k) {
                        const hlsl::float2 v = shaders::building_window_corner(sh, k);
                        points_.push_back({.x = v.x, .y = v.y});
                    }
                }
                std::size_t first = 0;
                for (const shaders::BuildingWindowShape& sh : shapes) {
                    subpaths_.push_back(
                        {.points = std::span<const Point>(points_).subspan(first, sh.count), .closed = true});
                    first += sh.count;
                }
                Style style{};
                style.fill = Paint::colour(argb_of(colour));
                style.join = Join::round;
                out_.at(slot + part, group_, item);
                out_.path(style, subpaths_);
                if (depths_ != nullptr) {
                    ElementDepth d{.edged = true, .tile = tile_};
                    d.parts.push_back({.shapes = {shapes.begin(), shapes.end()},
                                       .slot = slot + part,
                                       .plane = plane,
                                       .lead = lead,
                                       .colour = colour});
                    depths_->push_back(std::move(d));
                }
                ++elements_;
            };
            fill(reveal_shapes_, shaders::BUILDING_WINDOW_REVEALS, shaders::building_bgr(reveals_[i]),
                 shaders::BUILDING_REVEAL_LEAD);
            for (const bool lit : {false, true}) {
                const std::vector<shaders::BuildingWindowShape>& drawn = windows_[lit ? 1 : 0];
                if (drawn.empty()) continue;
                const std::uint32_t part = lit ? shaders::BUILDING_WINDOW_LIT_SLOT : shaders::BUILDING_WINDOW_DARK;
                // one path, a closed subpath a window, and its box from top to bottom (the gradient's)
                points_.clear();
                subpaths_.clear();
                float top = 0.0F;
                float bottom = 0.0F;
                for (const shaders::BuildingWindowShape& s : drawn) {
                    for (std::uint32_t k = 0; k < s.count; ++k) {
                        const hlsl::float2 v = shaders::building_window_corner(s, k);
                        top = points_.empty() || v.y < top ? v.y : top;
                        bottom = points_.empty() || v.y > bottom ? v.y : bottom;
                        points_.push_back({.x = v.x, .y = v.y});
                    }
                }
                std::size_t first = 0;
                for (const shaders::BuildingWindowShape& s : drawn) {
                    subpaths_.push_back(
                        {.points = std::span<const Point>(points_).subspan(first, s.count), .closed = true});
                    first += s.count;
                }
                const shaders::BuildingGlass glass =
                    shaders::building_glass(paints, theme_flags, w.style, w.flags, lit);
                Style style{};
                if (glass.gradient) {
                    // the lit glass's gradient but where a Paris front's lit panes take the dark ones'
                    const bool lit_gradient = lit && (w.style != shaders::BUILDING_WINDOW_PARIS || night);
                    style.fill = Paint::gradient(lit_gradient ? gradients_.lit : gradients_.glass);
                } else {
                    style.fill = Paint::colour(argb_of(glass.top));
                }
                style.join = Join::round;
                style.stroke = Paint::colour(argb_of(frame));
                style.stroke_width = width;
                out_.at(slot + part, group_, item);
                out_.path(style, subpaths_);
                if (lit) ++sunlit_;
                if (depths_ != nullptr) {
                    ElementDepth d{.edged = true, .tile = tile_};
                    d.windows.push_back({.windows = drawn,
                                         .slot = slot + part,
                                         .plane = plane,
                                         .half = 0.5F * width,
                                         .frame = frame,
                                         .glass = glass,
                                         .top = top,
                                         .bottom = bottom});
                    depths_->push_back(std::move(d));
                }
                ++elements_;
            }
            if (!sashes_.empty()) {
                // two open subpaths a window, each a bar: M m1 L m2 M h1 L h2, stroked in the frames' colour
                points_.clear();
                subpaths_.clear();
                for (const shaders::BuildingSash& bars : sashes_) {
                    for (const hlsl::float2 v : {bars.m1, bars.m2, bars.h1, bars.h2})
                        points_.push_back({.x = v.x, .y = v.y});
                }
                for (std::size_t k = 0; k < points_.size(); k += 2) {
                    subpaths_.push_back({.points = std::span<const Point>(points_).subspan(k, 2), .closed = false});
                }
                Style style{};
                style.join = Join::round;
                style.stroke = Paint::colour(argb_of(frame));
                style.stroke_width = sash_width;
                out_.at(slot + shaders::BUILDING_WINDOW_SASHES, group_, item);
                out_.path(style, subpaths_);
                if (depths_ != nullptr) {
                    ElementDepth d{.edged = true, .tile = tile_};
                    PartsDepth bars{.shapes = {},
                                    .slot = slot + shaders::BUILDING_WINDOW_SASHES,
                                    .plane = plane,
                                    .lead = shaders::BUILDING_SASH_LEAD,
                                    .colour = frame};
                    for (const shaders::BuildingSash& sash : sashes_) {
                        for (std::uint32_t k = 0; k < 2; ++k) {
                            const shaders::BuildingWindowShape bar =
                                shaders::building_sash_bar(sash, k, sash_width / 2);
                            if (bar.count != 0) bars.shapes.push_back(bar);
                        }
                    }
                    d.parts.push_back(std::move(bars));
                    depths_->push_back(std::move(d));
                }
                ++elements_;
            }
            fill(sill_shapes_, shaders::BUILDING_WINDOW_SILLS, sill_colour, shaders::BUILDING_SILL_LEAD);
        }
        flush_facades();
    }
}

} // namespace atlas::twin
