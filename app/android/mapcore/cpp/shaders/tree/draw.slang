// The trees' pipelines (ENGINE.md step 4, "Trees and things"; CP/passes/Trees.cpp; shaders/tree/tree.slangh): before
// the main pass trees_cs decides each drawn tree once, a thread a job, from its record in the points heap (set 1,
// binding 0) and the frame's tones (set 2), into its records: its trunk, sunlit side, limbs and circles, each at its
// colour and depth. trees_vs draws each record as two triangles: a quad its own corners, a circle or a round-capped
// limb a box about it whose fragments trees_fs keeps where they lie in it, sample by sample (Vulkan's standard
// positions), opaque, with mat's grain; trees_side_fs draws the trunk's sunlit side translucent (premultiplied). At true
// depth with float depth the quads below depth 1 (tree.slangh's tree_fill: a close tree's trunk, a lamp's cap and
// lantern, a box's top) draw apart, from the fills' list through trees_fills_vs and trees_fills_fs, which writes no mask
// and discards nothing, so a tiler keeps its early depth test and hidden-surface removal for them (ENGINE.md gain 18);
// trees_vs leaves them out where the push says.

#include "shared/frame.slang"
#include "shared/painter.slang"

// K17a's mat: the trees are in the renders' objects group (`<g filter="url(#mat)">` where the moment has weather).
#define ATLAS_FX_FUNCTIONS
#include "fx/mat.slang"

#include "tree/tree.slangh"
#include "tree/things.slangh"

[[vk::binding(0, 0)]] ConstantBuffer<FrameBlock> frame;
[[vk::binding(0, 1)]] StructuredBuffer<uint4> points_heap;
[[vk::binding(6, 1)]] StructuredBuffer<uint2> sun_bits;

// Set 2: the jobs (written by the CPU), the tones, the records as the compute stage writes them and as the vertex
// stage reads them, the sunlit sides alike, and the fills' list (written by the CPU: each a record's index, in the
// records' order).
[[vk::binding(0, 2)]] StructuredBuffer<uint4> tree_jobs;
[[vk::binding(1, 2)]] StructuredBuffer<uint> tree_tones;
[[vk::binding(2, 2)]] RWStructuredBuffer<uint4> tree_records_out;
[[vk::binding(3, 2)]] StructuredBuffer<uint4> tree_records;
[[vk::binding(4, 2)]] RWStructuredBuffer<uint4> tree_sides_out;
[[vk::binding(5, 2)]] StructuredBuffer<uint4> tree_sides;
[[vk::binding(6, 2)]] StructuredBuffer<uint> tree_fills;

/// The push constants: x the jobs (trees_cs) or the samples a pixel (the fragment stages); y 1 where trees_vs leaves
/// out the records the plain fills draw (tree_fill), 0 where it draws them all.
struct TreePush {
    uint4 at;
};
[[vk::push_constant]] ConstantBuffer<TreePush> push;

/// The jobs a row of workgroups covers (64 a workgroup).
static const uint TREE_JOB_ROW = 65535u;

void put(uint at, TreeRecord r) {
    tree_records_out[at * TREE_RECORD_VECTORS] = r.a;
    tree_records_out[at * TREE_RECORD_VECTORS + 1u] = r.b;
    tree_records_out[at * TREE_RECORD_VECTORS + 2u] = r.c;
}

TreeRecord written(uint at) {
    TreeRecord r;
    r.a = tree_records_out[at * TREE_RECORD_VECTORS];
    r.b = tree_records_out[at * TREE_RECORD_VECTORS + 1u];
    r.c = tree_records_out[at * TREE_RECORD_VECTORS + 2u];
    return r;
}

void put_side(uint at, TreeRecord r) {
    tree_sides_out[at * TREE_RECORD_VECTORS] = r.a;
    tree_sides_out[at * TREE_RECORD_VECTORS + 1u] = r.b;
    tree_sides_out[at * TREE_RECORD_VECTORS + 2u] = r.c;
}

TreeClump clump_at(TreeJob j, uint i) {
    return tree_clump(points_heap[j.tree + TREE_HEADER_VECTORS + i]);
}

TreeClump blob_at(TreeJob j, uint i) {
    return tree_clump(points_heap[j.tree + TREE_HEADER_VECTORS + TREE_CLUMPS + i]);
}

/// The crown's sun: how lit its tones draw, and whether it takes the highlight tone.
struct TreeSun {
    float amount;
    bool lit;
};

TreeSun sun_of(TreeJob j, bool night) {
    bool has = j.sun != TREE_NO_BITS;
    uint2 texel = {0u, 0u};
    if (has) {
        texel = sun_bits[j.sun + j.sample / 32u];
    }
    TreeSun s;
    s.amount = tree_sun_amount(j.sample, texel, has, asfloat(j.weight), night);
    s.lit = tree_sunlit(j.sample, texel, has, night);
    return s;
}

