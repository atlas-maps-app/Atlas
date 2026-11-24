// A polyline of the ground's lines in the runs that draw it (passes/Lines.cpp's write_lines): a segment's instance word
// is (run << 14) | segment, 14 bits of its run's segment, so a polyline of more segments than that is drawn in pieces,
// each a run of its own with its own header words. A piece after the first starts one point back, so that its first
// segment has the one before it for its join, and each piece takes the point after its last segment's end where there
// is one, so that segment has the next for its join: across a cut the joins are the whole line's (the line stage's
// LINE_HAS_PREV where its run's segment is over 0, LINE_HAS_NEXT where segment + 2 is under its run's count). A
// polyline within the bound is one piece, its run's words a whole polyline's.
#pragma once

#include "base/assert.hpp"

#include <algorithm>
#include <cstdint>
#include <vector>

namespace atlas::passes {

/// The most segments of a polyline one run draws: an instance word's 14 bits of its segment.
inline constexpr std::uint32_t run_segments_most = 0x4000;

/// A run's piece of a polyline: its points [from, from + points) of the polyline's (the run's first point and its
/// count), and the segments it draws, its own [first, first + segments): the polyline's segment from + k is its k.
struct LinePiece {
    std::uint32_t from = 0;
    std::uint32_t points = 0;
    std::uint32_t first = 0;
    std::uint32_t segments = 0;
    friend constexpr bool operator==(const LinePiece&, const LinePiece&) = default;
};

/// The piece after `before` of a polyline of `n` segments (n + 1 points) drawn in runs of at most `most` segments (2
/// to run_segments_most); the first piece after LinePiece{}, and none (no segments) after the last. The first is of
/// `most` segments at most (all n, from 0 with all n + 1 points, where n is at most `most`); each later one starts one
/// point before its first segment's start, its segments 1 to at most most - 1; each takes the point after its last
/// segment's end where there is one.
[[nodiscard]] constexpr LinePiece next_line_piece(const LinePiece& before, std::uint32_t n,
                                                  std::uint32_t most) noexcept {
    ATLAS_ASSERT(most >= 2 && most <= run_segments_most);
    const std::uint32_t s = before.from + before.first + before.segments; // the polyline's segment it starts at
    if (s >= n) return {};
    const std::uint32_t back = s == 0 ? 0U : 1U;
    const std::uint32_t take = std::min(n - s, most - back);
    const std::uint32_t from = s - back;
    return {.from = from, .points = std::min(n + 1, s + take + 2) - from, .first = back, .segments = take};
}

/// Every piece of a polyline of `n` segments in runs of at most `most`, in order (next_line_piece's).
[[nodiscard]] inline std::vector<LinePiece> line_pieces(std::uint32_t n, std::uint32_t most = run_segments_most) {
    std::vector<LinePiece> out;
    for (LinePiece p = next_line_piece({}, n, most); p.segments > 0; p = next_line_piece(p, n, most)) out.push_back(p);
    return out;
}

} // namespace atlas::passes
