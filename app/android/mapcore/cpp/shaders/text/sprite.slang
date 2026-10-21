// The board's labels (plan-vector D13, K13; passes/Labels.cpp): each a sprite the app rasterised once with the device's
// own Skia exactly as LabelPainter draws it (text and halos, premultiplied, at the screen's density; mapview's
// LabelSprites), drawn by the core at its placed position in the composite subpass after the composite, so it never
// trails the map and is not hazed. At its own size a sprite stands on whole device pixels of the view and is copied
// texel for texel (no filtering), so its pixels are LabelPainter's; grown or shrunk with the map (labels/Placement's
// scale) it is sampled bilinearly, premultiplied, the texels beyond it clear, over its rectangle and a pixel around it.
// Its fade scales it. The output's pre-rotation turns the quad, and the fragment stage finds its texel in the view's
// own pixels (css_of_output).
//
// Variants: this module writes colour attachment 0; sprite_loc1.slang writes attachment 1, where the composite writes
// the dynamic route's 1x output beside the scene's colour (shaders/frame/composite_x1_loc1.slang).
//
// The texels are set 1's (frame/FrameLayout.hpp's sprites, which passes/Labels.hpp's SpriteGlobals fills from the
// frame's LabelSet): every sprite's rows one after another, premultiplied RGBA8, R in the low byte. The frame slot's
// sprites to draw are the pass's own set (2).

#include "shared/frame.slang"

#ifndef LABELS_LOC1
#define LABELS_LOC1 0
#endif

/// One sprite to draw: where it stands in the view and where its texels are.
struct SpriteDraw {
    float4 target; // its top-left corner (x, y) in the view's device px and its size there (z, w): whole px at scale 1
    uint4 source;  // x: its first texel; y: its row's texels (its width); z: its rows (its height)
    float4 fade;   // x: its opacity, 0 to 1 (the 250 ms fades); y: its scale (1: texel for texel)
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
    // the corners are device px already); a scaled sprite's quad a pixel larger every way, for its filtered edge.
    float fringe = s.fade.y != 1.0f ? 1.0f : 0.0f;
    float2 px = s.target.xy - fringe + sprite_corners[vertex] * (s.target.zw + 2.0f * fringe);
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

/// Texel (x, y) of a sprite, premultiplied 0 to 1: clear beyond it, and past the buffer (set 1's placeholder until
/// SpriteGlobals gives the frame's sprites).
float4 sprite_texel(SpriteDraw s, int2 texel, uint n) {
    if (texel.x < 0 || texel.y < 0 || texel.x >= int(s.source.y) || texel.y >= int(s.source.z)) {
        return float4(0.0f);
    }
    uint i = s.source.x + uint(texel.y) * s.source.y + uint(texel.x);
    uint t = i < n ? sprite_texels[i] : 0u;
    return float4(float(t & 0xFFu), float((t >> 8u) & 0xFFu), float((t >> 16u) & 0xFFu), float(t >> 24u)) / 255.0f;
}

[shader("fragment")]
SpriteColour sprite_fs(SpriteOut v) {
    SpriteDraw s = sprite_draws[v.sprite];
    // The view's point under this fragment (a pixel's centre), from the sprite's corner.
    float2 view = css_of_output(frame.camera, v.position.xy) * frame.camera.frame.z;
    uint n;
    uint stride;
    sprite_texels.GetDimensions(n, stride);
    float4 c;
    if (s.fade.y == 1.0f) {
        // at its own size: the texel under the pixel
        int2 texel = int2(floor(view - s.target.xy));
        c = sprite_texel(s, texel, n);
    } else {
        // scaled: the four texels about the pixel's centre in the sprite, weighed by where it falls between them
        float2 u = (view - s.target.xy) / s.fade.y - 0.5f;
        float2 base = floor(u);
        float2 f = u - base;
        int2 t = int2(base);
        float4 top = lerp(sprite_texel(s, t, n), sprite_texel(s, t + int2(1, 0), n), f.x);
        float4 bottom = lerp(sprite_texel(s, t + int2(0, 1), n), sprite_texel(s, t + int2(1, 1), n), f.x);
        c = lerp(top, bottom, f.y);
    }
    SpriteColour o;
    o.colour = c * s.fade.x;
    return o;
}
