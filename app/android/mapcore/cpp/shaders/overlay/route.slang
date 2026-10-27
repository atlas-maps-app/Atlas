// The app's routes and areas (ENGINE.md step 6; passes/Routes.cpp): what lies on the ground under the map's symbols,
// drawn by the core in the map's own frame after the composite and before the labels, so it moves with the map in the
// same frame, as mapview's OverlayPainter.drawGround draws it over the canvas. Each pixel's ray meets the street, and
// what it shows is measured there, in the frame's metres:
//   - an area: its fill within its radius and a 1.5 dp line round it (AreaPaths' circle and the painter's stroke);
//   - a piece of a route's ribbon (a segment of a run of it in front of the eye and short of the haze, its casing or
//     its fill): the street within its half-width of the segment, never thinner than its least half-width in device px
//     where the ribbon runs far away (RouteRibbons.halfWidth), with round caps; a piece that meets the one before or
//     after it in its run leaves a pixel nearer that one to it, so their round joins and the pixels they share draw
//     once (as the painter's mitred quads and discs make one ribbon); dashed, only the dashes of its period (the
//     nearest point of the nearest dash); its colour before the distance travelled and its faded colour after it, the
//     colour's part over the faded one with its round cap (the painter's two pieces, the second drawn over the first);
//   - a route's chip: the sprite the app drew of it, on whole pixels, texel for texel.
// An edge's coverage is the pixel's distance from it, as Skia's antialiased paths; the colours are sRGB values over
// sRGB values, premultiplied, as the canvas blends them.
//
// The draws and the chips' texels are the pass's own set (2): the frame slot's draws, and the slot's copy of the set's
// chips, every chip's rows one after another, premultiplied RGBA8, R in the low byte.
//
// Variants: loc1 writes colour attachment 1, where the composite writes the dynamic route's 1x output beside the
// scene's colour (shaders/frame/composite_x1_loc1.slang).
// atlas: variant loc1 OVERLAY_LOC1=1

#include "shared/coverage.slang"
#include "shared/frame.slang"

#ifndef OVERLAY_LOC1
#define OVERLAY_LOC1 0
#endif

/// One draw: a piece of a ribbon, an area, or a chip.
struct RouteDraw {
    float4 a;     // a piece: its start on the street, relative to the eye (frame metres; y the street's), and its
                  // distance along the route (w, metres); an area: its centre, and its radius (w, metres)
    float4 b;     // a piece: its end, and its distance along the route (w)
    float4 width; // a piece: its half-width (x, metres) and least half-width (y, device px), its dashes' period and
                  // length (z, w, metres; 0: no dashes); an area: its line's half-width (x, device px)
    float4 box;   // the quad: left, top, right, bottom (the view's device px); a chip: its sprite's top-left and size
    float4 more;  // a piece: the distance travelled (x, metres)
    uint4 look;   // a piece: its colour (x) and faded colour (y, ARGB), its joins (z: ROUTE_PREV, ROUTE_NEXT); an area:
                  // its fill (x) and line (y, ARGB); a chip: its first texel (x), width (y) and height (z); w: the kind
};

[[vk::binding(0, 0)]] ConstantBuffer<FrameBlock> frame;
[[vk::binding(0, 2)]] StructuredBuffer<RouteDraw> route_draws;
[[vk::binding(1, 2)]] StructuredBuffer<uint> route_texels;

/// The kinds of draw.
static const uint ROUTE_PIECE = 0u;
static const uint ROUTE_AREA = 1u;
static const uint ROUTE_CHIP = 2u;
/// A piece's joins: it meets the piece before it in the draws, or the one after it.
static const uint ROUTE_PREV = 1u;
static const uint ROUTE_NEXT = 2u;

/// Farther than anything on the view: no part there.
static const float route_none = 1e30f;

struct RouteOut {
    float4 position : SV_Position;
    nointerpolation uint draw : TEXCOORD0;
};

