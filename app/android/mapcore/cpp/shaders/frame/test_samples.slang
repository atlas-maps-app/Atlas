// The composite's pass-through probe (CP/tests/frame/composite_vk_test.cpp, K7): a pass that writes known colours into
// known samples, so the twin predicts every sample the composite reads. Layer 0 covers every sample of every pixel;
// each later layer covers the samples a hash of its pixel and layer picks (SV_Coverage), each layer's colour a hash of
// its pixel and layer in the output's levels. The C++ test computes the same hash (test_hash), so it knows each
// sample's last colour, and the composite's output must be the twin's composite_pixel of them, bit for bit.

/// The probe's push constants: the frame's 128-byte range.
struct SamplesPush {
    uint4 layer;   // x: the layer (0 covers every sample); y: the output's levels (255, 1023); z: samples per pixel
    float4 pad[7];
};

[[vk::push_constant]] ConstantBuffer<SamplesPush> probe;

struct SamplesVertex {
    float4 position : SV_Position;
};

[shader("vertex")]
SamplesVertex samples_vs(uint vertex : SV_VulkanVertexID) {
    float2 uv = float2(float((vertex << 1) & 2u), float(vertex & 2u));
    SamplesVertex o;
    o.position = float4(uv * 2.0f - 1.0f, 0.5f, 1.0f);
    return o;
}

/// A 32-bit integer hash (the C++ test's test_hash).
uint test_hash(uint x) {
    x = x ^ (x >> 16u);
    x = x * 0x7feb352du;
    x = x ^ (x >> 15u);
    x = x * 0x846ca68bu;
    x = x ^ (x >> 16u);
    return x;
}

struct SamplesOut {
    [[vk::location(0)]] float4 colour : SV_Target0;
    uint coverage : SV_Coverage;
};

[shader("fragment")]
SamplesOut samples_fs(SamplesVertex v) {
    uint x = uint(v.position.x);
    uint y = uint(v.position.y);
    uint h = test_hash(x * 73856093u ^ y * 19349663u ^ (probe.layer.x + 1u) * 83492791u);
    uint levels = probe.layer.y;
    float n = float(levels);
    float3 c = {float(h % (levels + 1u)), float((h / 7u) % (levels + 1u)), float((h / 49u) % (levels + 1u))};
    SamplesOut o;
    o.colour = float4(c / n, 1.0f);
    uint full = (1u << probe.layer.z) - 1u;
    o.coverage = probe.layer.x == 0u ? full : (test_hash(h) & full);
    return o;
}
