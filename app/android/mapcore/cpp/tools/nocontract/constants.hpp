// Specialization constants, which no module of the core may hold (cmake/Shaders.cmake refuses the module). On 17
// October 2026 PowerVR's Vulkan driver crashed in its shader compiler, in SpvConstCalculated's destructor (the vendor's
// libufwriter.so) while it destroyed the SpvModule of a graphics pipeline VK bench made: the stand-in's composite,
// whose sample count was a specialization constant and held a constant computed from it (OpSpecConstantOp IAdd). A
// specialization constant leaves the folding to each driver's compiler at pipeline creation, on every phone; a variant
// is a module of its own instead (a "// atlas: variant" line, or a file that defines a macro and includes another),
// compiled and checked once, here.
//
// Refused, by the SPIR-V grammar of the pinned Slang (grammar.hpp, derived by grammar.py from the table its compiler
// embeds, so a module can hold nothing Slang's spirv_asm could write that this check does not know): every instruction
// that makes one, each whose name begins OpSpecConstant (the core's OpSpecConstantTrue, False, Constant, Composite and
// Op, 48 to 52, and the extensions' 4462, 5104, 5148, 6092 and 6251 to 6253 in Slang 2026.19); and any SpecId
// decoration, by any instruction that decorates (each whose name begins OpDecorate or OpMemberDecorate), whatever it
// decorates.
#pragma once

#include "grammar.hpp"
#include "spirv.hpp"

#include <algorithm>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <tuple>
#include <utility>
#include <vector>

namespace atlas::spirv {

/// The opcode of the grammar's instruction `name` that makes a specialization constant (0 if there is none).
[[nodiscard]] consteval std::uint16_t spec_constant_opcode(std::string_view name) {
    for (const grammar::Named& n : grammar::spec_constant_instructions) {
        if (n.name == name) return n.opcode;
    }
    return 0;
}

inline constexpr std::uint16_t op_spec_constant_true = spec_constant_opcode("OpSpecConstantTrue");
inline constexpr std::uint16_t op_spec_constant_false = spec_constant_opcode("OpSpecConstantFalse");
inline constexpr std::uint16_t op_spec_constant = spec_constant_opcode("OpSpecConstant");
inline constexpr std::uint16_t op_spec_constant_composite = spec_constant_opcode("OpSpecConstantComposite");
inline constexpr std::uint16_t op_spec_constant_op = spec_constant_opcode("OpSpecConstantOp");
inline constexpr std::uint16_t op_spec_constant_composite_replicate_ext =
    spec_constant_opcode("OpSpecConstantCompositeReplicateEXT");
inline constexpr std::uint16_t op_spec_constant_composite_continued_intel =
    spec_constant_opcode("OpSpecConstantCompositeContinuedINTEL");
inline constexpr std::uint32_t decoration_spec_id = grammar::decoration_spec_id;
static_assert(op_spec_constant_true == 48 && op_spec_constant_op == 52 &&
                  op_spec_constant_composite_continued_intel != 0,
              "grammar.hpp lacks the core instructions that make specialization constants");

/// Whether an instruction of `opcode` makes a specialization constant.
[[nodiscard]] constexpr bool makes_spec_constant(std::uint16_t opcode) noexcept {
    return std::ranges::any_of(grammar::spec_constant_instructions,
                               [opcode](const grammar::Named& n) { return n.opcode == opcode; });
}

/// The decorating instruction of `opcode`, if it is one.
[[nodiscard]] constexpr const grammar::Decorating* decorating(std::uint16_t opcode) noexcept {
    for (const grammar::Decorating& d : grammar::decorating_instructions) {
        if (d.opcode == opcode) return &d;
    }
    return nullptr;
}

/// Whether an instruction of `opcode` declares a type (its first operand is the type's id).
[[nodiscard]] constexpr bool declares_type(std::uint16_t opcode) noexcept {
    return std::ranges::find(grammar::type_declarations, opcode) != grammar::type_declarations.end();
}

/// One specialization constant of a module, or a SpecId decoration of an id that is none.
struct SpecConstant {
    std::uint32_t id = 0;        // its result id where the module shows it (0 when not: below), else the target
    std::uint16_t opcode = 0;    // the instruction that makes it; for a SpecId alone, the decorating one
    std::uint32_t operation = 0; // the operation of an OpSpecConstantOp (OpIAdd is 128), else 0
    std::string name;            // its OpName, if the module names it
    std::optional<std::uint32_t> spec_id; // its SpecId decoration (the shader's constant_id), if it has one
};

/// The instruction's name in the grammar (a decoration's: "a SpecId decoration (OpDecorate)").
[[nodiscard]] inline std::string spec_constant_opname(std::uint16_t opcode) {
    for (const grammar::Named& n : grammar::spec_constant_instructions) {
        if (n.opcode == opcode) return std::string(n.name);
    }
    if (const grammar::Decorating* d = decorating(opcode); d != nullptr)
        return "a SpecId decoration (" + std::string(d->name) + ")";
    return "not a specialization constant";
}

/// The module's specialization constants, in order, with their names and SpecIds where the module gives them; then
/// every SpecId decoration of an id that is no specialization constant. A constant's result id is known where its
/// first operand is a type the module declared (a typed result: the id follows its type, as SPIR-V lays out every
/// one); an untyped one (OpSpecConstantStringAMDX) or one with no result (OpSpecConstantCompositeContinuedINTEL) is
/// found by its instruction alone.
[[nodiscard]] inline std::vector<SpecConstant> specialization_constants(std::span<const Instruction> code) {
    std::vector<SpecConstant> found;
    std::vector<std::pair<std::uint32_t, std::string>> names;
    std::vector<std::uint32_t> types; // the ids of the types declared so far
    // Each SpecId decoration: its target, the decorating instruction and the constant_id it gives.
    std::vector<std::tuple<std::uint32_t, std::uint16_t, std::optional<std::uint32_t>>> spec_ids;
    for (const Instruction& in : code) {
        const auto ops = in.operands();
        if (makes_spec_constant(in.opcode)) {
            // <result type> <result id> ...; OpSpecConstantOp's third operand is its operation.
            const bool typed = ops.size() >= 2 && std::ranges::find(types, ops[0]) != types.end();
            found.push_back({.id = typed ? ops[1] : 0U,
                             .opcode = in.opcode,
                             .operation = in.opcode == op_spec_constant_op && ops.size() >= 3 ? ops[2] : 0U,
                             .name = {},
                             .spec_id = std::nullopt});
            continue;
        }
        if (ops.empty()) continue;
        if (declares_type(in.opcode)) {
            types.push_back(ops[0]);
            continue;
        }
        if (in.opcode == op_name && ops.size() >= 2) {
            names.emplace_back(ops[0], literal_string(ops.subspan(1)));
            continue;
        }
        // OpDecorate <target> <decoration> <literals>; the member forms put the member's index before the decoration.
        const grammar::Decorating* d = decorating(in.opcode);
        if (d == nullptr) continue;
        const std::size_t at = d->member ? 2 : 1;
        if (ops.size() <= at || ops[at] != decoration_spec_id) continue;
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
