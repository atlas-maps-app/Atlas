// The camera in the vertex stage (plan-vector D4): the engine's Camera (render/city.py:20, R/City.kt Camera) as the GPU
// projects with it, shared with the C++ twin (after base/hlsl.hpp, by CP/view/Camera.hpp). The core composes the
// frame's camera in double, relative to the eye (CP/view/Camera.cpp), and hands it over in float: the basis, the
// principal point and the focal length in CSS px, the frame's density, and the output's pre-rotation. Positions arrive
// as float metres relative to their tile's south-west corner and are placed relative to the eye by the tile's own
// placement (TilePlace), composed in double, so a float keeps about 0.1 mm wherever the tile lies.
//
// Projection: camera coordinates k = (r · p, u · p, f · p) of the eye-relative point p, each dot product summed left to
// right as the port's vdot; the screen point (CSS px) cx + focal·k.x / k.z, cy − focal·k.y / k.z as the port's pc;
// device px the CSS point times the density about the frame's top-left corner (plan-gpu D3's rule); clip space from
// those with the output's quarter turns, and z = d·w for the primitive's painter depth d (painter.slang, D6). At rest
// the vertex stage snaps the CSS point to 0.1 px as the SVG prints it (snap_tenth), before it goes to the device.
//
// Shared with the C++ twin: only the subset base/hlsl.hpp describes. Every member of the structs is a four-lane vector
// of 32-bit values, so std140, std430 and C++ lay them out the same way.
#pragma once

/// The frame's camera, in float: 112 bytes.
struct FrameCamera {
    float4 right;   // xyz: the camera's right unit vector r; w: the principal point's x, cx (CSS px)
    float4 up;      // xyz: u; w: cy (CSS px)
    float4 forward; // xyz: f, the view direction; w: the focal length (CSS px per unit of tangent)
    float4 frame;   // x: the engine frame's width W, y: its height H (CSS px); z: the density (device px per CSS px);
                    // w: the near plane (metres, the engine's 0.6)
    float4 device;  // x: 2 / the view's width in device px, y: 2 / its height; z: the density's reciprocal; w: 0
    float4 turn;    // the output's pre-rotation of the view's normalised coordinates: x' = (x, y)·(turn.x, turn.y),
                    // y' = (x, y)·(turn.z, turn.w); the identity on a surface that needs none
    uint4 flags;    // x: the CAMERA_ bits; y: the frame's number (low 32 bits); z, w: 0
};

/// The camera rests: the vertex stage snaps every point to the SVG's print precision (D4, "Snapping at rest").
static const uint CAMERA_REST = 1u;
/// Engine mode: the renders' radius cut is applied (the likeness cases), D9.
static const uint CAMERA_ENGINE_MODE = 2u;

/// A tile's place relative to the eye (D2): eye-relative frame metres = local metres times the scale, plus the offset.
/// The scale is the frame's metres per tile metre along x (the ratio of the two frames' kx; 1 in the tile's own frame)
/// while the frame changes it blends (CP/view/Frames.cpp); z and y keep the metre.
struct TilePlace {
    float4 offset; // xyz: the tile's south-west corner at street level minus the eye, frame metres; w: 0
    float4 scale;  // x: metres of the frame per metre of the tile along x; y, z: 1; w: the tile's fade (0 to 1)
};

/// The eye-relative point of a tile-local point.
inline float3 eye_relative(TilePlace t, float3 local) {
    float3 p = {local.x * t.scale.x + t.offset.x, local.y + t.offset.y, local.z + t.offset.z};
    return p;
}

/// Camera coordinates (right, up, forward) of an eye-relative point: the port's cam, vdot summed left to right.
inline float3 camera_of(FrameCamera c, float3 p) {
    float3 k = {((p.x * c.right.x) + (p.y * c.right.y)) + (p.z * c.right.z),
                ((p.x * c.up.x) + (p.y * c.up.y)) + (p.z * c.up.z),
                ((p.x * c.forward.x) + (p.y * c.forward.y)) + (p.z * c.forward.z)};
    return k;
}

/// The screen point (CSS px) of camera coordinates k (k.z > 0): the port's pc.
inline float2 css_of(FrameCamera c, float3 k) {
    float2 s = {c.right.w + c.forward.w * k.x / k.z, c.up.w - c.forward.w * k.y / k.z};
    return s;
}

