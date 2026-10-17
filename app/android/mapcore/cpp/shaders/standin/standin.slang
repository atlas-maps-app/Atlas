// The stand-in load of VK bench (plan-vector K1, A0): a city-sized frame drawn the way the engine will draw its own,
// so the owner's phone measures the real costs before the renderer exists. Lit prisms pulled from storage buffers,
// front to back with painter-rank depth and the objects bit; a ground with roads, shadows and a water box; tree
// sprites from an atlas by alpha-to-coverage; screen-blended glows; and the offscreen passes before the main pass:
// shadow footprints into an R8 mask, a mirrored city into the water's box, crowns into a 2048² atlas. The composite
// (shaders/standin/composite*.slang) finishes the frame; in standin_onepass (ONEPASS) the scene's fragments apply the
// haze and the vignette themselves, as the one-subpass form does. Each form is a module of its own: the core's modules
// hold no specialization constants (cmake/Shaders.cmake).
//
// Sets: 0 per frame (the frame's uniforms, the ranks, the draw order, the shadow casters), 1 the scene (boxes, trees,
// the shadow mask, the water box, the atlas). Nothing here is held to the port: it is a load, not the map.
//
// atlas: variant onepass ONEPASS=1

#ifndef ONEPASS
#define ONEPASS 0
#endif

// The frame's uniforms (StandIn.cpp fills them; gen/layouts.hpp checks the layout).
struct Frame {
    float4 view0;      // the view-projection's rows (clip = M · world)
    float4 view1;
    float4 view2;
    float4 view3;
    float4 mirror0;    // the water's mirrored camera
    float4 mirror1;
    float4 mirror2;
    float4 mirror3;
    float4 eye;        // xyz the eye (metres), w seconds since the bench began
    float4 sun;        // xyz towards the sun, w the ambient light
    float4 screen;     // x, y the output's pixels; z, w their reciprocals
    float4 rotation;   // the surface's pre-rotation of clip space: (r00, r01, r10, r11)
    float4 shadow;     // xy the shadow's offset per metre of height (-L.xz / L.y), z the mask's scale, w the radius cut
    float4 water;      // the water's box in screen uv: x0, y0, x1, y1
    float4 vignette;   // x strength, y inner radius, z outer radius
    float4 haze_colour[17];
    float4 haze_stop[5]; // the 17 stops' screen y (0 top, 1 bottom), four to a vector
    uint4 counts;      // x boxes, y trees, z items (ranks), w the ground's slot
};

struct Box {
    float2 centre;
    float2 half_size;
    float base;
    float height;
    uint wall;  // RGBA8 colour
    uint roof;
};

struct Tree {
    float2 position;
    float radius;
    float height;
    uint colour;
    uint sprite; // the atlas cell
    uint item;   // its slot in the ranks
    uint pad;
};

struct Push {
    uint pass;    // 0 the main pass, 1 the water's box, 2 the shadow mask, 3 the atlas
    uint first;   // the first atlas sprite drawn this frame
    uint count;
    uint flags;
    float4 params;
};

[[vk::binding(0, 0)]] ConstantBuffer<Frame> frame;
[[vk::binding(1, 0)]] StructuredBuffer<uint> ranks;   // per item: its place in the painter's order (0 the farthest)
[[vk::binding(2, 0)]] StructuredBuffer<uint> order;   // per draw slot: the box drawn there (front to back)
[[vk::binding(3, 0)]] StructuredBuffer<uint> casters; // the boxes within the shadows' radius, then the water's mirrored ones
[[vk::binding(0, 1)]] StructuredBuffer<Box> boxes;
[[vk::binding(1, 1)]] StructuredBuffer<Tree> trees;
[[vk::binding(2, 1)]] Sampler2D shadow_mask;
[[vk::binding(3, 1)]] Sampler2D water_box;
[[vk::binding(4, 1)]] Sampler2D atlas;
[[vk::push_constant]] ConstantBuffer<Push> push;

float4 unpack(uint c) {
    return float4(float(c & 0xFFu), float((c >> 8) & 0xFFu), float((c >> 16) & 0xFFu), float(c >> 24)) / 255.0f;
}

