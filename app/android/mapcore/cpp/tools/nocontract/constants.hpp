// Specialization constants, which no module of the core may hold (cmake/Shaders.cmake refuses the module). On 17
// October 2026 PowerVR's Vulkan driver crashed in its shader compiler, in SpvConstCalculated's destructor (the vendor's
// libufwriter.so) while it destroyed the SpvModule of a graphics pipeline VK bench made: the stand-in's composite,
// whose sample count was a specialization constant and held a constant computed from it (OpSpecConstantOp IAdd). A
// specialization constant leaves the folding to each driver's compiler at pipeline creation, on every phone; a variant
// is a module of its own instead (a "// atlas: variant" line, or a file that defines a macro and includes another),
// compiled and checked once, here.
//
// Refused: every instruction that makes one, the core's OpSpecConstantTrue, False, Constant, Composite and Op (opcodes
// 48 to 52) and the extensions' OpSpecConstantCompositeReplicateEXT (4462, SPV_EXT_replicated_composites) and
// OpSpecConstantCompositeContinuedINTEL (6092, SPV_INTEL_long_composites); and any SpecId decoration (decoration 1),
// whatever it decorates.
#pragma once

#include "spirv.hpp"

#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <tuple>
#include <utility>
#include <vector>

namespace atlas::spirv {

inline constexpr std::uint16_t op_spec_constant_true = 48;
inline constexpr std::uint16_t op_spec_constant_false = 49;
inline constexpr std::uint16_t op_spec_constant = 50;
inline constexpr std::uint16_t op_spec_constant_composite = 51;
inline constexpr std::uint16_t op_spec_constant_op = 52;
inline constexpr std::uint16_t op_spec_constant_composite_replicate_ext = 4462;
inline constexpr std::uint16_t op_spec_constant_composite_continued_intel = 6092;
inline constexpr std::uint32_t decoration_spec_id = 1;

/// Whether an instruction of `opcode` makes a specialization constant.
[[nodiscard]] constexpr bool makes_spec_constant(std::uint16_t opcode) noexcept {
    return (opcode >= op_spec_constant_true && opcode <= op_spec_constant_op) ||
           opcode == op_spec_constant_composite_replicate_ext || opcode == op_spec_constant_composite_continued_intel;
}

/// One specialization constant of a module, or a SpecId decoration of an id that is none.
struct SpecConstant {
    std::uint32_t id = 0;                 // its result id (0 for OpSpecConstantCompositeContinuedINTEL, which has none)
    std::uint16_t opcode = 0;             // the instruction that makes it; for a SpecId alone, the decorating one
    std::uint32_t operation = 0;          // the operation of an OpSpecConstantOp (OpIAdd is 128), else 0
    std::string name;                     // its OpName, if the module names it
    std::optional<std::uint32_t> spec_id; // its SpecId decoration (the shader's constant_id), if it has one
};

/// The instruction's name in the specification (a decoration's: "a SpecId decoration (OpDecorate)").
[[nodiscard]] inline std::string_view spec_constant_opname(std::uint16_t opcode) noexcept {
    switch (opcode) {
    case op_spec_constant_true: return "OpSpecConstantTrue";
    case op_spec_constant_false: return "OpSpecConstantFalse";
    case op_spec_constant: return "OpSpecConstant";
    case op_spec_constant_composite: return "OpSpecConstantComposite";
    case op_spec_constant_op: return "OpSpecConstantOp";
    case op_spec_constant_composite_replicate_ext: return "OpSpecConstantCompositeReplicateEXT";
    case op_spec_constant_composite_continued_intel: return "OpSpecConstantCompositeContinuedINTEL";
    case op_decorate: return "a SpecId decoration (OpDecorate)";
    case op_decorate_id: return "a SpecId decoration (OpDecorateId)";
    case op_decorate_string: return "a SpecId decoration (OpDecorateString)";
    case op_member_decorate: return "a SpecId decoration (OpMemberDecorate)";
    case op_member_decorate_string: return "a SpecId decoration (OpMemberDecorateString)";
    default: return "not a specialization constant";
    }
}

/// The module's specialization constants, in order, with their names and SpecIds where the module gives them; then
/// every SpecId decoration of an id that is no specialization constant.
[[nodiscard]] inline std::vector<SpecConstant> specialization_constants(std::span<const Instruction> code) {
    std::vector<SpecConstant> found;
    std::vector<std::pair<std::uint32_t, std::string>> names;
    // Each SpecId decoration: its target, the decorating instruction and the constant_id it gives.
    std::vector<std::tuple<std::uint32_t, std::uint16_t, std::optional<std::uint32_t>>> spec_ids;
    for (const Instruction& in : code) {
        const auto ops = in.operands();
        if (makes_spec_constant(in.opcode)) {
            // <result type> <result id> ...; OpSpecConstantOp's third operand is its operation; the INTEL
            // continuation has no result, only more constituents of the composite before it.
            const bool result = in.opcode != op_spec_constant_composite_continued_intel && ops.size() >= 2;
            found.push_back({.id = result ? ops[1] : 0U,
                             .opcode = in.opcode,
                             .operation = in.opcode == op_spec_constant_op && ops.size() >= 3 ? ops[2] : 0U,
                             .name = {},
                             .spec_id = std::nullopt});
            continue;
        }
        if (ops.empty()) continue;
        if (in.opcode == op_name && ops.size() >= 2) {
            names.emplace_back(ops[0], literal_string(ops.subspan(1)));
            continue;
        }
        // OpDecorate <target> <decoration> <literals>; the member forms put the member's index before the decoration.
        const bool member = in.opcode == op_member_decorate || in.opcode == op_member_decorate_string;
        const bool decorates =
            member || in.opcode == op_decorate || in.opcode == op_decorate_id || in.opcode == op_decorate_string;
        const std::size_t at = member ? 2 : 1;
        if (!decorates || ops.size() <= at || ops[at] != decoration_spec_id) continue;
        spec_ids.emplace_back(ops[0], in.opcode,
                              ops.size() > at + 1 ? std::optional<std::uint32_t>(ops[at + 1]) : std::nullopt);
    }
    for (const auto& [target, opcode, value] : spec_ids) {
        SpecConstant* constant = nullptr;
        for (SpecConstant& c : found) {
            if (c.id != 0 && c.id == target && makes_spec_constant(c.opcode) && opcode == op_decorate) constant = &c;
        }
        if (constant != nullptr) {
            constant->spec_id = value;
        } else {
            found.push_back({.id = target, .opcode = opcode, .operation = 0, .name = {}, .spec_id = value});
        }
    }
    for (SpecConstant& c : found) {
        for (const auto& [id, name] : names) {
            if (c.id != 0 && id == c.id) c.name = name;
        }
    }
    return found;
}

} // namespace atlas::spirv
