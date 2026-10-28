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
// facade walls' records, its near windows' records, its facades' records and its church windows' records, as the
// compute stage writes them and as the vertex stages read them (one buffer each).
[[vk::binding(3, 2)]] StructuredBuffer<uint4> window_jobs;
[[vk::binding(4, 2)]] RWStructuredBuffer<uint4> window_records_out;
[[vk::binding(5, 2)]] StructuredBuffer<uint4> window_records;
[[vk::binding(6, 2)]] RWStructuredBuffer<uint4> window_walls_out;
[[vk::binding(7, 2)]] StructuredBuffer<uint4> window_walls;
[[vk::binding(8, 2)]] RWStructuredBuffer<uint4> window_nears_out;
[[vk::binding(9, 2)]] StructuredBuffer<uint4> window_nears;
[[vk::binding(10, 2)]] RWStructuredBuffer<uint4> window_facades_out;
[[vk::binding(11, 2)]] StructuredBuffer<uint4> window_facades;
[[vk::binding(17, 2)]] RWStructuredBuffer<uint4> church_records_out;
[[vk::binding(18, 2)]] StructuredBuffer<uint4> church_records;
[[vk::binding(19, 2)]] RWStructuredBuffer<uint4> material_fills_out;
[[vk::binding(20, 2)]] StructuredBuffer<uint4> material_fills;
[[vk::binding(21, 2)]] RWStructuredBuffer<uint4> material_lines_out;
[[vk::binding(22, 2)]] StructuredBuffer<uint4> material_lines;
[[vk::binding(23, 2)]] StructuredBuffer<uint4> beams;
// The kept primitives' set (passes/Kept.cpp), set 2 of the walls' and roofs' kept pipelines and of the cull's compute
// stages: this frame slot's jobs (the header, then each job: a tile's push constants and a run of at most 64 of one
// list's primitives), each job's candidates (their count, their mask and their first place in their list), the kept
// primitives in their lists' order (a job and a lane each), as the compute stage writes them and as the vertex stage
// reads them, and the lists' chunks' indirect draws.
[[vk::binding(12, 2)]] StructuredBuffer<uint4> kept_jobs;
[[vk::binding(13, 2)]] RWStructuredBuffer<uint4> kept_counts;
[[vk::binding(14, 2)]] RWStructuredBuffer<uint> kept_out;
[[vk::binding(15, 2)]] StructuredBuffer<uint> kept;
[[vk::binding(16, 2)]] RWStructuredBuffer<uint> kept_draws;
[[vk::push_constant]] ConstantBuffer<BuildingPush> push;

struct BuildingOut {
    float4 position : SV_Position;
    nointerpolation float3 colour : COLOUR;
    nointerpolation uint asked : ASKED; // a wall of the building the app asks about, before its answer: hatched
};

/// The primitive at record `index` of the draw's list, its face, its rank entry, and the shape the frame draws.
struct Drawn {
    BuildingPrim prim;
    BuildingFace face;
    BuildingShape shape;
    uint rank;
};

/// The primitive at `at` (a uint4 index) of the walls heap.
BuildingPrim prim_at(uint at) {
    return building_prim(walls_heap[at], walls_heap[at + 1u], walls_heap[at + 2u], walls_heap[at + 3u]);
}

/// The primitive at record `index` of the list a draw's push constants `p` name (a tile's), its face and its item's rank
/// table entry, as the walls heap and the frame's rank table hold them.
struct Fetched {
    BuildingPrim prim;
    BuildingFace face;
    uint rank;
};

Fetched fetched_at(BuildingPush p, uint index) {
    Fetched f;
    if (p.sun.w != 0u) {
        // a near cell: its record's two words (its wall in the edged list, its place), cut from its wall
        uint4 pair = walls_heap[p.at.x + index / 2u];
        bool odd = (index & 1u) != 0u;
        uint wall = odd ? pair.z : pair.x;
        uint code = odd ? pair.w : pair.y;
        f.prim = building_cell_prim(prim_at(p.sun.z + wall * BUILDING_PRIM_VECTORS), code);
    } else {
        f.prim = prim_at(p.at.x + index * BUILDING_PRIM_VECTORS);
    }
    uint fa = p.at.y + f.prim.face * BUILDING_FACE_VECTORS;
    f.face = building_face(walls_heap[fa], walls_heap[fa + 1u]);
    uint4 rows = walls_heap[p.at.w + f.prim.item / 4u];
    f.rank = rank_table[rows[f.prim.item % 4u]];
    return f;
}

/// The primitive at record `index` of the list a draw's push constants `p` name (a tile's), as the frame draws it.
Drawn drawn_at(BuildingPush p, uint index) {
    Fetched f = fetched_at(p, index);
    Drawn d;
    d.prim = f.prim;
    d.face = f.face;
    d.shape = building_shape(frame.camera, frame.theme.flags.x, p.place, d.prim, d.face, f.rank);
    d.rank = f.rank;
    return d;
}