/// The value Python's '%.1f' prints for v, as a float: the snap at rest (D4). Python rounds the exact binary value to
/// the nearest tenth, ties to even, and prints a zero with v's sign (-0.0 for small negatives). Done in integers, which
/// every GPU computes exactly whatever it may do to float arithmetic outside an exact module: |v| = m · 2^(e − 23) with
/// its 24-bit significand m, so 10|v| = 10m · 2^(e − 23), whose integer part and remainder come from one shift, and the
/// remainder against a half decides, an exact half going to the even integer. The tenths q (below 2^24, so exact as a
/// float) become q / 10, the float nearest it, which prints as q's tenths. From 2^20 on a float's step is an eighth or
/// more, so the float nearest the printed tenth is v itself: v is its own snap there, as is an infinity or a NaN; below
/// 2^-5 the tenth is zero.
inline float snap_tenth(float v) {
    uint bits = asuint(v);
    uint sign = bits & 0x80000000u;
    uint magnitude = bits & 0x7FFFFFFFu;
    int e = int(magnitude >> 23u) - 127;
    if (e >= 20) {
        return v;
    }
    if (e < -5) {
        return asfloat(sign);
    }
    uint p = ((magnitude & 0x7FFFFFu) | 0x800000u) * 10u; // 10|v| = p · 2^(e − 23), p < 2^28
    uint shift = uint(23 - e);                             // 4 to 28
    uint q = p >> shift;
    uint rest = p & ((1u << shift) - 1u);
    uint half = 1u << (shift - 1u);
    if (rest > half || (rest == half && (q & 1u) != 0u)) {
        q = q + 1u;
    }
    float r = float(q) / 10.0f;
    return asfloat(asuint(r) | sign);
}

/// The clip position of the CSS point `css` of a primitive whose eye-relative depth (k.z) is `kz`, with painter depth
/// `depth` (painter.slang): device px are CSS px times the density from the frame's top-left corner; normalised
/// coordinates are px · 2 / size − 1; then the output's pre-rotation; z = depth · w, so Vulkan clips at the eye plane and
/// the primitive keeps its depth after the division (D6, "The near plane").
inline float4 clip_of(FrameCamera c, float2 css, float kz, float depth) {
    float2 n = {(css.x * c.frame.z) * c.device.x - 1.0f, (css.y * c.frame.z) * c.device.y - 1.0f};
    float2 t = {c.turn.x * n.x + c.turn.y * n.y, c.turn.z * n.x + c.turn.w * n.y};
    float4 clip = {t.x * kz, t.y * kz, depth * kz, kz};
    return clip;
}

/// The signed distance of camera coordinates k beyond the engine's near plane (a clip distance: negative is cut), for
/// the items the render thread flags as reaching within it (D6, "The near plane").
inline float near_distance(FrameCamera c, float3 k) {
    return k.z - c.frame.w;
}

/// Whether the camera rests (snapping on).
inline bool camera_rests(FrameCamera c) {
    return (c.flags.x & CAMERA_REST) != 0u;
}

/// The view's CSS point of a position in the output (SV_Position: device px from the output's top-left corner, a pixel's
/// centre at + 0.5): the pre-rotation undone (its transpose: a quarter turn is orthogonal), then the density.
inline float2 css_of_output(FrameCamera c, float2 px) {
    bool odd = c.turn.y != 0.0f; // a quarter or three quarters: the output is the view's size turned
    float2 scale = {odd ? c.device.y : c.device.x, odd ? c.device.x : c.device.y};
    float2 n = {px.x * scale.x - 1.0f, px.y * scale.y - 1.0f};
    float2 v = {c.turn.x * n.x + c.turn.z * n.y, c.turn.y * n.x + c.turn.w * n.y};
    float2 css = {(v.x + 1.0f) / c.device.x * c.device.z, (v.y + 1.0f) / c.device.y * c.device.z};
    return css;
}

/// The CSS point of a tile-local point at this frame, snapped at rest; `k` is its camera coordinates.
inline float2 project_css(FrameCamera c, float3 k) {
    float2 s = css_of(c, k);
    if (camera_rests(c)) {
        float2 snapped = {snap_tenth(s.x), snap_tenth(s.y)};
        s = snapped;
    }
    return s;
}
