// The base's hot pieces: fused multiply-add lanes against the scalar loop (plan-vector T7), the emulated fused
// multiply-add the GPU noise path falls back to, varint decoding and arena allocation.
#include "base/arena.hpp"
#include "base/hlsl.hpp"
#include "base/simd.hpp"
#include "base/varint.hpp"

#include <nanobench.h>

#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace atlas::hlsl {
#include "shaders/probe/exact_probe.slang"
} // namespace atlas::hlsl

int main() {
    constexpr std::size_t n = 1U << 16U;
    std::vector<float> a(n);
    std::vector<float> b(n);
    std::vector<float> c(n);
    std::vector<float> out(n);
    for (std::size_t i = 0; i < n; ++i) {
        a[i] = 1.0F + (static_cast<float>(i % 97) * 0.01F);
        b[i] = 0.5F + (static_cast<float>(i % 89) * 0.02F);
        c[i] = -0.25F * static_cast<float>(i % 13);
    }
    ankerl::nanobench::Bench bench;
    bench.title("base").unit("value").batch(n).minEpochIterations(500).warmup(10);

    bench.run("fma, scalar std::fma", [&] {
        for (std::size_t i = 0; i < n; ++i) out[i] = std::fma(a[i], b[i], c[i]);
        ankerl::nanobench::doNotOptimizeAway(out);
    });
    bench.run("fma, simd lanes", [&] {
        for (std::size_t i = 0; i < n; i += 4) {
            using namespace atlas::simd;
            store(out.data() + i, fma(load<f32x4>(a.data() + i), load<f32x4>(b.data() + i), load<f32x4>(c.data() + i)));
        }
        ankerl::nanobench::doNotOptimizeAway(out);
    });
    bench.run("fma, emulated (exact_probe.slang as C++)", [&] {
        for (std::size_t i = 0; i < n; ++i) out[i] = atlas::hlsl::fma_emulated(a[i], b[i], c[i]);
        ankerl::nanobench::doNotOptimizeAway(out);
    });

    std::vector<std::byte> stream;
    for (std::uint64_t i = 0; i < n; ++i) {
        std::array<std::byte, atlas::varint_max_bytes> buffer{};
        const std::size_t len = atlas::write_varint(atlas::zigzag(static_cast<std::int64_t>(i * 37) - 1000), buffer);
        stream.insert(stream.end(), buffer.begin(), buffer.begin() + static_cast<std::ptrdiff_t>(len));
    }
    bench.run("varint, decode", [&] {
        atlas::VarintReader reader(stream);
        std::int64_t sum = 0;
        while (!reader.done()) sum += reader.next_signed().value_or(0);
        ankerl::nanobench::doNotOptimizeAway(sum);
    });

    atlas::Arena arena(n * 64);
    bench.run("arena, allocate 48 B", [&] {
        arena.reset();
        for (std::size_t i = 0; i < n; ++i) ankerl::nanobench::doNotOptimizeAway(arena.allocate(48, 16));
    });
    return 0;
}