/// A small crown: one circle, or its shade and its lit side (tree_close's first branch).
void small_tree(FrameCamera c, TreeJob j, TreeHeader t) {
    float3 k = tree_camera(c, j.place, tree_local(t, 0.0f, t.h * 0.65f, 0.0f));
    if (!tree_ahead(c, k)) {
        return;
    }
    float kz = max(k.z, c.frame.w);
    float2 p = css_of(c, k);
    float rr = j.w0;
    if ((j.flags & TREE_JOB_ONE) != 0u) {
        put(j.first, tree_circle(tree_printed(c, p), tree_print(c, rr), tree_tones[j.tint + TREE_TONE_SMALL_MIX], 1.0f,
                                 tree_depth(c, kz, j.slot, 0u)));
        return;
    }
    // the nearer (its second) first, so early depth testing rejects what it hides of the other
    put(j.first + 1u, tree_circle(tree_printed(c, p), tree_print(c, rr), tree_tones[j.tint + TREE_TONE_SMALL_SHADE],
                                  1.0f, tree_depth(c, kz, j.slot, 0u)));
    float2 q = {tree_print(c, p.x - rr * 0.2f), tree_print(c, p.y - rr * 0.25f)};
    put(j.first, tree_circle(q, tree_print(c, rr * 0.62f), tree_tones[j.tint + TREE_TONE_SMALL_LIT], 1.0f,
                             tree_depth(c, kz, j.slot, 1u)));
}

/// tree_close's trunk, limbs and crown.
void close_tree(FrameCamera c, TreeJob j, TreeHeader t) {
    bool night = (j.flags & TREE_JOB_NIGHT) != 0u;
    float3 k0 = tree_camera(c, j.place, tree_local(t, 0.0f, 0.0f, 0.0f));
    float3 k1 = tree_camera(c, j.place, tree_local(t, 0.0f, t.top, 0.0f));
    if (!tree_ahead(c, k0) || !tree_ahead(c, k1)) {
        return;
    }
    float3 kc = tree_camera(c, j.place, tree_local(t, 0.0f, t.cy, 0.0f));
    float kz = max(kc.z, c.frame.w);
    float2 c0 = css_of(c, k0);
    float2 c1 = css_of(c, k1);
    uint bark = tree_tones[TREE_TONE_BARK];
    // the records nearest first, so early depth testing rejects what each hides: the crown's circles from its nearest
    // (each clump's highlight, lit tone, then its own), then the limbs, then the trunk
    uint nb = (j.flags >> TREE_JOB_NB_SHIFT) & 0xFFu;
    uint limbs_at = j.first + 3u * nb;
    put(limbs_at + 5u,
        tree_quad(tree_trunk_corner(c, c0, c1, j.w0, j.w1, 0u), tree_trunk_corner(c, c0, c1, j.w0, j.w1, 1u),
                  tree_trunk_corner(c, c0, c1, j.w0, j.w1, 2u), tree_trunk_corner(c, c0, c1, j.w0, j.w1, 3u), bark, 1.0f,
                  tree_depth(c, kz, j.slot, 0u)));
    if ((j.flags & TREE_JOB_SIDE) != 0u) {
        float sx = (j.flags & TREE_JOB_RIGHT) != 0u ? 1.0f : -1.0f;
        float2 s0 = tree_side_corner(c, c0, c1, j.w0, j.w1, sx, 0u);
        float2 s1 = tree_side_corner(c, c0, c1, j.w0, j.w1, sx, 1u);
        float2 s2 = tree_side_corner(c, c0, c1, j.w0, j.w1, sx, 2u);
        float2 s3 = tree_side_corner(c, c0, c1, j.w0, j.w1, sx, 3u);
        put_side(j.highlight,
                 tree_quad(s0, s1, s2, s3, tree_tones[TREE_TONE_SIDE], asfloat(tree_tones[TREE_TONE_SIDE_OPACITY]),
                           tree_depth(c, kz, j.slot, 1u)));
    }
    // the clumps kept at the camera, in their order
    float dd[TREE_CLUMPS];
    uint kept[TREE_CLUMPS];
    uint n = 0u;
    for (uint i = 0u; i < nb; ++i) {
        TreeSeen s = tree_seen(c, j.place, t, clump_at(j, i), false);
        if (s.kept) {
            dd[n] = s.k.z;
            kept[n] = i;
            n = n + 1u;
        }
    }
    if (n == 0u) {
        return;
    }
    float big = nb < 12u ? 1.25f : 1.0f;
    // the limbs, from the trunk's top to the five lowest (sorted(cl, key=y)[:5]: a stable sort)
    if ((j.flags & TREE_JOB_LIMBS) != 0u) {
        // each the least (height, place) after the one before, as the stable sort orders them
        float2 top = tree_printed(c, c1);
        float last_y = 0.0f;
        uint last = 0xFFFFFFFFu;
        for (uint l = 0u; l < 5u && l < n; ++l) {
            uint best = 0xFFFFFFFFu;
            float least = 0.0f;
            for (uint m = 0u; m < n; ++m) {
                float y = clump_at(j, kept[m]).y;
                bool after = last == 0xFFFFFFFFu || y > last_y || (y == last_y && m > last);
                if (after && (best == 0xFFFFFFFFu || y < least)) {
                    best = m;
                    least = y;
                }
            }
            last = best;
            last_y = least;
            TreeSeen s = tree_seen(c, j.place, t, clump_at(j, kept[best]), false);
            put(limbs_at + l, tree_segment(top, tree_printed(c, css_of(c, s.k)), 0.5f * j.limb, TREE_ROUND, bark, 1.0f,
                                           tree_depth(c, kz, j.slot, 2u)));
        }
    }
    // the crown: by depth far to near (stable), three slices, four tones
    for (uint a = 1u; a < n; ++a) {
        float d = dd[a];
        uint i = kept[a];
        uint b = a;
        while (b > 0u && dd[b - 1u] < d) {
            dd[b] = dd[b - 1u];
            kept[b] = kept[b - 1u];
            b = b - 1u;
        }
        dd[b] = d;
        kept[b] = i;
    }
    TreeSun sun = sun_of(j, night);
    float3 l = frame.theme.light.xyz;
    uint lit_tone = tree_mix(tree_tones[j.tint + TREE_TONE_LIT], tree_tones[j.tint + TREE_TONE_LIT + 1u], sun.amount);
    uint high_tone =
        tree_mix(tree_tones[j.tint + TREE_TONE_HIGHLIGHT], tree_tones[j.tint + TREE_TONE_HIGHLIGHT + 1u], sun.amount);
    uint third = n / 3u;
    uint two_thirds = (2u * n) / 3u;
    for (uint p = 0u; p < n; ++p) {
        uint slice = p < third ? 0u : (p < two_thirds ? 1u : 2u);
        TreeClump q = clump_at(j, kept[p]);
        TreeSeen s = tree_seen(c, j.place, t, q, false);
        float br = q.br * big;
        float rr = br * c.forward.w / s.k.z;
        uint at = j.first + 3u * (n - 1u - p);
        uint g = q.under ? 0u : 1u;
        uint tone = tree_tones[j.tint + (q.under ? TREE_TONE_UNDER : TREE_TONE_FLANK)];
        put(at + 2u, tree_circle(tree_printed(c, css_of(c, s.k)), tree_print(c, rr), tone, 1.0f,
                            tree_depth(c, kz, j.slot, 3u + 4u * slice + g)));
        float lam = tree_lam(t, q, l, night);
        if (lam > -0.2f) {
            float3 m = tree_toward(c, j.place, t, q, br, l, 0.35f);
            if (tree_ahead(c, m)) {
                put(at + 1u, tree_circle(tree_printed(c, css_of(c, m)), tree_print(c, rr * 0.72f), lit_tone, 1.0f,
                                         tree_depth(c, kz, j.slot, 3u + 4u * slice + 2u)));
            }
        }
        if (lam > 0.35f && sun.lit) {
            float3 m = tree_toward(c, j.place, t, q, br, l, 0.55f);
            if (tree_ahead(c, m)) {
                put(at, tree_circle(tree_printed(c, css_of(c, m)), tree_print(c, rr * 0.36f), high_tone, 1.0f,
                                         tree_depth(c, kz, j.slot, 3u + 4u * slice + 3u)));
            }
        }
    }
    // the crown under its filter (crown_filter): its circles marked, each with the region of the group's box
    float4 box = {1e30f, 1e30f, -1e30f, -1e30f};
    bool any = false;
    for (uint i = j.first; i < j.first + 3u * n; ++i) {
        TreeRecord r = written(i);
        if ((r.a.x & TREE_KIND) == TREE_CIRCLE) {
            box = tree_crown_box(box, r);
            any = true;
        }
    }
    if (any) {
        int4 region = tree_crown_region(box, c.frame.z);
        for (uint i = j.first; i < j.first + 3u * n; ++i) {
            TreeRecord r = written(i);
            if ((r.a.x & TREE_KIND) == TREE_CIRCLE) {
                put(i, tree_crowned(r, region));
            }
        }
    }
}

