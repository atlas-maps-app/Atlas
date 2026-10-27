// The location's puck (ENGINE.md step 6; passes/Overlays.cpp): where the user is, drawn by the core in the map's own
// frame after the labels, so it moves with the map in the same frame, as mapview's OverlayPainter.drawLocation draws
// it on the canvas (the board's puck):
//   - the fix's accuracy: a circle of its radius on the street (13 % of the puck's blue, its edge a 1 dp line at 35 %),
//     where it is wider than 20 dp at its centre's depth;
//   - with a heading, its cone: a 36 x 40 dp box with round top corners (radius 18), its bottom 2 dp under the dot's
//     centre and turned there toward the heading on screen, filled with the board's conic gradient (40 % of the blue
//     at 25 degrees left of its axis, none at 25 degrees right of it, and none from 75 degrees left round to there);
//   - a disc of 20 dp at 16 %, a glow of 10 dp at 55 % 2 dp lower, blurred as the canvas's BlurMaskFilter blurs it (a
//     Gaussian of 4 dp), a white disc of 10 dp and the blue dot of 7 dp.
// An edge's coverage is the pixel's distance from it, as Skia's antialiased circles; the colours are sRGB values over
// sRGB values, premultiplied, as the canvas blends them. The accuracy's circle is the street's: each pixel's ray meets
// the street, and its distance from the fix is measured there, in the frame's metres.
//
// Variants: loc1 writes colour attachment 1, where the composite writes the dynamic route's 1x output beside the
// scene's colour (shaders/frame/composite_x1_loc1.slang).
// atlas: variant loc1 OVERLAY_LOC1=1

#include "shared/frame.slang"

#ifndef OVERLAY_LOC1
#define OVERLAY_LOC1 0
#endif

/// What the frame draws of the location (passes/Overlays.cpp writes it): a part a draw, the instance its number.
struct LocationPush {
    float4 dot;      // xy: the dot's centre (the view's device px); z: the density (px per dp); w: the cone's turn,
                     // radians clockwise from up on screen
    float4 ground;   // xyz: the fix on the street relative to the eye (frame metres); w: the accuracy's radius (frame
                     // metres)
    float4 boxes[2]; // the accuracy's and the puck's quads: left, top, right, bottom (the view's device px)
    uint4 flags;     // x: LOCATION_HEADING
};
[[vk::push_constant]] ConstantBuffer<LocationPush> push;

[[vk::binding(0, 0)]] ConstantBuffer<FrameBlock> frame;

/// The parts, by the instance drawn.
static const uint LOCATION_ACCURACY = 0u;
static const uint LOCATION_PUCK = 1u;
/// The fix has a heading: the puck draws its cone.
static const uint LOCATION_HEADING = 1u;

/// The puck's blue, #2E5BFF.
static const float3 location_blue = float3(46.0f, 91.0f, 255.0f) / 255.0f;

struct LocationOut {
    float4 position : SV_Position;
    nointerpolation uint part : TEXCOORD0;
};

static const float2 location_corners[6] = { float2(0, 0), float2(1, 0), float2(0, 1), float2(0, 1), float2(1, 0), float2(1, 1) };

[shader("vertex")]
LocationOut location_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    FrameCamera c = frame.camera;
    float4 box = push.boxes[min(instance, 1u)];
    float2 px = box.xy + location_corners[vertex] * (box.zw - box.xy);
    // The view's device px to its normalised coordinates, then the output's pre-rotation, as the labels' sprites.
    float2 n = {px.x * c.device.x - 1.0f, px.y * c.device.y - 1.0f};
    LocationOut o;
    o.position = float4(c.turn.x * n.x + c.turn.y * n.y, c.turn.z * n.x + c.turn.w * n.y, 0.0f, 1.0f);
    o.part = instance;
    return o;
}

struct LocationColour {
#if OVERLAY_LOC1
    [[vk::location(1)]] float4 colour : SV_Target1;
#else
    [[vk::location(0)]] float4 colour : SV_Target0;
#endif
};

/// `top` over `under`, premultiplied.
float4 location_over(float4 top, float4 under) { return top + under * (1.0f - top.a); }

/// The blue at opacity `a`, premultiplied.
float4 location_tint(float a) { return float4(location_blue * a, a); }

/// The coverage of a pixel whose centre lies `d` px inside an edge (negative outside): Skia's antialiased edge.
float location_edge(float d) { return saturate(d + 0.5f); }

