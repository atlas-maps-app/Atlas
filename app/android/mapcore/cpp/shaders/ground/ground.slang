// The ground (plan-vector D10 "Ground" and "Water", D5.12's passes 3 and 4, D6; CP/passes/Ground.cpp, K9): what the
// renders draw on the ground, as the frame's opaque ground (top first, painter depth) and its translucent elements
// (painter's order, depth tested, not written).
//
//   - fill_vs and colour_fs: a tile's fills (the land, the areas, the roads, the region's ballast, asphalt and marine
//     works), pulled from the ground heap by index (a draw's firstVertex is its first index's word, its firstInstance
//     the tile's place in the frame's tile table), each vertex placed by its tile, projected as camera.slang projects
//     (snapped at rest), at its fill's painter depth: its group's base and stride from the frame's slot table, its
//     order and sub-element (geometry/Ground.hpp), in its day or night colour or the theme's. A fill the frame does not
//     draw (the renders' cull and area test, decided on the CPU) folds its triangles away. In the app's map a street, a
//     big road's asphalt and a runway's or a taxiway's pavement are as wide as their way (GROUND_WIDE), where the
//     renders lay them half as wide; the streets are drawn by their own draws after the land (passes/Ground.cpp's
//     record_streets, street_vs with colour_over_fs), over what lies under them: off a bridge (GROUND_STREETS) where
//     the stencil holds no water at the street, so that a street keeps off the canals that a bridge's street crosses,
//     then on a bridge over the water; and a street narrower on the screen than LEAST_PX device px is drawn LEAST_PX
//     wide at the share of them its width covers (GROUND_LEAST, least_point), so that its colour across it holds while
//     it moves, where 4 samples a pixel would step.
//   - water_vs: the water's pieces' fans at the street's height or at their water level, counted in the stencil
//     (front faces increment, back faces decrement, wrapping), so the clips are nonzero unions (passes/Water.cpp).
//   - thin_vs, thin_band_vs and thin_fs: the app's map's thin water (passes/Water.cpp), where the water is narrower on
//     the screen than LEAST_PX across a thin edge of its pieces (geometry/Water.hpp's thin_edges): the edge's quad
//     across the water leaves the count there to its band, LEAST_PX wide along the water's middle at the share of
//     them the water covers (thin_of), in the surface's colour with its tint.
//   - screen_vs with sky_fs, gradient_fs and flat_fs: one triangle over the frame at a painter slot's depth: the sky
//     (render's #sky: sky_top to sky_hor between two rows), the water's surface (#wgrad, or th['water'] without
//     weather), the water's tint, and the stencil's unions composited at their opacity (the shadows, the canopy);
//     screen_vs with canopy_fs: the canopy's union through its filter where the fields are bound in full mode.
//   - stroke_vs and stroke_fs: the ground's lines (passes/Lines.cpp; their coverage in ground/lines.slangh), a sett
//     road's courses each line's share of the path's layer (ground/setts.slangh).
//   - quay_vs and quay_fs: the quay walls' faces (passes/Quays.cpp); reflection_vs and reflection_fs: the water's
//     reflections inside its clip, each sample composited as their group composites it over the water
//     (passes/Reflections.cpp); glitter_vs and glitter_fs: the sun's glitter, the glints' layer screened over the
//     water once a sample (passes/Glitter.cpp); screen_vs with rings_fs: the drizzle's rings, their pattern over the
//     water (passes/Rings.cpp).
//   - screen_vs with clouds_fs: the sky layer's clouds and their shadows, each filter's result from its table;
//   - disc_vs with sunglow_fs and sundisc_fs, screen_vs with cityglow_fs: the sky layer's sun (its glow and its disc)
//     and the city's glow at night, translucent at their slots over the sky (passes/Sky.cpp).
//   - lit_vs and lit_fs: the lit streets' runs into the glow's and the wet cores' layers; screen_vs with
//     lit_composite_fs: the glow (its filter's result) and the cores screened over the ground at night, after the quay
//     walls (passes/LitGlow.cpp).
//   - pool_vs and pool_fs: the street lamps' pools at night, after the lit streets (passes/Pools.cpp).
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
#include "shared/heaps.slang"
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
[[vk::binding(4, 1)]] StructuredBuffer<uint> heap_high; // the heaps' second buffer (shared/heaps.slang)

/// Word i of the ground's heaps and of the lines', in whichever buffer holds it.
uint ground_word(uint i) {
    return i < HEAP_HIGH_WORDS ? ground_heap[i] : heap_high[i - HEAP_HIGH_WORDS];
}
uint lines_word(uint i) {
    return i < HEAP_HIGH_WORDS ? lines_heap[i] : heap_high[i - HEAP_HIGH_WORDS];
}
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
/// The rippled reflections (ripple.slang's two buffers, the frame's upright device px a word each: premultiplied linear
/// RGB, a r g b): the blur's last pass leaves the result in one of them (ripple_fs's push.mode.z).
[[vk::binding(3, 3)]] StructuredBuffer<uint> ripple_a;
[[vk::binding(4, 3)]] StructuredBuffer<uint> ripple_b;
/// The lit streets (passes/LitGlow.cpp): the glow's filter's result (litglow.slang's first buffer, the layers' px a
/// word each: premultiplied linear RGB, a r g b), the glow's layer itself and the wet cores' (premultiplied sRGB).
[[vk::binding(5, 3)]] StructuredBuffer<uint> glow_result;
[[vk::binding(6, 3)]] Texture2D<float4> glow_layer;
[[vk::binding(7, 3)]] Texture2D<float4> core_layer;

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
static const uint GROUND_LAYER = 8u;       // into the reflections' layer (Ripple.cpp): the upright view, its own colours
static const uint GROUND_WIDE = 16u;       // fill_vs: the ribbons' pavements as wide as their ways (the app's map)
static const uint GROUND_NEAR_CLIP = 32u;  // stroke_vs: a line of a fixed width from the near plane on (the app's map)
static const uint GROUND_STREETS = 64u;    // fill_vs: the streets' own draw, each off a bridge (with GROUND_WIDE)
static const uint GROUND_LEAST = 128u;     // street_vs: a thin street LEAST_PX wide at its coverage (with GROUND_WIDE)
static const uint GROUND_COURSES = 256u;   // stroke_vs: the runs' points the quay walls' courses', in `words` (x, y, z)

/// The painter groups whose fills are ribbons as wide as their ways in the app's map (geometry::group): S['roads'], a
/// group a class from road up to draw_over's; draw_over's big roads (over + 1), their asphalt its one fill; and its
/// runways and taxiways (over + 2), their pavements its sub-element 0 (aero_sub::pavement).
static const uint GROUP_ROAD = 8u;
static const uint GROUP_OVER = 24u;
static const uint GROUP_BIG = 25u;
static const uint GROUP_AERO = 26u;
/// A street's height: 0.05 m, on a bridge 0.45 (geometry/Ground.cpp's roads); a bridge's is over this.
static const float BRIDGE_Y = 0.25f;

bool street_group(uint group) {
    return group >= GROUP_ROAD && group < GROUP_OVER;
}

bool wide_group(uint group, uint sub) {
    return street_group(group) || group == GROUP_BIG || (group == GROUP_AERO && sub == 0u);
}

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

