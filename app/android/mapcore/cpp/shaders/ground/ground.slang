// The ground (plan-vector D10 "Ground" and "Water", D5.12's passes 3 and 4, D6; CP/passes/Ground.cpp, K9): what the
// renders draw on the ground, as the frame's opaque ground (top first, painter depth) and its translucent elements
// (painter's order, depth tested, not written).
//
//   - fill_vs and colour_fs: a tile's fills (the land, the areas, the roads, the region's ballast, asphalt and marine
//     works), pulled from the ground heap by index (a draw's firstVertex is its first index's word, its firstInstance
//     the tile's place in the frame's tile table), each vertex placed by its tile, projected as camera.slang projects
//     (snapped at rest), at its fill's painter depth: its group's base and stride from the frame's slot table, its
//     order and sub-element (geometry/Ground.hpp), in its day or night colour or the theme's. A fill the frame does not
//     draw (the renders' cull and area test, decided on the CPU) folds its triangles away.
//   - water_vs: the water's pieces' fans at the street's height or at their water level, counted in the stencil
//     (front faces increment, back faces decrement, wrapping), so the clips are nonzero unions (passes/Water.cpp).
//   - screen_vs with sky_fs, gradient_fs and flat_fs: one triangle over the frame at a painter slot's depth: the sky
//     (render's #sky: sky_top to sky_hor between two rows), the water's surface (#wgrad, or th['water'] without
//     weather), the water's tint, and the stencil's unions composited at their opacity (the shadows, the canopy).
//   - stroke_vs and stroke_fs: the ground's lines (passes/Lines.cpp; their coverage in ground/lines.slangh), a sett
//     road's courses each line's share of the path's layer (ground/setts.slangh).
//   - shadow_vs: the ground shadows (passes/Shadows.cpp), moved by the moment's sun: a prism's footprint hull swept
//     along its offset (each hull point twice, before and after its turn), a raised outline or a crown disc moved by
//     it, their union set in the stencil and composited by flat_fs.
//   - the soft shadows, where the moment blurs them (passes/Shadows.cpp): shadow_box_cs measures the box of the drawn
//     shadows as Camera.poly keeps them and the SVG prints them, shadow_grid_cs works out from it the grid the port's
//     raster blurs them on (shared/shadow_grid.slang, fx/BlurGrid.hpp), shadow_mask_vs and mask_fs draw their union on
//     that grid into a 4x mask, which shadow_blur.slang blurs, and soft_fs draws the result back over the ground as the
//     port draws its bitmap.
//
// Colours are the renders' 8-bit sRGB values, blended as the renders composite encoded values (D7, "Gamma"); a
// translucent colour is premultiplied. Not an exact module: positions are the vertex stage's float maths, held to the
// twin's (twin/Emit.hpp) by L2.

#include "shared/frame.slang"
#include "shared/painter.slang"
#include "shared/shadow_grid.slang"

// K17a's mat (render's `mat` filter over draw_under's land, the areas, the roads and draw_over's fills, D10), where
// mat_on says: the frame's fields bound, in full mode, and the moment's weather, with which alone render defines #mat.
// ATLAS_GROUND_MAT keeps the switch in one place.
#define ATLAS_GROUND_MAT
#if defined(ATLAS_GROUND_MAT)
#define ATLAS_FX_FUNCTIONS
#include "fx/mat.slang"
#include "ground/lines.slangh"
#endif

[[vk::binding(0, 0)]] ConstantBuffer<FrameBlock> frame;
[[vk::binding(2, 1)]] StructuredBuffer<uint> ground_heap;
[[vk::binding(3, 1)]] StructuredBuffer<uint> lines_heap;
/// The pass's words for this frame (passes/Ground.cpp's FrameWords): the tile table, the slot table, the theme's
/// colours, the fills' visibility bits, the lines' runs and segments.
[[vk::binding(0, 2)]] StructuredBuffer<uint> words;
/// The soft shadows' grid, read; shadow_box_cs and shadow_grid_cs write it as words through binding 2, the same
/// buffer (passes/Shadows.cpp).
[[vk::binding(0, 3)]] StructuredBuffer<ShadowGrid> shadow_grid;
/// The blurred mask, faded by the opacity: the grid's input rectangle, four texels to a word, push.colour.w words a
/// row (shadow_blur.slang's last pass).
[[vk::binding(1, 3)]] StructuredBuffer<uint> shadow_blurred;
[[vk::binding(2, 3)]] RWStructuredBuffer<uint> shadow_words;

/// A draw's own values: where its tables lie in `words`, its mode and its colours.
struct GroundPush {
    uint4 at;     // x: the tile table's first word, y: the slot table's, z: the theme colours', w: the runs' (lines)
    uint4 mode;   // x: the GROUND_ flags, y: the painter slot of a screen pass, z: the instances' first word (lines), w: 0
    float4 rows;  // x, y: a gradient's first and last rows (CSS px); z: a screen pass's opacity; w: 0 (the soft
                  // shadows: shadow_box_cs and the mask's xyz the sun, shadow_grid_cs's σ, opacity and WebKit's factor)
    uint4 colour; // x, y: a gradient's two colours (RGBA8); z: a screen pass's colour; w: 0 (the soft shadows: x, y the
                  // mask's size in texels, w the blurred mask's words a row)
};

[[vk::push_constant]] ConstantBuffer<GroundPush> push;

/// The draw's flags (GroundPush.mode.x).
static const uint GROUND_NIGHT = 1u;       // the night colours
static const uint GROUND_WATER_LEVEL = 2u; // water_vs: the pieces at their water level (wclip), else at the street (w0)
static const uint GROUND_VISIBLE_ALL = 4u; // every fill drawn (the frame's decisions not applied)

/// A tile's record in the tile table: 20 words.
static const uint TILE_WORDS = 20u;

/// The tile table's words of tile `t`, word `k`: 0-3 its place's offset, 4-7 its scale (camera.slang's TilePlace), 8
/// its ground range's first word in the ground heap, 9 its vertices' first word in it, 10 its fills', 11 its lines
/// range's first word in the lines heap, 12 its polylines' first word in it, 13 the first word of its fills' visibility
/// bits in `words`, 14 of its water pieces' at their level, 15 of its shadows', 16 its shadows' points' first word in
/// its ground range, 17 its shadows' records', 18 how many shadows it draws from (0 where its region draws none), 19
/// the first word of its fills' ranks in `words` where the frame draws tiles of more than one scene (0: none, each fill
/// at its scene's order).
uint tile_word(uint t, uint k) {
    return words[push.at.x + t * TILE_WORDS + k];
}

TilePlace place_of(uint t) {
    TilePlace p;
    p.offset = float4(asfloat(tile_word(t, 0u)), asfloat(tile_word(t, 1u)), asfloat(tile_word(t, 2u)),
                      asfloat(tile_word(t, 3u)));
    p.scale = float4(asfloat(tile_word(t, 4u)), asfloat(tile_word(t, 5u)), asfloat(tile_word(t, 6u)),
                     asfloat(tile_word(t, 7u)));
    return p;
}

