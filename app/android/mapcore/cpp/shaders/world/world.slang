// The world pass (passes/World.hpp): the world's tiles beyond the countries' reach, flat on the frame's ground, under
// every country's tile (ENGINE.md step 5's step 2). A tile's words lie in the world's heap (scene/WorldTiles.hpp):
// three vertex words a triangle (its box's 16-bit fractions, u in the low half and v, from the north, in the high),
// a word a triangle (its palette entry, its cell at bit 8), then three words a line's segment (its ends, and its
// palette entry, its cell at bit 8 and its width in decimetres from bit 16).
//
//   - fill_vs and colour_fs: a tile's triangles, those of the cells the draw asks for (push.at.y), in the tile's
//     painter's order;
//   - line_vs and colour_fs: its lines, each segment a quad on the view as wide as the renders' terrain lines are
//     (relief.py: the width's pixels at its distance, no nearer than 600 m, between 0.5 and 7 px), with square caps;
//     a line under a device pixel is drawn a pixel wide, as faint as it is thin.
//
// Every draw writes the world's depth (push.origin.w: between the sky's painter slots and the ground's first), with
// GREATER_OR_EQUAL, so a later draw of the world lies over an earlier one, the countries' ground over all of it and
// the sky, drawn after, nowhere over it. A tile fading in (push.scale.z under 1) is drawn over its ancestors' cells at
// a depth a little above the world's (higher for a finer level's), its triangles top first with GREATER (the fading
// pipeline), so each pixel takes its topmost fill alone at the fade's alpha, as the tile would look whole and faded,
// and its lines at that alpha. Its colours are the palette's (set 2, binding 1), the moment's theme and the
// world's style as passes/World.cpp writes them, as 8-bit RGBA (r in the low byte), premultiplied by the line's alpha.
#include "shared/frame.slang"

[[vk::binding(0, 0)]] ConstantBuffer<FrameBlock> frame;
[[vk::binding(0, 2)]] StructuredBuffer<uint> world_words;
[[vk::binding(1, 2)]] StructuredBuffer<uint> world_palette;

struct WorldPush {
    float4 origin; // xyz: the tile's north-west corner from the eye (frame metres, on the ground); w: the world's depth
    float4 scale;  // x: metres a unit of u spans (east), y: of v (south); z: the tile's fade (1 drawn whole); w: 1 where
                   // it fades (its triangles top first), else 0
    uint4 at;      // x: the tile's first word; y: the cells drawn (bit r * 4 + c); z: its triangles' words (3T); w: its
                   // segments' (4T)
};

[[vk::push_constant]] ConstantBuffer<WorldPush> push;

struct WorldOut {
    float4 position : SV_Position;
    [[vk::location(0)]] nointerpolation float4 colour : COLOR0;
};

/// An 8-bit RGBA colour (r in the low byte) as 0-1 values.
float4 unpack(uint c) {
    return float4(float(c & 0xFFu), float((c >> 8u) & 0xFFu), float((c >> 16u) & 0xFFu), float(c >> 24u)) / 255.0f;
}

/// Whether the draw asks for cell `cell`.
bool drawn(uint cell) {
    return cell < 32u && (push.at.y & (1u << cell)) != 0u;
}

/// A vertex word's point from the eye (frame metres).
float3 point_of(uint w) {
    float3 p = {push.origin.x + float(w & 0xFFFFu) * push.scale.x, push.origin.y,
                push.origin.z + float(w >> 16u) * push.scale.y};
    return p;
}

/// The clip position of camera coordinates k, the perspective divide left to the rasteriser (k.z may be behind the
/// eye: the triangle is clipped where it crosses the eye's plane).
float4 clip_of_camera(FrameCamera c, float3 k, float depth) {
    float sx = c.frame.z * c.device.x;
    float sy = c.frame.z * c.device.y;
    float2 n = {(c.right.w * sx - 1.0f) * k.z + c.forward.w * sx * k.x,
                (c.up.w * sy - 1.0f) * k.z - c.forward.w * sy * k.y};
    float2 t = {c.turn.x * n.x + c.turn.y * n.y, c.turn.z * n.x + c.turn.w * n.y};
    float4 clip = {t.x, t.y, depth * k.z, k.z};
    return clip;
}

