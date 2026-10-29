// The shader pipeline's products (plan-vector T6): the embedded SPIR-V, the exact-module check that guards it, and
// the C++ layouts generated from Slang's reflection.
#include "gen/layouts.hpp"
#include "gen/spirv/probe/exact_probe.hpp"
#include "gen/spirv/probe/exact_probe_fmakhr.hpp"
#include "gen/spirv/probe/exact_probe_glsl_fma_probe.hpp"
#include "gen/spirv/probe/input_probe.hpp"
#include "gen/spirv/probe/layout_probe.hpp"
#include "gen/spirv/probe/probe.hpp"
#include "tools/nocontract/capabilities.hpp"
#include "tools/nocontract/constants.hpp"
#include "tools/nocontract/exactness.hpp"
#include "tools/nocontract/outputs.hpp"
#include "tools/nocontract/spirv.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <initializer_list>
#include <span>
#include <string>
#include <string_view>
#include <tuple>
#include <type_traits>
#include <utility>
#include <vector>

namespace {

atlas::spirv::Exactness check(std::span<const std::uint32_t> module) {
    const auto code = atlas::spirv::instructions(module);
    REQUIRE(code.has_value());
    return atlas::spirv::check_exact(*code);
}

// "GLSL.std.450" as a literal string operand.
constexpr std::array<std::uint32_t, 4> glsl_std_450{0x4C534C47U, 0x6474732EU, 0x3035342EU, 0x00000000U};

} // namespace

TEST_CASE("the embedded modules are SPIR-V 1.3") {
    for (const std::span<const std::uint32_t> module :
         {std::span<const std::uint32_t>(atlas::spirv::probe_probe),
          std::span<const std::uint32_t>(atlas::spirv::probe_exact_probe),
          std::span<const std::uint32_t>(atlas::spirv::probe_exact_probe_fmakhr),
          std::span<const std::uint32_t>(atlas::spirv::probe_exact_probe_glsl_fma_probe)}) {
        REQUIRE(module.size() > atlas::spirv::header_words);
        CHECK(module[0] == atlas::spirv::magic);
        CHECK(module[1] == 0x00010300U);
        CHECK(atlas::spirv::instructions(module).has_value());
    }
    CHECK(atlas::spirv::probe_probe_sha256.size() == 64);
}

TEST_CASE("the exact probe carries NoContraction on every float operation") {
    const auto emulated = check(atlas::spirv::probe_exact_probe);
    CHECK(emulated.exact());
    CHECK(emulated.float_ops >= 20); // Dekker, TwoSum and the odd rounding: about twenty operations
    CHECK(emulated.decorated == emulated.float_ops);
    CHECK(emulated.fma_khr == 0);

    const auto device = check(atlas::spirv::probe_exact_probe_fmakhr);
    CHECK(device.exact());
    CHECK(device.fma_khr == 1);

    // Through GLSL and glslang the decorations are lost; the build's nocontract step put every one back.
    const auto glsl = check(atlas::spirv::probe_exact_probe_glsl_fma_probe);
    CHECK(glsl.exact());
    CHECK(glsl.float_ops == emulated.float_ops);
    CHECK(glsl.decorated == glsl.float_ops);
}

TEST_CASE("the check is not vacuous: an ordinary module fails it, and fix makes it pass") {
    const auto ordinary = check(atlas::spirv::probe_probe);
    CHECK_FALSE(ordinary.exact());
    CHECK(ordinary.float_ops > 0);
    CHECK(ordinary.decorated == 0);

    const std::span<const std::uint32_t> module(atlas::spirv::probe_probe);
    const auto code = atlas::spirv::instructions(module);
    REQUIRE(code.has_value());
    const auto [fixed, added] = atlas::spirv::add_no_contraction(module, *code);
    CHECK(added == ordinary.float_ops);
    CHECK(fixed.size() == module.size() + (3 * added));
    CHECK(check(fixed).exact());
}