static const float2 route_corners[6] = { float2(0, 0), float2(1, 0), float2(0, 1), float2(0, 1), float2(1, 0), float2(1, 1) };

[shader("vertex")]
RouteOut route_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    RouteDraw d = route_draws[instance];
    FrameCamera c = frame.camera;
    float2 lo = d.box.xy;
    float2 size = d.look.w == ROUTE_CHIP ? d.box.zw : d.box.zw - d.box.xy;
    float2 px = lo + route_corners[vertex] * size;
    // The view's device px to its normalised coordinates, then the output's pre-rotation, as the labels' sprites.
    float2 n = {px.x * c.device.x - 1.0f, px.y * c.device.y - 1.0f};
    RouteOut o;
    o.position = float4(c.turn.x * n.x + c.turn.y * n.y, c.turn.z * n.x + c.turn.w * n.y, 0.0f, 1.0f);
    o.draw = instance;
    return o;
}

struct RouteColour {
#if OVERLAY_LOC1
    [[vk::location(1)]] float4 colour : SV_Target1;
#else
    [[vk::location(0)]] float4 colour : SV_Target0;
#endif
};

/// An ARGB colour premultiplied, at opacity `a` more.
float4 route_colour(uint argb, float a) {
    float alpha = float(argb >> 24u) / 255.0f * a;
    float3 rgb = float3(float((argb >> 16u) & 0xFFu), float((argb >> 8u) & 0xFFu), float(argb & 0xFFu)) / 255.0f;
    return float4(rgb * alpha, alpha);
}

/// The nearest point to `p` of a piece from `a` to `b` (on the street; distances along the route `sa`, `sb`), of its
/// part between `lo` and `hi` along the route, and of its dashes there (`period`, `dash`; none for a period of 0): its
/// distance from `p` (route_none where that part has none), and the distance along the route of the dash's end (or of
/// the part's).
float2 route_nearest(float2 p, float2 a, float2 b, float sa, float sb, float lo, float hi, float period, float dash) {
    float s0 = max(sa, lo);
    float s1 = min(sb, hi);
    if (s0 > s1) {
        return float2(route_none, 0.0f);
    }
    float2 ab = b - a;
    float l2 = dot(ab, ab);
    float u = l2 > 0.0f ? saturate(dot(p - a, ab) / l2) : 0.0f;
    float s = clamp(sa + (sb - sa) * u, s0, s1);
    float end = s1;
    if (period > 0.0f) {
        // the dash about s: [k period, k period + dash]; in a gap, the nearer of its end and the next one's start,
        // each where it lies within the part
        float k = floor(s / period);
        float d0 = k * period;
        float d1 = d0 + dash;
        if (s > d1) {
            float next = d0 + period;
            bool before = d1 >= s0;
            bool after = next <= s1;
            if (!before && !after) {
                return float2(route_none, 0.0f);
            }
            s = before && (!after || s - d1 <= next - s) ? d1 : next;
            d0 = s == next ? next : d0;
            d1 = d0 + dash;
        }
        end = min(d1, s1);
    }
    float t = sb > sa ? (s - sa) / (sb - sa) : 0.0f;
    return float2(length(p - (a + ab * t)), end);
}

