// The stand-in's SPIR-V as the build embeds it (plan-vector K1, T6): no capability a Vulkan 1.1 phone may lack
// (StorageImageReadWithoutFormat and WriteWithoutFormat, which Slang declares for subpass loads and K0's capability
// pass drops; DrawParameters, which SV_VertexID would bring), the dynamic route's 1x composite writing colour
// attachment 1 (Slang 2026.19 numbers a lone output 0 unless the location is explicit), no specialization constant in
// any module the core embeds (PowerVR's compiler crashed on one; each variant is a module of its own), and no frame
// shader reading the alpha of the scene's colour samples (the frame's colour may be rgb10a2, which keeps 2 bits of it).
#include "gen/spirv/modules.hpp"
#include "gen/spirv/probe/input_probe.hpp"
#include "gen/spirv/standin/blur.hpp"
#include "gen/spirv/standin/composite.hpp"
#include "gen/spirv/standin/composite_glsl_composite_fs.hpp"
#include "gen/spirv/standin/composite_x1.hpp"
#include "gen/spirv/standin/composite_x1_glsl_composite_fs.hpp"
#include "gen/spirv/standin/composite_x1_loc1.hpp"
#include "gen/spirv/standin/composite_x1_loc1_glsl_composite_fs.hpp"
#include "gen/spirv/standin/composite_x2.hpp"
#include "gen/spirv/standin/composite_x2_glsl_composite_fs.hpp"
#include "gen/spirv/standin/frosted.hpp"
#include "gen/spirv/standin/standin.hpp"
#include "gen/spirv/standin/standin_onepass.hpp"
#include "tests/base/require.hpp"
#include "tools/nocontract/constants.hpp"

#include <doctest/doctest.h>

#include <algorithm>
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
                                                      atlas::spirv::standin_standin_onepass,
                                                      atlas::spirv::standin_composite,
                                                      atlas::spirv::standin_composite_x2,
                                                      atlas::spirv::standin_composite_x1,
                                                      atlas::spirv::standin_composite_x1_loc1,
                                                      atlas::spirv::standin_blur,
                                                      atlas::spirv::standin_frosted,
                                                      atlas::spirv::standin_composite_glsl_composite_fs,
                                                      atlas::spirv::standin_composite_x2_glsl_composite_fs,
                                                      atlas::spirv::standin_composite_x1_glsl_composite_fs,
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
                             std::span<const std::uint32_t>(atlas::spirv::standin_composite_x2),
                             std::span<const std::uint32_t>(atlas::spirv::standin_composite_x1),
                             std::span<const std::uint32_t>(atlas::spirv::standin_composite_glsl_composite_fs),
                             std::span<const std::uint32_t>(atlas::spirv::standin_composite_x2_glsl_composite_fs),
                             std::span<const std::uint32_t>(atlas::spirv::standin_composite_x1_glsl_composite_fs)}) {
        const auto locations = output_locations(words);
        ATLAS_REQUIRE(locations.size() == 1, locations.size() << " outputs");
        CHECK(locations[0] == 0);
    }
}

