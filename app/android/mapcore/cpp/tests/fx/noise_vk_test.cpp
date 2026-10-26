// WebKit's turbulence on the GPU (fx/GpuNoise.hpp, shaders/fx/noise.slang): a seed's lattice as the shader reads it,
// and on the host's Vulkan the byte check: every channel of every pixel of the renders' filters' noises, near the
// frame's corner and far from it, the CPU's (fx::fill), the port's bytes.
#include "fx/GpuNoise.hpp"

#include <doctest/doctest.h>

#include <bit>
#include <cstdint>
#include <vector>

using namespace atlas;

TEST_CASE("fx: a lattice as the GPU reads it is its selector, then its gradients' bits") {
    const fx::Lattice lattice(5.0F);
    const std::vector<std::uint32_t> w = fx::lattice_words(lattice);
    REQUIRE(w.size() == lattice.selector().size() + lattice.gradients().size());
    REQUIRE(lattice.selector().size() == 514);
    for (std::size_t i = 0; i < lattice.selector().size(); ++i) {
        CHECK(w[i] == static_cast<std::uint32_t>(lattice.selector()[i]));
    }
    for (std::size_t i = 0; i < lattice.gradients().size(); ++i) {
        CHECK(w[514 + i] == std::bit_cast<std::uint32_t>(lattice.gradients()[i]));
    }
}

#if !defined(__ANDROID__)

#include "gpu/PipelineCache.hpp"
#include "tests/gpu/gpu_fixture.hpp"

#include <string>

TEST_CASE("fx: the GPU's turbulence is the CPU's, byte for byte, on every noise of the renders' filters") {
    auto session = test::open_session();
    if (!session) return;
    const gpu::Device& device = *session->device;
    auto cache = gpu::PipelineCache::open(device.handle(), device.facts(), {}, 0, false);
    ATLAS_REQUIRE(cache.has_value(), cache.error().text());
    constexpr std::uint32_t side = 64;
    const auto check = fx::noise_byte_check(device, *cache, side);
    ATLAS_REQUIRE(check.has_value(), check.error().text());
    MESSAGE(check->pixels << " pixels of " << fx::checked_noises().size() << " noises, " << check->differ
                          << " not the CPU's");
    CHECK(check->pixels == fx::checked_noises().size() * 2 * side * side);
    CHECK_MESSAGE(check->exact, check->first);
    for (const std::string& m : session->take_messages()) CHECK_MESSAGE(false, m);
}

#endif