/// Whether bit `i` of the bits from word `first` is set.
bool bit(uint first, uint i) {
    return (words[first + (i >> 5u)] & (1u << (i & 31u))) != 0u;
}

/// An 8-bit RGBA colour (r in the low byte) as 0-1 values.
float4 unpack(uint c) {
    return float4(float(c & 0xFFu), float((c >> 8u) & 0xFFu), float((c >> 16u) & 0xFFu), float(c >> 24u)) / 255.0f;
}

/// A fill's colour: a literal (alpha 255), or the theme's colour its red names (alpha 0), from the frame's theme words.
float4 paint_of(uint c) {
    if ((c >> 24u) == 0u) {
        c = words[push.at.z + (c & 0xFFu)];
    }
    return unpack(c);
}

/// A painter slot: its group's base (a signed word) and stride from the slot table, the order and the sub-element.
uint slot_of(uint group, uint sub, uint order) {
    int base = int(words[push.at.y + 2u * group]);
    uint stride = words[push.at.y + 2u * group + 1u];
    return uint(base + int(order * stride + sub));
}

/// A vertex no triangle draws: beyond the far plane, so a triangle of three of them is clipped away.
static const float4 FOLDED = float4(0.0f, 0.0f, 2.0f, 1.0f);

/// The clip position of tile `t`'s local point at painter slot `slot`.
float4 place(uint t, float3 local, uint slot) {
    float3 k = camera_of(frame.camera, eye_relative(place_of(t), local));
    float2 css = project_css(frame.camera, k);
    return clip_of(frame.camera, css, k.z, painter_depth(frame.camera, slot));
}

// ---------------------------------------------------------------- fills

struct FillOut {
    float4 position : SV_Position;
    nointerpolation float4 colour : COLOR0;
#if defined(ATLAS_GROUND_MAT)
    nointerpolation uint mat : COLOR1; // 1: in render's `mat` group (the land, the areas, the roads, draw_over's fills)
#endif
};

/// Whether the fill is in render's `mat` group (with K17a's mat: its colour stage's switch).
void set_mat(inout FillOut o, bool on) {
#if defined(ATLAS_GROUND_MAT)
    o.mat = on ? 1u : 0u;
#endif
}

/// Whether a fill's group is in render's `mat` group: draw_under's land and render's `<g filter="url(#mat)">` (the
/// areas, the roads, draw_over's ballast, asphalt and marine works).
bool in_mat(uint group) {
    return group == 4u || (group >= 8u && group < 32u) || group >= 64u;
}

/// A fill's vertex: its index from the ground heap at word `index` (the draw's firstVertex counts the words), its tile
/// the instance (the draw's firstInstance).
[shader("vertex")]
FillOut fill_vs(uint index : SV_VulkanVertexID, uint tile : SV_VulkanInstanceID) {
    uint base = tile_word(tile, 8u);
    uint v = ground_heap[index];
    uint at = base + tile_word(tile, 9u) + 3u * v;
    float x = asfloat(ground_heap[at]);
    float z = asfloat(ground_heap[at + 1u]);
    uint f = ground_heap[at + 2u];
    uint r = base + tile_word(tile, 10u) + 5u * f;
    FillOut o;
    set_mat(o, false);
    if ((push.mode.x & GROUND_VISIBLE_ALL) == 0u && !bit(tile_word(tile, 13u), f)) {
        o.position = FOLDED;
        o.colour = float4(0.0f, 0.0f, 0.0f, 0.0f);
        return o;
    }
    float y = asfloat(ground_heap[r]);
    uint gs = ground_heap[r + 1u];
    // its order in its group: its scene's, or where the frame draws tiles of more than one scene its rank across them
    uint ranks = tile_word(tile, 19u);
    uint order = ranks != 0u ? words[ranks + f] : ground_heap[r + 2u];
    uint c = (push.mode.x & GROUND_NIGHT) != 0u ? ground_heap[r + 4u] : ground_heap[r + 3u];
    o.position = place(tile, float3(x, y, z), slot_of(gs & 0xFFFFu, gs >> 16u, order));
    float4 p = paint_of(c);
    // a translucent fill (the canopy's union is composited instead): premultiplied by the screen pass's opacity
    o.colour = float4(p.rgb, 1.0f);
    set_mat(o, in_mat(gs & 0xFFFFu));
    return o;
}

struct ColourOut {
    [[vk::location(0)]] float4 colour : SV_Target0;
};

[shader("fragment")]
ColourOut colour_fs(FillOut v) {
    ColourOut o;
    o.colour = v.colour;
#if defined(ATLAS_GROUND_MAT)
    // mat's grain on a fill in the group, where mat_on says (the fields bound, full mode, the moment's weather)
    o.colour = float4(mat_colour(frame.camera, v.colour.rgb, v.position.xy, mat_on(frame) && v.mat != 0u), v.colour.a);
#endif
    return o;
}

/// The ground plate's two triangles: render's rect(-GR, GR, -GR, GR) as unit corners.
static const float2 plate_corners[6] = {float2(-1.0f, -1.0f), float2(1.0f, -1.0f), float2(1.0f, 1.0f),
                                        float2(-1.0f, -1.0f), float2(1.0f, 1.0f),  float2(-1.0f, 1.0f)};

/// The ground plate on the street, placed by the frame's own entry of the tile table (push.mode.z), half its side
/// push.rows.w, at painter slot push.mode.y, in push.colour.z.
[shader("vertex")]
FillOut plate_vs(uint vertex : SV_VulkanVertexID) {
    float2 c = plate_corners[vertex] * push.rows.w;
    FillOut o;
    o.position = place(push.mode.z, float3(c.x, 0.0f, c.y), push.mode.y);
    o.colour = float4(unpack(push.colour.z).rgb, 1.0f);
    set_mat(o, false);
    return o;
}

// ---------------------------------------------------------------- the water's pieces, counted in the stencil

/// A water piece's vertex, at the street's height (w0) or at its level (wclip): drawn where the frame drew its ring at
/// that height (each its own bits).
[shader("vertex")]
FillOut water_vs(uint index : SV_VulkanVertexID, uint tile : SV_VulkanInstanceID) {
    uint base = tile_word(tile, 8u);
    uint v = ground_heap[index];
    uint at = base + tile_word(tile, 9u) + 3u * v;
    float x = asfloat(ground_heap[at]);
    float z = asfloat(ground_heap[at + 1u]);
    uint f = ground_heap[at + 2u];
    uint r = base + tile_word(tile, 10u) + 5u * f;
    bool level = (push.mode.x & GROUND_WATER_LEVEL) != 0u;
    FillOut o;
    o.colour = float4(0.0f, 0.0f, 0.0f, 0.0f);
    set_mat(o, false);
    uint bits = level ? tile_word(tile, 14u) : tile_word(tile, 13u);
    if ((push.mode.x & GROUND_VISIBLE_ALL) == 0u && !bit(bits, f)) {
        o.position = FOLDED;
        return o;
    }
    float y = level ? asfloat(ground_heap[r]) : 0.0f;
    // the stencil's count only: the depth is the water's surface slot, never tested
    o.position = place(tile, float3(x, y, z), push.mode.y);
    return o;
}

