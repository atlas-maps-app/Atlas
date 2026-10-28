// nocontract: the build's check of exact shader modules (plan-vector T6, run by cmake/Shaders.cmake).
//
//   nocontract check <module.spv>                exit 0 when the module is exact, else list why and exit 1
//   nocontract fix <in.spv> <out.spv>            add NoContraction to every float add, subtract and multiply lacking it
//   nocontract capabilities <in.spv> <out.spv>   remove capabilities the module declares but does not need
//                                                (capabilities.hpp); run on every module, exact or not
//   nocontract constants <module.spv> <source>   exit 0 when the module holds no specialization constant, else name
//                                                each, say why the core refuses them (constants.hpp, by the pinned
//                                                Slang's SPIR-V grammar: grammar.hpp) and exit 1; run on every module,
//                                                before it is embedded
//   nocontract budget <module.spv>               print the module's bytes, its local arrays and their bytes, its local
//                                                variables and theirs (budget.hpp); run on each compute entry point
//                                                compiled alone, for the test spirv_budget
//
// Slang 2026.19 emits NoContraction on every float operation under -fp-mode precise, so fix adds nothing today; it
// stays in the build so a compiler that stops emitting it still yields exact modules, and check still proves them.
#include "budget.hpp"
#include "capabilities.hpp"
#include "constants.hpp"
#include "exactness.hpp"
#include "spirv.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace {

struct FileCloser {
    void operator()(std::FILE* f) const noexcept { (void)std::fclose(f); }
};
using File = std::unique_ptr<std::FILE, FileCloser>;

std::vector<std::byte> read_file(const char* path) {
    const File f(std::fopen(path, "rb"));
    std::vector<std::byte> bytes;
    if (!f) return bytes;
    std::array<std::byte, 65536> chunk{};
    std::size_t n = 0;
    while ((n = std::fread(chunk.data(), 1, chunk.size(), f.get())) > 0) {
        bytes.insert(bytes.end(), chunk.begin(), chunk.begin() + static_cast<std::ptrdiff_t>(n));
    }
    return bytes;
}

bool write_file(const char* path, std::span<const std::uint32_t> words) {
    const File f(std::fopen(path, "wb"));
    if (!f) return false;
    std::vector<unsigned char> bytes;
    bytes.reserve(words.size() * 4);
    for (const std::uint32_t w : words) {
        for (unsigned shift = 0; shift < 32; shift += 8)
            bytes.push_back(static_cast<unsigned char>((w >> shift) & 0xFFU));
    }
    return std::fwrite(bytes.data(), 1, bytes.size(), f.get()) == bytes.size();
}

int usage() {
    (void)std::fputs(
        "usage: nocontract check <module.spv>\n       nocontract fix <in.spv> <out.spv>\n"
        "       nocontract capabilities <in.spv> <out.spv>\n       nocontract constants <module.spv> <source>\n"
        "       nocontract budget <module.spv>\n",
        stderr);
    return 2;
}

} // namespace

int main(int argc, char** argv) {
    const std::span<char*> args(argv, static_cast<std::size_t>(argc));
    if (args.size() < 3) return usage();
    const std::string_view mode = args[1];
    const char* input = args[2];

    const std::vector<std::uint32_t> module = atlas::spirv::words_of(read_file(input));
    const auto code = atlas::spirv::instructions(module);
    if (!code) {
        (void)std::fprintf(stderr, "nocontract: %s is not a SPIR-V module\n", input);
        return 1;
    }

    if (mode == "check" && args.size() == 3) {
        const atlas::spirv::Exactness e = atlas::spirv::check_exact(*code);
        for (const auto& f : e.findings) {
            (void)std::fprintf(stderr, "nocontract: %s: %%%u%s%s in %s: %s\n", input, f.id, f.name.empty() ? "" : " ",
                               f.name.c_str(), f.function.empty() ? "the module" : f.function.c_str(), f.what.c_str());
        }
        (void)std::printf("nocontract: %s: %zu float operations, %zu with NoContraction, %zu OpFmaKHR: %s\n", input,
                          e.float_ops, e.decorated, e.fma_khr, e.exact() ? "exact" : "NOT EXACT");
        return e.exact() ? 0 : 1;
    }
    if (mode == "fix" && args.size() == 4) {
        const auto [fixed, added] = atlas::spirv::add_no_contraction(module, *code);
        if (!write_file(args[3], fixed)) {
            (void)std::fprintf(stderr, "nocontract: cannot write %s\n", args[3]);
            return 1;
        }
        (void)std::printf("nocontract: %s: added NoContraction to %zu operations\n", input, added);
        return 0;
    }
    if (mode == "capabilities" && args.size() == 4) {
        const auto [tidied, removed] = atlas::spirv::remove_unneeded_capabilities(module, *code);
        if (!write_file(args[3], tidied)) {
            (void)std::fprintf(stderr, "nocontract: cannot write %s\n", args[3]);
            return 1;
        }
        if (removed > 0) (void)std::printf("nocontract: %s: removed %zu unneeded capabilities\n", input, removed);
        return 0;
    }
    if (mode == "budget" && args.size() == 3) {
        const atlas::spirv::Budget b = atlas::spirv::budget_of(module, *code);
        (void)std::printf("%zu %zu %zu %zu %zu\n", b.bytes, b.arrays, b.array_bytes, b.locals, b.local_bytes);
        return 0;
    }
    if (mode == "constants" && args.size() == 4) {
        const std::vector<atlas::spirv::SpecConstant> found = atlas::spirv::specialization_constants(*code);
        if (found.empty()) return 0;
        for (const atlas::spirv::SpecConstant& c : found) {
            const std::string op = atlas::spirv::spec_constant_opname(c.opcode);
            // The id where the module shows it (a typed constant's, or a decoration's target), else the instruction.
            if (c.id != 0) {
                (void)std::fprintf(stderr, "nocontract: %s: %%%u%s%s: %s", input, c.id, c.name.empty() ? "" : " ",
                                   c.name.c_str(), op.c_str());
            } else {
                (void)std::fprintf(stderr, "nocontract: %s: %s", input, op.c_str());
            }
            if (c.spec_id) (void)std::fprintf(stderr, ", SpecId %u", *c.spec_id);
            if (c.opcode == atlas::spirv::op_spec_constant_op)
                (void)std::fprintf(stderr, ", operation %u", c.operation);
            (void)std::fputc('\n', stderr);
        }
        (void)std::fprintf(
            stderr,
            "%s: the module holds %zu specialization constant instructions, and no module of the core may "
            "hold one: PowerVR's shader compiler crashed on a constant computed from one "
            "(SpvConstCalculated::~SpvConstCalculated in libufwriter.so, VK bench, 17 October 2026). A "
            "variant is a module of its own: a \"// atlas: variant <suffix> <MACRO>=<value>\" line, or a "
            "file that defines the macro and includes this one (cmake/Shaders.cmake).\n",
            args[3], found.size());
        return 1;
    }
    return usage();
}
