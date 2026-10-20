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
//   - stroke_vs and stroke_fs: the ground's lines (passes/Lines.cpp).
//   - shadow_vs: the ground shadows (passes/Shadows.cpp), moved by the moment's sun: a prism's footprint hull swept
//     along its offset (each hull point twice, before and after its turn), a raised outline or a crown disc moved by
//     it, their union set in the stencil and composited by flat_fs.
//
// Colours are the renders' 8-bit sRGB values, blended as the renders composite encoded values (D7, "Gamma"); a
// translucent colour is premultiplied. Not an exact module: positions are the vertex stage's float maths, held to the
// twin's (twin/Emit.hpp) by L2.

#include "shared/frame.slang"
#include "shared/painter.slang"

// K17a's mat (render's `mat` filter over draw_under's land, the areas, the roads and draw_over's fills, D10), where
// mat_on says: the frame's fields bound, in full mode, and the moment's weather, with which alone render defines #mat.
// ATLAS_GROUND_MAT keeps the switch in one place.
#define ATLAS_GROUND_MAT
#if defined(ATLAS_GROUND_MAT)
#define ATLAS_FX_FUNCTIONS
#include "fx/mat.slang"
#endif

[[vk::binding(0, 0)]] ConstantBuffer<FrameBlock> frame;
[[vk::binding(2, 1)]] StructuredBuffer<uint> ground_heap;
[[vk::binding(3, 1)]] StructuredBuffer<uint> lines_heap;
/// The pass's words for this frame (passes/Ground.cpp's FrameWords): the tile table, the slot table, the theme's
/// colours, the fills' visibility bits, the lines' runs and segments.
[[vk::binding(0, 2)]] StructuredBuffer<uint> words;

