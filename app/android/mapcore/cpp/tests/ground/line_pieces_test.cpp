// A polyline of the ground's lines in the runs that draw it (passes/LinePieces.hpp, passes/Lines.cpp's write_lines): a
// segment's instance word holds 14 bits of its run's segment, so within 16,384 segments one run holds the whole line,
// its words as before; past them, pieces end to end, each later one from a point back. Every segment is drawn once,
// by a run's segment its word holds, and each segment's joins are the whole line's: the line stage joins a run's
// segment to the one before it where the segment is over 0, and to the next where segment + 2 is under the run's count.
#include "passes/LinePieces.hpp"

#include <doctest/doctest.h>

#include <cstdint>
#include <vector>

using atlas::passes::line_pieces;
using atlas::passes::LinePiece;
using atlas::passes::next_line_piece;
using atlas::passes::run_segments_most;

TEST_CASE("lines: a polyline within a run's segments is one run, the whole line's") {
    CHECK(line_pieces(0).empty());
    CHECK(line_pieces(1) == std::vector<LinePiece>{{.from = 0, .points = 2, .first = 0, .segments = 1}});
    CHECK(line_pieces(1000) == std::vector<LinePiece>{{.from = 0, .points = 1001, .first = 0, .segments = 1000}});
    CHECK(line_pieces(16'384) == std::vector<LinePiece>{{.from = 0, .points = 16'385, .first = 0, .segments = 16'384}});
}

TEST_CASE("lines: a polyline past a run's segments is drawn in pieces, each after the first from a point back") {
    // one segment more: the first piece takes the point after its last segment's end, the second the point before
    // its one segment's start
    CHECK(line_pieces(16'385) == std::vector<LinePiece>{{.from = 0, .points = 16'386, .first = 0, .segments = 16'384},
                                                        {.from = 16'383, .points = 3, .first = 1, .segments = 1}});
    // a park's edge of 20,001 points
    CHECK(line_pieces(20'000) ==
          std::vector<LinePiece>{{.from = 0, .points = 16'386, .first = 0, .segments = 16'384},
                                 {.from = 16'383, .points = 3618, .first = 1, .segments = 3616}});
    // a later piece holds a segment fewer, its run's segment 0 the join's point alone
    CHECK(line_pieces(32'767).size() == 2);
    CHECK(line_pieces(32'767).back() == LinePiece{.from = 16'383, .points = 16'385, .first = 1, .segments = 16'383});
    CHECK(line_pieces(32'768).size() == 3);
    CHECK(line_pieces(32'768).back() == LinePiece{.from = 32'766, .points = 3, .first = 1, .segments = 1});
    // runs of 97, as a test cuts a line: ten cuts in 1,000 segments
    CHECK(line_pieces(1000, 97).size() == 11);
    CHECK(next_line_piece(line_pieces(1000, 97).back(), 1000, 97) == LinePiece{});
}

TEST_CASE("lines: every segment of a polyline in pieces is drawn once, within its word, with the whole line's joins") {
    for (const std::uint32_t most : {2U, 3U, 7U, 97U, 1000U, run_segments_most}) {
        for (const std::uint32_t n :
             {1U,   2U,   3U,   4U,    5U,    6U,      7U,      8U,      13U,     96U,     97U,     98U,
              193U, 194U, 999U, 1000U, 1001U, 16'383U, 16'384U, 16'385U, 20'000U, 32'767U, 32'768U, 50'000U}) {
            CAPTURE(most);
            CAPTURE(n);
            const std::vector<LinePiece> pieces = line_pieces(n, most);
            REQUIRE(!pieces.empty());
            CHECK(pieces.size() == (n <= most ? 1 : 1 + (((n - most) + (most - 2)) / (most - 1))));
            std::uint32_t next = 0; // the polyline's segment the next piece starts at
            bool in_order = true;
            bool in_word = true;
            bool joins = true;
            for (const LinePiece& p : pieces) {
                // end to end, each later one from a point back; its own segments within the most a run draws (and an
                // instance word's 14 bits), its points within the polyline's
                in_order = in_order && p.segments > 0 && p.from + p.first == next && p.first == (next == 0 ? 0U : 1U);
                in_word = in_word && p.first + p.segments <= most && p.first + p.segments - 1 <= 0x3FFFU &&
                          p.from + p.points <= n + 1;
                for (std::uint32_t k = p.first; k < p.first + p.segments; ++k) {
                    const std::uint32_t s = p.from + k; // the polyline's segment its run's k is
                    // its points in the run, and its neighbours where the whole line has them
                    joins = joins && k + 1 < p.points && (k > 0) == (s > 0) && (k + 2 < p.points) == (s + 2 < n + 1);
                }
                next += p.segments;
            }
            CHECK(in_order);
            CHECK(in_word);
            CHECK(joins);
            CHECK(next == n);
        }
    }
}
