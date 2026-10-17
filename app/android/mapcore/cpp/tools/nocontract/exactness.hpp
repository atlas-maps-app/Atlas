// The exact-module rules (plan-vector T6). Vulkan makes add, subtract, multiply and conversions correctly rounded but
// lets a driver combine and rearrange them unless each carries NoContraction; it leaves the rounding of division,
// sqrt, dot products, matrix products, lerp and the transcendentals open, and GLSL.std.450's Fma "is not required to be
// implemented in hardware as a fused operation"; only OpFmaKHR is a correctly rounded fused multiply-add. So a module
// is exact when every float add, subtract and multiply carries NoContraction and it uses none of the open operations.
// check_exact lists every place a module breaks that; add_no_contraction decorates what lacks the decoration.
//
// What the rule leaves to the code: unless an entry point declares the SignedZeroInfNanPreserve execution mode
// (SPV_KHR_float_controls: Vulkan 1.2, or VK_KHR_shader_float_controls, and the device's
// shaderSignedZeroInfNanPreserveFloat32), Vulkan lets a driver ignore the sign of zero and assume no infinity or NaN,
// which NoContraction does not forbid (Vulkan's SPIR-V environment, "Precision and Operation of SPIR-V Instructions").
// The rule does not require the mode, since declaring it makes a module invalid on the Vulkan 1.1 phones without the
// extension, which the core targets. So an exact module's results must not depend on the sign of a zero, an infinity
// or a NaN; D16's byte check on each device is the backstop, and a variant that declares the mode where a device has
// it is the quirk table's to add (plan-vector T6).
#pragma once

#include "spirv.hpp"

#include <algorithm>
#include <array>
#include <cstdint>
#include <map>
#include <set>
#include <span>
#include <string>
#include <utility>
#include <vector>

namespace atlas::spirv {

/// One place where a module is not exact.
struct Finding {
    std::uint32_t id = 0; ///< the instruction's result id
    std::string name;     ///< the result's OpName, when it has one
    std::string function; ///< the function the instruction is in
    std::string what;     ///< what is wrong
};

/// What the check found.
struct Exactness {
    std::size_t float_ops = 0; ///< adds, subtracts and multiplies, which must carry NoContraction
    std::size_t decorated = 0; ///< of those, the ones that do
    std::size_t fma_khr = 0;   ///< correctly rounded fused multiply-adds
    std::vector<Finding> findings;