// ---------------------------------------------------------------- the ground shadows (passes/Shadows.cpp)

/// A shadow record's flags (geometry/Ground.hpp's shadow_flag).
static const uint SHADOW_MOVED = 1u; // moved by its offset whole (a raised outline, a crown disc); else a swept hull
static const uint SHADOW_TREE = 2u;  // a tree's crown (Camera.poly's margin of 40 CSS px; a solid's 900)

/// Point `i` of tile `t`'s shadows' points (its ground range from `base`): x, z from the tile's origin.
float2 shadow_point(uint base, uint t, uint i) {
    uint at = base + tile_word(t, 16u) + 2u * i;
    return float2(asfloat(ground_heap[at]), asfloat(ground_heap[at + 1u]));
}

/// Whether edge k of a swept hull (from its point k to k + 1, turned positive) faces the offset d: its outward normal,
/// (dz, -dx), has a positive dot with it.
bool facing(uint base, uint t, uint first, uint count, uint k, float2 d) {
    float2 e = shadow_point(base, t, first + (k + 1u) % count) - shadow_point(base, t, first + k);
    return dot(float2(e.y, -e.x), d) > 0.0f;
}

/// One shadow of a tile: its record's points, how many, the flags, the height it is drawn at, and its offset −L.xz /
/// L.y · h (push.rows.xyz: L, toward the sun).
struct ShadowOf {
    uint first;
    uint count;
    uint flags;
    float y;
    float2 d;
};

ShadowOf shadow_of(uint base, uint tile, uint s) {
    uint r = base + tile_word(tile, 17u) + 5u * s;
    ShadowOf c;
    c.first = ground_heap[r];
    c.count = ground_heap[r + 1u];
    float h = asfloat(ground_heap[r + 2u]);
    c.flags = ground_heap[r + 3u];
    c.y = asfloat(ground_heap[r + 4u]);
    float3 sun = push.rows.xyz;
    c.d = float2(-sun.x / sun.y * h, -sun.z / sun.y * h);
    return c;
}

/// Corner j of a shadow, tile-local x, z. A raised outline or a crown disc is moved by the offset whole. A footprint's
/// hull is swept along it: the edges that face it move, the others stay, and the two points where the facing edges
/// begin and end come twice, staying and moved (count + 2 corners, a convex fan: the hull of the footprint and its
/// moved copy).
float2 shadow_corner(uint base, uint tile, ShadowOf c, uint j) {
    uint first = c.first;
    uint count = c.count;
    float2 d = c.d;
    uint i = min(j, count - 1u);
    bool moved = true;
    if ((c.flags & SHADOW_MOVED) == 0u) {
        // the turning points: where the edge before a point and the edge after it face differently (two on a convex
        // hull, none when the offset is nothing)
        uint t1 = count;
        uint t2 = count;
        bool before = facing(base, tile, first, count, count - 1u, d);
        for (uint k = 0u; k < count; ++k) {
            bool after = facing(base, tile, first, count, k, d);
            if (after != before) {
                if (t1 == count) {
                    t1 = k;
                } else if (t2 == count) {
                    t2 = k;
                }
            }
            before = after;
        }
        // corner j: the points in order, each turning point twice (staying, then moved, or the other way)
        uint copy = 0u;
        if (t2 < count) {
            if (j <= t1) {
                i = j;
            } else if (j == t1 + 1u) {
                i = t1;
                copy = 1u;
            } else if (j <= t2 + 1u) {
                i = j - 1u;
            } else if (j == t2 + 2u) {
                i = t2;
                copy = 1u;
            } else {
                i = j - 2u;
            }
        }
        // the first copy takes the edge before its point, the second the edge after it
        moved = facing(base, tile, first, count, copy == 0u ? (i + count - 1u) % count : i, d);
    }
    float2 q = shadow_point(base, tile, first + i);
    return moved ? q + d : q;
}

/// A shadow's vertex: its index from the ground heap at word `index` names the shadow (index >> 16) and its corner
/// (index & 0xFFFF), its tile the instance. Drawn at the shadows' painter slot (push.mode.y), where the frame drew the
/// shadow (its radius cut).
[shader("vertex")]
FillOut shadow_vs(uint index : SV_VulkanVertexID, uint tile : SV_VulkanInstanceID) {
    uint base = tile_word(tile, 8u);
    uint id = ground_heap[index];
    uint s = id >> 16u;
    FillOut o;
    o.colour = float4(0.0f, 0.0f, 0.0f, 0.0f);
    set_mat(o, false);
    if (!bit(tile_word(tile, 15u), s)) {
        o.position = FOLDED;
        return o;
    }
    ShadowOf c = shadow_of(base, tile, s);
    float2 p = shadow_corner(base, tile, c, id & 0xFFFFu);
    o.position = place(tile, float3(p.x, c.y, p.y), push.mode.y);
    return o;
}

// ---------------------------------------------------------------- the soft shadows (passes/Shadows.cpp)

/// The order-preserving keys of the box shadow_box_cs folds this workgroup's shadows into.
groupshared uint box_low_x;
groupshared uint box_low_y;
groupshared uint box_high_x;
groupshared uint box_high_y;

