// The frosted glass (passes/Frosted.hpp, plan-vector D5.1): one frosted shape of the chrome drawn back over the frame
// from the blurred copy of what lies under it, saturated as CSS's saturate() is and inside the shape's rounded
// rectangle; Compose draws the tint and the content over it. One draw a shape, its quad the shape's box grown by a
// pixel for the edge's coverage.

struct GlassPush {
    float4 box;    // the shape in the output's pixels: left, top, right, bottom
    float4 copy;   // an output pixel p in the copy's uv: p * copy.xy + copy.zw
    float4 bounds; // the centres of the region's corner texels in the copy's uv: its reads stay inside the region
    float4 screen; // x, y: the output's size in pixels; z, w: their reciprocals
    float4 shape;  // x: the corner radius in pixels; y: the saturation (1: none); z: the chrome's alpha over it
};

[[vk::push_constant]] ConstantBuffer<GlassPush> glass;
[[vk::binding(0, 0)]] Sampler2D frost;

struct GlassOut {
    float4 position : SV_Position;
};

static const float2 glass_corners[6] = { float2(0, 0), float2(1, 0), float2(0, 1), float2(0, 1), float2(1, 0), float2(1, 1) };

[shader("vertex")]
GlassOut glass_vs(uint vertex : SV_VulkanVertexID) {
    float2 c = glass_corners[vertex];
    float2 p = lerp(glass.box.xy - 1.0f, glass.box.zw + 1.0f, c);
    GlassOut o;
    o.position = float4(p * glass.screen.zw * 2.0f - 1.0f, 0.0f, 1.0f);
    return o;
}

[shader("fragment")]
float4 glass_fs(GlassOut v) : SV_Target0 {
    float2 p = v.position.xy;
    // The rounded rectangle's coverage of the pixel, from its signed distance.
    float2 half_size = (glass.box.zw - glass.box.xy) * 0.5f;
    float radius = clamp(glass.shape.x, 0.0f, min(half_size.x, half_size.y));
    float2 q = abs(p - (glass.box.xy + half_size)) - (half_size - radius);
    float d = length(max(q, 0.0f)) + min(max(q.x, q.y), 0.0f) - radius;
    float coverage = saturate(0.5f - d);
    // The blurred copy, premultiplied (opaque: past the frame's edge the frame's edge goes on), read within the region.
    float2 uv = clamp(p * glass.copy.xy + glass.copy.zw, glass.bounds.xy, glass.bounds.zw);
    float4 g = frost.SampleLevel(uv, 0.0f);
    // CSS saturate(s): Filter Effects' feColorMatrix "saturate" on the unpremultiplied colour, then clamped.
    float3 c = g.a > 0.0f ? g.rgb / g.a : float3(0.0f, 0.0f, 0.0f);
    float grey = 0.213f * c.r + 0.715f * c.g + 0.072f * c.b;
    c = saturate(grey + (c - grey) * glass.shape.y);
    // The chrome's alpha (a screen fading out takes its glass with it, whether or not the surface follows the views).
    return float4(c * g.a, g.a) * (coverage * glass.shape.z);
}
