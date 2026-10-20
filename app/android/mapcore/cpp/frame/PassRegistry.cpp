// The pass registry (frame/PassRegistry.hpp). Created by K7; its registration lines are the coordinator's (plan-vector
// D19): a feature package asks for its pass's line, and the coordinator adds it here, one line per pass.
#include "frame/PassRegistry.hpp"

#include "passes/Composite.hpp"
#include "passes/Frosted.hpp"

#include <algorithm>
#include <array>

namespace atlas::frame {

namespace {

template <class P>
[[nodiscard]] std::unique_ptr<Pass> make() {
    return std::make_unique<P>();
}

// ---- The passes, one line each, in the order the frame records phases that tie (the coordinator's lines).
constexpr std::array registry{
    Registration{.name = "composite", .make = &make<passes::Composite>}, // K7: the composite subpass (D5.4)
    Registration{.name = "frosted", .make = &make<passes::Frosted>},     // K11b: the frosted backdrops (D5.1)
};

} // namespace

std::span<const Registration> registrations() noexcept { return registry; }

std::vector<std::unique_ptr<Pass>> make_passes(std::span<const std::string_view> only) {
    std::vector<std::unique_ptr<Pass>> out;
    for (const Registration& r : registry) {
        if (only.empty() || std::ranges::find(only, r.name) != only.end()) out.push_back(r.make());
    }
    return out;
}

} // namespace atlas::frame
