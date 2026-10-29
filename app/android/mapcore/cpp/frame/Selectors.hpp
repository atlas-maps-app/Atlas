// The frame's detail choices by distance (plan-vector D9, "Detail by distance"; D6): the renders draw an item's details
// by how far it is (a wall's cells within 460 m, windows within win_d, deep windows within 160 m, plinths within 760 m,
// ...). Each item kind has two kinds of thresholds, which the passes that draw it set for each frame from the frame's
// settings (Pass::select, before the ranks), in double as the port computes them:
//   - up to three band thresholds, ascending, which split its depth into four bands (band 0 the nearest), each with
//     its own count of painter slots: the ranks give each item its band's;
//   - up to twelve detail thresholds, in the kind's own order, each a condition of the port's (`d < 460`, `d <
//     win_d`): the ranks give each item its detail, bit i set where its depth lies nearer than threshold i, compared
//     in double as the port compares. The detail goes to the GPU in the rank table beside the item's first slot
//     (painter.slang's rank_detail): no shader recomputes it from a float depth, which can fall on the other side of a
//     threshold.
// Set 0's selector table (binding 2: one float4 per kind, the band thresholds in xyz rounded to float) serves only
// choices that are not per item.
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

    /// Sets kind `kind`'s band thresholds for the frame (metres, ascending; `never` for the ones it lacks), as the port
    /// computes them.
    void set(std::uint16_t kind, double near, double middle = never, double far = never) noexcept;
    /// The most detail thresholds a kind has: the rank entry's detail bits but its last, which marks the building the
    /// app asks about (painter.slang's RANK_ASKED).
    static constexpr std::size_t max_details = 11;
    /// Sets kind `kind`'s detail thresholds for the frame (metres, in the kind's order: bit i is threshold i's).
    void set_details(std::uint16_t kind, std::span<const double> thresholds) noexcept;
    /// The detail of an item of kind `kind` at depth `depth`: bit i where it lies nearer than threshold i (not at or
    /// beyond it, so a band's first threshold and its bit agree), compared in double.
    [[nodiscard]] std::uint32_t detail(std::uint16_t kind, double depth) const noexcept {
        std::uint32_t d = 0;
        const std::array<double, max_details>& t = details_[kind];
        for (std::size_t i = 0; i < details_count_[kind]; ++i) d |= static_cast<std::uint32_t>(!(depth >= t[i])) << i;
        return d;
    }
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
    /// Leaves kind `kind`'s items out of the frame (`out`) or takes them in: the ranks give a kind left out no slot, so
    /// none of its items is drawn or decided (the map leaves out the parts that draw them: style::camera_part). Each
    /// pass says it for its kinds in every select; a kind is in until then.
    void leave_out(std::uint16_t kind, bool out) noexcept {
        if (kind < max_kinds) left_out_[kind] = out ? 1U : 0U;
    }
    /// Whether kind `kind`'s items are left out of the frame.
    [[nodiscard]] bool left_out(std::uint16_t kind) const noexcept { return kind < max_kinds && left_out_[kind] != 0U; }
    /// The table set 0 holds: per kind its thresholds rounded to float (xyz) and 0.
    [[nodiscard]] std::span<const hlsl::float4> table() const noexcept { return {table_.data(), names_.size()}; }

private:
    std::vector<std::string> names_;
    std::array<std::array<double, 3>, max_kinds> thresholds_{};
    std::array<std::array<double, max_details>, max_kinds> details_{};
    std::array<std::uint8_t, max_kinds> details_count_{};
    std::array<std::uint8_t, max_kinds> left_out_{};
    std::array<hlsl::float4, max_kinds> table_{};
};

} // namespace atlas::frame
