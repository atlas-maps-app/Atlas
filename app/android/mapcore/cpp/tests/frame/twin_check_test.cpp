// The twin's check (frame/TwinCheck.hpp) on the host's Vulkan: the Westerkerk as the core cuts the app's pack of it
// (tests/load/westerkerk.hpp, the pack VK facts runs the check over) at the engine references' home, settled without
// drawing, emits a picture (its tiles drawn, its buildings lit by the sun), the same stream twice, and hashes to
// twin_check_expected, which ci/hashes.env's ATLAS_TWIN_HASH holds the emulator to.
#include "frame/TwinCheck.hpp"
#include "gpu/PipelineCache.hpp"
#include "tests/gpu/gpu_fixture.hpp"
#include "tests/load/westerkerk.hpp"

#include <doctest/doctest.h>

#include <filesystem>
#include <format>
#include <string>

using namespace atlas;

TEST_CASE("the twin's check: the Westerkerk the core cuts for the app, at the home, emits one stream every time, the "
          "host's") {
    const std::filesystem::path pack = atlas::test::westerkerk_core_pack();
    ATLAS_REQUIRE(!pack.empty(), "the Westerkerk's pack is made");
    auto session = test::open_session();
    if (!session) return;
    auto cache = gpu::PipelineCache::open(session->device->handle(), session->device->facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    const tiles::PackRef westerkerk{.id = "amsterdam", .kind = tiles::PackKind::render, .path = pack.string()};
    const auto first = frame::twin_check(*session->device, *cache, westerkerk);
    ATLAS_REQUIRE(first.has_value(), first.error().text());
    MESSAGE(std::format("twin {:016x}: {} bytes, {} tiles drawn, settled in {} frames; {} buildings' elements, {} lit",
                        first->hash, first->bytes, first->tiles, first->frames, first->elements, first->sunlit));
    CHECK(first->tiles > 0);
    CHECK(first->bytes > 100'000);
    // the buildings drawn, their sun's bits in
    CHECK(first->elements > 1000);
    CHECK(first->sunlit > 0);
    // the workers build the tiles in any order: the stream is the same
    const auto second = frame::twin_check(*session->device, *cache, westerkerk);
    ATLAS_REQUIRE(second.has_value(), second.error().text());
    CHECK(second->hash == first->hash);
    CHECK(second->bytes == first->bytes);
    CHECK_MESSAGE(first->hash == frame::twin_check_expected,
                  std::format("twin_check_expected is {:016x}, the check gives {:016x}", frame::twin_check_expected,
                              first->hash));
    CHECK(frame::twin_line(*session->device, *cache, westerkerk) == std::format("twin {:016x} ok", first->hash));
    CHECK(session->take_messages().empty());
}
