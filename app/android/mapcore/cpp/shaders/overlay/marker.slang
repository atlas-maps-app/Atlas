// The app's markers (ENGINE.md step 6; passes/Markers.cpp): drawn by the core in the map's own frame after the labels
// and before the location's puck, so they move with the map in the same frame, as mapview's MarkerPainter draws them
// over the canvas:
//   - a marker as itself is the sprite the app drew of its look with the device's own Skia (mapview's MarkerSprites:
//     MarkerPainter's own drawing), its place on a whole device pixel of the view (where the marker's place lands,
//     rounded as Kotlin rounds) and copied texel for texel, so its pixels are the app's;
//   - a cluster is its bubble, drawn here as MarkerPainter.cluster draws it (its look depends on how many it holds,
//     which changes as the map moves): a white disc of its diameter over its shadow (the boards' ink at 25 %, 4 dp
//     lower, blurred as the canvas's shadow layer blurs it, a Gaussian of 5 dp), and the disc of its colour 2 dp inside
//     the white; then its count, a sprite the app drew, centred on it.
// The draws go in order, each over the ones before it: the markers the lowest priority first, a cluster's count after
// its bubble. An edge's coverage is the pixel's distance from it, as Skia's antialiased circles; the colours are sRGB
// values over sRGB values, premultiplied, as the canvas blends them. The output's pre-rotation turns the quad, and the
// fragment stage finds its texel in the view's own pixels (css_of_output).
//
// The draws and the texels are the pass's own set (2): the frame slot's draws, and the slot's copy of the markers'
// sprites, every sprite's rows one after another, premultiplied RGBA8, R in the low byte.
//
// Variants: loc1 writes colour attachment 1, where the composite writes the dynamic route's 1x output beside the
// scene's colour (shaders/frame/composite_x1_loc1.slang).
// atlas: variant loc1 OVERLAY_LOC1=1

#include "shared/coverage.slang"
#include "shared/frame.slang"

#ifndef OVERLAY_LOC1
#define OVERLAY_LOC1 0
#endif

/// One thing to draw: a sprite, or a cluster's bubble.
struct MarkerDraw {
    float4 target; // a sprite: its top-left corner (x, y) and its size (z, w), whole device px of the view; a bubble:
                   // its centre (x, y) and radius (z) in the view's device px, and the density (w, px per dp)
    uint4 source;  // a sprite: its first texel (x), its width (y) and height (z); a bubble: its colour (x, opaque
                   // ARGB); w: the kind (MARKER_SPRITE, MARKER_BUBBLE)
};

[[vk::binding(0, 0)]] ConstantBuffer<FrameBlock> frame;
[[vk::binding(0, 2)]] StructuredBuffer<MarkerDraw> marker_draws;
[[vk::binding(1, 2)]] StructuredBuffer<uint> marker_texels;

/// The kinds of draw.
static const uint MARKER_SPRITE = 0u;
static const uint MARKER_BUBBLE = 1u;

/// How far a bubble's shadow reaches beyond its disc, dp: 4 dp lower, three sigmas of 5 dp, and a pixel more.
static const float marker_shadow_reach = 20.0f;
/// The shadow: the boards' ink #14161B at 25 % (Colours.withAlpha's 64 of 255), 4 dp lower, a Gaussian of 5 dp.
static const float3 marker_ink = float3(20.0f, 22.0f, 27.0f) / 255.0f;
static const float marker_shadow_alpha = 64.0f / 255.0f;

struct MarkerOut {
    float4 position : SV_Position;
    nointerpolation uint draw : TEXCOORD0;
};

static const float2 marker_corners[6] = { float2(0, 0), float2(1, 0), float2(0, 1), float2(0, 1), float2(1, 0), float2(1, 1) };

[shader("vertex")]
MarkerOut marker_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    MarkerDraw d = marker_draws[instance];
    FrameCamera c = frame.camera;
    float2 lo = d.target.xy;
    float2 size = d.target.zw;
    if (d.source.w == MARKER_BUBBLE) {
        float reach = d.target.z + marker_shadow_reach * d.target.w;
        lo = d.target.xy - reach;
        size = float2(2.0f * reach);
    }
    float2 px = lo + marker_corners[vertex] * size;
    // The view's device px to its normalised coordinates, then the output's pre-rotation, as the labels' sprites.
    float2 n = {px.x * c.device.x - 1.0f, px.y * c.device.y - 1.0f};
    MarkerOut o;
    o.position = float4(c.turn.x * n.x + c.turn.y * n.y, c.turn.z * n.x + c.turn.w * n.y, 0.0f, 1.0f);
    o.draw = instance;
    return o;
}

struct MarkerColour {
#if OVERLAY_LOC1
    [[vk::location(1)]] float4 colour : SV_Target1;
#else
    [[vk::location(0)]] float4 colour : SV_Target0;
#endif
};

/// The sprite's texel under the view's point `view`, premultiplied 0 to 1: clear beyond it, and past the buffer.
float4 marker_texel(MarkerDraw d, float2 view) {
    int2 texel = int2(floor(view - d.target.xy));
    if (texel.x < 0 || texel.y < 0 || texel.x >= int(d.source.y) || texel.y >= int(d.source.z)) {
        return float4(0.0f);
    }
    uint n;
    uint stride;
    marker_texels.GetDimensions(n, stride);
    uint i = d.source.x + uint(texel.y) * d.source.y + uint(texel.x);
    uint t = i < n ? marker_texels[i] : 0u;
    return float4(float(t & 0xFFu), float((t >> 8u) & 0xFFu), float((t >> 16u) & 0xFFu), float(t >> 24u)) / 255.0f;
}

/// A cluster's bubble at the view's point `view`: its shadow, the white disc over it, the disc of its colour inside.
float4 marker_bubble(MarkerDraw d, float2 view) {
    float2 p = view - d.target.xy;
    float r = d.target.z;
    float dp = d.target.w;
    float a = marker_shadow_alpha * coverage_blurred_disc(length(p - float2(0.0f, 4.0f * dp)), r, 5.0f * dp);
    float4 o = float4(marker_ink * a, a);
    float white = coverage_edge(r - length(p));
    o = premultiplied_over(float4(white, white, white, white), o);
    uint c = d.source.x;
    float3 colour = float3(float((c >> 16u) & 0xFFu), float((c >> 8u) & 0xFFu), float(c & 0xFFu)) / 255.0f;
    float inner = coverage_edge(r - 2.0f * dp - length(p));
    return premultiplied_over(float4(colour * inner, inner), o);
}

[shader("fragment")]
MarkerColour marker_fs(MarkerOut v) {
    MarkerDraw d = marker_draws[v.draw];
    // the view's point under this fragment (a pixel's centre), in its device px
    float2 view = css_of_output(frame.camera, v.position.xy) * frame.camera.frame.z;
    MarkerColour o;
    o.colour = d.source.w == MARKER_BUBBLE ? marker_bubble(d, view) : marker_texel(d, view);
    return o;
}