/// The box of the drawn shadows, one thread a shadow of one tile (the dispatch's y), 64 a workgroup, as the renders
/// keep and print them (render/amsterdam.py:742–771, city.py's Camera.poly): a shadow the frame draws (its radius cut)
/// has its corners' camera coordinates clipped at the near plane (each corner ahead of it, and where an edge crosses
/// it) and projected; it is kept unless it has fewer than three points left or its box lies wholly beyond the margin
/// past the frame (900 CSS px for a solid's, 40 for a tree's), and its box, snapped at rest as the SVG prints its
/// points, folds into the frame's (words 0 to 3 of the grid, as box_key keys: the low corner by atomic min, the high
/// one by atomic max).
[shader("compute")]
[numthreads(64, 1, 1)]
void shadow_box_cs(uint3 group : SV_GroupID, uint3 local : SV_GroupThreadID) {
    if (local.x == 0u) {
        box_low_x = 0xFFFFFFFFu;
        box_low_y = 0xFFFFFFFFu;
        box_high_x = 0u;
        box_high_y = 0u;
    }
    GroupMemoryBarrierWithGroupSync();
    uint tile = group.y;
    uint s = group.x * 64u + local.x;
    if (s < tile_word(tile, 18u) && bit(tile_word(tile, 15u), s)) {
        uint base = tile_word(tile, 8u);
        ShadowOf c = shadow_of(base, tile, s);
        uint corners = (c.flags & SHADOW_MOVED) != 0u ? c.count : c.count + 2u;
        TilePlace at = place_of(tile);
        float near = frame.camera.frame.w;
        float2 low = float2(1.0e30f, 1.0e30f);
        float2 high = float2(-1.0e30f, -1.0e30f);
        uint points = 0u;
        float2 p0 = shadow_corner(base, tile, c, 0u);
        float3 k0 = camera_of(frame.camera, eye_relative(at, float3(p0.x, c.y, p0.y)));
        float3 a = k0;
        for (uint j = 1u; j <= corners; ++j) {
            float3 b = k0;
            if (j < corners) {
                float2 p = shadow_corner(base, tile, c, j);
                b = camera_of(frame.camera, eye_relative(at, float3(p.x, c.y, p.y)));
            }
            bool ahead = a.z >= near;
            if (ahead) {
                float2 q = css_of(frame.camera, a);
                low = min(low, q);
                high = max(high, q);
                ++points;
            }
            if (ahead != (b.z >= near)) {
                float t = (near - a.z) / (b.z - a.z);
                float3 m = float3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, near);
                float2 q = css_of(frame.camera, m);
                low = min(low, q);
                high = max(high, q);
                ++points;
            }
            a = b;
        }
        float margin = (c.flags & SHADOW_TREE) != 0u ? 40.0f : 900.0f;
        bool kept = points >= 3u && !(high.x < -margin || low.x > frame.camera.frame.x + margin ||
                                      high.y < -margin || low.y > frame.camera.frame.y + margin);
        if (kept) {
            if (camera_rests(frame.camera)) {
                low = float2(snap_tenth(low.x), snap_tenth(low.y));
                high = float2(snap_tenth(high.x), snap_tenth(high.y));
            }
            InterlockedMin(box_low_x, box_key(low.x));
            InterlockedMin(box_low_y, box_key(low.y));
            InterlockedMax(box_high_x, box_key(high.x));
            InterlockedMax(box_high_y, box_key(high.y));
        }
    }
    GroupMemoryBarrierWithGroupSync();
    if (local.x == 0u && box_low_x != 0xFFFFFFFFu) {
        InterlockedMin(shadow_words[0], box_low_x);
        InterlockedMin(shadow_words[1], box_low_y);
        InterlockedMax(shadow_words[2], box_high_x);
        InterlockedMax(shadow_words[3], box_high_y);
    }
}

/// WebKit's `ImageBuffer::sizeNeedsClamping` (fx/BlurGrid.cpp's clamp_scale): the factor that brings a w × h pixel
/// area within 4096², the area's whole pixels counted in integers where they fit.
float clamp_scale(float w, float h) {
    if (!(w > 0.0f) || !(h > 0.0f)) {
        return 1.0f;
    }
    float fw = floor(w);
    float fh = floor(h);
    bool over = fw <= 65535.0f && fh <= 65535.0f ? uint(fw) * uint(fh) > 16777216u : fw * fh > 16777216.0f;
    return over ? sqrt(16777216.0f / (w * h)) : 1.0f;
}

/// WebKit's box size for a deviation of `sigma` filter px (fx::box_size): ⌊σ · f + ½⌋ for its factor f = 3√(2π)/4,
/// at least 2 and at most 500; 0 for none.
uint kernel_of(float sigma, float f) {
    if (!(sigma > 0.0f)) {
        return 0u;
    }
    float d = floor(sigma * f + 0.5f);
    if (!(d < 500.0f)) {
        return 500u;
    }
    return max(2u, uint(d));
}

/// The largest half box shadow_blur.slang holds (its HALF_MOST).
static const uint SHADOW_HALF_MOST = 24u;

/// The soft shadows' grid from their box: fx/BlurGrid.cpp's blur_grid, step by step in float. push.rows: x the
/// deviation σ (CSS px), y the opacity (as printed), z WebKit's factor as the CPU computes it; push.colour.xy the
/// mask's size in texels. The frame's clip is its CSS size rounded up, the canvas's scale its density. A grid larger
/// than the mask, or a box the blur cannot hold, draws nothing.
[shader("compute")]
[numthreads(1, 1, 1)]
void shadow_grid_cs() {
    float x0 = box_value(shadow_words[0]);
    float y0 = box_value(shadow_words[1]);
    float x1 = box_value(shadow_words[2]);
    float y1 = box_value(shadow_words[3]);
    float bw = x1 - x0;
    float bh = y1 - y0;
    float sx = 0.0f;
    float clamp = 1.0f;
    int4 input = int4(0, 0, 0, 0);
    int4 output = int4(0, 0, 0, 0);
    uint box = 0u;
    uint flags = 0u;
    if (bw > 0.0f && bh > 0.0f) {
        float r0 = x0 + (-0.1f) * bw;
        float r1 = y0 + (-0.1f) * bh;
        float r2 = 1.2f * bw;
        float r3 = 1.2f * bh;
        sx = frame.camera.frame.z;
        float w = min(x1 + 1.0e4f, r0 + r2) - max(x0 - 1.0e4f, r0);
        float h = min(y1 + 1.0e4f, r1 + r3) - max(y0 - 1.0e4f, r1);
        float k1 = clamp_scale(w * sx, h * sx);
        sx = sx * k1;
        float k2 = clamp_scale(r2 * sx, r3 * sx);
        sx = sx * k2;
        clamp = k1 * k2;
        int4 visible = int4(-1, -1, int(ceil(ceil(frame.camera.frame.x) * sx)) + 1,
                            int(ceil(ceil(frame.camera.frame.y) * sx)) + 1);
        float x = r0 * sx;
        float y = r1 * sx;
        int4 region = int4(int(floor(x)), int(floor(y)), int(ceil(x + r2 * sx)), int(ceil(y + r3 * sx)));
        output = int4(max(region.x, visible.x), max(region.y, visible.y), min(region.z, visible.z),
                      min(region.w, visible.w));
        uint d = kernel_of(push.rows.x * sx, push.rows.z);
        if (output.z > output.x && output.w > output.y && d > 0u) {
            int reach = int(3u * (d / 2u + 1u));
            box = d % 2u == 0u ? d + 1u : d;
            int4 r = int4(max(output.x - reach, region.x), max(output.y - reach, region.y),
                          min(output.z + reach, region.z), min(output.w + reach, region.w));
            input = int4(r.x, r.y, r.z - r.x, r.w - r.y);
            if (input.z > 0 && input.w > 0 && input.z <= int(push.colour.x) && input.w <= int(push.colour.y) &&
                box / 2u <= SHADOW_HALF_MOST) {
                flags = SHADOW_GRID_DRAWN | (clamp < 1.0f ? SHADOW_GRID_FILTERED : 0u);
            }
        }
    }
    shadow_words[4] = asuint(sx);
    shadow_words[5] = asuint(clamp);
    shadow_words[6] = asuint(push.rows.y);
    shadow_words[7] = 0u;
    shadow_words[8] = asuint(input.x);
    shadow_words[9] = asuint(input.y);
    shadow_words[10] = asuint(input.z);
    shadow_words[11] = asuint(input.w);
    shadow_words[12] = asuint(output.x);
    shadow_words[13] = asuint(output.y);
    shadow_words[14] = asuint(output.z);
    shadow_words[15] = asuint(output.w);
    shadow_words[16] = box;
    shadow_words[17] = box / 2u;
    shadow_words[18] = (box * box) / 2u;
    shadow_words[19] = flags;
    shadow_words[20] = asuint(box > 0u ? 1.0f / float(box * box) : 0.0f);
    shadow_words[21] = 0u;
    shadow_words[22] = 0u;
    shadow_words[23] = 0u;
}

