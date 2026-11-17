// The world pass (passes/World.hpp): the world's tiles beyond the countries' reach, flat on the frame's ground, under
// every country's tile (ENGINE.md step 5's step 2). A tile's words lie in the world's heap (scene/WorldTiles.hpp):
// three vertex words a triangle (its box's 16-bit fractions, u in the low half and v, from the north, in the high),
// a word a triangle (its palette entry, its cell at bit 8), then three words a line's segment (its ends, and its
// palette entry, its cell at bit 8 and its width in decimetres from bit 16). A tile no piece of the heap holds whole
// lies in parts, each laid out so (scene::WorldPart), and is drawn as one piece all the same: its draws find each
// triangle's, segment's and light's part in its parts' table (set 2, binding 3; part_of), so its draws are those of
// the tile whole, vertex for vertex, and so are its pixels. (Drawn part by part, the rasteriser's ties where an edge
// runs through a sample fell with where its parts began: the keep's pitched views, 14 November.)
//
//   - fill_vs and colour_fs: a tile's triangles, those of the cells the draw asks for (push.at.y), in the tile's
//     painter's order;
//   - line_vs and colour_fs: its lines, each segment a quad on the view as wide as the renders' terrain lines are
//     (relief.py: the width's pixels at its distance, no nearer than 600 m, between 0.5 and 7 px), with square caps;
//     a line under a device pixel is drawn a pixel wide, as faint as it is thin.
//
// Beyond a camera 18 km from its target the ground bends (ENGINE.md step 5, "The bend"; view/Bend.hpp): each point
// drops by w₁·|Δ|²/2R, as every pass's do (shared/camera.slang's bent), and from 150 km on blends by w₂ (push.sphere.z)
// to its place on the sphere tangent to the ground at the camera's target (shared/sphere.slang), so that from 600 km
// the world is the sphere. The fills' back faces are culled (passes/World.cpp's pipelines), and a line's segment is
// dropped where either end faces away from the eye, beyond the horizon.
//
// Beyond 18 km the world's look follows the style ladder (ENGINE.md step 5, "The style ladder" and "The globe";
// shared/globe.slang, scene/WorldLook.hpp). A frame the ladder shades draws its fills and lines with the look's stages
// (fill_look_vs, line_look_vs, look_fs): look_fs shades each fragment by the frame's look block (set 2, binding 2)
// from its palette colours, the moment's and the day's (the palette's second half), what it is to the ladder (the
// land, the water, another) and the ground's direction from the planet's centre, which gives the sun's altitude there
// (the night's caps) and the surface's normal in the view (the board's ramps). A frame it leaves its palette's
// colours (every frame up to 18 km; by day under the ramps while the sun stands over all the eye sees: the look's
// `plain`) draws with the plain stages, a colour a vertex and nothing a fragment. Over the tiles:
//
//   - light_vs and light_fs: a tile's cities' lights on the night side, a disc and its halo each (two words a light
//     after the tile's segments: its place as a vertex word, then its size and its cell at bit 8);
//   - star_vs and star_fs: the Globe board's backdrop stars (shared/backdrop.slang: the board's own, fixed on the
//     frame as the board's are and fitted to it as the board's pictures, board_fit), each a disc, at space's depth, so
//     only where nothing of the world draws; with the board's look (w.z, from 2,000 km). The board's Milky Way band is
//     globe_fs's, in space.
//   - globe_vs and globe_fs: a triangle over the frame, drawn twice. As space (push.at.x 0, at a depth under the
//     world's, so only where nothing of the world draws): space and its glow where the sky was, the atmosphere's ring
//     and the dashed outline about the planet's disc. As the glass (push.at.x 1, over the world): the shine on the
//     disc and the rim on its edge. The disc is the bend's sphere's, by each pixel's ray.
//
// Every draw writes the world's depth (push.origin.w: between the sky's painter slots and the ground's first), with
// GREATER_OR_EQUAL, so a later draw of the world lies over an earlier one, the countries' ground over all of it and
// the sky, drawn after, nowhere over it. A tile fading in (push.scale.z under 1) is drawn over its ancestors' cells at
// a depth a little above the world's (higher for a finer level's), its triangles top first with GREATER (the fading
// pipeline), so each pixel takes its topmost fill alone at the fade's alpha, as the tile would look whole and faded,
// and its lines at that alpha. Its colours are the palette's (set 2, binding 1), the moment's theme and the
// world's style as passes/World.cpp writes them, as 8-bit RGBA (r in the low byte), premultiplied by the line's alpha.
#include "shared/backdrop.slang"
#include "shared/frame.slang"
#include "shared/globe.slang"
#include "shared/sphere.slang"