/// Nothing drawn: every corner the same point.
static const float4 nowhere = {0.0f, 0.0f, 0.0f, 1.0f};

[shader("vertex")]
WorldOut fill_vs(uint vertex : SV_VulkanVertexID) {
    WorldOut o;
    // a fading tile's triangles top first, the last of its painter's order first
    uint v = push.scale.w != 0.0f ? push.at.z - 1u - vertex : vertex;
    uint look = world_words[push.at.x + push.at.z + v / 3u];
    float4 colour = unpack(world_palette[look & 0xFFu]);
    o.colour = float4(colour.rgb * push.scale.z, colour.a * push.scale.z);
    if (!drawn((look >> 8u) & 0xFFu)) {
        o.position = nowhere;
        return o;
    }
    float3 k = camera_of(frame.camera, point_of(world_words[push.at.x + v]));
    o.position = clip_of_camera(frame.camera, k, push.origin.w);
    return o;
}

/// A segment's quad: its corners (0 and 1 the first end's, 2 and 3 the second's), two triangles.
static const uint line_corners[6] = {0u, 1u, 2u, 2u, 1u, 3u};

/// The renders' terrain line width (relief.py): its pixels at camera depth `d`, CSS px.
float terrain_px(FrameCamera c, float width, float d) {
    return min(7.0f, max(0.5f, width * c.forward.w / max(d, 600.0f)));
}

[shader("vertex")]
WorldOut line_vs(uint vertex : SV_VulkanVertexID, uint segment : SV_VulkanInstanceID) {
    WorldOut o;
    FrameCamera c = frame.camera;
    uint base = push.at.x + push.at.w + segment * 3u;
    uint look = world_words[base + 2u];
    if (!drawn((look >> 8u) & 0xFFu)) {
        o.colour = float4(0.0f, 0.0f, 0.0f, 0.0f);
        o.position = nowhere;
        return o;
    }
    float3 k0 = camera_of(c, point_of(world_words[base]));
    float3 k1 = camera_of(c, point_of(world_words[base + 1u]));
    float near = c.frame.w;
    if (k0.z < near && k1.z < near) {
        o.colour = float4(0.0f, 0.0f, 0.0f, 0.0f);
        o.position = nowhere;
        return o;
    }
    // the part of the segment beyond the near plane
    if (k0.z < near) {
        k0 = k0 + (k1 - k0) * ((near - k0.z) / (k1.z - k0.z));
    } else if (k1.z < near) {
        k1 = k1 + (k0 - k1) * ((near - k1.z) / (k0.z - k1.z));
    }
    uint corner = line_corners[vertex % 6u];
    bool second = corner >= 2u;
    float side = (corner & 1u) != 0u ? 1.0f : -1.0f;
    float density = c.frame.z;
    float2 s0 = css_of(c, k0) * density;
    float2 s1 = css_of(c, k1) * density;
    float2 along = s1 - s0;
    float length_px = length(along);
    float2 d = length_px > 1e-6f ? along / length_px : float2(1.0f, 0.0f);
    float2 across = {-d.y, d.x};
    float3 k = second ? k1 : k0;
    float width_m = float(look >> 16u) * 0.1f;
    float wide = terrain_px(c, width_m, k.z) * density; // device px
    float half_px = max(wide, 1.0f) * 0.5f;
    float2 s = (second ? s1 : s0) + across * (side * half_px) + d * ((second ? 1.0f : -1.0f) * half_px);
    float2 n = {s.x * c.device.x - 1.0f, s.y * c.device.y - 1.0f};
    float2 t = {c.turn.x * n.x + c.turn.y * n.y, c.turn.z * n.x + c.turn.w * n.y};
    o.position = float4(t.x, t.y, push.origin.w, 1.0f);
    float alpha = min(1.0f, wide) * push.scale.z;
    float4 colour = unpack(world_palette[look & 0xFFu]);
    o.colour = float4(colour.rgb * alpha, alpha);
    return o;
}

struct ColourOut {
    [[vk::location(0)]] float4 colour : SV_Target0;
};

[shader("fragment")]
ColourOut colour_fs(WorldOut v) {
    ColourOut o;
    o.colour = v.colour;
    return o;
}
