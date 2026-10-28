// The buildings' twin (twin/building/BuildingTwin.hpp).
#include "twin/building/BuildingTwin.hpp"

#include "twin/building/BuildingShader.hpp"

#include <algorithm>
#include <array>
#include <bit>
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
    beams(b, rows, place);
    roof_lines(b, rows, place);
    kit_walls(b, rows, place);
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
    const hlsl::float3 cabin_frame = shaders::building_cabin_frame(paints, theme_flags);
    const float cabin_width = shaders::building_cabin_frame_width(paints);
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
            const bool materials = w.material != shaders::BUILDING_MATERIAL_NONE &&
                                   shaders::building_materials_drawn(theme_flags, place, f, rank);
            if (!windows_drawn && !facades && !materials) continue;
            const std::uint32_t detail = shaders::rank_detail(rank);
            const bool near_band = (detail & shaders::BUILDING_DETAIL_NEAR) != 0U;
            // the wall's first slot (its shop glazing's where it has one), then its material face's, then its windows'
            const std::uint32_t first_slot = shaders::rank_slot(rank) + (near_band ? w.near_slot : w.far_slot);
            const std::uint32_t material_slot =
                first_slot + ((w.flags & shaders::BUILDING_WINDOW_GLAZING) != 0U ? 1U : 0U);
            const std::uint32_t window_slot = material_slot + shaders::building_material_slots(w.material);
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
                                                 .bottom = bottom,
                                                 .shop = true});
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
            // its material face, with weather nearer than 1.35 times win_d: after its shop glazing, before its windows
            if (materials && i < b.window_materials.size()) {
                material_face(wall, f, place, wall_plane, w, b.window_materials[i], material_slot,
                              item_of(b, wall.item), row < distances_.depth.size() ? distances_.depth[row] : 0.0);
            }
            // a church's own windows, with weather nearer than 520 m (none of its plain ones then)
            const std::uint32_t church = i < b.window_churches.size() ? b.window_churches[i] : 0U;
            if (church != 0U && shaders::building_church_drawn(theme_flags, place, w, f, rank)) {
                church_windows(wall, f, place, wall_plane, church, window_slot, item_of(b, wall.item), reveals_[i],
                               row < distances_.depth.size() ? distances_.depth[row] : 0.0);
                continue;
            }
            if (!windows_drawn) continue;
            // a houseboat cabin's panes: one path at its slot, in its own frames
            const bool cabin = w.style == shaders::BUILDING_WINDOW_CABIN;
            const bool deep = !cabin && shaders::building_windows_deep(theme_flags, rank);
            const bool sashed = !cabin && shaders::building_windows_sashed(rank);
            const passes::WindowWidths widths = passes::window_widths(
                shaders::rank_detail(rank), weather, row < distances_.depth.size() ? distances_.depth[row] : 0.0,
                distances_.focal, frame_width);
            const float width = cabin ? cabin_width : shaders::building_tenths(widths.frame);
            const hlsl::float3 panes_frame = cabin ? cabin_frame : frame;
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
                    if (cabin) {
                        const shaders::BuildingWindowShape s =
                            shaders::building_cabin_window(cam, place, wall, f.normal, w, c);
                        if (s.count != 0) windows_[0].push_back(s);
                        continue;
                    }
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
                const std::uint32_t glass_slot =
                    cabin ? slot : slot + (lit ? shaders::BUILDING_WINDOW_LIT_SLOT : shaders::BUILDING_WINDOW_DARK);
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
                style.stroke = Paint::colour(argb_of(panes_frame));
                style.stroke_width = width;
                out_.at(glass_slot, group_, item);
                out_.path(style, subpaths_);
                if (lit) ++sunlit_;
                if (depths_ != nullptr) {
                    ElementDepth d{.edged = true, .tile = tile_};
                    d.windows.push_back({.windows = drawn,
                                         .slot = glass_slot,
                                         .plane = plane,
                                         .half = 0.5F * width,
                                         .frame = panes_frame,
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

void BuildingTwin::church_windows(const shaders::BuildingPrim& wall, const shaders::BuildingFace& f,
                                  const shaders::TilePlace& place, hlsl::float4 plane, std::uint32_t church,
                                  std::uint32_t slot, std::int32_t item, std::uint32_t reveal, double depth) {
    const shaders::FrameCamera& cam = block_.camera;
    const std::uint32_t theme_flags = block_.theme.flags.x;
    const bool night = (theme_flags & shaders::THEME_NIGHT) != 0U;
    const std::uint32_t bays = church & 0xFFFFU;
    const std::uint32_t transoms = church >> 16U;
    const shaders::BuildingChurchBay bay = shaders::building_church_bay(wall, bays);
    // each window as windows_cs decides it: its record's arches, and its leading's segments
    for (std::vector<shaders::BuildingArch>& a : arches_) a.clear();
    segments_.clear();
    for (std::uint32_t k = 0; k < bays; ++k) {
        const shaders::BuildingChurchRecord r =
            shaders::building_church_record(cam, place, wall, f.normal, plane, bay, k, transoms, 0, slot);
        if (r.v[0].x == 0U) continue;
        for (std::uint32_t part = 0; part < 3; ++part) {
            const shaders::BuildingArch a = shaders::building_church_arch(r, part);
            if (a.count != 0) arches_[part].push_back(a);
        }
        if (!shaders::building_church_leading_drawn(r)) continue;
        for (std::uint32_t j = 0; j <= transoms; ++j) {
            const shaders::BuildingSash segment = shaders::building_church_segment(cam, plane, r, j);
            if (segment.drawn) segments_.push_back(segment);
        }
    }
    // closeup.py: `if not glass: return []`
    if (arches_[2].empty()) return;
    float top = 0.0F;
    float bottom = 0.0F;
    for (std::size_t n = 0; n < arches_[2].size(); ++n) {
        for (std::uint32_t k = 0; k < arches_[2][n].count; ++k) {
            const float y = arches_[2][n].p[k].y;
            top = (n == 0 && k == 0) || y < top ? y : top;
            bottom = (n == 0 && k == 0) || y > bottom ? y : bottom;
        }
    }
    const shaders::BuildingGlass veiled = shaders::building_church_veiled(block_.windows, theme_flags);
    const shaders::BuildingGlass flat{.top = {}, .bottom = {}, .gradient = false};
    // the stones, the reveals, the glass and its veil (the glass's arches again): a closed subpath an arch, at the
    // windows' slot `at` on
    const auto arches = [&](std::uint32_t part, std::uint32_t at, const Style& style, float lead,
                            const shaders::BuildingGlass& paint, const Node& node) {
        const std::vector<shaders::BuildingArch>& drawn = arches_[part];
        if (drawn.empty()) return;
        points_.clear();
        subpaths_.clear();
        for (const shaders::BuildingArch& a : drawn) {
            for (std::uint32_t k = 0; k < a.count; ++k) points_.push_back({.x = a.p[k].x, .y = a.p[k].y});
        }
        std::size_t first = 0;
        for (const shaders::BuildingArch& a : drawn) {
            subpaths_.push_back({.points = std::span<const Point>(points_).subspan(first, a.count), .closed = true});
            first += a.count;
        }
        out_.at(slot + at, group_, item);
        out_.path(style, subpaths_, node);
        if (depths_ != nullptr) {
            ElementDepth d{.edged = true, .tile = tile_};
            if (node.opacity < 1.0F) {
                d.veil = true;
            } else {
                d.arches.push_back({.arches = drawn,
                                    .bars = {},
                                    .slot = slot + at,
                                    .plane = plane,
                                    .lead = lead,
                                    .paint = paint,
                                    .top = top,
                                    .bottom = bottom});
            }
            depths_->push_back(std::move(d));
        }
        ++elements_;
    };
    const auto filled = [](hlsl::float3 colour) {
        Style style{};
        style.fill = Paint::colour(argb_of(colour));
        style.join = Join::round;
        return style;
    };
    const auto flat_paint = [&](hlsl::float3 colour) {
        shaders::BuildingGlass g = flat;
        g.top = colour;
        g.bottom = colour;
        return g;
    };
    const hlsl::float3 stone = shaders::building_church_stone(theme_flags);
    const hlsl::float3 reveals = shaders::building_bgr(reveal);
    arches(0, 0, filled(stone), shaders::BUILDING_REVEAL_LEAD, flat_paint(stone), {});
    arches(1, 1, filled(reveals), shaders::BUILDING_GLASS_LEAD, flat_paint(reveals), {});
    Style glass{};
    glass.fill = night ? Paint::colour(argb_of(shaders::building_rgb(shaders::BUILDING_CHURCH_GLASS_NIGHT)))
                       : Paint::gradient(gradients_.glass);
    glass.join = Join::round;
    arches(2, 2, glass, shaders::BUILDING_WINDOW_LEAD, veiled, {});
    // by day the glass again in the veil, at opacity .42 (the frame folds it into the glass)
    if (!night) {
        arches(2, 3, filled(shaders::building_rgb(shaders::BUILDING_CHURCH_VEIL_RGB)), shaders::BUILDING_WINDOW_LEAD,
               veiled, {.opacity = shaders::BUILDING_CHURCH_VEIL});
    }
    if (segments_.empty()) return;
    // the leading: two points an open subpath, stroked butt-capped in its colour and width
    const auto width = static_cast<float>(passes::church_leading(depth, distances_.focal)) / 100.0F;
    points_.clear();
    subpaths_.clear();
    for (const shaders::BuildingSash& segment : segments_) {
        points_.push_back({.x = segment.m1.x, .y = segment.m1.y});
        points_.push_back({.x = segment.m2.x, .y = segment.m2.y});
    }
    for (std::size_t k = 0; k < points_.size(); k += 2) {
        subpaths_.push_back({.points = std::span<const Point>(points_).subspan(k, 2), .closed = false});
    }
    const hlsl::float3 leading = shaders::building_church_leading(theme_flags);
    Style style{};
    style.join = Join::round;
    style.stroke = Paint::colour(argb_of(leading));
    style.stroke_width = width;
    out_.at(slot + 4, group_, item);
    out_.path(style, subpaths_);
    if (depths_ != nullptr) {
        ElementDepth d{.edged = true, .tile = tile_};
        ArchesDepth bars{.arches = {},
                         .bars = {},
                         .slot = slot + 4,
                         .plane = plane,
                         .lead = shaders::BUILDING_SASH_LEAD,
                         .paint = flat_paint(leading),
                         .top = 0.0F,
                         .bottom = 0.0F};
        for (const shaders::BuildingSash& segment : segments_) {
            const shaders::BuildingWindowShape bar = shaders::building_sash_bar(segment, 0, width / 2);
            if (bar.count != 0) bars.bars.push_back(bar);
        }
        d.arches.push_back(std::move(bars));
        depths_->push_back(std::move(d));
    }
    ++elements_;
}

void BuildingTwin::material_face(const shaders::BuildingPrim& wall, const shaders::BuildingFace& f,
                                 const shaders::TilePlace& place, hlsl::float4 plane,
                                 const shaders::BuildingWindowWall& w, const passes::BuildingTile::MaterialWall& m,
                                 std::uint32_t slot, std::int32_t item, double depth) {
    const shaders::FrameCamera& cam = block_.camera;
    const std::uint32_t theme_flags = block_.theme.flags.x;
    const bool night = (theme_flags & shaders::THEME_NIGHT) != 0U;
    // as the jobs carry it (windows_cs's): its fills and lines, how many floors, decks or balconies, n or the width
    const passes::MaterialChoice c = passes::material_choice(m, night, depth, distances_.focal);
    const std::uint32_t count = c.counts & 0xFFFU;
    const std::uint32_t n = c.counts >> 12U;
    const bool glass = w.material == shaders::BUILDING_MATERIAL_GLASS;
    // the fills, each a path of its own: a glass wall's glass (the gradient `glass` by day), a parking garage's decks
    const shaders::BuildingMaterialPaint fill =
        shaders::building_material_fill(block_.windows, theme_flags, w.material);
    for (std::uint32_t k = 0; k < c.fills; ++k) {
        const shaders::BuildingPrim q = glass ? shaders::building_material_glass(wall, f.normal)
                                              : shaders::building_material_deck(wall, f.normal, w.levels, k);
        const shaders::BuildingWindowShape s = shaders::building_window_on_plane(
            cam, plane,
            shaders::building_window_over(shaders::building_window_cut(cam, place, q),
                                          glass ? shaders::BUILDING_MATERIAL_GLASS_AREA
                                                : shaders::BUILDING_MATERIAL_DECK_AREA));
        if (s.count == 0) continue;
        points_.clear();
        subpaths_.clear();
        float top = 0.0F;
        float bottom = 0.0F;
        for (std::uint32_t i = 0; i < s.count; ++i) {
            const hlsl::float2 v = shaders::building_window_corner(s, i);
            top = i == 0 || v.y < top ? v.y : top;
            bottom = i == 0 || v.y > bottom ? v.y : bottom;
            points_.push_back({.x = v.x, .y = v.y});
        }
        subpaths_.push_back({.points = points_, .closed = true});
        Style style{};
        style.fill = fill.paint.gradient ? Paint::gradient(gradients_.glass) : Paint::colour(argb_of(fill.paint.top));
        style.join = Join::round;
        out_.at(slot, group_, item);
        out_.path(style, subpaths_, {.opacity = fill.opacity});
        if (depths_ != nullptr) {
            ElementDepth d{.edged = true, .tile = tile_};
            d.materials.push_back({.shapes = {s},
                                   .lines = false,
                                   .slot = slot,
                                   .plane = plane,
                                   .lead = shaders::building_material_lead(w.material),
                                   .paint = fill.paint,
                                   .top = top,
                                   .bottom = fill.paint.gradient ? bottom : top,
                                   .opacity = fill.opacity});
            depths_->push_back(std::move(d));
        }
        ++elements_;
    }
    if (c.lines == 0) return;
    // the lines, one path (the port's even where none of them projects): a glass wall's floors and mullions, a metal
    // one's ribs, a stone front's balconies, an open subpath each, after a glass wall's fill
    const std::uint32_t floors = glass ? count : 0U;
    const std::uint32_t line_slot = slot + (glass ? 1U : 0U);
    const std::uint32_t style_word = w.material == shaders::BUILDING_MATERIAL_BALCONIES ? n : c.ribs;
    const float width = shaders::building_material_width(w.material, style_word);
    const shaders::BuildingMaterialPaint lines = shaders::building_material_lines(theme_flags, w.material, style_word);
    std::vector<shaders::BuildingWindowShape> bars;
    points_.clear();
    subpaths_.clear();
    for (std::uint32_t k = 0; k < c.lines; ++k) {
        const shaders::BuildingMaterialLine l = shaders::building_material_line(
            cam, place, wall, f.normal, shaders::building_material_ends(wall, w.material, w.levels, floors, n, k));
        if (!l.drawn) continue;
        points_.push_back({.x = l.p0.x, .y = l.p0.y});
        points_.push_back({.x = l.p1.x, .y = l.p1.y});
        const shaders::BuildingWindowShape bar =
            shaders::building_material_bar(shaders::building_material_line_on_plane(cam, plane, l), width / 2);
        if (bar.count != 0) bars.push_back(bar);
    }
    for (std::size_t k = 0; k < points_.size(); k += 2) {
        subpaths_.push_back({.points = std::span<const Point>(points_).subspan(k, 2), .closed = false});
    }
    Style style{};
    style.join = Join::round;
    style.stroke = Paint::colour(argb_of(lines.paint.top));
    style.stroke_width = width;
    out_.at(line_slot, group_, item);
    out_.path(style, subpaths_, {.opacity = lines.opacity});
    if (depths_ != nullptr) {
        ElementDepth d{.edged = true, .tile = tile_};
        d.materials.push_back({.shapes = std::move(bars),
                               .lines = true,
                               .slot = line_slot,
                               .plane = plane,
                               .lead = shaders::building_material_lead(w.material),
                               .paint = lines.paint,
                               .top = 0.0F,
                               .bottom = 0.0F,
                               .opacity = lines.opacity});
        depths_->push_back(std::move(d));
    }
    ++elements_;
}

void BuildingTwin::beams(const passes::BuildingTile& b, std::span<const std::uint32_t> rows,
                         const shaders::TilePlace& place) {
    const shaders::FrameCamera& cam = block_.camera;
    const std::uint32_t theme_flags = block_.theme.flags.x;
    if ((theme_flags & shaders::THEME_WEATHER) == 0U) return;
    const auto bits = [](float v) { return std::bit_cast<std::uint32_t>(v); };
    const auto offset = std::bit_cast<hlsl::uint4>(place.offset);
    const auto scale = std::bit_cast<hlsl::uint4>(place.scale);
    for (const passes::BuildingTile::Beam& beam : b.beams) {
        // as the frame's records give it (Buildings::write_window_jobs's): its item nearer than 260 m, its slot by its
        // item's band, its width as printed
        if (beam.item >= rows.size()) continue;
        const std::uint32_t row = rows[beam.item];
        const std::uint32_t rank = row < ranks_.size() ? ranks_[row] : shaders::RANK_NONE;
        if (rank == shaders::RANK_NONE) continue;
        const std::uint32_t detail = shaders::rank_detail(rank);
        if ((detail & shaders::BUILDING_DETAIL_BEAMS) == 0U) continue;
        const bool near_band = (detail & shaders::BUILDING_DETAIL_NEAR) != 0U;
        const std::uint32_t slot = shaders::rank_slot(rank) + (near_band ? beam.near_slot : beam.far_slot);
        const std::uint32_t width =
            passes::beam_width(row < distances_.depth.size() ? distances_.depth[row] : 0.0, distances_.focal);
        const hlsl::uint4 from = {bits(beam.from[0]), bits(beam.from[1]), bits(beam.from[2]), slot};
        const hlsl::uint4 to = {bits(beam.to[0]), bits(beam.to[1]), bits(beam.to[2]), width};
        const hlsl::uint4 normal = {bits(beam.normal[0]), bits(beam.normal[1]), bits(beam.normal[2]), 0U};
        const hlsl::uint4 test = {bits(beam.test[0]), bits(beam.test[1]), bits(beam.test[2]), 0U};
        const shaders::BuildingBeam bb = shaders::building_beam(cam, from, to, normal, test, offset, scale);
        if (!bb.drawn) continue;
        // `<path d="M%.1f %.1fL%.1f %.1f" stroke="#2A221C" stroke-width="%.2f" stroke-linecap="round"/>`: its fill
        // SVG's black, which a line leaves unpainted; the buildings' group's round joins
        points_.clear();
        subpaths_.clear();
        points_.push_back({.x = bb.p0.x, .y = bb.p0.y});
        points_.push_back({.x = bb.p1.x, .y = bb.p1.y});
        subpaths_.push_back({.points = points_, .closed = false});
        Style style{};
        style.join = Join::round;
        style.fill = Paint::colour(0xFF00'0000U);
        style.stroke = Paint::colour(argb_of(shaders::building_rgb(shaders::BUILDING_BEAM_RGB)));
        style.stroke_width = static_cast<float>(width) / 100.0F;
        style.cap = Cap::round;
        out_.at(slot, group_, item_of(b, beam.item));
        out_.path(style, subpaths_);
        if (depths_ != nullptr) {
            ElementDepth d{.edged = true, .tile = tile_};
            d.beams.push_back(bb);
            depths_->push_back(std::move(d));
        }
        ++elements_;
    }
}

void BuildingTwin::roof_lines(const passes::BuildingTile& b, std::span<const std::uint32_t> rows,
                              const shaders::TilePlace& place) {
    const shaders::FrameCamera& cam = block_.camera;
    const std::uint32_t theme_flags = block_.theme.flags.x;
    if ((theme_flags & shaders::THEME_WEATHER) == 0U || (theme_flags & shaders::THEME_NIGHT) != 0U) return;
    const auto bits = [](float v) { return std::bit_cast<std::uint32_t>(v); };
    const auto offset = std::bit_cast<hlsl::uint4>(place.offset);
    const auto scale = std::bit_cast<hlsl::uint4>(place.scale);
    // as the frame's records give them (Buildings::write_window_jobs's): its item drawn, its slot by its item's band
    const auto placed = [&](std::uint32_t item, std::uint16_t near_slot,
                            std::uint16_t far_slot) -> std::optional<std::pair<std::uint32_t, double>> {
        if (item >= rows.size()) return std::nullopt;
        const std::uint32_t row = rows[item];
        const std::uint32_t rank = row < ranks_.size() ? ranks_[row] : shaders::RANK_NONE;
        if (rank == shaders::RANK_NONE) return std::nullopt;
        const bool near = (shaders::rank_detail(rank) & shaders::BUILDING_DETAIL_NEAR) != 0U;
        return std::pair{shaders::rank_slot(rank) + (near ? near_slot : far_slot),
                         row < distances_.depth.size() ? distances_.depth[row] : 0.0};
    };
    const auto emit = [&](std::uint32_t item, std::uint32_t slot, const std::vector<shaders::BuildingBeam>& lines,
                          bool round, std::uint32_t rgb, float opacity, std::uint32_t width) {
        points_.clear();
        subpaths_.clear();
        for (const shaders::BuildingBeam& l : lines) {
            points_.push_back({.x = l.p0.x, .y = l.p0.y});
            points_.push_back({.x = l.p1.x, .y = l.p1.y});
        }
        for (std::size_t k = 0; k < points_.size(); k += 2) {
            subpaths_.push_back({.points = std::span<const Point>(points_).subspan(k, 2), .closed = false});
        }
        Style style{};
        style.join = Join::round;
        style.stroke = Paint::colour(argb_of(shaders::building_rgb(rgb)));
        style.stroke_width = static_cast<float>(width) / 100.0F;
        if (round) {
            // the ridge: SVG's black fill, which a line leaves unpainted; round caps
            style.fill = Paint::colour(0xFF00'0000U);
            style.cap = Cap::round;
        }
        out_.at(slot, group_, item_of(b, item));
        out_.path(style, subpaths_, {.opacity = opacity});
        if (depths_ != nullptr) {
            ElementDepth d{.edged = true, .tile = tile_};
            d.roof_lines.push_back({.lines = lines, .round = round, .rgb = rgb, .opacity = opacity});
            depths_->push_back(std::move(d));
        }
        ++elements_;
    };
    std::vector<shaders::BuildingBeam> lines;
    // each slope's tile courses: `<path d="..." stroke="..." stroke-width="%.2f" opacity=".3" fill="none"/>`, the
    // lines whose both ends project
    for (const passes::BuildingTile::Courses& c : b.courses) {
        const auto at = placed(c.item, c.near_slot, c.far_slot);
        if (!at || !passes::courses_shown(at->second, distances_.focal, c.rstep)) continue;
        const std::uint32_t width = passes::courses_width(at->second, distances_.focal);
        const std::uint32_t flags = 30U << shaders::BUILDING_ROOF_LINE_OPACITY_SHIFT;
        const hlsl::uint4 normal = {bits(c.normal[0]), bits(c.normal[1]), bits(c.normal[2]), flags};
        const hlsl::uint4 test = {bits(c.test[0]), bits(c.test[1]), bits(c.test[2]), c.rgb};
        lines.clear();
        float opacity = 0.0F;
        for (std::uint32_t j = 1; j < c.k; ++j) {
            const auto [a, e] = passes::course_ends(c, j);
            const hlsl::uint4 from = {bits(a[0]), bits(a[1]), bits(a[2]), at->first};
            const hlsl::uint4 to = {bits(e[0]), bits(e[1]), bits(e[2]), width};
            const shaders::BuildingRoofLine r = shaders::building_roof_line(cam, from, to, normal, test, offset, scale);
            opacity = r.opacity;
            if (r.line.drawn) lines.push_back(r.line);
        }
        if (!lines.empty()) emit(c.item, at->first, lines, false, c.rgb, opacity, width);
    }
    // the lit ridge: `<path d="M..L.." stroke="#F4EEE2" stroke-width="%.2f" opacity=".55" stroke-linecap="round"/>`
    for (const passes::BuildingTile::Ridge& r : b.ridges) {
        const auto at = placed(r.item, r.near_slot, r.far_slot);
        if (!at) continue;
        const std::uint32_t width = passes::ridge_width(at->second, distances_.focal);
        const std::uint32_t flags = shaders::BUILDING_ROOF_LINE_UNTURNED | shaders::BUILDING_ROOF_LINE_ROUND |
                                    (55U << shaders::BUILDING_ROOF_LINE_OPACITY_SHIFT);
        const hlsl::uint4 from = {bits(r.from[0]), bits(r.from[1]), bits(r.from[2]), at->first};
        const hlsl::uint4 to = {bits(r.to[0]), bits(r.to[1]), bits(r.to[2]), width};
        const hlsl::uint4 normal = {0U, 0U, 0U, flags};
        const hlsl::uint4 test = {0U, 0U, 0U, shaders::BUILDING_ROOF_RIDGE_RGB};
        const shaders::BuildingRoofLine rl = shaders::building_roof_line(cam, from, to, normal, test, offset, scale);
        if (!rl.line.drawn) continue;
        lines.assign(1, rl.line);
        emit(r.item, at->first, lines, true, shaders::BUILDING_ROOF_RIDGE_RGB, rl.opacity, width);
    }
}

void BuildingTwin::kit_walls(const passes::BuildingTile& b, std::span<const std::uint32_t> rows,
                             const shaders::TilePlace& place) {
    const shaders::FrameCamera& cam = block_.camera;
    const std::uint32_t theme_flags = block_.theme.flags.x;
    if ((theme_flags & shaders::THEME_WEATHER) == 0U || (theme_flags & shaders::THEME_NIGHT) != 0U) return;
    const auto bits = [](float v) { return std::bit_cast<std::uint32_t>(v); };
    const auto offset = std::bit_cast<hlsl::uint4>(place.offset);
    const auto scale = std::bit_cast<hlsl::uint4>(place.scale);
    // as the frame's records give them (Buildings::write_window_jobs's): its item drawn, its slot by its item's band
    const auto placed = [&](std::uint32_t item, std::uint16_t near_slot,
                            std::uint16_t far_slot) -> std::optional<std::pair<std::uint32_t, double>> {
        if (item >= rows.size()) return std::nullopt;
        const std::uint32_t row = rows[item];
        const std::uint32_t rank = row < ranks_.size() ? ranks_[row] : shaders::RANK_NONE;
        if (rank == shaders::RANK_NONE) return std::nullopt;
        const bool near = (shaders::rank_detail(rank) & shaders::BUILDING_DETAIL_NEAR) != 0U;
        return std::pair{shaders::rank_slot(rank) + (near ? near_slot : far_slot),
                         row < distances_.depth.size() ? distances_.depth[row] : 0.0};
    };
    std::vector<shaders::BuildingRoofLine> lines;
    for (const passes::BuildingTile::KitWall& k : b.kit_walls) {
        const hlsl::uint4 test = {bits(k.test[0]), bits(k.test[1]), bits(k.test[2]), k.rgb};
        // its boards' lines: `<path d="..." stroke="..." stroke-width=".45" opacity=".32" fill="none"/>`, the lines
        // whose both ends project
        const auto lines_at = k.boards >= 2 ? placed(k.item, k.lines_near, k.lines_far) : std::nullopt;
        if (lines_at && passes::kit_lines_shown(lines_at->second, distances_.focal, k.spacing)) {
            const std::uint32_t flags =
                shaders::BUILDING_ROOF_LINE_ON_WALL | (32U << shaders::BUILDING_ROOF_LINE_OPACITY_SHIFT);
            const hlsl::uint4 normal = {bits(k.normal[0]), bits(k.normal[1]), bits(k.normal[2]), flags};
            lines.clear();
            float opacity = 0.0F;
            for (std::uint32_t j = 1; j < k.boards; ++j) {
                const auto [a, e] = passes::kit_line_ends(k, j);
                const hlsl::uint4 from = {bits(a[0]), bits(a[1]), bits(a[2]), lines_at->first};
                const hlsl::uint4 to = {bits(e[0]), bits(e[1]), bits(e[2]), passes::kit_lines_width};
                const shaders::BuildingRoofLine r =
                    shaders::building_roof_line(cam, from, to, normal, test, offset, scale);
                opacity = r.opacity;
                if (r.line.drawn) lines.push_back(r);
            }
            if (!lines.empty()) {
                points_.clear();
                subpaths_.clear();
                for (const shaders::BuildingRoofLine& l : lines) {
                    points_.push_back({.x = l.line.p0.x, .y = l.line.p0.y});
                    points_.push_back({.x = l.line.p1.x, .y = l.line.p1.y});
                }
                for (std::size_t q = 0; q < points_.size(); q += 2) {
                    subpaths_.push_back({.points = std::span<const Point>(points_).subspan(q, 2), .closed = false});
                }
                Style style{};
                style.join = Join::round;
                style.stroke = Paint::colour(argb_of(shaders::building_rgb(k.rgb)));
                style.stroke_width = static_cast<float>(passes::kit_lines_width) / 100.0F;
                out_.at(lines_at->first, group_, item_of(b, k.item));
                out_.path(style, subpaths_, {.opacity = opacity});
                if (depths_ != nullptr) {
                    ElementDepth d{.edged = true, .tile = tile_};
                    d.kit_walls.push_back({.lines = lines,
                                           .shadow = {},
                                           .plane = lines.front().plane,
                                           .slot = lines_at->first,
                                           .rgb = k.rgb,
                                           .opacity = opacity});
                    depths_->push_back(std::move(d));
                }
                ++elements_;
            }
        }
        // its eaves' shadow: `<path d="..." fill="#141822" opacity=".26"/>`, as face() keeps it
        const auto shadow_at = k.shadow ? placed(k.item, k.shadow_near, k.shadow_far) : std::nullopt;
        if (shadow_at) {
            const std::array<std::array<float, 3>, 4>& q = k.shade;
            const hlsl::uint4 foot = {bits(q[0][0]), bits(q[0][2]), bits(q[1][0]), bits(q[1][2])};
            const hlsl::uint4 heights = {bits(q[0][1]), bits(q[2][1]), shadow_at->first,
                                         shaders::BUILDING_KIT_SHADOW_RGB};
            const hlsl::uint4 normal = {bits(k.normal[0]), bits(k.normal[1]), bits(k.normal[2]),
                                        shaders::BUILDING_KIT_SHADOW_OPACITY};
            const hlsl::uint4 on = {test.x, test.y, test.z, 0U};
            const shaders::BuildingKitShadow sh =
                shaders::building_kit_shadow(cam, foot, heights, normal, on, offset, scale);
            if (sh.shape.count != 0) {
                points_.clear();
                for (std::uint32_t c = 0; c < sh.shape.count; ++c) {
                    const hlsl::float2 v = shaders::building_window_corner(sh.shape, c);
                    points_.push_back({.x = v.x, .y = v.y});
                }
                Style style{};
                style.fill = Paint::colour(argb_of(shaders::building_rgb(sh.rgb)));
                style.join = Join::round;
                out_.at(shadow_at->first, group_, item_of(b, k.item));
                out_.path(style, points_, true, {.opacity = sh.opacity});
                if (depths_ != nullptr) {
                    ElementDepth d{.edged = true, .tile = tile_};
                    d.kit_walls.push_back({.lines = {},
                                           .shadow = {sh.shape},
                                           .plane = sh.plane,
                                           .slot = shadow_at->first,
                                           .rgb = sh.rgb,
                                           .opacity = sh.opacity});
                    depths_->push_back(std::move(d));
                }
                ++elements_;
            }
        }
    }
}

} // namespace atlas::twin