[[vk::binding(0, 0)]] ConstantBuffer<FrameBlock> frame;
[[vk::binding(0, 2)]] StructuredBuffer<uint> world_words;
[[vk::binding(1, 2)]] StructuredBuffer<uint> world_palette;
[[vk::binding(2, 2)]] ConstantBuffer<WorldLookBlock> look;
[[vk::binding(3, 2)]] StructuredBuffer<uint> world_parts;

/// The palette's entries: the moment's first, then the day's (scene::world_palette's size apart).
static const uint PALETTE_DAY = 64u;

/// push.at.x's bit for a tile in parts (passes/World.cpp's in_parts): its other bits are the entry its parts' table
/// begins at in world_parts, four words an entry: for each part its first word in the heap and its first triangle,
/// segment and light among the tile's, then the tile's triangles, segments and lights (after a word unread).
static const uint WORLD_PARTS = 0x80000000u;

struct WorldPush {
    float4 origin; // xyz: the tile's north-west corner from the eye (frame metres, on the ground); w: the world's depth
    float4 scale;  // x: metres a unit of u spans (east), y: of v (south); z: the tile's fade (1 drawn whole); w: 1 where
                   // it fades (its triangles top first), else 0
    uint4 at;      // x: the tile's first word; y: the cells drawn (bit r * 4 + c); z: its triangles' words (3T); w: its
                   // segments' (4T), or for its lights' draw where they begin. A tile in parts: x WORLD_PARTS and its
                   // parts' table, w its parts. globe_vs's draws: x 0 space, 1 the glass
    float4 globe;  // x: the tile's north edge less the target's latitude, y: the radians of latitude a unit of v spans
                   // (south: negative); z: its west edge less the target's longitude (within ±π), w: the radians of
                   // longitude a unit of u spans
    float4 sphere; // x, y: the sine and cosine of the target's latitude; z: w₂ (0: flat and dropped); w: the radius
    float4 target; // xyz: the camera's target from the eye (frame metres), where the sphere touches the ground
};

[[vk::push_constant]] ConstantBuffer<WorldPush> push;

/// The plain stages' (fill_vs, line_vs, colour_fs: a frame the ladder leaves its palette's colours, as every frame up
/// to 18 km): a primitive's colour, premultiplied by its opacity.
struct WorldOut {
    float4 position : SV_Position;
    [[vk::location(0)]] nointerpolation float4 colour : COLOR0;
};

/// The look's stages' (fill_look_vs, line_look_vs, look_fs: a frame the ladder shades): a primitive's palette entry
/// and opacity, and the ground's direction from the planet's centre (frame axes), which the fragment shades by. Its
/// colours are the fragment's to read, so a vertex carries five values, not eleven: the world's tiles hold two
/// million triangles from 100 km.
struct LookOut {
    float4 position : SV_Position;
    [[vk::location(0)]] nointerpolation uint entry : ENTRY0;
    [[vk::location(1)]] nointerpolation float alpha : ALPHA0;
    [[vk::location(2)]] float3 normal : NORMAL0;
};

/// What palette entry `entry` is to the ladder: the land (the land and the land cover), the water, or another (the
/// lines, and the ice: glaciers, ice shelves and the south pole's cap).
uint kind_of(uint entry) {
    if (entry == 1u) return GLOBE_WATER;
    // (entry 6, the ice, keeps its colour to the globe: it takes no ramp)
    return entry == 0u || entry > GLOBE_COAST_ENTRY ? GLOBE_LAND : GLOBE_OTHER;
}

/// An 8-bit RGBA colour (r in the low byte) as 0-1 values.
float4 unpack(uint c) {
    return float4(float(c & 0xFFu), float((c >> 8u) & 0xFFu), float((c >> 16u) & 0xFFu), float(c >> 24u)) / 255.0f;
}

