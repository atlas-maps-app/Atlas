// The stand-in's box blur (plan-vector K1, D5.5): one axis per dispatch, as WebKit's three box blurs per axis
// approximate a Gaussian. The shadow mask's soft edges and the frosted glass both go through it.

struct BlurPush {
    int2 size;   // the destination's pixels
    int2 step;   // (1, 0) along x, (0, 1) along y
    int radius;
    int pad0;
    int pad1;
    int pad2;
};

[[vk::push_constant]] ConstantBuffer<BlurPush> blur;
[[vk::binding(0, 0)]] Texture2D<float4> blur_source;
[[vk::binding(1, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> blur_target;

[shader("compute")]
[numthreads(8, 8, 1)]
void blur_cs(uint3 id : SV_DispatchThreadID) {
    int2 p = int2(id.xy);
    if (p.x >= blur.size.x || p.y >= blur.size.y) return;
    float4 sum = 0.0f;
    for (int k = -blur.radius; k <= blur.radius; ++k) {
        int2 q = clamp(p + blur.step * k, int2(0, 0), blur.size - 1);
        sum += blur_source.Load(int3(q, 0));
    }
    blur_target[p] = sum / float(2 * blur.radius + 1);
}