TEST_CASE("the check names every open operation of an exact module") {
    using namespace atlas::spirv;
    // A hand-made instruction stream: GLSL.std.450's Fma, an undecorated add, a division and a dot product.
    std::vector<std::uint32_t> words{magic, 0x00010300U, 0, 32, 0};
    words.push_back((6U << 16U) | op_ext_inst_import);
    words.push_back(1);
    words.insert(words.end(), glsl_std_450.begin(), glsl_std_450.end());
    words.insert(words.end(), {(3U << 16U) | op_decorate, 12, decoration_no_contraction});
    words.insert(words.end(), {(5U << 16U) | op_function, 2, 3, 0, 4});
    words.insert(words.end(), {(8U << 16U) | op_ext_inst, 5, 6, 1, 50, 7, 8, 9});
    words.insert(words.end(), {(5U << 16U) | op_f_add, 5, 10, 6, 6});
    words.insert(words.end(), {(5U << 16U) | op_f_div, 5, 11, 10, 10});
    words.insert(words.end(), {(5U << 16U) | op_dot, 5, 13, 10, 10});
    words.insert(words.end(), {(5U << 16U) | op_f_mul, 5, 12, 10, 10});
    words.insert(words.end(), {(8U << 16U) | op_ext_inst, 5, 14, 1, 8, 12, 0, 0}); // Floor: exact
    words.push_back((1U << 16U) | op_function_end);
    const auto e = check(words);
    REQUIRE(e.findings.size() == 4);
    CHECK(e.findings[0].what.find("Fma") != std::string::npos);
    CHECK(e.findings[1].what == "a float operation without NoContraction");
    CHECK(e.findings[1].id == 10);
    CHECK(e.findings[2].what.find("OpFDiv") != std::string::npos);
    CHECK(e.findings[3].what.find("OpDot") != std::string::npos);
    CHECK(e.float_ops == 2);
    CHECK(e.decorated == 1);
}

TEST_CASE("a truncated module is refused") {
    std::vector<std::uint32_t> words{atlas::spirv::magic, 0x00010300U, 0, 8, 0, (9U << 16U) | 17U, 1};
    CHECK_FALSE(atlas::spirv::instructions(words).has_value());
    words[0] = 0x03022307U;
    CHECK(atlas::spirv::instructions(words).error() == atlas::spirv::ModuleError::bad_magic);
}

TEST_CASE("the layouts generated from Slang's reflection match the shaders byte for byte") {
    using Push = atlas::layouts::probe_probe::PushConstants;
    static_assert(std::is_same_v<decltype(Push::scale), float>);
    static_assert(std::is_same_v<decltype(Push::count), std::uint32_t>);
    static_assert(std::is_same_v<decltype(Push::tint), std::array<float, 4>>);
    static_assert(offsetof(Push, tint) == 16); // a float4 aligns to 16
    static_assert(sizeof(Push) == 32);
    static_assert(std::is_same_v<atlas::layouts::probe_probe::values_element, float>);
    static_assert(atlas::layouts::probe_probe::values.set == 0 && atlas::layouts::probe_probe::values.binding == 0);

    using ExactPush = atlas::layouts::probe_exact_probe::PushConstants;
    static_assert(offsetof(ExactPush, count) == 0);
    static_assert(std::is_same_v<atlas::layouts::probe_exact_probe::operands_element, std::array<float, 4>>);
    static_assert(atlas::layouts::probe_exact_probe::results.binding == 1);
    Push push{};
    push.count = 3;
    CHECK(push.count == 3);
}

TEST_CASE("input attachments get their descriptor binding and their index in the subpass") {
    namespace ip = atlas::layouts::probe_input_probe;
    static_assert(ip::scene.set == 0 && ip::scene.binding == 0 && ip::scene_input_attachment == 0);
    static_assert(ip::marks.set == 0 && ip::marks.binding == 1 && ip::marks_input_attachment == 1);
    static_assert(ip::weights.binding == 2 && std::is_same_v<ip::weights_element, float>);
    static_assert(offsetof(ip::PushConstants, samples) == 4 && sizeof(ip::PushConstants) == 8);
    CHECK(atlas::spirv::probe_input_probe.size() > atlas::spirv::header_words);
}

