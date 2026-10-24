// The buildings' scene pipelines (plan-vector D10, K10; CP/passes/Walls.cpp): walls_vs draws a wall (as one cell, or a
// skillion's or a houseboat's wall), or a near cell cut from its wall's record, as its outline dilated by the
// same-colour 0.3 px edge, 16 points through the static fan of its index buffer; roofs_vs draws a roof's face or an
// n-gon's triangle as its polygon, 5 points through a fan of 3 triangles (the corners a near plane's cut may leave,
// repeated where there are fewer). Both read their primitive, face and colours from the walls heap (set 1, binding 1;
// the colours the CPU's light, per tile and moment) and their building's rank entry through the tile's rows (set 0,
// binding 1), decide it as shaders/building/building.slangh does, and write its true depth (an outline's points its
// face plane's, a fan's points their corners'), or its painter depth under the painter switch; building_fs fills it
// with its face's light. Opaque: never discards, its pipelines write the depth and the objects bit.
//
// The roofs' thin edges (each face's .25, .45 or .4 px stroke, drawn as the objects' thin strokes: fx/strokes.slang,
// through passes/ObjectStrokes.hpp's pipelines): before the main pass roof_strokes_cs decides each plain primitive the
// frame draws once, a thread each, from the frame's jobs (set 2: a tile's push constants and a run of its plain list,
// at most 64), into a record of its shape (building.slangh's BuildingStrokeRecord, at the job's slots, so the records
// keep the jobs' order); roof_strokes_vs then draws a segment along each edge its stroke follows from the record, at
// its face's own depth carried past the edge and a little nearer (building_stroke_recip, building_stroke_depth).

#include "shared/frame.slang"
#include "shared/painter.slang"

// K17a's mat: the walls and roofs are in the renders' mat group (the objects' `<g filter="url(#mat)">` where the moment
// has weather), where mat_on says.
#define ATLAS_FX_FUNCTIONS
#include "fx/mat.slang"

/// building.slangh's trigonometry: the GPU's own (the outline's arcs are not exact code).
inline float building_cos(float a) {
    return cos(a);
}
inline float building_sin(float a) {
    return sin(a);
}
inline float building_atan2(float y, float x) {
    return atan2(y, x);
}

#include "building/building.slangh"
#include "building/windows.slangh"
#include "fx/strokes.slang"

[[vk::binding(0, 0)]] ConstantBuffer<FrameBlock> frame;
[[vk::binding(1, 0)]] StructuredBuffer<uint> rank_table;
[[vk::binding(1, 1)]] StructuredBuffer<uint4> walls_heap;
[[vk::binding(6, 1)]] StructuredBuffer<uint2> sun_bits;
// The strokes' set (passes/Roofs.cpp): this frame slot's jobs, and its records as the vertex stage reads them and as
// the compute stage writes them (one buffer).
[[vk::binding(0, 2)]] StructuredBuffer<uint4> stroke_jobs;
[[vk::binding(1, 2)]] StructuredBuffer<uint4> stroke_records;
[[vk::binding(2, 2)]] RWStructuredBuffer<uint4> stroke_records_out;
// The windows' set (passes/Windows.cpp), set 2 of their own pipelines: this frame slot's jobs, its windows' records, its
// facade walls' records, its near windows' records and its facades' records, as the compute stage writes them and as
// the vertex stages read them (one buffer each).
[[vk::binding(3, 2)]] StructuredBuffer<uint4> window_jobs;
[[vk::binding(4, 2)]] RWStructuredBuffer<uint4> window_records_out;
[[vk::binding(5, 2)]] StructuredBuffer<uint4> window_records;
[[vk::binding(6, 2)]] RWStructuredBuffer<uint4> window_walls_out;
[[vk::binding(7, 2)]] StructuredBuffer<uint4> window_walls;
[[vk::binding(8, 2)]] RWStructuredBuffer<uint4> window_nears_out;
[[vk::binding(9, 2)]] StructuredBuffer<uint4> window_nears;
[[vk::binding(10, 2)]] RWStructuredBuffer<uint4> window_facades_out;
[[vk::binding(11, 2)]] StructuredBuffer<uint4> window_facades;
[[vk::push_constant]] ConstantBuffer<BuildingPush> push;

struct BuildingOut {
    float4 position : SV_Position;
    nointerpolation float3 colour : COLOUR;
};

/// The primitive at record `index` of the draw's list, its face, its rank entry, and the shape the frame draws.
struct Drawn {
    BuildingPrim prim;
    BuildingFace face;
    BuildingShape shape;
};

/// The primitive at `at` (a uint4 index) of the walls heap.
BuildingPrim prim_at(uint at) {
    return building_prim(walls_heap[at], walls_heap[at + 1u], walls_heap[at + 2u], walls_heap[at + 3u]);
}

