// The input attachment probe (package K0, for the composite of plan-vector D5.4): a fragment entry that reads the
// scene's samples from a multisampled input attachment and a second, single-sampled one, beside push constants and a
// buffer. Slang reflects an input attachment with two bindings (its descriptor slot and its index in the subpass);
// tests/base/shaders_test.cpp checks that gen/layouts.hpp gives both, and tests/base/vulkan_vk_test.cpp makes its
// shader module under the validation layer.

struct CompositePush {
    float haze;
    uint samples;
};

[[vk::push_constant]] ConstantBuffer<CompositePush> push;
[[vk::input_attachment_index(0)]] [[vk::binding(0, 0)]] SubpassInputMS<float4> scene;
[[vk::input_attachment_index(1)]] [[vk::binding(1, 0)]] SubpassInput<float4> marks;
[[vk::binding(2, 0)]] StructuredBuffer<float> weights;

// The mean of the pixel's samples, weighted, with the marks added.
[shader("fragment")]
float4 resolve(float4 position : SV_Position) : SV_Target0 {
    float4 sum = float4(0.0f);
    for (uint s = 0; s < push.samples; ++s) {
        sum += scene.SubpassLoad(int(s)) * weights[s];
    }
    return sum * push.haze + marks.SubpassLoad();
}
