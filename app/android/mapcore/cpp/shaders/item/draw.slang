// The region items' pipelines (ENGINE.md step 4, "Trees and things"; CP/passes/Items.cpp; shaders/item/items.slangh):
// before the main pass items_cs decides each drawn item once, a thread a job, from its words in the points heap (set
// 1, binding 0), the frame's tones and the job's own words (set 2), into its records: the faces (its towers' bands,
// its boxes' faces, a turbine's tower and blades), the opaque parts (a turbine's blades' strokes and hub, a mast's
// glow's core, a power line's tower) and the translucent ones (a turbine's night light, a mast's glow, a power line's
// conductors and its tower while it fades). items_faces_vs draws each face as its fan and, stroked, its edges' quads,
// whose fragments items_faces_fs keeps where they lie within the stroke, sample by sample; items_parts_vs draws a
// segment or a circle as a box about it whose fragments items_parts_fs keeps where they lie in it; items_sides_fs blends
// the translucent ones, premultiplied, a conductor's segment leaving the samples its path's earlier segments cover. Every
// corner at its own depth, with mat's grain.

#include "shared/frame.slang"
#include "shared/painter.slang"

// K17a's mat: the items are in the renders' objects group (`<g filter="url(#mat)">` where the moment has weather).
#define ATLAS_FX_FUNCTIONS
#include "fx/mat.slang"

#include "tree/tree.slangh"
#include "item/items.slangh"

[[vk::binding(0, 0)]] ConstantBuffer<FrameBlock> frame;
[[vk::binding(0, 1)]] StructuredBuffer<uint4> points_heap;

// Set 2: the jobs (written by the CPU), the tones, the jobs' own words, the faces, the opaque parts and the translucent
// records as the compute stage writes them and as the vertex and fragment stages read them.
[[vk::binding(0, 2)]] StructuredBuffer<uint4> item_jobs;
[[vk::binding(1, 2)]] StructuredBuffer<uint> item_tones;
[[vk::binding(2, 2)]] StructuredBuffer<uint> item_extra;
[[vk::binding(3, 2)]] RWStructuredBuffer<uint4> item_faces_out;
[[vk::binding(4, 2)]] StructuredBuffer<uint4> item_faces;
[[vk::binding(5, 2)]] RWStructuredBuffer<uint4> item_parts_out;
[[vk::binding(6, 2)]] StructuredBuffer<uint4> item_parts;
[[vk::binding(7, 2)]] RWStructuredBuffer<uint4> item_sides_out;
[[vk::binding(8, 2)]] StructuredBuffer<uint4> item_sides;

/// The push constants: x the jobs (items_cs) or the samples a pixel (the fragment stages).
struct ItemPush {
    uint4 at;
};
[[vk::push_constant]] ConstantBuffer<ItemPush> push;

/// The jobs a row of workgroups covers (64 a workgroup).
static const uint ITEM_JOB_ROW = 65535u;

void put(RWStructuredBuffer<uint4> out, uint at, ItemRecord r) {
    out[at * ITEM_RECORD_VECTORS] = r.a;
    out[at * ITEM_RECORD_VECTORS + 1u] = r.b;
    out[at * ITEM_RECORD_VECTORS + 2u] = r.c;
    out[at * ITEM_RECORD_VECTORS + 3u] = r.d;
    out[at * ITEM_RECORD_VECTORS + 4u] = r.e;
}

ItemRecord record_at(StructuredBuffer<uint4> list, uint at) {
    ItemRecord r;
    r.a = list[at * ITEM_RECORD_VECTORS];
    r.b = list[at * ITEM_RECORD_VECTORS + 1u];
    r.c = list[at * ITEM_RECORD_VECTORS + 2u];
    r.d = list[at * ITEM_RECORD_VECTORS + 3u];
    r.e = list[at * ITEM_RECORD_VECTORS + 4u];
    return r;
}

float tone_float(uint i) {
    return asfloat(item_tones[i]);
}

/// A point at the camera: its camera coordinates and whether Camera.proj gives it (nearer than the near plane: no).
struct ItemSeen {
    float3 k;
    bool ahead;
};