/// The primitive at record `index` of the list a draw's push constants `p` name (a tile's), as the frame draws it.
Drawn drawn_at(BuildingPush p, uint index) {
    Drawn d;
    if (p.sun.w != 0u) {
        // a near cell: its record's two words (its wall in the edged list, its place), cut from its wall
        uint4 pair = walls_heap[p.at.x + index / 2u];
        bool odd = (index & 1u) != 0u;
        uint wall = odd ? pair.z : pair.x;
        uint code = odd ? pair.w : pair.y;
        d.prim = building_cell_prim(prim_at(p.sun.z + wall * BUILDING_PRIM_VECTORS), code);
    } else {
        d.prim = prim_at(p.at.x + index * BUILDING_PRIM_VECTORS);
    }
    uint fa = p.at.y + d.prim.face * BUILDING_FACE_VECTORS;
    d.face = building_face(walls_heap[fa], walls_heap[fa + 1u]);
    uint4 rows = walls_heap[p.at.w + d.prim.item / 4u];
    uint rank = rank_table[rows[d.prim.item % 4u]];
    d.shape = building_shape(frame.camera, frame.theme.flags.x, p.place, d.prim, d.face, rank);
    return d;
}

/// The primitive at record `index` of this draw's list.
Drawn drawn_of(uint index) {
    BuildingPush p = push;
    return drawn_at(p, index);
}

/// The face's colour at the frame: its light, lit by its sample's sun bit.
float3 colour_of(Drawn d) {
    uint word = push.at.z + d.prim.face * 2u; // even, so both words lie in one uint4
    uint4 pair = walls_heap[word / 4u];
    uint lit = pair[word % 4u];
    uint unlit = pair[(word % 4u) + 1u];
    uint has = push.sun.x != BUILDING_NO_BITS ? 1u : 0u;
    uint2 texel = {0u, 0u};
    if (has != 0u && d.prim.sample != BUILDING_NO_SAMPLE) {
        texel = sun_bits[push.sun.x + d.prim.sample / 32u];
    }
    float amount = building_sun_amount(d.face.flags, d.prim.sample, texel, has, asfloat(push.sun.y));
    return building_colour(lit, unlit, amount);
}

/// Where a primitive that draws nothing puts its points: beyond the depth range, so its triangles are clipped away.
static const float4 NOWHERE = {0.0f, 0.0f, 2.0f, 1.0f};

[shader("vertex")]
BuildingOut walls_vs(uint vertex : SV_VulkanVertexID) {
    uint index = vertex / BUILDING_EDGED_VERTICES;
    Drawn d = drawn_of(index);
    BuildingOut o;
    if (d.shape.count == 0u) {
        o.position = NOWHERE;
        o.colour = float3(0.0f);
        return o;
    }
    // the outline of the wall's same-colour stroke, half its width out (the style's)
    float2 css = building_outline(d.shape, vertex - index * BUILDING_EDGED_VERTICES, 0.5f * frame.strokes.widths.w);
    float depth = building_depth(frame.camera, d.shape, building_plane_recip(frame.camera, d.shape, css));
    o.position = clip_of(frame.camera, css, 1.0f, depth);
    o.colour = colour_of(d);
    return o;
}

[shader("vertex")]
BuildingOut roofs_vs(uint vertex : SV_VulkanVertexID) {
    uint index = vertex / BUILDING_PLAIN_VERTICES;
    Drawn d = drawn_of(index);
    BuildingOut o;
    if (d.shape.count == 0u) {
        o.position = NOWHERE;
        o.colour = float3(0.0f);
        return o;
    }
    float3 point = building_plain_vertex(d.shape, vertex - index * BUILDING_PLAIN_VERTICES);
    float2 css = {point.x, point.y};
    o.position = clip_of(frame.camera, css, 1.0f, building_depth(frame.camera, d.shape, point.z));
    o.colour = colour_of(d);
    return o;
}

/// A stroke job (Buildings::update): a tile's push constants as its plain list's draws have them (4 uint4), then
/// its run's first primitive in that list, how many (at most 64) and its first record.
static const uint STROKE_JOB_VECTORS = 5u;
/// The jobs a row of workgroups covers (Vulkan's least maxComputeWorkGroupCount[0]).
static const uint STROKE_JOB_ROW = 65535u;

/// Each plain primitive of a job's run, decided once for the frame into its record (the push constants' at.x: the
/// frame's jobs).
[shader("compute")]
[numthreads(64, 1, 1)]
void roof_strokes_cs(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID) {
    uint job = group.y * STROKE_JOB_ROW + group.x;
    if (job >= push.at.x) {
        return;
    }
    uint at = job * STROKE_JOB_VECTORS;
    uint4 span = stroke_jobs[at + 4u];
    if (thread.x >= span.y) {
        return;
    }
    BuildingPush p;
    p.place.offset = asfloat(stroke_jobs[at]);
    p.place.scale = asfloat(stroke_jobs[at + 1u]);
    p.at = stroke_jobs[at + 2u];
    p.sun = stroke_jobs[at + 3u];
    Drawn d = drawn_at(p, span.x + thread.x);
    BuildingStrokeRecord r = building_stroke_record(building_stroke_header(d.prim.flags, d.shape), d.shape);
    uint out_at = (span.z + thread.x) * BUILDING_STROKE_RECORD_VECTORS;
    stroke_records_out[out_at] = r.v0;
    stroke_records_out[out_at + 1u] = r.v1;
    stroke_records_out[out_at + 2u] = r.v2;
    stroke_records_out[out_at + 3u] = r.v3;
}

