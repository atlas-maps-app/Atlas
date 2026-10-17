// The layout probe (plan-vector T6, package K0): push constants and a buffer element with every layout case the
// generated C++ must reproduce byte for byte: a signed vector, a float3 followed by a scalar in its padding, a nested
// struct rounded up to its alignment, an array, and a buffer element with tail padding. tests/base/shaders_test.cpp
// checks gen/layouts.hpp's offsets; tests/base/vulkan_vk_test.cpp fills the structs in C++, runs echo_layout on
// lavapipe and reads back what the GPU saw.

struct Inner {
    float2 offset;
    uint id;
};

struct LayoutPush {
    float scale;
    int2 cell;
    float3 tint;
    uint flags;
    Inner inner;
    float weights[4];
};

struct Record {
    float3 position;
    uint kind;
    float2 uv;
};

[[vk::push_constant]] ConstantBuffer<LayoutPush> push;
[[vk::binding(0, 0)]] RWStructuredBuffer<Record> records;
[[vk::binding(1, 0)]] RWStructuredBuffer<float> echo;

// Writes every push constant as the GPU read it, reads records[0] the same way, and writes records[1] from the push
// constants, so both directions of both layouts are checked.
[shader("compute")]
[numthreads(1, 1, 1)]
void echo_layout(uint3 id : SV_DispatchThreadID) {
    echo[0] = push.scale;
    echo[1] = float(push.cell.x);
    echo[2] = float(push.cell.y);
    echo[3] = push.tint.x;
    echo[4] = push.tint.y;
    echo[5] = push.tint.z;
    echo[6] = float(push.flags);
    echo[7] = push.inner.offset.x;
    echo[8] = push.inner.offset.y;
    echo[9] = float(push.inner.id);
    for (int i = 0; i < 4; ++i) {
        echo[10 + i] = push.weights[i];
    }
    Record r = records[0];
    echo[14] = r.position.x;
    echo[15] = r.position.y;
    echo[16] = r.position.z;
    echo[17] = float(r.kind);
    echo[18] = r.uv.x;
    echo[19] = r.uv.y;
    r.position = push.tint;
    r.kind = push.flags;
    r.uv = push.inner.offset;
    records[1] = r;
}