ItemSeen seen(FrameCamera c, float3 eye) {
    ItemSeen s;
    s.k = camera_of(c, eye);
    s.ahead = tree_ahead(c, s.k);
    return s;
}

void tower(FrameCamera c, ItemJob j, ItemWords w, float3 foot) {
    bool cab = (w.flags & ITEM_CAB) != 0u;
    bool high = (j.flags & ITEM_JOB_HIGH) != 0u;
    float h = w.h;
    // rs = 3.2 if cab else max(1.2, H / 30); band(0, H - (14 if cab else 0), rs, shaft), then a cab's
    float rs = cab ? 3.2f : max(1.2f, h / 30.0f);
    uint n = cab ? 5u : 1u;
    for (uint k = 0u; k < n; ++k) {
        float y0 = 0.0f;
        float y1 = cab ? h - 14.0f : h;
        float r = rs;
        uint tone = 0u;
        if (k == 1u) {
            y0 = h - 14.0f;
            y1 = h - 11.0f;
            r = rs * 2.3f;
        } else if (k == 2u) {
            y0 = h - 11.0f;
            y1 = h - 4.5f;
            r = rs * 2.6f;
            tone = 1u;
        } else if (k == 3u) {
            y0 = h - 4.5f;
            y1 = h - 3.2f;
            r = rs * 2.7f;
            tone = 2u;
        } else if (k == 4u) {
            y0 = h - 3.2f;
            y1 = h;
            r = 0.5f;
            tone = 3u;
        }
        ItemPoly f = item_band_poly(c, foot, y0, y1, r);
        if (f.count == 0u) {
            continue;
        }
        uint t = ITEM_TONE_TOWER + 3u * tone;
        put(item_faces_out, j.faces + k,
            item_band(c, f, j.slot + k, k, item_tones[t], item_tones[t + 1u], item_tones[t + 2u], high));
    }
}

/// A mast's, a gantry's or a conveyor's boxes, in draw_items' order (a mast's as listed, the others' from the job's
/// own words), each visible face filled and stroked .25 in its colour by its normal.
void boxes(FrameCamera c, ItemJob j, ItemWords w, float3 a, float3 b, uint kind) {
    uint gaps = uint(w.extra);
    uint n = item_part_total(kind, w.flags, gaps);
    for (uint s = 0u; s < n; ++s) {
        uint k = kind == ITEM_MAST ? s : item_extra[j.extra + ITEM_EXTRA_COLOURS + s];
        ItemPart p = item_part(c, kind, w.flags, gaps, a, b, w.h, k);
        for (uint face = 0u; face < 5u; ++face) {
            ItemPoly f = item_box_face(c, p.box, face);
            if (f.count == 0u) {
                continue;
            }
            uint colour = item_extra[j.extra + face * 2u + (p.second ? 1u : 0u)];
            uint slot = j.slot + s * 5u + face;
            put(item_faces_out, j.faces + s * 5u + face, item_face(c, f, slot, face, colour, 0.125f));
        }
    }
}

void mast(FrameCamera c, ItemJob j, ItemWords w, float3 foot) {
    boxes(c, j, w, foot, foot, ITEM_MAST);
    // at night a lighting mast's glow, r '%.1f' % max(2.0, 9.0 · focal / d), and its core, '%.1f' % max(0.6, 1.2 ·
    // focal / d), at hp = proj((x, H, z))
    if ((j.flags & ITEM_JOB_NIGHT) == 0u || (w.flags & ITEM_LIGHTING) == 0u) {
        return;
    }
    float3 top = {foot.x, foot.y + w.h, foot.z};
    ItemSeen hp = seen(c, top);
    if (!hp.ahead) {
        return;
    }
    float2 at = tree_printed(c, css_of(c, hp.k));
    put(item_sides_out, j.sides, item_glow(at, j.a, item_depth(c, hp.k.z, j.slot + 10u, 0u)));
    put(item_parts_out, j.parts,
        item_circle(at, j.b, item_tones[ITEM_TONE_CORE], 1.0f, item_depth(c, hp.k.z, j.slot + 11u, 1u)));
}

