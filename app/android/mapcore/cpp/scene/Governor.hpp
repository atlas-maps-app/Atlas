// The triangle governor (ENGINE.md step 5, "the triangle governor"): the cover's second budget beside the heaps'. Each
// frame the cover adds up the triangles of the tiles it plans (Cover::triangles) and the world those of the world's
// tiles it draws; over the budget a bias moves every level's start and the countries' reach nearer, by at most 0.1
// level a frame and 1.5 in all, and under 80 % of the budget it comes back by 0.05 a frame. At bias 0 the cover is
// as without the governor, and the budget is set so every reference and VK bench path stays there (no quality lost):
// the bias only ever takes a view heavier than any of them.
#pragma once

#include "base/pymath/PyMath.hpp"

#include <algorithm>
#include <cstdint>

namespace atlas::scene {

/// The bias moves in steps of a twentieth of a level: up by governor_rise steps a frame over the budget (0.1 level),
/// to governor_most (1.5 levels), and back by one under governor_under of the budget.
inline constexpr double governor_step = 0.05;
inline constexpr int governor_rise = 2;
inline constexpr int governor_most = 30;
inline constexpr double governor_under = 0.8;

/// The budget, in triangles a frame planned (Cover::triangles and the world's): a third above the most any reference or
/// VK bench path plans (pinch_audit_vk_test at the phone's frame over the Noord-Holland windows and levels, 3 November:
/// 12.1 M on the home's pinch at 4.4 km and the pitch of 70° turning, 11.7 M the Westerkerk's from 5 to 12 km, 4.7 M
/// from 25 km at 72° with the world), so they all stay at bias 0; a device whose heaps' budget is under 128 MB
/// (scene::heap_budget), whose cover the heaps already keep coarser, two thirds of it, as ENGINE.md's design has it,
/// until VK bench measures one.
inline constexpr std::uint64_t governor_budget = 16'000'000;

/// The budget for a device whose heaps' budget is `heaps` bytes.
[[nodiscard]] constexpr std::uint64_t triangle_budget(std::uint64_t heaps) noexcept {
    return heaps >= (std::uint64_t{128} << 20U) ? governor_budget : governor_budget / 3 * 2;
}

/// The governor's bias (World applies it: World::update).
class Governor {
public:
    /// After a frame: the triangles it planned at the bias it had, against `budget` (0: none, the bias 0), and whether
    /// the camera moved since the frame before. Over the budget the bias rises; under governor_under of it, it comes
    /// back, but never while the camera is still to a bias its plan was over the budget at, which would rise again
    /// and set a level's tiles fading in and out frame after frame.
    void update(std::uint64_t planned, std::uint64_t budget, bool moved) noexcept {
        const int before = steps_;
        if (moved || budget == 0) over_ = -1;
        if (budget == 0) {
            steps_ = 0;
        } else if (planned > budget) {
            over_ = std::max(over_, steps_);
            steps_ = std::min(steps_ + governor_rise, governor_most);
        } else if (steps_ > 0 && steps_ - 1 > over_ &&
                   static_cast<double>(planned) < governor_under * static_cast<double>(budget)) {
            --steps_;
        }
        changed_ = steps_ != before;
    }
    /// The bias in steps, and in levels.
    [[nodiscard]] int steps() const noexcept { return steps_; }
    [[nodiscard]] double bias() const noexcept { return governor_step * steps_; }
    /// Whether the last frame moved the bias: the next frame plans at the new one and may move it again, so the frames
    /// go on until it holds.
    [[nodiscard]] bool changed() const noexcept { return changed_; }
    /// `metres` moved nearer by the bias, metres · 2^−bias (fdlibm's, as every transcendental of the core's:
    /// plan-vector T3): a level's start or a reach. `metres` itself at bias 0.
    [[nodiscard]] double near(double metres) const noexcept {
        return steps_ == 0 ? metres : metres * pymath::pow(2.0, -bias());
    }

private:
    int steps_ = 0;
    int over_ = -1; ///< the highest step the still camera's plan was over the budget at (-1: none)
    bool changed_ = false;
};

} // namespace atlas::scene
