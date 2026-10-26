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
// The haze's and the vignette's opacities come from the frame's tables (set 2 binding 2, ENGINE.md gain 2): the haze's
// by upright row, the vignette's squared distances by upright column and row, made on the CPU once a frame with
// shared/composite.slang's own arithmetic (passes/Composite.cpp), so each pixel takes the opacities that arithmetic
// gives it, with a read where it took a loop over the haze's stops and two divisions.
//
// It reads its samples' colour only, never their alpha (the frame may be rgb10a2, with 2 bits of alpha; gpu/Facts.hpp).
// The multisampled variants read each sample's stencil too (set 2 binding 1): its objects bit tells the objects
// group's samples from the others for `mat`'s coverage rule at the group's silhouettes (K17a). The grain reads the
// grain field's grey and alpha at the pixel from set 1 (shaders/fx/mat.slang's fx_rgba).
//
// atlas: glsl-route composite_fs:fragment

#include "shared/frame.slang"
#include "shared/painter.slang"
#define ATLAS_FX_FUNCTIONS
#include "fx/mat.slang"

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
// A four-lane read (Slang 2026.19 emits a one-lane OpImageRead for SubpassInputMS<uint>, which SPIR-V refuses): the
// stencil in x.
[[vk::input_attachment_index(1)]] [[vk::binding(1, 2)]] SubpassInputMS<uint4> scene_stencil;
#endif
// The frame's tables: S rows' haze opacities (1/4096), then S columns' and S rows' squared vignette distances
// (COMPOSITE_BEYOND past the radius), S the output's longer side.
[[vk::binding(2, 2)]] StructuredBuffer<uint> composite_tables;

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
    uint levels = frame.layers.steps.y;
    float n = float(levels);
#if COMPOSITE_X1
    float3 c = scene_colour.SubpassLoad().rgb;
    float3 sum = {composite_level(c.x, n), composite_level(c.y, n), composite_level(c.z, n)};
    float3 mean = composite_mean(sum, 1u);
#else
    // The samples' levels, the objects group's (the stencil's objects bit) and the others' apart.
    float3 objects = {0.0f, 0.0f, 0.0f};
    float3 others = {0.0f, 0.0f, 0.0f};
    uint k = 0u;
    for (int s = 0; s < SAMPLES; ++s) {
        float3 c = scene_colour.SubpassLoad(s).rgb;
        float3 l = {composite_level(c.x, n), composite_level(c.y, n), composite_level(c.z, n)};
        if ((scene_stencil.SubpassLoad(s).x & OBJECTS_BIT) != 0u) {
            objects = objects + l;
            k = k + 1u;
        } else {
            others = others + l;
        }
    }
    uint count = uint(SAMPLES);
    float3 mean;
    if ((frame.layers.steps.x & COMPOSITE_MAT) != 0u && k > 0u && k < count && mat_on(frame)) {
        // A silhouette of the objects group: mat's coverage rule (shared/composite.slang).
        uint3 o = composite_mean_of(objects, k);
        uint3 e = {mat_edge_level(o.x, levels), mat_edge_level(o.y, levels), mat_edge_level(o.z, levels)};
        mean = composite_silhouette(e, composite_mean_of(others, count - k), k, count);
    } else {
        mean = composite_mean(objects + others, count);
    }
#endif
    // fin's grain: the field's grey and alpha here, at the moment's opacity, once the fields are bound.
    uint3 grain = {0u, 0u, 0u};
    if ((frame.layers.steps.x & COMPOSITE_GRAIN) != 0u && (frame.info.x & FRAME_FIELDS) != 0u) {
        uint4 b = fx_rgba(fx_pixel(frame.camera, v.position.xy));
        uint opacity = (frame.info.x & FRAME_NIGHT) != 0u ? COMPOSITE_GRAIN_NIGHT : COMPOSITE_GRAIN_DAY;
        uint3 g = {b.y, b.z, opacity};
        grain = g;
    }
    // The upright device pixel (the output's pre-rotation undone), where the haze and the vignette are taken.
    int2 at = fx_pixel(frame.camera, v.position.xy);
    uint2 pixel = {uint(at.x < 0 ? 0 : at.x), uint(at.y < 0 ? 0 : at.y)};
    uint entries = 0u;
    uint stride = 0u;
    composite_tables.GetDimensions(entries, stride);
    uint side = entries / 3u;
    uint row = pixel.y < side ? pixel.y : side - 1u;
    uint column = pixel.x < side ? pixel.x : side - 1u;
    int haze = int(composite_tables[row]);
    int vignette = composite_vignette_from_squares(frame.layers, composite_tables[side + column],
                                                   composite_tables[(2u * side) + row]);
    // the wash (the golden moment's alone) by the pixel's own arithmetic: two divisions where it runs
    int wash = (frame.layers.steps.x & COMPOSITE_WASH) != 0u ? composite_wash_t(pixel, frame.camera.frame.xyz) : 0;
    float3 finished = composite_steps_alphas(frame.layers, mean, haze, wash, vignette, grain);
    CompositeOut o;
    o.colour = composite_output(finished, n);
    return o;
}