void turbine(FrameCamera c, ItemJob j, ItemWords w, float3 foot) {
    float h = w.h;
    float rr = w.extra;
    float s = j.a;
    // base, top = cam.proj((x, 0, z)), cam.proj((x, H, z)): nothing where either lies nearer than the near plane
    float3 up = {foot.x, foot.y + h, foot.z};
    ItemSeen base = seen(c, foot);
    ItemSeen top = seen(c, up);
    if (!base.ahead || !top.ahead) {
        return;
    }
    float2 pb = css_of(c, base.k);
    float2 pt = css_of(c, top.k);
    // its tower: M base - 2.2 s, base + 2.2 s, top + 1.2 s, top - 1.2 s Z
    ItemPoly t = item_poly_none();
    t.count = 4u;
    float2 t0 = {pb.x - 2.2f * s, pb.y};
    float2 t1 = {pb.x + 2.2f * s, pb.y};
    float2 t2 = {pt.x + 1.2f * s, pt.y};
    float2 t3 = {pt.x - 1.2f * s, pt.y};
    t.p0 = t0;
    t.p1 = t1;
    t.p2 = t2;
    t.p3 = t3;
    t.z0 = base.k.z;
    t.z1 = base.k.z;
    t.z2 = top.k.z;
    t.z3 = top.k.z;
    put(item_faces_out, j.faces, item_face(c, t, j.slot, 0u, item_tones[ITEM_TONE_TURBINE], 0.0f));
    // the hub 2 m upwind, the rotor across the wind: rx, rz = -fz, fx
    float fx = tone_float(ITEM_TONE_WIND);
    float fz = tone_float(ITEM_TONE_WIND + 1u);
    float3 hub = {foot.x + fx * 2.0f, foot.y + h, foot.z + fz * 2.0f};
    float rx = -fz;
    float rz = fx;
    ItemSeen ph = seen(c, hub);
    float half = tone_float(ITEM_TONE_BLADE_WIDTH) * 0.5f;
    for (uint k = 0u; k < 3u; ++k) {
        uint at = ITEM_TONE_BLADES + 4u * k;
        float ca = tone_float(at);
        float sa = tone_float(at + 1u);
        float cm = tone_float(at + 2u);
        float sm = tone_float(at + 3u);
        float3 tip = {hub.x + rx * ca * rr, foot.y + (h + sa * rr), hub.z + rz * ca * rr};
        float3 mid = {hub.x + rx * cm * rr * 0.3f, foot.y + (h + sm * rr * 0.3f), hub.z + rz * cm * rr * 0.3f};
        ItemSeen pk = seen(c, tip);
        ItemSeen pm = seen(c, mid);
        if (!ph.ahead || !pk.ahead || !pm.ahead) {
            continue;
        }
        // M ph L pm L pt Z: filled, then stroked .4 round-joined
        ItemPoly b = item_poly_none();
        b.count = 3u;
        b.p0 = css_of(c, ph.k);
        b.p1 = css_of(c, pm.k);
        b.p2 = css_of(c, pk.k);
        b.z0 = ph.k.z;
        b.z1 = pm.k.z;
        b.z2 = pk.k.z;
        put(item_faces_out, j.faces + 1u + k, item_face(c, b, j.slot + 1u, 0u, item_tones[ITEM_TONE_TURBINE + 1u], 0.0f));
        for (uint e = 0u; e < 3u; ++e) {
            float2 p = item_poly_point(b, e);
            float2 q = item_poly_point(b, (e + 1u) % 3u);
            float zp = item_poly_z(b, e);
            float zq = item_poly_z(b, (e + 1u) % 3u);
            put(item_parts_out, j.parts + 3u * k + e,
                item_segment(tree_printed(c, p), tree_printed(c, q), item_depth(c, zp, j.slot + 2u, 1u),
                             item_depth(c, zq, j.slot + 2u, 1u), half, ITEM_ROUND,
                             item_tones[ITEM_TONE_TURBINE + 2u], 1.0f, 0u));
        }
    }
    if (!ph.ahead) {
        return;
    }
    float2 at = css_of(c, ph.k);
    // the hub: r '%.1f' % max(0.8, 2.2 s); at night its red light at (ph[0], ph[1] - max(1.0, 3 s)), r '%.1f' %
    // max(0.8, 1.2 s), at .9
    put(item_parts_out, j.parts + 9u,
        item_circle(tree_printed(c, at), j.c, item_tones[ITEM_TONE_TURBINE + 3u], 1.0f,
                    item_depth(c, ph.k.z, j.slot + 3u, 2u)));
    if ((j.flags & ITEM_JOB_NIGHT) != 0u) {
        float2 light = {at.x, at.y - j.b};
        put(item_sides_out, j.sides,
            item_circle(tree_printed(c, light), j.d, item_tones[ITEM_TONE_TURBINE + 4u],
                        tone_float(ITEM_TONE_LIGHT_OPACITY), item_depth(c, ph.k.z, j.slot + 4u, 3u)));
    }
}

