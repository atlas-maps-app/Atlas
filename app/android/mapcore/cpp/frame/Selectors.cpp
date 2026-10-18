// The frame's detail choices by distance (frame/Selectors.hpp).
#include "frame/Selectors.hpp"

#include "base/assert.hpp"

#include <algorithm>

namespace atlas::frame {

Selectors::Selectors() {
    for (hlsl::float4& t : table_) t = {never, never, never, 0.0F};
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

void Selectors::set(std::uint16_t kind, float near, float middle, float far) noexcept {
    if (kind >= max_kinds) return;
    table_[kind] = {near, middle, far, 0.0F};
}

} // namespace atlas::frame