/// The face's colour at the frame: its light, lit by its sample's sun bit, through the push constants `p` of its tile.
float3 colour_at(BuildingPush p, Drawn d) {
    uint word = p.at.z + d.prim.face * 2u; // even, so both words lie in one uint4
    uint4 pair = walls_heap[word / 4u];
    uint lit = pair[word % 4u];
    uint unlit = pair[(word % 4u) + 1u];
    uint has = p.sun.x != BUILDING_NO_BITS ? 1u : 0u;
    uint2 texel = {0u, 0u};
    if (has != 0u && d.prim.sample != BUILDING_NO_SAMPLE) {
        texel = sun_bits[p.sun.x + d.prim.sample / 32u];
    }
    float amount = building_sun_amount(d.face.flags, d.prim.sample, texel, has, asfloat(p.sun.y));
    return building_colour(lit, unlit, amount);
}

/// Where a primitive that draws nothing puts its points: beyond the depth range, so its triangles are clipped away.
static const float4 NOWHERE = {0.0f, 0.0f, 2.0f, 1.0f};

/// Point `corner` (0 to 15) of the outline of the edged primitive at record `index` of the list `p` names.
BuildingOut wall_vertex(BuildingPush p, uint index, uint corner) {
    Drawn d = drawn_at(p, index);
    BuildingOut o;
    if (d.shape.count == 0u) {
        o.position = NOWHERE;
        o.colour = float3(0.0f);
        o.asked = 0u;
        return o;
    }
    // the outline of the wall's same-colour stroke, half its width out (the style's)
    float2 css = building_outline(d.shape, corner, 0.5f * frame.strokes.widths.w);
    float depth = building_depth(frame.camera, d.shape, building_plane_recip(frame.camera, d.shape, css));
    o.position = clip_of(frame.camera, css, 1.0f, depth);
    o.colour = colour_at(p, d);
    o.asked = (d.rank & RANK_ASKED) != 0u ? 1u : 0u;
    return o;
}

/// Point `corner` (0 to 4) of the fan of the plain primitive at record `index` of the list `p` names.
BuildingOut roof_vertex(BuildingPush p, uint index, uint corner) {
    Drawn d = drawn_at(p, index);
    BuildingOut o;
    if (d.shape.count == 0u) {
        o.position = NOWHERE;
        o.colour = float3(0.0f);
        o.asked = 0u;
        return o;
    }
    float3 point = building_plain_vertex(d.shape, corner);
    float2 css = {point.x, point.y};
    o.position = clip_of(frame.camera, css, 1.0f, building_depth(frame.camera, d.shape, point.z));
    o.colour = colour_at(p, d);
    o.asked = 0u; // the board hatches the walls alone
    return o;
}

[shader("vertex")]
BuildingOut walls_vs(uint vertex : SV_VulkanVertexID) {
    uint index = vertex / BUILDING_EDGED_VERTICES;
    BuildingPush p = push;
    return wall_vertex(p, index, vertex - index * BUILDING_EDGED_VERTICES);
}

[shader("vertex")]
BuildingOut roofs_vs(uint vertex : SV_VulkanVertexID) {
    uint index = vertex / BUILDING_PLAIN_VERTICES;
    BuildingPush p = push;
    return roof_vertex(p, index, vertex - index * BUILDING_PLAIN_VERTICES);
}

/// A kept primitive (passes/Kept.cpp): its job's push constants (its tile's, as that list's draws had them) and its
/// record in its list, from its place `slot` among its list's kept primitives (a job and a lane: job << 6 | lane).
struct KeptPrim {
    BuildingPush push;
    uint index;
};

KeptPrim kept_at(uint slot) {
    uint k = kept[slot];
    uint at = BUILDING_KEPT_HEADER + (k >> 6u) * BUILDING_KEPT_JOB_VECTORS;
    KeptPrim o;
    o.push.place.offset = asfloat(kept_jobs[at]);
    o.push.place.scale = asfloat(kept_jobs[at + 1u]);
    o.push.at = kept_jobs[at + 2u];
    o.push.sun = kept_jobs[at + 3u];
    o.index = kept_jobs[at + 4u].x + (k & 63u);
    return o;
}

/// The kept walls and near cells: a kept draw's vertex is point vertex % 16 of the kept primitive at vertex / 16 (its
/// chunk's vertex offset is the chunk's first kept place, times 16), decided as walls_vs decides it.
[shader("vertex")]
BuildingOut walls_kept_vs(uint vertex : SV_VulkanVertexID) {
    uint slot = vertex / BUILDING_EDGED_VERTICES;
    KeptPrim k = kept_at(slot);
    return wall_vertex(k.push, k.index, vertex - slot * BUILDING_EDGED_VERTICES);
}