/// A shadow's vertex on the soft shadows' grid: shadow_vs's corner, its CSS point (snapped at rest, as the SVG prints
/// it) in filter px from the grid's input corner, which are the mask's texels (push.colour.xy of them). No depth: the
/// union is all the mask holds.
struct MaskOut {
    float4 position : SV_Position;
};

[shader("vertex")]
MaskOut shadow_mask_vs(uint index : SV_VulkanVertexID, uint tile : SV_VulkanInstanceID) {
    uint base = tile_word(tile, 8u);
    uint id = ground_heap[index];
    uint s = id >> 16u;
    MaskOut o;
    if (!bit(tile_word(tile, 15u), s)) {
        o.position = FOLDED;
        return o;
    }
    ShadowOf c = shadow_of(base, tile, s);
    float2 p = shadow_corner(base, tile, c, id & 0xFFFFu);
    float3 k = camera_of(frame.camera, eye_relative(place_of(tile), float3(p.x, c.y, p.y)));
    float2 css = project_css(frame.camera, k);
    ShadowGrid g = shadow_grid[0];
    float2 texel = css * g.scale.x - float2(float(g.input.x), float(g.input.y));
    float2 n = texel * float2(2.0f / float(push.colour.x), 2.0f / float(push.colour.y)) - 1.0f;
    o.position = float4(n.x * k.z, n.y * k.z, 0.5f * k.z, k.z);
    return o;
}

/// What each of the mask's samples a shadow covers holds: 252 of 255, so that a resolve's mean of four samples is a
/// whole level for every count (63 for each), however the driver rounds.
static const float MASK_SAMPLE = 252.0f / 255.0f;

[shader("fragment")]
ColourOut mask_fs(MaskOut v) {
    ColourOut o;
    o.colour = float4(MASK_SAMPLE, 0.0f, 0.0f, 1.0f);
    return o;
}

// ---------------------------------------------------------------- one triangle over the frame

struct ScreenOut {
    float4 position : SV_Position;
};

/// The triangle over the frame at painter slot push.mode.y.
[shader("vertex")]
ScreenOut screen_vs(uint vertex : SV_VulkanVertexID) {
    float2 uv = float2(float((vertex << 1u) & 2u), float(vertex & 2u));
    ScreenOut o;
    float d = painter_depth(frame.camera, push.mode.y);
    o.position = float4(uv * 2.0f - 1.0f, d, 1.0f);
    return o;
}

/// An SVG linearGradient from (0, y0) down to (0, y1) in user space between two stops (offsets 0 and 1, opaque), padded:
/// its colour at CSS row y, interpolated in the stops' encoded values, as the renders' rasteriser interpolates.
float4 gradient_at(float y) {
    float y0 = push.rows.x;
    float y1 = push.rows.y;
    float t = y1 != y0 ? saturate((y - y0) / (y1 - y0)) : (y < y0 ? 0.0f : 1.0f);
    float4 a = unpack(push.colour.x);
    float4 b = unpack(push.colour.y);
    return float4(lerp(a.rgb, b.rgb, t), 1.0f);
}

/// render's #sky and #wgrad: the gradient at the pixel's centre.
[shader("fragment")]
ColourOut gradient_fs(ScreenOut v) {
    float2 css = css_of_output(frame.camera, v.position.xy);
    ColourOut o;
    o.colour = gradient_at(css.y);
    return o;
}

/// A flat colour at an opacity, premultiplied: the water without weather (opaque), its tint, and the stencil's unions
/// composited (the shadows, the canopy).
[shader("fragment")]
ColourOut flat_fs(ScreenOut v) {
    float4 c = unpack(push.colour.z);
    float a = push.rows.z;
    ColourOut o;
    o.colour = float4(c.rgb * a, a);
    return o;
}

/// The blurred mask's texel t of the grid's input rectangle (push.colour.w words a row).
float blurred_at(int2 t) {
    uint at = uint(t.y) * push.colour.w + (uint(t.x) >> 2u);
    return float((shadow_blurred[at] >> ((uint(t.x) & 3u) * 8u)) & 0xFFu);
}

/// The soft shadows over the ground at the shadows' painter depth (screen_vs): the blurred mask drawn back as the
/// port draws its bitmap (SvgCanvas.filtered). Each pixel's centre in filter px (its CSS point times the grid's
/// scale); inside the grid's output, the mask there (faded by the blur's last pass): nearest where the scale is the
/// density's, else between the four texels around it (the bitmap's bilinear filter, its edge texels repeated), as
/// th['shadow'] (push.colour.z) at that alpha, premultiplied. Nothing elsewhere.
[shader("fragment")]
ColourOut soft_fs(ScreenOut v) {
    ColourOut o;
    o.colour = float4(0.0f, 0.0f, 0.0f, 0.0f);
    ShadowGrid g = shadow_grid[0];
    if ((g.blur.w & SHADOW_GRID_DRAWN) == 0u) {
        return o;
    }
    float2 f = css_of_output(frame.camera, v.position.xy) * g.scale.x;
    if (f.x < float(g.output.x) || f.x >= float(g.output.z) || f.y < float(g.output.y) || f.y >= float(g.output.w)) {
        return o;
    }
    float a = 0.0f;
    if ((g.blur.w & SHADOW_GRID_FILTERED) == 0u) {
        a = blurred_at(int2(floor(f)) - g.input.xy);
    } else {
        float2 u = f - 0.5f;
        float2 i0 = floor(u);
        float2 t = u - i0;
        int2 lo = clamp(int2(i0), g.output.xy, g.output.zw - 1) - g.input.xy;
        int2 hi = clamp(int2(i0) + 1, g.output.xy, g.output.zw - 1) - g.input.xy;
        float top = lerp(blurred_at(lo), blurred_at(int2(hi.x, lo.y)), t.x);
        float bottom = lerp(blurred_at(int2(lo.x, hi.y)), blurred_at(hi), t.x);
        a = lerp(top, bottom, t.y);
    }
    float alpha = a / 255.0f;
    float4 c = unpack(push.colour.z);
    o.colour = float4(c.rgb * alpha, alpha);
    return o;
}

