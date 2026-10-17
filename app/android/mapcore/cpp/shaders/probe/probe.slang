// The shader pipeline's probe (plan-vector T6, package K0): an ordinary module with a compute entry and a vertex and
// fragment pair, through slangc, validation, reflection and embedding. tests/base/vulkan_vk_test.cpp runs the compute
// entry on lavapipe and makes every entry's shader module under the validation layer.

struct ProbePush {
    float scale;
    float bias;
    uint count;
    float4 tint;
};

[[vk::push_constant]] ConstantBuffer<ProbePush> push;
[[vk::binding(0, 0)]] RWStructuredBuffer<float> values;

// values[i] · scale + bias for the first count values.
[shader("compute")]
[numthreads(64, 1, 1)]
void scale_bias(uint3 id : SV_DispatchThreadID) {
    uint i = id.x;
    if (i < push.count) {
        values[i] = values[i] * push.scale + push.bias;
    }
}

struct VertexOut {
    float4 position : SV_Position;
    float2 uv : TEXCOORD0;
};

// One triangle that covers the screen, from the vertex index alone (Vulkan's own, SV_VulkanVertexID: the build refuses
// SV_VertexID, whose SPIR-V depends on the build host; cmake/Shaders.cmake says why).
[shader("vertex")]
VertexOut fullscreen(uint vertex : SV_VulkanVertexID) {
    float2 uv = float2(float((vertex << 1) & 2u), float(vertex & 2u));
    VertexOut o;
    o.position = float4(uv * 2.0f - 1.0f, 0.0f, 1.0f);
    o.uv = uv;
    return o;
}

[shader("fragment")]
float4 tinted(VertexOut v) : SV_Target0 {
    return push.tint * float4(v.uv, 1.0f, 1.0f);
}
