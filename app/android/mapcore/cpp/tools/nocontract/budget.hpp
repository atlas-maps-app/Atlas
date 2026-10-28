// A compute entry point's size, as a phone's shader compiler takes it: its module's bytes (the entry point compiled
// alone, as cmake/Shaders.cmake compiles each one for this check) and its threads' local variables, the arrays among
// them, and their bytes. On the user's PowerVR phone the map froze from alpha 16 at about z 17, the first frame that
// ran the windows' decide stage (windows_cs), which had grown from 211,280 bytes with 17 local arrays of 780 bytes a
// thread (alpha 15, which ran it over the same walls) to 333,400 bytes with 53 of 5.1 KB (the audit of 28 October
// 2026). The test spirv_budget holds every compute entry point to alpha 15's windows_cs (shaders/spirv.budget), so a
// stage that grows past it is split instead.
#pragma once

#include "spirv.hpp"

#include <cstddef>
#include <cstdint>
#include <span>
#include <unordered_map>

namespace atlas::spirv {

inline constexpr std::uint16_t op_type_bool = 20;
inline constexpr std::uint16_t op_type_int = 21;
inline constexpr std::uint16_t op_type_float = 22;
inline constexpr std::uint16_t op_type_vector = 23;
inline constexpr std::uint16_t op_type_matrix = 24;
inline constexpr std::uint16_t op_type_array = 28;
inline constexpr std::uint16_t op_type_struct = 30;
inline constexpr std::uint16_t op_type_pointer = 32;
inline constexpr std::uint16_t op_constant = 43;
inline constexpr std::uint16_t op_variable = 59;
inline constexpr std::uint32_t storage_function = 7;

/// A module's size and its functions' local variables (each OpVariable of storage class Function, whatever function
/// holds it: a thread holds them all at once at worst), the arrays among them, and their bytes (a bool 4, each struct
/// its members' sum, unpadded).
struct Budget {
    std::size_t bytes = 0;
    std::size_t locals = 0;
    std::size_t local_bytes = 0;
    std::size_t arrays = 0;
    std::size_t array_bytes = 0;
};

[[nodiscard]] inline Budget budget_of(std::span<const std::uint32_t> module, std::span<const Instruction> code) {
    Budget b;
    b.bytes = module.size() * sizeof(std::uint32_t);
    std::unordered_map<std::uint32_t, std::size_t> sizes;     // a type's bytes
    std::unordered_map<std::uint32_t, std::uint32_t> values;  // a 32-bit integer constant's value
    std::unordered_map<std::uint32_t, std::uint32_t> pointee; // a Function pointer type's pointee
    std::unordered_map<std::uint32_t, bool> is_array;
    const auto size = [&sizes](std::uint32_t id) {
        const auto it = sizes.find(id);
        return it != sizes.end() ? it->second : std::size_t{0};
    };
    for (const Instruction& in : code) {
        const auto ops = in.operands();
        switch (in.opcode) {
        case op_type_bool:
            if (!ops.empty()) sizes[ops[0]] = 4;
            break;
        case op_type_int:
        case op_type_float:
            if (ops.size() >= 2) sizes[ops[0]] = ops[1] / 8;
            break;
        case op_type_vector:
        case op_type_matrix:
            if (ops.size() >= 3) sizes[ops[0]] = size(ops[1]) * ops[2];
            break;
        case op_type_array:
            if (ops.size() >= 3) {
                const auto n = values.find(ops[2]);
                sizes[ops[0]] = size(ops[1]) * (n != values.end() ? n->second : 0U);
                is_array[ops[0]] = true;
            }
            break;
        case op_type_struct:
            if (!ops.empty()) {
                std::size_t s = 0;
                for (const std::uint32_t member : ops.subspan(1)) s += size(member);
                sizes[ops[0]] = s;
            }
            break;
        case op_type_pointer:
            if (ops.size() >= 3 && ops[1] == storage_function) pointee[ops[0]] = ops[2];
            break;
        case op_constant:
            if (ops.size() == 3) values[ops[1]] = ops[2];
            break;
        case op_variable:
            if (ops.size() >= 3 && ops[2] == storage_function) {
                const auto p = pointee.find(ops[0]);
                if (p == pointee.end()) break;
                const std::size_t s = size(p->second);
                ++b.locals;
                b.local_bytes += s;
                if (is_array.contains(p->second)) {
                    ++b.arrays;
                    b.array_bytes += s;
                }
            }
            break;
        default: break;
        }
    }
    return b;
}

} // namespace atlas::spirv
