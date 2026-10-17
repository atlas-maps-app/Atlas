// Specialization constants, which no module of the core may hold (cmake/Shaders.cmake refuses the module). On 17
// October 2026 PowerVR's Vulkan driver crashed in its shader compiler, in SpvConstCalculated's destructor (the vendor's
// libufwriter.so) while it destroyed the SpvModule of a graphics pipeline VK bench made: the stand-in's composite,
// whose sample count was a specialization constant and held a constant computed from it (OpSpecConstantOp IAdd). A
// specialization constant leaves the folding to each driver's compiler at pipeline creation, on every phone; a variant
// is a module of its own instead (a "// atlas: variant" line, or a file that defines a macro and includes another),
// compiled and checked once, here.
#pragma once

#include "spirv.hpp"

#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::spirv {

inline constexpr std::uint16_t op_spec_constant_true = 48;
inline constexpr std::uint16_t op_spec_constant_false = 49;
inline constexpr std::uint16_t op_spec_constant = 50;
inline constexpr std::uint16_t op_spec_constant_composite = 51;
inline constexpr std::uint16_t op_spec_constant_op = 52;
inline constexpr std::uint32_t decoration_spec_id = 1;

/// One specialization constant of a module.
struct SpecConstant {
    std::uint32_t id = 0;                 // its result id
    std::uint16_t opcode = 0;             // 48 to 52
    std::uint32_t operation = 0;          // the operation of an OpSpecConstantOp (OpIAdd is 128), else 0
    std::string name;                     // its OpName, if the module names it
    std::optional<std::uint32_t> spec_id; // its SpecId decoration (the shader's constant_id), if it has one
};

/// The instruction's name in the specification.
[[nodiscard]] inline std::string_view spec_constant_opname(std::uint16_t opcode) noexcept {
    switch (opcode) {
    case op_spec_constant_true: return "OpSpecConstantTrue";
    case op_spec_constant_false: return "OpSpecConstantFalse";
    case op_spec_constant: return "OpSpecConstant";
    case op_spec_constant_composite: return "OpSpecConstantComposite";
    case op_spec_constant_op: return "OpSpecConstantOp";
    default: return "not a specialization constant";
    }
}

/// The module's specialization constants, in order, with their names and SpecIds where the module gives them.
[[nodiscard]] inline std::vector<SpecConstant> specialization_constants(std::span<const Instruction> code) {
    std::vector<SpecConstant> found;
    for (const Instruction& in : code) {
        const auto ops = in.operands();
        // Every one of them: <result type> <result id> ...; OpSpecConstantOp's third operand is its operation.
        if (in.opcode < op_spec_constant_true || in.opcode > op_spec_constant_op || ops.size() < 2) continue;
        found.push_back({.id = ops[1],
                         .opcode = in.opcode,
                         .operation = in.opcode == op_spec_constant_op && ops.size() >= 3 ? ops[2] : 0U,
                         .name = {},
                         .spec_id = std::nullopt});
    }
    if (found.empty()) return found;
    for (const Instruction& in : code) {
        const auto ops = in.operands();
        if (ops.empty()) continue;
        for (SpecConstant& c : found) {
            if (ops[0] != c.id) continue;
            if (in.opcode == op_name && ops.size() >= 2) c.name = literal_string(ops.subspan(1));
            if (in.opcode == op_decorate && ops.size() >= 3 && ops[1] == decoration_spec_id) c.spec_id = ops[2];
        }
    }
    return found;
}

} // namespace atlas::spirv