namespace {

bool declares(std::span<const std::uint32_t> module, std::uint32_t capability) {
    const auto code = atlas::spirv::instructions(module);
    REQUIRE(code.has_value());
    for (const auto& in : *code) {
        if (in.opcode == atlas::spirv::op_capability && in.operands().size() == 1 && in.operands()[0] == capability) {
            return true;
        }
    }
    return false;
}

// A module with one OpTypeImage of the given Dim, Sampled and format, and the without-format capability.
std::vector<std::uint32_t> image_module(std::uint32_t dim, std::uint32_t sampled, std::uint32_t format) {
    using namespace atlas::spirv;
    std::vector<std::uint32_t> words{magic, 0x00010300U, 0, 16, 0};
    words.insert(words.end(), {(2U << 16U) | op_capability, 1}); // Shader
    words.insert(words.end(), {(2U << 16U) | op_capability, capability_storage_image_read_without_format});
    words.insert(words.end(), {(3U << 16U) | 22U, 2, 32}); // OpTypeFloat %2 32
    words.insert(words.end(), {(9U << 16U) | op_type_image, 3, 2, dim, 0, 0, 0, sampled, format});
    return words;
}

} // namespace

TEST_CASE("capabilities a module declares without needing them are removed, and only those") {
    using namespace atlas::spirv;
    constexpr std::uint32_t input_attachment = 40;
    // The input probe as embedded: the build removed Slang's StorageImageReadWithoutFormat, InputAttachment stays.
    CHECK_FALSE(declares(probe_input_probe, capability_storage_image_read_without_format));
    CHECK(declares(probe_input_probe, input_attachment));

    for (const auto& [dim, sampled, format, keeps] :
         {std::tuple{dim_subpass_data, 2U, image_format_unknown, false}, std::tuple{1U, 2U, image_format_unknown, true},
          std::tuple{1U, 2U, 4U /* Rgba8 */, false}, std::tuple{1U, 1U, image_format_unknown, false}}) {
        const auto module = image_module(dim, sampled, format);
        const auto code = instructions(module);
        REQUIRE(code.has_value());
        const auto [tidied, removed] = remove_unneeded_capabilities(module, *code);
        CHECK(declares(tidied, capability_storage_image_read_without_format) == keeps);
        CHECK(removed == (keeps ? 0U : 1U));
        CHECK(declares(tidied, 1)); // Shader is never touched
    }
}

TEST_CASE("nested structs, arrays, float3 padding and tail padding keep Slang's offsets") {
    namespace lp = atlas::layouts::probe_layout_probe;
    using Push = lp::PushConstants;
    static_assert(offsetof(Push, scale) == 0);
    static_assert(offsetof(Push, cell) == 8 && std::is_same_v<decltype(Push::cell), std::array<std::int32_t, 2>>);
    static_assert(offsetof(Push, tint) == 16);
    static_assert(offsetof(Push, flags) == 28); // in the float3's padding, as std430 and Slang place it
    static_assert(offsetof(Push, inner) == 32 && sizeof(lp::Inner) == 16);
    static_assert(offsetof(Push, weights) == 48 && std::is_same_v<decltype(Push::weights), std::array<float, 4>>);
    static_assert(sizeof(Push) == 64);
    static_assert(std::is_same_v<lp::records_element, lp::Record>);
    static_assert(offsetof(lp::Record, kind) == 12 && offsetof(lp::Record, uv) == 16 && sizeof(lp::Record) == 32);
    static_assert(lp::echo.binding == 1);
    CHECK(sizeof(Push) == 64);
}

