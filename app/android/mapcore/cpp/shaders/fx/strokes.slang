// The objects group's thin strokes (plan-vector D5.4, D7 "Thin strokes and lines", N13; K17a; CP/passes/ObjectStrokes.hpp):
// the roofs' fine edges (K10), the railings, posts and lattices (K14, K15), each segment a quad the vertex stage builds
// around it, its pixels covered by the exact area of the segment's rectangle in them (a box filter, G1's), blended over
// what the scene drew. Two pipelines draw the same segments, the stencil's objects bit choosing each sample's: over a
// sample without it (the stroke stands alone over the ground) the renders' `mat` thins it, alpha min(1, 1.1·a)·a in
// mat's edge colour; over a sample with it (the stroke lies on the group's own paint) alpha a in mat's colour, as the
// group's other fragments take it. Where mat does not run (mat.slang's mat_on: structural mode, the fields not yet
// bound, a moment without weather) neither: alpha a in the stroke's own colour.
//
// A pass keeps its own stroke vertex stage, so its per-frame decisions (facing, the detail band, the near plane) stay
// in its shaders: it includes this file after `#define ATLAS_FX_FUNCTIONS` and returns stroke_corner (or stroke_none)
// for each of six vertices a segment (two triangles), and ObjectStrokes makes the two pipelines with this module's
// fragment stages. A stroke draws at its primitive's painter slot, over that primitive's own fill (the depth test is
// LESS_OR_EQUAL) and under every nearer one.
//
// The first version's residual (named, and counted at the corners by CP/tests/fx/strokes_vk_test.cpp): the objects
// group's strokes have round joins (stroke-linejoin="round"); butt segments, untrimmed, cover a join's inside twice and
// miss its round wedge outside (for a 0.25 px stroke at a right angle, about 0.1 px² of one pixel each way). Trimming
// each segment by its neighbours, and the wedge, are the next refinement.
//
// Built on its own, the file is that module: the two fragment stages and the test's vertex stage (strokes_check_vs).
#pragma once

#if !defined(ATLAS_FX_FUNCTIONS)
#define ATLAS_FX_STROKES_MODULE
#define ATLAS_FX_FUNCTIONS
#endif

#include "fx/mat.slang"
#include "shared/frame.slang"
#include "shared/painter.slang"

/// How far the quad reaches beyond the stroke's rectangle, output px: every pixel the rectangle touches has all its
/// samples inside the quad (a touched pixel's centre lies within √2/2 of the rectangle, a sample within 0.53 of it), so
/// each of its samples blends the pixel's coverage.
static const float STROKE_MARGIN = 1.5f;

/// Half a pixel's diagonal: no point of a pixel lies farther from its centre.
static const float STROKE_HALF_DIAGONAL = 0.70710678f;

/// What a stroke's vertex stage gives its fragment stage.
struct StrokeVertex {
    float4 position : SV_Position;
    /// The segment's ends in output px (SV_Position's frame): a.xy, b.xy.
    [[vk::location(0)]] nointerpolation float4 ends : STROKE_ENDS;
    /// x: half the stroke's width, output px; y, z, w: 0.
    [[vk::location(1)]] nointerpolation float4 style : STROKE_STYLE;
    /// Its colour: sRGB, 0 to 1 at 8-bit levels, unpremultiplied; alpha its opacity.
    [[vk::location(2)]] nointerpolation float4 colour : STROKE_COLOUR;
};

/// Two over the output's size in px (the view's turned at an odd quarter turn): output px from normalised coordinates.
inline float2 stroke_output_scale(FrameCamera camera) {
    bool odd = camera.turn.y != 0.0f;
    float2 s = {odd ? camera.device.y : camera.device.x, odd ? camera.device.x : camera.device.y};
    return s;
}

/// A segment's vertex that draws nothing (a culled stroke: every one of its six vertices).
inline StrokeVertex stroke_none() {
    StrokeVertex o;
    float4 away = {-2.0f, -2.0f, 0.0f, 1.0f};
    float4 zero = {0.0f, 0.0f, 0.0f, 0.0f};
    o.position = away;
    o.ends = zero;
    o.style = zero;
    o.colour = zero;
    return o;
}

