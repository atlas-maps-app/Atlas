// Outputs written from a value nothing set, which no module of the core may hold (cmake/Shaders.cmake refuses the
// module). From alpha 16 the near windows' parts' and the facades' vertex shaders (building/draw.slang's
// window_near_vs and window_facade_vs) left the walls' hatch flag unset (BuildingOut.asked, which building_fs reads):
// Slang keeps a member nothing sets as a local variable nothing writes and stores its load to the output, which SPIR-V
// leaves undefined. On the user's PowerVR phone the map stopped zooming past about z 17 from that alpha, where the
// facades' plinths and cornices come within 760 m, and past about z 19 with them switched off, where the deep windows
// come within 160 m; with both switched off, or the windows, it zoomed on, and no device was lost (alpha 20's
// switches, 29 October 2026).
//
// Refused: a store to an output variable (storage class Output, or a member of one through an access chain) of an
// OpUndef, of a load of a local variable (storage class Function, no initializer) that nothing may write (its every use
// and its access chains' a load, a debug name or a decoration), or of a copy or a composite made of such a load. A
// composite built with an OpUndef in it is let be: Slang makes a vector that way and inserts the missing component
// next (the stand-in's positions).
#pragma once

#include "budget.hpp"
#include "spirv.hpp"

#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace atlas::spirv {

inline constexpr std::uint16_t op_undef = 1;
inline constexpr std::uint16_t op_load = 61;
inline constexpr std::uint16_t op_store = 62;
inline constexpr std::uint16_t op_access_chain = 65;
inline constexpr std::uint16_t op_in_bounds_access_chain = 66;
inline constexpr std::uint16_t op_ptr_access_chain = 67;
inline constexpr std::uint16_t op_composite_construct = 80;
inline constexpr std::uint16_t op_copy_object = 83;
inline constexpr std::uint32_t storage_output = 3;

/// An output written from a value nothing set: the output variable, the function that writes it, and the local
/// variable whose load the value is (0 for an OpUndef), with their debug names where the module gives them.
struct UnsetOutput {
    std::uint32_t output = 0;
    std::string name;
    std::string function;
    std::uint32_t local = 0;
    std::string local_name;
};

[[nodiscard]] inline std::vector<UnsetOutput> unset_outputs(std::span<const Instruction> code) {
    std::unordered_map<std::uint32_t, std::uint32_t> storage; // a variable's storage class
    std::unordered_set<std::uint32_t> initialised;            // the variables with an initializer
    std::unordered_map<std::uint32_t, std::uint32_t> chains;  // an access chain's base
    std::unordered_map<std::uint32_t, const Instruction*> values;
    std::unordered_map<std::uint32_t, std::string> names;
    struct Store {
        std::uint32_t pointer;
        std::uint32_t object;
        std::uint32_t function;
    };
    std::vector<Store> stores;
    std::uint32_t function = 0;
    for (const Instruction& in : code) {
        const auto ops = in.operands();
        switch (in.opcode) {
        case op_name:
            if (!ops.empty()) names[ops[0]] = literal_string(ops.subspan(1));
            break;
        case op_variable:
            if (ops.size() >= 3) {
                storage[ops[1]] = ops[2];
                if (ops.size() >= 4) initialised.insert(ops[1]);
            }
            break;
        case op_access_chain:
        case op_in_bounds_access_chain:
        case op_ptr_access_chain:
            if (ops.size() >= 3) chains[ops[1]] = ops[2];
            break;
        case op_undef:
        case op_load:
        case op_composite_construct:
        case op_copy_object:
            if (ops.size() >= 2) values[ops[1]] = &in;
            break;
        case op_function:
            if (ops.size() >= 2) function = ops[1];
            break;
        case op_store:
            if (ops.size() >= 2) stores.push_back({.pointer = ops[0], .object = ops[1], .function = function});
            break;
        default: break;
        }
    }
    const auto root = [&chains](std::uint32_t p) {
        for (auto it = chains.find(p); it != chains.end(); it = chains.find(p)) p = it->second;
        return p;
    };
    // The locals nothing may write: every use of one or of its access chains a load's pointer, a chain's base, a debug
    // name or a decoration (a literal that happens to equal its id counts as a write: the check then passes over it).
    std::unordered_set<std::uint32_t> unwritten;
    for (const auto& [id, s] : storage) {
        if (s == storage_function && !initialised.contains(id)) unwritten.insert(id);
    }
    for (const Instruction& in : code) {
        const auto ops = in.operands();
        if (in.opcode == op_name || in.opcode == op_member_name || in.opcode == op_decorate ||
            in.opcode == op_member_decorate || in.opcode == op_decorate_id || in.opcode == op_decorate_string ||
            in.opcode == op_member_decorate_string || in.opcode == op_variable) {
            continue;
        }
        const bool chain =
            in.opcode == op_access_chain || in.opcode == op_in_bounds_access_chain || in.opcode == op_ptr_access_chain;
        for (std::size_t i = 0; i < ops.size(); ++i) {
            if ((in.opcode == op_load || chain) && i <= 2) continue; // its type, its result, its pointer or base
            const std::uint32_t r = root(ops[i]);
            if (r != ops[i] || storage.contains(r)) unwritten.erase(r);
        }
    }
    // The local whose load `v` is, where nothing writes it (0: none).
    const auto unset_local = [&](std::uint32_t v) -> std::uint32_t {
        const auto it = values.find(v);
        if (it == values.end() || it->second->opcode != op_load) return 0;
        const std::uint32_t r = root(it->second->operands()[2]);
        return unwritten.contains(r) ? r : 0U;
    };
    std::vector<UnsetOutput> out;
    for (const Store& s : stores) {
        const std::uint32_t target = root(s.pointer);
        const auto st = storage.find(target);
        if (st == storage.end() || st->second != storage_output) continue;
        // through copies to what was stored: an OpUndef, a load of a local nothing writes, or a composite of such loads
        std::uint32_t v = s.object;
        for (int hops = 0; hops < 8; ++hops) {
            const auto it = values.find(v);
            if (it == values.end() || it->second->opcode != op_copy_object) break;
            v = it->second->operands()[2];
        }
        const auto it = values.find(v);
        if (it == values.end()) continue;
        std::uint32_t local = 0;
        bool unset = it->second->opcode == op_undef;
        if (it->second->opcode == op_load) {
            local = unset_local(v);
            unset = local != 0;
        } else if (it->second->opcode == op_composite_construct) {
            for (const std::uint32_t part : it->second->operands().subspan(2)) {
                local = unset_local(part);
                if (local != 0) break;
            }
            unset = local != 0;
        }
        if (!unset) continue;
        const auto name_of = [&names](std::uint32_t id) {
            const auto n = names.find(id);
            return n != names.end() ? n->second : std::string();
        };
        out.push_back({.output = target,
                       .name = name_of(target),
                       .function = name_of(s.function),
                       .local = local,
                       .local_name = name_of(local)});
    }
    return out;
}

} // namespace atlas::spirv