float4 project(float3 p) {
    float4 w = float4(p, 1.0f);
    float4 clip = float4(dot(frame.view0, w), dot(frame.view1, w), dot(frame.view2, w), dot(frame.view3, w));
    // Pre-rotation of the surface (identity offscreen and in portrait).
    float2 xy = float2(dot(frame.rotation.xy, clip.xy), dot(frame.rotation.zw, clip.xy));
    return float4(xy, clip.z, clip.w);
}

float4 project_mirror(float3 p) {
    float4 w = float4(p, 1.0f);
    return float4(dot(frame.mirror0, w), dot(frame.mirror1, w), dot(frame.mirror2, w), dot(frame.mirror3, w));
}

// A painter slot's depth (D6): later in the renders' order is nearer, so smaller; z = d·w keeps it constant after the
// divide while the eye plane still clips.
float slot_depth(uint rank) {
    return 1.0f - float(rank + 1u) / float(frame.counts.z + 2u);
}

// The screen effects of the one-subpass form, per fragment (the composite applies them once per pixel instead).
float3 finish(float3 c, float2 pixel) {
    float y = pixel.y * frame.screen.w;
    uint lo = 0u;
    for (uint i = 0u; i < 16u; ++i) {
        float s = frame.haze_stop[(i + 1u) >> 2][(i + 1u) & 3u];
        if (y >= s) lo = i;
    }
    float s0 = frame.haze_stop[lo >> 2][lo & 3u];
    float s1 = frame.haze_stop[(lo + 1u) >> 2][(lo + 1u) & 3u];
    float t = saturate((y - s0) / max(s1 - s0, 1e-6f));
    float4 h = lerp(frame.haze_colour[lo], frame.haze_colour[lo + 1u], t);
    c = lerp(c, h.rgb, h.a);
    float2 d = pixel * frame.screen.zw - 0.5f;
    float r = length(d * float2(1.0f, frame.screen.y * frame.screen.z));
    return c * (1.0f - frame.vignette.x * smoothstep(frame.vignette.y, frame.vignette.z, r));
}

// ---- Boxes: 30 vertices, five faces, pulled from the box record.

struct BoxOut {
    float4 position : SV_Position;
    float3 colour : COLOR0;
};

static const float2 corner_uv[6] = { float2(0, 0), float2(1, 0), float2(0, 1), float2(0, 1), float2(1, 0), float2(1, 1) };

// The world position of corner k of face f (0-3 the walls, counter-clockwise from outside; 4 the roof).
float3 box_corner(Box b, uint f, uint k, out float3 normal) {
    float2 uv = corner_uv[k];
    float2 h = b.half_size;
    float y0 = b.base;
    float y1 = b.base + b.height;
    float3 p;
    if (f == 4u) {
        p = float3(b.centre.x + (uv.x * 2.0f - 1.0f) * h.x, y1, b.centre.y + (1.0f - uv.y * 2.0f) * h.y);
        normal = float3(0, 1, 0);
        return p;
    }
    // Walls: south (+z), east (+x), north (-z), west (-x), each from its left corner to its right seen from outside.
    float2 a, c;
    if (f == 0u) { a = float2(-h.x, h.y); c = float2(h.x, h.y); normal = float3(0, 0, 1); }
    else if (f == 1u) { a = float2(h.x, h.y); c = float2(h.x, -h.y); normal = float3(1, 0, 0); }
    else if (f == 2u) { a = float2(h.x, -h.y); c = float2(-h.x, -h.y); normal = float3(0, 0, -1); }
    else { a = float2(-h.x, -h.y); c = float2(-h.x, h.y); normal = float3(-1, 0, 0); }
    float2 xz = lerp(a, c, uv.x);
    return float3(b.centre.x + xz.x, lerp(y0, y1, uv.y), b.centre.y + xz.y);
}

[shader("vertex")]
BoxOut box_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    uint index = order[instance];
    Box b = boxes[index];
    float3 n;
    float3 p = box_corner(b, vertex / 6u, vertex % 6u, n);
    BoxOut o;
    o.position = project(p);
    o.position.z = slot_depth(ranks[index]) * o.position.w;
    float4 wall = unpack(vertex / 6u == 4u ? b.roof : b.wall);
    float lit = frame.sun.w + (1.0f - frame.sun.w) * saturate(dot(n, frame.sun.xyz));
    o.colour = wall.rgb * lit;
    return o;
}