/// A fill's colour word as the user's palette has it (passes/Ground.cpp's ground_palette): the table's first word is
/// the theme colours' fourth (0: none), 256 pairs of a colour today and the user's, a colour's at its hash
/// (palette_hash) or in the first pairs after it, an empty pair ending the search. A theme colour's word is not in it.
uint repainted(uint c) {
    uint at = words[push.at.z + 3u];
    if (at == 0u || (c >> 24u) == 0u) {
        return c;
    }
    uint h = (c * 2654435761u) >> 24u;
    for (uint k = 0u; k < 256u; k++) {
        uint i = at + 2u * ((h + k) & 255u);
        uint from = words[i];
        if (from == c) {
            return words[i + 1u];
        }
        if (from == 0u) {
            return c;
        }
    }
    return c;
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
    float3 k = camera_of(frame.camera, placed(frame.camera, place_of(t), local));
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

/// A street's fill in the app's map (street_vs, fill_fading_vs): fill_vs's with its share of the pixels it is drawn
/// over, below 1 where the least width draws it wider than it is (least_point).
struct StreetOut {
    FillOut fill;
    float cover : COLOR2;
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

/// The point mirroring a ribbon's vertex v across its centreline. The ribbon's vertices are its left side, then its
/// right side reversed, so a point and its mirror sum to the same s throughout; its quads are the triangles (l, l + 1,
/// s - l - 1) and (l, s - l - 1, s - l) (geometry::pack), so the vertex's own triangle (its word `index`) tells s, the
/// first kind where its second corner follows its first. The tile's triangle lists follow its vertices' words, 3 a
/// vertex, and hold whole triangles, so a word's corner is its place past the tile's first word, mod 3.
float2 mirror_point(uint tile, uint index, uint v) {
    uint base = tile_word(tile, 8u);
    uint t0 = index - (index - base) % 3u;
    uint a = ground_word(t0);
    uint b = ground_word(t0 + 1u);
    uint s = ground_word(t0 + 2u) + (b == a + 1u ? b : a);
    uint at = base + tile_word(tile, 9u) + 3u * (s - v);
    return float2(asfloat(ground_word(at)), asfloat(ground_word(at + 1u)));
}

/// A ribbon's vertex p with its mirror m (mirror_point), as wide as its way (GROUND_WIDE): render's ribbon(pts, w / 2)
/// lays its sides w / 4 from the centreline, half the way's width, where a big road's edge lines lie at w / 2 - 0.5, a
/// runway's threshold bars and designators reach w / 2 and a sett road's courses run across w, so each side point
/// moves twice as far from the centreline, the middle of it and its mirror on the other side.
float2 wide_point(float2 p, float2 m) {
    return 2.0f * p - 0.5f * (p + m);
}

/// The least width a street is drawn at on the screen, device px (GROUND_LEAST).
static const float LEAST_PX = 1.5f;
/// How far least_point looks along a street either way of its centreline for its direction on the screen (metres).
static const float LEAST_ALONG = 32.0f;

/// A street's vertex w as wide as its way (wide_point) and its mirror's wm, at height y: where the street is narrower
/// on the screen than LEAST_PX device px, measured across its direction there at its centreline, both move out from the
/// centreline to LEAST_PX apart and `cover` takes the street's width over LEAST_PX, the share of them it covers, so
/// that across it the street keeps its colour's sum as the renders' analytic coverage gives a thin path its share of a
/// pixel, where 4 samples a pixel give it 0 to 4 quarters as it moves: a street 1 px wide is drawn 1.5 px wide at 2/3.
/// The vertex and its mirror measure the same width (each of their differences only changes its sign), so the two move
/// apart alike. A street whose points reach the near plane is as it was (it is wide there).
float2 least_point(uint tile, float y, float2 w, float2 wm, inout float cover) {
    float2 across = w - wm;
    float l = length(across);
    if (!(l > 0.0f)) {
        return w;
    }
    float2 c = 0.5f * (w + wm);
    float2 along = float2(-across.y, across.x) * (LEAST_ALONG / l);
    float2 a = c + along;
    float2 b = c - along;
    TilePlace t = place_of(tile);
    float3 kw = camera_of(frame.camera, placed(frame.camera, t, float3(w.x, y, w.y)));
    float3 km = camera_of(frame.camera, placed(frame.camera, t, float3(wm.x, y, wm.y)));
    float3 ka = camera_of(frame.camera, placed(frame.camera, t, float3(a.x, y, a.y)));
    float3 kb = camera_of(frame.camera, placed(frame.camera, t, float3(b.x, y, b.y)));
    if (min(min(kw.z, km.z), min(ka.z, kb.z)) <= frame.camera.frame.w) {
        return w;
    }
    // the street's direction on the screen and its width across it, device px (none where the direction has none)
    float2 d = css_of(frame.camera, ka) - css_of(frame.camera, kb);
    float2 s = css_of(frame.camera, kw) - css_of(frame.camera, km);
    float px = abs(s.x * d.y - s.y * d.x) / length(d) * frame.camera.frame.z;
    if (!(px < LEAST_PX)) {
        return w;
    }
    cover = px / LEAST_PX;
    return c + (w - c) * (LEAST_PX / max(px, 1.0e-4f));
}

/// A fill's vertex: its index from the ground heap at word `index` (the draw's firstVertex counts the words), its tile
/// the instance (the draw's firstInstance); where `least` and the draw's GROUND_LEAST say, a street at least LEAST_PX
/// wide and `cover` its share of them (least_point).
FillOut fill_of(uint index, uint tile, bool least, inout float cover) {
    uint base = tile_word(tile, 8u);
    uint v = ground_word(index);
    uint at = base + tile_word(tile, 9u) + 3u * v;
    float x = asfloat(ground_word(at));
    float z = asfloat(ground_word(at + 1u));
    uint f = ground_word(at + 2u);
    uint r = base + tile_word(tile, 10u) + 5u * f;
    FillOut o;
    set_mat(o, false);
    if ((push.mode.x & GROUND_VISIBLE_ALL) == 0u && !bit(tile_word(tile, 13u), f)) {
        o.position = FOLDED;
        o.colour = float4(0.0f, 0.0f, 0.0f, 0.0f);
        return o;
    }
    float y = asfloat(ground_word(r));
    uint gs = ground_word(r + 1u);
    // its order in its group: its scene's, or where the frame draws tiles of more than one scene its rank across them
    uint ranks = tile_word(tile, 19u);
    uint order = ranks != 0u ? words[ranks + f] : ground_word(r + 2u);
    uint c = repainted((push.mode.x & GROUND_NIGHT) != 0u ? ground_word(r + 4u) : ground_word(r + 3u));
    if ((push.mode.x & GROUND_WIDE) != 0u && wide_group(gs & 0xFFFFu, gs >> 16u)) {
        // a street off a bridge in the streets' own draw only, one on a bridge (over the water) in a draw without the
        // flag (the bridges', a fading tile's rest)
        if (street_group(gs & 0xFFFFu) && (y < BRIDGE_Y) != ((push.mode.x & GROUND_STREETS) != 0u)) {
            o.position = FOLDED;
            o.colour = float4(0.0f, 0.0f, 0.0f, 0.0f);
            return o;
        }
        float2 q = float2(x, z);
        float2 m = mirror_point(tile, index, v);
        float2 w = wide_point(q, m);
        if (least && (push.mode.x & GROUND_LEAST) != 0u && street_group(gs & 0xFFFFu)) {
            w = least_point(tile, y, w, wide_point(m, q), cover);
        }
        x = w.x;
        z = w.y;
    }
    o.position = place(tile, float3(x, y, z), slot_of(gs & 0xFFFFu, gs >> 16u, order));
    float4 p = paint_of(c);
    // a translucent fill (the canopy's union is composited instead): premultiplied by the screen pass's opacity
    o.colour = float4(p.rgb, 1.0f);
    set_mat(o, in_mat(gs & 0xFFFFu));
    return o;
}

/// A fill's vertex (fill_of).
[shader("vertex")]
FillOut fill_vs(uint index : SV_VulkanVertexID, uint tile : SV_VulkanInstanceID) {
    float cover = 1.0f;
    return fill_of(index, tile, false, cover);
}

/// A street's vertex in the app's map (passes/Ground.cpp's record_streets): fill_of's, a thin one at least LEAST_PX
/// wide at its share of them.
[shader("vertex")]
StreetOut street_vs(uint index : SV_VulkanVertexID, uint tile : SV_VulkanInstanceID) {
    StreetOut o;
    o.cover = 1.0f;
    o.fill = fill_of(index, tile, true, o.cover);
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
    o.colour = float4(mat_colour(frame.camera, v.colour.rgb, v.position.xy, mat_grain_on(frame) && v.mat != 0u), v.colour.a);
#endif
    return o;
}

/// A fill of a tile fading in (passes/Ground.cpp's record_fading_fills): street_vs's, a thin street as wide as at rest,
/// at its tile's fade (its place's scale.w) as its alpha.
[shader("vertex")]
StreetOut fill_fading_vs(uint index : SV_VulkanVertexID, uint tile : SV_VulkanInstanceID) {
    StreetOut o = street_vs(index, tile);
    o.fill.colour.a = asfloat(tile_word(tile, 7u));
    return o;
}

/// A street's share of the pixels it is drawn over (StreetOut.cover): 1 from 0.999 on, so that a fill drawn whole is
/// drawn as it always was whatever its interpolation makes of a 1.
float cover_of(float cover) {
    return cover < 0.999f ? cover : 1.0f;
}

/// colour_fs's colour at the fill's alpha and its share (cover_of), premultiplied, over what lies under it: a tile
/// fading in (record_fading_fills), the app's map's streets (record_streets).
[shader("fragment")]
ColourOut colour_over_fs(StreetOut v) {
    ColourOut o = colour_fs(v.fill);
    o.colour.a *= cover_of(v.cover);
    o.colour = float4(o.colour.rgb * o.colour.a, o.colour.a);
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

/// Where the world draws beyond 18 km (ENGINE.md step 5), the plate under each wanted tile's box alone: entry
/// `instance` of the frame's tile plates (push.mode.z their first word; 5 words each: the tile table's entry of the tile
/// drawing it, then the box's west, north, east and south in that tile's metres), at painter slot push.mode.y, in
/// push.colour.z.
[shader("vertex")]
FillOut tile_plate_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    uint at = push.mode.z + 5u * instance;
    float2 lo = float2(asfloat(words[at + 1u]), asfloat(words[at + 2u]));
    float2 hi = float2(asfloat(words[at + 3u]), asfloat(words[at + 4u]));
    float2 c = lerp(lo, hi, plate_corners[vertex] * 0.5f + 0.5f);
    FillOut o;
    o.position = place(words[at], float3(c.x, 0.0f, c.y), push.mode.y);
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
    uint v = ground_word(index);
    uint at = base + tile_word(tile, 9u) + 3u * v;
    float x = asfloat(ground_word(at));
    float z = asfloat(ground_word(at + 1u));
    uint f = ground_word(at + 2u);
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
    float y = level ? asfloat(ground_word(r)) : 0.0f;
    // the stencil's count only: the depth is the water's surface slot, never tested
    o.position = place(tile, float3(x, y, z), push.mode.y);
    return o;
}

// ---------------------------------------------------------------- the water's thin edges (passes/Water.cpp)

/// A thin edge's record (geometry/Ground.hpp's GroundGpu::thin), its words, and a metre of its widths.
static const uint THIN_WORDS = 7u;
static const float THIN_UNITS = 1024.0f;

/// A thin edge's quad, two triangles over its corners: 0 and 1 its ends on its bank, 2 and 3 across from 1 and 0.
static const uint THIN_VERTICES = 6u;
static const uint thin_corners[6] = {0u, 1u, 2u, 0u, 2u, 3u};

struct ThinOut {
    float4 position : SV_Position;
    float cover : COLOR0;
};

/// The water's width on the screen at a thin edge's end w on its bank, wm across the water from it, at height y,
/// device px: across its direction there, measured as least_point measures a street's; LEAST_PX where a point reaches
/// the near plane (it is near) or the direction has none.
float thin_px(uint tile, float y, float2 w, float2 wm) {
    float2 across = w - wm;
    float l = length(across);
    if (!(l > 0.0f)) {
        return LEAST_PX;
    }
    float2 c = 0.5f * (w + wm);
    float2 along = float2(-across.y, across.x) * (LEAST_ALONG / l);
    float2 a = c + along;
    float2 b = c - along;
    TilePlace t = place_of(tile);
    float3 kw = camera_of(frame.camera, placed(frame.camera, t, float3(w.x, y, w.y)));
    float3 km = camera_of(frame.camera, placed(frame.camera, t, float3(wm.x, y, wm.y)));
    float3 ka = camera_of(frame.camera, placed(frame.camera, t, float3(a.x, y, a.y)));
    float3 kb = camera_of(frame.camera, placed(frame.camera, t, float3(b.x, y, b.y)));
    if (min(min(kw.z, km.z), min(ka.z, kb.z)) <= frame.camera.frame.w) {
        return LEAST_PX;
    }
    float2 d = css_of(frame.camera, ka) - css_of(frame.camera, kb);
    float2 s = css_of(frame.camera, kw) - css_of(frame.camera, km);
    float ld = length(d);
    if (!(ld > 0.0f)) {
        return LEAST_PX;
    }
    return abs(s.x * d.y - s.y * d.x) / ld * frame.camera.frame.z;
}

/// A thin edge's vertex: THIN_VERTICES a record, from the tile's first record's word (push.mode.z: a draw a tile),
/// its tile the instance. Drawn where the frame draws its piece and its other bank's at the draw's height (water_vs's
/// bits) and the water is narrower on the screen than LEAST_PX at either of its ends; else folded. Its quad from the
/// edge on its bank across the water to the other bank (`band` false), or its band along the water's middle, at each
/// end LEAST_PX wide at the share of them the water covers there (`cover`, least_point's) where narrower, else as wide
/// as the water.
ThinOut thin_of(uint index, uint tile, bool band) {
    uint at = push.mode.z + THIN_WORDS * (index / THIN_VERTICES);
    uint corner = index % THIN_VERTICES;
    ThinOut o;
    o.cover = 1.0f;
    uint f = ground_word(at + 5u);
    uint other = ground_word(at + 6u);
    bool level = (push.mode.x & GROUND_WATER_LEVEL) != 0u;
    uint bits = level ? tile_word(tile, 14u) : tile_word(tile, 13u);
    if ((push.mode.x & GROUND_VISIBLE_ALL) == 0u && !(bit(bits, f) && bit(bits, other))) {
        o.position = FOLDED;
        return o;
    }
    uint base = tile_word(tile, 8u);
    float y = level ? asfloat(ground_word(base + tile_word(tile, 10u) + 5u * f)) : 0.0f;
    float2 a = float2(asfloat(ground_word(at)), asfloat(ground_word(at + 1u)));
    float2 b = float2(asfloat(ground_word(at + 2u)), asfloat(ground_word(at + 3u)));
    uint widths = ground_word(at + 4u);
    // into the water: the left of the edge's direction (x east, z south)
    float2 e = b - a;
    float2 n = float2(-e.y, e.x) / length(e);
    float2 fa = a + n * (float(widths & 0xFFFFu) / THIN_UNITS);
    float2 fb = b + n * (float(widths >> 16u) / THIN_UNITS);
    float pa = thin_px(tile, y, a, fa);
    float pb = thin_px(tile, y, b, fb);
    if (!(min(pa, pb) < LEAST_PX)) {
        o.position = FOLDED;
        return o;
    }
    uint q = thin_corners[corner];
    bool at_b = q == 1u || q == 2u;
    float2 w = at_b ? b : a;
    float2 wm = at_b ? fb : fa;
    float2 p = q >= 2u ? wm : w;
    float px = at_b ? pb : pa;
    if (band && px < LEAST_PX) {
        float2 c = 0.5f * (w + wm);
        p = c + (p - c) * (LEAST_PX / max(px, 1.0e-4f));
        o.cover = px / LEAST_PX;
    }
    o.position = place(tile, float3(p.x, y, p.y), push.mode.y);
    return o;
}

/// A thin edge's quad across the water (thin_of): where it lies on water counted once, the count left to the band.
[shader("vertex")]
ThinOut thin_vs(uint index : SV_VulkanVertexID, uint tile : SV_VulkanInstanceID) {
    return thin_of(index, tile, false);
}

/// A thin edge's band along the water's middle (thin_of).
[shader("vertex")]
ThinOut thin_band_vs(uint index : SV_VulkanVertexID, uint tile : SV_VulkanInstanceID) {
    return thin_of(index, tile, true);
}

// ---------------------------------------------------------------- the ground shadows (passes/Shadows.cpp)

/// A shadow record's flags (geometry/Ground.hpp's shadow_flag).
static const uint SHADOW_MOVED = 1u; // moved by its offset whole (a raised outline, a crown disc); else a swept hull
static const uint SHADOW_TREE = 2u;  // a tree's crown (Camera.poly's margin of 40 CSS px; a solid's 900)

/// Point `i` of tile `t`'s shadows' points (its ground range from `base`): x, z from the tile's origin.
float2 shadow_point(uint base, uint t, uint i) {
    uint at = base + tile_word(t, 16u) + 2u * i;
    return float2(asfloat(ground_word(at)), asfloat(ground_word(at + 1u)));
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
    c.first = ground_word(r);
    c.count = ground_word(r + 1u);
    float h = asfloat(ground_word(r + 2u));
    c.flags = ground_word(r + 3u);
    c.y = asfloat(ground_word(r + 4u));
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
    uint id = ground_word(index);
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
        float3 k0 = camera_of(frame.camera, placed(frame.camera, at, float3(p0.x, c.y, p0.y)));
        float3 a = k0;
        for (uint j = 1u; j <= corners; ++j) {
            float3 b = k0;
            if (j < corners) {
                float2 p = shadow_corner(base, tile, c, j);
                b = camera_of(frame.camera, placed(frame.camera, at, float3(p.x, c.y, p.y)));
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
    uint id = ground_word(index);
    uint s = id >> 16u;
    MaskOut o;
    if (!bit(tile_word(tile, 15u), s)) {
        o.position = FOLDED;
        return o;
    }
    ShadowOf c = shadow_of(base, tile, s);
    float2 p = shadow_corner(base, tile, c, id & 0xFFFFu);
    float3 k = camera_of(frame.camera, placed(frame.camera, place_of(tile), float3(p.x, c.y, p.y)));
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

/// The water's thin edges' band's colour (thin_band_vs): the water's surface there (gradient_at: with weather wgrad,
/// else push.colour.x and .y both th['water']) with its tint over it (push.colour.z at push.rows.z), at the band's
/// share of the pixels it is drawn over (cover_of), premultiplied.
[shader("fragment")]
ColourOut thin_fs(ThinOut v) {
    float2 css = css_of_output(frame.camera, v.position.xy);
    float3 c = lerp(gradient_at(css.y).rgb, unpack(push.colour.z).rgb, push.rows.z);
    float a = cover_of(v.cover);
    ColourOut o;
    o.colour = float4(c * a, a);
    return o;
}

/// A premultiplied pixel (a r g b, a byte each) as 0-255 values in r g b a order.
float4 levels_of(uint s) {
    float4 c = {float((s >> 16u) & 0xFFu), float((s >> 8u) & 0xFFu), float(s & 0xFFu), float(s >> 24u)};
    return c;
}

/// The canopy where the stencil holds its rings' union (screen_vs; passes/Ground.cpp): its filter's result over the
/// pixel, one of fx/Canopy.hpp's table of 256 in `words` from push.mode.z (premultiplied sRGB, a r g b) by the
/// canopy's alpha, at the path's opacity (push.rows.z), premultiplied. The alpha is the field's at the pixel, as the
/// renders read it there; in the app's map (push.mode.w 1) it is the field's where the ground under the pixel lies in
/// it (the ground's map after the table: passes::water_map at the ground's height), between its texels and mipmaps (a
/// far pixel the mean of the canopy it sees), the table between the two alphas about it: so the canopy keeps its
/// place on the ground as the map moves, and holds still as it zooms out.
[shader("fragment")]
ColourOut canopy_fs(ScreenOut v) {
    // no discard: the stencil's union is tested before the fragment runs, so only the canopy's pixels run it
    float4 c = float4(0.0f, 0.0f, 0.0f, 0.0f);
    if (push.mode.w == 0u) {
        c = levels_of(words[push.mode.z + fx_canopy(fx_pixel(frame.camera, v.position.xy))]);
    } else {
        uint m = push.mode.z + 256u;
        float3 r0 = {asfloat(words[m]), asfloat(words[m + 1u]), asfloat(words[m + 2u])};
        float3 r1 = {asfloat(words[m + 3u]), asfloat(words[m + 4u]), asfloat(words[m + 5u])};
        float3 r2 = {asfloat(words[m + 6u]), asfloat(words[m + 7u]), asfloat(words[m + 8u])};
        float2 p = css_of_output(frame.camera, v.position.xy) * frame.camera.frame.z;
        float w = ((r2.x * p.x) + (r2.y * p.y)) + r2.z;
        if (!(w < 0.0f)) {
            ColourOut none;
            none.colour = c; // the canopy lies on the ground, below the horizon
            return none;
        }
        float2 q = {(((r0.x * p.x) + (r0.y * p.y)) + r0.z) / w, (((r1.x * p.x) + (r1.y * p.y)) + r1.z) / w};
        // the map's derivatives a device pixel across and down: the pixel's footprint in the field
        float2 dx = float2(r0.x - (q.x * r2.x), r1.x - (q.y * r2.x)) / w;
        float2 dy = float2(r0.y - (q.x * r2.y), r1.y - (q.y * r2.y)) / w;
        float a = clamp(fx_canopy_on_ground(q, dx, dy), 0.0f, 255.0f);
        float f = floor(a);
        uint lo = uint(f);
        uint hi = min(lo + 1u, 255u);
        c = lerp(levels_of(words[push.mode.z + lo]), levels_of(words[push.mode.z + hi]), a - f);
    }
    ColourOut o;
    o.colour = (c * (1.0f / 255.0f)) * push.rows.z;
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
//
// A run in metres (LINE_METRES: the app's map's markings, a taxiway's line, a big road's) is a painted line on the
// ground: each end of a segment px at its own depth, max(0.25, w · focal / depth), the half width between them along
// it (as 1 / depth goes on the screen), so it is never wider than it lies wherever the renders' one point of the line
// (its middle) is, even behind the camera. Its segments are kept between the near plane and the depth where the px
// reaches its cut (a big road's lines' 0.3), an end beyond either moved along it to that depth, a free end there (and
// where that lies far off the frame, toward the other end to just beyond it, keeping the floats about the screen). A
// pixel of it takes the coverage of the width it is drawn at there (a hairline's, the exact area's or the samples').
// A coaster track's shadow in the app's map is such a run too, its points moved by the sun: 1.1 m (a post's 0.5 m), at
// least 0.5 px as coasters.draw's are, without a cut. A line of a fixed width in the app's map (GROUND_NEAR_CLIP) is
// kept from the near plane on too, so it reaches the screen's bottom edge where it passes under the camera: a sett
// road's course where one of its ends is ahead (sett_line alike), a dashed run's pattern reaching the first point ahead
// as there. A bridge's lines in the app's map lie at the edges of its street, which is as wide as its way there: each
// point twice as far from the middle of it and its mirror on the other line (its words on from LINE_DASH_SHIFT), the
// renders' ribbon(pts, w / 2 + 0.3) moved as fill_vs moves the street's.

/// A run's record in `words`: 8 words.
static const uint RUN_WORDS = 8u;

struct StrokeOut {
    float4 position : SV_Position;
    nointerpolation float4 colour : COLOR0; // the paint, premultiplied
    nointerpolation float4 ab : TEXCOORD0;  // its ends a and b, device px of the view
    nointerpolation float4 pn : TEXCOORD1;  // the point before a and the point after b (where LINE_HAS_PREV, _NEXT)
    nointerpolation uint4 info : TEXCOORD2; // x: mode and LINE_HAS_ bits; y: the half width (device px, at a) as bits;
                                            // z: the run's LINE_ flags, and from bit 16 a layer run's line's reach
                                            // (how many lines before it it looks back at); w: its instance word
                                            // (run << 14 | segment)
    nointerpolation float4 dash : TEXCOORD3; // a dashed run's (in_dash): its start's place in the pattern, the dash and
                                             // the period (device px), 1; else 0
    nointerpolation float taper : TEXCOORD4; // a run in metres': how much the half width grows a device px along it
                                             // (Seg.dw); else 0
};

/// A run's point k in its tile's metres, at height y.
float3 run_local(uint first, uint k, float y) {
    float x = asfloat(lines_word(first + 2u * k));
    float z = asfloat(lines_word(first + 2u * k + 1u));
    return float3(x, y, z);
}

/// A tile-local point's camera coordinates.
float3 local_camera(uint tile, float3 p) {
    return camera_of(frame.camera, placed(frame.camera, place_of(tile), p));
}

/// A run's point k, projected: its camera coordinates.
float3 run_point(uint tile, uint first, uint k, float y) {
    return local_camera(tile, run_local(first, k, y));
}

/// A coaster track's shadow's point k (its (x, z, height) in the lines heap) at height y in its tile's metres, moved
/// along the sun's offset −L.xz / L.y · height (push.rows.xyz: L) where `moved`.
float3 shadow_local(uint first, uint k, float y, bool moved) {
    float x = asfloat(lines_word(first + 3u * k));
    float z = asfloat(lines_word(first + 3u * k + 1u));
    if (!moved) return float3(x, y, z);
    float h = asfloat(lines_word(first + 3u * k + 2u));
    float3 sun = push.rows.xyz;
    return float3(x - sun.x / sun.y * h, y, z - sun.z / sun.y * h);
}

/// A wide run's point k in its tile's metres at height y: twice as far from the middle of it and its mirror, the run
/// `mirror` words on (LINE_DASH_SHIFT), as wide_point lays a ribbon's vertex.
float3 wide_local(uint first, uint k, float y, int mirror) {
    float3 p = run_local(first, k, y);
    float3 m = run_local(uint(int(first) + mirror), k, y);
    float2 w = 2.0f * p.xz - 0.5f * (p.xz + m.xz);
    return float3(w.x, y, w.y);
}

/// A run's point k, projected: a shadow run's moved, a wide run's (its mirror's words on, 0 for none) moved out.
float3 stroke_point(uint tile, uint first, uint k, float y, bool shadow, int mirror) {
    if (shadow) return local_camera(tile, shadow_local(first, k, y, true));
    if (mirror != 0) return local_camera(tile, wide_local(first, k, y, mirror));
    return run_point(tile, first, k, y);
}

/// A quay wall's course's point k (passes/Quays.cpp, the draw's GROUND_COURSES): its place in its tile's metres, three
/// f32 (x, y, z) in the frame's words from `first`; its camera coordinates.
float3 course_point(uint tile, uint first, uint k) {
    float3 p = {asfloat(words[first + 3u * k]), asfloat(words[first + 3u * k + 1u]),
                asfloat(words[first + 3u * k + 2u])};
    return local_camera(tile, p);
}

/// A point's device px in the view (unturned): its CSS point (snapped at rest) times the density.
float2 device_of(float3 k) {
    return project_css(frame.camera, k) * frame.camera.frame.z;
}

/// The quad's corners: 0 to 5 over two triangles, (along, across) in -1..1.
static const float2 quad_corners[6] = {float2(-1.0f, -1.0f), float2(1.0f, -1.0f), float2(1.0f, 1.0f),
                                       float2(-1.0f, -1.0f), float2(1.0f, 1.0f),  float2(-1.0f, 1.0f)};

/// The point of the segment from camera coordinates k0 to k1 at depth z (k0's depth differing from k1's).
float3 at_depth(float3 k0, float3 k1, float z) {
    return k0 + (k1 - k0) * ((z - k0.z) / (k1.z - k0.z));
}

/// A run in metres' neighbour k of its end `end`: k, or where the segment from `end` to k passes the near plane (a
/// point behind it has no place on the screen; any point of the segment ahead of it gives the join's direction).
float3 ahead_toward(float3 end, float3 k, float near) {
    return k.z < near ? at_depth(end, k, near) : k;
}

/// How far from `o` toward `e` (device px, `len` apart) a segment need go to cover the frame with `spare` px beyond:
/// past the frame's middle's place along it by the frame's diagonal and the spare, at most `len`.
float kept_along(float2 o, float2 e, float len, float spare) {
    if (!(len > 1e-6f)) return len;
    float2 size = frame.camera.frame.xy * frame.camera.frame.z;
    float along = max(dot(0.5f * size - o, (e - o) / len), 0.0f) + length(size) + spare;
    return min(len, along);
}

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
    int mirror = (flags & (LINE_DASH | LINE_METRES)) == 0u ? asint(words[run + 7u]) >> int(LINE_DASH_SHIFT) : 0;
    StrokeOut o;
    o.colour = float4(0.0f, 0.0f, 0.0f, 0.0f);
    o.ab = float4(0.0f, 0.0f, 0.0f, 0.0f);
    o.pn = float4(0.0f, 0.0f, 0.0f, 0.0f);
    o.info = uint4(0u, 0u, 0u, 0u);
    o.dash = float4(0.0f, 0.0f, 0.0f, 0.0f);
    o.taper = 0.0f;
    // a pair run's segment k is its line k, the points 2k and 2k + 1, without neighbours; a post's shadow its point's
    // foot to the point moved
    bool pairs = (flags & LINE_PAIRS) != 0u;
    bool shadow = (flags & LINE_SHADOW) != 0u;
    bool post = (flags & LINE_POST) != 0u;
    uint ia = pairs ? 2u * seg : seg;
    float3 ka = post ? local_camera(tile, shadow_local(first, 0u, y, false)) : stroke_point(tile, first, ia, y, shadow, mirror);
    float3 kb = post ? local_camera(tile, shadow_local(first, 0u, y, true))
                     : stroke_point(tile, first, ia + 1u, y, shadow, mirror);
    if ((push.mode.x & GROUND_COURSES) != 0u) {
        // a quay wall's courses: each line's two ends where the wall has them, in the words
        ka = course_point(tile, first, ia);
        kb = course_point(tile, first, ia + 1u);
    }
    float near = frame.camera.frame.w;
    float focal = frame.camera.forward.w;
    // a run in metres: the part of the segment between the near plane and the depth where its px is its cut (none:
    // as far as it goes), each end beyond them moved to the depth it passes and free there; in the app's map
    // (GROUND_NEAR_CLIP) a line of a fixed width alike from the near plane on, where the renders' line_d leaves out a
    // segment with an end behind it
    bool metres = (flags & LINE_METRES) != 0u;
    bool clipped = metres || (push.mode.x & GROUND_NEAR_CLIP) != 0u;
    float cut = float(table) * 0.01f;
    float far = metres && cut > 0.0f ? width * focal / cut : 3.0e38f;
    bool free_a = false;
    bool free_b = false;
    if (clipped) {
        float lo = 0.0f;
        float hi = 1.0f;
        float dz = kb.z - ka.z;
        if (dz != 0.0f) {
            float tn = (near - ka.z) / dz;
            float tf = (far - ka.z) / dz;
            lo = max(lo, min(tn, tf));
            hi = min(hi, max(tn, tf));
        } else if (ka.z < near || ka.z > far) {
            hi = -1.0f;
        }
        if (!(lo < hi)) {
            o.position = FOLDED;
            return o;
        }
        float3 k0 = ka;
        if (lo > 0.0f) {
            ka = k0 + (kb - k0) * lo;
            free_a = true;
        }
        if (hi < 1.0f) {
            kb = k0 + (kb - k0) * hi;
            free_b = true;
        }
    } else if (ka.z < near || kb.z < near) {
        // a segment with an end behind the near plane is no part of a run
        o.position = FOLDED;
        return o;
    }
    float2 a = device_of(ka);
    float2 b = device_of(kb);
    float density = frame.camera.frame.z;
    float hw = width * density * 0.5f;
    float hb = hw;
    if (metres) {
        // px at each end's own depth, regions.py's rule a point at a time (a track's shadow's least coasters.draw's)
        float least = shadow ? LINE_SHADOW_PX_LEAST : LINE_PX_LEAST;
        hw = max(least, width * focal / ka.z) * density * 0.5f;
        hb = max(least, width * focal / kb.z) * density * 0.5f;
    }
    // a free end the near plane left far off the frame (millions of px where the segment passes it beside or below
    // the camera) moved toward the other end, its half width along as it goes between them, so the stage's floats
    // keep their place about the screen
    if (free_a || free_b) {
        float spare = 4.0f * max(hw, hb) + 2.0f;
        float len = length(b - a);
        float t = free_a ? kept_along(b, a, len, spare) : len;
        if (t < len) {
            a = b + (a - b) * (t / len);
            hw = hb + (hw - hb) * (t / len);
        }
        len = length(b - a);
        t = free_b ? kept_along(a, b, len, spare) : len;
        if (t < len) {
            b = a + (b - a) * (t / len);
            hb = hw + (hb - hw) * (t / len);
        }
    }
    uint info = 0u;
    float2 p = a;
    float2 n = b;
    if (!pairs && !post && seg > 0u && !free_a) {
        float3 kp = stroke_point(tile, first, seg - 1u, y, shadow, mirror);
        if (clipped) kp = ahead_toward(ka, kp, near);
        if (clipped || kp.z >= near) {
            p = device_of(kp);
            info |= LINE_HAS_PREV;
        }
    }
    if (!pairs && !post && seg + 2u < count && !free_b) {
        float3 kn = stroke_point(tile, first, seg + 2u, y, shadow, mirror);
        if (clipped) kn = ahead_toward(kb, kn, near);
        if (clipped || kn.z >= near) {
            n = device_of(kn);
            info |= LINE_HAS_NEXT;
        }
    }
    float wide = 2.0f * max(hw, hb);
    // a dashed run by its samples whatever its width (a park's edge is wider than two pixels anyway)
    bool dashed = (flags & LINE_DASH) != 0u;
    info |= dashed ? LINE_SAMPLES : (wide <= 1.0f ? LINE_HAIR : (wide < 2.0f ? LINE_AREA : LINE_SAMPLES));
    if (dashed) {
        // the table: the dash and the period (CSS px), then each point's place in the pattern
        o.dash = float4(asfloat(words[table + 2u + seg]), asfloat(words[table]), asfloat(words[table + 1u]), 1.0f);
        o.dash.xyz *= density;
        if (free_a) {
            // a start at the near plane: its place such that the pattern reaches b at b's, the first point ahead of
            // the near plane starting it again (Lines.cpp's dash_table)
            float at = asfloat(words[table + 3u + seg]) * density - length(b - a);
            o.dash.x = at - o.dash.z * floor(at / o.dash.z);
        }
    }
    // the quad: along the segment, its join's wedge and the coverage's reach beyond each end, across by half the
    // width and the reach
    float2 d = b - a;
    float len = length(d);
    float2 u = len > 1e-6f ? d / len : float2(1.0f, 0.0f);
    float2 v = float2(-u.y, u.x);
    float2 c = quad_corners[vertex];
    float2 at;
    if (!metres) {
        float reach = hw * 4.0f + 2.0f;
        // around a join the wedge stands out across too (a miter reaches four half widths): the quad as wide as it is
        // long
        at = (c.x < 0.0f ? a - u * reach : b + u * reach) + v * (c.y * reach);
    } else {
        // each end's reach as above, the quad about the two discs of them: bounded by their outer tangents (whose
        // sine is the reaches' difference over the length) and beyond each end by its reach, or where one disc all
        // but holds the other, the box of both
        float ra = hw * 4.0f + 2.0f;
        float rb = hb * 4.0f + 2.0f;
        float s = (rb - ra) / max(len, 1e-6f);
        if (abs(s) < 0.8f) {
            float k = 1.0f / sqrt(1.0f - s * s);
            at = c.x < 0.0f ? a - u * ra + v * (c.y * ra * (1.0f - s) * k) : b + u * rb + v * (c.y * rb * (1.0f + s) * k);
        } else {
            float r = max(ra, rb);
            at = a + u * (c.x < 0.0f ? min(-ra, len - rb) : max(ra, len + rb)) + v * (c.y * r);
        }
        o.taper = len > 1e-6f ? (hb - hw) / len : 0.0f;
    }
    float2 css = at / density;
    o.position = clip_of(frame.camera, css, 1.0f, painter_depth(frame.camera, slot));
    float4 paint = unpack(colour);
    // premultiplied, and at its tile's fade (its place's scale.w: 1 but while the tile fades in)
    o.colour = float4(paint.rgb * paint.a, paint.a) * asfloat(tile_word(tile, 7u));
    o.ab = float4(a, b);
    o.pn = float4(p, n);
    // a sett road's line: the earlier lines it looks back at, its reaches' level for the ground's stretch at its ends
    uint back = 0u;
    if ((flags & LINE_LAYER) != 0u) {
        back = sett_reach(lines_word(first + 2u * count + seg), sett_tolerance_at(tile, first, ia, y, hw));
    }
    o.info = uint4(info, asuint(hw), flags | (back << 16u), word);
    return o;
}

/// A fragment's segment: its ends and neighbours as the vertex stage found them, its half width growing along it as
/// the vertex stage tapered it.
Seg seg_of(StrokeOut s) {
    Seg g = seg_make(s.ab.xy, s.ab.zw, s.pn.xy, s.pn.zw, s.info.x, asfloat(s.info.y), s.info.z);
    g.dw = s.taper;
    return g;
}

/// A pair run (a sett road's courses) as setts.slangh reads it: its tile, its points' first word, their count and their
/// height.
struct SettRun {
    uint tile;
    uint first;
    uint count;
    float y;
};

/// Line i of a pair run: its ends projected as the vertex stage projects them, in the app's map (GROUND_NEAR_CLIP) from
/// the near plane on.
SettLine sett_line(SettRun r, uint i) {
    float3 ka = run_point(r.tile, r.first, 2u * i, r.y);
    float3 kb = run_point(r.tile, r.first, 2u * i + 1u, r.y);
    float near = frame.camera.frame.w;
    SettLine e;
    e.drawn = ka.z >= near && kb.z >= near;
    bool clip = !e.drawn && (push.mode.x & GROUND_NEAR_CLIP) != 0u && max(ka.z, kb.z) > near;
    bool behind_a = ka.z < near;
    if (clip) {
        float3 k = at_depth(ka, kb, near);
        if (behind_a) {
            ka = k;
        } else {
            kb = k;
        }
        e.drawn = true;
    }
    e.a = device_of(ka);
    e.b = device_of(kb);
    if (clip) {
        // its end at the near plane toward the other, past the frame as the vertex stage moves it
        float len = length(e.b - e.a);
        if (behind_a) {
            float t = kept_along(e.b, e.a, len, 0.0f);
            if (t < len) e.a = e.b + (e.a - e.b) * (t / len);
        } else {
            float t = kept_along(e.a, e.b, len, 0.0f);
            if (t < len) e.b = e.a + (e.b - e.a) * (t / len);
        }
    }
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
    if ((push.mode.x & GROUND_LAYER) != 0u) {
        return output; // the reflections' layer is the upright view
    }
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
    if (s.taper != 0.0f) {
        // a run in metres tapers: the mode of its width at the pixel's place along it, so its thin far end is drawn as
        // a line that thin is (by its area or as a hairline) and its wide near end by its samples
        float2 at = device_at(corner + 0.5f);
        float wide = 2.0f * seg_hw(g, dot(at - g.a, g.u));
        mode = wide <= 1.0f ? LINE_HAIR : (wide < 2.0f ? LINE_AREA : LINE_SAMPLES);
    }
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
        // the pixel of the view's device grid the output's pixel is (a turned output keeps the grid), its alpha the
        // width at the pixel's place along it
        cov = hair_coverage(g, floor(centre), 2.0f * seg_hw(g, dot(centre - g.a, g.u)));
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
    nointerpolation uint4 extra : TEXCOORD3; // a streak's opacity (its float's bits) and its gradient's three stops
};

/// Corner i of a quay face's record from word `at`, in its tile's metres.
float3 quay_corner(uint at, uint i) {
    return float3(asfloat(words[at + 4u + 3u * i]), asfloat(words[at + 5u + 3u * i]), asfloat(words[at + 6u + 3u * i]));
}

/// The clip coordinates of CSS point `css` at depth `depth`: the frame's (clip_of), or in the reflections' layer
/// (GROUND_LAYER) the upright view's, the output's turn left out.
float4 ground_clip(float2 css, float depth) {
    if ((push.mode.x & GROUND_LAYER) != 0u) {
        FrameCamera c = frame.camera;
        float2 n = {(css.x * c.frame.z) * c.device.x - 1.0f, (css.y * c.frame.z) * c.device.y - 1.0f};
        return float4(n.x, n.y, depth, 1.0f);
    }
    return clip_of(frame.camera, css, 1.0f, depth);
}

/// How many vertices a face's record draws (face_vs): a fan of up to three triangles.
static const uint FACE_VERTICES = 9u;

/// The face of the record at word `at` (a quay wall's, a reflection's) at its slot's depth: its polygon (Camera.poly's
/// near clip of its corners, device px of the view) grown by its stroke's half width and a pixel, each side moved out
/// along its normal and the corners mitred, as a fan of up to three triangles (`vertex` 0 to 8, one past the fan's
/// last triangle folded); where a corner's mitre would reach past four times that growth or a side has no length, the
/// polygon's box grown alike (two triangles, the third folded). The fragment keeps the samples inside the polygon or
/// within the half width of it, each of which lies inside either shape, so the shape spares only pixels it would
/// discard.
QuayOut face_vs(uint at, uint vertex) {
    uint tile = words[at];
    uint slot = words[at + 1u];
    uint colour = words[at + 2u];
    uint flags = words[at + 3u];
    QuayOut o;
    o.colour = float4(0.0f, 0.0f, 0.0f, 0.0f);
    o.p01 = float4(0.0f, 0.0f, 0.0f, 0.0f);
    o.p23 = float4(0.0f, 0.0f, 0.0f, 0.0f);
    o.p4 = float4(0.0f, 0.0f, 0.0f, 0.0f);
    o.extra = uint4(0u, 0u, 0u, 0u);
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
    float grow = hw + 1.0f;
    // its turn (the shoelace sum's sign) and each side's outward unit normal
    float twice = 0.0f;
    for (uint i = 0u; i < n; ++i) {
        float2 a = p[i];
        float2 b = p[(i + 1u) % n];
        twice = twice + (a.x * b.y - b.x * a.y);
    }
    float turn = twice >= 0.0f ? 1.0f : -1.0f;
    float2 normal[5] = {float2(0.0f, 0.0f), float2(0.0f, 0.0f), float2(0.0f, 0.0f), float2(0.0f, 0.0f),
                        float2(0.0f, 0.0f)};
    bool tight = true;
    for (uint i = 0u; i < n; ++i) {
        float2 e = p[(i + 1u) % n] - p[i];
        float l = length(e);
        if (!(l > 0.001f)) {
            tight = false;
        } else {
            normal[i] = float2(e.y, -e.x) * (turn / l);
        }
    }
    // each corner moved out along both its sides' normals: the sides' offset lines meet there
    float2 grown[5] = {p[0], p[1], p[2], p[2], p[2]};
    for (uint i = 0u; i < n; ++i) {
        float2 n0 = normal[(i + n - 1u) % n];
        float2 n1 = normal[i];
        float k = 1.0f + dot(n0, n1);
        if (!(k >= 0.125f)) tight = false;
        grown[i] = p[i] + (n0 + n1) * (grow / max(k, 0.125f));
    }
    float2 device;
    if (tight) {
        uint fan = vertex / 3u;
        uint corner = vertex % 3u;
        if (fan + 2u >= n) {
            o.position = FOLDED;
            return o;
        }
        device = grown[corner == 0u ? 0u : fan + corner];
    } else {
        if (vertex >= 6u) {
            o.position = FOLDED;
            return o;
        }
        float2 lo = p[0];
        float2 hi = p[0];
        for (uint i = 1u; i < n; ++i) {
            lo = min(lo, p[i]);
            hi = max(hi, p[i]);
        }
        lo = lo - grow;
        hi = hi + grow;
        float2 c = quad_corners[vertex];
        device = float2(c.x < 0.0f ? lo.x : hi.x, c.y < 0.0f ? lo.y : hi.y);
    }
    o.position = ground_clip(device / density, painter_depth(frame.camera, slot));
    o.colour = unpack(colour);
    o.p01 = float4(p[0], p[1]);
    o.p23 = float4(p[2], n > 3u ? p[3] : p[2]);
    o.p4 = float4(n > 4u ? p[4] : p[n - 1u], hw, float(n));
    return o;
}

[shader("vertex")]
QuayOut quay_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    return face_vs(push.mode.z + instance * QUAY_WORDS, vertex);
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

// ---------------------------------------------------------------- the water's reflections (passes/Reflections.cpp)
//
// A reflection's face is a record of the quay walls' layout (its corners mirrored in the water's plane), a tree's
// circle (REFLECTION_CIRCLE: its centre and radius in CSS px, words 4 to 6), or a night streak (REFLECTION_ELLIPSE:
// its centre and radii in CSS px, words 4 to 7, its opacity, word 8, and its radial gradient's stops at 0, .5 and 1,
// words 9 to 11, RGBA8 with the stop's opacity in its alpha). Drawn in the renders' order inside the water's clip, a
// sample takes what the reflections' group would composite there over the water: the face's colour at the group's
// opacity over wgrad's at the pixel (push.rows: wgrad's rows, the group's opacity, the window rows'), opaque, or a
// window row's (REFLECTION_SHEER) or a streak's at its opacity over what lies under it, premultiplied; the pipeline
// writes the slot's depth, so the faces of one path (a building's window rows) blend once.

/// A reflection's flags besides QUAY_STROKE: a window row at its opacity, a tree's circle, a night streak.
static const uint REFLECTION_SHEER = 2u;
static const uint REFLECTION_CIRCLE = 4u;
static const uint REFLECTION_ELLIPSE = 8u;
/// A tree's circle under its group's leaf filter (amsterdam.py's `leaf`): words 7 to 10 its region in whole device px,
/// word 11 its tree's place in the leaves' field (passes::leaf_anchor). Drawn into the ripple's layer (GROUND_LAYER,
/// where push.colour.x places the filter's 256 offsets and its reach), each sample tests its place moved by the whole
/// pixels the leaves' field gives its pixel where its tree reads it (its anchor less its region's centre, fx_wrap), kept
/// where the pixel and the moved pixel both lie in the region (SvgFilterRaster's DisplacementNode, over the region
/// alone).
static const uint REFLECTION_LEAF = 16u;

/// Whether a record's flags draw under the leaf filter: a tree's circle, into the ripple's layer.
bool leafed(uint flags) {
    return (flags & REFLECTION_LEAF) != 0u && (push.mode.x & GROUND_LAYER) != 0u && push.colour.x != 0u;
}

/// A streak's radialGradient at distance t (0 to 1 of the ellipse): its stops at 0, .5 and 1, each channel and the
/// opacity interpolated apart (as the mast glow's).
float4 streak_at(float t, float4 s0, float4 s1, float4 s2) {
    float u = saturate(t);
    if (u <= 0.5f) {
        float k = u / 0.5f;
        return float4(lerp(s0.rgb, s1.rgb, k), lerp(s0.a, s1.a, k));
    }
    float k = (u - 0.5f) / 0.5f;
    return float4(lerp(s1.rgb, s2.rgb, k), lerp(s1.a, s2.a, k));
}

[shader("vertex")]
QuayOut reflection_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    uint at = push.mode.z + instance * QUAY_WORDS;
    uint flags = words[at + 3u];
    if ((flags & (REFLECTION_CIRCLE | REFLECTION_ELLIPSE)) == 0u) {
        QuayOut f = face_vs(at, vertex);
        f.colour.a = (flags & REFLECTION_SHEER) != 0u ? 0.0f : 1.0f;
        return f;
    }
    // a circle or an ellipse: its box grown by a pixel (two triangles, the third folded), its centre and radii in
    // device px (no points; a circle's second radius 0); a streak's colour alpha 2, its opacity and stops beside
    if (vertex >= 6u) {
        QuayOut folded;
        folded.position = FOLDED;
        folded.colour = float4(0.0f, 0.0f, 0.0f, 0.0f);
        folded.p01 = float4(0.0f, 0.0f, 0.0f, 0.0f);
        folded.p23 = float4(0.0f, 0.0f, 0.0f, 0.0f);
        folded.p4 = float4(0.0f, 0.0f, 0.0f, 0.0f);
        folded.extra = uint4(0u, 0u, 0u, 0u);
        return folded;
    }
    bool ellipse = (flags & REFLECTION_ELLIPSE) != 0u;
    float density = frame.camera.frame.z;
    float2 c = float2(asfloat(words[at + 4u]), asfloat(words[at + 5u])) * density;
    float2 r = float2(asfloat(words[at + 6u]), asfloat(words[at + (ellipse ? 7u : 6u)])) * density;
    float2 k = quad_corners[vertex];
    bool leaf = !ellipse && leafed(flags);
    // the leaf filter moves its pixels by whole device px, up to its reach
    float e = 1.0f + (leaf ? float(words[push.colour.x + 256u]) : 0.0f);
    float2 device = c + float2(k.x < 0.0f ? -(r.x + e) : r.x + e, k.y < 0.0f ? -(r.y + e) : r.y + e);
    QuayOut o;
    o.position = ground_clip(device / density, painter_depth(frame.camera, words[at + 1u]));
    o.colour = float4(unpack(words[at + 2u]).rgb, ellipse ? 2.0f : 1.0f);
    o.p01 = float4(c, r.x, ellipse ? r.y : 0.0f);
    o.p23 = float4(0.0f, 0.0f, 0.0f, 0.0f);
    o.p4 = float4(0.0f, 0.0f, 0.0f, 0.0f);
    o.extra = ellipse ? uint4(words[at + 8u], words[at + 9u], words[at + 10u], words[at + 11u]) : uint4(0u, 0u, 0u, 0u);
    if (leaf) {
        o.extra = uint4(words[at + 7u], words[at + 8u], words[at + 9u], words[at + 10u]);
        // the shift to where its tree reads the leaves' field: its anchor less its region's centre (whole px, exact)
        uint anchor = words[at + 11u];
        int2 centre = {(asint(o.extra.x) + asint(o.extra.z)) / 2, (asint(o.extra.y) + asint(o.extra.w)) / 2};
        int2 shift = int2(int(anchor & 0xFFFFu), int(anchor >> 16u)) - centre;
        o.p23 = float4(1.0f, float(shift.x), float(shift.y), 0.0f);
    }
    return o;
}

/// A reflection's pixel: on the samples inside it, its colour composited as the group composites it over the water;
/// into the reflections' layer (GROUND_LAYER), its own colour at its own opacity, as the group's layer holds it.
[shader("fragment")]
StrokeOutput reflection_fs(QuayOut q) {
    float2 pts[5] = {q.p01.xy, q.p01.zw, q.p23.xy, q.p23.zw, q.p4.xy};
    uint m = uint(q.p4.w);
    float2 corner = floor(q.position.xy);
    uint n = push.mode.w;
    uint mask = 0u;
    bool ellipse = q.colour.a > 1.5f;
    // a tree's circle under the leaf filter (its layer is the upright view): its pixel moved by the leaves' field where
    // its tree reads it
    float2 shift = {0.0f, 0.0f};
    if (m == 0u && !ellipse && q.p23.x > 0.5f) {
        int2 p = {int(corner.x), int(corner.y)};
        uint2 l = fx_leaf(fx_wrap(p + int2(int(q.p23.y), int(q.p23.z))));
        int2 o = {asint(words[push.colour.x + l.x]), asint(words[push.colour.x + l.y])};
        int2 s = p + o;
        int4 region = {asint(q.extra.x), asint(q.extra.y), asint(q.extra.z), asint(q.extra.w)};
        if (p.x < region.x || p.y < region.y || p.x >= region.z || p.y >= region.w) discard;
        if (s.x < region.x || s.y < region.y || s.x >= region.z || s.y >= region.w) discard;
        shift = float2(float(o.x), float(o.y));
    }
    for (uint i = 0u; i < n; ++i) {
        float2 at = n == 4u ? samples4[i] : (n == 2u ? samples2[i] : float2(0.5f, 0.5f));
        float2 x = device_at(corner + at) + shift;
        bool inside;
        if (m != 0u) {
            inside = in_quay(pts, m, q.p4.z, x);
        } else if (ellipse) {
            float2 d = (x - q.p01.xy) / q.p01.zw;
            inside = dot(d, d) <= 1.0f;
        } else {
            inside = length(x - q.p01.xy) <= q.p01.z;
        }
        if (inside) mask |= 1u << i;
    }
    if (mask == 0u) discard;
    float3 colour = q.colour.rgb;
    float a = q.colour.a > 0.5f ? 1.0f : push.rows.w;
    if (ellipse) {
        // the streak's gradient at the pixel's centre, at its opacity
        float2 d = (device_at(corner + float2(0.5f, 0.5f)) - q.p01.xy) / q.p01.zw;
        float4 g = streak_at(sqrt(dot(d, d)), unpack(q.extra.y), unpack(q.extra.z), unpack(q.extra.w));
        colour = g.rgb;
        a = g.a * asfloat(q.extra.x);
    }
    StrokeOutput o;
    o.coverage = mask;
    if ((push.mode.x & GROUND_LAYER) != 0u) {
        o.colour = float4(colour * a, a);
        return o;
    }
    float2 css = css_of_output(frame.camera, q.position.xy);
    float3 water = gradient_at(css.y).rgb;
    float3 rgb = lerp(water, colour, push.rows.z);
    o.colour = float4(rgb * a, a);
    return o;
}

/// The glints' filter's result (premultiplied sRGB bytes, r g b a) at the field's point `q`: the moment's table's
/// entry (in `words` from push.mode.z) at the field's red there (fx_glints_red).
float4 glints_entry(float2 q) {
    uint s = words[push.mode.z + fx_glints_red(q)];
    float4 c = {float((s >> 16u) & 0xFFu), float((s >> 8u) & 0xFFu), float(s & 0xFFu), float(s >> 24u)};
    return c;
}

/// The glints' field's point of octave `n` for the water's map's point `q`: halved n times about the map's own origin
/// on the water, which the map left `periods` whole mirror periods of the fields from (passes::WaterOctaves): the
/// octave's own period is 2^n of them, so those within it are put back first and its glints keep their places as the
/// frame's middle crosses a period.
float2 glints_octave(float2 q, int2 periods, uint n) {
    uint w = 0u;
    uint h = 0u;
    noise_rgba.GetDimensions(w, h);
    int mask = int((1u << n) - 1u);
    float2 whole = {float(2u * w) * float(periods.x & mask), float(2u * h) * float(periods.y & mask)};
    return (q + whole) * (1.0f / float(1u << n));
}

/// The water's glints inside its clip at their slot (screen_vs; passes/Glints.cpp): the glints' field's red where the
/// water under the pixel reads it (the water's map after the table: passes::water_map, fixed to the water; between its
/// pixels, fx_glints_red) picks the filter's result (premultiplied sRGB, a r g b) from the moment's table of 256 in
/// `words` (from push.mode.z), at the rect's opacity (push.rows.z), premultiplied; nothing where no water lies.
/// The field is read by the pixel's footprint in it (passes::WaterOctaves, the three words after the map; the far
/// flicker): the longer of the map's derivatives along the screen's x and y, squared. Up to the first word the field
/// as it lies, the one read (the home view's every pixel, to the bit); beyond it at octave L = half the log2 of their
/// ratio, the results of octaves floor(L) and the next (glints_octave) blended by L's fraction, so a far view's pixel
/// reads glints of its own size, the same ones as the map pans.
[shader("fragment")]
ColourOut glints_fs(ScreenOut v) {
    uint m = push.mode.z + 256u;
    float3 r0 = {asfloat(words[m]), asfloat(words[m + 1u]), asfloat(words[m + 2u])};
    float3 r1 = {asfloat(words[m + 3u]), asfloat(words[m + 4u]), asfloat(words[m + 5u])};
    float3 r2 = {asfloat(words[m + 6u]), asfloat(words[m + 7u]), asfloat(words[m + 8u])};
    float2 p = css_of_output(frame.camera, v.position.xy) * frame.camera.frame.z;
    float3 q = fx_water_point(r0, r1, r2, p);
    if (q.z == 0.0f) {
        discard;
    }
    float w = ((r2.x * p.x) + (r2.y * p.y)) + r2.z;
    float2 dx = {r0.x - (q.x * r2.x), r1.x - (q.y * r2.x)};
    float2 dy = {r0.y - (q.x * r2.y), r1.y - (q.y * r2.y)};
    float foot = max((dx.x * dx.x) + (dx.y * dx.y), (dy.x * dy.x) + (dy.y * dy.y)) / (w * w);
    float lies = asfloat(words[m + 9u]);
    float4 c;
    if (!(foot > lies)) {
        c = glints_entry(q.xy);
    } else {
        float l = min(0.5f * log2(foot / lies), 15.0f);
        float n = floor(l);
        int2 periods = {int(words[m + 10u]), int(words[m + 11u])};
        float4 a = glints_entry(glints_octave(q.xy, periods, uint(n)));
        float4 b = glints_entry(glints_octave(q.xy, periods, uint(n) + 1u));
        c = lerp(a, b, l - n);
    }
    ColourOut o;
    o.colour = (c * (1.0f / 255.0f)) * push.rows.z;
    return o;
}

/// The rippled reflections inside the water's clip at their group's first slot (screen_vs; passes/Ripple.cpp): the
/// filter's result at the pixel (push.mode.z: 0 ripple_a, 1 ripple_b; push.colour.x the upright view's width) written
/// back as premultiplied sRGB, at the group's opacity (push.rows.z), premultiplied; nothing beyond the result's region
/// (push.colour.y its x0 | y0 << 16, z its x1 | y1 << 16), where it is transparent.
[shader("fragment")]
ColourOut ripple_fs(ScreenOut v) {
    int2 p = fx_pixel(frame.camera, v.position.xy);
    int2 lo = {int(push.colour.y & 0xFFFFu), int(push.colour.y >> 16u)};
    int2 hi = {int(push.colour.z & 0xFFFFu), int(push.colour.z >> 16u)};
    if (p.x < lo.x || p.y < lo.y || p.x >= hi.x || p.y >= hi.y) {
        discard;
    }
    uint at = uint(p.y) * push.colour.x + uint(p.x);
    uint linear = push.mode.z == 0u ? ripple_a[at] : ripple_b[at];
    uint s = fx_to_srgb_premultiplied(linear);
    float4 c = {float((s >> 16u) & 0xFFu), float((s >> 8u) & 0xFFu), float(s & 0xFFu), float(s >> 24u)};
    float a = push.rows.z;
    ColourOut o;
    o.colour = (c * (1.0f / 255.0f)) * a;
    return o;
}

// ---------------------------------------------------------------- the sun's glitter (passes/Glitter.cpp)
//
// A glint is an instance of GLINT_WORDS words: its ellipse's centre and radii in CSS px, its opacity, where its list
// of neighbours (the glints whose boxes meet its) lies in `words` and how long it is. The vertex stage draws its box
// at the glitter's slot (push.mode.y); the fragment, run per sample where the device can, takes the group's layer at
// each of its samples inside the glint: the union of every glint over it (1 - Π(1 - opacity)), and screens the group's
// colour (push.colour.x) at that alpha, the mean over its samples where it runs per pixel. The stencil lets the first
// glint over a sample take it, so the layer is screened once.

/// A glint's record in `words`: 8 words.
static const uint GLINT_WORDS = 8u;

struct GlintOut {
    float4 position : SV_Position;
    nointerpolation uint glint : TEXCOORD0;
};

/// Glint g's centre and radii in device px of the view.
float4 glint_shape(uint g) {
    uint at = push.mode.z + g * GLINT_WORDS;
    float density = frame.camera.frame.z;
    return float4(asfloat(words[at]), asfloat(words[at + 1u]), asfloat(words[at + 2u]), asfloat(words[at + 3u])) *
           density;
}

/// Whether glint g holds device point x.
bool in_glint(uint g, float2 x) {
    float4 e = glint_shape(g);
    float2 d = (x - e.xy) / e.zw;
    return dot(d, d) <= 1.0f;
}

[shader("vertex")]
GlintOut glitter_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    float4 e = glint_shape(instance);
    float density = frame.camera.frame.z;
    float2 k = quad_corners[vertex];
    float2 device = e.xy + float2(k.x < 0.0f ? -(e.z + 1.0f) : e.z + 1.0f, k.y < 0.0f ? -(e.w + 1.0f) : e.w + 1.0f);
    GlintOut o;
    o.position = clip_of(frame.camera, device / density, 1.0f, painter_depth(frame.camera, push.mode.y));
    o.glint = instance;
    return o;
}

/// A glint's pixel: on its samples (one where it runs per sample) inside it, the group's layer screened over the
/// water, premultiplied (Blend::screen).
[shader("fragment")]
StrokeOutput glitter_fs(GlintOut v, uint given : SV_Coverage) {
    float2 corner = floor(v.position.xy);
    uint n = push.mode.w;
    uint at = push.mode.z + v.glint * GLINT_WORDS;
    uint first = words[at + 5u];
    uint count = words[at + 6u];
    uint mask = 0u;
    float sum = 0.0f;
    for (uint i = 0u; i < n; ++i) {
        if ((given & (1u << i)) == 0u) continue;
        float2 x = device_at(corner + (n == 4u ? samples4[i] : (n == 2u ? samples2[i] : float2(0.5f, 0.5f))));
        if (!in_glint(v.glint, x)) continue;
        // the group's layer here: every glint over the sample, its own first
        float keep = 1.0f - asfloat(words[at + 4u]);
        for (uint k = 0u; k < count; ++k) {
            uint j = words[first + k];
            if (in_glint(j, x)) keep = keep * (1.0f - asfloat(words[push.mode.z + j * GLINT_WORDS + 4u]));
        }
        sum = sum + (1.0f - keep);
        mask |= 1u << i;
    }
    if (mask == 0u) discard;
    float u = sum / float(countbits(mask));
    float3 s = unpack(push.colour.x).rgb;
    StrokeOutput o;
    o.colour = float4(s * u, u);
    o.coverage = mask;
    return o;
}

// ---------------------------------------------------------------- the sky layer (passes/Sky.cpp)
//
// sky_layer's sun and the city's glow at night, each at its slot over the sky's gradient (and under every nearer
// ground, by depth): the sun's glow and its disc on a quad about their circle (push.rows: its centre and radius, CSS px),
// the city's glow over the frame (screen_vs).

/// A circle's quad: the circle of centre push.rows.xy and radius push.rows.z (CSS px), grown by a device px, at painter
/// slot push.mode.y.
[shader("vertex")]
ScreenOut disc_vs(uint vertex : SV_VulkanVertexID) {
    float density = frame.camera.frame.z;
    float2 k = quad_corners[vertex];
    float reach = push.rows.z + 1.0f / density;
    float2 css = push.rows.xy + float2(k.x < 0.0f ? -reach : reach, k.y < 0.0f ? -reach : reach);
    ScreenOut o;
    o.position = clip_of(frame.camera, css, 1.0f, painter_depth(frame.camera, push.mode.y));
    return o;
}

/// The sun's glow (`sunglow`, a radialGradient over the circle's box): at the pixel's centre, its stops at 0, .25 and 1
/// (push.colour.x, .y and .z, each opacity folded into its alpha) interpolated in their encoded values, premultiplied;
/// nothing beyond the circle, where the gradient has faded to nothing.
[shader("fragment")]
ColourOut sunglow_fs(ScreenOut v) {
    float2 css = css_of_output(frame.camera, v.position.xy);
    float t = length(css - push.rows.xy) / push.rows.z;
    if (t > 1.0f) discard;
    float4 a = unpack(push.colour.x);
    float4 b = unpack(push.colour.y);
    float4 c = unpack(push.colour.z);
    float4 g = t <= 0.25f ? lerp(a, b, t * 4.0f) : lerp(b, c, (t - 0.25f) / 0.75f);
    ColourOut o;
    o.colour = float4(g.rgb * g.a, g.a);
    return o;
}

/// The sun's disc: the circle filled with push.colour.x on the samples within it (exactly, as the trees' circles).
[shader("fragment")]
StrokeOutput sundisc_fs(ScreenOut v, uint given : SV_Coverage) {
    float2 corner = floor(v.position.xy);
    uint n = push.mode.w;
    float density = frame.camera.frame.z;
    float r2 = push.rows.z * push.rows.z;
    uint mask = 0u;
    for (uint i = 0u; i < n; ++i) {
        if ((given & (1u << i)) == 0u) continue;
        float2 css = device_at(corner + (n == 4u ? samples4[i] : (n == 2u ? samples2[i] : float2(0.5f, 0.5f)))) /
                     density;
        float2 d = css - push.rows.xy;
        if (d.x * d.x + d.y * d.y <= r2) mask |= 1u << i;
    }
    if (mask == 0u) discard;
    StrokeOutput o;
    o.colour = float4(unpack(push.colour.x).rgb, 1.0f);
    o.coverage = mask;
    return o;
}

/// The city's glow at night (`cityglow`): the linearGradient from row push.rows.x down to push.rows.y between its
/// stops (push.colour.x and .y) at the pixel's centre, padded, premultiplied, on the samples above the rect's bottom
/// (push.rows.z, CSS px).
[shader("fragment")]
StrokeOutput cityglow_fs(ScreenOut v, uint given : SV_Coverage) {
    float2 corner = floor(v.position.xy);
    uint n = push.mode.w;
    float density = frame.camera.frame.z;
    uint mask = 0u;
    for (uint i = 0u; i < n; ++i) {
        if ((given & (1u << i)) == 0u) continue;
        float2 css = device_at(corner + (n == 4u ? samples4[i] : (n == 2u ? samples2[i] : float2(0.5f, 0.5f)))) /
                     density;
        if (css.y < push.rows.z) mask |= 1u << i;
    }
    if (mask == 0u) discard;
    float y = css_of_output(frame.camera, v.position.xy).y;
    float y0 = push.rows.x;
    float y1 = push.rows.y;
    float t = y1 != y0 ? saturate((y - y0) / (y1 - y0)) : (y < y0 ? 0.0f : 1.0f);
    float4 g = lerp(unpack(push.colour.x), unpack(push.colour.y), t);
    StrokeOutput o;
    o.colour = float4(g.rgb * g.a, g.a);
    o.coverage = mask;
    return o;
}

/// The sky layer's clouds and their shadows (screen_vs; passes/Sky.cpp, CP/fx/Clouds.hpp): one of the two filters'
/// results over the pixels of their region (the rows above push.colour.x), from its table of 256 in `words`
/// (premultiplied sRGB, a r g b: cloudsh2's from push.mode.z, cloudf's after it) at the red its matrix reads of the
/// clouds' field, premultiplied. cloudf (push.colour.w 1) reads the field at the pixel; cloudsh2 (0) the field moved
/// down by its offset: the pixel push.colour.y rows up, blended with the one a row above it by push.colour.z of 2^24
/// (CP/fx/Clouds.hpp's cloud_blend, whole numbers), nothing above the first row.
[shader("fragment")]
ColourOut clouds_fs(ScreenOut v) {
    int2 p = fx_pixel(frame.camera, v.position.xy);
    if (p.y >= int(push.colour.x)) {
        discard;
    }
    uint table = push.mode.z;
    uint2 n = uint2(0u, 0u);
    if (push.colour.w == 0u) {
        int row = p.y - int(push.colour.y);
        if (row >= 0) {
            n = fx_clouds(int2(p.x, row));
        }
        uint above = push.colour.z;
        if (above != 0u) {
            uint2 over = uint2(0u, 0u);
            if (row >= 1) {
                over = fx_clouds(int2(p.x, row - 1));
            }
            n = (((16777216u - above) * n) + (above * over) + 8388608u) >> 24u;
        }
    } else {
        table += 256u;
        n = fx_clouds(p);
    }
    // the red the matrix reads: the premultiplied red unpremultiplied as vImage does it
    uint red = n.x;
    if (n.y == 0u) {
        red = 0u;
    } else if (n.y != 255u) {
        red = min((n.x * 255u + n.y / 2u) / n.y, 255u);
    }
    ColourOut o;
    o.colour = levels_of(words[table + red]) * (1.0f / 255.0f);
    return o;
}

// ---------------------------------------------------------------- the lit streets (passes/LitGlow.cpp)
//
// A lit street's run's segment is an instance (its word run << 14 | segment, from push.mode.z), drawn into the glow's
// or the cores' layer (the view's upright device px, push.rows.x of margin about them, push.rows.yz the layer's size):
// its ends and its run's points before and after them, CSS px in `words` (its run's record from push.at.w, no point
// repeating the last), in device px; its coverage as the ground's lines find it (ground/lines.slangh: a hairline, the
// exact area or the standard samples inside its shape), round-capped and round-joined, at the run's width
// (push.colour.y: 0 the glow's, 1 the core's), in the layer's colour (push.colour.x), opaque. A run of one point is a
// dot, as RefRaster draws one: its radius's coverage at the pixel's centre, or the samples within it. The group's
// opacity comes with its composite.

/// A run's record in `words`: its points' first word (two f32 a point, CSS px), their count, its width and its core's
/// (CSS px, f32).
static const uint LIT_RUN_WORDS = 4u;

/// Point k of the run whose points begin at word `first`, CSS px.
float2 lit_point(uint first, uint k) {
    return float2(asfloat(words[first + 2u * k]), asfloat(words[first + 2u * k + 1u]));
}

[shader("vertex")]
StrokeOut lit_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    uint word = words[push.mode.z + instance];
    uint run = push.at.w + (word >> 14u) * LIT_RUN_WORDS;
    uint seg = word & 0x3FFFu;
    uint first = words[run];
    uint count = words[run + 1u];
    float width = asfloat(words[run + 2u + push.colour.y]);
    float density = frame.camera.frame.z;
    float2 a = lit_point(first, seg) * density;
    float2 b = lit_point(first, min(seg + 1u, count - 1u)) * density;
    uint info = 0u;
    float2 p = a;
    float2 n = b;
    if (seg > 0u) {
        p = lit_point(first, seg - 1u) * density;
        info |= LINE_HAS_PREV;
    }
    if (seg + 2u < count) {
        n = lit_point(first, seg + 2u) * density;
        info |= LINE_HAS_NEXT;
    }
    float hw = width * density * 0.5f;
    float wide = 2.0f * hw;
    info |= wide <= 1.0f ? LINE_HAIR : (wide < 2.0f ? LINE_AREA : LINE_SAMPLES);
    // the quad: along the segment, its join's wedge and the coverage's reach beyond each end, across by the reach
    float2 d = b - a;
    float len = length(d);
    float2 u = len > 1e-6f ? d / len : float2(1.0f, 0.0f);
    float2 v = float2(-u.y, u.x);
    float reach = hw * 4.0f + 2.0f;
    float2 c = quad_corners[vertex];
    float2 at = (c.x < 0.0f ? a - u * reach : b + u * reach) + v * (c.y * reach);
    StrokeOut o;
    o.position = float4((at + push.rows.x) / push.rows.yz * 2.0f - 1.0f, 0.0f, 1.0f);
    float4 paint = unpack(push.colour.x);
    o.colour = float4(paint.rgb * paint.a, paint.a);
    o.ab = float4(a, b);
    o.pn = float4(p, n);
    o.info = uint4(info, asuint(hw), LINE_CAP_ROUND | LINE_JOIN_ROUND, word);
    o.dash = float4(0.0f, 0.0f, 0.0f, 0.0f);
    o.taper = 0.0f;
    return o;
}

/// A run's pixel in its layer (4x): a hairline's or a thin stroke's coverage at the pixel's centre scaling its colour
/// over every sample, or a wide stroke's colour on the standard samples inside its shape; a dot's alike.
[shader("fragment")]
StrokeOutput lit_fs(StrokeOut s) {
    Seg g = seg_of(s);
    float2 corner = floor(s.position.xy) - push.rows.x; // the view's device px
    uint mode = s.info.x & 3u;
    bool lone = g.len < 1e-9f && !g.prev && !g.next; // a dot
    StrokeOutput o;
    if (mode == LINE_SAMPLES) {
        uint mask = 0u;
        for (uint i = 0u; i < 4u; ++i) {
            float2 x = corner + samples4[i];
            bool inside = lone ? length(x - g.a) <= g.hw : g.len >= 1e-9f && seg_inside(g, x);
            if (inside) mask |= 1u << i;
        }
        if (mask == 0u) discard;
        o.colour = s.colour;
        o.coverage = mask;
        return o;
    }
    float2 centre = corner + 0.5f;
    float cov = lone ? radial(length(centre - g.a), g.hw)
                    : (mode == LINE_HAIR ? hair_coverage(g, floor(centre), 2.0f * g.hw)
                                         : (g.len < 1e-9f ? 0.0f : seg_coverage(g, centre)));
    if (cov <= 0.0f) discard;
    o.colour = s.colour * min(cov, 1.0f);
    o.coverage = 0xFFFFFFFFu;
    return o;
}

/// The lit streets' groups over the ground at their slots (screen_vs), screened (the pipeline's blend) at the group's
/// opacity (push.rows.z), premultiplied, from the layers' px (the frame's upright pixel and push.colour.w of margin,
/// push.colour.x texels a row): push.mode.z 0, the glow's filter's result written back as premultiplied sRGB, nothing
/// beyond the result's region (push.colour.y its x0 | y0 << 16, z its x1 | y1 << 16, the frame's px), where it is
/// transparent; 1, the glow's layer itself (the filters off); 2, the wet cores' layer.
[shader("fragment")]
ColourOut lit_composite_fs(ScreenOut v) {
    int2 p = fx_pixel(frame.camera, v.position.xy);
    int m = int(push.colour.w);
    float4 c;
    if (push.mode.z == 0u) {
        int2 lo = {int(push.colour.y & 0xFFFFu), int(push.colour.y >> 16u)};
        int2 hi = {int(push.colour.z & 0xFFFFu), int(push.colour.z >> 16u)};
        if (p.x < lo.x || p.y < lo.y || p.x >= hi.x || p.y >= hi.y) {
            discard;
        }
        uint s = fx_to_srgb_premultiplied(glow_result[uint(p.y + m) * push.colour.x + uint(p.x + m)]);
        c = float4(float((s >> 16u) & 0xFFu), float((s >> 8u) & 0xFFu), float(s & 0xFFu), float(s >> 24u)) *
            (1.0f / 255.0f);
    } else if (push.mode.z == 1u) {
        c = glow_layer.Load(int3(p.x + m, p.y + m, 0));
    } else {
        c = core_layer.Load(int3(p.x + m, p.y + m, 0));
    }
    ColourOut o;
    o.colour = c * push.rows.z;
    return o;
}

// ---------------------------------------------------------------- the night's pools (passes/Pools.cpp)

struct PoolOut {
    float4 position : SV_Position;
    nointerpolation float4 ellipse : POOL_ELLIPSE; // its centre and radii, CSS px
};

/// A street lamp's pool: instance i's ellipse (its centre and radii at push.mode.z + 4i, CSS px) on a quad grown by a
/// device px, at painter slot push.mode.y.
[shader("vertex")]
PoolOut pool_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    uint at = push.mode.z + instance * 4u;
    float4 e = {asfloat(words[at]), asfloat(words[at + 1u]), asfloat(words[at + 2u]), asfloat(words[at + 3u])};
    float px = 1.0f / frame.camera.frame.z;
    float2 k = quad_corners[vertex];
    float2 css = e.xy + float2(k.x < 0.0f ? -(e.z + px) : e.z + px, k.y < 0.0f ? -(e.w + px) : e.w + px);
    PoolOut o;
    o.position = clip_of(frame.camera, css, 1.0f, painter_depth(frame.camera, push.mode.y));
    o.ellipse = e;
    return o;
}

/// The pool's gradient (`pool`, a radialGradient over the ellipse's box) at the pixel's centre: its stops at 0, .5 and
/// 1 (push.colour.x, .y and .z, each opacity folded into its alpha) interpolated in their encoded values, premultiplied;
/// nothing beyond the ellipse, where the gradient has faded to nothing.
[shader("fragment")]
ColourOut pool_fs(PoolOut v) {
    float2 css = css_of_output(frame.camera, v.position.xy);
    float2 d = (css - v.ellipse.xy) / v.ellipse.zw;
    float t = sqrt(d.x * d.x + d.y * d.y);
    if (t > 1.0f) discard;
    float4 a = unpack(push.colour.x);
    float4 b = unpack(push.colour.y);
    float4 c = unpack(push.colour.z);
    float4 g = t <= 0.5f ? lerp(a, b, t * 2.0f) : lerp(b, c, (t - 0.5f) * 2.0f);
    ColourOut o;
    o.colour = float4(g.rgb * g.a, g.a);
    return o;
}

// ---------------------------------------------------------------- the drizzle's rings (passes/Rings.cpp)
//
// The rings' words from push.mode.z: a header (the rings' count, the grid's columns and rows, the bands' count, and
// where the rings, the cells, their lists and the bands lie), each ring's record (its centre and radii in the tile's
// px, its opacity), each cell's list of the rings whose strokes reach it, and each band's rows and the pattern's
// transform there (y0, y1, then translate(tx 0) scale(kx ky)). One triangle over the frame draws them in the water's
// clip: at each sample in a band, its place in the 173 × 97 tile, the rings of its cell whose strokes hold it (within
// push.rows.x of the ellipse), their layer 1 - Π(1 - opacity) in the rings' colour (push.colour.x) at the rect's
// opacity (push.rows.y), the mean over the samples they reach.

/// The margin (in the tile's px) the ellipse's own bounds keep from the stroke's half width before they decide a
/// sample, far beyond the float rounding of both them and ellipse_distance (each within a few 1e-6 px here).
static const float RING_MARGIN = 0.001f;

/// The distance from p (relative to an ellipse's centre) to the ellipse of semi-axes e (e.x >= e.y > 0): D. Eberly's
/// bisection for the closest point.
float ellipse_distance(float2 p, float2 e) {
    float y0 = abs(p.x);
    float y1 = abs(p.y);
    if (y1 > 0.0f) {
        if (y0 > 0.0f) {
            float z0 = y0 / e.x;
            float z1 = y1 / e.y;
            float g = z0 * z0 + z1 * z1 - 1.0f;
            if (g == 0.0f) return 0.0f;
            float r0 = (e.x / e.y) * (e.x / e.y);
            float n0 = r0 * z0;
            float s0 = z1 - 1.0f;
            float s1 = g < 0.0f ? 0.0f : length(float2(n0, z1)) - 1.0f;
            float s = 0.0f;
            for (uint i = 0u; i < 64u; ++i) {
                s = 0.5f * (s0 + s1);
                if (s == s0 || s == s1) break;
                float a = n0 / (s + r0);
                float b = z1 / (s + 1.0f);
                float h = a * a + b * b - 1.0f;
                if (h > 0.0f) {
                    s0 = s;
                } else if (h < 0.0f) {
                    s1 = s;
                } else {
                    break;
                }
            }
            float x0 = r0 * y0 / (s + r0);
            float x1 = y1 / (s + 1.0f);
            return length(float2(x0 - y0, x1 - y1));
        }
        return abs(y1 - e.y);
    }
    float numer = e.x * y0;
    float denom = e.x * e.x - e.y * e.y;
    if (numer < denom) {
        float q = numer / denom;
        return length(float2(e.x * q - y0, e.y * sqrt(1.0f - q * q)));
    }
    return abs(y0 - e.x);
}

/// The drizzle's pixel: the rings' layer at its samples in the pattern, at the rect's opacity, premultiplied.
[shader("fragment")]
StrokeOutput rings_fs(ScreenOut v, uint given : SV_Coverage) {
    uint base = push.mode.z;
    uint gx = words[base + 1u];
    uint gy = words[base + 2u];
    uint bands = words[base + 3u];
    uint ring_at = words[base + 4u];
    uint cell_at = words[base + 5u];
    uint band_at = words[base + 7u];
    float density = frame.camera.frame.z;
    float hw = push.rows.x;
    float2 tile = float2(173.0f, 97.0f);
    float2 corner = floor(v.position.xy);
    uint n = push.mode.w;
    uint mask = 0u;
    float sum = 0.0f;
    for (uint i = 0u; i < n; ++i) {
        if ((given & (1u << i)) == 0u) continue;
        float2 css = device_at(corner + (n == 4u ? samples4[i] : (n == 2u ? samples2[i] : float2(0.5f, 0.5f)))) /
                     density;
        // its band, and its place in the tile there
        uint at = 0xFFFFFFFFu;
        for (uint b = 0u; b < bands; ++b) {
            uint k = band_at + b * 8u;
            if (css.y >= asfloat(words[k]) && css.y < asfloat(words[k + 1u])) {
                at = k;
                break;
            }
        }
        if (at == 0xFFFFFFFFu) continue;
        float2 q = float2((css.x - asfloat(words[at + 2u])) / asfloat(words[at + 3u]), css.y / asfloat(words[at + 4u]));
        float2 t = q - tile * floor(q / tile);
        uint cx = min(uint(t.x / (tile.x / float(gx))), gx - 1u);
        uint cy = min(uint(t.y / (tile.y / float(gy))), gy - 1u);
        uint cell = cell_at + (cy * gx + cx) * 2u;
        uint first = words[cell];
        uint count = words[cell + 1u];
        float keep = 1.0f;
        for (uint k = 0u; k < count; ++k) {
            uint r = ring_at + words[first + k] * 8u;
            float2 c = float2(asfloat(words[r]), asfloat(words[r + 1u]));
            float2 e = float2(asfloat(words[r + 2u]), asfloat(words[r + 3u]));
            float2 d = t - c;
            if (abs(d.x) > e.x + hw || abs(d.y) > e.y + hw) continue;
            // The ellipse's own measure first (ENGINE.md gain 19): with g = |(d.x / e.x, d.y / e.y)| the point lies on
            // the ellipse scaled by g, and its distance to the ellipse lies between e.y · |g − 1| and e.x · |g − 1|
            // (the scaled ellipse's support lines, and the scaling's own move). That settles the samples clearly
            // outside the stroke or clearly within it; the bisection runs where the bounds straddle the half width,
            // so every sample is decided as the bisection alone decides it.
            float2 z = d / e;
            float gap = abs(sqrt(z.x * z.x + z.y * z.y) - 1.0f);
            if (e.y * gap > hw + RING_MARGIN) continue;
            if (e.x * gap < hw - RING_MARGIN || ellipse_distance(d, e) <= hw) {
                keep = keep * (1.0f - asfloat(words[r + 4u]));
            }
        }
        if (keep < 1.0f) {
            sum = sum + (1.0f - keep);
            mask |= 1u << i;
        }
    }
    if (mask == 0u) discard;
    float a = (sum / float(countbits(mask))) * push.rows.y;
    StrokeOutput o;
    o.colour = float4(unpack(push.colour.x).rgb * a, a);
    o.coverage = mask;
    return o;
}
