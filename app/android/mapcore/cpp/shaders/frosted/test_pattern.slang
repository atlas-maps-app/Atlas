// The frosted backdrops' test picture (CP/tests/frosted/frosted_vk_test.cpp, K11b): a full-screen triangle in the scene
// subpass whose colour is a known function of its pixel (a ramp along x every 97 pixels, one along y every 71, a
// checker of 13), so that a frosted copy taken from the wrong place, or blurred by the wrong boxes, shows.

struct PatternVertex {
    float4 position : SV_Position;
};

[shader("vertex")]
PatternVertex pattern_vs(uint vertex : SV_VulkanVertexID) {
    float2 uv = float2(float((vertex << 1) & 2u), float(vertex & 2u));
    PatternVertex o;
    o.position = float4(uv * 2.0f - 1.0f, 0.5f, 1.0f);
    return o;
}

[shader("fragment")]
float4 pattern_fs(PatternVertex v) : SV_Target0 {
    uint x = uint(v.position.x);
    uint y = uint(v.position.y);
    float r = float(x % 97u) / 96.0f;
    float g = float(y % 71u) / 70.0f;
    float b = ((x / 13u + y / 13u) & 1u) != 0u ? 0.9f : 0.1f;
    return float4(r, g, b, 1.0f);
}
