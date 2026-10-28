// The buildings' records in the walls heap (twin/building/BuildingTile.hpp).
#include "twin/building/BuildingTile.hpp"

#include "base/assert.hpp"
#include "base/pymath/PyMath.hpp"
#include "twin/building/BuildingShader.hpp"

#include <algorithm>
#include <bit>
#include <cmath>
#include <cstdint>

namespace atlas::passes {

namespace {

using Words = std::array<std::uint32_t, 4>;

[[nodiscard]] std::uint32_t bits(float v) noexcept { return std::bit_cast<std::uint32_t>(v); }

[[nodiscard]] float f32(double v) noexcept { return static_cast<float>(v); }

static_assert(geometry::prim_flag::near == shaders::BUILDING_NEAR &&
                  geometry::prim_flag::far == shaders::BUILDING_FAR &&
                  geometry::prim_flag::weather == shaders::BUILDING_WEATHER &&
                  geometry::prim_flag::dry == shaders::BUILDING_DRY &&
                  geometry::prim_flag::edge == shaders::BUILDING_EDGE &&
                  geometry::prim_flag::triangle == shaders::BUILDING_TRIANGLE &&
                  geometry::prim_flag::polygon == shaders::BUILDING_POLYGON &&
                  geometry::prim_flag::stroke_fine == shaders::BUILDING_STROKE_FINE &&
                  geometry::prim_flag::stroke_wide == shaders::BUILDING_STROKE_WIDE &&
                  geometry::prim_flag::stroke_cabin == shaders::BUILDING_STROKE_CABIN &&
                  geometry::prim_flag::outline == shaders::BUILDING_OUTLINE,
              "geometry/Walls.hpp's prim_flag and building.slangh's flags");
static_assert(geometry::window_flag::shop_floor == shaders::BUILDING_WINDOW_SHOP_FLOOR &&
                  geometry::window_flag::warehouse == shaders::BUILDING_WINDOW_WAREHOUSE &&
                  geometry::window_flag::church == shaders::BUILDING_WINDOW_CHURCH &&
                  geometry::window_flag::plinth == shaders::BUILDING_WINDOW_PLINTH &&
                  geometry::window_flag::cornice == shaders::BUILDING_WINDOW_CORNICE &&
                  geometry::window_flag::glazing == shaders::BUILDING_WINDOW_GLAZING &&
                  geometry::no_facade == shaders::BUILDING_NO_FACADE &&
                  static_cast<std::uint32_t>(geometry::WindowStyle::plain) == shaders::BUILDING_WINDOW_PLAIN &&
                  static_cast<std::uint32_t>(geometry::WindowStyle::modern) == shaders::BUILDING_WINDOW_MODERN &&
                  static_cast<std::uint32_t>(geometry::WindowStyle::paris) == shaders::BUILDING_WINDOW_PARIS &&
                  static_cast<std::uint32_t>(geometry::WindowStyle::cabin) == shaders::BUILDING_WINDOW_CABIN &&
                  geometry::window_slots == shaders::BUILDING_WINDOW_SILLS + 1,
              "geometry/Walls.hpp's windows and windows.slangh's");

/// Two runs this close (primitives of cells not drawn between them) are drawn as one.
constexpr std::uint32_t run_gap = 512;

} // namespace

std::vector<std::array<std::uint32_t, 4>> heap_words(const geometry::TileBuildings& g, double origin_x, double origin_z,
                                                     BuildingTile& layout) {
    namespace gm = atlas::geometry;
    // The edged list without the near cells, which the frame cuts from their walls (stage T writes a wall's one cell,
    // then its cells), and the near cells' records: per tile cell, its edged primitives and its near cells.
    const std::size_t grid = !g.edged_first.empty() ? g.edged_first.size() - 1 : 0;
    std::vector<const gm::Prim*> edged;
    edged.reserve(g.edged.size());
    std::vector<std::uint16_t> wall_near; // per edged record: its near slot as the heap holds it
    std::vector<std::array<std::uint32_t, 2>> cells;
    // per primitive of stage T's edged list, its place in the heap's (the near cells leave it), for the windowed walls
    std::vector<std::uint32_t> heap_of(g.edged.size(), 0);
    layout.edged_first.assign(grid + 1, 0);
    layout.cells_first.assign(grid + 1, 0);
    layout.near_items_first.assign(grid + 1, 0);
    layout.near_items.clear();
    for (std::size_t c = 0; c < grid; ++c) {
        layout.edged_first[c] = static_cast<std::uint32_t>(edged.size());
        layout.cells_first[c] = static_cast<std::uint32_t>(cells.size());
        layout.near_items_first[c] = static_cast<std::uint32_t>(layout.near_items.size());
        std::size_t wall = SIZE_MAX; // the edged record of the last wall drawn only far
        for (std::uint32_t i = g.edged_first[c]; i < g.edged_first[c + 1]; ++i) {
            const gm::Prim& q = g.edged[i];
            const bool near_cell = q.kind == gm::PrimKind::cell && (q.flags & gm::prim_flag::near) != 0 &&
                                   (q.flags & gm::prim_flag::far) == 0;
            if (!near_cell) {
                if (q.kind == gm::PrimKind::cell && (q.flags & gm::prim_flag::near) == 0) wall = edged.size();
                heap_of[i] = static_cast<std::uint32_t>(edged.size());
                edged.push_back(&q);
                wall_near.push_back(q.near_slot);
                continue;
            }
            ATLAS_ASSERT(wall != SIZE_MAX, "a near cell before its wall");
            const gm::Prim& w = *edged[wall];
            ATLAS_ASSERT(w.item == q.item && w.face == q.face && q.sample == w.sample + 1 + q.part,
                         "a near cell that is not its wall's: its item, face and sample after the wall's");
            ATLAS_ASSERT(q.ncol >= 1 && q.ncol <= 0xFF && q.nrow >= 1 && q.nrow <= 0xFF && q.part < q.ncol * q.nrow,
                         "a wall's cells beyond the near cells' record");
            if (q.part == 0) wall_near[wall] = q.near_slot;
            ATLAS_ASSERT(q.near_slot == wall_near[wall] + q.part, "a wall's cells' slots not in a run");
            cells.push_back({static_cast<std::uint32_t>(wall),
                             std::uint32_t{q.part} | (std::uint32_t{q.ncol} << 16U) | (std::uint32_t{q.nrow} << 24U)});
            layout.near_items.push_back(static_cast<std::uint16_t>(q.item)); // below the 0x10000 items asserted
        }
        // the cell's items with near cells, once each
        const auto from = layout.near_items.begin() + layout.near_items_first[c];
        std::ranges::sort(from, layout.near_items.end());
        const auto repeated = std::ranges::unique(from, layout.near_items.end());
        layout.near_items.erase(repeated.begin(), repeated.end());
    }
    layout.edged_first[grid] = static_cast<std::uint32_t>(edged.size());
    layout.cells_first[grid] = static_cast<std::uint32_t>(cells.size());
    layout.near_items_first[grid] = static_cast<std::uint32_t>(layout.near_items.size());
    layout.plain_first = g.plain_first;
    layout.windows_first = g.window_walls_first;
    const auto e = static_cast<std::uint32_t>(edged.size());
    const auto p = static_cast<std::uint32_t>(g.plain.size());
    const auto n = static_cast<std::uint32_t>(cells.size());
    const auto ww = static_cast<std::uint32_t>(g.window_walls.size());
    const auto f = static_cast<std::uint32_t>(g.faces.size());
    const auto k = static_cast<std::uint32_t>(g.items.size());
    ATLAS_ASSERT(k <= 0x1'0000U, "a tile with more buildings than the primitives' 16 bits of item hold");
    ATLAS_ASSERT(e <= shaders::BUILDING_WINDOW_WALL_BITS, "a tile with more walls than a windowed wall's 24 bits hold");
    layout.edged = e;
    layout.plain = p;
    layout.cells = n;
    layout.window_walls = ww;
    layout.windows = g.windows;
    layout.faces = f;
    layout.items = k;
    layout.prims_at = 0;
    layout.cells_at = (e + p) * shaders::BUILDING_PRIM_VECTORS;
    layout.windows_at = layout.cells_at + ((n + 1) / 2);
    layout.faces_at = layout.windows_at + ww;
    layout.colours_at = layout.faces_at + (f * shaders::BUILDING_FACE_VECTORS);
    layout.reveals_at = layout.colours_at + f;                  // two slots of two words a face: f uint4
    layout.facades_at = layout.reveals_at + ((2 * ww) + 3) / 4; // two slots of a word a windowed wall
    layout.rows_at = layout.facades_at + ((k + 3) / 4);         // a word an item
    layout.vectors = layout.rows_at + ((k + 3) / 4);
    std::vector<Words> out(layout.vectors, Words{0, 0, 0, 0});
    const auto local = [&](const gm::V3& v) {
        return std::array<std::uint32_t, 3>{bits(f32(v.x - origin_x)), bits(f32(v.y)), bits(f32(v.z - origin_z))};
    };
    const auto put_prim = [&](std::uint32_t at, const gm::Prim& q, std::uint16_t near_slot) {
        const std::array<std::uint32_t, 4> w{q.face, q.sample, (q.item & 0xFFFFU) | (std::uint32_t{q.flags} << 16U),
                                             std::uint32_t{near_slot} | (std::uint32_t{q.far_slot} << 16U)};
        for (std::size_t c = 0; c < 4; ++c) {
            const auto xyz = local(q.p[c]);
            out[at + c] = {xyz[0], xyz[1], xyz[2], w[c]};
        }
    };
    for (std::uint32_t i = 0; i < e; ++i) put_prim(i * shaders::BUILDING_PRIM_VECTORS, *edged[i], wall_near[i]);
    for (std::uint32_t i = 0; i < p; ++i) {
        put_prim((e + i) * shaders::BUILDING_PRIM_VECTORS, g.plain[i], g.plain[i].near_slot);
    }
    for (std::uint32_t i = 0; i < n; ++i) {
        Words& w = out[layout.cells_at + (i / 2)];
        const std::size_t at = std::size_t{2} * (i % 2);
        w[at] = cells[i][0];
        w[at + 1] = cells[i][1];
    }
    // the windowed walls (windows.slangh's BuildingWindowWall), each naming its wall in the heap's edged list, and its
    // windows' place among the tile's by cell and its item (the CPU keeps them for the frame's jobs)
    layout.window_at.assign(std::size_t{ww} + 1, 0);
    layout.window_items.assign(ww, 0);
    layout.window_faces.assign(ww, 0);
    layout.window_flags.assign(ww, 0);
    layout.window_styles.assign(ww, gm::WindowStyle::plain);
    layout.window_churches.assign(ww, 0);
    layout.window_materials.assign(ww, {});
    for (std::uint32_t i = 0; i < ww; ++i) {
        const gm::WindowWall& w = g.window_walls[i];
        layout.window_at[i + 1] = layout.window_at[i] + (std::uint32_t{w.levels} * w.cols);
        layout.window_items[i] = static_cast<std::uint16_t>(g.edged[w.wall].item); // below the 0x10000 items asserted
        layout.window_faces[i] = g.edged[w.wall].face;
        layout.window_flags[i] = w.flags;
        layout.window_styles[i] = w.style;
        // a church's windows' counts in a byte each (the jobs' church word): a wall over 1.5 km or 380 m tall keeps 255
        constexpr std::uint32_t most = 0xFF;
        if (w.church_windows > 0) {
            layout.window_churches[i] = std::min<std::uint32_t>(w.church_windows, most) |
                                        (std::min<std::uint32_t>(w.church_transoms, most) << 16U);
        }
        if (w.material != gm::Material::none) {
            layout.window_materials[i] = {
                .kind = w.material, .levels = w.levels, .fh = w.fh, .elen = w.elen, .ribs = w.ribs};
        }
        out[layout.windows_at + i] = {
            heap_of[w.wall] | (static_cast<std::uint32_t>(w.style) << shaders::BUILDING_WINDOW_STYLE_SHIFT) |
                (std::uint32_t{w.flags} << shaders::BUILDING_WINDOW_FLAGS_SHIFT),
            w.first,
            std::uint32_t{w.levels} |
                (static_cast<std::uint32_t>(w.material) << shaders::BUILDING_WINDOW_MATERIAL_SHIFT) |
                (std::uint32_t{w.cols} << 16U),
            std::uint32_t{w.near_slot} | (std::uint32_t{w.far_slot} << 16U)};
    }
    // each item's plinth's and cornice's slots (the CPU keeps them for the twin)
    layout.item_facades = g.item_facades;
    layout.item_facades.resize(k, geometry::no_facade);
    for (std::uint32_t i = 0; i < k; ++i) out[layout.facades_at + (i / 4)][i % 4] = layout.item_facades[i];
    // the n-gons whole, for the twin: their rings in the triangles' floats
    layout.rings.clear();
    layout.ring_points.clear();
    for (const gm::Polygon& poly : g.polygons) {
        if (poly.count == 0) continue; // the frame draws nothing of it
        layout.rings.push_back({.first = static_cast<std::uint32_t>(layout.ring_points.size() / 3),
                                .points = static_cast<std::uint32_t>(poly.ring.size()),
                                .prim = poly.first,
                                .prims = poly.count});
        for (const gm::V3& v : poly.ring) {
            layout.ring_points.push_back(f32(v.x - origin_x));
            layout.ring_points.push_back(f32(v.y));
            layout.ring_points.push_back(f32(v.z - origin_z));
        }
    }
    // in their triangles' order (stage T sorts the primitives by cell, not the n-gons), as the twin looks them up
    std::ranges::sort(layout.rings, {}, &BuildingRing::prim);
    // each face's geometry for the vertex stage, its light inputs for the CPU's light
    std::vector<double> areas(f, 0.0);
    for (const gm::Polygon& poly : g.polygons) areas[poly.face] = poly.area;
    layout.lights.resize(f);
    for (std::uint32_t i = 0; i < f; ++i) {
        const gm::Face& face = g.faces[i];
        std::uint32_t flags = 0;
        if (face.lit == gm::Lit::day) flags |= shaders::BUILDING_FACE_DAY;
        if (!face.facing) flags |= shaders::BUILDING_FACE_BOTH;
        const auto t = local(face.test);
        out[layout.faces_at + (i * 2)] = {bits(f32(face.normal.x)), bits(f32(face.normal.y)), bits(f32(face.normal.z)),
                                          bits(f32(areas[i]))};
        out[layout.faces_at + (i * 2) + 1] = {t[0], t[1], t[2], flags};
        layout.lights[i] = {.normal = {face.normal.x, face.normal.y, face.normal.z}, .base = face.base, .flags = flags};
    }
    // the hoist beams, their ends and their gables' faces in the heap's floats
    layout.beams.clear();
    layout.beams.reserve(g.beams.size());
    const auto floats = [&](const gm::V3& v) {
        return std::array<float, 3>{f32(v.x - origin_x), f32(v.y), f32(v.z - origin_z)};
    };
    for (const gm::RoofBeam& beam : g.beams) {
        if (beam.face >= f) continue;
        const gm::Face& face = g.faces[beam.face];
        layout.beams.push_back({.item = beam.item,
                                .from = floats(beam.from),
                                .to = floats(beam.to),
                                .normal = {f32(face.normal.x), f32(face.normal.y), f32(face.normal.z)},
                                .test = floats(face.test),
                                .near_slot = beam.near_slot,
                                .far_slot = beam.far_slot});
    }
    // each face's item and whether it is a wall's, from the primitives that draw it
    layout.face_items.assign(f, 0);
    layout.face_walls.assign(f, 0);
    for (const auto* list : {&g.edged, &g.plain}) {
        for (const gm::Prim& q : *list) {
            if (q.face >= f) continue;
            layout.face_items[q.face] = static_cast<std::uint16_t>(q.item); // below the 0x10000 items asserted
            const bool wall = q.kind == gm::PrimKind::cell || q.kind == gm::PrimKind::skillion_wall ||
                              q.kind == gm::PrimKind::hull_wall || q.kind == gm::PrimKind::cabin_wall ||
                              q.kind == gm::PrimKind::gable;
            if (wall) layout.face_walls[q.face] = 1;
        }
    }
    return out;
}

void building_colours(const shaders::Theme& theme, std::span<const BuildingLight> lights,
                      std::span<std::uint32_t> out) {
    for (std::size_t i = 0; i < lights.size() && (2 * i) + 1 < out.size(); ++i) {
        const BuildingLight& l = lights[i];
        const shaders::double3 rgb{static_cast<double>((l.base >> 16U) & 0xFFU),
                                   static_cast<double>((l.base >> 8U) & 0xFFU), static_cast<double>(l.base & 0xFFU)};
        const shaders::double3 n{l.normal[0], l.normal[1], l.normal[2]};
        const hlsl::uint3 lit = shaders::light_bytes(theme, rgb, n, true);
        const hlsl::uint3 unlit = shaders::light_bytes(theme, rgb, n, false);
        out[2 * i] = lit.x | (lit.y << 8U) | (lit.z << 16U);
        out[(2 * i) + 1] = unlit.x | (unlit.y << 8U) | (unlit.z << 16U);
    }
}

void building_reveals(const shaders::Theme& theme, std::span<const BuildingLight> lights,
                      std::span<const std::uint32_t> faces, std::span<std::uint32_t> out) {
    for (std::size_t i = 0; i < faces.size() && i < out.size(); ++i) {
        if (faces[i] >= lights.size()) continue;
        const BuildingLight& l = lights[faces[i]];
        // amsterdam.py: tohex(light(th, mulc(base, 0.55), nrm, False))
        const shaders::double3 rgb{static_cast<double>((l.base >> 16U) & 0xFFU) * 0.55,
                                   static_cast<double>((l.base >> 8U) & 0xFFU) * 0.55,
                                   static_cast<double>(l.base & 0xFFU) * 0.55};
        const shaders::double3 n{l.normal[0], l.normal[1], l.normal[2]};
        const hlsl::uint3 c = shaders::light_bytes(theme, rgb, n, false);
        out[i] = c.x | (c.y << 8U) | (c.z << 16U);
    }
}

WindowWidths window_widths(std::uint32_t detail, bool weather, double depth, double focal, double frame_width) {
    // amsterdam.py: fw = max(0.7, 0.07 * cam.focal / max(d, 1)) if deep else 0.7, and the sash bars' max(0.7, 0.045 *
    // cam.focal / max(d, 1)) if deep else max(0.7, 9 / max(d, 1)), each printed '%.1f'
    const auto tenths = [](double v) { return static_cast<std::uint16_t>(std::llround(pymath::round(v, 1) * 10.0)); };
    const bool deep = weather && (detail & shaders::BUILDING_DETAIL_DEEP) != 0U;
    const double d = pymath::max(depth, 1.0);
    WindowWidths w{.frame = tenths(deep ? pymath::max(frame_width, 0.07 * focal / d) : frame_width), .sash = 0};
    if ((detail & shaders::BUILDING_DETAIL_SASH) != 0U) {
        w.sash = tenths(deep ? pymath::max(sash_least, 0.045 * focal / d) : pymath::max(sash_least, 9.0 / d));
    }
    return w;
}

MaterialChoice material_choice(const BuildingTile::MaterialWall& m, bool night, double depth, double focal) {
    // amsterdam.py's material_face: ppm = cam.focal / max(d, 1); lv = max(1, b['levels']); fh = (top - y0) / lv
    using M = geometry::Material;
    constexpr std::int64_t most = 0xF'FFFF; // n's 20 bits: a wall of 1,500 km
    const double ppm = focal / pymath::max(depth, 1.0);
    const std::uint32_t lv = std::max<std::uint32_t>(1, m.levels);
    MaterialChoice c;
    switch (m.kind) {
    case M::glass: {
        // its glass; its floors `for r in range(1, lv)` where fh * ppm > 2.4; its mullions: step = 1.5 if 1.5 * ppm >=
        // 3.2 else (3.0 if 3.0 * ppm >= 3.2 else None), n = int(elen / step), `for k in range(1, n)` where n > 1
        c.fills = 1;
        const std::uint32_t floors = m.fh * ppm > 2.4 ? lv - 1 : 0;
        const double step = 1.5 * ppm >= 3.2 ? 1.5 : (3.0 * ppm >= 3.2 ? 3.0 : 0.0);
        const std::int64_t n = step != 0.0 ? std::min(static_cast<std::int64_t>(m.elen / step), most) : 0;
        c.lines = floors + (n > 1 ? static_cast<std::uint32_t>(n - 1) : 0U);
        c.counts = floors | (static_cast<std::uint32_t>(n) << 12U);
        break;
    }
    case M::metal: {
        // by day: step = 1.0 if ppm >= 2.6 else (2.0 if ppm >= 1.4 else None), n = int(elen / step), `for k in
        // range(1, n)`, the path where any
        const double step = ppm >= 2.6 ? 1.0 : (ppm >= 1.4 ? 2.0 : 0.0);
        if (night || step == 0.0) break;
        const std::int64_t n = std::min(static_cast<std::int64_t>(m.elen / step), most);
        if (n <= 1) break;
        c.lines = static_cast<std::uint32_t>(n - 1);
        c.counts = static_cast<std::uint32_t>(n) << 12U;
        c.ribs = m.ribs;
        break;
    }
    case M::balconies: {
        // where fh * ppm > 2.2: rows = [2] + ([lv - 1] if lv >= 6 else []), stroke-width "%.2f" % max(0.6, 0.14 * ppm)
        if (!(m.fh * ppm > 2.2)) break;
        c.lines = lv >= 6 ? 2U : 1U;
        const auto width = static_cast<std::uint32_t>(
            std::min<std::int64_t>(std::llround(pymath::round(pymath::max(0.6, 0.14 * ppm), 2) * 100.0), most));
        c.counts = c.lines | (width << 12U);
        break;
    }
    case M::parking:
        // where fh * ppm > 2.0: a deck `for r in range(lv)`
        if (!(m.fh * ppm > 2.0)) break;
        c.fills = lv;
        c.counts = lv;
        break;
    case M::none: break;
    }
    return c;
}

std::uint32_t beam_width(double depth, double focal) {
    // amsterdam.py: stroke-width="%.2f" % max(0.5, 0.12 * cam.focal / d)
    const double width = pymath::max(0.5, 0.12 * focal / depth);
    return static_cast<std::uint32_t>(std::llround(pymath::round(width, 2) * 100.0));
}

std::uint16_t church_leading(double depth, double focal) {
    // closeup.py: s = cam.focal / max(d, 1); stroke-width="%.2f" % max(0.4, 0.05 * s)
    const double width = pymath::max(0.4, 0.05 * (focal / pymath::max(depth, 1.0)));
    return static_cast<std::uint16_t>(std::llround(pymath::round(width, 2) * 100.0));
}

void building_runs(std::uint64_t cells, std::span<const std::uint32_t> first, std::vector<BuildingRun>& out) {
    const std::size_t n = !first.empty() ? first.size() - 1 : 0;
    const std::size_t own = out.size(); // the runs before are another list's: never joined
    for (std::size_t c = 0; c < n && c < 64; ++c) {
        if (((cells >> c) & 1U) == 0) continue;
        const std::uint32_t a = first[c];
        const std::uint32_t z = first[c + 1];
        if (a == z) continue;
        if (out.size() > own && a <= out.back().end + run_gap && a >= out.back().first) {
            out.back().end = std::max(out.back().end, z);
        } else {
            out.push_back({.first = a, .end = z});
        }
    }
}

} // namespace atlas::passes