TEST_CASE("specialization constants are found, with their names and SpecIds") {
    using namespace atlas::spirv;
    // A hand-made module: %3 SAMPLES (SpecId 1) = 4, %4 = SAMPLES + 1 (OpSpecConstantOp IAdd), an unnamed %5 (SpecId 0)
    // = false, and an ordinary constant %6 that is none of them.
    std::vector<std::uint32_t> words{magic, 0x00010300U, 0, 16, 0};
    words.insert(words.end(), {(2U << 16U) | op_capability, 1});                     // Shader
    words.insert(words.end(), {(4U << 16U) | op_name, 3, 0x504D4153U, 0x0053454CU}); // "SAMPLES"
    words.insert(words.end(), {(4U << 16U) | op_decorate, 3, decoration_spec_id, 1});
    words.insert(words.end(), {(4U << 16U) | op_decorate, 5, decoration_spec_id, 0});
    words.insert(words.end(), {(4U << 16U) | 21U, 2, 32, 1}); // OpTypeInt %2 32 1
    words.insert(words.end(), {(2U << 16U) | 20U, 7});        // OpTypeBool %7
    words.insert(words.end(), {(4U << 16U) | op_spec_constant, 2, 3, 4});
    words.insert(words.end(), {(6U << 16U) | op_spec_constant_op, 2, 4, 128, 3, 6}); // IAdd %3 %6
    words.insert(words.end(), {(3U << 16U) | op_spec_constant_false, 7, 5});
    words.insert(words.end(), {(4U << 16U) | 43U, 2, 6, 1}); // OpConstant %2 %6 1
    const auto code = instructions(words);
    REQUIRE(code.has_value());
    const auto found = specialization_constants(*code);
    REQUIRE(found.size() == 3);
    CHECK(found[0].id == 3);
    CHECK(found[0].opcode == op_spec_constant);
    CHECK(found[0].name == "SAMPLES");
    CHECK(found[0].spec_id == 1U);
    CHECK(found[1].id == 4);
    CHECK(found[1].opcode == op_spec_constant_op);
    CHECK(found[1].operation == 128);
    CHECK_FALSE(found[1].spec_id.has_value());
    CHECK(found[2].opcode == op_spec_constant_false);
    CHECK(found[2].spec_id == 0U);
    CHECK(spec_constant_opname(found[1].opcode) == "OpSpecConstantOp");
    // The probe, as embedded, holds none.
    const auto probe = instructions(probe_probe);
    REQUIRE(probe.has_value());
    CHECK(specialization_constants(*probe).empty());
}

