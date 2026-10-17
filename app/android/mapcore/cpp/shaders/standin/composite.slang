// The stand-in's composite (plan-vector D5.4, K1): one full-screen triangle at pixel rate that reads every sample of
// its pixel and their stencil from tile memory, applies the coverage rule of `mat` at the objects group's silhouettes
// (the samples with the objects bit, stencil bit 7, are the group: alpha min(1, 1.1·A)·A over the rest, in linear
// light), then the haze from the frame's 17 stops and the vignette, once per pixel. It writes the finished colour to
// the samples, which resolve unchanged into the output, or (COMPOSITE_X1, or the direct target) to the 1x output.
//
// Variants, each a module of its own (the core's modules hold no specialization constants, cmake/Shaders.cmake): this
// file reads 4x samples, composite_x2.slang 2x (SAMPLES), composite_x1.slang one sample, composite_x1_loc1.slang one
// sample written to colour attachment 1 (the dynamic route at 1x). Each has Slang's GLSL route as well, used where the
// device may not take Slang's own SPIR-V for subpass loads (it declares StorageImageReadWithoutFormat, which Vulkan
// allows from 1.3, with VK_KHR_format_feature_flags2, or with shaderStorageImageReadWithoutFormat).
//
// atlas: glsl-route composite_fs:fragment

#ifndef COMPOSITE_X1
#define COMPOSITE_X1 0
#endif

// The samples of a pixel the composite reads (4 or 2; the 1x variants read one).
#ifndef SAMPLES
#define SAMPLES 4
#endif

struct Haze {
    float4 screen;      // x, y the output's pixels; z, w their reciprocals
    float4 vignette;    // x strength, y inner radius, z outer radius
    float4 haze_colour[17];
    float4 haze_stop[5];
};

[[vk::binding(0, 0)]] ConstantBuffer<Haze> haze;
#if COMPOSITE_X1
[[vk::input_attachment_index(0)]] [[vk::binding(0, 1)]] SubpassInput<float4> scene_colour;
[[vk::input_attachment_index(1)]] [[vk::binding(1, 1)]] SubpassInput<uint4> scene_stencil;
#else
[[vk::input_attachment_index(0)]] [[vk::binding(0, 1)]] SubpassInputMS<float4> scene_colour;
[[vk::input_attachment_index(1)]] [[vk::binding(1, 1)]] SubpassInputMS<uint4> scene_stencil;
#endif

struct FullOut {
    float4 position : SV_Position;
};

[shader("vertex")]
FullOut fullscreen_vs(uint vertex : SV_VulkanVertexID) {
    float2 uv = float2(float((vertex << 1) & 2u), float(vertex & 2u));
    FullOut o;
    o.position = float4(uv * 2.0f - 1.0f, 0.0f, 1.0f);
    return o;
}

float3 to_linear(float3 c) { return c * c; }
float3 to_encoded(float3 c) { return sqrt(max(c, 0.0f)); }

float3 screen_effects(float3 c, float2 pixel) {
    float y = pixel.y * haze.screen.w;
    uint lo = 0u;
    for (uint i = 0u; i < 16u; ++i) {
        float s = haze.haze_stop[(i + 1u) >> 2][(i + 1u) & 3u];
        if (y >= s) lo = i;
    }
    float s0 = haze.haze_stop[lo >> 2][lo & 3u];
    float s1 = haze.haze_stop[(lo + 1u) >> 2][(lo + 1u) & 3u];
    float t = saturate((y - s0) / max(s1 - s0, 1e-6f));
    float4 h = lerp(haze.haze_colour[lo], haze.haze_colour[lo + 1u], t);
    c = lerp(c, h.rgb, h.a);
    float2 d = pixel * haze.screen.zw - 0.5f;
    float r = length(d * float2(1.0f, haze.screen.y * haze.screen.z));
    return c * (1.0f - haze.vignette.x * smoothstep(haze.vignette.y, haze.vignette.z, r));
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
#if COMPOSITE_X1
    float3 c = scene_colour.SubpassLoad().rgb;
#else
    float3 objects = 0.0f;
    float3 others = 0.0f;
    uint covered = 0u;
    for (int s = 0; s < SAMPLES; ++s) {
        float3 sample = scene_colour.SubpassLoad(s).rgb;
        if ((scene_stencil.SubpassLoad(s).x & 0x80u) != 0u) {
            objects += sample;
            ++covered;
        } else {
            others += sample;
        }
    }
    float3 c;
    uint n = uint(SAMPLES);
    if (covered == 0u || covered == n) {
        c = (objects + others) / float(n);
    } else {
        // A silhouette: the objects group thinned as `mat` thins it, over what lies behind, in linear light.
        float share = float(covered) / float(n);
        float alpha = min(1.0f, 1.1f * share) * share;
        float3 group = to_linear(objects / float(covered));
        float3 behind = to_linear(others / float(n - covered));
        c = to_encoded(behind * (1.0f - alpha) + group * alpha);
    }
#endif
    CompositeOut o;
    o.colour = float4(screen_effects(c, v.position.xy), 1.0f);
    return o;
}
