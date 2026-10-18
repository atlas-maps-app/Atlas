// The frame's detail choices by distance (frame/Selectors.hpp).
#include "frame/Selectors.hpp"

#include "base/assert.hpp"

#include <algorithm>

namespace atlas::frame {

namespace {

/// A threshold as the selector table holds it (infinity stays infinity).
[[nodiscard]] float f32(double v) noexcept { return static_cast<float>(v); }

} // namespace

Selectors::Selectors() {
    for (std::array<double, 3>& t : thresholds_) t = {never, never, never};
    for (hlsl::float4& t : table_) t = {f32(never), f32(never), f32(never), 0.0F};
}

std::uint16_t Selectors::kind(std::string_view name) {
    const auto it = std::ranges::find(names_, name);
    if (it != names_.end()) return static_cast<std::uint16_t>(it - names_.begin());
    ATLAS_ASSERT(names_.size() < max_kinds, "more item kinds than the selector table holds");
    names_.emplace_back(name);
    return static_cast<std::uint16_t>(names_.size() - 1);
}

std::string_view Selectors::name(std::uint16_t kind) const noexcept {
    return kind < names_.size() ? std::string_view(names_[kind]) : std::string_view();
}

void Selectors::set(std::uint16_t kind, double near, double middle, double far) noexcept {
    if (kind >= max_kinds) return;
    thresholds_[kind] = {near, middle, far};
    table_[kind] = {f32(near), f32(middle), f32(far), 0.0F};
}

} // namespace atlas::frame
