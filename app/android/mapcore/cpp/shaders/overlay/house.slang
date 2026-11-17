// The house the app asks about, in the roof it asks about (ENGINE.md step 6, FillIn's roof question; passes/House.cpp):
// the building as the FillIn board's house, drawn in its place while the question is open. The frame's ranks leave the
// tile's own building out, and this draws the one building the core built again with the roof asked for
// (scene/House.hpp): its triangles at their true depth among what stands up (opaque, writing the depth and the objects
// bit, as the buildings' walls and roofs do), each in its face's colour as the CPU gives it for the frame (the walls'
// flat colour, a slope's grey in the moment's light, a flat top's white), the walls under the board's hatch before the
// colour's answer (shaders/building/draw.slang's building_hatch, the same lines); then a flat top's outline, the
// board's thin stroke, one device px wide over what the frame drew there (translucent, at its own depth).
//
// The points are the pass's own set (2): this frame slot's, a triangle's three corners after another, then the
// outline's segments' ends. Each is placed as its tile's geometry is (camera.slang's placed, with the tile's place in
// the push constants) and projected with the perspective division left to the rasteriser, so a triangle that passes
// the eye's plane is clipped at the near plane (z = depth · w, as clip_of has it).

#include "shared/frame.slang"
#include "shared/painter.slang"

/// One corner.
struct HousePoint {
    float4 at;     // xyz: metres from its tile's south-west corner at street level (x east, y up, z south); w: 1 where
                   // the board's hatch lies over its face
    float4 colour; // its face's colour, sRGB 0 to 1 at 8-bit levels, premultiplied by its alpha (w: 1 but an outline's)
};

/// The draw's: its tile's place (camera.slang's TilePlace, its two vectors).
struct HousePush {
    float4 offset;
    float4 scale;
};

[[vk::binding(0, 0)]] ConstantBuffer<FrameBlock> frame;
[[vk::binding(0, 2)]] StructuredBuffer<HousePoint> house_points;
[[vk::push_constant]] ConstantBuffer<HousePush> push;

struct HouseOut {
    float4 position : SV_Position;
    nointerpolation float4 colour : COLOUR;
    nointerpolation uint hatched : HATCHED;
};

/// The clip position of camera coordinates `k` at their true depth: clip_of's, times k.z, without dividing by it (the
/// rasteriser divides, and clips what lies nearer than the near plane: z / w is the reciprocal depth near / k.z, at
/// most 1).
float4 house_clip(FrameCamera c, float3 k) {
    float2 s = {c.right.w * k.z + c.forward.w * k.x, c.up.w * k.z - c.forward.w * k.y};
    float2 n = {(s.x * c.frame.z) * c.device.x - k.z, (s.y * c.frame.z) * c.device.y - k.z};
    float2 t = {c.turn.x * n.x + c.turn.y * n.y, c.turn.z * n.x + c.turn.w * n.y};
    // true_depth(near / k.z) · k.z
    float z = (c.flags.x & CAMERA_DEPTH24) != 0u ? 0.5f * k.z + 0.5f * c.frame.w : c.frame.w;
    float4 clip = {t.x, t.y, z, k.z};
    return clip;
}

[shader("vertex")]
HouseOut house_vs(uint vertex : SV_VulkanVertexID) {
    HousePoint p = house_points[vertex];
    FrameCamera c = frame.camera;
    float3 local = {p.at.x, p.at.y, p.at.z};
    TilePlace place;
    place.offset = push.offset;
    place.scale = push.scale;
    HouseOut o;
    o.position = house_clip(c, camera_of(c, placed(c, place, local)));
    o.colour = p.colour;
    o.hatched = p.at.w != 0.0f ? 1u : 0u;
    return o;
}

/// The FillIn board's hatch over `colour` at the output's pixel `position`: building_hatch's (#8C857A lines, 2.2 CSS px
/// wide every 7, at 45 degrees), so the house's walls are hatched as the asked building's are.
float3 house_hatch(FrameCamera c, float3 colour, float2 position) {
    float2 css = css_of_output(c, position);
    float u = (css.x + css.y) * 0.70710678f;
    float d = abs(u - 7.0f * round(u / 7.0f)) * c.frame.z;
    float cover = saturate(1.1f * c.frame.z - d + 0.5f);
    return lerp(colour, float3(140.0f, 133.0f, 122.0f) / 255.0f, cover);
}

struct HouseTarget {
    [[vk::location(0)]] float4 colour : SV_Target0;
};

[shader("fragment")]
HouseTarget house_fs(HouseOut v) {
    float3 c = {v.colour.x, v.colour.y, v.colour.z};
    if (v.hatched != 0u) {
        c = house_hatch(frame.camera, c, v.position.xy);
    }
    HouseTarget o;
    o.colour = float4(c, v.colour.w);
    return o;
}
