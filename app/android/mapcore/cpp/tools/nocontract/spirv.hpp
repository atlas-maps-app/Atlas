// A small reader and writer of SPIR-V modules (the binary form: a five-word header, then instructions whose first word
// holds the word count and the opcode). Enough for the exact-module check and its fix (nocontract.cpp) and for tests;
// the opcode and instruction numbers are the SPIR-V 1.6 specification's and GLSL.std.450's.
#pragma once

#include <cstddef>
#include <cstdint>
#include <expected>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::spirv {

inline constexpr std::uint32_t magic = 0x07230203U;
inline constexpr std::size_t header_words = 5;

// Opcodes.
enum Op : std::uint16_t {
    op_source_continued = 2,
    op_source = 3,
    op_source_extension = 4,
    op_name = 5,
    op_member_name = 6,
    op_string = 7,
    op_extension = 10,
    op_ext_inst_import = 11,
    op_ext_inst = 12,
    op_memory_model = 14,
    op_entry_point = 15,
    op_execution_mode = 16,
    op_capability = 17,
    op_function = 54,
    op_function_end = 56,
    op_decorate = 71,
    op_member_decorate = 72,
    op_decoration_group = 73,
    op_group_decorate = 74,
    op_group_member_decorate = 75,
    op_f_negate = 127,
    op_f_add = 129,
    op_f_sub = 131,
    op_f_mul = 133,
    op_f_div = 136,
    op_f_rem = 140,
    op_f_mod = 141,
    op_vector_times_scalar = 142,
    op_matrix_times_scalar = 143,
    op_vector_times_matrix = 144,
    op_matrix_times_vector = 145,
    op_matrix_times_matrix = 146,
    op_outer_product = 147,
    op_dot = 148,
    op_dpdx = 207,
    op_fwidth_coarse = 215,
    op_module_processed = 330,
    op_execution_mode_id = 331,
    op_decorate_id = 332,
    op_fma_khr = 4427, // SPV_KHR_fma (capability FMAKHR, 6030): correctly rounded in Vulkan
    op_decorate_string = 5632,
    op_member_decorate_string = 5633,
};

inline constexpr std::uint32_t decoration_no_contraction = 42;

/// One instruction: its opcode and its words (the first word included).
struct Instruction {
    std::uint16_t opcode;
    std::span<const std::uint32_t> words;

    /// The operand words after the first word.
    [[nodiscard]] std::span<const std::uint32_t> operands() const noexcept { return words.subspan(1); }
};

/// Why words are not a SPIR-V module.
enum class ModuleError : unsigned char { too_short, bad_magic, bad_instruction };

/// The instructions of a module, in order; checks the header and that every instruction fits.
[[nodiscard]] inline std::expected<std::vector<Instruction>, ModuleError>
instructions(std::span<const std::uint32_t> module) {
    if (module.size() < header_words) return std::unexpected(ModuleError::too_short);
    if (module[0] != magic) return std::unexpected(ModuleError::bad_magic);
    std::vector<Instruction> out;
    std::size_t at = header_words;
    while (at < module.size()) {
        const std::uint32_t first = module[at];
        const std::size_t count = first >> 16U;
        if (count == 0 || count > module.size() - at) return std::unexpected(ModuleError::bad_instruction);
        out.push_back({static_cast<std::uint16_t>(first & 0xFFFFU), module.subspan(at, count)});
        at += count;
    }
    return out;
}

/// A literal string operand (UTF-8, NUL-terminated, packed four bytes a word, low byte first).
[[nodiscard]] inline std::string literal_string(std::span<const std::uint32_t> words) {
    std::string s;
    for (const std::uint32_t w : words) {
        for (unsigned shift = 0; shift < 32; shift += 8) {
            const auto c = static_cast<char>((w >> shift) & 0xFFU);
            if (c == '\0') return s;
            s.push_back(c);
        }
    }
    return s;
}

/// The words of a little-endian SPIR-V file, or an empty vector when its size is not a whole number of words.
[[nodiscard]] inline std::vector<std::uint32_t> words_of(std::span<const std::byte> bytes) {
    if (bytes.size() % 4 != 0) return {};
    std::vector<std::uint32_t> words(bytes.size() / 4);
    for (std::size_t i = 0; i < words.size(); ++i) {
        words[i] = std::to_integer<std::uint32_t>(bytes[(4 * i)]) |
                   (std::to_integer<std::uint32_t>(bytes[(4 * i) + 1]) << 8U) |
                   (std::to_integer<std::uint32_t>(bytes[(4 * i) + 2]) << 16U) |
                   (std::to_integer<std::uint32_t>(bytes[(4 * i) + 3]) << 24U);
    }
    return words;
}

} // namespace atlas::spirv
