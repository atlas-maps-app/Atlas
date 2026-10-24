// The pass registry (frame/PassRegistry.hpp). Created by K7; its registration lines are the coordinator's (plan-vector
// D19): a feature package asks for its pass's line, and the coordinator adds it here, one line per pass.
#include "frame/PassRegistry.hpp"

#include "passes/Composite.hpp"
#include "passes/Frosted.hpp"
#include "passes/Ground.hpp"
#include "passes/GroundText.hpp"
#include "passes/Labels.hpp"
#include "passes/Trees.hpp"
#include "passes/Walls.hpp"

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
    Registration{.name = "composite", .make = &make<passes::Composite>},    // K7: the composite subpass (D5.4)
    Registration{.name = "frosted", .make = &make<passes::Frosted>},        // K11b: the frosted backdrops (D5.1)
    Registration{.name = "ground", .make = &make<passes::Ground>},          // K9: the ground (D10, D8's shadows)
    Registration{.name = "buildings", .make = &make<passes::Buildings>},    // K10: the buildings (D10), true depth
    Registration{.name = "trees", .make = &make<passes::Trees>},            // ENGINE.md step 4: the trees, true depth
    Registration{.name = "ground_text", .make = &make<passes::GroundText>}, // K13: the canal names, the ground's last
    Registration{.name = "labels", .make = &make<passes::Labels>},          // K13: the board's labels (D13)
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