namespace {

constexpr std::uint16_t op_type_float = 22;
constexpr std::uint16_t op_type_image = 25;
constexpr std::uint16_t op_load = 61;
constexpr std::uint16_t op_vector_shuffle = 79;
constexpr std::uint16_t op_composite_extract = 81;
constexpr std::uint16_t op_image_read = 98;
constexpr std::uint32_t dim_subpass_data = 6;

/// What a module does with the colour it reads from float subpass inputs (subpassLoad): how many reads, and how many
/// uses of a read that may take its alpha (a shuffle or an extract of component 3, or any other use of the whole).
struct InputReads {
    std::size_t reads = 0;
    std::size_t alpha_uses = 0;
};

InputReads input_reads(std::span<const atlas::spirv::Instruction> code) {
    std::vector<std::uint32_t> floats;
    std::vector<std::uint32_t> images;
    std::vector<std::uint32_t> loaded;
    std::vector<std::uint32_t> reads;
    auto in = [](const std::vector<std::uint32_t>& ids, std::uint32_t id) { return std::ranges::contains(ids, id); };
    for (const auto& i : code) {
        const auto o = i.operands();
        if (i.opcode == op_type_float && !o.empty()) floats.push_back(o[0]);
        // OpTypeImage: result, sampled type, dim, ...
        if (i.opcode == op_type_image && o.size() >= 3 && o[2] == dim_subpass_data && in(floats, o[1]))
            images.push_back(o[0]);
    }
    for (const auto& i : code) {
        const auto o = i.operands();
        if (i.opcode == op_load && o.size() >= 3 && in(images, o[0])) loaded.push_back(o[1]);
    }
    for (const auto& i : code) {
        const auto o = i.operands();
        // OpImageRead: result type, result, image, coordinate, ...
        if (i.opcode == op_image_read && o.size() >= 4 && in(loaded, o[2])) reads.push_back(o[1]);
    }
    InputReads out{.reads = reads.size(), .alpha_uses = 0};
    for (const auto& i : code) {
        const auto o = i.operands();
        if (i.opcode == op_image_read) continue;
        if (i.opcode == op_vector_shuffle && o.size() >= 4) {
            // Result type, result, vector 1, vector 2, components (0-3 from vector 1, 4-7 from vector 2).
            for (std::size_t k = 4; k < o.size(); ++k) {
                if ((o[k] == 3 && in(reads, o[2])) || (o[k] == 7 && in(reads, o[3]))) ++out.alpha_uses;
            }
            continue;
        }
        if (i.opcode == op_composite_extract && o.size() >= 4) {
            if (in(reads, o[2]) && o[3] == 3) ++out.alpha_uses;
            continue;
        }
        // Names and decorations only name it (op_name 5, op_member_name 6, op_decorate 71, op_member_decorate 72).
        if (i.opcode == 5 || i.opcode == 6 || i.opcode == op_decorate || i.opcode == 72) continue;
        // Any other use of a read takes the whole of it, alpha included.
        for (const std::uint32_t operand : o) out.alpha_uses += in(reads, operand) ? 1 : 0;
    }
    return out;
}

} // namespace

TEST_CASE("no frame shader reads the alpha of the scene's colour samples") {
    // gpu/Facts.hpp: the scene's and the composite's colour attachments follow the output, rgb10a2 where it can be,
    // whose 2 bits of alpha hold nothing the composite could use. K0's probes are not the frame's, and read it.
    std::size_t composites = 0;
    for (const atlas::spirv::Module& m : atlas::spirv::modules) {
        if (m.name.starts_with("probe_")) continue;
        const auto code = atlas::spirv::instructions(m.words);
        ATLAS_REQUIRE(code.has_value(), m.name << " is not a SPIR-V module");
        const InputReads r = input_reads(*code);
        CHECK_MESSAGE(r.alpha_uses == 0,
                      m.name << " uses the alpha of its colour samples " << r.alpha_uses << " times");
        if (m.name.find("composite") != std::string_view::npos) {
            CHECK_MESSAGE(r.reads >= 1, m.name << " reads no colour samples");
            ++composites;
        }
    }
    CHECK(composites == 8);
    // The scan sees an alpha read: K0's input probe sums whole samples.
    const auto probe = atlas::spirv::instructions(atlas::spirv::probe_input_probe);
    ATLAS_REQUIRE(probe.has_value(), "the input probe is not a SPIR-V module");
    const InputReads p = input_reads(*probe);
    CHECK(p.reads >= 2);
    CHECK(p.alpha_uses >= 1);
}

TEST_CASE("no module the core embeds holds a specialization constant") {
    // The build refuses one (cmake/Shaders.cmake, nocontract constants); this holds every embedded module to it, so a
    // module embedded some other way is caught too. Opcodes 48 to 52: OpSpecConstantTrue, False, Constant, Composite,
    // Op.
    std::size_t scanned = 0;
    for (const atlas::spirv::Module& m : atlas::spirv::modules) {
        const auto code = atlas::spirv::instructions(m.words);
        ATLAS_REQUIRE(code.has_value(), m.name << " is not a SPIR-V module");
        std::size_t opcodes = 0;
        for (const atlas::spirv::Instruction& in : *code) {
            opcodes +=
                in.opcode >= atlas::spirv::op_spec_constant_true && in.opcode <= atlas::spirv::op_spec_constant_op ? 1
                                                                                                                   : 0;
        }
        const auto found = atlas::spirv::specialization_constants(*code);
        CHECK_MESSAGE(found.empty(), m.name << " holds " << found.size() << " specialization constants, the first "
                                            << (found.empty() ? std::string_view()
                                                              : atlas::spirv::spec_constant_opname(found[0].opcode)));
        CHECK(opcodes == found.size());
        ++scanned;
    }
    MESSAGE(scanned << " modules scanned");
    CHECK(scanned == atlas::spirv::modules.size());
    CHECK(scanned >= 20);
}