/// Vertex `vertex` of the records' strokes: record vertex / 30's segment along its shape's edge (vertex % 30) / 6, if
/// its stroke follows that edge, its width and colour its stroke's, at its face's depth there.
[shader("vertex")]
StrokeVertex roof_strokes_vs(uint vertex : SV_VulkanVertexID) {
    uint index = vertex / BUILDING_STROKE_VERTICES;
    uint rest = vertex - index * BUILDING_STROKE_VERTICES;
    uint k = rest / 6u;
    uint corner = rest - k * 6u;
    uint at = index * BUILDING_STROKE_RECORD_VECTORS;
    BuildingStrokeRecord r;
    r.v0 = stroke_records[at];
    uint header = r.v0.x;
    if ((building_stroke_edges(header) & (1u << k)) == 0u) {
        return stroke_none();
    }
    r.v1 = stroke_records[at + 1u];
    r.v2 = stroke_records[at + 2u];
    r.v3 = stroke_records[at + 3u];
    uint count = building_stroke_count(header);
    float3 a = building_stroke_corner(r, k);
    float3 b = building_stroke_corner(r, k + 1u < count ? k + 1u : 0u);
    float3 c = building_stroke_corner(r, building_stroke_third(count, k));
    // the stroke's width and colour, the style's (draw_building's `edge`, a houseboat cabin roof's)
    uint style = building_stroke_style(header);
    FrameStrokes strokes = frame.strokes;
    float width = style == BUILDING_STROKE_STYLE_FINE
                      ? strokes.widths.x
                      : (style == BUILDING_STROKE_STYLE_WIDE ? strokes.widths.y : strokes.widths.z);
    float4 colour = style == BUILDING_STROKE_STYLE_CABIN
                        ? strokes.cabin
                        : ((frame.theme.flags.x & THEME_NIGHT) != 0u ? strokes.roof_night : strokes.roof_day);
    float2 pa = {a.x, a.y};
    float2 pb = {b.x, b.y};
    float2 pc = {c.x, c.y};
    float4 ca = clip_of(frame.camera, pa, 1.0f, 0.0f);
    float4 cb = clip_of(frame.camera, pb, 1.0f, 0.0f);
    StrokeVertex o = stroke_quad(frame.camera, ca, cb, width, colour, corner, 0.0f);
    if (o.style.x > 0.0f) {
        // the face's reciprocal depth at the quad's corner, in output px as stroke_quad places the ends
        float2 scale = stroke_output_scale(frame.camera);
        float4 cc = clip_of(frame.camera, pc, 1.0f, 0.0f);
        float3 oa = {o.ends.x, o.ends.y, a.z};
        float3 ob = {o.ends.z, o.ends.w, b.z};
        float3 oc = {(cc.x / cc.w + 1.0f) / scale.x, (cc.y / cc.w + 1.0f) / scale.y, c.z};
        float2 here = {(o.position.x + 1.0f) / scale.x, (o.position.y + 1.0f) / scale.y};
        o.position.z = building_stroke_depth(frame.camera, building_stroke_slot(header),
                                             building_stroke_recip(oa, ob, oc, here));
    }
    return o;
}

/// A window job (Buildings::update): a tile's push constants (4 uint4: its place; at x its facade walls, y its faces, z
/// its edged primitives, w its rows; sun x its first sun texel, y its fade weight's bits, z its window group's first
/// sample, w its reveals' colours' first word this frame slot), then its run (its first facade wall in the tile's list,
/// how many, at most 64, its first wall record, its items' facade slots), each of its walls' first window record
/// (building_window_records of them: its windows', then its shop glazing's), its first near record (WINDOW_NO_NEAR: its
/// item lies 160 m or more away), its windows' widths (passes::window_widths: the frames' in tenths of a CSS px, bits
/// 0-15, the sash bars', 16-31), and its facade record (WINDOW_NO_NEAR: none), four a uint4.
static const uint WINDOW_JOB_VECTORS = 69u;
static const uint WINDOW_JOB_RECORDS = 5u;
static const uint WINDOW_JOB_NEARS = 21u;
static const uint WINDOW_JOB_WIDTHS = 37u;
static const uint WINDOW_JOB_FACADES = 53u;
static const uint WINDOW_NO_NEAR = 0xFFFFFFFFu;

/// Word `i` (a wall's) of a job's list starting at vector `list`.
uint window_job_word(uint at, uint list, uint i) {
    uint4 v = window_jobs[at + list + i / 4u];
    return v[i % 4u];
}