/// A piece of a ribbon at the street point `p` (relative to the eye, on the street), `m` metres a device px there.
float4 route_piece(uint i, RouteDraw d, float2 p, float m) {
    float hw = max(d.width.x, d.width.y * m);
    float period = d.width.z;
    float dash = d.width.w;
    // every distance first (their derivatives need every pixel of the quad): the piece's own, its neighbours', and
    // its parts' before and after the distance travelled
    float2 own = route_nearest(p, d.a.xz, d.b.xz, d.a.w, d.b.w, -route_none, route_none, period, dash);
    float before = route_none;
    float after = route_none;
    if ((d.look.z & ROUTE_PREV) != 0u) {
        RouteDraw e = route_draws[i - 1u];
        before = route_nearest(p, e.a.xz, e.b.xz, e.a.w, e.b.w, -route_none, route_none, period, dash).x;
    }
    if ((d.look.z & ROUTE_NEXT) != 0u) {
        RouteDraw e = route_draws[i + 1u];
        after = route_nearest(p, e.a.xz, e.b.xz, e.a.w, e.b.w, -route_none, route_none, period, dash).x;
    }
    float travelled = d.more.x;
    float2 faded = route_nearest(p, d.a.xz, d.b.xz, d.a.w, d.b.w, -route_none, travelled, period, dash);
    float2 coloured = route_nearest(p, d.a.xz, d.b.xz, d.a.w, d.b.w, travelled, route_none, period, dash);
    float px = max(fwidth(min(own.x, hw * 4.0f)), 1e-6f);
    // a pixel nearer the piece before or after this one is that one's (the one before takes a tie)
    if (before <= own.x || after < own.x) {
        return float4(0.0f);
    }
    if (period > 0.0f) {
        // a dash ending at or before the distance travelled is faded, the others are the colour (the painter's pieces)
        float4 c = route_colour(own.y <= travelled ? d.look.y : d.look.x, 1.0f);
        return c * coverage_edge((hw - own.x) / px);
    }
    float4 o = route_colour(d.look.y, coverage_edge((hw - faded.x) / px));
    return premultiplied_over(route_colour(d.look.x, coverage_edge((hw - coloured.x) / px)), o);
}

/// An area at the street point `p`: its fill within its radius, and its line over it.
float4 route_area(RouteDraw d, float2 p) {
    float r = length(p - d.a.xz);
    float px = max(fwidth(r), 1e-6f);
    float inside = (d.a.w - r) / px;
    float4 o = route_colour(d.look.x, coverage_edge(inside));
    float line = d.width.x - abs(inside);
    return premultiplied_over(route_colour(d.look.y, coverage_edge(line)), o);
}

/// A chip's texel under the view's point `view`.
float4 route_texel(RouteDraw d, float2 view) {
    int2 texel = int2(floor(view - d.box.xy));
    if (texel.x < 0 || texel.y < 0 || texel.x >= int(d.look.y) || texel.y >= int(d.look.z)) {
        return float4(0.0f);
    }
    uint n;
    uint stride;
    route_texels.GetDimensions(n, stride);
    uint i = d.look.x + uint(texel.y) * d.look.y + uint(texel.x);
    uint t = i < n ? route_texels[i] : 0u;
    return float4(float(t & 0xFFu), float((t >> 8u) & 0xFFu), float((t >> 16u) & 0xFFu), float(t >> 24u)) / 255.0f;
}

[shader("fragment")]
RouteColour route_fs(RouteOut v) {
    RouteDraw d = route_draws[v.draw];
    FrameCamera c = frame.camera;
    // the view's point under this fragment (a pixel's centre), in its device px
    float2 view = css_of_output(c, v.position.xy) * c.frame.z;
    RouteColour o;
    if (d.look.w == ROUTE_CHIP) {
        o.colour = route_texel(d, view);
        return o;
    }
    // the pixel's ray (camera coordinates, then eye-relative frame metres), where it meets the street, and the metres a
    // device px spans there (its depth over the focal length in device px)
    float2 css = view / c.frame.z;
    float3 k = {(css.x - c.right.w) / c.forward.w, (c.up.w - css.y) / c.forward.w, 1.0f};
    float3 ray = k.x * c.right.xyz + k.y * c.up.xyz + k.z * c.forward.xyz;
    float t = ray.y < 0.0f ? d.a.y / ray.y : 0.0f;
    float2 p = ray.xz * t;
    float m = t / (c.forward.w * c.frame.z);
    float4 colour = d.look.w == ROUTE_AREA ? route_area(d, p) : route_piece(v.draw, d, p, m);
    o.colour = t > 0.0f ? colour : float4(0.0f);
    return o;
}