TEST_CASE("the extensions' specialization constants and a SpecId on anything are found too") {
    using namespace atlas::spirv;
    // A hand-made module: %3 = OpSpecConstantCompositeReplicateEXT of %4 (SPV_EXT_replicated_composites), named "REP";
    // %5 an OpSpecConstantComposite continued by OpSpecConstantCompositeContinuedINTEL (SPV_INTEL_long_composites),
    // which has no result of its own; a SpecId on %4, an ordinary constant; a SpecId through OpDecorateId on %6 and one
    // on member 0 of a struct %7; and an OpDecorate of another decoration (Location 0), which is none of them.
    std::vector<std::uint32_t> words{magic, 0x00010300U, 0, 16, 0};
    words.insert(words.end(), {(2U << 16U) | op_capability, 1});        // Shader
    words.insert(words.end(), {(3U << 16U) | op_name, 3, 0x00504552U}); // "REP"
    words.insert(words.end(), {(4U << 16U) | op_decorate, 4, decoration_spec_id, 7});
    words.insert(words.end(), {(4U << 16U) | op_decorate_id, 6, decoration_spec_id, 8});
    words.insert(words.end(), {(5U << 16U) | op_member_decorate, 7, 0, decoration_spec_id, 9});
    words.insert(words.end(), {(4U << 16U) | op_decorate, 6, 30, 0}); // Location 0
    words.insert(words.end(), {(4U << 16U) | 21U, 2, 32, 1});         // OpTypeInt %2 32 1
    words.insert(words.end(), {(4U << 16U) | 23U, 8, 2, 2});          // OpTypeVector %8 %2 2
    words.insert(words.end(), {(4U << 16U) | 43U, 2, 4, 1});          // OpConstant %2 %4 1
    words.insert(words.end(), {(4U << 16U) | op_spec_constant_composite_replicate_ext, 8, 3, 4});
    words.insert(words.end(), {(5U << 16U) | op_spec_constant_composite, 8, 5, 4, 4});
    words.insert(words.end(), {(3U << 16U) | op_spec_constant_composite_continued_intel, 4, 4});
    const auto code = instructions(words);
    REQUIRE(code.has_value());
    const auto found = specialization_constants(*code);
    REQUIRE(found.size() == 6);
    CHECK(found[0].opcode == op_spec_constant_composite_replicate_ext);
    CHECK(found[0].id == 3);
    CHECK(found[0].name == "REP");
    CHECK(spec_constant_opname(found[0].opcode) == "OpSpecConstantCompositeReplicateEXT");
    CHECK(found[1].opcode == op_spec_constant_composite);
    CHECK(found[1].id == 5);
    CHECK(found[2].opcode == op_spec_constant_composite_continued_intel);
    CHECK(found[2].id == 0);
    CHECK(spec_constant_opname(found[2].opcode) == "OpSpecConstantCompositeContinuedINTEL");
    // The SpecIds on what is no specialization constant, each refused with its own.
    CHECK(found[3].opcode == op_decorate);
    CHECK(found[3].id == 4);
    CHECK(found[3].spec_id == 7U);
    CHECK(spec_constant_opname(found[3].opcode) == "a SpecId decoration (OpDecorate)");
    CHECK(found[4].opcode == op_decorate_id);
    CHECK(found[4].spec_id == 8U);
    CHECK(found[5].opcode == op_member_decorate);
    CHECK(found[5].id == 7);
    CHECK(found[5].spec_id == 9U);
    for (const std::uint16_t op : {op_spec_constant_true, op_spec_constant_op, op_spec_constant_composite_replicate_ext,
                                   op_spec_constant_composite_continued_intel})
        CHECK(makes_spec_constant(op));
    for (const std::uint16_t op : {std::uint16_t{43}, std::uint16_t{53}, std::uint16_t{4461}, std::uint16_t{6091}})
        CHECK_FALSE(makes_spec_constant(op));
}