/// Each facade wall of a job's run, decided once for the frame: each of its windows into its record (at the job's
/// records, in the wall's order) and, nearer than 160 m, into its near record (its reveal, sill and sash bars); its
/// shop glazing into the record after them; its plinth and cornice into its facade record; and the wall into its own
/// (its plane, its dark and lit windows' boxes, its widths, its reveals' colour).
[shader("compute")]
[numthreads(64, 1, 1)]
void windows_cs(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID) {
    uint job = group.y * STROKE_JOB_ROW + group.x;
    if (job >= push.at.x) {
        return;
    }
    uint at = job * WINDOW_JOB_VECTORS;
    uint4 span = window_jobs[at + 4u];
    if (thread.x >= span.y) {
        return;
    }
    BuildingPush p;
    p.place.offset = asfloat(window_jobs[at]);
    p.place.scale = asfloat(window_jobs[at + 1u]);
    p.at = window_jobs[at + 2u];
    p.sun = window_jobs[at + 3u];
    BuildingWindowWall w = building_window_wall(walls_heap[p.at.x + span.x + thread.x]);
    BuildingPrim wall = prim_at(p.at.z + w.wall * BUILDING_PRIM_VECTORS);
    uint fa = p.at.y + wall.face * BUILDING_FACE_VECTORS;
    BuildingFace f = building_face(walls_heap[fa], walls_heap[fa + 1u]);
    uint4 rows = walls_heap[p.at.w + wall.item / 4u];
    uint rank = rank_table[rows[wall.item % 4u]];
    uint theme_flags = frame.theme.flags.x;
    bool drawn = building_windows_drawn(theme_flags, p.place, w, f, rank);
    bool deep = building_windows_deep(theme_flags, rank);
    bool sashed = building_windows_sashed(rank);
    bool facades = building_facades_drawn(theme_flags, p.place, f, rank);
    uint detail = rank != RANK_NONE ? rank_detail(rank) : 0u;
    uint first_row = building_windows_first_row(theme_flags, w);
    bool near = (detail & BUILDING_DETAIL_NEAR) != 0u;
    uint item_slot = rank != RANK_NONE ? rank_slot(rank) : 0u;
    // the wall's first slot (its shop glazing's where it has one), then its windows'
    uint first_slot = item_slot + (near ? w.near_slot : w.far_slot);
    uint slot = first_slot + ((w.flags & BUILDING_WINDOW_GLAZING) != 0u ? 1u : 0u);
    bool sun = p.sun.x != BUILDING_NO_BITS && (theme_flags & THEME_NIGHT) == 0u;
    float4 plane = building_window_plane(frame.camera, p.place, f);
    float infinity = asfloat(0x7F800000u);
    float2 dark = {infinity, -infinity};
    float2 lit = {infinity, -infinity};
    uint wall_record = span.z + thread.x;
    uint count = (detail & BUILDING_DETAIL_WINDOWS) != 0u ? w.levels * w.cols : 0u;
    uint first = window_job_word(at, WINDOW_JOB_RECORDS, thread.x);
    uint near_first = window_job_word(at, WINDOW_JOB_NEARS, thread.x);
    uint widths = window_job_word(at, WINDOW_JOB_WIDTHS, thread.x);
    float frame_half = 0.5f * building_tenths(widths & 0xFFFFu);
    float sash_half = 0.5f * building_tenths(widths >> 16u);
    for (uint k = 0u; k < count; ++k) {
        uint r = k / w.cols;
        uint c = k - r * w.cols;
        BuildingWindowShape s = building_window_nothing();
        BuildingWindowShape reveal = building_window_nothing();
        BuildingWindowShape sill = building_window_nothing();
        BuildingSash bars = building_sash_nothing();
        bool on = false;
        if (drawn && r >= first_row) {
            // amsterdam.py: sp = cam.poly(quad_at(..., 0.04, nrm)); deep and area(sp) > 70: the reveal sp, then sp
            // set back (rec -0.16) and the sill; the glass where area(sp) > 0.25; the sash bars at rec + 0.01 where
            // dd < 140 and area(sp) > 30
            BuildingWindowBox box = building_window_box(wall, w, r, c);
            BuildingWindowShape sp = building_window_cut(
                frame.camera, p.place, building_window_quad(wall, f.normal, box, BUILDING_WINDOW_OUT));
            float rec = BUILDING_WINDOW_OUT;
            if (deep && sp.count != 0u && sp.area > BUILDING_DEEP_AREA) {
                reveal = building_window_on_plane(frame.camera, plane, sp);
                rec = BUILDING_GLASS_BACK;
                sp = building_window_on_plane(
                    frame.camera, plane,
                    building_window_cut(frame.camera, p.place, building_window_quad(wall, f.normal, box, rec)));
                sill = building_window_on_plane(
                    frame.camera, plane,
                    building_window_cut(frame.camera, p.place, building_window_sill(wall, f.normal, box)));
            }
            s = building_window_over(sp, BUILDING_WINDOW_MIN_AREA);
            if (sashed && sp.count != 0u && sp.area > BUILDING_SASH_AREA) {
                bars = building_sash_on_plane(
                    frame.camera, plane,
                    building_sash(frame.camera, p.place,
                                  building_window_quad(wall, f.normal, box, rec + BUILDING_SASH_OUT)));
            }
            if (sun) {
                uint sample = p.sun.z + w.first + k;
                uint2 texel = sun_bits[p.sun.x + sample / 32u];
                on = ((texel.x >> (sample & 31u)) & 1u) != 0u;
            }
            for (uint i = 0u; i < s.count; ++i) {
                float y = building_window_corner(s, i).y;
                if (on) {
                    lit.x = min(lit.x, y);
                    lit.y = max(lit.y, y);
                } else {
                    dark.x = min(dark.x, y);
                    dark.y = max(dark.y, y);
                }
            }
        }
        BuildingWindowRecord record =
            building_window_record(s, on, building_glass_drawn(s, frame_half), false, w, wall_record,
                                   slot + (on ? BUILDING_WINDOW_LIT_SLOT : BUILDING_WINDOW_DARK));
        uint out_at = (first + k) * BUILDING_WINDOW_RECORD_VECTORS;
        window_records_out[out_at] = record.v0;
        window_records_out[out_at + 1u] = record.v1;
        window_records_out[out_at + 2u] = record.v2;
        window_records_out[out_at + 3u] = record.v3;
        if (near_first != WINDOW_NO_NEAR) {
            BuildingNearRecord n = building_near_record(reveal, sill, bars, wall_record, slot);
            uint near_at = (near_first + k) * BUILDING_NEAR_RECORD_VECTORS;
            window_nears_out[near_at] = n.v0;
            window_nears_out[near_at + 1u] = n.v1;
            window_nears_out[near_at + 2u] = n.v2;
            window_nears_out[near_at + 3u] = n.v3;
            window_nears_out[near_at + 4u] = n.v4;
            window_nears_out[near_at + 5u] = n.v5;
            window_nears_out[near_at + 6u] = n.v6;
            window_nears_out[near_at + 7u] = n.v7;
            window_nears_out[near_at + 8u] = n.v8;
            window_nears_out[near_at + 9u] = n.v9;
        }
    }
    // its shop glazing (by day: the open shops at night come with the night), a window record after its windows'
    if (building_window_records(w, rank) > count) {
        BuildingWindowShape g = building_window_nothing();
        if (facades && (theme_flags & THEME_NIGHT) == 0u) {
            g = building_window_over(
                building_window_cut(frame.camera, p.place, building_glazing(wall, f.normal)), BUILDING_GLAZING_AREA);
        }
        float shop_half = 0.5f * building_shop_frame_width(frame.windows);
        BuildingWindowRecord record =
            building_window_record(g, false, building_glass_drawn(g, shop_half), true, w, wall_record, first_slot);
        uint out_at = (first + count) * BUILDING_WINDOW_RECORD_VECTORS;
        window_records_out[out_at] = record.v0;
        window_records_out[out_at + 1u] = record.v1;
        window_records_out[out_at + 2u] = record.v2;
        window_records_out[out_at + 3u] = record.v3;
    }
    // its plinth (low nearer than 300 m) and cornice (nearer than 520 m), at its building's slots after its walls
    uint facade_first = window_job_word(at, WINDOW_JOB_FACADES, thread.x);
    if (facade_first != WINDOW_NO_NEAR) {
        BuildingWindowShape plinth = building_window_nothing();
        BuildingWindowShape cornice = building_window_nothing();
        if (facades && (w.flags & BUILDING_WINDOW_PLINTH) != 0u) {
            bool low = (detail & BUILDING_DETAIL_LOW_PLINTH) != 0u;
            plinth = building_window_on_plane(
                frame.camera, plane,
                building_window_over(building_window_cut_in(frame.camera, p.place, building_plinth(wall, f.normal, low),
                                                            BUILDING_FACADE_MARGIN),
                                     BUILDING_FACADE_AREA));
        }
        if (facades && (w.flags & BUILDING_WINDOW_CORNICE) != 0u && (detail & BUILDING_DETAIL_CORNICES) != 0u) {
            cornice = building_window_on_plane(
                frame.camera, plane,
                building_window_over(building_window_cut_in(frame.camera, p.place, building_cornice(wall, f.normal),
                                                            BUILDING_FACADE_MARGIN),
                                     BUILDING_FACADE_AREA));
        }
        uint4 slots4 = walls_heap[span.w + wall.item / 4u];
        uint slots = slots4[wall.item % 4u];
        uint facade_slot = item_slot + (near ? (slots & 0xFFFFu) : (slots >> 16u));
        BuildingFacadeRecord fr = building_facade_record(plinth, cornice, wall_record, facade_slot);
        uint fat = facade_first * BUILDING_FACADE_RECORD_VECTORS;
        window_facades_out[fat] = fr.v0;
        window_facades_out[fat + 1u] = fr.v1;
        window_facades_out[fat + 2u] = fr.v2;
        window_facades_out[fat + 3u] = fr.v3;
        window_facades_out[fat + 4u] = fr.v4;
        window_facades_out[fat + 5u] = fr.v5;
        window_facades_out[fat + 6u] = fr.v6;
    }
    uint4 plane_words = {asuint(plane.x), asuint(plane.y), asuint(plane.z), asuint(plane.w)};
    uint4 boxes = {asuint(dark.x), asuint(dark.y), asuint(lit.x), asuint(lit.y)};
    uint reveal_word = p.sun.w + span.x + thread.x;
    uint4 reveals = walls_heap[reveal_word / 4u];
    uint4 more = {asuint(frame_half), asuint(sash_half), reveals[reveal_word % 4u], 0u};
    window_walls_out[wall_record * BUILDING_WALL_RECORD_VECTORS] = plane_words;
    window_walls_out[wall_record * BUILDING_WALL_RECORD_VECTORS + 1u] = boxes;
    window_walls_out[wall_record * BUILDING_WALL_RECORD_VECTORS + 2u] = more;
}