void power(FrameCamera c, ItemJob j, ItemWords w, float3 a, float3 b) {
    float h = w.h;
    bool fading = (j.flags & ITEM_JOB_FADE) != 0u;
    float fade = fading ? j.b : 1.0f;
    // its tower: M p0 L p1 from (a, 0) to (a, H + 2), '%.2f' % max(0.5, 1.4 s) wide
    float3 top = {a.x, a.y + (h + 2.0f), a.z};
    ItemSeen p0 = seen(c, a);
    ItemSeen p1 = seen(c, top);
    if (p0.ahead && p1.ahead) {
        ItemRecord t = item_segment(tree_printed(c, css_of(c, p0.k)), tree_printed(c, css_of(c, p1.k)),
                                    item_depth(c, p0.k.z, j.slot, 0u), item_depth(c, p1.k.z, j.slot, 0u),
                                    j.a * 0.5f, 0u, item_tones[ITEM_TONE_POWER], fade, 0u);
        if (fading) {
            put(item_sides_out, j.sides, t);
        } else {
            put(item_parts_out, j.parts, t);
        }
    }
    // its conductors: one path at .8 (times the fade), each conductor where its five points project, butt-ended and
    // round-joined
    float half = tone_float(ITEM_TONE_CONDUCTOR_WIDTH) * 0.5f;
    float opacity = tone_float(ITEM_TONE_CONDUCTOR_OPACITY) * fade;
    uint written = 0u;
    for (uint o = 0u; o < 3u; ++o) {
        float off = o == 0u ? -6.0f : (o == 1u ? 0.0f : 6.0f);
        bool all = true;
        ItemSeen q[5];
        for (uint f = 0u; f < 5u; ++f) {
            q[f] = seen(c, item_conductor(a, b, h, w.sag, off, f));
            all = all && q[f].ahead;
        }
        if (!all) {
            continue;
        }
        for (uint f = 0u; f < 4u; ++f) {
            uint caps = (f > 0u ? ITEM_ROUND_START : 0u) | (f < 3u ? ITEM_ROUND_END : 0u);
            ItemRecord r = item_segment(tree_printed(c, css_of(c, q[f].k)), tree_printed(c, css_of(c, q[f + 1u].k)),
                                        item_depth(c, q[f].k.z, j.slot + 1u, 0u),
                                        item_depth(c, q[f + 1u].k.z, j.slot + 1u, 0u), half, caps,
                                        item_tones[ITEM_TONE_POWER + 1u], opacity, written);
            put(item_sides_out, j.sides + 1u + written, r);
            written = written + 1u;
        }
    }
}

[shader("compute")]
[numthreads(64, 1, 1)]
void items_cs(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID) {
    uint index = (group.y * ITEM_JOB_ROW + group.x) * 64u + thread.x;
    if (index >= push.at.x) {
        return;
    }
    uint at = index * ITEM_JOB_VECTORS;
    ItemJob j = item_job(item_jobs[at], item_jobs[at + 1u], item_jobs[at + 2u], item_jobs[at + 3u],
                         item_jobs[at + 4u]);
    ItemWords w = item_words(points_heap[j.item], points_heap[j.item + 1u], points_heap[j.item + 2u]);
    uint kind = j.flags & ITEM_JOB_KIND;
    // every record of the job's starts empty
    uint faces = item_face_count(kind, uint(w.extra));
    for (uint i = 0u; i < faces; ++i) {
        put(item_faces_out, j.faces + i, item_nothing());
    }
    for (uint i = 0u; i < item_part_count(kind); ++i) {
        put(item_parts_out, j.parts + i, item_nothing());
    }
    for (uint i = 0u; i < item_side_count(kind); ++i) {
        put(item_sides_out, j.sides + i, item_nothing());
    }
    FrameCamera c = frame.camera;
    float3 foot = item_eye(j.place, w.foot);
    float3 end_local = {w.end.x, w.foot.y, w.end.y};
    float3 end = item_eye(j.place, end_local);
    if (kind == ITEM_TOWER) {
        tower(c, j, w, foot);
    } else if (kind == ITEM_MAST) {
        mast(c, j, w, foot);
    } else if (kind == ITEM_GANTRY || kind == ITEM_CONVEYOR) {
        boxes(c, j, w, foot, end, kind);
    } else if (kind == ITEM_TURBINE) {
        turbine(c, j, w, foot);
    } else if (kind == ITEM_POWER) {
        power(c, j, w, foot, end);
    }
}

