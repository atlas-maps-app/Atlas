// The board's labels (plan-vector D13, K13): each a sprite the app rasterised once with the device's own Skia exactly
// as LabelPainter draws it (text and halo, premultiplied, at the screen's density; mapview's LabelSprites), drawn by the
// core at its placed position after the composite, so it never trails the map and is not hazed. A sprite stands on
// whole pixels and is copied texel for texel (no filtering), so its pixels are LabelPainter's; its fade scales it.

/// One sprite to draw: where it stands on the target and where its texels are in the sprite atlas.
struct SpriteDraw {
    int4 target;   // its top-left pixel (x, y) and its size (z, w)
    int4 source;   // its top-left texel in the atlas (x, y)
    float4 fade;   // x: its opacity, 0 to 1 (the 250 ms fades)
};

struct SpriteFrame {
    float4 screen; // the target's pixels (x, y) and their reciprocals (z, w)
};

[[vk::binding(0, 0)]] StructuredBuffer<SpriteDraw> sprites;
[[vk::binding(1, 0)]] [[vk::image_format("rgba8")]] Texture2D<float4> sprite_atlas; // premultiplied
[[vk::push_constant]] ConstantBuffer<SpriteFrame> sprite_frame;

struct SpriteOut {
    float4 position : SV_Position;
    nointerpolation uint sprite : TEXCOORD0;
};

static const float2 sprite_corners[6] = { float2(0, 0), float2(1, 0), float2(0, 1), float2(0, 1), float2(1, 0), float2(1, 1) };

[shader("vertex")]
SpriteOut sprite_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    SpriteDraw s = sprites[instance];
    float2 px = float2(s.target.xy) + sprite_corners[vertex] * float2(s.target.zw);
    SpriteOut o;
    o.position = float4(px * sprite_frame.screen.zw * 2.0f - 1.0f, 0.0f, 1.0f);
    o.sprite = instance;
    return o;
}

[shader("fragment")]
float4 sprite_fs(SpriteOut v) : SV_Target0 {
    SpriteDraw s = sprites[v.sprite];
    // The pixel's own texel: its centre's whole part, from the sprite's corner.
    int2 pixel = int2(floor(v.position.xy)) - s.target.xy + s.source.xy;
    return sprite_atlas.Load(int3(pixel, 0)) * s.fade.x;
}