/// The generic tree, without weather.
void generic_tree(FrameCamera c, TreeJob j, TreeHeader t) {
    bool night = (j.flags & TREE_JOB_NIGHT) != 0u;
    float3 k0 = tree_camera(c, j.place, tree_local(t, 0.0f, 0.0f, 0.0f));
    float3 k1 = tree_camera(c, j.place, tree_local(t, 0.0f, t.h * 0.55f, 0.0f));
    if (!tree_ahead(c, k0) || !tree_ahead(c, k1)) {
        return;
    }
    float3 kc = tree_camera(c, j.place, tree_local(t, 0.0f, t.h * 0.68f, 0.0f));
    float kz = max(kc.z, c.frame.w);
    // the records nearest first: the blobs from the nearest (its lit side, then itself), then the trunk
    uint nb = (j.flags >> TREE_JOB_NB_SHIFT) & 0xFFu;
    put(j.first + 2u * nb, tree_segment(tree_printed(c, css_of(c, k0)), tree_printed(c, css_of(c, k1)), 0.5f * j.w0,
                                        TREE_ROUND, tree_tones[TREE_TONE_GENERIC_TRUNK], 1.0f,
                                        tree_depth(c, kz, j.slot, 0u)));
    float dd[TREE_BLOBS];
    uint kept[TREE_BLOBS];
    uint n = 0u;
    for (uint i = 0u; i < nb; ++i) {
        TreeSeen s = tree_seen(c, j.place, t, blob_at(j, i), true);
        if (s.kept) {
            dd[n] = s.k.z;
            kept[n] = i;
            n = n + 1u;
        }
    }
    for (uint a = 1u; a < n; ++a) {
        float d = dd[a];
        uint i = kept[a];
        uint b = a;
        while (b > 0u && dd[b - 1u] < d) {
            dd[b] = dd[b - 1u];
            kept[b] = kept[b - 1u];
            b = b - 1u;
        }
        dd[b] = d;
        kept[b] = i;
    }
    TreeSun sun = sun_of(j, night);
    float3 l = frame.theme.light.xyz;
    uint dark = tree_tones[TREE_TONE_GENERIC_DARK];
    uint mid = tree_mix(tree_tones[TREE_TONE_GENERIC_MID], tree_tones[TREE_TONE_GENERIC_MID + 1u], sun.amount);
    for (uint p = 0u; p < n; ++p) {
        TreeClump q = blob_at(j, kept[p]);
        TreeSeen s = tree_seen(c, j.place, t, q, true);
        float2 at = css_of(c, s.k);
        float rr = q.br * c.forward.w / s.k.z;
        uint pair = j.first + 2u * (n - 1u - p);
        put(pair + 1u, tree_circle(tree_printed(c, at), tree_print(c, rr), dark, 1.0f, tree_depth(c, kz, j.slot, 1u)));
        if (!night) {
            float3 m = tree_toward(c, j.place, t, q, q.br, l, 1.0f);
            if (tree_ahead(c, m)) {
                float2 sx = css_of(c, m);
                float2 o = {at.x + (sx.x - at.x) * 0.3f, at.y + (sx.y - at.y) * 0.3f};
                put(pair, tree_circle(tree_printed(c, o), tree_print(c, rr * 0.78f), mid, 1.0f,
                                      tree_depth(c, kz, j.slot, 2u)));
            }
        }
    }
}