/// erf(x) within 1.5e-7 (Abramowitz and Stegun 7.1.26).
float location_erf(float x) {
    float a = abs(x);
    float t = 1.0f / (1.0f + 0.3275911f * a);
    float poly = ((((1.061405429f * t - 1.453152027f) * t + 1.421413741f) * t - 0.284496736f) * t + 0.254829592f) * t;
    return sign(x) * (1.0f - poly * exp(-a * a));
}

/// A disc of radius `r` blurred by a Gaussian of sigma `s`, at `d` from its centre (all px): the disc's profile across
/// its centre blurred, which the canvas's blurred circle comes within a few hundredths of.
float location_blurred(float d, float r, float s) {
    float k = 1.0f / (s * 1.41421356f);
    return 0.5f * (location_erf((r - d) * k) + location_erf((r + d) * k));
}

/// The accuracy's circle at the output's pixel `position` (its view point `view`).
float4 location_accuracy(FrameCamera c, float2 view) {
    // the pixel's ray (camera coordinates, then eye-relative frame metres), and where it meets the street
    float2 css = view / c.frame.z;
    float3 k = {(css.x - c.right.w) / c.forward.w, (c.up.w - css.y) / c.forward.w, 1.0f};
    float3 ray = k.x * c.right.xyz + k.y * c.up.xyz + k.z * c.forward.xyz;
    float4 o = float4(0.0f);
    if (!(ray.y < 0.0f)) {
        return o;
    }
    float t = push.ground.y / ray.y;
    float2 at = ray.xz * t - push.ground.xz;
    float distance = length(at);
    float px = max(fwidth(distance), 1e-6f); // frame metres a pixel, here
    float inside = (push.ground.w - distance) / px;
    o = location_tint(0.13f * location_edge(inside));
    // the edge's line, 1 dp wide about the circle
    float line = 0.5f * push.dot.z - abs(inside);
    return location_over(location_tint(0.35f * location_edge(line)), o);
}

/// The heading's cone at `p` (px from the dot's centre), as OverlayPainter.cone draws it.
float4 location_cone(float2 p, float dp) {
    // into the box's own frame: its bottom middle 2 dp under the dot, turned back by the heading's turn
    float2 q = p - float2(0.0f, 2.0f * dp);
    float cs = cos(push.dot.w);
    float sn = sin(push.dot.w);
    q = float2(q.x * cs + q.y * sn, -q.x * sn + q.y * cs) / dp;
    // 36 x 40 dp, its top corners round (radius 18): the box below 22 dp up, the half disc above (signed, in dp)
    float2 b = abs(q - float2(0.0f, -11.0f)) - float2(18.0f, 11.0f);
    float box = length(max(b, float2(0.0f))) + min(max(b.x, b.y), 0.0f);
    float cap = max(length(q - float2(0.0f, -22.0f)) - 18.0f, q.y + 22.0f);
    float inside = -min(box, cap) * dp;
    // the conic gradient about the bottom middle: Android's sweep (clockwise from the right) turned by 115 degrees
    float sweep = degrees(atan2(q.y, q.x)) + 115.0f;
    sweep = sweep - 360.0f * floor(sweep / 360.0f);
    float a = sweep <= 50.0f ? 0.4f * (1.0f - sweep / 50.0f) : (sweep >= 310.0f ? 0.4f * (sweep - 310.0f) / 50.0f : 0.0f);
    return location_tint(a * location_edge(inside));
}

/// The puck at `p` (px from the dot's centre).
float4 location_puck(float2 p) {
    float dp = push.dot.z;
    float r = length(p);
    float4 o = float4(0.0f);
    if ((push.flags.x & LOCATION_HEADING) != 0u) {
        o = location_cone(p, dp);
    }
    o = location_over(location_tint(0.16f * location_edge(20.0f * dp - r)), o);
    float glow = location_blurred(length(p - float2(0.0f, 2.0f * dp)), 10.0f * dp, 4.0f * dp);
    o = location_over(location_tint(0.55f * glow), o);
    float white = location_edge(10.0f * dp - r);
    o = location_over(float4(white, white, white, white), o);
    return location_over(location_tint(location_edge(7.0f * dp - r)), o);
}

[shader("fragment")]
LocationColour location_fs(LocationOut v) {
    FrameCamera c = frame.camera;
    // the view's point under this fragment (a pixel's centre), in its device px
    float2 view = css_of_output(c, v.position.xy) * c.frame.z;
    LocationColour o;
    o.colour = v.part == LOCATION_ACCURACY ? location_accuracy(c, view) : location_puck(view - push.dot.xy);
    return o;
}