/// The window record `index` of the frame's.
BuildingWindowRecord window_record_at(uint index) {
    uint at = index * BUILDING_WINDOW_RECORD_VECTORS;
    BuildingWindowRecord r;
    r.v0 = window_records[at];
    r.v1 = window_records[at + 1u];
    r.v2 = window_records[at + 2u];
    r.v3 = window_records[at + 3u];
    return r;
}

/// The frame's wall record `index`.
BuildingWallRecord window_wall_at(uint index) {
    uint at = index * BUILDING_WALL_RECORD_VECTORS;
    return building_wall_record_of(window_walls[at], window_walls[at + 1u], window_walls[at + 2u]);
}

/// A window's (or a part's) shape on its wall's plane with its lead and painter slot (the depth building_depth gives
/// its points).
BuildingShape window_shape_on(BuildingWallRecord wall, BuildingWindowShape s, float lead, uint slot) {
    BuildingShape shape = building_window_as_shape(s, wall.plane, lead);
    shape.slot = slot;
    shape.depth = painter_depth(frame.camera, slot);
    return shape;
}

/// The frames' fragments: the frame's colour, and the window's shape and its frame's half width (CSS px), with which
/// window_frames_fs keeps the samples the stroke covers.
struct FrameOut {
    float4 position : SV_Position;
    nointerpolation float3 colour : COLOUR;
    nointerpolation float4 a : WINDOW_A; // its corners 0 and 1
    nointerpolation float4 b : WINDOW_B; // 2 and 3
    nointerpolation float4 c : WINDOW_C; // 4, then its corners' count and the half width
};

