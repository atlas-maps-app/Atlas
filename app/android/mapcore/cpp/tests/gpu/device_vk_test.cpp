// The device layer on the host's Vulkan (plan-vector K1, T10): the instance and the device open on lavapipe under the
// validation layer, VkFacts are read and printed, and the frame they decide is one the device can draw. Any message of
// the validation layer fails the test. Without Vulkan the tests are skipped, unless ATLAS_REQUIRE_VULKAN=1 (CI and the
// presets) makes that a failure.
#include "gpu/Device.hpp"
#include "gpu/Facts.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <string>

using namespace atlas;

TEST_CASE("the device opens on the host's Vulkan and prints its facts") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Facts& facts = session->device->facts();
    const std::vector<std::string> lines = gpu::facts_lines(facts);
    for (const std::string& l : lines) MESSAGE("vkfacts " << l);
    CHECK(facts.missing.empty());
    CHECK(facts.api_version >= VK_API_VERSION_1_1);
    CHECK(lines.size() > 60);
    auto has_line = [&](std::string_view prefix) {
        return std::ranges::any_of(lines, [&](const std::string& l) { return l.starts_with(prefix); });
    };
    for (const char* prefix :
         {"gpu: ", "vendor: ", "api: ", "driver", "hardware: ", "tiler: ", "lazily.allocated.memory: ", "config: ",
          "route: ", "composite: ", "depth.stencil: ", "samples: ", "profiles: ", "memory.heap0: ", "memory.type0: ",
          "queue0: ", "extensions: ", "extensions.0: "}) {
        CHECK_MESSAGE(has_line(prefix), "no line starts with " << std::string(prefix));
    }
    // lavapipe is a CPU implementation, not a tiler, without lazily allocated memory: configuration C (D5.3).
    if (facts.driver_properties && facts.driver_id == vk::DriverId::eMesaLlvmpipe) {
        CHECK_FALSE(facts.hardware);
        CHECK_FALSE(facts.tiler);
        CHECK_FALSE(facts.lazy_memory);
        CHECK(facts.choice.config == gpu::FrameConfig::c);
    }
    CHECK(facts.supports(facts.choice.config));
    CHECK(session->take_messages().empty());
}
