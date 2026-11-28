// The frame loop's waits on the GPU are bounded (gpu/Frames.hpp's wait_ns): a frame whose slot's earlier frame the GPU
// never finishes (a hang the driver does not report as a lost device) fails within the bound, the render thread free,
// and once the GPU goes on the next frame waits for the same slot and draws. The hang is a slot held by the test
// (Frames::hold_next: its fence unsignalled, nothing submitted to signal it), not a frame held on the GPU: lavapipe
// runs a submission in the thread that submits it, where a frame held on a host event held the thread to set it.
#include "gpu/Frames.hpp"
#include "gpu/Offscreen.hpp"
#include "tests/base/require.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <numeric>
#include <string>
#include <tuple>
#include <vector>

using namespace atlas;

namespace {

/// Draws nothing.
class Blank final : public gpu::Content {
public:
    [[nodiscard]] gpu::Result<void> prepare(const gpu::MainPass& /*pass*/, const gpu::Output& /*output*/) override {
        return {};
    }
    void release() override {}
    void record(const gpu::FrameContext& /*frame*/) override {}
};

} // namespace

#if !defined(__ANDROID__)
TEST_CASE("frames: a slot the GPU does not finish fails its next frame within the bound, then draws once it does") {
    auto s = test::open_session();
    if (!s) return;
    auto output = gpu::Offscreen::create(*s->device, {.width = 64, .height = 64}, 2);
    ATLAS_REQUIRE(output.has_value(), output.error().text());
    Blank content;
    auto renderer = gpu::Renderer::create(*s->device, **output, content, s->device->facts().choice);
    ATLAS_REQUIRE(renderer.has_value(), renderer.error().text());
    gpu::Frames& frames = (*renderer)->frames();
    // a phone GPU's bound, though the device be CI's lavapipe (whose bound is longer)
    frames.limit_slot_wait(2'000'000'000);
    const std::uint64_t bound = frames.slot_wait_ns();
    // whatever the checks find, the held slot is let go and the GPU drained before the renderer goes
    class Release {
    public:
        explicit Release(gpu::Renderer& renderer) noexcept : renderer_(renderer) {}
        Release(const Release&) = delete;
        Release& operator=(const Release&) = delete;
        Release(Release&&) = delete;
        Release& operator=(Release&&) = delete;
        ~Release() {
            std::ignore = renderer_.frames().let_go(); // a lost device has nothing left to wait for
            renderer_.drain();
        }

    private:
        gpu::Renderer& renderer_;
    };
    const Release release(**renderer);

    for (std::uint32_t i = 0; i < gpu::Frames::in_flight; ++i) {
        auto drawn = (*renderer)->draw(0);
        ATLAS_REQUIRE(drawn.has_value(), drawn.error().text());
    }
    // the next frame's slot holds a frame the GPU never finishes: its wait ends at the bound, the frame failing
    ATLAS_REQUIRE(frames.hold_next().has_value());
    const auto waiting = std::chrono::steady_clock::now();
    auto timed_out = (*renderer)->draw(0);
    const auto waited = std::chrono::steady_clock::now() - waiting;
    REQUIRE_FALSE(timed_out.has_value());
    CHECK(timed_out.error().code == vk::Result::eTimeout);
    CHECK(waited >= std::chrono::nanoseconds(bound));
    CHECK(waited < std::chrono::nanoseconds(4 * bound));
    // once the GPU goes on, the same slot is waited for again, and the frames draw on
    ATLAS_REQUIRE(frames.let_go().has_value());
    for (int i = 0; i < 3; ++i) {
        auto drawn = (*renderer)->draw(0);
        ATLAS_REQUIRE(drawn.has_value(), drawn.error().text());
    }
    for (const std::string& m : s->take_messages()) MESSAGE(m);
}
#endif

// An acquire's wait in slices (gpu::acquire_in_slices, as gpu::Swapchain's acquire takes it): no device, an acquire
// that answers as a presentation engine would, starved where the window's consumer goes only once the main thread's
// destroy callback returns, which waits for the render thread (VK bench's 60 s, Android 44458997508).
TEST_CASE("frames: an acquire waits in slices up to its bound, gives the wait up within a slice once told the window "
          "is going (none at all where told before), and brings any other answer back at once") {
    constexpr std::uint64_t slice = gpu::Frames::acquire_slice_ns;
    constexpr std::uint64_t ms = 1'000'000;
    std::vector<std::uint64_t> asked;
    const auto answer = [](vk::Result result, std::uint32_t index = 0) {
        return vk::ResultValue<std::uint32_t>(result, index);
    };
    const auto starved = [&](std::uint64_t ns) {
        asked.push_back(ns);
        return answer(vk::Result::eTimeout);
    };
    const auto waited = [&] { return std::accumulate(asked.begin(), asked.end(), std::uint64_t{0}); };

    SUBCASE("starved and never told: a GPU's 2 s and a CPU device's 60 s, in slices, then the timeout") {
        for (const std::uint64_t bound : {std::uint64_t{2'000} * ms, std::uint64_t{60'000} * ms}) {
            asked.clear();
            CHECK(gpu::acquire_in_slices(bound, slice, nullptr, starved).result == vk::Result::eTimeout);
            CHECK(asked.size() == bound / slice);
            CHECK(waited() == bound);
            CHECK(std::ranges::all_of(asked, [&](std::uint64_t ns) { return ns == slice; }));
        }
    }
    SUBCASE("a bound that is no whole number of slices: the last slice is what is left of it") {
        CHECK(gpu::acquire_in_slices(250 * ms, slice, nullptr, starved).result == vk::Result::eTimeout);
        CHECK(asked == std::vector<std::uint64_t>{slice, slice, 50 * ms});
    }
    SUBCASE("told while it waits: given up at the end of that slice") {
        std::atomic<bool> going{false};
        const auto told_in_third = [&](std::uint64_t ns) {
            asked.push_back(ns);
            if (asked.size() == 3) going.store(true); // the main thread's word, during the third slice
            return answer(vk::Result::eTimeout);
        };
        CHECK(gpu::acquire_in_slices(60'000 * ms, slice, &going, told_in_third).result == vk::Result::eTimeout);
        CHECK(asked.size() == 3);
        CHECK(waited() == 3 * slice);
    }
    SUBCASE("told before: no acquire at all") {
        std::atomic<bool> going{true};
        CHECK(gpu::acquire_in_slices(2'000 * ms, slice, &going, starved).result == vk::Result::eTimeout);
        CHECK(asked.empty());
    }
    SUBCASE("not starved: one call, the image") {
        std::atomic<bool> going{false};
        const auto r = gpu::acquire_in_slices(2'000 * ms, slice, &going, [&](std::uint64_t ns) {
            asked.push_back(ns);
            return answer(vk::Result::eSuccess, 1);
        });
        CHECK(r.result == vk::Result::eSuccess);
        CHECK(r.value == 1);
        CHECK(asked == std::vector<std::uint64_t>{slice});
    }
    SUBCASE("an image in the fourth slice, after three timeouts (eNotReady among them): that image") {
        const auto r = gpu::acquire_in_slices(2'000 * ms, slice, nullptr, [&](std::uint64_t ns) {
            asked.push_back(ns);
            if (asked.size() == 2) return answer(vk::Result::eNotReady);
            return asked.size() < 4 ? answer(vk::Result::eTimeout) : answer(vk::Result::eSuboptimalKHR, 2);
        });
        CHECK(r.result == vk::Result::eSuboptimalKHR);
        CHECK(r.value == 2);
        CHECK(asked.size() == 4);
    }
    SUBCASE("out of date, a lost device: back at once, as given") {
        for (const vk::Result given : {vk::Result::eErrorOutOfDateKHR, vk::Result::eErrorDeviceLost}) {
            asked.clear();
            const auto r = gpu::acquire_in_slices(2'000 * ms, slice, nullptr, [&](std::uint64_t ns) {
                asked.push_back(ns);
                return answer(given);
            });
            CHECK(r.result == given);
            CHECK(asked.size() == 1);
        }
    }
}
