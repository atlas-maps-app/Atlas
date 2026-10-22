// Fuzzes the JSON reader (tiles/Json.hpp), strict and with comments, and the building traditions read from it
// (load/Tradition.hpp) with arbitrary bytes: neither may read past its input or fail an invariant, a refusal must name
// a byte inside the input, what strict JSON accepts the reader with comments accepts too, and every tree the reader
// accepts must hold together: each container's children lie in the nodes before it, nested no deeper than the values'
// coding allows, each string and key in the pool and UTF-8, each map's keys different. A tradition read must hold what
// generic.py's looks rely on: options to pick from and colours to choose, a roof for every age.
// Seeds: fuzz/corpus/fuzz_json (render/traditions' five files).
#include "base/assert.hpp"
#include "load/Tradition.hpp"
#include "tiles/Bytes.hpp"
#include "tiles/Json.hpp"

#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>

namespace {

using atlas::tiles::Value;
using atlas::tiles::ValueType;

void check_text(std::string_view s) {
    ATLAS_ASSERT(atlas::tiles::valid_utf8(std::as_bytes(std::span(s.data(), s.size()))), "a string that is not UTF-8");
}

void check(const Value& v, unsigned depth) {
    ATLAS_ASSERT(depth <= atlas::tiles::max_value_depth, "a value nested too deep");
    const atlas::tiles::ValueNode& n = v.nodes()[v.node()];
    if (v.type() == ValueType::string) {
        ATLAS_ASSERT(n.first < v.strings().size(), "a string past the pool");
        check_text(v.string().value_or(""));
    }
    if (v.type() != ValueType::list && v.type() != ValueType::map) return;
    ATLAS_ASSERT(n.first + n.count <= v.node(), "children that are not before their container");
    for (std::size_t i = 0; i < v.size(); ++i) {
        if (v.type() == ValueType::map) {
            check_text(v.key(i));
            for (std::size_t j = 0; j < i; ++j) ATLAS_ASSERT(v.key(j) != v.key(i), "a key twice in one map");
        }
        check(v.child(i), depth + 1);
    }
}

} // namespace

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size) {
    const std::span<const std::byte> bytes(reinterpret_cast<const std::byte*>(data), size); // NOLINT: libFuzzer's bytes
    // with comments (the style file's reading): what strict JSON accepts reads alike, and every tree holds together
    const auto commented = atlas::tiles::read_json(bytes, {.comments = true});
    if (commented) {
        check(commented->root(), 0);
    } else {
        ATLAS_ASSERT(commented.error().at <= size, "a refusal past the input");
    }
    const auto doc = atlas::tiles::read_json(bytes);
    if (doc) ATLAS_ASSERT(commented.has_value(), "strict JSON refused where comments are taken");
    if (!doc) {
        ATLAS_ASSERT(doc.error().at <= size, "a refusal past the input");
        return 0;
    }
    check(doc->root(), 0);
    const auto t = atlas::load::read_tradition(bytes);
    if (!t) return 0;
    const atlas::load::Tradition& p = **t;
    ATLAS_ASSERT(p.floor_h > 0, "a floor height Python would divide by zero by");
    for (const auto& [key, era] : p.walls) {
        for (const auto& b : era.branches) ATLAS_ASSERT(!b.value || !b.value->empty(), "walls without an option");
        ATLAS_ASSERT(!era.fallback || !era.fallback->empty(), "walls without an option");
    }
    const auto* roof = atlas::load::Tradition::entry(p.roof, "default");
    ATLAS_ASSERT(roof != nullptr && roof->fallback.has_value(), "no roof for some age");
    for (const auto& [key, era] : p.roof) {
        for (const auto& b : era.branches)
            ATLAS_ASSERT(!b.value || !b.value->colours.empty(), "a roof without colours");
    }
    if (p.shutters) ATLAS_ASSERT(!p.shutters->colours.empty(), "shutters without colours");
    return 0;
}