/// Whether the draw asks for cell `cell`.
bool drawn(uint cell) {
    return cell < 32u && (push.at.y & (1u << cell)) != 0u;
}

/// Whether the draw's tile lies in parts (WORLD_PARTS).
bool in_parts() {
    return (push.at.x & WORLD_PARTS) != 0u;
}

/// The part of the draw's tile in parts that holds the tile's `index`th triangle (`lane` 1), segment (2) or light (3):
/// its first word in the heap, its first of the lane's among the tile's, and its triangles and segments, where its
/// segments' and its lights' words begin.
struct Part {
    uint word;
    uint first;
    uint triangles;
    uint segments;
};

Part part_of(uint lane, uint index) {
    uint table = push.at.x & ~WORLD_PARTS;
    // the last of its push.at.w parts whose first is at `index` or before it
    uint lo = 0u;
    uint hi = push.at.w;
    while (hi - lo > 1u) {
        uint mid = (lo + hi) / 2u;
        if (world_parts[(table + mid) * 4u + lane] <= index) {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    uint e = (table + lo) * 4u;
    Part p;
    p.word = world_parts[e];
    p.first = world_parts[e + lane];
    p.triangles = world_parts[e + 5u] - world_parts[e + 1u];
    p.segments = world_parts[e + 6u] - world_parts[e + 2u];
    return p;
}

/// A vertex word's point on the bent ground, from the eye (frame metres), the ground's normal there (not of unit
/// length: the dropped ground's gradient blended to the sphere's by w₂), and its direction from the planet's centre
/// (not of unit length: its offset from the target over the radius on the flat ground, the sphere's own beyond).
struct Ground {
    float3 p;
    float3 n;
    float3 from_centre;
};

Ground ground_of(uint w) {
    FrameCamera c = frame.camera;
    float u = float(w & 0xFFFFu);
    float v = float(w >> 16u);
    float3 flat = {push.origin.x + u * push.scale.x, push.origin.y, push.origin.z + v * push.scale.y};
    float slope = 2.0f * c.device.w;
    Ground g;
    g.p = bent(c, flat);
    g.n = float3(slope * (flat.x - asfloat(c.flags.z)), 1.0f, slope * (flat.z - asfloat(c.flags.w)));
    float r = push.sphere.w;
    g.from_centre = float3((flat.x - push.target.x) / r, 1.0f, (flat.z - push.target.z) / r);
    float w2 = push.sphere.z;
    if (w2 > 0.0f) {
        float3 o = on_sphere(push.globe.x + v * push.globe.y, push.globe.z + u * push.globe.w, push.sphere.x,
                             push.sphere.y, r);
        float3 s = push.target.xyz + o;
        float3 n = {o.x / r, (o.y + r) / r, o.z / r};
        g.p = g.p + (s - g.p) * w2;
        g.n = g.n + (n - g.n) * w2;
        g.from_centre = n;
    }
    return g;
}

/// Whether the ground at g faces the eye: in front of the horizon.
bool faces_eye(Ground g) {
    return dot(g.n, g.p) < 0.0f;
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

/// A vertex of a fill or of a line's quad, as both sets of stages take it: its place, its primitive's palette entry
/// and opacity, and the ground's direction from the planet's centre there.
struct Placed {
    float4 position;
    uint entry;
    float alpha;
    float3 normal;
};

Placed fill_of(uint vertex) {
    Placed o;
    // a fading tile's triangles top first, the last of its painter's order first
    uint v = push.scale.w != 0.0f ? push.at.z - 1u - vertex : vertex;
    // its vertex's word and its triangle's; in its part where the tile lies in parts
    uint word = push.at.x + v;
    uint what_at = push.at.x + push.at.z + v / 3u;
    if (in_parts()) {
        Part p = part_of(1u, v / 3u);
        word = p.word + v - 3u * p.first;
        what_at = p.word + 3u * p.triangles + (v / 3u - p.first);
    }
    uint what = world_words[what_at];
    o.entry = what & 0xFFu;
    o.alpha = push.scale.z;
    o.normal = float3(0.0f, 1.0f, 0.0f);
    if (!drawn((what >> 8u) & 0xFFu)) {
        o.position = nowhere;
        return o;
    }
    Ground g = ground_of(world_words[word]);
    o.normal = g.from_centre;
    float3 k = camera_of(frame.camera, g.p);
    o.position = clip_of_camera(frame.camera, k, push.origin.w);
    return o;
}

[shader("vertex")]
WorldOut fill_vs(uint vertex : SV_VulkanVertexID) {
    WorldOut o;
    Placed f = fill_of(vertex);
    float4 colour = unpack(world_palette[f.entry]);
    o.colour = float4(colour.rgb * f.alpha, colour.a * f.alpha);
    o.position = f.position;
    return o;
}

[shader("vertex")]
LookOut fill_look_vs(uint vertex : SV_VulkanVertexID) {
    LookOut o;
    Placed f = fill_of(vertex);
    o.entry = f.entry;
    o.alpha = f.alpha;
    o.normal = f.normal;
    o.position = f.position;
    return o;
}

/// A segment's quad: its corners (0 and 1 the first end's, 2 and 3 the second's), two triangles.
static const uint line_corners[6] = {0u, 1u, 2u, 2u, 1u, 3u};

/// The renders' terrain line width (relief.py): its pixels at camera depth `d`, CSS px.
float terrain_px(FrameCamera c, float width, float d) {
    return min(7.0f, max(0.5f, width * c.forward.w / max(d, 600.0f)));
}

Placed segment_of(uint vertex, uint segment) {
    Placed o;
    FrameCamera c = frame.camera;
    uint base = push.at.x + push.at.w + segment * 3u;
    if (in_parts()) {
        Part p = part_of(2u, segment);
        base = p.word + 4u * p.triangles + 3u * (segment - p.first);
    }
    uint what = world_words[base + 2u];
    uint entry = what & 0xFFu;
    o.entry = entry;
    o.alpha = 0.0f;
    o.normal = float3(0.0f, 1.0f, 0.0f);
    // the coast comes with the board's ramps, by the ladder's weight
    float coast = entry == GLOBE_COAST_ENTRY ? look.coast.a * look.weights.y : 1.0f;
    if (!drawn((what >> 8u) & 0xFFu) || coast <= 0.0f) {
        o.position = nowhere;
        return o;
    }
    Ground g0 = ground_of(world_words[base]);
    Ground g1 = ground_of(world_words[base + 1u]);
    float3 k0 = camera_of(c, g0.p);
    float3 k1 = camera_of(c, g1.p);
    float near = c.frame.w;
    if ((k0.z < near && k1.z < near) || !faces_eye(g0) || !faces_eye(g1)) {
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
    o.normal = second ? g1.from_centre : g0.from_centre;
    float width_m = float(what >> 16u) * 0.1f;
    float wide = terrain_px(c, width_m, k.z) * density; // device px
    if (entry == GLOBE_COAST_ENTRY) wide = look.coast_at.x;
    float half_px = max(wide, 1.0f) * 0.5f;
    float2 s = (second ? s1 : s0) + across * (side * half_px) + d * ((second ? 1.0f : -1.0f) * half_px);
    float2 n = {s.x * c.device.x - 1.0f, s.y * c.device.y - 1.0f};
    float2 t = {c.turn.x * n.x + c.turn.y * n.y, c.turn.z * n.x + c.turn.w * n.y};
    o.position = float4(t.x, t.y, push.origin.w, 1.0f);
    float alpha = min(1.0f, wide) * push.scale.z * coast;
    // a coast's segment by its own length's share of its quad, without its caps': where a detailed coast (a fjord's)
    // has many segments to a pixel their caps would pile up into a band
    if (entry == GLOBE_COAST_ENTRY) alpha *= length_px / (length_px + 2.0f * half_px);
    o.alpha = alpha;
    return o;
}

[shader("vertex")]
WorldOut line_vs(uint vertex : SV_VulkanVertexID, uint segment : SV_VulkanInstanceID) {
    WorldOut o;
    Placed l = segment_of(vertex, segment);
    float4 colour = unpack(world_palette[l.entry]);
    o.colour = float4(colour.rgb * l.alpha, l.alpha);
    o.position = l.position;
    return o;
}

[shader("vertex")]
LookOut line_look_vs(uint vertex : SV_VulkanVertexID, uint segment : SV_VulkanInstanceID) {
    LookOut o;
    Placed l = segment_of(vertex, segment);
    o.entry = l.entry;
    o.alpha = l.alpha;
    o.normal = l.normal;
    o.position = l.position;
    return o;
}

struct ColourOut {
    [[vk::location(0)]] float4 colour : SV_Target0;
};

/// How much of a fragment the graticule covers where the ground's direction from the planet's centre is the unit `n`:
/// the parallels (the equator and two a side) and the meridians, each a line of the block's width, by the screen's
/// gradients of the sines that are 0 on them.
float graticule(float3 n) {
    float half_px = look.prime.w;
    float s = dot(n, look.pole.xyz);
    float a = abs(s);
    float along = min(a, min(abs(a - look.parallels.x), abs(a - look.parallels.y)));
    float cover = saturate(half_px + 0.5f - along / max(length(float2(ddx(s), ddy(s))), 1e-12f));
    if (a < look.pole.w) {
        float x = dot(n, look.prime.xyz);
        float y = dot(n, look.east.xyz);
        float2 gx = {ddx(x), ddy(x)};
        float2 gy = {ddx(y), ddy(y)};
        float step_cos = look.parallels.w;
        float step_sin = sqrt(max(0.0f, 1.0f - step_cos * step_cos));
        float c = 1.0f;
        float t = 0.0f;
        uint count = uint(look.east.w);
        for (uint k = 0u; k < count; ++k) {
            // the plane of the meridians k steps east of the prime: 0 where n lies on it
            float f = c * y - t * x;
            float2 g = gy * c - gx * t;
            cover = max(cover, saturate(half_px + 0.5f - abs(f) / max(length(g), 1e-12f)));
            float next = c * step_cos - t * step_sin;
            t = t * step_cos + c * step_sin;
            c = next;
        }
    }
    return cover;
}

[shader("fragment")]
ColourOut colour_fs(WorldOut v) {
    ColourOut o;
    o.colour = v.colour;
    return o;
}

[shader("fragment")]
ColourOut look_fs(LookOut v) {
    ColourOut o;
    float4 moment = unpack(world_palette[v.entry]);
    float3 c = moment.rgb;
    float alpha = moment.a * v.alpha;
    float4 w = look.weights;
    if (w.x > 0.0f) {
        // the day's colour, toward the board's ramps, under the graticule and the night's caps
        float3 n = normalize(v.normal);
        float3 d = unpack(world_palette[PALETTE_DAY + v.entry]).rgb;
        uint kind = kind_of(v.entry);
        if (w.y > 0.0f && kind != GLOBE_OTHER) {
            float2 on_disc = {dot(n, frame.camera.right.xyz), dot(n, frame.camera.up.xyz)};
            float3 ramp = globe_land(look, on_disc);
            if (kind == GLOBE_WATER) ramp = globe_sea(look, length(on_disc - look.sea_at.xy));
            d = d + (ramp - d) * w.y;
        }
        if (w.z > 0.0f) {
            // the lines' cover is worked out for every fragment, the land's too: its screen gradients are whole
            // only where a pixel's neighbours take the same path
            float lines = graticule(n);
            if (kind == GLOBE_WATER) d = d + (look.graticule.rgb - d) * (lines * look.graticule.a * w.z);
        }
        float sine = dot(n, look.sun.xyz);
        float soft = max(length(float2(ddx(sine), ddy(sine))), look.sun.w);
        d = d + (look.night.rgb - d) * globe_night(look, sine, soft);
        c = c + (d - c) * w.x;
    }
    o.colour = float4(c * alpha, alpha);
    return o;
}

struct LightOut {
    float4 position : SV_Position;
    [[vk::location(0)]] nointerpolation float4 colour : COLOR0; // the light's colour and opacity
    [[vk::location(1)]] nointerpolation float2 radii : RADII0;  // its radius and its halo's (device px)
    [[vk::location(2)]] float2 from : FROM0;                    // the fragment from the light's middle (device px)
};

[shader("vertex")]
LightOut light_vs(uint vertex : SV_VulkanVertexID, uint light : SV_VulkanInstanceID) {
    LightOut o;
    o.colour = float4(0.0f, 0.0f, 0.0f, 0.0f);
    o.radii = float2(0.0f, 0.0f);
    o.from = float2(0.0f, 0.0f);
    o.position = nowhere;
    FrameCamera c = frame.camera;
    uint base = push.at.x + push.at.w + light * 2u;
    if (in_parts()) {
        Part p = part_of(3u, light);
        base = p.word + 4u * p.triangles + 3u * p.segments + 2u * (light - p.first);
    }
    uint what = world_words[base + 1u];
    if (look.weights.x <= 0.0f || !drawn((what >> 8u) & 0xFFu)) return o;
    Ground g = ground_of(world_words[base]);
    float3 k = camera_of(c, g.p);
    if (k.z < c.frame.w) return o;
    // the board's rule: how deep the night is there, and how far the surface's normal in the view faces us; none
    // behind the horizon
    if (!faces_eye(g)) return o;
    float3 n = normalize(g.from_centre);
    float facing = -dot(n, c.forward.xyz);
    float altitude = degrees(asin(clamp(dot(n, look.sun.xyz), -1.0f, 1.0f)));
    float night = globe_light(look, altitude, facing);
    if (facing < look.light_least.x || night < look.light_least.y) return o;
    float4 colour = globe_light_colour(look, night);
    o.colour = float4(colour.rgb, colour.a * look.weights.x * push.scale.z);
    uint size = min(what & 0xFFu, 2u);
    float r = look.light_at[size];
    o.radii = float2(r, r * look.light_at.w);
    uint corner = line_corners[vertex % 6u];
    float reach = o.radii.y + 1.0f;
    o.from = float2((corner & 1u) != 0u ? reach : -reach, corner >= 2u ? reach : -reach);
    float2 s = css_of(c, k) * c.frame.z + o.from;
    float2 at = {s.x * c.device.x - 1.0f, s.y * c.device.y - 1.0f};
    float2 t = {c.turn.x * at.x + c.turn.y * at.y, c.turn.z * at.x + c.turn.w * at.y};
    o.position = float4(t.x, t.y, push.origin.w, 1.0f);
    return o;
}

[shader("fragment")]
ColourOut light_fs(LightOut v) {
    ColourOut o;
    float d = length(v.from);
    // the halo, then the light over it
    float halo = v.colour.a * look.light_rule.x * saturate(v.radii.y + 0.5f - d);
    float core = v.colour.a * saturate(v.radii.x + 0.5f - d);
    float alpha = core + halo * (1.0f - core);
    o.colour = float4(v.colour.rgb * alpha, alpha);
    return o;
}

/// The Globe board's frame (CSS px): its backdrop's stars and band are placed in it.
static const float2 BOARD_FRAME = {412.0f, 915.0f};

/// The board's frame in the engine's, as the board's pictures fit a screen (CosmosArt's inBoardFrame): scaled by the
/// lesser of the two sides' ratios and centred. xy: where the board's corner lies (CSS px); z: the scale.
float3 board_fit(FrameCamera c) {
    float s = min(c.frame.x / BOARD_FRAME.x, c.frame.y / BOARD_FRAME.y);
    return float3((c.frame.xy - BOARD_FRAME * s) * 0.5f, s);
}

/// The planet's disc at the CSS point `css`: the point's place from the disc's middle in radii of the disc (1 on the
/// limb), by the tangents of its ray's angle and the limb's; far beyond where the ray looks away from the planet.
float disc_rho(FrameCamera c, float2 css) {
    float kx = (css.x - c.right.w) / c.forward.w;
    float ky = -(css.y - c.up.w) / c.forward.w;
    float3 ray = normalize(c.right.xyz * kx + c.up.xyz * ky + c.forward.xyz);
    float3 centre = look.centre.xyz;
    float radius = look.centre.w;
    float toward = dot(ray, centre);
    float beside = length(cross(ray, centre));
    float far = sqrt(max(dot(centre, centre) - radius * radius, 1.0f)); // the eye to the limb
    return toward > 0.0f ? (beside / toward) * (far / radius) : 1e9f;
}

/// How much of the atmosphere's ring lies at `rho` radii from the disc's middle, 0 to 1 (the board's: rising from its
/// inner edge to its brightest, falling to its outer edge).
float air_cover(float rho) {
    float air = 0.0f;
    if (rho > look.air_at.x && rho < look.air_at.y) {
        air = (rho - look.air_at.x) / (look.air_at.y - look.air_at.x);
    } else if (rho >= look.air_at.y && rho < look.air_at.z) {
        air = (look.air_at.z - rho) / (look.air_at.z - look.air_at.y);
    }
    return air;
}

struct StarOut {
    float4 position : SV_Position;
    [[vk::location(0)]] nointerpolation float2 look : LOOK0; // the star's radius (device px) and its opacity
    [[vk::location(1)]] float2 from : FROM0;                 // the fragment from the star's middle (device px)
};

[shader("vertex")]
StarOut star_vs(uint vertex : SV_VulkanVertexID, uint star : SV_VulkanInstanceID) {
    StarOut o;
    FrameCamera c = frame.camera;
    float4 s = backdrop_stars[star % BACKDROP_STARS];
    float3 fit = board_fit(c);
    float2 css = fit.xy + s.xy * fit.z;
    float r = s.z * fit.z * c.frame.z;
    // (the atmosphere's ring lies over the board's stars: a star within it shows through what the ring leaves)
    float under_air = 1.0f - air_cover(disc_rho(c, css)) * look.air.a * look.weights.z;
    o.look = float2(r, s.w * look.weights.z * look.weights.w * under_air);
    uint corner = line_corners[vertex % 6u];
    float reach = r + 1.0f;
    o.from = float2((corner & 1u) != 0u ? reach : -reach, corner >= 2u ? reach : -reach);
    float2 px = css * c.frame.z + o.from;
    float2 at = {px.x * c.device.x - 1.0f, px.y * c.device.y - 1.0f};
    float2 t = {c.turn.x * at.x + c.turn.y * at.y, c.turn.z * at.x + c.turn.w * at.y};
    o.position = float4(t.x, t.y, push.origin.w, 1.0f);
    return o;
}

[shader("fragment")]
ColourOut star_fs(StarOut v) {
    ColourOut o;
    float alpha = v.look.y * saturate(v.look.x + 0.5f - length(v.from));
    o.colour = float4(alpha, alpha, alpha, alpha);
    return o;
}

/// The Globe board's Milky Way band over space at the board's point `p` (its 412 x 915 frame): the board's path, from
/// (−40, 260) by the cubic through (120, 330) and (260, 520) to (460, 700) and 120 px down from it, filled by its
/// linear gradient from (−40, 260) to (460, 820): #8FA8FF at 0, #B9A8FF at 7 % (0x12 of 255) half way, #8FA8FF at 0.
/// Premultiplied; nothing outside the path.
float4 backdrop_band(float2 p) {
    if (p.x < -40.0f || p.x > 460.0f) {
        return float4(0.0f, 0.0f, 0.0f, 0.0f);
    }
    // the curve's parameter at the column: x(t) rises along it, so Newton's steps from the chord's guess
    float t = (p.x + 40.0f) / 500.0f;
    for (uint i = 0u; i < 4u; ++i) {
        float u = 1.0f - t;
        float x = -40.0f * u * u * u + 360.0f * u * u * t + 780.0f * u * t * t + 460.0f * t * t * t;
        float dx = 480.0f * u * u + 840.0f * u * t + 600.0f * t * t;
        t = saturate(t - (x - p.x) / dx);
    }
    float u = 1.0f - t;
    float top = 260.0f * u * u * u + 990.0f * u * u * t + 1560.0f * u * t * t + 700.0f * t * t * t;
    float cover = saturate(p.y - top + 0.5f) * saturate(top + 120.0f - p.y + 0.5f);
    float along = ((p.x + 40.0f) * 500.0f + (p.y - 260.0f) * 560.0f) / 563600.0f;
    float mid = saturate(1.0f - abs(2.0f * along - 1.0f));
    float3 colour = float3(0.5608f, 0.6588f, 1.0f) + (float3(0.7255f, 0.6588f, 1.0f) - float3(0.5608f, 0.6588f, 1.0f)) * mid;
    float alpha = (18.0f / 255.0f) * mid * cover;
    return float4(colour * alpha, alpha);
}

struct GlobeOut {
    float4 position : SV_Position;
    [[vk::location(0)]] float2 at : AT0; // the fragment, upright device px
};

[shader("vertex")]
GlobeOut globe_vs(uint vertex : SV_VulkanVertexID) {
    GlobeOut o;
    FrameCamera c = frame.camera;
    float2 n = {vertex == 1u ? 3.0f : -1.0f, vertex == 2u ? 3.0f : -1.0f};
    o.at = float2((n.x + 1.0f) / c.device.x, (n.y + 1.0f) / c.device.y);
    float2 t = {c.turn.x * n.x + c.turn.y * n.y, c.turn.z * n.x + c.turn.w * n.y};
    o.position = float4(t.x, t.y, push.origin.w, 1.0f);
    return o;
}

[shader("fragment")]
ColourOut globe_fs(GlobeOut v) {
    ColourOut o;
    FrameCamera c = frame.camera;
    float4 w = look.weights;
    // the pixel's ray from the eye, and the planet's disc about its centre's direction: rho the ray's place from the
    // disc's middle in radii of the disc (1 on the limb), by the tangents of its angle and the limb's
    float2 css = v.at * c.device.z;
    float kx = (css.x - c.right.w) / c.forward.w;
    float ky = -(css.y - c.up.w) / c.forward.w;
    float3 ray = normalize(c.right.xyz * kx + c.up.xyz * ky + c.forward.xyz);
    float3 centre = look.centre.xyz;
    float radius = look.centre.w;
    float toward = dot(ray, centre);
    float beside = length(cross(ray, centre));
    float far = sqrt(max(dot(centre, centre) - radius * radius, 1.0f)); // the eye to the limb
    float rho = toward > 0.0f ? (beside / toward) * (far / radius) : 1e9f;
    float disc_px = 1.0f / max(length(float2(ddx(rho), ddy(rho))), 1e-9f); // the disc's radius on the screen
    float edge_px = (rho - 1.0f) * disc_px;                                // from the limb, outward
    if (push.at.x == 0u) {
        // space where the sky was: its glow about the frame's middle, then the atmosphere's ring and the outline
        float glow = saturate(length(v.at - look.space_at.xy) * look.space_at.z / look.space_glow.a);
        float3 colour = look.space_glow.rgb + (look.space.rgb - look.space_glow.rgb) * glow;
        if (w.z > 0.0f) {
            // the board's Milky Way band behind everything (its stars are star_fs's)
            float3 fit = board_fit(c);
            float4 band = backdrop_band((css - fit.xy) / fit.z) * w.z;
            colour = band.rgb + colour * (1.0f - band.a);
            float air = air_cover(rho);
            colour = colour + (look.air.rgb - colour) * (air * look.air.a * w.z);
            // the outline's dashes along the limb, from the disc's right, clockwise
            float2 on_screen = {dot(ray, c.right.xyz) - dot(normalize(centre), c.right.xyz),
                                dot(normalize(centre), c.up.xyz) - dot(ray, c.up.xyz)};
            float turned = atan2(on_screen.y, on_screen.x);
            float along = (turned < 0.0f ? turned + 6.283185307f : turned) * disc_px;
            float period = look.outline_at.y + look.outline_at.z;
            float dash = along - period * floor(along / period) < look.outline_at.y ? 1.0f : 0.0f;
            float line = saturate(look.outline_at.x + 0.5f - abs(edge_px)) * dash;
            colour = colour + (look.outline.rgb - colour) * (line * look.outline.a * w.z);
        }
        o.colour = float4(colour * w.w, w.w);
        return o;
    }
    // the glass over the disc: the shine from the upper left, and the rim on the limb
    float4 glass = float4(0.0f, 0.0f, 0.0f, 0.0f);
    if (rho < 1.0f) {
        float reach = toward - sqrt(max(radius * radius - beside * beside, 0.0f));
        float3 n = (ray * reach - centre) / radius;
        float2 on_disc = {dot(n, c.right.xyz), dot(n, c.up.xyz)};
        float shine = saturate(1.0f - length(on_disc - look.shine_at.xy) * look.shine_at.z) * look.shine.a;
        shine *= saturate(0.5f - edge_px);
        glass = float4(look.shine.rgb * shine, shine);
    }
    float rim = saturate(look.shine_at.w + 0.5f - abs(edge_px)) * look.rim.a;
    glass = float4(look.rim.rgb * rim, rim) + glass * (1.0f - rim);
    o.colour = glass * w.z;
    return o;
}
