// The frame's counters (plan-vector §3, D10, K7): what each frame issues, per pass and per feature kind, so CI gates
// the draws, triangles and instances a commit would cost the phone without a phone (§3: at most 300 draws, 600k
// triangles and 30k instances while moving), and the overdraw mode's shaded fragments (at most 1.6 FSE of opaque
// fragments: the frame replayed with an additive 1/255 output, FrameBuilder's overdraw option). Passes count as they
// record (Recording::counters); the frame builder sums them. Plain data: nothing allocates per frame.
#pragma once

#include "base/arena.hpp"

#include <cstdint>
#include <span>
#include <string>
#include <string_view>

namespace atlas::frame {

/// What a frame, a pass or a feature kind issued.
struct Count {
    std::uint32_t draws = 0;
    std::uint32_t dispatches = 0;
    std::uint64_t triangles = 0;
    std::uint64_t instances = 0;

    Count& operator+=(const Count& o) noexcept {
        draws += o.draws;
        dispatches += o.dispatches;
        triangles += o.triangles;
        instances += o.instances;
        return *this;
    }
    friend constexpr bool operator==(const Count&, const Count&) = default;
};

/// One feature kind's count within a pass ("walls", "roofs", "ground", ...: a name the pass gives, which lives as long
/// as the pass).
struct KindCount {
    std::string_view kind;
    Count count;
};

/// The most feature kinds a pass counts apart (the buildings pass's close frames count 22: its cull, its roofs' edges
/// and its four windows' decide stages, its walls, roofs, windows' and facades' parts, its translucent lines).
inline constexpr std::size_t max_kinds = 24;

/// One pass's counts for a frame.
class PassCounters {
public:
    explicit PassCounters(std::string_view pass = {}) noexcept : pass_(pass) {}

    /// A draw of `kind`: `triangles` triangles in all, over `instances` instances (1 for a plain draw).
    void draw(std::string_view kind, std::uint64_t triangles, std::uint64_t instances = 1) noexcept;
    /// A compute dispatch of `kind`.
    void dispatch(std::string_view kind) noexcept;
    /// Forgets the frame's counts (the frame builder, before a frame).
    void clear() noexcept;

    [[nodiscard]] std::string_view pass() const noexcept { return pass_; }
    [[nodiscard]] const Count& total() const noexcept { return total_; }
    [[nodiscard]] std::span<const KindCount> kinds() const noexcept { return kinds_.span(); }

private:
    Count& of(std::string_view kind) noexcept;

    std::string_view pass_;
    Count total_;
    InplaceVector<KindCount, max_kinds> kinds_;
};

/// §3's budgets for a frame while moving (the CI gates on the counters).
struct Budget {
    std::uint32_t draws = 300;
    std::uint64_t triangles = 600'000;
    std::uint64_t instances = 30'000;
    double opaque_fse = 1.6; ///< opaque shaded fragments, full-screen equivalents (the overdraw mode)
};

/// Why a frame's counts are over the budget ("" within it).
[[nodiscard]] std::string over_budget(const Count& count, const Budget& budget = {});

/// "draws <n> dispatches <n> triangles <n> instances <n>".
[[nodiscard]] std::string text_of(const Count& count);

} // namespace atlas::frame
