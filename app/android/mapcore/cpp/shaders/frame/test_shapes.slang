// The painter-depth proof's shapes (CP/tests/frame/painter_vk_test.cpp, K7): one triangle a draw, its three corners in
// CSS px and its colour in push constants, drawn at its painter slot's depth (painter.slang), through the frame's camera
// to the output (camera.slang's clip_of: the density and the pre-rotation). The test draws them in the two passes of
// D6 (opaque front to back with the depth test and writes, translucent after in painter's order with writes off) and in
// strict painter's order without depth, and holds the two frames equal.

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
