#include "tiles/Heights.hpp"

#include "tiles/Bytes.hpp"
#include "tiles/Schema.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <limits>
#include <utility>

namespace atlas::tiles {
namespace {

/// The columns this reader knows, each with its codec.
enum class Slot : std::uint8_t { side, apron, step, base, max, flags, sources, shares, samples, count };

struct Known {
    std::string_view name;
    std::uint8_t codec = 0;
};

constexpr std::array<Known, static_cast<std::size_t>(Slot::count)> known = {{
    {.name = "side", .codec = static_cast<std::uint8_t>(Codec::varu)},
    {.name = "apron", .codec = static_cast<std::uint8_t>(Codec::varu)},
    {.name = "step", .codec = static_cast<std::uint8_t>(Codec::f64)},
    {.name = "base", .codec = static_cast<std::uint8_t>(Codec::varz)},
    {.name = "max", .codec = static_cast<std::uint8_t>(Codec::varu)},
    {.name = "flags", .codec = static_cast<std::uint8_t>(Codec::varu)},
    {.name = "sources", .codec = static_cast<std::uint8_t>(Codec::strs)},
    {.name = "shares", .codec = static_cast<std::uint8_t>(Codec::ids)},
    {.name = "samples", .codec = heights_codec_med},
}};

[[nodiscard]] std::unexpected<TileError> fail(Fault fault, Slot slot, std::uint64_t detail = 0) noexcept {
    return std::unexpected(column_error(fault, LayerId::heights, known[static_cast<std::size_t>(slot)].name, detail));
}

[[nodiscard]] const SkippedLayer* layer_of(const Tile& tile) noexcept {
    const auto it = std::ranges::find(tile.skipped, static_cast<std::uint8_t>(LayerId::heights), &SkippedLayer::id);
    return it == tile.skipped.end() ? nullptr : &*it;
}

/// LOCO-I's median edge detector over the samples before (r, c) of a raster `w` a row.
[[nodiscard]] std::int64_t predict(const std::int32_t* v, std::size_t w, std::size_t r, std::size_t c) noexcept {
    const std::size_t at = (r * w) + c;
    const std::int64_t a = c > 0 ? v[at - 1] : (r > 0 ? v[at - w] : 0);
    const std::int64_t b = r > 0 ? v[at - w] : a;
    const std::int64_t d = (r > 0 && c > 0) ? v[at - w - 1] : b;
    if (d >= std::max(a, b)) return std::min(a, b);
    if (d <= std::min(a, b)) return std::max(a, b);
    return a + b - d;
}

} // namespace

bool has_heights(const Tile& tile) noexcept { return layer_of(tile) != nullptr; }

Result<Heights> read_heights(const Tile& tile, Arena& arena) {
    const SkippedLayer* layer = layer_of(tile);
    if (layer == nullptr) return std::unexpected(error(Fault::missing, Site::layers, tile.header.level));
    if (layer->count != 1) return std::unexpected(column_error(Fault::bad_count, LayerId::heights, "", layer->count));

    // The column directory: the columns this reader knows, by name; the others are skipped.
    std::array<std::span<const std::byte>, known.size()> blocks{};
    std::array<bool, known.size()> present{};
    Reader lr(layer->bytes);
    for (std::size_t k = 0; k < layer->columns; ++k) {
        const std::uint32_t name_index = lr.count();
        const std::uint8_t codec = lr.u8();
        Reader block = lr.block();
        if (!lr.ok()) return std::unexpected(column_error(lr.fault(), LayerId::heights, "", k));
        if (name_index >= tile.strings.size()) {
            return std::unexpected(column_error(Fault::bad_value, LayerId::heights, "", k));
        }
        const std::string_view name = tile.strings[name_index];
        const auto* const it = std::ranges::find(known, name, &Known::name);
        if (it == known.end()) continue; // a column this reader does not know
        const auto slot = static_cast<std::size_t>(it - known.begin());
        if (codec != it->codec) return std::unexpected(column_error(Fault::bad_value, LayerId::heights, name, codec));
        if (present[slot]) return std::unexpected(column_error(Fault::duplicate, LayerId::heights, name));
        present[slot] = true;
        blocks[slot] = block.bytes(block.remaining());
    }
    if (!lr.done()) {
        return std::unexpected(column_error(Fault::left_over, LayerId::heights, "", lr.remaining()));
    }

    const auto has = [&](Slot s) { return present[static_cast<std::size_t>(s)]; };
    const auto reader = [&](Slot s) { return Reader(blocks[static_cast<std::size_t>(s)]); };
    // one number of a column that holds one: the value, or the reader's fault, or what is left over after it
    const auto one = [&](Slot s, auto take) -> Result<decltype(take(std::declval<Reader&>()))> {
        Reader r = reader(s);
        const auto v = take(r);
        if (!r.ok()) return fail(r.fault(), s);
        if (!r.done()) return fail(Fault::left_over, s, r.remaining());
        return v;
    };
    for (const Slot s : {Slot::side, Slot::step, Slot::max, Slot::samples}) {
        if (!has(s)) return fail(Fault::missing, s);
    }

    Heights h;
    h.tile = TileKey(tile.header.level, tile.header.x, tile.header.y);
    const auto side = one(Slot::side, [](Reader& r) { return r.varu(); });
    if (!side) return std::unexpected(side.error());
    if (!heights_side_allowed(*side)) return fail(Fault::bad_value, Slot::side, *side);
    h.side = static_cast<std::uint16_t>(*side);
    if (has(Slot::apron)) {
        const auto apron = one(Slot::apron, [](Reader& r) { return r.varu(); });
        if (!apron) return std::unexpected(apron.error());
        if (*apron > heights_max_apron) return fail(Fault::too_big, Slot::apron, *apron);
        h.apron = static_cast<std::uint16_t>(*apron);
    }
    const auto step = one(Slot::step, [](Reader& r) { return r.f64le(); });
    if (!step) return std::unexpected(step.error());
    if (!std::isfinite(*step) || !(*step > 0.0)) return fail(Fault::bad_value, Slot::step);
    h.step = *step;
    if (has(Slot::base)) {
        const auto base = one(Slot::base, [](Reader& r) { return r.varz(); });
        if (!base) return std::unexpected(base.error());
        h.base = *base;
    }
    const auto max = one(Slot::max, [](Reader& r) { return r.varu(); });
    if (!max) return std::unexpected(max.error());
    if (*max > heights_max_steps) return fail(Fault::too_big, Slot::max, *max);
    h.max = static_cast<std::uint32_t>(*max);
    if (has(Slot::flags)) {
        const auto flags = one(Slot::flags, [](Reader& r) { return r.varu(); });
        if (!flags) return std::unexpected(flags.error());
        h.flags = static_cast<std::uint32_t>(*flags & 0x7FFF'FFFFU);
    }
    const std::size_t w = h.width();
    const std::size_t total = w * w;

    // The sources and their shares: as many of each, the shares adding up to at most the raster's samples.
    std::span<HeightSource> sources;
    if (has(Slot::sources)) {
        Reader r = reader(Slot::sources);
        const std::uint32_t n = r.count();
        if (!r.ok()) return fail(r.fault(), Slot::sources);
        if (n > 0) {
            if (n - 1 > r.remaining()) return fail(Fault::bad_count, Slot::sources, n);
            sources = arena.allocate_array<HeightSource>(n - 1);
            for (HeightSource& s : sources) {
                const std::uint32_t i = r.count();
                if (!r.ok()) return fail(r.fault(), Slot::sources);
                if (i >= tile.strings.size()) return fail(Fault::bad_value, Slot::sources, i);
                s = HeightSource{.id = tile.strings[i], .samples = 0};
            }
        }
        if (!r.done()) return fail(Fault::left_over, Slot::sources, r.remaining());
    }
    {
        std::uint32_t n = 0;
        Reader r;
        if (has(Slot::shares)) {
            r = reader(Slot::shares);
            n = r.count();
            if (!r.ok()) return fail(r.fault(), Slot::shares);
        }
        if (n != sources.size()) return fail(Fault::bad_count, Slot::shares, n);
        std::int64_t share = 0;
        std::uint64_t sum = 0;
        for (HeightSource& s : sources) {
            share = wrap_add(share, r.varz());
            if (!r.ok()) return fail(r.fault(), Slot::shares);
            if (share < 0 || std::cmp_greater(share, total)) return fail(Fault::bad_value, Slot::shares);
            s.samples = static_cast<std::uint32_t>(share);
            sum += s.samples;
        }
        if (sum > total) return fail(Fault::bad_count, Slot::shares, sum);
        if (has(Slot::shares) && !r.done()) return fail(Fault::left_over, Slot::shares, r.remaining());
    }
    h.sources = sources;

    // The samples: the width twice, then each sample's residual from its prediction.
    Reader r = reader(Slot::samples);
    const std::uint64_t columns = r.varu();
    const std::uint64_t rows = r.varu();
    if (!r.ok()) return fail(r.fault(), Slot::samples);
    if (columns != w || rows != w) return fail(Fault::bad_count, Slot::samples, columns);
    if (r.remaining() < total) return fail(Fault::truncated, Slot::samples, r.remaining());
    const std::span<std::int32_t> samples = arena.allocate_array<std::int32_t>(total);
    std::int64_t low = std::numeric_limits<std::int64_t>::max();
    std::int64_t high = std::numeric_limits<std::int64_t>::min();
    for (std::size_t row = 0; row < w; ++row) {
        for (std::size_t c = 0; c < w; ++c) {
            // wrapping, as Kotlin's Long sum: a damaged residual lands outside 0 to max and is refused
            const std::int64_t s = wrap_add(predict(samples.data(), w, row, c), r.varz());
            if (!r.ok()) return fail(r.fault(), Slot::samples, (row * w) + c);
            if (s < 0 || s > std::int64_t{h.max}) return fail(Fault::bad_value, Slot::samples, (row * w) + c);
            samples[(row * w) + c] = static_cast<std::int32_t>(s);
            low = std::min(low, s);
            high = std::max(high, s);
        }
    }
    if (!r.done()) return fail(Fault::left_over, Slot::samples, r.remaining());
    if (low != 0) return fail(Fault::bad_value, Slot::base, static_cast<std::uint64_t>(low));
    if (high != std::int64_t{h.max}) return fail(Fault::bad_value, Slot::max, static_cast<std::uint64_t>(high));
    h.samples = samples;
    return h;
}

Sha256Digest heights_hash(const Heights& heights) noexcept {
    Sha256 md;
    const auto put = [&md](std::uint64_t v, unsigned n) {
        std::array<std::byte, 8> b{};
        for (unsigned k = 0; k < n; ++k) b.at(k) = static_cast<std::byte>((v >> (8U * k)) & 0xFFU);
        md.update(std::span(b).first(n));
    };
    put(heights.side, 2);
    put(heights.apron, 2);
    put(std::bit_cast<std::uint64_t>(heights.step), 8);
    put(static_cast<std::uint64_t>(heights.base), 8);
    for (const std::int32_t s : heights.samples) put(static_cast<std::uint32_t>(s), 4);
    return md.finish();
}

std::optional<HeightsCut> cut_heights(const Heights& raster, TileKey tile) noexcept {
    if (tile.z < raster.tile.z || tile.z > max_zoom) return std::nullopt;
    const std::uint32_t d = std::uint32_t{tile.z} - raster.tile.z;
    if (d > 8 || (tile.x >> d) != raster.tile.x || (tile.y >> d) != raster.tile.y) return std::nullopt;
    const std::uint32_t whole = std::uint32_t{raster.side} - 1U;
    const std::uint32_t part = whole >> d;
    if (part == 0 || (part << d) != whole) return std::nullopt;
    const std::uint32_t mask = (std::uint32_t{1} << d) - 1U;
    return HeightsCut{.raster = &raster,
                      .tile = tile,
                      .side = static_cast<std::uint16_t>(part + 1U),
                      .row = static_cast<std::uint16_t>((tile.y & mask) * part),
                      .column = static_cast<std::uint16_t>((tile.x & mask) * part)};
}

} // namespace atlas::tiles
