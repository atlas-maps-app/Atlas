// The owner mode's fragment stage (frame/FrameBuilder.hpp, FrameOptions::owners; the likeness harness's owner map, K4's
// per-owner gates): in place of a scene pipeline's own, it writes the painter slot its fragment draws at, which its
// depth holds (the frame draws an owner-mode frame under the painter switch, so every primitive, the objects too, draws
// at its painter depth: painter.slang's (slot + 1)·S, exact; the rasteriser's interpolation stays within a few units of
// the last place of it, far inside half a slot), as the colour: slot + 1 in 24 bits, the low byte in red, the middle in
// green, the high in blue, each as its 8-bit level over 255, which an 8- or 10-bit attachment keeps and the read back
// returns exactly. Blending is off, the composite passes colours through (its steps stubbed) and the frame is drawn at
// one sample, so each pixel ends with the slot of the latest primitive in painter's order that covers it: the opaque
// ones front to back with the depth test, the translucent ones after in painter's order. 0 is the clear: nothing drawn.

#include "shared/frame.slang"
#include "shared/painter.slang"

[[vk::binding(0, 0)]] ConstantBuffer<FrameBlock> frame;

struct OwnerOut {
    [[vk::location(0)]] float4 colour : SV_Target0;
};

[shader("fragment")]
OwnerOut owner_fs(float4 position : SV_Position) {
    uint id = painter_ordinal(frame.camera, position.z); // slot + 1
    OwnerOut o;
    o.colour = float4(float(id & 255u) / 255.0f, float((id >> 8u) & 255u) / 255.0f, float((id >> 16u) & 255u) / 255.0f,
                      1.0f);
    return o;
}
