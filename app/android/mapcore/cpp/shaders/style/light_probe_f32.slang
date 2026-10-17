// The binary32 light probe (plan-vector K5): light.slang's light_f32 and tohex_f32 as a compute entry, compiled as an
// ordinary module (Slang's default floating point, the driver free to fuse and to use its own division and exp), as a
// phone without shaderFloat64 would run them; the parity tests hold its values to the port's within light_f32_bound
// (CP/style/Light.hpp). The build embeds it as atlas::spirv::style_light_probe_f32.
#include "shared/theme.slang"
#include "shared/light.slang"

/// One input: a surface's colour, its normal, its sun bit and which theme lights it, as float vertex data.
struct LightInputF32 {
    float4 base;   // rgb: the surface's colour (0-255); a: 1 where its sun bit is set, else 0
    float4 normal; // xyz: the unit normal; w: the theme's index in `themes`
};

/// How many inputs there are.
struct LightProbeF32Params {
    uint count;
};

[[vk::binding(0, 0)]] StructuredBuffer<LightInputF32> inputs;
[[vk::binding(1, 0)]] StructuredBuffer<Theme> themes;
[[vk::binding(2, 0)]] RWStructuredBuffer<uint> bytes;    // r | g << 8 | b << 16: tohex_f32 of the light
[[vk::binding(3, 0)]] RWStructuredBuffer<float4> values; // rgb: the light before rounding
[[vk::push_constant]] ConstantBuffer<LightProbeF32Params> probe;

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 id: SV_DispatchThreadID) {
    if (id.x >= probe.count) {
        return;
    }
    LightInputF32 p = inputs[id.x];
    Theme th = themes[uint(p.normal.w)];
    float3 v = light_f32(th, p.base.xyz, p.normal.xyz, p.base.w != 0.0f);
    uint3 c = tohex3_f32(v);
    bytes[id.x] = c.x | (c.y << 8u) | (c.z << 16u);
    values[id.x] = float4(v, 0.0f);
}
