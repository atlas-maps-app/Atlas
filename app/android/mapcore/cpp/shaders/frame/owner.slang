// The owner mode's fragment stage (frame/FrameBuilder.hpp, FrameOptions::owners; the likeness harness's owner map, K4's
// per-owner gates): in place of a scene pipeline's own, it writes the painter slot its fragment draws at, which its
// depth holds (painter.slang: 1 − (slot + 1)·2^-20, exact; the rasteriser's interpolation stays within a few 2^-24 of
// it, far inside half a slot), as the colour: slot + 1 in 24 bits, the low byte in red, the middle in green, the high in
// blue, each as its 8-bit level over 255, which an 8- or 10-bit attachment keeps and the read back returns exactly.
// Blending is off, the composite passes colours through (its steps stubbed) and the frame is drawn at one sample, so
// each pixel ends with the slot of the latest primitive in painter's order that covers it: the opaque ones front to
// back with the depth test, the translucent ones after in painter's order. 0 is the clear: nothing drawn.

struct OwnerOut {
    [[vk::location(0)]] float4 colour : SV_Target0;
};

[shader("fragment")]
OwnerOut owner_fs(float4 position : SV_Position) {
    uint id = uint(round((1.0f - position.z) * 1048576.0f)); // slot + 1
    OwnerOut o;
    o.colour = float4(float(id & 255u) / 255.0f, float((id >> 8u) & 255u) / 255.0f, float((id >> 16u) & 255u) / 255.0f,
                      1.0f);
    return o;
}