[shader("vertex")]
BoxOut box_mirror_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    uint index = casters[push.first + instance];
    Box b = boxes[index];
    float3 n;
    float3 p = box_corner(b, vertex / 6u, vertex % 6u, n);
    BoxOut o;
    o.position = project_mirror(float3(p.x, -p.y, p.z));
    o.position.z = slot_depth(ranks[index]) * o.position.w;
    float4 wall = unpack(vertex / 6u == 4u ? b.roof : b.wall);
    float lit = frame.sun.w + (1.0f - frame.sun.w) * saturate(dot(n, frame.sun.xyz));
    o.colour = wall.rgb * lit * 0.8f;
    return o;
}

[shader("fragment")]
float4 box_fs(BoxOut v) : SV_Target0 {
    float3 c = v.colour;
#if ONEPASS
    c = finish(c, v.position.xy);
#endif
    return float4(c, 1.0f);
}

// ---- Shadow footprints into the R8 mask: per caster, its base, its top moved along the sun, and the parallelogram
// between the diagonal through them (their union is the swept prism's hull); 18 vertices.

[shader("vertex")]
float4 shadow_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) : SV_Position {
    Box b = boxes[casters[instance]];
    float2 s = frame.shadow.xy * (b.base + b.height);
    float2 h = b.half_size;
    uint part = vertex / 6u;
    float2 uv = corner_uv[vertex % 6u];
    float2 xz;
    if (part == 0u) xz = (uv * 2.0f - 1.0f) * h;
    else if (part == 1u) xz = (uv * 2.0f - 1.0f) * h + s;
    else {
        // The diagonal perpendicular-most to the sun's offset.
        float2 d0 = float2(h.x, h.y);
        float2 d1 = float2(h.x, -h.y);
        float2 d = abs(d0.x * s.y - d0.y * s.x) > abs(d1.x * s.y - d1.y * s.x) ? d0 : d1;
        xz = lerp(-d, d, uv.x) + s * uv.y;
    }
    return project(float3(b.centre.x + xz.x, 0.0f, b.centre.y + xz.y));
}

[shader("fragment")]
float4 shadow_fs(float4 position : SV_Position) : SV_Target0 {
    return float4(1.0f, 0.0f, 0.0f, 1.0f);
}

// ---- The ground: one quad over the city, its roads, the water's box with a displaced mirror, the shadows.

struct GroundOut {
    float4 position : SV_Position;
    float2 world : TEXCOORD0;
};

[shader("vertex")]
GroundOut ground_vs(uint vertex : SV_VulkanVertexID) {
    float2 uv = corner_uv[vertex % 6u];
    float extent = push.params.x;
    GroundOut o;
    o.world = (uv * 2.0f - 1.0f) * extent;
    o.position = project(float3(o.world.x, 0.0f, o.world.y));
    o.position.z = (1.0f - 0.5f / float(frame.counts.z + 2u)) * o.position.w;
    return o;
}

float hash(float2 p) {
    uint2 q = uint2(int2(floor(p)) + 65536);
    uint h = q.x * 0x8da6b343u ^ q.y * 0xd8163841u;
    h = (h ^ (h >> 13)) * 0x85ebca6bu;
    return float(h >> 8) * (1.0f / 16777216.0f);
}

[shader("fragment")]
float4 ground_fs(GroundOut v) : SV_Target0 {
    float2 cell = v.world / 13.0f;
    float2 f = abs(frac(cell) - 0.5f);
    float road = step(0.40f, max(f.x, f.y));
    float3 land = lerp(float3(0.80f, 0.78f, 0.72f), float3(0.62f, 0.60f, 0.58f), road);
    land *= 0.94f + 0.06f * hash(cell * 7.0f);
    float2 screen_uv = v.position.xy * frame.screen.zw;
    float shade = shadow_mask.SampleLevel(screen_uv, 0.0f).r;
    float3 c = land * (1.0f - 0.32f * shade);
    // The canal: a band of water across the city, its mirror rippled.
    float band = abs(v.world.y - push.params.y);
    if (band < push.params.z) {
        float2 box = (screen_uv - frame.water.xy) / (frame.water.zw - frame.water.xy);
        float t = frame.eye.w;
        float2 ripple = float2(hash(v.world * 0.5f + t) - 0.5f, hash(v.world.yx * 0.5f - t) - 0.5f) * 0.006f;
        float3 mirror = water_box.SampleLevel(saturate(box + ripple), 0.0f).rgb;
        c = lerp(float3(0.36f, 0.50f, 0.56f), mirror, 0.45f) * (1.0f - 0.2f * shade);
    }
#if ONEPASS
    c = finish(c, v.position.xy);
#endif
    return float4(c, 1.0f);
}