[shader("compute")]
[numthreads(64, 1, 1)]
void trees_cs(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID) {
    uint index = (group.y * TREE_JOB_ROW + group.x) * 64u + thread.x;
    if (index >= push.at.x) {
        return;
    }
    uint at = index * TREE_JOB_VECTORS;
    TreeJob j = tree_job(tree_jobs[at], tree_jobs[at + 1u], tree_jobs[at + 2u], tree_jobs[at + 3u], tree_jobs[at + 4u]);
    // every record of the job's starts empty
    uint n = tree_record_count(j.flags);
    for (uint i = 0u; i < n; ++i) {
        put(j.first + i, tree_nothing());
    }
    if ((j.flags & TREE_JOB_SIDE) != 0u) {
        put_side(j.highlight, tree_nothing());
    }
    TreeHeader t = tree_header(points_heap[j.tree], points_heap[j.tree + 1u]);
    FrameCamera c = frame.camera;
    if ((j.flags & TREE_JOB_GENERIC) != 0u) {
        generic_tree(c, j, t);
    } else if ((j.flags & TREE_JOB_SMALL) != 0u) {
        small_tree(c, j, t);
    } else {
        close_tree(c, j, t);
    }
}

/// A box (draw_box) about its foot moved `dz` along z: its leg (slot k, where its foot and height project) at `at`, its
/// top (slot k + 1: one quad, or two for a face the near plane cut to five corners) at `at` + 1 and + 2.
void thing_box(FrameCamera c, ThingJob j, float3 foot, float dz, float w, float dd, float h, uint top, uint leg, uint k,
               float kz, uint at) {
    float3 k0 = tree_camera(c, j.place, thing_point(foot, 0.0f, 0.0f, dz));
    float3 k1 = tree_camera(c, j.place, thing_point(foot, 0.0f, h, dz));
    if (tree_ahead(c, k0) && tree_ahead(c, k1)) {
        put(at, tree_segment(tree_printed(c, css_of(c, k0)), tree_printed(c, css_of(c, k1)), 0.5f, 0u, leg, 1.0f,
                             tree_depth(c, kz, j.slot, k)));
    }
    float hw = w * 0.5f;
    float hd = dd * 0.5f;
    ThingFace f = thing_face(c, j.place, thing_point(foot, -hw, h, dz - hd), thing_point(foot, hw, h, dz - hd),
                             thing_point(foot, hw, h, dz + hd), thing_point(foot, -hw, h, dz + hd));
    if (f.count < 3u) {
        return;
    }
    float depth = tree_depth(c, kz, j.slot, k + 1u);
    float2 q0 = tree_printed(c, f.p0);
    float2 q1 = tree_printed(c, f.p1);
    float2 q2 = tree_printed(c, f.p2);
    float2 q3 = f.count > 3u ? tree_printed(c, f.p3) : q2;
    put(at + 1u, tree_quad(q0, q1, q2, q3, top, 1.0f, depth));
    if (f.count > 4u) {
        float2 q4 = tree_printed(c, f.p4);
        put(at + 2u, tree_quad(q0, q3, q4, q4, top, 1.0f, depth));
    }
}