// ---- drawing

struct ItemOut {
    float4 position : SV_Position;
    nointerpolation float3 colour : COLOUR;
    nointerpolation float4 shape : ITEM_SHAPE; // an edge's or a segment's ends; a circle's centre and radius
    nointerpolation uint4 kind : ITEM_KIND;    // the record's kind word, its part (0 a fan, 1 to 5 an edge), its index
    nointerpolation float4 paint : ITEM_PAINT; // a gradient's box (x0, x1); a segment's half width; the opacity
    nointerpolation uint2 stops : ITEM_STOPS;  // a gradient's middle and last stops' colours
};

/// Where a vertex that draws nothing goes: beyond the depth range, so its triangles are clipped away.
static const float4 NOWHERE = {0.0f, 0.0f, 2.0f, 1.0f};

ItemOut nowhere() {
    ItemOut o;
    o.position = NOWHERE;
    o.colour = float3(0.0f);
    o.shape = float4(0.0f);
    o.kind = uint4(0u);
    o.paint = float4(0.0f);
    o.stops = uint2(0u);
    return o;
}

ItemOut place_face(ItemRecord r, uint index, uint vertex) {
    uint kind = r.a.x & ITEM_RECORD_KIND;
    if (kind == ITEM_NONE) {
        return nowhere();
    }
    uint n = item_count(r);
    ItemOut o = nowhere();
    o.colour = tree_rgb(r.a.y);
    float x0 = item_point(r, 0u).x;
    float x1 = x0;
    for (uint i = 1u; i < n; ++i) {
        x0 = min(x0, item_point(r, i).x);
        x1 = max(x1, item_point(r, i).x);
    }
    float4 paint = {x0, x1, asfloat(r.a.w), asfloat(r.a.z)};
    o.paint = paint;
    uint2 stops = {r.a.w, r.e.w};
    o.stops = stops;
    if (vertex < 9u) {
        // the fan: triangles 0 1 2, 0 2 3, 0 3 4
        uint t = vertex / 3u;
        uint corner = vertex % 3u;
        if (t + 2u >= n) {
            return nowhere();
        }
        uint i = corner == 0u ? 0u : t + corner;
        o.position = clip_of(frame.camera, item_point(r, i), 1.0f, item_corner_depth(r, i));
        uint4 k = {r.a.x, 0u, index, 0u};
        o.kind = k;
        return o;
    }
    // an edge's quad (corners 0 1 2, then 0 2 3), a device pixel beyond the stroke
    uint e = (vertex - 9u) / 6u;
    uint q = (vertex - 9u) % 6u;
    if ((r.a.x & ITEM_STROKED) == 0u || e >= n) {
        return nowhere();
    }
    uint corner = q < 3u ? q : (q == 3u ? 0u : q - 2u);
    float2 a = item_point(r, e);
    float2 b = item_point(r, (e + 1u) % n);
    float half = asfloat(r.a.w);
    ItemCorner k = item_segment_corner(a, b, item_corner_depth(r, e), item_corner_depth(r, (e + 1u) % n),
                                  half + frame.camera.device.z, corner);
    o.position = clip_of(frame.camera, k.p, 1.0f, k.depth);
    float4 shape = {a.x, a.y, b.x, b.y};
    o.shape = shape;
    uint4 kw = {r.a.x, 1u + e, index, 0u};
    o.kind = kw;
    return o;
}

