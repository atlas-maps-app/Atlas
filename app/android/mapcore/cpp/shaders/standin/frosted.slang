// The stand-in's frosted backdrops (plan-vector K1, D5.1): on the SurfaceView host the core draws the chrome's frosted
// glass itself, from a blurred, saturated copy of what lies under each pill, inside the pill's rounded rectangle;
// Compose draws only the tint and the content on top.

struct FrostPush {
    float4 box;     // the pill in output pixels: left, top, right, bottom
    float4 source;  // the pill's box in the blurred copy's uv
    float4 screen;  // x, y the output's pixels; z, w their reciprocals
    float4 shape;   // x the corner radius in pixels, y the saturation
};

[[vk::push_constant]] ConstantBuffer<FrostPush> pill;
[[vk::binding(0, 0)]] Sampler2D frost_source;

struct FrostOut {
    float4 position : SV_Position;
    float2 local : TEXCOORD0;
    float2 uv : TEXCOORD1;
};

static const float2 frost_corners[6] = { float2(0, 0), float2(1, 0), float2(0, 1), float2(0, 1), float2(1, 0), float2(1, 1) };

[shader("vertex")]
FrostOut frosted_vs(uint vertex : SV_VulkanVertexID) {
    float2 c = frost_corners[vertex];
    FrostOut o;
    o.local = lerp(pill.box.xy, pill.box.zw, c);
    o.uv = lerp(pill.source.xy, pill.source.zw, c);
    o.position = float4(o.local * pill.screen.zw * 2.0f - 1.0f, 0.0f, 1.0f);
    return o;
}

[shader("fragment")]
float4 frosted_fs(FrostOut v) : SV_Target0 {
    // The rounded rectangle's coverage, from its signed distance in pixels.
    float2 half = (pill.box.zw - pill.box.xy) * 0.5f;
    float2 q = abs(v.local - (pill.box.xy + half)) - (half - pill.shape.x);
    float d = length(max(q, 0.0f)) + min(max(q.x, q.y), 0.0f) - pill.shape.x;
    float coverage = saturate(0.5f - d);
    float3 c = frost_source.SampleLevel(v.uv, 0.0f).rgb;
    float grey = dot(c, float3(0.2126f, 0.7152f, 0.0722f));
    c = lerp(float3(grey, grey, grey), c, pill.shape.y);
    return float4(c * coverage, coverage);
}