/// The kept roofs' faces and n-gons' triangles, as roofs_vs decides them.
[shader("vertex")]
BuildingOut roofs_kept_vs(uint vertex : SV_VulkanVertexID) {
    uint slot = vertex / BUILDING_PLAIN_VERTICES;
    KeptPrim k = kept_at(slot);
    return roof_vertex(k.push, k.index, vertex - slot * BUILDING_PLAIN_VERTICES);
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

groupshared uint kept_mask[2];
groupshared uint kept_sums[64];
groupshared uint kept_total;

/// The kept primitives' cull (passes/Kept.cpp): each primitive of a job's run that may draw (building_may_draw) is a
/// candidate, into its job's count and mask (bit i: the run's primitive i), which kept_scan_cs and kept_scatter_cs read.
[shader("compute")]
[numthreads(64, 1, 1)]
void kept_cull_cs(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID) {
    uint job = group.y * STROKE_JOB_ROW + group.x;
    if (job >= kept_jobs[0].w) {
        return; // the whole workgroup
    }
    if (thread.x < 2u) {
        kept_mask[thread.x] = 0u;
    }
    GroupMemoryBarrierWithGroupSync();
    uint at = BUILDING_KEPT_HEADER + job * BUILDING_KEPT_JOB_VECTORS;
    uint4 span = kept_jobs[at + 4u];
    if (thread.x < span.y) {
        BuildingPush p;
        p.place.offset = asfloat(kept_jobs[at]);
        p.place.scale = asfloat(kept_jobs[at + 1u]);
        p.at = kept_jobs[at + 2u];
        p.sun = kept_jobs[at + 3u];
        Fetched f = fetched_at(p, span.x + thread.x);
        if (building_may_draw(frame.theme.flags.x, p.place, f.prim, f.face, f.rank)) {
            InterlockedOr(kept_mask[thread.x >> 5u], 1u << (thread.x & 31u));
        }
    }
    GroupMemoryBarrierWithGroupSync();
    if (thread.x == 0u) {
        uint lo = kept_mask[0];
        uint hi = kept_mask[1];
        kept_counts[job] = uint4(countbits(lo) + countbits(hi), lo, hi, 0u);
    }
}

/// The kept primitives' scan: workgroup l takes list l's jobs in their order and gives each its first place among the
/// list's kept primitives (kept_counts' w: the candidates of the list's jobs before it), then each of the list's chunks
/// its indirect draw: as many of the static index buffer's primitives as the chunk keeps, from its first place on.
[shader("compute")]
[numthreads(64, 1, 1)]
void kept_scan_cs(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID) {
    uint list = group.x;
    uint first = kept_jobs[0][list];
    uint count = kept_jobs[1][list];
    uint base = kept_jobs[2][list];
    uint draw = kept_jobs[3][list];
    // the list's places: up to the next list's first (the total after the last)
    uint end = list + 1u < BUILDING_KEPT_LISTS ? kept_jobs[2][list + 1u] : kept_jobs[2].w;
    uint chunk = list + 1u == BUILDING_KEPT_LISTS ? BUILDING_KEPT_PLAIN_CHUNK : BUILDING_KEPT_EDGED_CHUNK;
    uint vertices = list + 1u == BUILDING_KEPT_LISTS ? BUILDING_PLAIN_VERTICES : BUILDING_EDGED_VERTICES;
    uint indices = (vertices - 2u) * 3u;
    // each thread a block of the list's jobs, in order
    uint block = (count + 63u) / 64u;
    uint from = first + min(count, thread.x * block);
    uint to = first + min(count, (thread.x + 1u) * block);
    uint sum = 0u;
    for (uint j = from; j < to; ++j) {
        sum += kept_counts[j].x;
    }
    kept_sums[thread.x] = sum;
    GroupMemoryBarrierWithGroupSync();
    if (thread.x == 0u) {
        uint run = 0u;
        for (uint i = 0u; i < 64u; ++i) {
            uint s = kept_sums[i];
            kept_sums[i] = run;
            run += s;
        }
        kept_total = run;
    }
    GroupMemoryBarrierWithGroupSync();
    uint offset = kept_sums[thread.x];
    for (uint j = from; j < to; ++j) {
        uint4 c = kept_counts[j];
        kept_counts[j] = uint4(c.x, c.y, c.z, offset);
        offset += c.x;
    }
    uint total = kept_total;
    uint chunks = (end - base + chunk - 1u) / chunk;
    for (uint k = thread.x; k < chunks; k += 64u) {
        uint n = total > k * chunk ? min(chunk, total - k * chunk) : 0u;
        uint w = (draw + k) * BUILDING_KEPT_DRAW_WORDS;
        kept_draws[w] = n * indices;                         // indexCount
        kept_draws[w + 1u] = 1u;                             // instanceCount
        kept_draws[w + 2u] = 0u;                             // firstIndex
        kept_draws[w + 3u] = (base + k * chunk) * vertices;  // vertexOffset
        kept_draws[w + 4u] = 0u;                             // firstInstance
    }
}

/// The kept primitives' scatter: each candidate of a job's run into its place among its list's kept primitives (its
/// job's first place, then the candidates before it in the job), as its job and lane (job << 6 | lane).
[shader("compute")]
[numthreads(64, 1, 1)]
void kept_scatter_cs(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID) {
    uint job = group.y * STROKE_JOB_ROW + group.x;
    if (job >= kept_jobs[0].w) {
        return;
    }
    uint4 c = kept_counts[job];
    uint lane = thread.x;
    uint word = lane < 32u ? c.y : c.z;
    uint bit = lane & 31u;
    if (((word >> bit) & 1u) == 0u) {
        return;
    }
    uint below = countbits(word & ((1u << bit) - 1u)) + (lane < 32u ? 0u : countbits(c.y));
    uint list = kept_jobs[BUILDING_KEPT_HEADER + job * BUILDING_KEPT_JOB_VECTORS + 4u].z;
    kept_out[kept_jobs[2][list] + c.w + below] = (job << 6u) | lane;
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
/// 0-15, the sash bars', 16-31; where it draws church windows, their leading's in hundredths), its facade record
/// (WINDOW_NO_NEAR: none), its church windows (WINDOW_NO_NEAR: none drawn: its first church record, bits 0-15,
/// how many, 16-23, each one's transoms, 24-31), its material face's first fill record and first line record
/// (WINDOW_NO_NEAR: none), how many lines its glass's floors take or its decks or balconies how many records, bits
/// 0-11, and its mullions' or ribs' n or its balconies' width in hundredths, 12-31 (passes::material_choice), and its
/// ribs' colour, four a uint4.
static const uint WINDOW_JOB_VECTORS = 149u;
static const uint WINDOW_JOB_RECORDS = 5u;
static const uint WINDOW_JOB_NEARS = 21u;
static const uint WINDOW_JOB_WIDTHS = 37u;
static const uint WINDOW_JOB_FACADES = 53u;
static const uint WINDOW_JOB_CHURCHES = 69u;
static const uint WINDOW_JOB_MATERIAL_FILLS = 85u;
static const uint WINDOW_JOB_MATERIAL_LINES = 101u;
static const uint WINDOW_JOB_MATERIALS = 117u;
static const uint WINDOW_JOB_RIBS = 133u;
static const uint WINDOW_NO_NEAR = 0xFFFFFFFFu;

/// Word `i` (a wall's) of a job's list starting at vector `list`.
uint window_job_word(uint at, uint list, uint i) {
    uint4 v = window_jobs[at + list + i / 4u];
    return v[i % 4u];
}

/// Each facade wall of a job's run, decided once for the frame: each of its windows into its record (at the job's
/// records, in the wall's order) and, nearer than 160 m, into its near record (its reveal, sill and sash bars); its
/// shop glazing into the record after them; its plinth and cornice into its facade record; a church's windows each
/// into its church record; and the wall into its own (its plane, its dark and lit windows' boxes, its widths, its
/// reveals' colour; a church's windows' glass's box as its dark windows', which it has none of then, and their
/// leading's width). A houseboat cabin's wall's panes are its windows, never lit, at its one slot.
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
    bool cabin = w.style == BUILDING_WINDOW_CABIN;
    bool near = (detail & BUILDING_DETAIL_NEAR) != 0u;
    uint item_slot = rank != RANK_NONE ? rank_slot(rank) : 0u;
    // the wall's first slot (its shop glazing's where it has one), then its material face's, then its windows'
    uint first_slot = item_slot + (near ? w.near_slot : w.far_slot);
    uint material_slot = first_slot + ((w.flags & BUILDING_WINDOW_GLAZING) != 0u ? 1u : 0u);
    uint slot = material_slot + building_material_slots(w.material);
    bool sun = p.sun.x != BUILDING_NO_BITS && (theme_flags & THEME_NIGHT) == 0u;
    float4 plane = building_window_plane(frame.camera, p.place, f);
    float infinity = asfloat(0x7F800000u);
    float2 dark = {infinity, -infinity};
    float2 lit = {infinity, -infinity};
    uint wall_record = span.z + thread.x;
    uint count = building_window_count(w, detail);
    uint first = window_job_word(at, WINDOW_JOB_RECORDS, thread.x);
    uint near_first = window_job_word(at, WINDOW_JOB_NEARS, thread.x);
    uint widths = window_job_word(at, WINDOW_JOB_WIDTHS, thread.x);
    // a houseboat cabin's frames' width is its own
    float frame_half = 0.5f * (cabin ? building_cabin_frame_width(frame.windows) : building_tenths(widths & 0xFFFFu));
    float sash_half = 0.5f * building_tenths(widths >> 16u);
    for (uint k = 0u; k < count; ++k) {
        uint r = k / w.cols;
        uint c = k - r * w.cols;
        BuildingWindowShape s = building_window_nothing();
        BuildingWindowShape reveal = building_window_nothing();
        BuildingWindowShape sill = building_window_nothing();
        BuildingSash bars = building_sash_nothing();
        bool on = false;
        if (drawn && cabin) {
            // draw_houseboat: sp = cam.poly(quad_at(a, c, y0 + 0.35, y0 + 1.0, u0, u1, 0.03, nrm), margin=200)
            s = building_cabin_window(frame.camera, p.place, wall, f.normal, w, c);
        } else if (drawn && r >= first_row) {
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
        // a cabin's panes are one path, at its slot
        uint glass_slot = cabin ? slot : slot + (on ? BUILDING_WINDOW_LIT_SLOT : BUILDING_WINDOW_DARK);
        BuildingWindowRecord record = building_window_record(s, on, building_glass_drawn(s, frame_half), false, w,
                                                             wall_record, glass_slot);
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
    // a church's own windows (with weather nearer than 520 m, its plain ones not drawn), each into its record at the
    // job's church records in the wall's order; none where none of them has its glass
    uint church = window_job_word(at, WINDOW_JOB_CHURCHES, thread.x);
    float leading = 0.0f;
    if (church != WINDOW_NO_NEAR) {
        uint church_first = church & 0xFFFFu;
        uint bays = (church >> 16u) & 0xFFu;
        uint transoms = church >> 24u;
        bool shown = building_church_drawn(theme_flags, p.place, w, f, rank);
        BuildingChurchBay bay = building_church_bay(wall, bays);
        bool glass = false;
        for (uint k = 0u; k < bays; ++k) {
            BuildingChurchRecord r = building_church_empty();
            if (shown) {
                r = building_church_record(frame.camera, p.place, wall, f.normal, plane, bay, k, transoms, wall_record,
                                           slot);
            }
            BuildingArch g = building_church_arch(r, 2u);
            for (uint i = 0u; i < g.count; ++i) {
                dark.x = min(dark.x, g.p[i].y);
                dark.y = max(dark.y, g.p[i].y);
            }
            glass = glass || g.count != 0u;
            uint church_at = (church_first + k) * BUILDING_CHURCH_RECORD_VECTORS;
            for (uint i = 0u; i < BUILDING_CHURCH_RECORD_VECTORS; ++i) {
                church_records_out[church_at + i] = r.v[i];
            }
        }
        if (!glass) {
            uint4 none = {0u, 0u, 0u, 0u};
            for (uint k = 0u; k < bays; ++k) {
                church_records_out[(church_first + k) * BUILDING_CHURCH_RECORD_VECTORS] = none;
            }
        }
        leading = building_hundredths(widths);
    }
    // its material face (with weather nearer than 1.35 times win_d), its fills and lines each into its record at the
    // job's, in the wall's order: a glass wall's glass, then its floors and mullions; a metal one's ribs; a stone front's
    // balconies; a parking garage's decks
    uint fills_first = window_job_word(at, WINDOW_JOB_MATERIAL_FILLS, thread.x);
    uint lines_first = window_job_word(at, WINDOW_JOB_MATERIAL_LINES, thread.x);
    if (fills_first != WINDOW_NO_NEAR || lines_first != WINDOW_NO_NEAR) {
        uint counts = window_job_word(at, WINDOW_JOB_MATERIALS, thread.x);
        uint ribs = window_job_word(at, WINDOW_JOB_RIBS, thread.x);
        uint count = counts & 0xFFFu;
        uint n = counts >> 12u;
        bool shown = building_materials_drawn(theme_flags, p.place, f, rank);
        uint m = w.material;
        if (fills_first != WINDOW_NO_NEAR) {
            uint fills = m == BUILDING_MATERIAL_GLASS ? 1u : count;
            for (uint k = 0u; k < fills; ++k) {
                BuildingWindowShape s = building_window_nothing();
                if (shown) {
                    bool glass = m == BUILDING_MATERIAL_GLASS;
                    BuildingPrim q = glass ? building_material_glass(wall, f.normal)
                                           : building_material_deck(wall, f.normal, w.levels, k);
                    s = building_window_on_plane(
                        frame.camera, plane,
                        building_window_over(building_window_cut(frame.camera, p.place, q),
                                             glass ? BUILDING_MATERIAL_GLASS_AREA : BUILDING_MATERIAL_DECK_AREA));
                }
                BuildingMaterialFillRecord r = building_material_fill_record(s, m, wall_record, material_slot);
                uint fat = (fills_first + k) * BUILDING_MATERIAL_FILL_VECTORS;
                material_fills_out[fat] = r.v0;
                material_fills_out[fat + 1u] = r.v1;
                material_fills_out[fat + 2u] = r.v2;
                material_fills_out[fat + 3u] = r.v3;
            }
        }
        if (lines_first != WINDOW_NO_NEAR) {
            // a glass wall's floors (count of them) and its mullions (n - 1 where n > 1), a metal wall's ribs (n - 1), a
            // stone front's balconies (count of them); the lines' slot after a glass wall's fill's
            uint floors = m == BUILDING_MATERIAL_GLASS ? count : 0u;
            uint across = m == BUILDING_MATERIAL_BALCONIES ? 0u : (n > 1u ? n - 1u : 0u);
            uint lines = m == BUILDING_MATERIAL_BALCONIES ? count : floors + across;
            uint line_slot = material_slot + (m == BUILDING_MATERIAL_GLASS ? 1u : 0u);
            uint style = m == BUILDING_MATERIAL_BALCONIES ? n : ribs;
            for (uint k = 0u; k < lines; ++k) {
                BuildingMaterialLine l = building_material_line(frame.camera, p.place, wall, f.normal,
                                                                building_material_ends(wall, m, w.levels, floors, n, k));
                if (!shown) {
                    l.drawn = false;
                }
                BuildingMaterialLineRecord r = building_material_line_record(
                    building_material_line_on_plane(frame.camera, plane, l), m, wall_record, line_slot, style);
                uint lat = (lines_first + k) * BUILDING_MATERIAL_LINE_VECTORS;
                material_lines_out[lat] = r.v0;
                material_lines_out[lat + 1u] = r.v1;
                material_lines_out[lat + 2u] = r.v2;
            }
        }
    }
    uint4 plane_words = {asuint(plane.x), asuint(plane.y), asuint(plane.z), asuint(plane.w)};
    uint4 boxes = {asuint(dark.x), asuint(dark.y), asuint(lit.x), asuint(lit.y)};
    uint reveal_word = p.sun.w + span.x + thread.x;
    uint4 reveals = walls_heap[reveal_word / 4u];
    uint4 more = {asuint(frame_half), asuint(sash_half), reveals[reveal_word % 4u], asuint(leading)};
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
/// paints about it (round joins: the fragments keep the samples within it), in the frames' colour (a shop's glazing's
/// or a houseboat cabin's own), on its wall's plane a lead over the glass.
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
    bool shop = (r.v0.x & BUILDING_WINDOW_RECORD_SHOP) != 0u;
    BuildingShape shape = window_shape_on(wall, s, shop ? BUILDING_GLAZING_FRAME_LEAD : BUILDING_WINDOW_LEAD, r.v0.z);
    float half = shop ? 0.5f * building_shop_frame_width(frame.windows) : wall.frame_half;
    bool glass = (r.v0.x & BUILDING_WINDOW_RECORD_GLASS) != 0u;
    float2 css = building_window_frame_point(s, vertex - index * BUILDING_WINDOW_FRAME_VERTICES, half, glass);
    float depth = building_depth(frame.camera, shape, building_plane_recip(frame.camera, shape, css));
    o.position = clip_of(frame.camera, css, 1.0f, depth);
    bool cabin = ((r.v0.x >> BUILDING_WINDOW_RECORD_STYLE_SHIFT) & 3u) == BUILDING_WINDOW_CABIN;
    o.colour = shop    ? building_shop_frame(frame.windows, frame.theme.flags.x)
               : cabin ? building_cabin_frame(frame.windows, frame.theme.flags.x)
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
    BuildingShape shape = window_shape_on(wall, s, shop ? BUILDING_GLAZING_GLASS_LEAD : BUILDING_GLASS_LEAD, r.v0.z);
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

/// The frame's church window record `index`: its header and part `part`'s vectors alone (0 to 2 its arches, 3 its
/// leading), the rest none.
BuildingChurchRecord church_record_at(uint index, uint part) {
    uint at = index * BUILDING_CHURCH_RECORD_VECTORS;
    BuildingChurchRecord r = building_church_empty();
    r.v[0] = church_records[at];
    uint from = building_church_part_at(part);
    uint n = part < 3u ? BUILDING_CHURCH_ARCH_VECTORS : BUILDING_CHURCH_RECORD_VECTORS - BUILDING_CHURCH_LEADING_AT;
    for (uint i = 0u; i < n; ++i) {
        r.v[from + i] = church_records[at + from + i];
    }
    return r;
}

/// Vertex `v` of church window record `index` (building_church_part's): its stone's, reveal's and glass's fans of 10
/// triangles (BUILDING_ARCH_VERTICES each) at the reveals', glass's and frames' leads over its wall's cells, then each
/// segment of its leading (its mullion, then its transoms; past its last none) a bar of two triangles at the sash bars'
/// lead, at its path's slot under the painter switch: the stones' colour, its wall's reveals', its glass with the
/// day's veil folded in (window_glass_fs: its gradient over its wall's church windows' glass's box), the leading's; an
/// instance a record.
[shader("vertex")]
GlassOut church_window_vs(uint v : SV_VulkanVertexID, uint index : SV_VulkanInstanceID) {
    uint part = building_church_part(v);
    BuildingChurchRecord r = church_record_at(index, part);
    GlassOut o;
    o.top = float4(0.0f);
    o.bottom = float4(0.0f);
    if (r.v[0].x == 0u) {
        o.position = NOWHERE;
        return o;
    }
    BuildingWallRecord wall = window_wall_at(r.v[0].y);
    uint theme_flags = frame.theme.flags.x;
    BuildingShape shape = building_nothing();
    float2 css = float2(0.0f);
    float3 colour = building_church_stone(theme_flags);
    if (part < 3u) {
        BuildingArch a = building_church_arch(r, part);
        if (a.count == 0u) {
            o.position = NOWHERE;
            return o;
        }
        float lead = part == 0u ? BUILDING_REVEAL_LEAD : (part == 1u ? BUILDING_GLASS_LEAD : BUILDING_WINDOW_LEAD);
        shape = building_arch_as_shape(a, wall.plane, lead);
        css = building_arch_fan_point(a, v - part * BUILDING_ARCH_VERTICES);
        if (part == 1u) {
            colour = building_bgr(wall.reveal);
        }
    } else {
        uint i = v - BUILDING_CHURCH_ARCH_VERTICES;
        uint j = i / BUILDING_CHURCH_SEGMENT_VERTICES;
        if (!building_church_leading_drawn(r) || j > r.v[0].w) {
            o.position = NOWHERE;
            return o;
        }
        BuildingWindowShape bar = building_sash_bar(building_church_segment(frame.camera, wall.plane, r, j), 0u,
                                                    0.5f * wall.leading);
        if (bar.count == 0u) {
            o.position = NOWHERE;
            return o;
        }
        shape = building_window_as_shape(bar, wall.plane, BUILDING_SASH_LEAD);
        css = building_window_corner(bar, building_bar_corner(i - j * BUILDING_CHURCH_SEGMENT_VERTICES));
        colour = building_church_leading(theme_flags);
    }
    // the paths' slots: the stones', the reveals', the glass's, (the veil's), the leading's
    shape.slot = r.v[0].z + (part < 3u ? part : 4u);
    shape.depth = painter_depth(frame.camera, shape.slot);
    float depth = building_depth(frame.camera, shape, building_plane_recip(frame.camera, shape, css));
    o.position = clip_of(frame.camera, css, 1.0f, depth);
    if (part == 2u) {
        BuildingGlass g = building_church_veiled(frame.windows, theme_flags);
        o.top = float4(g.top, wall.dark.x);
        o.bottom = g.gradient ? float4(g.bottom, wall.dark.y) : float4(g.top, wall.dark.x);
    } else {
        o.top = float4(colour, 0.0f);
        o.bottom = float4(colour, 0.0f);
    }
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
    float3 c = mat_colour(frame.camera, building_glass_at(g, v.top.w, v.bottom.w, y), v.position.xy, mat_grain_on(frame));
    o.colour = float4(c, 1.0f);
    return o;
}

/// The FillIn board's hatch over the building the app asks about, before its answer (CP/frame/Asked.hpp): the pattern's
/// #8C857A lines, 2.2 CSS px wide every 7 (its rotate(45) of vertical lines at x = 7k), over the face's colour at the
/// output's pixel `position`, each pixel the part of it a line covers (its distance from the line's middle).
float3 building_hatch(FrameCamera c, float3 colour, float2 position) {
    float2 css = css_of_output(c, position);
    float u = (css.x + css.y) * 0.70710678f; // the pattern's x
    float d = abs(u - 7.0f * round(u / 7.0f)) * c.frame.z;
    float cover = saturate(1.1f * c.frame.z - d + 0.5f);
    return lerp(colour, float3(140.0f, 133.0f, 122.0f) / 255.0f, cover);
}

[shader("fragment")]
BuildingTarget building_fs(BuildingOut v) {
    BuildingTarget o;
    // mat's grain on the walls and roofs, where the frame's fields are bound and the moment has weather
    float3 c = mat_colour(frame.camera, v.colour, v.position.xy, mat_grain_on(frame));
    if (v.asked != 0u) {
        c = building_hatch(frame.camera, c, v.position.xy);
    }
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
    o.colour = float4(mat_colour(frame.camera, v.colour, v.position.xy, mat_grain_on(frame)), 1.0f);
    o.coverage = mask;
    return o;
}

/// The frame's material fill record `index`.
BuildingMaterialFillRecord material_fill_at(uint index) {
    uint at = index * BUILDING_MATERIAL_FILL_VECTORS;
    BuildingMaterialFillRecord r;
    r.v0 = material_fills[at];
    r.v1 = material_fills[at + 1u];
    r.v2 = material_fills[at + 2u];
    r.v3 = material_fills[at + 3u];
    return r;
}

/// The frame's material line record `index`.
BuildingMaterialLineRecord material_line_at(uint index) {
    uint at = index * BUILDING_MATERIAL_LINE_VECTORS;
    BuildingMaterialLineRecord r;
    r.v0 = material_lines[at];
    r.v1 = material_lines[at + 1u];
    r.v2 = material_lines[at + 2u];
    return r;
}

/// A material face's fragments: its paint at its box's top and bottom (as the glass's: rgb, then the CSS y), and its
/// opacity.
struct MaterialOut {
    float4 position : SV_Position;
    nointerpolation float4 top : GLASS_TOP;
    nointerpolation float4 bottom : GLASS_BOTTOM;
    nointerpolation float opacity : MATERIAL_OPACITY;
};

/// Vertex `v` of material fill record `index`: its shape's fan of 3 triangles from its first corner (its last corner
/// repeated past its count), on its wall's plane at its material's lead, at its path's slot under the painter switch,
/// in its paint (by day a glass wall's the gradient `glass` over its own box) at its opacity; an instance a record.
[shader("vertex")]
MaterialOut material_fill_vs(uint v : SV_VulkanVertexID, uint index : SV_VulkanInstanceID) {
    BuildingMaterialFillRecord r = material_fill_at(index);
    MaterialOut o;
    o.top = float4(0.0f);
    o.bottom = float4(0.0f);
    o.opacity = 0.0f;
    BuildingWindowShape s = building_material_fill_shape(r);
    if (s.count == 0u) {
        o.position = NOWHERE;
        return o;
    }
    BuildingWallRecord wall = window_wall_at(r.v0.y);
    BuildingShape shape = window_shape_on(wall, s, building_material_lead((r.v0.x >> 4u) & 7u), r.v0.z);
    uint t = v / 3u;
    uint c = v - t * 3u;
    uint corner = c == 0u ? 0u : t + c;
    float2 css = building_window_corner(s, corner < s.count ? corner : s.count - 1u);
    float depth = building_depth(frame.camera, shape, building_plane_recip(frame.camera, shape, css));
    o.position = clip_of(frame.camera, css, 1.0f, depth);
    BuildingMaterialPaint p = building_material_fill(frame.windows, frame.theme.flags.x, (r.v0.x >> 4u) & 7u);
    float top = s.p0.y;
    float bottom = s.p0.y;
    for (uint i = 1u; i < s.count; ++i) {
        float y = building_window_corner(s, i).y;
        top = min(top, y);
        bottom = max(bottom, y);
    }
    o.top = float4(p.paint.top, top);
    o.bottom = p.paint.gradient ? float4(p.paint.bottom, bottom) : o.top;
    o.opacity = p.opacity;
    return o;
}

/// Vertex `v` of material line record `index`: its line stroked its width either side, butt-capped (a quad: two
/// triangles), on its wall's plane at its material's lead, at its path's slot under the painter switch, in its colour at
/// its opacity; an instance a record.
[shader("vertex")]
MaterialOut material_line_vs(uint v : SV_VulkanVertexID, uint index : SV_VulkanInstanceID) {
    BuildingMaterialLineRecord r = material_line_at(index);
    MaterialOut o;
    o.top = float4(0.0f);
    o.bottom = float4(0.0f);
    o.opacity = 0.0f;
    uint material = (r.v0.x >> 4u) & 7u;
    BuildingWindowShape bar =
        building_material_bar(building_material_line_of(r), 0.5f * building_material_width(material, r.v0.w));
    if (bar.count == 0u) {
        o.position = NOWHERE;
        return o;
    }
    BuildingWallRecord wall = window_wall_at(r.v0.y);
    BuildingShape shape = window_shape_on(wall, bar, building_material_lead(material), r.v0.z);
    uint corner = v < 3u ? v : (v == 3u ? 0u : v - 2u);
    float2 css = building_window_corner(bar, corner);
    float depth = building_depth(frame.camera, shape, building_plane_recip(frame.camera, shape, css));
    o.position = clip_of(frame.camera, css, 1.0f, depth);
    BuildingMaterialPaint p = building_material_lines(frame.theme.flags.x, material, r.v0.w);
    o.top = float4(p.paint.top, 0.0f);
    o.bottom = o.top;
    o.opacity = p.opacity;
    return o;
}

/// A material face's fragments: its paint at the pixel (a gradient's from its box's top to its bottom, as the glass's),
/// with mat's grain as the objects take it, at its opacity over what lies under it (premultiplied, source over).
[shader("fragment")]
BuildingTarget material_fs(MaterialOut v) {
    BuildingTarget o;
    BuildingGlass g;
    g.top = v.top.xyz;
    g.bottom = v.bottom.xyz;
    g.gradient = v.bottom.w > v.top.w;
    float y = css_of_output(frame.camera, v.position.xy).y;
    float3 c = mat_colour(frame.camera, building_glass_at(g, v.top.w, v.bottom.w, y), v.position.xy, mat_grain_on(frame));
    o.colour = float4(c * v.opacity, v.opacity);
    return o;
}

/// A hoist beam's fragments: its segment's ends (CSS px) and half its width, with which beam_fs keeps the samples it
/// covers.
struct BeamOut {
    float4 position : SV_Position;
    nointerpolation float4 ends : BEAM_ENDS;
    nointerpolation float half : BEAM_HALF;
};

/// Vertex `v` of beam record `index`: its quad (two triangles over building_beam_corner's), at its true depth along it
/// with the sills' lead, or at its slot's painter depth under the painter switch; an instance a record.
[shader("vertex")]
BeamOut beam_vs(uint v : SV_VulkanVertexID, uint index : SV_VulkanInstanceID) {
    uint at = index * BUILDING_BEAM_VECTORS;
    BuildingBeam b = building_beam(frame.camera, beams[at], beams[at + 1u], beams[at + 2u], beams[at + 3u],
                                   beams[at + 4u], beams[at + 5u]);
    BeamOut o;
    o.ends = float4(0.0f);
    o.half = 0.0f;
    if (!b.drawn) {
        o.position = NOWHERE;
        return o;
    }
    uint corner = v < 3u ? v : (v == 3u ? 0u : v - 2u);
    float2 css = building_beam_corner(b, corner);
    float recip = building_beam_recip(b, css);
    float depth = (frame.camera.flags.x & CAMERA_PAINTER) != 0u
                      ? painter_depth(frame.camera, b.slot)
                      : true_depth(frame.camera, recip + recip * BUILDING_SILL_LEAD);
    o.position = clip_of(frame.camera, css, 1.0f, depth);
    o.ends = float4(b.p0, b.p1);
    o.half = b.half;
    return o;
}

/// A hoist beam's fragments: the samples within half its width of its segment (its round caps), in its colour with
/// mat's grain as the objects take it.
[shader("fragment")]
FrameTarget beam_fs(BeamOut v) {
    BuildingBeam b;
    b.drawn = true;
    b.p0 = v.ends.xy;
    b.p1 = v.ends.zw;
    b.r0 = 0.0f;
    b.r1 = 0.0f;
    b.half = v.half;
    b.slot = 0u;
    float2 corner = floor(v.position.xy);
    uint n = push.at.x;
    uint mask = 0u;
    for (uint i = 0u; i < n; ++i) {
        float2 at = n == 4u ? window_samples4[i] : (n == 2u ? window_samples2[i] : float2(0.5f, 0.5f));
        if (building_beam_covers(b, css_of_output(frame.camera, corner + at))) {
            mask |= 1u << i;
        }
    }
    if (mask == 0u) {
        discard;
    }
    FrameTarget o;
    o.colour = float4(mat_colour(frame.camera, building_rgb(BUILDING_BEAM_RGB), v.position.xy, mat_grain_on(frame)),
                      1.0f);
    o.coverage = mask;
    return o;
}