// ---- Trees: billboards from the atlas, alpha-to-coverage, with the objects bit.

struct TreeOut {
    float4 position : SV_Position;
    float2 uv : TEXCOORD0;
    nointerpolation uint sprite : TEXCOORD1;
    float3 colour : COLOR0;
};

[shader("vertex")]
TreeOut tree_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    Tree t = trees[instance];
    float2 uv = corner_uv[vertex % 6u];
    float3 right = float3(frame.view0.x, 0.0f, frame.view0.z);
    right = right / max(length(right), 1e-6f);
    float3 p = float3(t.position.x, t.height * 0.6f, t.position.y) + right * (uv.x * 2.0f - 1.0f) * t.radius +
               float3(0.0f, (1.0f - uv.y * 2.0f) * t.radius, 0.0f);
    TreeOut o;
    o.position = project(p);
    o.position.z = slot_depth(ranks[t.item]) * o.position.w;
    o.uv = uv;
    o.sprite = t.sprite;
    o.colour = unpack(t.colour).rgb;
    return o;
}

[shader("fragment")]
float4 tree_fs(TreeOut v) : SV_Target0 {
    float2 cell = float2(float(v.sprite & 15u), float(v.sprite >> 4)) / 16.0f;
    float4 s = atlas.SampleLevel(cell + v.uv / 16.0f, 0.0f);
    float3 c = v.colour * (0.6f + 0.4f * s.rgb);
#if ONEPASS
    c = finish(c, v.position.xy);
#endif
    return float4(c, s.a);
}

// ---- Glows: soft discs on the ground, blended by screen (D5.4: before anything of the objects group).

struct GlowOut {
    float4 position : SV_Position;
    float2 uv : TEXCOORD0;
};

[shader("vertex")]
GlowOut glow_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    float2 uv = corner_uv[vertex % 6u];
    float2 centre = float2(hash(float2(float(instance), 1.0f)), hash(float2(float(instance), 2.0f))) * 2.0f - 1.0f;
    float radius = push.params.w;
    float2 xz = centre * push.params.x * 0.8f + (uv * 2.0f - 1.0f) * radius;
    GlowOut o;
    o.position = project(float3(xz.x, 0.0f, xz.y));
    // In front of the ground (1 - 0.5 / (n + 2)), behind every item (at most 1 - 1 / (n + 2)).
    o.position.z = (1.0f - 0.75f / float(frame.counts.z + 2u)) * o.position.w;
    o.uv = uv * 2.0f - 1.0f;
    return o;
}

[shader("fragment")]
float4 glow_fs(GlowOut v) : SV_Target0 {
    float g = saturate(1.0f - dot(v.uv, v.uv));
    float3 c = float3(1.0f, 0.82f, 0.52f) * (g * g * 0.35f);
    return float4(c, g * 0.35f);
}

// ---- The atlas: crowns drawn into 128-pixel cells of the 2048² atlas, a few each frame.

struct AtlasOut {
    float4 position : SV_Position;
    float2 uv : TEXCOORD0;
    nointerpolation uint cell : TEXCOORD1;
};

[shader("vertex")]
AtlasOut atlas_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    uint cell = (push.first + instance) & 255u;
    float2 uv = corner_uv[vertex % 6u];
    float2 at = (float2(float(cell & 15u), float(cell >> 4)) + uv) / 16.0f;
    AtlasOut o;
    o.position = float4(at * 2.0f - 1.0f, 0.0f, 1.0f);
    o.uv = uv * 2.0f - 1.0f;
    o.cell = cell;
    return o;
}

[shader("fragment")]
float4 atlas_fs(AtlasOut v) : SV_Target0 {
    float r = dot(v.uv, v.uv);
    float n = 0.0f;
    float scale = 4.0f;
    for (uint octave = 0u; octave < 4u; ++octave) {
        n += hash(v.uv * scale + float(v.cell) * 13.0f) / float(1u << octave);
        scale *= 2.0f;
    }
    float edge = 1.0f - smoothstep(0.55f, 1.0f, r + (n - 0.9f) * 0.35f);
    return float4(float3(0.35f, 0.55f, 0.28f) * (0.7f + 0.3f * n), edge);
}
