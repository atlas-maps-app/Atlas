// The frame's detail choices by distance (plan-vector D9, "Detail by distance"; D6): the renders draw an item's details
// by how far it is (a wall's cells within 460 m, windows within win_d, deep windows within 160 m, plinths within 760 m,
// ...). Each item kind has up to three distance thresholds, ascending, which split its depth into four detail bands
// (band 0 the nearest, with the most detail). The passes that draw a kind set its thresholds for each frame from the
// frame's settings (Pass::select, before the ranks), in double as the port computes them; the ranks choose each
// item's band once, from its depth in double against them as the port compares, and give it that band's painter
// slots. The band goes to the GPU in the rank table beside the item's first slot (painter.slang's rank_band): no
// shader recomputes it from a float depth, which can fall on the other side of a threshold. Set 0's selector table
// (binding 2: one float4 per kind, the thresholds in xyz rounded to float) serves only choices that are not per item.
//
// Kinds are registered once, as the frame builder is made (Pass::kinds), so stage T writes each item's row
// (ItemSpec::kind) before any tile is built; rows never change, and a frame only sets their thresholds.
#pragma once

#include "base/hlsl.hpp"

#include <array>
#include <cstdint>
#include <limits>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::frame {

/// The selector table's kinds and their thresholds.
class Selectors {
public:
    /// The most kinds the table holds.
    static constexpr std::uint16_t max_kinds = 256;
    /// A threshold that never splits (a kind with fewer than three).
    static constexpr double never = std::numeric_limits<double>::infinity();

    Selectors();

    /// The row of the kind named `name` ("building", "tree", ...), registered the first time it is asked for.
    [[nodiscard]] std::uint16_t kind(std::string_view name);
    /// The kind's name.
    [[nodiscard]] std::string_view name(std::uint16_t kind) const noexcept;
    /// The registered kinds.
    [[nodiscard]] std::size_t size() const noexcept { return names_.size(); }

    /// Sets kind `kind`'s thresholds for the frame (metres, ascending; `never` for the ones it lacks), as the port
    /// computes them.
    void set(std::uint16_t kind, double near, double middle = never, double far = never) noexcept;
    /// The detail band (0 to 3) of an item of kind `kind` at depth `depth`: how many of its thresholds it lies at or
    /// beyond, compared in double.
    [[nodiscard]] std::size_t band(std::uint16_t kind, double depth) const noexcept {
        const std::array<double, 3>& t = thresholds_[kind];
        return static_cast<std::size_t>(depth >= t[0]) + static_cast<std::size_t>(depth >= t[1]) +
               static_cast<std::size_t>(depth >= t[2]);
    }
    /// Kind `kind`'s thresholds, in double.
    [[nodiscard]] const std::array<double, 3>& thresholds(std::uint16_t kind) const noexcept {
        return thresholds_[kind];
    }
    /// The table set 0 holds: per kind its thresholds rounded to float (xyz) and 0.
    [[nodiscard]] std::span<const hlsl::float4> table() const noexcept { return {table_.data(), names_.size()}; }

private:
    std::vector<std::string> names_;
    std::array<std::array<double, 3>, max_kinds> thresholds_{};
    std::array<hlsl::float4, max_kinds> table_{};
};

} // namespace atlas::frame
