// The light probe (plan-vector K5): light.slang's exact light() and tohex in binary64 as a compute entry, so the
// parity tests run the GPU's own compilation of the shared module on a Vulkan device with shaderFloat64 (lavapipe on
// CI and this Mac; CP/style/HostProbe.cpp). Mode 0 lights surfaces; modes 1 and 2 return the exact operations light()
// is built from on given arguments (exp, the divisions by 255 and 50, fdlibm exp's division), so each is held to its
// C++ bits on its own. The build embeds it as atlas::spirv::style_light_probe (gen/spirv/style/light_probe.hpp).
//
// atlas: exact
#include "shared/theme.slang"
#include "shared/light.slang"

/// One input: a surface's colour (0-255), its unit normal, its sun bit and which theme lights it. Modes 1 and 2 read
/// their arguments from base and normal.
struct LightInput {
    double base[3];
    double normal[3];
    uint lit;
    uint theme;
};

/// How many inputs there are, and what to do with them.
struct LightProbeParams {
    uint count;
    uint mode; // 0: light; 1: exp(base[0]), base[1] / 255, base[2] / 50; 2: normal[0] / normal[1] (1.5-2.5)
};

[[vk::binding(0, 0)]] StructuredBuffer<LightInput> inputs;
[[vk::binding(1, 0)]] StructuredBuffer<Theme> themes;
[[vk::binding(2, 0)]] RWStructuredBuffer<uint> bytes;    // r | g << 8 | b << 16: tohex of the light
[[vk::binding(3, 0)]] RWStructuredBuffer<double> values; // three per input: the light before rounding, or the results
[[vk::push_constant]] ConstantBuffer<LightProbeParams> probe;

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 id: SV_DispatchThreadID) {
    if (id.x >= probe.count) {
        return;
    }
    LightInput p = inputs[id.x];
    uint at = 3u * id.x;
    if (probe.mode == 0u) {
        double3 base = {p.base[0], p.base[1], p.base[2]};
        double3 n = {p.normal[0], p.normal[1], p.normal[2]};
        double3 v = light(themes[p.theme], base, n, p.lit != 0u);
        uint3 c = tohex3(v);
        bytes[id.x] = c.x | (c.y << 8u) | (c.z << 16u);
        values[at] = v.x;
        values[at + 1u] = v.y;
        values[at + 2u] = v.z;
    } else if (probe.mode == 1u) {
        bytes[id.x] = 0u;
        values[at] = light_exp(p.base[0]);
        values[at + 1u] = light_div255(p.base[1]);
        values[at + 2u] = light_div50(p.base[2]);
    } else {
        bytes[id.x] = 0u;
        values[at] = light_quotient_near2(p.normal[0], p.normal[1]);
        values[at + 1u] = ATLAS_D(0.0);
        values[at + 2u] = ATLAS_D(0.0);
    }
}