/// A draw's own values: where its tables lie in `words`, its mode and its colours.
struct GroundPush {
    uint4 at;     // x: the tile table's first word, y: the slot table's, z: the theme colours', w: the runs' (lines)
    uint4 mode;   // x: the GROUND_ flags, y: the painter slot of a screen pass, z: the instances' first word (lines), w: 0
    float4 rows;  // x, y: a gradient's first and last rows (CSS px); z: a screen pass's opacity; w: 0
    uint4 colour; // x, y: a gradient's two colours (RGBA8); z: a screen pass's colour; w: 0
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
/// its ground range, 17 its shadows' records'.
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
    uint order = ground_heap[r + 2u];
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

/// A shadow's vertex: its index from the ground heap at word `index` names the shadow (index >> 16) and its corner
/// (index & 0xFFFF), its tile the instance. The offset is −L.xz / L.y · h (push.rows.xyz: L, toward the sun). A raised
/// outline or a crown disc is moved by it whole. A footprint's hull is swept along it: the edges that face it move,
/// the others stay, and the two points where the facing edges begin and end come twice, staying and moved (count + 2
/// corners, a convex fan: the hull of the footprint and its moved copy). Drawn at the shadows' painter slot
/// (push.mode.y), where the frame drew the shadow (its radius cut).
[shader("vertex")]
FillOut shadow_vs(uint index : SV_VulkanVertexID, uint tile : SV_VulkanInstanceID) {
    uint base = tile_word(tile, 8u);
    uint id = ground_heap[index];
    uint s = id >> 16u;
    uint j = id & 0xFFFFu;
    FillOut o;
    o.colour = float4(0.0f, 0.0f, 0.0f, 0.0f);
    set_mat(o, false);
    if (!bit(tile_word(tile, 15u), s)) {
        o.position = FOLDED;
        return o;
    }
    uint r = base + tile_word(tile, 17u) + 5u * s;
    uint first = ground_heap[r];
    uint count = ground_heap[r + 1u];
    float h = asfloat(ground_heap[r + 2u]);
    uint flags = ground_heap[r + 3u];
    float y = asfloat(ground_heap[r + 4u]);
    float3 sun = push.rows.xyz;
    float2 d = float2(-sun.x / sun.y * h, -sun.z / sun.y * h);
    uint i = min(j, count - 1u);
    bool moved = true;
    if ((flags & SHADOW_MOVED) == 0u) {
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
    float2 p = moved ? q + d : q;
    o.position = place(tile, float3(p.x, y, p.y), push.mode.y);
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

/// The lines' flags (a run's word 7).
static const uint LINE_CAP_SQUARE = 1u; // its free ends' caps (butt without)
static const uint LINE_CAP_ROUND = 2u;
static const uint LINE_JOIN_ROUND = 4u; // its joins (miter, limit 4, without)
static const uint LINE_JOIN_BEVEL = 8u;

/// The line stage's modes (StrokeOut.info.x's low bits), by the device width: a hairline, the exact area, the samples.
static const uint LINE_HAIR = 0u;
static const uint LINE_AREA = 1u;
static const uint LINE_SAMPLES = 2u;
static const uint LINE_HAS_PREV = 4u;
static const uint LINE_HAS_NEXT = 8u;

struct StrokeOut {
    float4 position : SV_Position;
    nointerpolation float4 colour : COLOR0; // the paint, premultiplied
    nointerpolation float4 ab : TEXCOORD0;  // its ends a and b, device px of the view
    nointerpolation float4 pn : TEXCOORD1;  // the point before a and the point after b (where LINE_HAS_PREV, _NEXT)
    nointerpolation uint4 info : TEXCOORD2; // x: mode and LINE_HAS_ bits; y: the half width (device px) as bits; z: the
                                            // run's LINE_ flags; w: 0
};

/// A run's point k, projected: its camera coordinates.
float3 run_point(uint tile, uint first, uint k, float y) {
    float x = asfloat(lines_heap[first + 2u * k]);
    float z = asfloat(lines_heap[first + 2u * k + 1u]);
    return camera_of(frame.camera, eye_relative(place_of(tile), float3(x, y, z)));
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
    uint flags = words[run + 7u];
    StrokeOut o;
    o.colour = float4(0.0f, 0.0f, 0.0f, 0.0f);
    o.ab = float4(0.0f, 0.0f, 0.0f, 0.0f);
    o.pn = float4(0.0f, 0.0f, 0.0f, 0.0f);
    o.info = uint4(0u, 0u, 0u, 0u);
    float3 ka = run_point(tile, first, seg, y);
    float3 kb = run_point(tile, first, seg + 1u, y);
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
    if (seg > 0u) {
        float3 kp = run_point(tile, first, seg - 1u, y);
        if (kp.z >= frame.camera.frame.w) {
            p = device_of(kp);
            info |= LINE_HAS_PREV;
        }
    }
    if (seg + 2u < count) {
        float3 kn = run_point(tile, first, seg + 2u, y);
        if (kn.z >= frame.camera.frame.w) {
            n = device_of(kn);
            info |= LINE_HAS_NEXT;
        }
    }
    float density = frame.camera.frame.z;
    float hw = width * density * 0.5f;
    float wide = 2.0f * hw;
    info |= wide <= 1.0f ? LINE_HAIR : (wide < 2.0f ? LINE_AREA : LINE_SAMPLES);
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
    o.info = uint4(info, asuint(hw), flags, 0u);
    return o;
}

/// Strokes.boxCdf: the share of a unit pixel box centred at the origin on the near side of the line n·o = t.
float box_cdf(float t, float nx, float ny) {
    float a = abs(nx) * 0.5f;
    float b = abs(ny) * 0.5f;
    if (b > a) {
        float q = a;
        a = b;
        b = q;
    }
    if (t <= -(a + b)) return 0.0f;
    if (t >= a + b) return 1.0f;
    if (b < 1e-9f) return saturate(0.5f + t / (2.0f * a));
    if (t < -(a - b)) {
        float w = t + a + b;
        return w * w / (8.0f * a * b);
    }
    if (t <= a - b) return 0.5f + t / (2.0f * a);
    float w = a + b - t;
    return 1.0f - w * w / (8.0f * a * b);
}

/// Strokes.radial: the overlap of d - hw ... d + hw with -0.5 ... 0.5.
float radial(float d, float hw) {
    return max(0.0f, min(d + hw, 0.5f) - max(d - hw, -0.5f));
}

/// A segment's shape (RefRaster's SegGeom): its ends, direction, trims and join.
struct Seg {
    float2 a;
    float2 b;
    float len;
    float2 u;
    float2 n;
    bool prev;
    bool next;
    float2 sa; // the bisector with the predecessor
    float2 v;  // the successor's direction
    float2 sb; // the bisector with the successor
    float2 oi;
    float2 oo;
    float2 o;
    float ol;
    bool bevel;
    bool round_join;
    uint cap;
    float hw;
};

Seg seg_of(StrokeOut s) {
    Seg g;
    g.a = s.ab.xy;
    g.b = s.ab.zw;
    g.hw = asfloat(s.info.y);
    g.prev = (s.info.x & LINE_HAS_PREV) != 0u;
    g.next = (s.info.x & LINE_HAS_NEXT) != 0u;
    uint flags = s.info.z;
    g.cap = (flags & LINE_CAP_ROUND) != 0u ? 1u : ((flags & LINE_CAP_SQUARE) != 0u ? 2u : 0u);
    g.round_join = (flags & LINE_JOIN_ROUND) != 0u;
    float2 d = g.b - g.a;
    g.len = length(d);
    g.u = g.len < 1e-9f ? float2(0.0f, 0.0f) : d / g.len;
    g.n = float2(-g.u.y, g.u.x);
    g.sa = float2(0.0f, 0.0f);
    g.v = float2(0.0f, 0.0f);
    g.sb = float2(0.0f, 0.0f);
    if (g.prev) {
        float2 e = g.a - s.pn.xy;
        float l = length(e);
        if (l > 1e-9f) g.sa = e / l + g.u;
        if (length(g.sa) < 1e-6f) g.sa = g.n;
    }
    if (g.next) {
        float2 e = s.pn.zw - g.b;
        float l = length(e);
        if (l > 1e-9f) g.v = e / l;
        g.sb = g.u + g.v;
        if (length(g.sb) < 1e-6f) g.sb = g.n;
    }
    float turn = g.u.x * g.v.y - g.u.y * g.v.x;
    float sgn = turn > 0.0f ? -1.0f : 1.0f;
    g.oi = float2(-g.u.y * sgn, g.u.x * sgn);
    g.oo = float2(-g.v.y * sgn, g.v.x * sgn);
    g.o = g.oi + g.oo;
    g.ol = length(g.o);
    float half = sqrt(max(0.0f, (1.0f + dot(g.u, g.v)) * 0.5f));
    bool bevel_join = (flags & LINE_JOIN_BEVEL) != 0u;
    g.bevel = bevel_join || (!g.round_join && (half == 0.0f || 1.0f / half > 4.0f));
    return g;
}

/// SegGeom.coverage: G1's exact-area coverage of the pixel centred at c (device px).
float seg_coverage(Seg g, float2 c) {
    float2 q = c - g.a;
    float al = dot(q, g.u);
    float pe = dot(q, g.n);
    if (g.prev && (al < 0.0f || dot(q, g.sa) < 0.0f)) return 0.0f;
    float2 r = c - g.b;
    if (g.next && al > g.len) {
        if (dot(r, g.v) >= 0.0f) return 0.0f;
        if (g.round_join) return radial(length(r), g.hw);
        if (g.bevel) return g.ol < 1e-9f ? 0.0f : saturate(0.5f + g.hw * abs(dot(g.oi, g.o)) / g.ol - dot(r, g.o) / g.ol);
        return saturate(0.5f + g.hw - dot(r, g.oi)) * saturate(0.5f + g.hw - dot(r, g.oo));
    }
    if (g.next && dot(r, g.sb) >= 0.0f) return 0.0f;
    if (!g.prev && g.cap == 1u && al < 0.0f) return radial(length(q), g.hw);
    if (!g.next && g.cap == 1u && al > g.len) return radial(length(r), g.hw);
    if (abs(pe) > g.hw + 1.0f) return 0.0f;
    float strip = box_cdf(g.hw - pe, g.n.x, g.n.y) - box_cdf(-g.hw - pe, g.n.x, g.n.y);
    float ext_a = (!g.prev && g.cap == 2u) ? g.hw : 0.0f;
    float ext_b = (!g.next && g.cap == 2u) ? g.hw : 0.0f;
    float lo = (!g.prev && g.cap != 1u) ? -ext_a : -1e9f;
    float hi = (!g.next && g.cap != 1u) ? g.len + ext_b : 1e9f;
    return strip * (box_cdf(hi - al, g.u.x, g.u.y) - box_cdf(lo - al, g.u.x, g.u.y));
}

/// SegGeom.inside: whether the point x (device px) lies in the segment's shape.
bool seg_inside(Seg g, float2 x) {
    float2 q = x - g.a;
    float al = dot(q, g.u);
    float pe = dot(q, g.n);
    if (g.prev && (al < 0.0f || dot(q, g.sa) < 0.0f)) return false;
    float2 r = x - g.b;
    if (g.next && al > g.len) {
        if (dot(r, g.v) >= 0.0f) return false;
        if (g.round_join) return length(r) <= g.hw;
        if (g.bevel) return g.ol >= 1e-9f && dot(r, g.o) <= g.hw * abs(dot(g.oi, g.o));
        return dot(r, g.oi) <= g.hw && dot(r, g.oo) <= g.hw;
    }
    if (g.next && dot(r, g.sb) >= 0.0f) return false;
    if (!g.prev && g.cap == 1u && al < 0.0f) return length(q) <= g.hw;
    if (!g.next && g.cap == 1u && al > g.len) return length(r) <= g.hw;
    float ext_a = (!g.prev && g.cap == 2u) ? g.hw : 0.0f;
    float ext_b = (!g.next && g.cap == 2u) ? g.hw : 0.0f;
    float lo = (!g.prev && g.cap != 1u) ? -ext_a : -1e9f;
    float hi = (!g.next && g.cap != 1u) ? g.len + ext_b : 1e9f;
    return abs(pe) <= g.hw && al >= lo && al <= hi;
}

/// Strokes.hairline's coverage of the pixel at integer (px, py) (device px of the view): Skia's anti-aliased hairline,
/// the segment alone, a capped free end half a pixel longer.
float hair_coverage(Seg g, float2 pixel, float width) {
    bool x_major = abs(g.b.x - g.a.x) >= abs(g.b.y - g.a.y);
    float lo = x_major ? g.a.x : g.a.y;
    float hi = x_major ? g.b.x : g.b.y;
    float m0 = x_major ? g.a.y : g.a.x;
    float m1 = x_major ? g.b.y : g.b.x;
    bool ext_lo = !g.prev && g.cap != 0u;
    bool ext_hi = !g.next && g.cap != 0u;
    if (lo > hi) {
        float t = lo;
        lo = hi;
        hi = t;
        t = m0;
        m0 = m1;
        m1 = t;
        bool e = ext_lo;
        ext_lo = ext_hi;
        ext_hi = e;
    }
    float len = hi - lo;
    float slope = len > 1e-12f ? (m1 - m0) / len : 0.0f;
    float l0 = lo - (ext_lo ? 0.5f : 0.0f);
    float h0 = hi + (ext_hi ? 0.5f : 0.0f);
    float u = x_major ? pixel.x : pixel.y;
    float v = x_major ? pixel.y : pixel.x;
    float c = u + 0.5f;
    float span = min(c + 0.5f, h0) - max(c - 0.5f, l0);
    if (span <= 0.0f) return 0.0f;
    float m = m0 + slope * (min(max(c, lo), hi) - lo);
    return max(0.0f, 1.0f - abs(v + 0.5f - m)) * span * width;
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
    if (mode == LINE_SAMPLES) {
        uint mask = 0u;
        if (g.len >= 1e-9f) {
            for (uint i = 0u; i < n; ++i) {
                float2 at = n == 4u ? samples4[i] : (n == 2u ? samples2[i] : float2(0.5f, 0.5f));
                if (seg_inside(g, device_at(corner + at))) mask |= 1u << i;
            }
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
    o.colour = s.colour * min(cov, 1.0f);
    o.coverage = 0xFFFFFFFFu;
    return o;
}