/// draw_lamp: its mast, lantern and cap (the cap first: the nearest of them), by day its glint.
void thing_lamp(FrameCamera c, ThingJob j, float3 foot, float kz) {
    float3 k0 = tree_camera(c, j.place, foot);
    float3 k1 = tree_camera(c, j.place, thing_point(foot, 0.0f, 4.2f, 0.0f));
    if (!tree_ahead(c, k0) || !tree_ahead(c, k1)) {
        return;
    }
    float2 p0 = css_of(c, k0);
    float2 p1 = css_of(c, k1);
    float hw = j.b;
    float hh = j.c;
    uint streak = (j.flags & THING_JOB_STREAK) != 0u ? 1u : 0u;
    if (streak != 0u) {
        // on a wet night, first: '<ellipse cx="%.1f" cy="%.1f" rx="%.2f" ry="%.1f" fill="url(#streak)" opacity=".75"/>'
        // at p0 + 2.6 s down
        float2 at = {p0.x, p0.y + j.drop};
        put_side(j.side, tree_streak(tree_printed(c, at), j.rx, j.ry, 0.75f, tree_depth(c, kz, j.slot, 0u)));
    }
    put(j.first + 2u, tree_segment(tree_printed(c, p0), tree_printed(c, p1), 0.5f * j.a, TREE_ROUND,
                                   tree_tones[THING_TONE_MAST], 1.0f, tree_depth(c, kz, j.slot, 1u)));
    float2 l0 = {p1.x - hw, p1.y};
    float2 l1 = {p1.x + hw, p1.y};
    float2 l2 = {p1.x + hw * 0.7f, p1.y + hh};
    float2 l3 = {p1.x - hw * 0.7f, p1.y + hh};
    put(j.first + 1u, tree_quad(tree_printed(c, l0), tree_printed(c, l1), tree_printed(c, l2), tree_printed(c, l3),
                                tree_tones[THING_TONE_LANTERN], 1.0f, tree_depth(c, kz, j.slot, 2u)));
    float2 c0 = {p1.x - hw * 1.2f, p1.y};
    float2 c1 = {p1.x + hw * 1.2f, p1.y};
    float2 c2 = {p1.x, p1.y - hh * 0.6f};
    float2 apex = tree_printed(c, c2);
    put(j.first, tree_quad(tree_printed(c, c0), tree_printed(c, c1), apex, apex, tree_tones[THING_TONE_CAP], 1.0f,
                           tree_depth(c, kz, j.slot, 3u)));
    if ((j.flags & THING_JOB_GLINT) != 0u) {
        // 'M%.1f %.1fv%.1f': the second point the first as printed and its printed length down
        float2 g = {p1.x - hw * 0.4f, p1.y + hh * 0.2f};
        float2 g0 = tree_printed(c, g);
        float2 g1 = {g0.x, g0.y + tree_print(c, hh * 0.6f)};
        put_side(j.side, tree_segment(g0, g1, 0.5f * j.d, 0u, tree_tones[THING_TONE_GLINT],
                                      asfloat(tree_tones[THING_TONE_GLINT_OPACITY]), tree_depth(c, kz, j.slot, 4u)));
    }
    if ((j.flags & THING_JOB_HALO) != 0u) {
        // at night its halo: '<circle cx="%.1f" cy="%.1f" r="%.1f" fill="url(#halo)"/>' about p1 + 0.5 hh
        float2 h = {p1.x, p1.y + hh * 0.5f};
        put_side(j.side + streak, tree_halo(tree_printed(c, h), j.d, tree_depth(c, kz, j.slot, 4u)));
    }
}

/// draw_bollard: its post, by day its head.
void thing_bollard(FrameCamera c, ThingJob j, float3 foot, float kz) {
    float3 k0 = tree_camera(c, j.place, foot);
    float3 k1 = tree_camera(c, j.place, thing_point(foot, 0.0f, 0.85f, 0.0f));
    if (!tree_ahead(c, k0) || !tree_ahead(c, k1)) {
        return;
    }
    float2 p1 = css_of(c, k1);
    put(j.first, tree_segment(tree_printed(c, css_of(c, k0)), tree_printed(c, p1), 0.5f * j.a, TREE_ROUND,
                              tree_tones[THING_TONE_BOLLARD], 1.0f, tree_depth(c, kz, j.slot, 0u)));
    if ((j.flags & THING_JOB_HEAD) != 0u) {
        float2 h = {tree_print(c, p1.x - j.b), tree_print(c, p1.y)};
        put_side(j.side, tree_circle(h, j.c, tree_tones[THING_TONE_HEAD], asfloat(tree_tones[THING_TONE_HEAD_OPACITY]),
                                     tree_depth(c, kz, j.slot, 1u)));
    }
}

/// draw_rack: its three arcs, each where all nine of its points project.
void thing_rack(FrameCamera c, ThingJob j, float3 foot, float kz) {
    float half = 0.5f * asfloat(tree_tones[THING_TONE_RACK_WIDTH]);
    float depth = tree_depth(c, kz, j.slot, 0u);
    for (uint k = 0u; k < 3u; ++k) {
        float dx = float(k) - 1.0f; // xx = n['x'] - 1.0 + k
        bool all = true;
        for (uint i = 0u; i < 9u; ++i) {
            float2 a = thing_arc(i);
            all = all && tree_ahead(c, tree_camera(c, j.place, thing_point(foot, dx + a.x, a.y, 0.0f)));
        }
        if (!all) {
            continue;
        }
        float2 a0 = thing_arc(0u);
        float2 last = tree_printed(c, css_of(c, tree_camera(c, j.place, thing_point(foot, dx + a0.x, a0.y, 0.0f))));
        for (uint i = 1u; i < 9u; ++i) {
            float2 a = thing_arc(i);
            float2 next = tree_printed(c, css_of(c, tree_camera(c, j.place, thing_point(foot, dx + a.x, a.y, 0.0f))));
            put(j.first + 8u * k + (i - 1u),
                tree_segment(last, next, half, thing_arc_caps(i - 1u), tree_tones[THING_TONE_RACK], 1.0f, depth));
            last = next;
        }
    }
}

/// railing: a piece's two rails (at 1 m, then 0.12 m over its foot) and its posts between them, each where both of its
/// ends project, round-capped.
void thing_railing(FrameCamera c, ThingJob j, float3 a, float3 b, float kz) {
    float half = 0.5f * j.a;
    uint colour = tree_tones[THING_TONE_RAILING];
    float depth = tree_depth(c, kz, j.slot, 0u);
    for (uint r = 0u; r < 2u; ++r) {
        float y = r == 0u ? 1.0f : 0.12f;
        float3 k0 = tree_camera(c, j.place, thing_point(a, 0.0f, y, 0.0f));
        float3 k1 = tree_camera(c, j.place, thing_point(b, 0.0f, y, 0.0f));
        if (tree_ahead(c, k0) && tree_ahead(c, k1)) {
            put(j.first + r, tree_segment(tree_printed(c, css_of(c, k0)), tree_printed(c, css_of(c, k1)), half,
                                          TREE_ROUND, colour, 1.0f, depth));
        }
    }
    uint m = (j.flags >> THING_JOB_POSTS_SHIFT) & 0xFFu;
    for (uint i = 0u; i <= m; ++i) {
        // f = j / m; px, pz = pa[0] + (pb[0] - pa[0]) * f, pa[1] + (pb[1] - pa[1]) * f
        float f = float(i) / float(m);
        float3 p = {a.x + (b.x - a.x) * f, a.y, a.z + (b.z - a.z) * f};
        float3 k0 = tree_camera(c, j.place, thing_point(p, 0.0f, 0.12f, 0.0f));
        float3 k1 = tree_camera(c, j.place, thing_point(p, 0.0f, 1.0f, 0.0f));
        if (tree_ahead(c, k0) && tree_ahead(c, k1)) {
            put(j.first + 2u + i, tree_segment(tree_printed(c, css_of(c, k0)), tree_printed(c, css_of(c, k1)), half,
                                               TREE_ROUND, colour, 1.0f, depth));
        }
    }
}