/// Vertex `corner` (0 to 5: triangles 0, 1, 2 and 3, 4, 5) of the quad of the stroke from `clip_a` to `clip_b` (the
/// pass's clip positions of its ends, as clip_of gives them: w > 0, the pass having culled or clipped the segment at the
/// near plane), `width_css` CSS px wide (the device's px are CSS px times the density), in `colour` (sRGB 0 to 1,
/// alpha its opacity), at painter slot `slot` (its primitive's). A segment of no length or width draws nothing.
inline StrokeVertex stroke_corner(FrameCamera camera, float4 clip_a, float4 clip_b, float width_css, float4 colour,
                                  uint slot, uint corner) {
    if (!(clip_a.w > 0.0f) || !(clip_b.w > 0.0f) || !(width_css > 0.0f)) {
        return stroke_none();
    }
    float2 scale = stroke_output_scale(camera);
    float2 na = {clip_a.x / clip_a.w, clip_a.y / clip_a.w};
    float2 nb = {clip_b.x / clip_b.w, clip_b.y / clip_b.w};
    float2 a = {(na.x + 1.0f) / scale.x, (na.y + 1.0f) / scale.y};
    float2 b = {(nb.x + 1.0f) / scale.x, (nb.y + 1.0f) / scale.y};
    float2 d = {b.x - a.x, b.y - a.y};
    float len = sqrt(d.x * d.x + d.y * d.y);
    if (!(len > 0.0f)) {
        return stroke_none();
    }
    float2 u = {d.x / len, d.y / len};
    float2 n = {-u.y, u.x};
    float hw = 0.5f * width_css * camera.frame.z;
    // The quad's corners: 0 at a's −n side, 1 at b's, 2 at a's +n side, 3 at b's.
    uint k = corner == 3u ? 2u : (corner == 4u ? 1u : (corner == 5u ? 3u : corner));
    float along = (k & 1u) != 0u ? len + STROKE_MARGIN : -STROKE_MARGIN;
    float across = (k & 2u) != 0u ? hw + STROKE_MARGIN : -(hw + STROKE_MARGIN);
    float2 px = {a.x + u.x * along + n.x * across, a.y + u.y * along + n.y * across};
    StrokeVertex o;
    float4 position = {px.x * scale.x - 1.0f, px.y * scale.y - 1.0f, painter_depth(slot), 1.0f};
    float4 ends = {a.x, a.y, b.x, b.y};
    float4 style = {hw, 0.0f, 0.0f, 0.0f};
    o.position = position;
    o.ends = ends;
    o.style = style;
    o.colour = colour;
    return o;
}

/// Clips the convex polygon `p` (`count` points, at most 8 once clipped) to the side of the line `axis` = `bound`
/// where `side` · (coordinate − bound) ≤ 0 (Sutherland and Hodgman's step).
inline void stroke_clip(inout float2 p[8], inout uint count, uint axis, float bound, float side) {
    float2 q[8];
    uint m = 0u;
    for (uint i = 0u; i < count; ++i) {
        float2 c = p[i];
        float2 e = p[i + 1u < count ? i + 1u : 0u];
        float dc = side * ((axis == 0u ? c.x : c.y) - bound);
        float de = side * ((axis == 0u ? e.x : e.y) - bound);
        if (dc <= 0.0f) {
            q[m] = c;
            m = m + 1u;
        }
        if ((dc <= 0.0f) != (de <= 0.0f)) {
            float t = dc / (dc - de);
            float2 x = {c.x + (e.x - c.x) * t, c.y + (e.y - c.y) * t};
            q[m] = x;
            m = m + 1u;
        }
    }
    for (uint j = 0u; j < 8u; ++j) {
        p[j] = q[j];
    }
    count = m;
}

/// The area of the segment from `a` to `b` (output px) stroked `hw` either side with butt ends, within the pixel
/// whose centre is `centre`: the pixel's square in the segment's own frame (along, across; centred on the pixel's
/// place along it, so every coordinate stays small), clipped to the rectangle, its area by the shoelace.
inline float stroke_area(float2 a, float2 b, float hw, float2 centre) {
    float2 d = {b.x - a.x, b.y - a.y};
    float len = sqrt(d.x * d.x + d.y * d.y);
    if (!(len > 0.0f) || !(hw > 0.0f)) {
        return 0.0f;
    }
    float2 u = {d.x / len, d.y / len};
    float2 n = {-u.y, u.x};
    float2 rel = {centre.x - a.x, centre.y - a.y};
    float sc = rel.x * u.x + rel.y * u.y; // the centre's place along the segment
    float tc = rel.x * n.x + rel.y * n.y; // and across it
    if (abs(tc) > hw + STROKE_HALF_DIAGONAL || sc < -STROKE_HALF_DIAGONAL || sc > len + STROKE_HALF_DIAGONAL) {
        return 0.0f;
    }
    float2 p[8];
    for (uint i = 0u; i < 8u; ++i) {
        float2 z = {0.0f, 0.0f};
        p[i] = z;
    }
    // The square's corners about its centre, in order around it.
    float2 o0 = {-0.5f, -0.5f};
    float2 o1 = {0.5f, -0.5f};
    float2 o2 = {0.5f, 0.5f};
    float2 o3 = {-0.5f, 0.5f};
    float2 c0 = {o0.x * u.x + o0.y * u.y, tc + o0.x * n.x + o0.y * n.y};
    float2 c1 = {o1.x * u.x + o1.y * u.y, tc + o1.x * n.x + o1.y * n.y};
    float2 c2 = {o2.x * u.x + o2.y * u.y, tc + o2.x * n.x + o2.y * n.y};
    float2 c3 = {o3.x * u.x + o3.y * u.y, tc + o3.x * n.x + o3.y * n.y};
    p[0] = c0;
    p[1] = c1;
    p[2] = c2;
    p[3] = c3;
    uint count = 4u;
    stroke_clip(p, count, 0u, -sc, -1.0f);      // along ≥ −sc: from a on
    stroke_clip(p, count, 0u, len - sc, 1.0f);  // along ≤ len − sc: up to b
    stroke_clip(p, count, 1u, -hw, -1.0f);      // across ≥ −hw
    stroke_clip(p, count, 1u, hw, 1.0f);        // across ≤ hw
    float twice = 0.0f;
    for (uint j = 0u; j < count; ++j) {
        float2 c = p[j];
        float2 e = p[j + 1u < count ? j + 1u : 0u];
        twice = twice + (c.x * e.y - e.x * c.y);
    }
    return min(abs(twice) * 0.5f, 1.0f);
}