/// Vertex `vertex` of the windows' frames: record vertex / 30's polygon holding the band its wall's frames' half width
/// paints about it (round joins: the fragments keep the samples within it), in the frames' colour, on its wall's plane
/// a lead over the glass.
[shader("vertex")]
FrameOut window_frames_vs(uint vertex : SV_VulkanVertexID) {
    uint index = vertex / BUILDING_WINDOW_FRAME_VERTICES;
    BuildingWindowRecord r = window_record_at(index);
    FrameOut o;
    BuildingWindowShape s = building_window_of(r);
    o.a = float4(0.0f);
    o.b = float4(0.0f);
    o.c = float4(0.0f);
    if (s.count == 0u) {
        o.position = NOWHERE;
        o.colour = float3(0.0f);
        return o;
    }
    BuildingWallRecord wall = window_wall_at(r.v0.y);
    BuildingShape shape = window_shape_on(wall, s, BUILDING_WINDOW_LEAD, r.v0.z);
    bool shop = (r.v0.x & BUILDING_WINDOW_RECORD_SHOP) != 0u;
    float half = shop ? 0.5f * building_shop_frame_width(frame.windows) : wall.frame_half;
    bool glass = (r.v0.x & BUILDING_WINDOW_RECORD_GLASS) != 0u;
    float2 css = building_window_frame_point(s, vertex - index * BUILDING_WINDOW_FRAME_VERTICES, half, glass);
    float depth = building_depth(frame.camera, shape, building_plane_recip(frame.camera, shape, css));
    o.position = clip_of(frame.camera, css, 1.0f, depth);
    o.colour = shop ? building_shop_frame(frame.windows, frame.theme.flags.x)
                    : building_window_frame(frame.windows, frame.theme.flags.x);
    o.a = float4(s.p0, s.p1);
    o.b = float4(s.p2, s.p3);
    o.c = float4(s.p4, float(s.count), half);
    return o;
}

struct GlassOut {
    float4 position : SV_Position;
    /// rgb: the glass's colour at its box's top; a: the top (CSS y); and at its bottom (a flat glass: both the top's)
    nointerpolation float4 top : GLASS_TOP;
    nointerpolation float4 bottom : GLASS_BOTTOM;
};