[shader("compute")]
[numthreads(64, 1, 1)]
void things_cs(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID) {
    uint index = (group.y * TREE_JOB_ROW + group.x) * 64u + thread.x;
    if (index >= push.at.x) {
        return;
    }
    uint at = index * THING_JOB_VECTORS;
    ThingJob j = thing_job(tree_jobs[at], tree_jobs[at + 1u], tree_jobs[at + 2u], tree_jobs[at + 3u],
                           tree_jobs[at + 4u]);
    // every record of the job's starts empty
    uint n = thing_record_count(j.flags);
    for (uint i = 0u; i < n; ++i) {
        put(j.first + i, tree_nothing());
    }
    for (uint i = 0u; i < thing_side_count(j.flags); ++i) {
        put_side(j.side + i, tree_nothing());
    }
    FrameCamera c = frame.camera;
    float3 foot = thing_foot(points_heap[j.thing]);
    uint kind = j.flags & THING_JOB_KIND;
    if (kind == THING_RAILING) {
        // its plane at its middle, 0.5 m over its foot (the gather's point)
        float3 end = thing_end(points_heap[j.thing + 1u], foot);
        float3 mid = {(foot.x + end.x) * 0.5f, foot.y + 0.5f, (foot.z + end.z) * 0.5f};
        thing_railing(c, j, foot, end, max(tree_camera(c, j.place, mid).z, c.frame.w));
        return;
    }
    float kz = max(tree_camera(c, j.place, thing_point(foot, 0.0f, thing_middle(kind), 0.0f)).z, c.frame.w);
    if (kind == THING_LAMP) {
        thing_lamp(c, j, foot, kz);
    } else if (kind == THING_BENCH) {
        thing_box(c, j, foot, 0.0f, 1.8f, 0.5f, 0.45f, tree_tones[THING_TONE_BENCH], tree_tones[THING_TONE_BENCH + 1u],
                  0u, kz, j.first);
        thing_box(c, j, foot, 0.22f, 1.8f, 0.08f, 0.9f, tree_tones[THING_TONE_BACK], tree_tones[THING_TONE_BACK + 1u],
                  2u, kz, j.first + 3u);
    } else if (kind == THING_BASKET) {
        thing_box(c, j, foot, 0.0f, 0.42f, 0.42f, 0.95f, tree_tones[THING_TONE_BASKET],
                  tree_tones[THING_TONE_BASKET + 1u], 0u, kz, j.first);
    } else if (kind == THING_BOLLARD) {
        thing_bollard(c, j, foot, kz);
    } else {
        thing_rack(c, j, foot, kz);
    }
}

struct TreeOut {
    float4 position : SV_Position;
    nointerpolation float3 colour : COLOUR;
    nointerpolation float4 shape : TREE_SHAPE; // a circle's centre and radius; a limb's two ends
    nointerpolation float4 kind : TREE_KIND;   // its kind and caps, a segment's half width, its opacity
    nointerpolation int4 region : TREE_REGION; // a crown's circle's filter region (device px)
};

/// Whether record word `word` draws under the crown filter this frame: a crown's circle, where `mat` runs (the fields
/// bound in full mode, with weather: the renders' filters).
bool crowned(uint word) {
    return (word & TREE_KIND) == TREE_CIRCLE && (word & TREE_CROWN) != 0u && mat_on(frame);
}

/// Where a record that draws nothing puts its points: beyond the depth range, so its triangles are clipped away.
static const float4 NOWHERE = {0.0f, 0.0f, 2.0f, 1.0f};

/// Corner i (0 to 3) of the box a record's fragments keep their samples from.
float2 corner_of(TreeRecord r, uint i) {
    uint kind = r.a.x & TREE_KIND;
    if (kind == TREE_QUAD) {
        uint4 v = r.c;
        if (i < 2u) {
            v = r.b;
        }
        bool odd = (i & 1u) != 0u;
        float2 p = {asfloat(odd ? v.z : v.x), asfloat(odd ? v.w : v.y)};
        return p;
    }
    // a device pixel's reach beyond the shape (a CSS px or more)
    if (kind == TREE_CIRCLE) {
        float2 at = {asfloat(r.b.x), asfloat(r.b.y)};
        float e = asfloat(r.b.z) + 1.0f;
        if (crowned(r.a.x)) {
            // the crown filter moves its pixels by whole device px, up to its reach
            e = e + float(tree_tones[TREE_TONE_CROWN_REACH]) / frame.camera.frame.z;
        }
        // a streak's ellipse: its own radius along y
        float ey = (r.a.x & TREE_STREAK) != 0u ? asfloat(r.b.w) + 1.0f : e;
        float2 u = {(i == 1u || i == 2u) ? e : -e, i >= 2u ? ey : -ey};
        return at + u;
    }
    float2 a = {asfloat(r.b.x), asfloat(r.b.y)};
    float2 b = {asfloat(r.b.z), asfloat(r.b.w)};
    float e = asfloat(r.c.x) + 1.0f;
    float2 d = b - a;
    float len = sqrt(d.x * d.x + d.y * d.y);
    float2 u = {1.0f, 0.0f};
    if (len > 0.0f) {
        u = d / len;
    }
    float2 v = {-u.y * e, u.x * e};
    float2 from = a - u * e;
    if (i == 1u || i == 2u) {
        from = b + u * e;
    }
    if (i >= 2u) {
        return from + v;
    }
    return from - v;
}