TEST_CASE("every instruction of the pinned Slang's SPIR-V grammar that makes a specialization constant is refused") {
    using namespace atlas::spirv;
    // The grammar is the pinned Slang's (tools/nocontract/grammar.hpp, derived from the table its compiler embeds and
    // checked against NDK r30's SPIRV-Headers): each instruction whose name begins OpSpecConstant, none left out.
    CHECK(grammar::slang_version == ATLAS_SLANG_VERSION);
    for (const grammar::Named& n : grammar::spec_constant_instructions) CHECK(n.name.starts_with("OpSpecConstant"));
    // The five the hand-made list of 59bcec0 left out, which Slang's spirv_asm writes: SPV_AMDX_shader_enqueue's
    // string, the data constant, and SPV_INTEL_function_variants' three.
    for (const auto& [opcode, name] : {std::pair<std::uint16_t, std::string_view>{5104, "OpSpecConstantStringAMDX"},
                                       {5148, "OpSpecConstantDataKHR"},
                                       {6251, "OpSpecConstantTargetINTEL"},
                                       {6252, "OpSpecConstantArchitectureINTEL"},
                                       {6253, "OpSpecConstantCapabilitiesINTEL"}}) {
        CHECK(makes_spec_constant(opcode));
        CHECK(spec_constant_opname(opcode) == name);
    }
    // Each in a module of its own, as the verifier wrote them by hand: a type, a constant, then the instruction with a
    // result of that type where it has one (operands past it are literals, which the check does not read).
    for (const grammar::Named& n : grammar::spec_constant_instructions) {
        std::vector<std::uint32_t> words{magic, 0x00010300U, 0, 16, 0};
        words.insert(words.end(), {(2U << 16U) | op_capability, 1}); // Shader
        words.insert(words.end(), {(4U << 16U) | 21U, 2, 32, 1});    // OpTypeInt %2 32 1
        words.insert(words.end(), {(4U << 16U) | 43U, 2, 4, 1});     // OpConstant %2 %4 1
        words.insert(words.end(), {(5U << 16U) | n.opcode, 2, 5, 4, 1});
        const auto code = instructions(words);
        REQUIRE(code.has_value());
        const auto found = specialization_constants(*code);
        REQUIRE_MESSAGE(found.size() == 1, n.name << " was not found");
        CHECK(found[0].opcode == n.opcode);
        CHECK(found[0].id == 5); // its type, %2, declared before it
    }
    // Untyped (OpSpecConstantStringAMDX: a result and a literal string): found by the instruction, the id unnamed.
    std::vector<std::uint32_t> words{magic, 0x00010300U, 0, 16, 0};
    words.insert(words.end(), {(3U << 16U) | 5104U, 5, 0x00636261U}); // %5 = OpSpecConstantStringAMDX "abc"
    auto code = instructions(words);
    REQUIRE(code.has_value());
    auto found = specialization_constants(*code);
    REQUIRE(found.size() == 1);
    CHECK(found[0].opcode == 5104);
    CHECK(found[0].id == 0);
    // A SpecId through every instruction that decorates, OpMemberDecorateIdEXT (5127) among them, which the hand-made
    // list missed too.
    CHECK(decorating(5127) != nullptr);
    for (const grammar::Decorating& d : grammar::decorating_instructions) {
        words = {magic, 0x00010300U, 0, 16, 0};
        if (d.member) {
            words.insert(words.end(), {(5U << 16U) | d.opcode, 7, 0, decoration_spec_id, 9});
        } else {
            words.insert(words.end(), {(4U << 16U) | d.opcode, 7, decoration_spec_id, 9});
        }
        code = instructions(words);
        REQUIRE(code.has_value());
        found = specialization_constants(*code);
        REQUIRE_MESSAGE(found.size() == 1, d.name << "'s SpecId was not found");
        CHECK(found[0].spec_id == 9U);
        CHECK(spec_constant_opname(found[0].opcode) == "a SpecId decoration (" + std::string(d.name) + ")");
    }
}