/// Vertex `vertex` of the windows' glass: record vertex / 5's window eroded by its wall's frames' half width (a fan from
/// its first corner, the last repeated), on its wall's plane a lead over the wall's cells, with its glass's paint.
[shader("vertex")]
GlassOut window_glass_vs(uint vertex : SV_VulkanVertexID) {
    uint index = vertex / BUILDING_WINDOW_GLASS_VERTICES;
    BuildingWindowRecord r = window_record_at(index);
    GlassOut o;
    BuildingWindowShape s = building_window_of(r);
    o.top = float4(0.0f);
    o.bottom = float4(0.0f);
    if (s.count == 0u) {
        o.position = NOWHERE;
        return o;
    }
    if ((r.v0.x & BUILDING_WINDOW_RECORD_GLASS) == 0u) {
        o.position = NOWHERE;
        return o;
    }
    BuildingWallRecord wall = window_wall_at(r.v0.y);
    bool shop = (r.v0.x & BUILDING_WINDOW_RECORD_SHOP) != 0u;
    float half = shop ? 0.5f * building_shop_frame_width(frame.windows) : wall.frame_half;
    uint v = vertex - index * BUILDING_WINDOW_GLASS_VERTICES;
    float2 css = building_glass_corner(s, v < s.count ? v : s.count - 1u, half);
    BuildingShape shape = window_shape_on(wall, s, BUILDING_GLASS_LEAD, r.v0.z);
    float depth = building_depth(frame.camera, shape, building_plane_recip(frame.camera, shape, css));
    o.position = clip_of(frame.camera, css, 1.0f, depth);
    bool lit = (r.v0.x & BUILDING_WINDOW_LIT) != 0u;
    uint style = (r.v0.x >> BUILDING_WINDOW_RECORD_STYLE_SHIFT) & 3u;
    uint flags = (r.v0.x & BUILDING_WINDOW_RECORD_WAREHOUSE) != 0u ? BUILDING_WINDOW_WAREHOUSE : 0u;
    BuildingGlass g = building_glass(frame.windows, frame.theme.flags.x, style, flags, lit);
    float2 box = lit ? wall.lit : wall.dark;
    if (shop) {
        // a shop's glazing is a path of its own: its gradient spans its own box
        box.x = s.p0.y;
        box.y = s.p0.y;
        for (uint i = 1u; i < s.count; ++i) {
            float y = building_window_corner(s, i).y;
            box.x = min(box.x, y);
            box.y = max(box.y, y);
        }
    }
    o.top = float4(g.top, box.x);
    o.bottom = g.gradient ? float4(g.bottom, box.y) : float4(g.top, box.x);
    return o;
}

/// The near window record `index` of the frame's.
BuildingNearRecord window_near_at(uint index) {
    uint at = index * BUILDING_NEAR_RECORD_VECTORS;
    BuildingNearRecord r;
    r.v0 = window_nears[at];
    r.v1 = window_nears[at + 1u];
    r.v2 = window_nears[at + 2u];
    r.v3 = window_nears[at + 3u];
    r.v4 = window_nears[at + 4u];
    r.v5 = window_nears[at + 5u];
    r.v6 = window_nears[at + 6u];
    r.v7 = window_nears[at + 7u];
    r.v8 = window_nears[at + 8u];
    r.v9 = window_nears[at + 9u];
    return r;
}

/// Vertex `v` (0 to 29, building_near_part's) of near record `index`'s parts: its reveal (in its wall's reveals'
/// colour), sill (the sills') or sash bars (the frames'), each on its wall's plane at its lead and painter slot; an
/// instance a record, so a draw takes a part's vertices alone (record_windows draws the sills and bars first, the
/// reveals last).
[shader("vertex")]
BuildingOut window_near_vs(uint v : SV_VulkanVertexID, uint index : SV_VulkanInstanceID) {
    BuildingNearRecord r = window_near_at(index);
    BuildingOut o;
    o.colour = float3(0.0f);
    uint part = building_near_part(v);
    uint header = r.v0.x;
    bool drawn = (header & BUILDING_NEAR_SASH) != 0u;
    if (part == 0u) {
        drawn = (header & 7u) != 0u;
    } else if (part == 1u) {
        drawn = ((header >> BUILDING_NEAR_SILL_SHIFT) & 7u) != 0u;
    }
    if (!drawn) {
        o.position = NOWHERE;
        return o;
    }
    BuildingWallRecord wall = window_wall_at(r.v0.y);
    BuildingWindowShape s = building_near_shape_of(r, v, wall.sash_half);
    if (s.count == 0u) {
        o.position = NOWHERE;
        return o;
    }
    uint theme_flags = frame.theme.flags.x;
    float lead = BUILDING_SASH_LEAD;
    uint slot = r.v0.z + BUILDING_WINDOW_SASHES;
    o.colour = building_window_frame(frame.windows, theme_flags);
    if (part == 0u) {
        lead = BUILDING_REVEAL_LEAD;
        slot = r.v0.z + BUILDING_WINDOW_REVEALS;
        o.colour = building_bgr(wall.reveal);
    } else if (part == 1u) {
        lead = BUILDING_SILL_LEAD;
        slot = r.v0.z + BUILDING_WINDOW_SILLS;
        o.colour = building_window_sill_colour(frame.windows, theme_flags);
    }
    BuildingShape shape = window_shape_on(wall, s, lead, slot);
    float2 css = building_near_point(r, v, wall.sash_half);
    float depth = building_depth(frame.camera, shape, building_plane_recip(frame.camera, shape, css));
    o.position = clip_of(frame.camera, css, 1.0f, depth);
    return o;
}