// ---------------------------------------------------------------- lines (passes/Lines.cpp)
//
// A segment of a run (a polyline of a stroke this frame) is an instance: its four points (the one before, its two
// ends, the one after) projected as the vertex stage projects (snapped at rest), in device px, drawn as a quad around
// it wide enough for its join's wedge, and its coverage found per pixel or per sample as RefRaster finds a SEG's
// (anchor/src/testFixtures/.../RefRaster.kt SegGeom, Strokes.kt): a stroke at most a device pixel wide as Skia's
// anti-aliased hairline (its alpha the width, each segment its own line), one under two device px with G1's exact-area
// coverage (the strip's area times the pixel box's along it, trimmed at the bisectors with its neighbours, the join's
// wedge drawn by the segment that ends there), a wider one by the samples inside its shape (the standard sample
// positions). A run's points behind the near plane end it (line_d's runs): a segment with an end there folds away.

/// A run's record in `words`: 8 words.
static const uint RUN_WORDS = 8u;

struct StrokeOut {
    float4 position : SV_Position;
    nointerpolation float4 colour : COLOR0; // the paint, premultiplied
    nointerpolation float4 ab : TEXCOORD0;  // its ends a and b, device px of the view
    nointerpolation float4 pn : TEXCOORD1;  // the point before a and the point after b (where LINE_HAS_PREV, _NEXT)
    nointerpolation uint4 info : TEXCOORD2; // x: mode and LINE_HAS_ bits; y: the half width (device px) as bits; z: the
                                            // run's LINE_ flags, and from bit 16 a layer run's line's reach (how many
                                            // lines before it it looks back at); w: its instance word (run << 14 | segment)
    nointerpolation float4 dash : TEXCOORD3; // a dashed run's (in_dash): its start's place in the pattern, the dash and
                                             // the period (device px), 1; else 0
};

/// A run's point k in its tile's metres, at height y.
float3 run_local(uint first, uint k, float y) {
    float x = asfloat(lines_heap[first + 2u * k]);
    float z = asfloat(lines_heap[first + 2u * k + 1u]);
    return float3(x, y, z);
}

/// A tile-local point's camera coordinates.
float3 local_camera(uint tile, float3 p) {
    return camera_of(frame.camera, eye_relative(place_of(tile), p));
}

/// A run's point k, projected: its camera coordinates.
float3 run_point(uint tile, uint first, uint k, float y) {
    return local_camera(tile, run_local(first, k, y));
}

/// A coaster track's shadow's point k (its (x, z, height) in the lines heap) at height y in its tile's metres, moved
/// along the sun's offset −L.xz / L.y · height (push.rows.xyz: L) where `moved`.
float3 shadow_local(uint first, uint k, float y, bool moved) {
    float x = asfloat(lines_heap[first + 3u * k]);
    float z = asfloat(lines_heap[first + 3u * k + 1u]);
    if (!moved) return float3(x, y, z);
    float h = asfloat(lines_heap[first + 3u * k + 2u]);
    float3 sun = push.rows.xyz;
    return float3(x - sun.x / sun.y * h, y, z - sun.z / sun.y * h);
}

/// A run's point k, projected: a shadow run's moved.
float3 stroke_point(uint tile, uint first, uint k, float y, bool shadow) {
    return shadow ? local_camera(tile, shadow_local(first, k, y, true)) : run_point(tile, first, k, y);
}

/// A point's device px in the view (unturned): its CSS point (snapped at rest) times the density.
float2 device_of(float3 k) {
    return project_css(frame.camera, k) * frame.camera.frame.z;
}

/// The quad's corners: 0 to 5 over two triangles, (along, across) in -1..1.
static const float2 quad_corners[6] = {float2(-1.0f, -1.0f), float2(1.0f, -1.0f), float2(1.0f, 1.0f),
                                       float2(-1.0f, -1.0f), float2(1.0f, 1.0f),  float2(-1.0f, 1.0f)};

[shader("vertex")]
StrokeOut stroke_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    uint word = words[push.mode.z + instance];
    uint run = push.at.w + (word >> 14u) * RUN_WORDS;
    uint seg = word & 0x3FFFu;
    uint tile = words[run];
    uint first = words[run + 1u];
    uint count = words[run + 2u];
    float y = asfloat(words[run + 3u]);
    float width = asfloat(words[run + 4u]);
    uint colour = words[run + 5u];
    uint slot = words[run + 6u];
    uint flags = words[run + 7u] & LINE_FLAG_BITS;
    uint table = words[run + 7u] >> LINE_DASH_SHIFT;
    StrokeOut o;
    o.colour = float4(0.0f, 0.0f, 0.0f, 0.0f);
    o.ab = float4(0.0f, 0.0f, 0.0f, 0.0f);
    o.pn = float4(0.0f, 0.0f, 0.0f, 0.0f);
    o.info = uint4(0u, 0u, 0u, 0u);
    o.dash = float4(0.0f, 0.0f, 0.0f, 0.0f);
    // a pair run's segment k is its line k, the points 2k and 2k + 1, without neighbours; a post's shadow its point's
    // foot to the point moved
    bool pairs = (flags & LINE_PAIRS) != 0u;
    bool shadow = (flags & LINE_SHADOW) != 0u;
    bool post = (flags & LINE_POST) != 0u;
    uint ia = pairs ? 2u * seg : seg;
    float3 ka = post ? local_camera(tile, shadow_local(first, 0u, y, false)) : stroke_point(tile, first, ia, y, shadow);
    float3 kb = post ? local_camera(tile, shadow_local(first, 0u, y, true))
                     : stroke_point(tile, first, ia + 1u, y, shadow);
    // a segment with an end behind the near plane is no part of a run
    if (ka.z < frame.camera.frame.w || kb.z < frame.camera.frame.w) {
        o.position = FOLDED;
        return o;
    }
    float2 a = device_of(ka);
    float2 b = device_of(kb);
    uint info = 0u;
    float2 p = a;
    float2 n = b;
    if (!pairs && !post && seg > 0u) {
        float3 kp = stroke_point(tile, first, seg - 1u, y, shadow);
        if (kp.z >= frame.camera.frame.w) {
            p = device_of(kp);
            info |= LINE_HAS_PREV;
        }
    }
    if (!pairs && !post && seg + 2u < count) {
        float3 kn = stroke_point(tile, first, seg + 2u, y, shadow);
        if (kn.z >= frame.camera.frame.w) {
            n = device_of(kn);
            info |= LINE_HAS_NEXT;
        }
    }
    float density = frame.camera.frame.z;
    float hw = width * density * 0.5f;
    float wide = 2.0f * hw;
    // a dashed run by its samples whatever its width (a park's edge is wider than two pixels anyway)
    bool dashed = (flags & LINE_DASH) != 0u;
    info |= dashed ? LINE_SAMPLES : (wide <= 1.0f ? LINE_HAIR : (wide < 2.0f ? LINE_AREA : LINE_SAMPLES));
    if (dashed) {
        // the table: the dash and the period (CSS px), then each point's place in the pattern
        o.dash = float4(asfloat(words[table + 2u + seg]), asfloat(words[table]), asfloat(words[table + 1u]), 1.0f);
        o.dash.xyz *= density;
    }
    // the quad: along the segment, its join's wedge and the coverage's reach beyond each end, across by half the
    // width and the reach
    float2 d = b - a;
    float len = length(d);
    float2 u = len > 1e-6f ? d / len : float2(1.0f, 0.0f);
    float2 v = float2(-u.y, u.x);
    float reach = hw * 4.0f + 2.0f;
    float2 c = quad_corners[vertex];
    // around a join the wedge stands out across too (a miter reaches four half widths): the quad as wide as it is long
    float2 at = (c.x < 0.0f ? a - u * reach : b + u * reach) + v * (c.y * reach);
    float2 css = at / density;
    o.position = clip_of(frame.camera, css, 1.0f, painter_depth(frame.camera, slot));
    float4 paint = unpack(colour);
    o.colour = float4(paint.rgb * paint.a, paint.a);
    o.ab = float4(a, b);
    o.pn = float4(p, n);
    // a sett road's line: the earlier lines it looks back at, its reaches' level for the ground's stretch at its ends
    uint back = 0u;
    if ((flags & LINE_LAYER) != 0u) {
        back = sett_reach(lines_heap[first + 2u * count + seg], sett_tolerance_at(tile, first, ia, y, hw));
    }
    o.info = uint4(info, asuint(hw), flags | (back << 16u), word);
    return o;
}