TreeOut place(TreeRecord r, uint vertex) {
    TreeOut o;
    o.shape = float4(0.0f);
    o.kind = float4(0.0f);
    o.region = int4(0);
    if ((r.a.x & TREE_KIND) == TREE_NONE) {
        o.position = NOWHERE;
        o.colour = float3(0.0f);
        return o;
    }
    // two triangles: corners 0 1 2, then 0 2 3
    uint corner = vertex < 3u ? vertex : (vertex == 3u ? 0u : vertex - 2u);
    // flat on the screen at one depth: w = 1, so the depth (a painter slot's exact) and the corners come through the
    // division as they are
    o.position = clip_of(frame.camera, corner_of(r, corner), 1.0f, asfloat(r.a.w));
    o.colour = tree_rgb(r.a.y);
    o.shape = asfloat(r.b);
    o.kind = float4(float(r.a.x), asfloat(r.c.x), asfloat(r.a.z), 0.0f);
    if (crowned(r.a.x)) {
        o.region = int4(asint(r.c.x), asint(r.c.y), asint(r.c.z), asint(r.c.w));
    }
    return o;
}

[shader("vertex")]
TreeOut trees_vs(uint vertex : SV_VulkanVertexID) {
    uint index = vertex / TREE_RECORD_VERTICES;
    uint at = index * TREE_RECORD_VECTORS;
    TreeRecord r;
    r.a = tree_records[at];
    r.b = tree_records[at + 1u];
    r.c = tree_records[at + 2u];
    if (push.at.y == 1u && tree_fill(r)) {
        r = tree_nothing(); // the plain fills draw it
    }
    return place(r, vertex - index * TREE_RECORD_VERTICES);
}

/// The plain fills (ENGINE.md gain 18): the records the fills' list names (a close tree's trunk; a lamp's cap and
/// lantern, a box's top), each two triangles where tree_fill takes it, else nowhere.
[shader("vertex")]
TreeOut trees_fills_vs(uint vertex : SV_VulkanVertexID) {
    uint index = vertex / TREE_RECORD_VERTICES;
    uint at = tree_fills[index] * TREE_RECORD_VECTORS;
    TreeRecord r;
    r.a = tree_records[at];
    r.b = tree_records[at + 1u];
    r.c = tree_records[at + 2u];
    if (!tree_fill(r)) {
        r = tree_nothing();
    }
    return place(r, vertex - index * TREE_RECORD_VERTICES);
}

[shader("vertex")]
TreeOut tree_sides_vs(uint vertex : SV_VulkanVertexID) {
    uint index = vertex / TREE_RECORD_VERTICES;
    uint at = index * TREE_RECORD_VECTORS;
    TreeRecord r;
    r.a = tree_sides[at];
    r.b = tree_sides[at + 1u];
    r.c = tree_sides[at + 2u];
    return place(r, vertex - index * TREE_RECORD_VERTICES);
}

/// Vulkan's standard sample positions in a pixel (D7): 4x, then 2x.
static const float2 tree_samples4[4] = {float2(0.375f, 0.125f), float2(0.875f, 0.375f), float2(0.125f, 0.625f),
                                        float2(0.625f, 0.875f)};
static const float2 tree_samples2[2] = {float2(0.75f, 0.75f), float2(0.25f, 0.25f)};

struct TreeTarget {
    [[vk::location(0)]] float4 colour : SV_Target0;
    uint coverage : SV_Coverage;
};

/// The samples of the fragment at `v` its record's shape covers: a quad's are the rasteriser's (all kept); a circle's
/// and a segment's each sample in it, push.at.x a pixel.
uint coverage_of(TreeOut v) {
    uint word = uint(v.kind.x);
    uint kind = word & TREE_KIND;
    if (kind == TREE_QUAD) {
        return 0xFFFFFFFFu;
    }
    float2 corner = floor(v.position.xy);
    uint n = push.at.x;
    uint mask = 0u;
    for (uint i = 0u; i < n; ++i) {
        float2 at = n == 4u ? tree_samples4[i] : (n == 2u ? tree_samples2[i] : float2(0.5f, 0.5f));
        float2 s = css_of_output(frame.camera, corner + at);
        bool covered = kind == TREE_CIRCLE ? ((word & TREE_STREAK) != 0u ? tree_ellipse_covers(s, v.shape.xy, v.shape.zw)
                                                                         : tree_circle_covers(s, v.shape.xy, v.shape.z))
                                           : tree_segment_covers(s, v.shape.xy, v.shape.zw, v.kind.y, word);
        if (covered) {
            mask |= 1u << i;
        }
    }
    return mask;
}

