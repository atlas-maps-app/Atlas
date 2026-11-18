// The relief's light on the ground (ENGINE.md part 6, terrain, step 2; passes/Relief.cpp): where the terrain's heights
// are, the ground the frame has drawn is multiplied by the light its slope and its hollows take (shared/relief.slang),
// once a pixel, after the ground's shadows and before the quays, the night's pools and what stands up.
//
// One quad a lit tile (scene/Relief.hpp's cover of its own), instanced: its corners on the ground at street level,
// placed and bent as the ground's vertices are (camera.slang's bent), projected with the perspective division left to
// the rasteriser, so a quad that passes the eye's plane is clipped (z = depth · w). It draws half a slot above the
// ground's last painter slot (exact, as the slots' depths are, and half a slot clear of the rasteriser's rounding on
// either side) with GREATER OR EQUAL, and writes none: it passes over everything the ground's passes drew, at their
// painter depths, and fails on the items' slots after them and on what stands up at its true depth (painter.slang).
// Each fragment reads
// its tile's texel (the surface's normal and its sky-view, between the samples) from the array's layer, and its
// colour's factor c goes out as (c − a, a) with a = min(c, 1), blended DST_COLOR, SRC_ALPHA: the ground times c, a
// factor above 1 among them, on a target that clamps what a fragment writes.

#include "shared/frame.slang"
#include "shared/painter.slang"
#include "shared/relief.slang"

/// One lit tile.
struct ReliefQuad {
    float4 corner; // xyz: its north-west corner at street level minus the eye, frame metres; w: its strength (0 to 1:
                   // a tile's relief fades in where none was drawn)
    float4 reach;  // x: how far it reaches east, y: south, metres; z: its raster's layer of the array; w: 0
    float4 part;   // xy: the texture coordinates of its north-west corner; zw: how far they reach east and south
};

/// The draw's: the frame's light, where it draws and where it ends.
struct ReliefPush {
    ReliefLight light;
    float4 far; // x: the depth (metres along the view) from which the light fades out, y: the reciprocal of the depth it
                // fades over (the cover's far plane ends it); z, w: 0
    uint4 at;   // x: the ground's painter slots (every pass's: the light draws half a slot above the last); yzw: 0
};

[[vk::binding(0, 0)]] ConstantBuffer<FrameBlock> frame;
[[vk::binding(0, 2)]] StructuredBuffer<ReliefQuad> relief_quads;
[[vk::binding(1, 2)]] Sampler2DArray<float4> relief_texels;
[[vk::push_constant]] ConstantBuffer<ReliefPush> push;

struct ReliefOut {
    float4 position : SV_Position;
    float3 at : AT; // the texture coordinates, and the layer
    nointerpolation float strength : STRENGTH;
};

[shader("vertex")]
ReliefOut relief_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    ReliefQuad q = relief_quads[instance];
    FrameCamera c = frame.camera;
    // two triangles: 0 the north-west corner, 1 the north-east, 2 the south-west; 3 the south-west, 4 the north-east,
    // 5 the south-east
    float east = (vertex == 1u || vertex == 4u || vertex == 5u) ? 1.0f : 0.0f;
    float south = (vertex == 2u || vertex == 3u || vertex == 5u) ? 1.0f : 0.0f;
    float3 p = {q.corner.x + east * q.reach.x, q.corner.y, q.corner.z + south * q.reach.y};
    float3 k = camera_of(c, bent(c, p));
    // clip_of's, times k.z (the rasteriser divides, and clips what lies behind the eye's plane)
    float2 s = {c.right.w * k.z + c.forward.w * k.x, c.up.w * k.z - c.forward.w * k.y};
    float2 n = {(s.x * c.frame.z) * c.device.x - k.z, (s.y * c.frame.z) * c.device.y - k.z};
    float2 t = {c.turn.x * n.x + c.turn.y * n.y, c.turn.z * n.x + c.turn.w * n.y};
    ReliefOut o;
    // between the ground's last slot, at depth at.x · S, and the items' first, at (at.x + 1) · S
    float depth = (float(push.at.x) + 0.5f) * painter_scale(c);
    o.position = float4(t.x, t.y, depth * k.z, k.z);
    o.at = float3(q.part.x + east * q.part.z, q.part.y + south * q.part.w, q.reach.z);
    o.strength = q.corner.w;
    return o;
}

struct ReliefTarget {
    [[vk::location(0)]] float4 colour : SV_Target0;
};

[shader("fragment")]
ReliefTarget relief_fs(ReliefOut v) {
    float4 texel = relief_texels.Sample(v.at);
    float3 lanes = {texel.x, texel.y, texel.z};
    // the fragment's depth along the view: SV_Position's w is the reciprocal of the clip position's, k.z
    float depth = 1.0f / v.position.w;
    float near = saturate(1.0f - (depth - push.far.x) * push.far.y);
    float3 c = relief_at(push.light, lanes, v.strength * near);
    float a = min(1.0f, min(c.x, min(c.y, c.z)));
    ReliefTarget o;
    o.colour = float4(c.x - a, c.y - a, c.z - a, a);
    return o;
}