/// A fragment's segment: its ends and neighbours as the vertex stage found them.
Seg seg_of(StrokeOut s) {
    return seg_make(s.ab.xy, s.ab.zw, s.pn.xy, s.pn.zw, s.info.x, asfloat(s.info.y), s.info.z);
}

/// A pair run (a sett road's courses) as setts.slangh reads it: its tile, its points' first word, their count and their
/// height.
struct SettRun {
    uint tile;
    uint first;
    uint count;
    float y;
};

/// Line i of a pair run: its ends projected as the vertex stage projects them.
SettLine sett_line(SettRun r, uint i) {
    float3 ka = run_point(r.tile, r.first, 2u * i, r.y);
    float3 kb = run_point(r.tile, r.first, 2u * i + 1u, r.y);
    SettLine e;
    e.drawn = ka.z >= frame.camera.frame.w && kb.z >= frame.camera.frame.w;
    e.a = device_of(ka);
    e.b = device_of(kb);
    return e;
}

#include "ground/setts.slangh"

/// The tolerance line k of a pair run (its points 2k and 2k + 1 from `ia`) looks back over: setts.slangh's
/// sett_tolerance at each of its ends, the larger, from where a metre along it and across it goes on the screen
/// (unsnapped).
float sett_tolerance_at(uint tile, uint first, uint ia, float y, float hw) {
    float3 pa = run_local(first, ia, y);
    float3 pb = run_local(first, ia + 1u, y);
    float2 d = pb.xz - pa.xz;
    float l = length(d);
    if (!(l > 1e-6f)) return 1e9f;
    float3 u = float3(d.x / l, 0.0f, d.y / l);
    float3 n = float3(-u.z, 0.0f, u.x);
    float tolerance = 0.0f;
    for (uint e = 0u; e < 2u; ++e) {
        float3 p = e == 0u ? pa : pb;
        float3 k0 = local_camera(tile, p);
        float3 ku = local_camera(tile, p + u);
        float3 kn = local_camera(tile, p + n);
        if (k0.z < frame.camera.frame.w || ku.z < frame.camera.frame.w || kn.z < frame.camera.frame.w) return 1e9f;
        float2 s0 = css_of(frame.camera, k0) * frame.camera.frame.z;
        float2 su = css_of(frame.camera, ku) * frame.camera.frame.z - s0;
        float2 sn = css_of(frame.camera, kn) * frame.camera.frame.z - s0;
        tolerance = max(tolerance, sett_tolerance(su, sn, hw));
    }
    return tolerance;
}

/// The run of a fragment's instance word (StrokeOut.info.w) as a SettRun.
SettRun sett_run_of(uint word) {
    uint run = push.at.w + (word >> 14u) * RUN_WORDS;
    SettRun r;
    r.tile = words[run];
    r.first = words[run + 1u];
    r.count = words[run + 2u];
    r.y = asfloat(words[run + 3u]);
    return r;
}

/// The view's device point of an output position (a pixel's corner or a sample).
float2 device_at(float2 output) {
    return css_of_output(frame.camera, output) * frame.camera.frame.z;
}

/// Vulkan's standard sample positions in a pixel (D7): 4x, then 2x.
static const float2 samples4[4] = {float2(0.375f, 0.125f), float2(0.875f, 0.375f), float2(0.125f, 0.625f),
                                   float2(0.625f, 0.875f)};
static const float2 samples2[2] = {float2(0.75f, 0.75f), float2(0.25f, 0.25f)};

struct StrokeOutput {
    [[vk::location(0)]] float4 colour : SV_Target0;
    uint coverage : SV_Coverage;
};

/// A line's pixel: a hairline's or a thin stroke's coverage at the pixel's centre scaling its paint over every sample,
/// or a wide stroke's paint on the standard samples inside its shape (push.mode.w samples a pixel). One stage for all
/// three, so the lines blend in their painter's order whatever their widths.
[shader("fragment")]
StrokeOutput stroke_fs(StrokeOut s) {
    Seg g = seg_of(s);
    float2 corner = floor(s.position.xy);
    uint n = push.mode.w;
    StrokeOutput o;
    uint mode = s.info.x & 3u;
    // a sett road's courses: one layer at the paint's alpha (ground/setts.slangh), each line's share of it
    bool layer = (s.info.z & LINE_LAYER) != 0u;
    SettRun run = {0u, 0u, 0u, 0.0f};
    uint line = s.info.w & 0x3FFFu;
    if (layer) run = sett_run_of(s.info.w);
    uint reach = (s.info.z >> 16u) & 63u;
    if (mode == LINE_SAMPLES) {
        uint mask = 0u;
        float2 points[4] = {float2(0.0f, 0.0f), float2(0.0f, 0.0f), float2(0.0f, 0.0f), float2(0.0f, 0.0f)};
        for (uint i = 0u; i < n; ++i) {
            float2 at = n == 4u ? samples4[i] : (n == 2u ? samples2[i] : float2(0.5f, 0.5f));
            points[i] = device_at(corner + at);
        }
        if (g.len >= 1e-9f) {
            for (uint i = 0u; i < n; ++i) {
                if (seg_inside(g, points[i]) && in_dash(g, s.dash, points[i])) mask |= 1u << i;
            }
        }
        if (layer && mask != 0u) {
            mask &= ~sett_before_mask(run, line, reach, g.hw, points[0], points[1], points[2], points[3], n);
        }
        if (mask == 0u) discard;
        o.colour = s.colour;
        o.coverage = mask;
        return o;
    }
    float2 centre = device_at(corner + 0.5f);
    float cov;
    if (mode == LINE_HAIR) {
        // the pixel of the view's device grid the output's pixel is (a turned output keeps the grid)
        cov = hair_coverage(g, floor(centre), 2.0f * g.hw);
    } else {
        cov = g.len < 1e-9f ? 0.0f : seg_coverage(g, centre);
    }
    if (cov <= 0.0f) discard;
    float k = min(cov, 1.0f);
    if (layer) k = sett_share(k, sett_before(run, line, reach, g.hw, mode, floor(centre), centre), s.colour.a);
    o.colour = s.colour * k;
    o.coverage = 0xFFFFFFFFu;
    return o;
}