ItemOut place_part(ItemRecord r, uint index, uint vertex) {
    uint kind = r.a.x & ITEM_RECORD_KIND;
    if (kind == ITEM_NONE || vertex >= 6u) {
        return nowhere();
    }
    uint corner = vertex < 3u ? vertex : (vertex == 3u ? 0u : vertex - 2u);
    ItemOut o = nowhere();
    o.colour = tree_rgb(r.a.y);
    float extent = asfloat(r.a.w) + frame.camera.device.z;
    float2 a = item_point(r, 0u);
    float d0 = asfloat(r.d.z);
    if (kind == ITEM_SEGMENT) {
        float2 b = item_point(r, 1u);
        ItemCorner k = item_segment_corner(a, b, d0, asfloat(r.d.w), extent, corner);
        o.position = clip_of(frame.camera, k.p, 1.0f, k.depth);
        float4 shape = {a.x, a.y, b.x, b.y};
        o.shape = shape;
    } else {
        float2 u = {(corner == 1u || corner == 2u) ? extent : -extent, corner >= 2u ? extent : -extent};
        o.position = clip_of(frame.camera, a + u, 1.0f, d0);
        float4 shape = {a.x, a.y, asfloat(r.a.w), 0.0f};
        o.shape = shape;
    }
    float4 paint = {0.0f, 0.0f, asfloat(r.a.w), asfloat(r.a.z)};
    o.paint = paint;
    uint4 k = {r.a.x, 0u, index, 0u};
    o.kind = k;
    return o;
}

[shader("vertex")]
ItemOut items_faces_vs(uint vertex : SV_VulkanVertexID) {
    uint index = vertex / ITEM_FACE_VERTICES;
    return place_face(record_at(item_faces, index), index, vertex - index * ITEM_FACE_VERTICES);
}

[shader("vertex")]
ItemOut items_parts_vs(uint vertex : SV_VulkanVertexID) {
    uint index = vertex / ITEM_PART_VERTICES;
    return place_part(record_at(item_parts, index), index, vertex - index * ITEM_PART_VERTICES);
}

[shader("vertex")]
ItemOut items_sides_vs(uint vertex : SV_VulkanVertexID) {
    uint index = vertex / ITEM_PART_VERTICES;
    return place_part(record_at(item_sides, index), index, vertex - index * ITEM_PART_VERTICES);
}

/// Vulkan's standard sample positions in a pixel (D7): 4x, then 2x.
static const float2 item_samples4[4] = {float2(0.375f, 0.125f), float2(0.875f, 0.375f), float2(0.125f, 0.625f),
                                        float2(0.625f, 0.875f)};
static const float2 item_samples2[2] = {float2(0.75f, 0.75f), float2(0.25f, 0.25f)};

struct ItemTarget {
    [[vk::location(0)]] float4 colour : SV_Target0;
    uint coverage : SV_Coverage;
};

float2 sample_at(float2 corner, uint i) {
    uint n = push.at.x;
    float2 at = n == 4u ? item_samples4[i] : (n == 2u ? item_samples2[i] : float2(0.5f, 0.5f));
    return css_of_output(frame.camera, corner + at);
}

/// Whether part record `r` (a segment, a circle or a glow) covers CSS point `s`.
bool part_covers(ItemRecord r, float2 s) {
    uint kind = r.a.x & ITEM_RECORD_KIND;
    float2 a = item_point(r, 0u);
    if (kind == ITEM_SEGMENT) {
        return tree_segment_covers(s, a, item_point(r, 1u), asfloat(r.a.w), r.a.x);
    }
    if (kind == ITEM_CIRCLE || kind == ITEM_GLOW) {
        return tree_circle_covers(s, a, asfloat(r.a.w));
    }
    return false;
}

