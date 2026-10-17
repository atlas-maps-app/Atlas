// The stand-in's SPIR-V as the build embeds it (plan-vector K1, T6): no capability a Vulkan 1.1 phone may lack
// (StorageImageReadWithoutFormat and WriteWithoutFormat, which Slang declares for subpass loads and K0's capability
// pass drops; DrawParameters, which SV_VertexID would bring), and the dynamic route's 1x composite writing colour
// attachment 1 (Slang 2026.19 numbers a lone output 0 unless the location is explicit).
#include "gen/spirv/standin/blur.hpp"
#include "gen/spirv/standin/composite.hpp"
#include "gen/spirv/standin/composite_glsl_composite_fs.hpp"
#include "gen/spirv/standin/composite_x1.hpp"
#include "gen/spirv/standin/composite_x1_loc1.hpp"
#include "gen/spirv/standin/composite_x1_loc1_glsl_composite_fs.hpp"
#include "gen/spirv/standin/frosted.hpp"
#include "gen/spirv/standin/standin.hpp"
#include "tests/base/require.hpp"

#include <doctest/doctest.h>

#include <cstdint>
#include <span>
#include <string_view>
#include <vector>

namespace {

constexpr std::uint32_t op_capability = 17;
constexpr std::uint32_t op_decorate = 71;
constexpr std::uint32_t op_variable = 59;
constexpr std::uint32_t decoration_location = 30;
constexpr std::uint32_t storage_output = 3;
constexpr std::uint32_t capability_draw_parameters = 4427;
constexpr std::uint32_t capability_read_without_format = 55;
constexpr std::uint32_t capability_write_without_format = 56;

/// Each instruction's opcode and operands.
template <class F>
void each(std::span<const std::uint32_t> words, F f) {
    for (std::size_t i = 5; i < words.size();) {
        const std::uint32_t count = words[i] >> 16;
        if (count == 0 || i + count > words.size()) break;
        f(words[i] & 0xFFFFU, words.subspan(i + 1, count - 1));
        i += count;
    }
}

std::vector<std::uint32_t> capabilities(std::span<const std::uint32_t> words) {
    std::vector<std::uint32_t> out;
    each(words, [&](std::uint32_t op, std::span<const std::uint32_t> operands) {
        if (op == op_capability && !operands.empty()) out.push_back(operands[0]);
    });
    return out;
}

/// The Location of every Output variable.
std::vector<std::uint32_t> output_locations(std::span<const std::uint32_t> words) {
    std::vector<std::pair<std::uint32_t, std::uint32_t>> located; // id, location
    std::vector<std::uint32_t> outputs;
    each(words, [&](std::uint32_t op, std::span<const std::uint32_t> o) {
        if (op == op_decorate && o.size() >= 3 && o[1] == decoration_location) located.emplace_back(o[0], o[2]);
        if (op == op_variable && o.size() >= 3 && o[2] == storage_output) outputs.push_back(o[1]);
    });
    std::vector<std::uint32_t> out;
    for (const auto& [id, location] : located) {
        for (const std::uint32_t v : outputs) {
            if (v == id) out.push_back(location);
        }
    }
    return out;
}

} // namespace

TEST_CASE("the stand-in's shaders declare nothing a Vulkan 1.1 phone may lack") {
    const std::span<const std::uint32_t> modules[] = {atlas::spirv::standin_standin,
                                                      atlas::spirv::standin_composite,
                                                      atlas::spirv::standin_composite_x1,
                                                      atlas::spirv::standin_composite_x1_loc1,
                                                      atlas::spirv::standin_blur,
                                                      atlas::spirv::standin_frosted,
                                                      atlas::spirv::standin_composite_glsl_composite_fs,
                                                      atlas::spirv::standin_composite_x1_loc1_glsl_composite_fs};
    for (const auto words : modules) {
        ATLAS_REQUIRE(words.size() > 5, "a module of " << words.size() << " words");
        CHECK(words[0] == 0x07230203U);
        CHECK(words[1] <= 0x00010300U); // SPIR-V 1.3 at most (Vulkan 1.1)
        for (const std::uint32_t c : capabilities(words)) {
            CHECK(c != capability_read_without_format);
            CHECK(c != capability_write_without_format);
            CHECK(c != capability_draw_parameters);
        }
    }
}

TEST_CASE("the dynamic route's 1x composite writes colour attachment 1, the others attachment 0") {
    for (const auto words :
         {std::span<const std::uint32_t>(atlas::spirv::standin_composite_x1_loc1),
          std::span<const std::uint32_t>(atlas::spirv::standin_composite_x1_loc1_glsl_composite_fs)}) {
        const auto locations = output_locations(words);
        ATLAS_REQUIRE(locations.size() == 1, locations.size() << " outputs");
        CHECK(locations[0] == 1);
    }
    for (const auto words : {std::span<const std::uint32_t>(atlas::spirv::standin_composite),
                             std::span<const std::uint32_t>(atlas::spirv::standin_composite_x1)}) {
        const auto locations = output_locations(words);
        ATLAS_REQUIRE(locations.size() == 1, locations.size() << " outputs");
        CHECK(locations[0] == 0);
    }
}