TEST_CASE("outputs written from a value nothing set are found, and those something set are not") {
    using namespace atlas::spirv;
    // A hand-made vertex stage: %10 "out" a uint output and %11 a uint2 one; in "main", %20 "unset" a local nothing
    // writes (as Slang keeps a member of the returned struct that no path sets, window_near_vs' asked before 29 October
    // 2026), %22 a uint2 local written through an access chain, %30 the constant 0; `body` follows its load %21.
    const auto module = [](std::initializer_list<std::uint32_t> body) {
        std::vector<std::uint32_t> words{magic, 0x00010300U, 0, 64, 0};
        words.insert(words.end(), {(2U << 16U) | op_capability, 1});
        words.insert(words.end(), {(3U << 16U) | op_name, 10, 0x0074756FU});              // "out"
        words.insert(words.end(), {(4U << 16U) | op_name, 20, 0x65736E75U, 0x00000074U}); // "unset"
        words.insert(words.end(), {(4U << 16U) | op_name, 6, 0x6E69616DU, 0});            // "main"
        words.insert(words.end(), {(4U << 16U) | op_type_int, 1, 32, 0});
        words.insert(words.end(), {(4U << 16U) | op_type_vector, 8, 1, 2});
        words.insert(words.end(), {(4U << 16U) | op_type_pointer, 2, storage_output, 1});
        words.insert(words.end(), {(4U << 16U) | op_type_pointer, 9, storage_output, 8});
        words.insert(words.end(), {(4U << 16U) | op_type_pointer, 3, storage_function, 1});
        words.insert(words.end(), {(4U << 16U) | op_type_pointer, 12, storage_function, 8});
        words.insert(words.end(), {(2U << 16U) | 19U, 4});    // OpTypeVoid %4
        words.insert(words.end(), {(3U << 16U) | 33U, 5, 4}); // OpTypeFunction %5 %4
        words.insert(words.end(), {(4U << 16U) | op_constant, 1, 30, 0});
        words.insert(words.end(), {(4U << 16U) | op_variable, 2, 10, storage_output});
        words.insert(words.end(), {(4U << 16U) | op_variable, 9, 11, storage_output});
        words.insert(words.end(), {(5U << 16U) | op_function, 4, 6, 0, 5});
        words.insert(words.end(), {(2U << 16U) | 248U, 7}); // OpLabel %7
        words.insert(words.end(), {(4U << 16U) | op_variable, 3, 20, storage_function});
        words.insert(words.end(), {(4U << 16U) | op_variable, 12, 22, storage_function});
        words.insert(words.end(), {(5U << 16U) | op_access_chain, 3, 23, 22, 30});
        words.insert(words.end(), {(3U << 16U) | op_store, 23, 30});
        words.insert(words.end(), {(4U << 16U) | op_load, 1, 21, 20});
        words.insert(words.end(), body);
        words.insert(words.end(), {(1U << 16U) | 253U}); // OpReturn
        words.insert(words.end(), {(1U << 16U) | op_function_end});
        return words;
    };
    const auto found_in = [](const std::vector<std::uint32_t>& words) {
        const auto code = instructions(words);
        REQUIRE(code.has_value());
        return unset_outputs(*code);
    };
    // The unset local's load, stored to the output: found, with the output, its writer and the local named.
    auto found = found_in(module({(3U << 16U) | op_store, 10, 21}));
    REQUIRE(found.size() == 1);
    CHECK(found[0].output == 10);
    CHECK(found[0].name == "out");
    CHECK(found[0].function == "main");
    CHECK(found[0].local == 20);
    CHECK(found[0].local_name == "unset");
    // The same once something writes the local, before or after: none.
    CHECK(found_in(module({(3U << 16U) | op_store, 20, 30, (3U << 16U) | op_store, 10, 21})).empty());
    CHECK(found_in(module({(3U << 16U) | op_store, 10, 21, (3U << 16U) | op_store, 20, 30})).empty());
    // An OpUndef stored whole: found, no local named.
    found = found_in(module({(3U << 16U) | op_undef, 1, 40, (3U << 16U) | op_store, 10, 40}));
    REQUIRE(found.size() == 1);
    CHECK(found[0].local == 0);
    // A member of an output through an access chain, from the unset load: found, as the output.
    found = found_in(module({(5U << 16U) | op_access_chain, 2, 25, 11, 30, (3U << 16U) | op_store, 25, 21}));
    REQUIRE(found.size() == 1);
    CHECK(found[0].output == 11);
    // A composite of the unset load and a constant, and a copy of it: found.
    CHECK(found_in(module({(5U << 16U) | op_composite_construct, 8, 44, 21, 30, (3U << 16U) | op_store, 11, 44}))
              .size() == 1);
    CHECK(found_in(module({(5U << 16U) | op_composite_construct, 8, 44, 21, 30, (4U << 16U) | op_copy_object, 8, 45, 44,
                           (3U << 16U) | op_store, 11, 45}))
              .size() == 1);
    // A vector made with an OpUndef whose component is inserted next (the stand-in's positions), and the local written
    // through its access chain, loaded whole: none.
    CHECK(found_in(module({(3U << 16U) | op_undef, 1, 41, (5U << 16U) | op_composite_construct, 8, 42, 30, 41,
                           (6U << 16U) | 82U, 8, 43, 30, 42, 1, (3U << 16U) | op_store, 11, 43}))
              .empty());
    CHECK(found_in(module({(4U << 16U) | op_load, 8, 24, 22, (3U << 16U) | op_store, 11, 24})).empty());
    // A local handed to a call may be written there: none.
    CHECK(found_in(module({(5U << 16U) | 57U, 4, 46, 6, 20, (3U << 16U) | op_store, 10, 21})).empty());
    // The probe, as embedded, writes none so.
    const auto probe = instructions(probe_probe);
    REQUIRE(probe.has_value());
    CHECK(unset_outputs(*probe).empty());
}
