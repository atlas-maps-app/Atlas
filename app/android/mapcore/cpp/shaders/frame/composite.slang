// The frame's composite (plan-vector D5.4, CP/passes/Composite.cpp): one full-screen triangle at pixel rate that reads
// every sample of its pixel from tile memory (subpassLoad with a sample index: Vulkan 1.0 core), makes the pixel's
// colour from them and applies the screen steps once (shared/composite.slang, the twin's text), then writes the finished
// colour to every sample, which the render pass resolves unchanged into the output, or (COMPOSITE_X1, the direct
// target) to the 1x output.
//
// Variants, each a module of its own (the core's modules hold no specialization constants, cmake/Shaders.cmake): this
// file reads 4x samples, composite_x2.slang 2x (SAMPLES), composite_x1.slang one sample, composite_x1_loc1.slang one
// sample written to colour attachment 1 (the dynamic route at 1x). Each has Slang's GLSL route as well, for a device
// that may not take Slang's own SPIR-V for subpass loads (it declares StorageImageReadWithoutFormat, which Vulkan allows
// from 1.3, with VK_KHR_format_feature_flags2, or with shaderStorageImageReadWithoutFormat).
//
// It reads its samples' colour only, never their alpha (the frame may be rgb10a2, with 2 bits of alpha; gpu/Facts.hpp).
// The objects bit (the stencil, set 2 binding 1) is the composite's input for `mat`'s coverage rule, which K17a adds.
//
// atlas: glsl-route composite_fs:fragment

#include "shared/frame.slang"

#ifndef COMPOSITE_X1
#define COMPOSITE_X1 0
#endif

// The samples of a pixel the composite reads (4 or 2; the 1x variants read one).
#ifndef SAMPLES
#define SAMPLES 4
#endif

[[vk::binding(0, 0)]] ConstantBuffer<FrameBlock> frame;
#if COMPOSITE_X1
[[vk::input_attachment_index(0)]] [[vk::binding(0, 2)]] SubpassInput<float4> scene_colour;
#else
[[vk::input_attachment_index(0)]] [[vk::binding(0, 2)]] SubpassInputMS<float4> scene_colour;
#endif

struct FullOut {
    float4 position : SV_Position;
};

/// The full-screen triangle: three vertices covering the clip square.
[shader("vertex")]
FullOut fullscreen_vs(uint vertex : SV_VulkanVertexID) {
    float2 uv = float2(float((vertex << 1) & 2u), float(vertex & 2u));
    FullOut o;
    o.position = float4(uv * 2.0f - 1.0f, 0.0f, 1.0f);
    return o;
}

// The finished colour: colour attachment 0, or 1 in the dynamic route at one sample (beside the scene's colour).
// Slang 2026.19 numbers a lone output Location 0 whatever its SV_Target index, so the location is explicit.
struct CompositeOut {
#if COMPOSITE_X1 == 2
    [[vk::location(1)]] float4 colour : SV_Target1;
#else
    [[vk::location(0)]] float4 colour : SV_Target0;
#endif
};

[shader("fragment")]
CompositeOut composite_fs(FullOut v) {
    float n = float(frame.layers.steps.y);
#if COMPOSITE_X1
    float3 c = scene_colour.SubpassLoad().rgb;
    float3 sum = {composite_level(c.x, n), composite_level(c.y, n), composite_level(c.z, n)};
    uint count = 1u;
#else
    float3 sum = {0.0f, 0.0f, 0.0f};
    for (int s = 0; s < SAMPLES; ++s) {
        float3 c = scene_colour.SubpassLoad(s).rgb;
        float3 l = {composite_level(c.x, n), composite_level(c.y, n), composite_level(c.z, n)};
        sum = sum + l;
    }
    uint count = uint(SAMPLES);
#endif
    float3 mean = composite_mean(sum, count);
    float2 css = css_of_output(frame.camera, v.position.xy);
    float3 finished = composite_steps(frame.layers, mean, css, frame.camera.frame.xy);
    CompositeOut o;
    o.colour = composite_output(finished, n);
    return o;
}
