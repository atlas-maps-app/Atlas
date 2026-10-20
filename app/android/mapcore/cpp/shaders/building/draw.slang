// The buildings' scene pipelines (plan-vector D10, K10; CP/passes/Walls.cpp): walls_vs draws a wall (as one cell, or a
// skillion's or a houseboat's wall), or a near cell cut from its wall's record, as its outline dilated by the
// same-colour 0.3 px edge, 16 points through the static fan of its index buffer; roofs_vs draws a roof's face or an
// n-gon's triangle as its polygon, 5 points through a fan of 3 triangles (the corners a near plane's cut may leave,
// repeated where there are fewer). Both read their primitive, face and colours from the walls heap (set 1, binding 1;
// the colours the CPU's light, per tile and moment) and their building's rank entry through the tile's rows (set 0,
// binding 1), decide it as shaders/building/building.slangh does, and write its painter depth; building_fs fills it
// with its face's light. Opaque: never discards, its pipelines write the depth and the objects bit.

#include "shared/frame.slang"
#include "shared/painter.slang"

/// building.slangh's trigonometry: the GPU's own (the outline's arcs are not exact code).
inline float building_cos(float a) {
    return cos(a);
}
inline float building_sin(float a) {
    return sin(a);
}
inline float building_atan2(float y, float x) {
    return atan2(y, x);
}

#include "building/building.slangh"

[[vk::binding(0, 0)]] ConstantBuffer<FrameBlock> frame;
[[vk::binding(1, 0)]] StructuredBuffer<uint> rank_table;
[[vk::binding(1, 1)]] StructuredBuffer<uint4> walls_heap;
[[vk::binding(6, 1)]] StructuredBuffer<uint2> sun_bits;
[[vk::push_constant]] ConstantBuffer<BuildingPush> push;

struct BuildingOut {
    float4 position : SV_Position;
    nointerpolation float3 colour : COLOUR;
};

/// The primitive at record `index` of the draw's list, its face, its rank entry, and the shape the frame draws.
struct Drawn {
    BuildingPrim prim;
    BuildingFace face;
    BuildingShape shape;
};

/// The primitive at `at` (a uint4 index) of the walls heap.
BuildingPrim prim_at(uint at) {
    return building_prim(walls_heap[at], walls_heap[at + 1u], walls_heap[at + 2u], walls_heap[at + 3u]);
}

Drawn drawn_of(uint index) {
    Drawn d;
    if (push.sun.w != 0u) {
        // a near cell: its record's two words (its wall in the edged list, its place), cut from its wall
        uint4 pair = walls_heap[push.at.x + index / 2u];
        bool odd = (index & 1u) != 0u;
        uint wall = odd ? pair.z : pair.x;
        uint code = odd ? pair.w : pair.y;
        d.prim = building_cell_prim(prim_at(push.sun.z + wall * BUILDING_PRIM_VECTORS), code);
    } else {
        d.prim = prim_at(push.at.x + index * BUILDING_PRIM_VECTORS);
    }
    uint fa = push.at.y + d.prim.face * BUILDING_FACE_VECTORS;
    d.face = building_face(walls_heap[fa], walls_heap[fa + 1u]);
    uint4 rows = walls_heap[push.at.w + d.prim.item / 4u];
    uint rank = rank_table[rows[d.prim.item % 4u]];
    d.shape = building_shape(frame.camera, frame.theme.flags.x, push.place, d.prim, d.face, rank);
    return d;
}

/// The face's colour at the frame: its light, lit by its sample's sun bit.
float3 colour_of(Drawn d) {
    uint word = push.at.z + d.prim.face * 2u; // even, so both words lie in one uint4
    uint4 pair = walls_heap[word / 4u];
    uint lit = pair[word % 4u];
    uint unlit = pair[(word % 4u) + 1u];
    uint has = push.sun.x != BUILDING_NO_BITS ? 1u : 0u;
    uint2 texel = {0u, 0u};
    if (has != 0u && d.prim.sample != BUILDING_NO_SAMPLE) {
        texel = sun_bits[push.sun.x + d.prim.sample / 32u];
    }
    float amount = building_sun_amount(d.face.flags, d.prim.sample, texel, has, asfloat(push.sun.y));
    return building_colour(lit, unlit, amount);
}

/// Where a primitive that draws nothing puts its points: beyond the depth range, so its triangles are clipped away.
static const float4 NOWHERE = {0.0f, 0.0f, 2.0f, 1.0f};

[shader("vertex")]
BuildingOut walls_vs(uint vertex : SV_VulkanVertexID) {
    uint index = vertex / BUILDING_EDGED_VERTICES;
    Drawn d = drawn_of(index);
    BuildingOut o;
    if (d.shape.count == 0u) {
        o.position = NOWHERE;
        o.colour = float3(0.0f);
        return o;
    }
    float2 css = building_outline(d.shape, vertex - index * BUILDING_EDGED_VERTICES, BUILDING_EDGE_HALF);
    o.position = clip_of(frame.camera, css, 1.0f, d.shape.depth);
    o.colour = colour_of(d);
    return o;
}

[shader("vertex")]
BuildingOut roofs_vs(uint vertex : SV_VulkanVertexID) {
    uint index = vertex / BUILDING_PLAIN_VERTICES;
    Drawn d = drawn_of(index);
    BuildingOut o;
    if (d.shape.count == 0u) {
        o.position = NOWHERE;
        o.colour = float3(0.0f);
        return o;
    }
    float2 css = building_plain_point(d.shape, vertex - index * BUILDING_PLAIN_VERTICES);
    o.position = clip_of(frame.camera, css, 1.0f, d.shape.depth);
    o.colour = colour_of(d);
    return o;
}

struct BuildingTarget {
    [[vk::location(0)]] float4 colour : SV_Target0;
};

[shader("fragment")]
BuildingTarget building_fs(BuildingOut v) {
    BuildingTarget o;
    // K17a's drawn texture of the walls (`mat`) joins here when shaders/fx/mat.slang lands, on where the frame's fields
    // are bound and the moment has weather (the renders' mat group): c = mat_colour(frame.camera, c, v.position.xy,
    // mat_on(frame))
    float3 c = v.colour;
    o.colour = float4(c, 1.0f);
    return o;
}
