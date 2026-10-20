// The renders' line geometry on the ground (plan-vector D10 "Roads", "Region overlays", "Tram tracks"; K9), in the
// port's arithmetic and order, contraction off, so every point is the port's bit for bit:
//   - ribbon: render/city.py:116 ribbon (R/City.kt ribbon), the left side then the right side reversed, each point
//     moved by the averaged normal (the neighbours' difference at an inner point) times w / 2 (CPython's hypot, its
//     zero length taken as 1). Roads draw ribbon(pts, w / 2), so their ribbon is half the mapped width
//     (amsterdam.py:688).
//   - offset_line: render/regions.py:512 offset_line, a polyline moved sideways by `off` (left positive).
//   - dashes: render/regions.py:530 dashes, world-metre dashes along a polyline, `on` painted and `off` gap, measured
//     along the whole line from its start, each dash a segment of its own.
//   - near_radius: render/regions.py:551 draw_over's `near`, the engine's radius cut for a line (any of every
//     len // 8th point and the last within the radius).
// Everything here is plain data in double: stage T keeps it and the frame projects it (the ground's per-frame
// decisions, passes/Ground.cpp).
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <vector>

namespace atlas::geometry {

/// A point on the ground: x east, z south, metres in the pack's frame.
struct P2 {
    double x = 0;
    double z = 0;
    friend constexpr bool operator==(const P2&, const P2&) = default;
};

/// city.py's ribbon(pts, w): the left side (each point plus its unit normal times w / 2), then the right side
/// reversed, 2·n points. Appends to `out`. `pts` has at least 2 points (the renders' roads and lines do).
void ribbon(std::span<const P2> pts, double w, std::vector<P2>& out);

/// regions.py's offset_line(pts, off): each point moved by `off` along the left normal of its neighbours' difference
/// (the clamped neighbours at the ends). Appends to `out`.
void offset_line(std::span<const P2> pts, double off, std::vector<P2>& out);

/// One dash of `dashes`: its two ends, in order along the line.
struct Dash {
    P2 a;
    P2 b;
};

/// regions.py's dashes(cam, pts, y, on, off) before its projection: every dash along `pts` (on metres painted, off
/// metres gap, the pattern measured along the whole line), in order. The camera only drops a dash with an end behind
/// the near plane, which the frame decides. Appends to `out`.
void dashes(std::span<const P2> pts, double on, double off, std::vector<Dash>& out);

/// draw_over's `near(pts)`: whether any of pts[::max(1, len // 8)] and the last point lies within `radius` of
/// (tx, tz) (CPython's hypot, the port's), the engine's radius cut for the region's lines.
[[nodiscard]] bool near_radius(std::span<const P2> pts, double tx, double tz, double radius) noexcept;

/// The box (x0, x1, z0, z1) of the points draw_over's `near` samples (near_radius's): what a frame tests first.
[[nodiscard]] std::array<double, 4> near_box(std::span<const P2> pts) noexcept;

/// render/real.py:79 signed_area: half the shoelace sum of a ring, summed in order (Python's float addition).
[[nodiscard]] double signed_area(std::span<const P2> ring) noexcept;

/// render/real.py:92 centroid: the vertices' means, each a Python sum() in order, over the count.
[[nodiscard]] P2 centroid(std::span<const P2> ring) noexcept;

} // namespace atlas::geometry
