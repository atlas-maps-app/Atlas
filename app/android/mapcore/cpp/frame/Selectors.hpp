// The frame's detail choices by distance (plan-vector D9, "Detail by distance"; D6): the renders draw an item's details
// by how far it is (a wall's cells within 460 m, windows within win_d, deep windows within 160 m, plinths within 760 m,
// ...). Each item kind has up to three distance thresholds, ascending, which split its depth into four detail bands
// (band 0 the nearest, with the most detail); the passes that draw a kind set its thresholds for each frame from the
// frame's settings (their update, before the ranks), the ranks give each item the painter slots of its band, and the
// GPU reads the same thresholds from set 0's selector table (binding 2: one float4 per kind, the thresholds in xyz),
// so the vertex stage chooses as the render thread did.
//
// Kinds are registered once (a pass's prepare or its making); a frame only sets their thresholds.
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
    static constexpr float never = std::numeric_limits<float>::infinity();

    Selectors();

    /// The row of the kind named `name` ("building", "tree", ...), registered the first time it is asked for.
    [[nodiscard]] std::uint16_t kind(std::string_view name);
    /// The kind's name.
    [[nodiscard]] std::string_view name(std::uint16_t kind) const noexcept;
    /// The registered kinds.
    [[nodiscard]] std::size_t size() const noexcept { return names_.size(); }

    /// Sets kind `kind`'s thresholds for the frame (metres, ascending; `never` for the ones it lacks).
    void set(std::uint16_t kind, float near, float middle = never, float far = never) noexcept;
    /// The detail band (0 to 3) of an item of kind `kind` at depth `depth`: how many of its thresholds it lies at or
    /// beyond.
    [[nodiscard]] std::size_t band(std::uint16_t kind, double depth) const noexcept {
        const hlsl::float4& t = table_[kind];
        return static_cast<std::size_t>(depth >= static_cast<double>(t.x)) +
               static_cast<std::size_t>(depth >= static_cast<double>(t.y)) +
               static_cast<std::size_t>(depth >= static_cast<double>(t.z));
    }
    /// The table set 0 holds: per kind its thresholds (xyz) and 0.
    [[nodiscard]] std::span<const hlsl::float4> table() const noexcept { return {table_.data(), names_.size()}; }

private:
    std::vector<std::string> names_;
    std::array<hlsl::float4, max_kinds> table_{};
};

} // namespace atlas::frame
