// VK facts' blur check (passes/BlurCheck.hpp) on the host's Vulkan: lavapipe blurs the soft shadows' mask as the CPU's
// twin does, the mask uploaded, drawn through the frame's own mask pass and drawn with it cleared first, at both boxes;
// so does the blur whose group memory is poisoned before each tile (shadow_blur_poison.slang), so the blur reads no
// word a tile does not write; and none of it under the validation layer's synchronization or (with
// VK_KHRONOS_VALIDATION_GPUAV_ENABLE=1) its GPU-assisted checks says a word. What differs the check names by its
// blocks: how many, whether they hold any of the pattern, how much of it lies on their edges.
#if !defined(__ANDROID__)

#include "passes/BlurCheck.hpp"
#include "tests/base/require.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <doctest/doctest.h>

#include <cstdint>
#include <string>
#include <vector>

using namespace atlas;

TEST_CASE("blur check: the device blurs the shadows' mask as the CPU's twin does, block by block") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    for (const bool poisoned : {false, true}) {
        CAPTURE(poisoned);
        const auto check = passes::blur_check(device, *cache, poisoned);
        ATLAS_REQUIRE(check.has_value(), check.error().text());
        for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
        const std::string line = passes::blur_line(*check);
        MESSAGE(line);
        CHECK(check->exact());
        CHECK(line.starts_with("shadow.blur: exact"));
        CHECK(check->runs.size() == 5U);
    }
}

TEST_CASE("blur check: what differs is named by its blocks, their pattern and their edges") {
    // a 100-texel square, its pattern a square from 40 to 60: blocks (1, 1) and the three after it hold it
    constexpr std::uint32_t side = 100;
    std::vector<std::uint8_t> want(std::size_t{side} * side, 0);
    for (std::uint32_t y = 40; y < 60; ++y) {
        for (std::uint32_t x = 40; x < 60; ++x) want[(std::size_t{y} * side) + x] = 200;
    }
    CHECK(passes::compare_blocks(want, want, side).exact());
    // block (1, 1), texels 32 to 63 each way, three levels up: one block, its edge's 124 texels among its 1,024
    std::vector<std::uint8_t> got = want;
    for (std::uint32_t y = 32; y < 64; ++y) {
        for (std::uint32_t x = 32; x < 64; ++x) {
            const std::size_t i = (std::size_t{y} * side) + x;
            got[i] = static_cast<std::uint8_t>(got[i] + 3);
        }
    }
    const passes::BlurCompare one = passes::compare_blocks(got, want, side);
    CHECK(one.texels == 1024U);
    CHECK(one.blocks == 1U);
    CHECK(one.empty == 0U);
    CHECK(one.edge == 124U);
    CHECK(one.most == 3U);
    CHECK(one.first_x == 32U);
    CHECK(one.first_y == 32U);
    // and a texel of block (0, 0), which holds none of the pattern
    got[(std::size_t{5} * side) + 7] = 9;
    const passes::BlurCompare two = passes::compare_blocks(got, want, side);
    CHECK(two.blocks == 2U);
    CHECK(two.empty == 1U);
    CHECK(two.most == 9U);
    CHECK(two.first_x == 7U);
    CHECK(two.first_y == 5U);
    // the last block of a row is cut short (100 is no whole number of 32): its last column meets no other block
    got = want;
    got[(std::size_t{70} * side) + 99] = 1;
    const passes::BlurCompare cut = passes::compare_blocks(got, want, side);
    CHECK(cut.blocks == 1U);
    CHECK(cut.edge == 0U);
}

#endif
