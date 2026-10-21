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

struct BuildingTarget {
    [[vk::location(0)]] float4 colour : SV_Target0;
};

[shader("fragment")]
BuildingTarget building_fs(BuildingOut v) {
    BuildingTarget o;
    // mat's grain on the walls and roofs, where the frame's fields are bound and the moment has weather
    float3 c = mat_colour(frame.camera, v.colour, v.position.xy, mat_on(frame));
    o.colour = float4(c, 1.0f);
    return o;
}
