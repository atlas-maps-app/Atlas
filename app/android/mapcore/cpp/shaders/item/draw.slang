// The region items' pipelines (ENGINE.md step 4, "Trees and things"; CP/passes/Items.cpp; shaders/item/items.slangh):
// before the main pass items_cs decides each drawn item once, a thread a job, from its words in the points heap (set
// 1, binding 0), the frame's tones and the job's own words (set 2), into its records: the faces (its towers' bands,
// its boxes' faces, a turbine's tower and blades), the opaque parts (a turbine's blades' strokes and hub, a crane's
// stays, a mast's glow's core, a power line's tower) and the translucent ones (a turbine's night light, a mast's glow, a
// power line's conductors and its tower while it fades). items_faces_vs draws each face as its fan and, stroked, its edges' quads,
// whose fragments items_faces_fs keeps where they lie within the stroke, sample by sample; at true depth the fans draw
// first, alone, through items_fills_fs, which keeps every sample the rasteriser covers and so keeps the early depth
// test and a tiler's hidden-surface removal (ENGINE.md gain 18), then the edges alone; items_parts_vs draws a
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

/// The push constants: x the jobs (items_cs) or the samples a pixel (the fragment stages); y which of a face's
/// triangles items_faces_vs places (0 all, 1 its fan, 2 its edges' quads).
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