/// The facade record `index` of the frame's.
BuildingFacadeRecord window_facade_at(uint index) {
    uint at = index * BUILDING_FACADE_RECORD_VECTORS;
    BuildingFacadeRecord r;
    r.v0 = window_facades[at];
    r.v1 = window_facades[at + 1u];
    r.v2 = window_facades[at + 2u];
    r.v3 = window_facades[at + 3u];
    r.v4 = window_facades[at + 4u];
    r.v5 = window_facades[at + 5u];
    r.v6 = window_facades[at + 6u];
    return r;
}

/// Vertex `v` (0 to 17) of facade record `index`'s plinth (its first 9: a fan of 3 triangles) and cornice (the rest),
/// each in its colour on its wall's plane at the sills' lead, at its building's slot after its walls (the cornice's
/// the next); an instance a record.
[shader("vertex")]
BuildingOut window_facade_vs(uint v : SV_VulkanVertexID, uint index : SV_VulkanInstanceID) {
    BuildingFacadeRecord r = window_facade_at(index);
    BuildingOut o;
    o.colour = float3(0.0f);
    uint part = v < 9u ? 0u : 1u;
    BuildingWindowShape s = building_facade_part(r, part);
    if (s.count == 0u) {
        o.position = NOWHERE;
        return o;
    }
    BuildingWallRecord wall = window_wall_at(r.v0.y);
    BuildingShape shape = window_shape_on(wall, s, BUILDING_SILL_LEAD, r.v0.z + part);
    float2 css = building_facade_point(r, v);
    float depth = building_depth(frame.camera, shape, building_plane_recip(frame.camera, shape, css));
    o.position = clip_of(frame.camera, css, 1.0f, depth);
    uint theme_flags = frame.theme.flags.x;
    o.colour = part == 0u ? building_plinth_colour(frame.windows, theme_flags)
                          : building_cornice_colour(frame.windows, theme_flags);
    return o;
}

struct BuildingTarget {
    [[vk::location(0)]] float4 colour : SV_Target0;
};

/// A window's glass: its gradient at the pixel's centre over its path's box (or its flat colour), with mat's grain.
[shader("fragment")]
BuildingTarget window_glass_fs(GlassOut v) {
    BuildingTarget o;
    BuildingGlass g;
    g.top = v.top.xyz;
    g.bottom = v.bottom.xyz;
    g.gradient = v.bottom.w > v.top.w;
    float y = css_of_output(frame.camera, v.position.xy).y;
    float3 c = mat_colour(frame.camera, building_glass_at(g, v.top.w, v.bottom.w, y), v.position.xy, mat_on(frame));
    o.colour = float4(c, 1.0f);
    return o;
}

[shader("fragment")]
BuildingTarget building_fs(BuildingOut v) {
    BuildingTarget o;
    // mat's grain on the walls and roofs, where the frame's fields are bound and the moment has weather
    float3 c = mat_colour(frame.camera, v.colour, v.position.xy, mat_on(frame));
    o.colour = float4(c, 1.0f);
    return o;
}

/// Vulkan's standard sample positions in a pixel (D7): 4x, then 2x.
static const float2 window_samples4[4] = {float2(0.375f, 0.125f), float2(0.875f, 0.375f), float2(0.125f, 0.625f),
                                          float2(0.625f, 0.875f)};
static const float2 window_samples2[2] = {float2(0.75f, 0.75f), float2(0.25f, 0.25f)};

struct FrameTarget {
    [[vk::location(0)]] float4 colour : SV_Target0;
    uint coverage : SV_Coverage;
};

/// A window's frame: its colour with mat's grain on the samples its stroke covers (building_window_covers: SVG's round
/// joins at any width; the polygon the vertex stage draws covers more), push.at.x samples a pixel (record_windows').
[shader("fragment")]
FrameTarget window_frames_fs(FrameOut v) {
    BuildingWindowShape s = building_window_nothing();
    s.count = uint(v.c.z);
    s.p0 = v.a.xy;
    s.p1 = v.a.zw;
    s.p2 = v.b.xy;
    s.p3 = v.b.zw;
    s.p4 = v.c.xy;
    float half = v.c.w;
    float2 corner = floor(v.position.xy);
    uint n = push.at.x;
    uint mask = 0u;
    for (uint i = 0u; i < n; ++i) {
        float2 at = n == 4u ? window_samples4[i] : (n == 2u ? window_samples2[i] : float2(0.5f, 0.5f));
        if (building_window_covers(s, css_of_output(frame.camera, corner + at), half)) {
            mask |= 1u << i;
        }
    }
    if (mask == 0u) {
        discard;
    }
    FrameTarget o;
    o.colour = float4(mat_colour(frame.camera, v.colour, v.position.xy, mat_on(frame)), 1.0f);
    o.coverage = mask;
    return o;
}