// ---------------------------------------------------------------- the quay walls (passes/Quays.cpp)
//
// A quay wall's face is an instance of 16 words (passes/Ground.hpp's quay records): its tile, its painter slot, its
// colour (RGBA8, opaque: lit and unlit mixed on the render thread by its sample's bit and the tile's fade), its flags
// and its four corners in its tile's metres. The vertex stage clips them at the near plane as Camera.poly does and
// projects them (snapped at rest), and draws the box of the polygon grown by its stroke's half width and a pixel; the
// fragment keeps the samples inside the polygon or within the half width of it (a fill with its own colour's stroke,
// round-joined: the polygon dilated), opaque at the slot's painter depth.

/// A quay face's record in `words`: 16 words.
static const uint QUAY_WORDS = 16u;
/// Its flags: edged by its own .3 stroke.
static const uint QUAY_STROKE = 1u;

struct QuayOut {
    float4 position : SV_Position;
    nointerpolation float4 colour : COLOR0;
    nointerpolation float4 p01 : TEXCOORD0; // the polygon's points 0 and 1, device px of the view
    nointerpolation float4 p23 : TEXCOORD1; // points 2 and 3
    nointerpolation float4 p4 : TEXCOORD2;  // point 4, the half width (device px) and the point count
};

/// Corner i of a quay face's record from word `at`, in its tile's metres.
float3 quay_corner(uint at, uint i) {
    return float3(asfloat(words[at + 4u + 3u * i]), asfloat(words[at + 5u + 3u * i]), asfloat(words[at + 6u + 3u * i]));
}

[shader("vertex")]
QuayOut quay_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    uint at = push.mode.z + instance * QUAY_WORDS;
    uint tile = words[at];
    uint slot = words[at + 1u];
    uint colour = words[at + 2u];
    uint flags = words[at + 3u];
    QuayOut o;
    o.colour = float4(0.0f, 0.0f, 0.0f, 0.0f);
    o.p01 = float4(0.0f, 0.0f, 0.0f, 0.0f);
    o.p23 = float4(0.0f, 0.0f, 0.0f, 0.0f);
    o.p4 = float4(0.0f, 0.0f, 0.0f, 0.0f);
    // Camera.poly's near clip: each corner ahead of the near plane, and where an edge crosses it
    float near = frame.camera.frame.w;
    float2 p[5] = {float2(0.0f, 0.0f), float2(0.0f, 0.0f), float2(0.0f, 0.0f), float2(0.0f, 0.0f), float2(0.0f, 0.0f)};
    uint n = 0u;
    for (uint i = 0u; i < 4u; ++i) {
        float3 a = local_camera(tile, quay_corner(at, i));
        float3 b = local_camera(tile, quay_corner(at, (i + 1u) % 4u));
        bool ai = a.z >= near;
        bool bi = b.z >= near;
        if (ai && n < 5u) {
            p[n] = device_of(a);
            n = n + 1u;
        }
        if (ai != bi && n < 5u) {
            float t = (near - a.z) / (b.z - a.z);
            float3 c = float3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, near);
            p[n] = device_of(c);
            n = n + 1u;
        }
    }
    if (n < 3u) {
        o.position = FOLDED;
        return o;
    }
    float density = frame.camera.frame.z;
    float hw = (flags & QUAY_STROKE) != 0u ? 0.15f * density : 0.0f;
    float2 lo = p[0];
    float2 hi = p[0];
    for (uint i = 1u; i < n; ++i) {
        lo = min(lo, p[i]);
        hi = max(hi, p[i]);
    }
    lo = lo - (hw + 1.0f);
    hi = hi + (hw + 1.0f);
    float2 c = quad_corners[vertex];
    float2 device = float2(c.x < 0.0f ? lo.x : hi.x, c.y < 0.0f ? lo.y : hi.y);
    o.position = clip_of(frame.camera, device / density, 1.0f, painter_depth(frame.camera, slot));
    o.colour = unpack(colour);
    o.p01 = float4(p[0], p[1]);
    o.p23 = float4(p[2], n > 3u ? p[3] : p[2]);
    o.p4 = float4(n > 4u ? p[4] : p[n - 1u], hw, float(n));
    return o;
}

/// Whether device point x lies in a convex polygon of m points (either way round) or within hw of it.
bool in_quay(float2 pts[5], uint m, float hw, float2 x) {
    float twice = 0.0f;
    for (uint i = 0u; i < m; ++i) {
        float2 a = pts[i];
        float2 b = pts[(i + 1u) % m];
        twice = twice + (a.x * b.y - b.x * a.y);
    }
    float sgn = twice >= 0.0f ? 1.0f : -1.0f;
    bool inside = true;
    float nearest = 1e30f;
    for (uint i = 0u; i < m; ++i) {
        float2 a = pts[i];
        float2 e = pts[(i + 1u) % m] - a;
        float2 q = x - a;
        if (sgn * (e.x * q.y - e.y * q.x) < 0.0f) inside = false;
        float l2 = dot(e, e);
        float t = l2 > 0.0f ? clamp(dot(q, e) / l2, 0.0f, 1.0f) : 0.0f;
        nearest = min(nearest, length(q - e * t));
    }
    return inside || (hw > 0.0f && nearest <= hw);
}

/// A quay face's pixel: its colour on the samples inside it (push.mode.w samples a pixel).
[shader("fragment")]
StrokeOutput quay_fs(QuayOut q) {
    float2 pts[5] = {q.p01.xy, q.p01.zw, q.p23.xy, q.p23.zw, q.p4.xy};
    uint m = uint(q.p4.w);
    float2 corner = floor(q.position.xy);
    uint n = push.mode.w;
    uint mask = 0u;
    for (uint i = 0u; i < n; ++i) {
        float2 at = n == 4u ? samples4[i] : (n == 2u ? samples2[i] : float2(0.5f, 0.5f));
        if (in_quay(pts, m, q.p4.z, device_at(corner + at))) mask |= 1u << i;
    }
    if (mask == 0u) discard;
    StrokeOutput o;
    o.colour = q.colour;
    o.coverage = mask;
    return o;
}
