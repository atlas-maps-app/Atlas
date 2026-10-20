// The painter-depth proof's shapes (CP/tests/frame/painter_vk_test.cpp, K7): one triangle a draw, its three corners in
// CSS px and its colour in push constants, drawn at its painter slot's depth (painter.slang), through the frame's camera
// to the output (camera.slang's clip_of: the density and the pre-rotation). The test draws them in the two passes of
// D6 (opaque front to back with the depth test and writes, translucent after in painter's order with writes off) and in
// strict painter's order without depth, and holds the two frames equal. varying_vs passes the colour on as a varying, for
// the check that the owner and overdraw twins of a pass with varyings are made without the layer's warning (their
// fragment stages read nothing of it) while a pass's own pipeline leaving it unread still gets it (shapes_fs).

#include "shared/frame.slang"
#include "shared/painter.slang"

[[vk::binding(0, 0)]] ConstantBuffer<FrameBlock> frame;

/// A shape's push constants: the frame's 128-byte range.
struct ShapePush {
    float4 a_b;    // xy: the first corner, zw: the second (CSS px)
    float4 c_slot; // xy: the third corner; z: unused; w: unused
    float4 colour; // its colour, its alpha its opacity
    uint4 slot;    // x: its painter slot
    float4 pad[4];
};

[[vk::push_constant]] ConstantBuffer<ShapePush> shape;

struct ShapeVertex {
    float4 position : SV_Position;
};

[shader("vertex")]
ShapeVertex shapes_vs(uint vertex : SV_VulkanVertexID) {
    float2 css = vertex == 0u ? shape.a_b.xy : (vertex == 1u ? shape.a_b.zw : shape.c_slot.xy);
    ShapeVertex o;
    o.position = clip_of(frame.camera, css, 1.0f, painter_depth(shape.slot.x));
    return o;
}

struct ShapeOut {
    [[vk::location(0)]] float4 colour : SV_Target0;
};

[shader("fragment")]
ShapeOut shapes_fs(ShapeVertex v) {
    ShapeOut o;
    o.colour = shape.colour;
    return o;
}

/// A vertex with outputs beside its position: the shape's colour, and its slot (flat), at locations 0 and 1. The layer
/// in use reports an unread output at location 1 whatever the fragment stage declares (one at location 0 it takes for
/// read when the fragment stage writes a colour there), so the check needs both.
struct ColourVertex {
    float4 position : SV_Position;
    [[vk::location(0)]] float4 colour : COLOR0;
    [[vk::location(1)]] nointerpolation uint slot : SLOT;
};

[shader("vertex")]
ColourVertex varying_vs(uint vertex : SV_VulkanVertexID) {
    float2 css = vertex == 0u ? shape.a_b.xy : (vertex == 1u ? shape.a_b.zw : shape.c_slot.xy);
    ColourVertex o;
    o.position = clip_of(frame.camera, css, 1.0f, painter_depth(shape.slot.x));
    o.colour = shape.colour;
    o.slot = shape.slot.x;
    return o;
}

[shader("fragment")]
ShapeOut varying_fs(ColourVertex v) {
    ShapeOut o;
    o.colour = v.slot == 0xFFFFFFFFu ? float4(0.0f) : v.colour;
    return o;
}
