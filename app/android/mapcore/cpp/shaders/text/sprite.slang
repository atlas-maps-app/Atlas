// The board's labels (plan-vector D13, K13; passes/Labels.cpp): each a sprite the app rasterised once with the device's
// own Skia exactly as LabelPainter draws it (text and halos, premultiplied, at the screen's density; mapview's
// LabelSprites), drawn by the core at its placed position in the composite subpass after the composite, so it never
// trails the map and is not hazed. A sprite stands on whole device pixels of the view and is copied texel for texel (no
// filtering), so its pixels are LabelPainter's; its fade scales it. The output's pre-rotation turns the quad, and the
// fragment stage finds its texel in the view's own pixels (css_of_output).
//
// Variants: this module writes colour attachment 0; sprite_loc1.slang writes attachment 1, where the composite writes
// the dynamic route's 1x output beside the scene's colour (shaders/frame/composite_x1_loc1.slang).
//
// The texels are set 1's (frame/FrameLayout.hpp's sprites, which passes/GroundText.hpp's TextGlobals fills from the
// frame's LabelSet): every sprite's rows one after another, premultiplied RGBA8, R in the low byte. The frame slot's
// sprites to draw are the pass's own set (2).

#include "shared/frame.slang"

#ifndef LABELS_LOC1
#define LABELS_LOC1 0
#endif

/// One sprite to draw: where it stands in the view and where its texels are.
struct SpriteDraw {
    int4 target;  // its top-left pixel (x, y) in the view's device px, and its size (z, w)
    uint4 source; // x: its first texel; y: its row's texels (its width)
    float4 fade;  // x: its opacity, 0 to 1 (the 250 ms fades)
};

[[vk::binding(0, 0)]] ConstantBuffer<FrameBlock> frame;
[[vk::binding(13, 1)]] StructuredBuffer<uint> sprite_texels;
[[vk::binding(0, 2)]] StructuredBuffer<SpriteDraw> sprite_draws;

struct SpriteOut {
    float4 position : SV_Position;
    nointerpolation uint sprite : TEXCOORD0;
};

static const float2 sprite_corners[6] = { float2(0, 0), float2(1, 0), float2(0, 1), float2(0, 1), float2(1, 0), float2(1, 1) };

[shader("vertex")]
SpriteOut sprite_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    SpriteDraw s = sprite_draws[instance];
    FrameCamera c = frame.camera;
    // The view's device px to its normalised coordinates, then the output's pre-rotation (clip_of without the density:
    // the corners are whole device pixels already).
    float2 px = float2(s.target.xy) + sprite_corners[vertex] * float2(s.target.zw);
    float2 n = {px.x * c.device.x - 1.0f, px.y * c.device.y - 1.0f};
    SpriteOut o;
    o.position = float4(c.turn.x * n.x + c.turn.y * n.y, c.turn.z * n.x + c.turn.w * n.y, 0.0f, 1.0f);
    o.sprite = instance;
    return o;
}

struct SpriteColour {
#if LABELS_LOC1
    [[vk::location(1)]] float4 colour : SV_Target1;
#else
    [[vk::location(0)]] float4 colour : SV_Target0;
#endif
};

[shader("fragment")]
SpriteColour sprite_fs(SpriteOut v) {
    SpriteDraw s = sprite_draws[v.sprite];
    // The pixel of the view under this fragment: its centre's whole part, from the sprite's corner.
    float2 view = css_of_output(frame.camera, v.position.xy) * frame.camera.frame.z;
    int2 texel = clamp(int2(floor(view)) - s.target.xy, int2(0, 0), s.target.zw - int2(1, 1));
    // Nothing past the buffer (set 1's placeholder until TextGlobals gives the frame's sprites).
    uint n;
    uint stride;
    sprite_texels.GetDimensions(n, stride);
    uint i = s.source.x + uint(texel.y) * s.source.y + uint(texel.x);
    uint t = i < n ? sprite_texels[i] : 0u;
    float4 c = float4(float(t & 0xFFu), float((t >> 8u) & 0xFFu), float((t >> 16u) & 0xFFu), float(t >> 24u)) / 255.0f;
    SpriteColour o;
    o.colour = c * s.fade.x;
    return o;
}
