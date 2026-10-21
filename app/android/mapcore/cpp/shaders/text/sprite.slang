// The board's labels (plan-vector D13, K13; passes/Labels.cpp): each a sprite the app rasterised with the device's own
// Skia exactly as LabelPainter draws it (text and halos, premultiplied, at the screen's density; mapview's
// LabelSprites), at its own size and at the band's top, drawn by the core at its placed position in the composite
// subpass after the composite, so it never trails the map and is not hazed. At its own size a sprite stands on whole
// device pixels of the view and is copied texel for texel (no filtering), so its pixels are LabelPainter's; grown or
// shrunk with the map (labels/Placement's scale) it is drawn from the image passes/Labels picked (grown, the label
// drawn at the band's top, so within the band an image is only ever shrunk), each pixel the area its square covers of
// the image's texels (a texel's width at least, which is the bilinear mix where an image is enlarged), premultiplied,
// the texels beyond it clear, over its rectangle and a fringe around it. Its fade scales it. The output's pre-rotation
// turns the quad, and the fragment stage finds its texel in the view's own pixels (css_of_output).
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
    float4 fade;   // x: its opacity, 0 to 1 (the 250 ms fades); y: its scale, view px a texel (1: texel for texel)
};

/// The widest a pixel's square is, in texels: a sprite shrunk below a third of its image is filtered as a third.
static const float sprite_widest = 3.0f;

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
    // the corners are device px already); a scaled sprite's quad larger every way by half a pixel's square, whole
    // pixels, for its filtered edge.
    float fringe = s.fade.y != 1.0f ? ceil(max(1.0f, s.fade.y) * 0.5f) : 0.0f;
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

/// A scaled sprite over one pixel: the pixel's square, `w` texels wide each way about the pixel's centre `u` in the
/// image's texels (texel i spans [i, i + 1)), each texel weighed by the area it shares with the square; at most four
/// texels a row and a column (w at most sprite_widest).
float4 sprite_area(SpriteDraw s, float2 u, float w, uint n) {
    float2 lo = u - 0.5f * w;
    float2 hi = u + 0.5f * w;
    int2 first = int2(floor(lo));
    float4 sum = float4(0.0f);
    for (int j = 0; j < 4; ++j) {
        int y = first.y + j;
        float wy = min(hi.y, float(y + 1)) - max(lo.y, float(y));
        if (wy <= 0.0f) {
            break;
        }
        for (int i = 0; i < 4; ++i) {
            int x = first.x + i;
            float wx = min(hi.x, float(x + 1)) - max(lo.x, float(x));
            if (wx <= 0.0f) {
                break;
            }
            sum += sprite_texel(s, int2(x, y), n) * (wx * wy);
        }
    }
    return sum / (w * w);
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
        // scaled: the area of the pixel's square over the texels (a texel's width at least: the bilinear mix)
        float k = s.fade.y;
        c = sprite_area(s, (view - s.target.xy) / k, clamp(1.0f / k, 1.0f, sprite_widest), n);
    }
    SpriteColour o;
    o.colour = c * s.fade.x;
    return o;
}
