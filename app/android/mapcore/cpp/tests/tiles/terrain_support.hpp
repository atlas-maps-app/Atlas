// The terrain's test packs (tiles/Heights.hpp, ENGINE.md part 6): a raster written as the packer's writer lays a
// terrain tile out (rendertiles' HeightsCodec: the `heights` layer's columns, its samples by the median edge
// detector), and a terrain pack of such tiles, for the tests that read heights (tests/tiles/heights_test.cpp) and those
// that light the ground with them (tests/frame/relief_vk_test.cpp).
#pragma once

#include "tests/tiles/support.hpp"
#include "tiles/Heights.hpp"
#include "tiles/Payload.hpp"

#include <algorithm>
#include <cstdint>
#include <functional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::test::terrain {

using namespace atlas::tiles; // NOLINT(google-build-using-namespace): the tiles' names, as the tests write them

inline constexpr auto med = static_cast<Codec>(heights_codec_med);

/// A raster to write: its numbers and its samples in whole steps (not yet above their base), w × w.
struct Raster {
    std::uint32_t side = 17;
    std::uint32_t apron = 8;
    double step = 0.0625;
    std::vector<std::int64_t> steps;
    std::vector<std::string> sources = {"ahn4", "glo30"};
    std::vector<std::int64_t> shares = {1000, 80};
    std::uint64_t flags = heights_filled;

    [[nodiscard]] std::uint32_t width() const { return side + (2 * apron); }
    [[nodiscard]] std::int64_t base() const { return *std::ranges::min_element(steps); }
    [[nodiscard]] std::int64_t max() const { return *std::ranges::max_element(steps) - base(); }
};

inline Raster raster_of(std::uint32_t side, std::uint32_t apron,
                        const std::function<std::int64_t(std::int64_t, std::int64_t)>& f) {
    Raster out;
    out.side = side;
    out.apron = apron;
    const auto a = static_cast<std::int64_t>(apron);
    const auto n = static_cast<std::int64_t>(side);
    for (std::int64_t r = -a; r < n + a; ++r) {
        for (std::int64_t c = -a; c < n + a; ++c) out.steps.push_back(f(r, c));
    }
    const auto total = static_cast<std::int64_t>(out.steps.size());
    out.shares = {total - 9, 4};
    return out;
}

/// The `samples` column of `v` (w a row), as HeightsCodec.med writes it.
inline std::vector<std::byte> med_bytes(const std::vector<std::int64_t>& v, std::size_t w) {
    Sink s;
    s.varu(w);
    s.varu(w);
    for (std::size_t r = 0; r < w; ++r) {
        for (std::size_t c = 0; c < w; ++c) {
            const std::size_t at = (r * w) + c;
            const std::int64_t a = c > 0 ? v[at - 1] : (r > 0 ? v[at - w] : 0);
            const std::int64_t b = r > 0 ? v[at - w] : a;
            const std::int64_t d = (r > 0 && c > 0) ? v[at - w - 1] : b;
            const std::int64_t p =
                d >= std::max(a, b) ? std::min(a, b) : (d <= std::min(a, b) ? std::max(a, b) : a + b - d);
            s.varz(v[at] - p);
        }
    }
    return s.bytes;
}

/// The columns of `raster`, each by its name, for a test to change before the tile is written.
struct Columns {
    std::vector<atlas::test::ColumnBytes> list;

    atlas::test::ColumnBytes& operator[](std::string_view name) {
        return *std::ranges::find(list, name, &atlas::test::ColumnBytes::name);
    }
    void drop(std::string_view name) {
        std::erase_if(list, [&](const auto& c) { return c.name == name; });
    }
};

inline Columns columns_of(TileBytes& t, const Raster& raster) {
    namespace col = atlas::test::columns;
    std::vector<std::int64_t> above(raster.steps.size());
    const std::int64_t base = raster.base();
    std::ranges::transform(raster.steps, above.begin(), [&](std::int64_t s) { return s - base; });
    Sink step;
    step.f64(raster.step);
    Sink sources;
    sources.varu(raster.sources.size() + 1);
    for (const auto& s : raster.sources) sources.varu(t.string(s));
    Sink shares;
    shares.varu(raster.shares.size());
    std::int64_t previous = 0;
    for (const std::int64_t s : raster.shares) {
        shares.varz(s - previous);
        previous = s;
    }
    return Columns{{{"side", Codec::varu, col::varu({raster.side})},
                    {"apron", Codec::varu, col::varu({raster.apron})},
                    {"step", Codec::f64, step.bytes},
                    {"base", Codec::varz, col::varz({base})},
                    {"max", Codec::varu, col::varu({static_cast<std::uint64_t>(raster.max())})},
                    {"flags", Codec::varu, col::varu({raster.flags})},
                    {"sources", Codec::strs, sources.bytes},
                    {"shares", Codec::ids, shares.bytes},
                    {"samples", med, med_bytes(above, raster.width())}}};
}

/// A terrain tile of `columns`: one layer, one feature in its first cell.
inline TileBytes tile_of(std::uint8_t level, std::uint32_t x, std::uint32_t y) {
    TileBytes t;
    t.level = level;
    t.n = cells_per_side(level);
    t.x = x;
    t.y = y;
    return t;
}

inline void put(TileBytes& t, const Columns& columns, std::uint32_t features = 1) {
    LayerBytes layer{.id = static_cast<std::uint8_t>(LayerId::heights)};
    layer.cells.assign(features, 0);
    layer.columns = columns.list;
    t.layers = {layer};
}

inline TileBytes terrain_tile(std::uint8_t level, std::uint32_t x, std::uint32_t y, const Raster& raster) {
    TileBytes t = tile_of(level, x, y);
    put(t, columns_of(t, raster));
    return t;
}

/// A terrain pack of `tiles` for `levels` (fine to coarse), as the packer's writer lays one out: the terrain's
/// profile, a style without tables.
inline std::vector<std::byte> terrain_pack(std::vector<TileBytes> tiles, std::span<const std::uint8_t> levels,
                                           Profile profile = Profile::terrain) {
    std::ranges::stable_sort(tiles, {},
                             [&](const TileBytes& t) { return std::ranges::find(levels, t.level) - levels.begin(); });
    std::vector<ChunkBytes> chunks;
    for (TileBytes& t : tiles) {
        ChunkBytes c;
        c.tile = {t.level, t.x, t.y};
        c.raw = t.encode();
        c.count = 1;
        c.reach = t.reach();
        chunks.push_back(std::move(c));
    }
    atlas::test::PackOptions options;
    options.head = atlas::test::head_bytes(levels, profile);
    if (profile == Profile::terrain) {
        Sink style;
        style.u32(style_magic);
        style.u16(payload_version);
        style.varu(0);
        options.style = style.bytes;
    }
    return atlas::test::pack_bytes(chunks, levels, format_render, options);
}

} // namespace atlas::test::terrain