/// sRGB's transfer function and its inverse (IEC 61966-2-1) on 0 to 1.
inline float stroke_linear_of(float c) {
    return c <= 0.04045f ? c / 12.92f : pow((c + 0.055f) / 1.055f, 2.4f);
}
inline float stroke_srgb_of(float l) {
    return l <= 0.0031308f ? l * 12.92f : 1.055f * pow(l, 1.0f / 2.4f) - 0.055f;
}

/// `mat`'s result for an opaque `colour` (sRGB 0 to 1) standing alone in the group with coverage `a` at grey `g` (the
/// field's byte): the arithmetic composite in linear light (colour 0.9·C + 0.2·C·G, alpha 1.1·A, clamped and corrected
/// to valid premultiplied colour), then `in` SourceAlpha, as premultiplied sRGB to composite over the ground: alpha
/// min(1, 1.1·a)·a, its colour (0.9 + 0.2·G)/1.1 of the stroke's in linear light while 1.1·a < 1.
inline float4 stroke_alone(float3 colour, float a, uint g) {
    float grey = float(g) / 255.0f;
    float ta = min(1.0f, 1.1f * a);
    float k = a * (0.9f + 0.2f * grey);
    float3 t = {min(stroke_linear_of(colour.r) * k, ta) / ta, min(stroke_linear_of(colour.g) * k, ta) / ta,
                min(stroke_linear_of(colour.b) * k, ta) / ta};
    float alpha = ta * a;
    float4 o = {stroke_srgb_of(t.r) * alpha, stroke_srgb_of(t.g) * alpha, stroke_srgb_of(t.b) * alpha, alpha};
    return o;
}

/// The stroke's coverage of the pixel under a fragment at `position` (SV_Position), times its opacity.
inline float stroke_coverage(StrokeVertex v) {
    float2 a = {v.ends.x, v.ends.y};
    float2 b = {v.ends.z, v.ends.w};
    float2 centre = {floor(v.position.x) + 0.5f, floor(v.position.y) + 0.5f};
    return stroke_area(a, b, v.style.x, centre) * v.colour.a;
}

#if defined(ATLAS_FX_STROKES_MODULE)

[[vk::binding(0, 0)]] ConstantBuffer<FrameBlock> frame;

struct StrokeOut {
    [[vk::location(0)]] float4 colour : SV_Target0;
};

/// Over a sample without the objects bit: the stroke alone over the ground, thinned by `mat`.
[shader("fragment")]
StrokeOut strokes_ground_fs(StrokeVertex v) {
    StrokeOut o;
    float a = stroke_coverage(v);
    if (mat_on(frame) && a > 0.0f) {
        float3 c = {v.colour.r, v.colour.g, v.colour.b};
        o.colour = stroke_alone(c, a, mat_grey(fx_pixel(frame.camera, v.position.xy)));
    } else {
        float4 plain = {v.colour.r * a, v.colour.g * a, v.colour.b * a, a};
        o.colour = plain;
    }
    return o;
}

/// Over a sample with the objects bit: the stroke on the group's own paint, in `mat`'s colour.
[shader("fragment")]
StrokeOut strokes_objects_fs(StrokeVertex v) {
    StrokeOut o;
    float a = stroke_coverage(v);
    float3 c = {v.colour.r, v.colour.g, v.colour.b};
    float3 m = mat_colour(frame.camera, c, v.position.xy, mat_on(frame));
    float4 over = {m.r * a, m.g * a, m.b * a, a};
    o.colour = over;
    return o;
}

/// The test's segments (CP/tests/fx/strokes_vk_test.cpp): one a draw, its ends in CSS px in push constants.
struct StrokePush {
    float4 ends;   // a.xy, b.xy: CSS px
    float4 colour; // sRGB 0 to 1, alpha its opacity
    float4 style;  // x: its width, CSS px
    uint4 slot;    // x: its painter slot
    float4 pad[4];
};

[[vk::push_constant]] ConstantBuffer<StrokePush> stroke_push;

[shader("vertex")]
StrokeVertex strokes_check_vs(uint vertex : SV_VulkanVertexID) {
    float2 pa = {stroke_push.ends.x, stroke_push.ends.y};
    float2 pb = {stroke_push.ends.z, stroke_push.ends.w};
    float4 ca = clip_of(frame.camera, pa, 1.0f, 0.0f);
    float4 cb = clip_of(frame.camera, pb, 1.0f, 0.0f);
    return stroke_corner(frame.camera, ca, cb, stroke_push.style.x, stroke_push.colour, stroke_push.slot.x, vertex);
}

#endif