/// A crane's, a mast's, a gantry's or a conveyor's boxes, in draw_items' order (a mast's as listed, the others' from
/// the job's own words), each visible face filled and stroked .25 in its colour by its normal.
void boxes(FrameCamera c, ItemJob j, ItemWords w, float3 a, float3 b, uint kind) {
    uint gaps = uint(w.extra);
    uint n = item_part_total(kind, w.flags, gaps);
    for (uint s = 0u; s < n; ++s) {
        uint k = kind == ITEM_MAST ? s : item_extra[j.extra + ITEM_EXTRA_COLOURS + s];
        ItemPart p = item_part(c, w, kind, a, b, k);
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

void crane(FrameCamera c, ItemJob j, ItemWords w, float3 foot) {
    boxes(c, j, w, foot, foot, ITEM_CRANE);
    if ((w.flags & ITEM_YARD) != 0u) {
        return;
    }
    // on a quay its stays, one path after its boxes: M top L e for e in (tip, tail) where both project, butt-ended,
    // '%.2f' % max(0.5, 0.35 · focal / d) wide in the steel unlit, over the boom where they meet it
    ItemSeen top = seen(c, item_stay_point(w, foot, 0u));
    if (!top.ahead) {
        return;
    }
    float2 p0 = tree_printed(c, css_of(c, top.k));
    for (uint e = 1u; e < 3u; ++e) {
        ItemSeen q = seen(c, item_stay_point(w, foot, e));
        if (!q.ahead) {
            continue;
        }
        put(item_parts_out, j.parts + e - 1u,
            item_segment(p0, tree_printed(c, css_of(c, q.k)), item_depth(c, top.k.z, j.slot + 60u, 1u),
                         item_depth(c, q.k.z, j.slot + 60u, 1u), j.a * 0.5f, 0u, item_tones[ITEM_TONE_STEEL], 1.0f,
                         0u));
    }
}

/// A coaster's piece (coasters.draw): its post from the ground up to its second end where the render thread's walk put
/// one, '%.2f' % max(0.5, 0.5 or 0.7 s) wide; a wooden coaster's braces back to the post before (M q0 L pb M q1 L g0,
/// one path at .85), max(0.25, 0.3 s); its dark underside 0.35 s px below it, max(1.1, 1.7 s), and its lit top over it,
/// 0.72 of that, both round-capped; every end at its own depth, the top over the underside.
void coaster(FrameCamera c, ItemJob j, ItemWords w, float3 foot, float3 end) {
    uint kind = (w.flags & ITEM_WOODEN) != 0u ? 1u : ((w.flags & ITEM_MONO) != 0u ? 2u : 0u);
    uint lit = item_tones[ITEM_TONE_COASTER + 2u * kind];
    uint dark = item_tones[ITEM_TONE_COASTER + 2u * kind + 1u];
    float s = j.a;
    float3 pa3 = {foot.x, foot.y + w.h, foot.z};
    float3 pb3 = {end.x, end.y + w.extra, end.z};
    ItemSeen a = seen(c, pa3);
    ItemSeen b = seen(c, pb3);
    if (!a.ahead || !b.ahead) {
        return;
    }
    float2 qa = css_of(c, a.k);
    float2 qb = css_of(c, b.k);
    float2 pb = tree_printed(c, qb);
    if ((j.flags & ITEM_JOB_POST) != 0u) {
        ItemSeen g = seen(c, end);
        if (g.ahead) {
            float2 g0 = tree_printed(c, css_of(c, g.k));
            put(item_parts_out, j.parts,
                item_segment(g0, pb, item_depth(c, g.k.z, j.slot, 0u), item_depth(c, b.k.z, j.slot, 0u), j.c * 0.5f, 0u,
                             dark, 1.0f, 0u));
            if ((j.flags & ITEM_JOB_BRACES) != 0u) {
                // the post before: its foot q0 and its top q1, as this frame projects them
                uint prev = item_extra[j.extra];
                ItemWords pw = item_words(points_heap[prev], points_heap[prev + 1u], points_heap[prev + 2u]);
                float3 local = {pw.end.x, pw.foot.y, pw.end.y};
                float3 pe = item_eye(j.place, local);
                float3 pt = {pe.x, pe.y + pw.extra, pe.z};
                ItemSeen q0 = seen(c, pe);
                ItemSeen q1 = seen(c, pt);
                if (q0.ahead && q1.ahead) {
                    float half = j.d * 0.5f;
                    put(item_sides_out, j.sides,
                        item_segment(tree_printed(c, css_of(c, q0.k)), pb, item_depth(c, q0.k.z, j.slot + 1u, 0u),
                                     item_depth(c, b.k.z, j.slot + 1u, 0u), half, 0u, dark, 0.85f, 0u));
                    put(item_sides_out, j.sides + 1u,
                        item_segment(tree_printed(c, css_of(c, q1.k)), g0, item_depth(c, q1.k.z, j.slot + 1u, 0u),
                                     item_depth(c, g.k.z, j.slot + 1u, 0u), half, 0u, dark, 0.85f, 1u));
                }
            }
        }
    }
    // the track: a dark underside 0.35 s px down, the lit top over it
    float2 ua = {qa.x, qa.y + 0.35f * s};
    float2 ub = {qb.x, qb.y + 0.35f * s};
    put(item_parts_out, j.parts + 1u,
        item_segment(tree_printed(c, ua), tree_printed(c, ub), item_depth(c, a.k.z, j.slot + 2u, 0u),
                     item_depth(c, b.k.z, j.slot + 2u, 0u), j.b * 0.5f, ITEM_ROUND, dark, 1.0f, 0u));
    float top = asfloat(item_extra[j.extra + 1u]);
    put(item_parts_out, j.parts + 2u,
        item_segment(tree_printed(c, qa), pb, item_depth(c, a.k.z, j.slot + 3u, 1u), item_depth(c, b.k.z, j.slot + 3u, 1u),
                     top * 0.5f, ITEM_ROUND, lit, 1.0f, 0u));
}

/// A wood's tree (woods.draw): four clumps about its crown's centre (stage T's from random.Random(id): each its offset
/// and its radius over s), each a dark circle; by sun a mid one 0.3 r toward it, 0.74 the size, and over the first a
/// high glint, 0.36; all on one plane at the tree's depth, each layer leading the one below.
void wood(FrameCamera c, ItemJob j, ItemWords w, float3 foot, uint4 d0, uint4 d1, uint4 d2, uint4 d3) {
    float r = w.end.x / 2.0f;
    float h = w.h;
    float s = j.a;
    uint tone = ITEM_TONE_WOODS + 3u * uint(w.extra);
    float3 centre = {foot.x, foot.y + h * 0.7f, foot.z};
    float kz = camera_of(c, centre).z;
    bool sun = (j.flags & ITEM_JOB_SUN) != 0u;
    float3 l = {tone_float(ITEM_TONE_SUN), tone_float(ITEM_TONE_SUN + 1u), tone_float(ITEM_TONE_SUN + 2u)};
    for (uint k = 0u; k < 4u; ++k) {
        float3 clump = {foot.x + item_draw(d0, d1, d2, d3, 4u * k),
                        foot.y + ((h * 0.7f) + item_draw(d0, d1, d2, d3, 4u * k + 1u)),
                        foot.z + item_draw(d0, d1, d2, d3, 4u * k + 2u)};
        ItemSeen p = seen(c, clump);
        if (!p.ahead) {
            continue;
        }
        float rr = item_draw(d0, d1, d2, d3, 4u * k + 3u) * s;
        float2 pp = css_of(c, p.k);
        put(item_parts_out, j.parts + k,
            item_circle(tree_printed(c, pp), tree_print(c, rr), item_tones[tone], 1.0f, item_depth(c, kz, j.slot + k, 0u)));
        if (!sun) {
            continue;
        }
        float3 lift = {clump.x + l.x * r * 0.3f, clump.y + l.y * r * 0.3f, clump.z + l.z * r * 0.3f};
        ItemSeen q = seen(c, lift);
        if (!q.ahead) {
            continue;
        }
        float2 qq = css_of(c, q.k);
        put(item_parts_out, j.parts + 4u + k,
            item_circle(tree_printed(c, qq), tree_print(c, rr * 0.74f), item_tones[tone + 1u], 1.0f,
                        item_depth(c, kz, j.slot + 4u + k, 1u)));
        if (k == 0u) {
            float2 gl = {qq.x + (qq.x - pp.x) * 0.5f, qq.y + (qq.y - pp.y) * 0.5f};
            put(item_parts_out, j.parts + 8u,
                item_circle(tree_printed(c, gl), tree_print(c, rr * 0.36f), item_tones[tone + 2u], 1.0f,
                            item_depth(c, kz, j.slot + 8u, 2u)));
        }
    }
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
    } else if (kind == ITEM_CRANE) {
        crane(c, j, w, foot);
    } else if (kind == ITEM_MAST) {
        mast(c, j, w, foot);
    } else if (kind == ITEM_GANTRY || kind == ITEM_CONVEYOR) {
        boxes(c, j, w, foot, end, kind);
    } else if (kind == ITEM_TURBINE) {
        turbine(c, j, w, foot);
    } else if (kind == ITEM_POWER) {
        power(c, j, w, foot, end);
    } else if (kind == ITEM_COASTER) {
        coaster(c, j, w, foot, end);
    } else if (kind == ITEM_WOOD) {
        wood(c, j, w, foot, points_heap[j.item + 3u], points_heap[j.item + 4u], points_heap[j.item + 5u],
             points_heap[j.item + 6u]);
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
    // push.at.y: 1 the fans alone, 2 the edges alone, 0 both (under the painter switch)
    if ((push.at.y == 1u && vertex >= 9u) || (push.at.y == 2u && vertex < 9u)) {
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

/// A face's colour at `v`: its own or its gradient's at the pixel's centre, with mat's grain.
float4 face_colour(ItemOut v) {
    float3 colour = v.colour;
    if ((v.kind.x & ITEM_GRADIENT) != 0u) {
        float2 centre = css_of_output(frame.camera, floor(v.position.xy) + float2(0.5f, 0.5f));
        colour = item_gradient_at(v.colour, tree_rgb(v.stops.x), tree_rgb(v.stops.y), (v.kind.x & ITEM_HIGH) != 0u,
                                  v.paint.x, v.paint.y, centre.x);
    }
    return float4(mat_colour(frame.camera, colour, v.position.xy, mat_on(frame)), 1.0f);
}

/// A face: its colour or its gradient at the pixel's centre, with mat's grain, on the samples it covers.
[shader("fragment")]
ItemTarget items_faces_fs(ItemOut v) {
    uint mask = coverage_of(v);
    if (mask == 0u) {
        discard;
    }
    ItemTarget o;
    o.colour = face_colour(v);
    o.coverage = mask;
    return o;
}

struct ItemFill {
    [[vk::location(0)]] float4 colour : SV_Target0;
};

/// A face's fan alone: its colour on every sample the rasteriser covers (coverage_of's own for a fan), with no mask
/// written and nothing discarded.
[shader("fragment")]
ItemFill items_fills_fs(ItemOut v) {
    ItemFill o;
    o.colour = face_colour(v);
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