/// The samples of the fragment at `v` its shape covers: a fan's are the rasteriser's (all kept); an edge's those within
/// the stroke's half width of it (round-capped); a segment's, a circle's and a glow's each sample in it.
uint coverage_of(ItemOut v) {
    uint word = v.kind.x;
    uint kind = word & ITEM_RECORD_KIND;
    if (kind == ITEM_FACE && v.kind.y == 0u) {
        return 0xFFFFFFFFu;
    }
    float2 corner = floor(v.position.xy);
    uint mask = 0u;
    for (uint i = 0u; i < push.at.x; ++i) {
        float2 s = sample_at(corner, i);
        bool covered = false;
        if (kind == ITEM_FACE || kind == ITEM_SEGMENT) {
            uint caps = kind == ITEM_FACE ? ITEM_ROUND : word;
            covered = tree_segment_covers(s, v.shape.xy, v.shape.zw, v.paint.z, caps);
        } else {
            covered = tree_circle_covers(s, v.shape.xy, v.shape.z);
        }
        if (covered) {
            mask |= 1u << i;
        }
    }
    return mask;
}

/// A face: its colour or its gradient at the pixel's centre, with mat's grain, on the samples it covers.
[shader("fragment")]
ItemTarget items_faces_fs(ItemOut v) {
    uint mask = coverage_of(v);
    if (mask == 0u) {
        discard;
    }
    float3 colour = v.colour;
    if ((v.kind.x & ITEM_GRADIENT) != 0u) {
        float2 centre = css_of_output(frame.camera, floor(v.position.xy) + float2(0.5f, 0.5f));
        colour = item_gradient_at(v.colour, tree_rgb(v.stops.x), tree_rgb(v.stops.y), (v.kind.x & ITEM_HIGH) != 0u,
                                  v.paint.x, v.paint.y, centre.x);
    }
    ItemTarget o;
    o.colour = float4(mat_colour(frame.camera, colour, v.position.xy, mat_on(frame)), 1.0f);
    o.coverage = mask;
    return o;
}

/// An opaque part: its colour with mat's grain, on the samples it covers.
[shader("fragment")]
ItemTarget items_parts_fs(ItemOut v) {
    uint mask = coverage_of(v);
    if (mask == 0u) {
        discard;
    }
    ItemTarget o;
    o.colour = float4(mat_colour(frame.camera, v.colour, v.position.xy, mat_on(frame)), 1.0f);
    o.coverage = mask;
    return o;
}

/// A glow's colour and opacity at distance t (0 to 1 of its radius): its three stops, each channel and the opacity
/// interpolated apart.
float4 glow_at(float t) {
    return item_glow_at(tree_rgb(item_tones[ITEM_TONE_GLOW]), tree_rgb(item_tones[ITEM_TONE_GLOW + 1u]),
                        tree_rgb(item_tones[ITEM_TONE_GLOW + 2u]), tone_float(ITEM_TONE_GLOW_OPACITY),
                        tone_float(ITEM_TONE_GLOW_OPACITY + 1u), tone_float(ITEM_TONE_GLOW_OPACITY + 2u), t);
}

/// A translucent part at its opacity (premultiplied), with mat's grain, on the samples it covers that its path's
/// earlier segments do not; a glow at its gradient at the pixel's centre.
[shader("fragment")]
ItemTarget items_sides_fs(ItemOut v) {
    uint mask = coverage_of(v);
    uint back = v.kind.x >> ITEM_BACK_SHIFT;
    if (back > 0u && mask != 0u) {
        float2 corner = floor(v.position.xy);
        for (uint k = 1u; k <= back; ++k) {
            ItemRecord e = record_at(item_sides, v.kind.z - k);
            for (uint i = 0u; i < push.at.x; ++i) {
                if ((mask & (1u << i)) != 0u && part_covers(e, sample_at(corner, i))) {
                    mask &= ~(1u << i);
                }
            }
        }
    }
    if (mask == 0u) {
        discard;
    }
    float3 colour = v.colour;
    float alpha = v.paint.w;
    if ((v.kind.x & ITEM_RECORD_KIND) == ITEM_GLOW) {
        float2 centre = css_of_output(frame.camera, floor(v.position.xy) + float2(0.5f, 0.5f));
        float2 d = centre - v.shape.xy;
        float4 g = glow_at(sqrt(d.x * d.x + d.y * d.y) / v.shape.z);
        colour = g.xyz;
        alpha = g.w;
    }
    ItemTarget o;
    float3 c = mat_colour(frame.camera, colour, v.position.xy, mat_on(frame));
    o.colour = float4(c * alpha, alpha);
    o.coverage = mask;
    return o;
}