/// The samples of a crown's circle's fragment at `v` under the crown filter (render/closeup.py's crown_filter;
/// SvgFilterRaster's DisplacementNode): the crowns' field at the fragment's upright pixel (`k`: its red and green)
/// moves the pixel by whole device px, and the moved pixel shows the crown's layer there, so each sample keeps where
/// its moved place lies in the circle and the pixel and the moved pixel in the tree's filter region (the filter's
/// result covers its region alone).
uint crown_coverage(TreeOut v, uint3 k) {
    int2 p = fx_pixel(frame.camera, v.position.xy);
    int2 o = {asint(tree_tones[TREE_TONE_CROWN_OFFSETS + k.x]), asint(tree_tones[TREE_TONE_CROWN_OFFSETS + k.y])};
    int2 s = p + o;
    if (p.x < v.region.x || p.y < v.region.y || p.x >= v.region.z || p.y >= v.region.w) {
        return 0u;
    }
    if (s.x < v.region.x || s.y < v.region.y || s.x >= v.region.z || s.y >= v.region.w) {
        return 0u;
    }
    float2 shift = float2(float(o.x), float(o.y)) / frame.camera.frame.z;
    float2 corner = floor(v.position.xy);
    uint n = push.at.x;
    uint mask = 0u;
    for (uint i = 0u; i < n; ++i) {
        float2 at = n == 4u ? tree_samples4[i] : (n == 2u ? tree_samples2[i] : float2(0.5f, 0.5f));
        float2 m = css_of_output(frame.camera, corner + at) + shift;
        if (tree_circle_covers(m, v.shape.xy, v.shape.z)) {
            mask |= 1u << i;
        }
    }
    return mask;
}

/// A tree's part: its colour with mat's grain, on the samples its shape covers; a crown's circle under the crown filter
/// on the samples its moved places hold, in the filter's colour at the crown tone's byte there.
[shader("fragment")]
TreeTarget trees_fs(TreeOut v) {
    bool crown = crowned(uint(v.kind.x));
    uint3 k = uint3(0u);
    if (crown) {
        k = fx_crown(fx_pixel(frame.camera, v.position.xy));
    }
    uint mask = crown ? crown_coverage(v, k) : coverage_of(v);
    if (mask == 0u) {
        discard;
    }
    float3 colour = crown ? crown_colour(v.colour, k.z) : v.colour;
    TreeTarget o;
    o.colour = float4(mat_colour(frame.camera, colour, v.position.xy, mat_on(frame)), 1.0f);
    o.coverage = mask;
    return o;
}

struct TreeFill {
    [[vk::location(0)]] float4 colour : SV_Target0;
};

/// A plain fill: a quad's colour with mat's grain on every sample the rasteriser covers (coverage_of's own for a quad),
/// with no mask written and nothing discarded.
[shader("fragment")]
TreeFill trees_fills_fs(TreeOut v) {
    TreeFill o;
    o.colour = float4(mat_colour(frame.camera, v.colour, v.position.xy, mat_on(frame)), 1.0f);
    return o;
}

/// A lamp's halo's colour and opacity at `t` of its radius: the `halo` gradient's stops at 0, .35 and 1 interpolated
/// in their encoded values.
float4 halo_at(float t) {
    float4 s0 = tree_rgba(TREE_HALO_STOP0);
    float4 s1 = tree_rgba(TREE_HALO_STOP1);
    float4 s2 = tree_rgba(TREE_HALO_STOP2);
    if (t <= 0.35f) {
        return lerp(s0, s1, t / 0.35f);
    }
    return lerp(s1, s2, saturate((t - 0.35f) / 0.65f));
}

/// A lamp's wet streak's colour and opacity at `t` of its ellipse: the `streak` gradient's stops at 0, .5 and 1
/// interpolated in their encoded values.
float4 streak_at(float t) {
    float4 s0 = tree_rgba(TREE_STREAK_STOP0);
    float4 s1 = tree_rgba(TREE_STREAK_STOP1);
    float4 s2 = tree_rgba(TREE_STREAK_STOP2);
    if (t <= 0.5f) {
        return lerp(s0, s1, t / 0.5f);
    }
    return lerp(s1, s2, saturate((t - 0.5f) / 0.5f));
}

/// A translucent part (a trunk's sunlit side, a lamp's halo or its wet streak): its colour at its opacity (a halo's
/// its gradient's at the pixel's centre, a streak's its gradient's at its opacity), premultiplied, with mat's grain, on
/// the samples its shape covers.
[shader("fragment")]
TreeTarget tree_sides_fs(TreeOut v) {
    uint mask = coverage_of(v);
    if (mask == 0u) {
        discard;
    }
    float alpha = v.kind.z;
    float3 colour = v.colour;
    if ((uint(v.kind.x) & TREE_HALO) != 0u) {
        float2 css = css_of_output(frame.camera, v.position.xy);
        float2 d = css - v.shape.xy;
        float4 g = halo_at(sqrt(d.x * d.x + d.y * d.y) / v.shape.z);
        colour = g.rgb;
        alpha = g.a;
    }
    if ((uint(v.kind.x) & TREE_STREAK) != 0u) {
        float2 css = css_of_output(frame.camera, v.position.xy);
        float2 d = (css - v.shape.xy) / v.shape.zw;
        float4 g = streak_at(sqrt(d.x * d.x + d.y * d.y));
        colour = g.rgb;
        alpha = g.a * v.kind.z;
    }
    TreeTarget o;
    float3 c = mat_colour(frame.camera, colour, v.position.xy, mat_on(frame));
    o.colour = float4(c * alpha, alpha);
    o.coverage = mask;
    return o;
}