    [[nodiscard]] bool exact() const noexcept { return findings.empty(); }
};

namespace detail {

/// Operations whose result is exact only with NoContraction.
[[nodiscard]] constexpr bool needs_no_contraction(std::uint16_t op) noexcept {
    return op == op_f_add || op == op_f_sub || op == op_f_mul || op == op_vector_times_scalar ||
           op == op_matrix_times_scalar;
}

/// Operations whose rounding Vulkan leaves open, with the reason.
[[nodiscard]] constexpr const char* open_operation(std::uint16_t op) noexcept {
    switch (op) {
    case op_f_div: return "OpFDiv (2.5 ULP in Vulkan): multiply by an exact reciprocal";
    case op_f_rem: return "OpFRem (precision left to the driver)";
    case op_f_mod: return "OpFMod (precision left to the driver)";
    case op_vector_times_matrix: return "OpVectorTimesMatrix (summation order and fusion left to the driver)";
    case op_matrix_times_vector: return "OpMatrixTimesVector (summation order and fusion left to the driver)";
    case op_matrix_times_matrix: return "OpMatrixTimesMatrix (summation order and fusion left to the driver)";
    case op_outer_product: return "OpOuterProduct (left to the driver)";
    case op_dot: return "OpDot (summation order and fusion left to the driver)";
    default: break;
    }
    if (op >= op_dpdx && op <= op_fwidth_coarse) return "a derivative (approximate)";
    return nullptr;
}

/// GLSL.std.450 instructions that are exact: rounding to integers, absolute value, sign, min, max, clamp, step,
/// fractional parts, exponent manipulation, bit reinterpretation and bit search.
[[nodiscard]] constexpr bool exact_glsl_instruction(std::uint32_t instruction) noexcept {
    constexpr std::array exact{
        2U,  // RoundEven
        3U,  // Trunc
        4U,  // FAbs
        5U,  // SAbs
        6U,  // FSign
        7U,  // SSign
        8U,  // Floor
        9U,  // Ceil
        10U, // Fract
        35U, // Modf
        36U, // ModfStruct
        37U, // FMin
        38U, // UMin
        39U, // SMin
        40U, // FMax
        41U, // UMax
        42U, // SMax
        43U, // FClamp
        44U, // UClamp
        45U, // SClamp
        48U, // Step
        51U, // Frexp
        52U, // FrexpStruct
        53U, // Ldexp
        59U, // PackDouble2x32
        65U, // UnpackDouble2x32
        73U, // FindILsb
        74U, // FindSMsb
        75U, // FindUMsb
        79U, // NMin
        80U, // NMax
        81U, // NClamp
    };
    return std::ranges::find(exact, instruction) != exact.end();
}

/// Why a GLSL.std.450 instruction that is not exact is open.
[[nodiscard]] constexpr const char* glsl_reason(std::uint32_t instruction) noexcept {
    switch (instruction) {
    case 50U: return "GLSL.std.450 Fma is not guaranteed to be fused: use OpFmaKHR or the emulation";
    case 46U: return "GLSL.std.450 FMix (lerp) rounds as the driver likes: write a + t * (b - a)";
    case 31U: return "GLSL.std.450 Sqrt is not correctly rounded in Vulkan";
    case 32U: return "GLSL.std.450 InverseSqrt is approximate";
    case 1U: return "GLSL.std.450 Round leaves halfway cases to the driver: use RoundEven";
    default: return "a GLSL.std.450 instruction whose precision Vulkan leaves to the driver";
    }
}

struct ModuleFacts {
    std::map<std::uint32_t, std::string> names;
    std::set<std::uint32_t> no_contraction;
    std::map<std::uint32_t, std::string> ext_sets;
};

[[nodiscard]] inline ModuleFacts facts(std::span<const Instruction> code) {
    ModuleFacts f;
    std::set<std::uint32_t> groups_with_no_contraction;
    for (const Instruction& in : code) {
        const auto ops = in.operands();
        if (in.opcode == op_name && ops.size() >= 2) {
            f.names.insert_or_assign(ops[0], literal_string(ops.subspan(1)));
        } else if (in.opcode == op_ext_inst_import && ops.size() >= 2) {
            f.ext_sets.insert_or_assign(ops[0], literal_string(ops.subspan(1)));
        } else if (in.opcode == op_decorate && ops.size() >= 2 && ops[1] == decoration_no_contraction) {
            f.no_contraction.insert(ops[0]);
            groups_with_no_contraction.insert(ops[0]);
        }
    }
    // Decoration groups (deprecated, but valid): a group decorated NoContraction passes it to its targets.
    for (const Instruction& in : code) {
        const auto ops = in.operands();
        if (in.opcode == op_group_decorate && !ops.empty() && groups_with_no_contraction.contains(ops[0])) {
            for (const std::uint32_t target : ops.subspan(1)) f.no_contraction.insert(target);
        }
    }
    return f;
}

[[nodiscard]] inline std::string name_of(const ModuleFacts& f, std::uint32_t id) {
    const auto it = f.names.find(id);
    return it == f.names.end() ? std::string() : it->second;
}

} // namespace detail

/// Checks a module against the exact-module rules.
[[nodiscard]] inline Exactness check_exact(std::span<const Instruction> code) {
    const detail::ModuleFacts f = detail::facts(code);
    Exactness result;
    std::string function;
    for (const Instruction& in : code) {
        const auto ops = in.operands();
        const std::uint32_t id = ops.size() >= 2 ? ops[1] : 0;
        auto find = [&](std::string what) {
            result.findings.push_back({id, detail::name_of(f, id), function, std::move(what)});
        };
        if (in.opcode == op_function && ops.size() >= 2) {
            function = detail::name_of(f, ops[1]);
        } else if (in.opcode == op_function_end) {
            function.clear();
        } else if (detail::needs_no_contraction(in.opcode)) {
            ++result.float_ops;
            if (f.no_contraction.contains(id)) {
                ++result.decorated;
            } else {
                find("a float operation without NoContraction");
            }
        } else if (in.opcode == op_fma_khr) {
            ++result.fma_khr;
        } else if (const char* reason = detail::open_operation(in.opcode)) {
            find(reason);
        } else if (in.opcode == op_ext_inst && ops.size() >= 4) {
            const auto set = f.ext_sets.find(ops[2]);
            const std::string set_name = set == f.ext_sets.end() ? std::string() : set->second;
            if (set_name == "GLSL.std.450") {
                if (!detail::exact_glsl_instruction(ops[3])) find(detail::glsl_reason(ops[3]));
            } else if (!set_name.starts_with("NonSemantic.")) {
                find("an instruction of the extended set '" + set_name + "'");
            }
        }
    }
    return result;
}

/// The module with NoContraction added to every add, subtract and multiply that lacks it, and how many it added.
[[nodiscard]] inline std::pair<std::vector<std::uint32_t>, std::size_t>
add_no_contraction(std::span<const std::uint32_t> module, std::span<const Instruction> code) {
    const detail::ModuleFacts f = detail::facts(code);
    std::vector<std::uint32_t> missing;
    for (const Instruction& in : code) {
        const auto ops = in.operands();
        if (detail::needs_no_contraction(in.opcode) && ops.size() >= 2 && !f.no_contraction.contains(ops[1])) {
            missing.push_back(ops[1]);
        }
    }
    // The decorations go after the module's preamble, debug names and existing annotations, where SPIR-V's logical
    // layout puts annotations: before the first type, constant or variable.
    auto preamble = [](std::uint16_t op) {
        switch (op) {
        case op_capability:
        case op_extension:
        case op_ext_inst_import:
        case op_memory_model:
        case op_entry_point:
        case op_execution_mode:
        case op_execution_mode_id:
        case op_string:
        case op_source_extension:
        case op_source:
        case op_source_continued:
        case op_name:
        case op_member_name:
        case op_module_processed:
        case op_decorate:
        case op_member_decorate:
        case op_decoration_group:
        case op_group_decorate:
        case op_group_member_decorate:
        case op_decorate_id:
        case op_decorate_string:
        case op_member_decorate_string: return true;
        default: return false;
        }
    };
    std::vector<std::uint32_t> out(module.begin(), module.begin() + static_cast<std::ptrdiff_t>(header_words));
    bool inserted = false;
    for (const Instruction& in : code) {
        if (!inserted && !preamble(in.opcode)) {
            for (const std::uint32_t id : missing) {
                out.insert(out.end(), {(3U << 16U) | op_decorate, id, decoration_no_contraction});
            }
            inserted = true;
        }
        out.insert(out.end(), in.words.begin(), in.words.end());
    }
    if (!inserted) {
        for (const std::uint32_t id : missing)
            out.insert(out.end(), {(3U << 16U) | op_decorate, id, decoration_no_contraction});
    }
    return {std::move(out), missing.size()};
}

} // namespace atlas::spirv
