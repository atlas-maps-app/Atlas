#include "tiles/Chunks.hpp"

#include "base/assert.hpp"
#include "tiles/Bytes.hpp"
#include "tiles/Sections.hpp"

#include <zstd.h>

#include <algorithm>
#include <array>
#include <utility>

namespace atlas::tiles {

std::span<const ChunkRef> ChunkTable::level(std::uint8_t z) const noexcept {
    for (std::size_t i = 0; i < zooms_.size(); ++i) {
        if (zooms_[i] == z) return std::span(chunks_).subspan(level_first_[i], level_first_[i + 1] - level_first_[i]);
    }
    return {};
}

const ChunkRef* ChunkTable::find(TileKey tile) const noexcept {
    const std::uint64_t key = tile.packed();
    const auto it = std::ranges::lower_bound(keys_, key);
    if (it == keys_.end() || *it != key) return nullptr;
    return &chunks_[order_[static_cast<std::size_t>(it - keys_.begin())]];
}

Result<ChunkTable> read_chunk_table(std::span<const std::byte> bytes, const Section& chunks, std::uint8_t format,
                                    std::span<const std::uint8_t> levels) {
    auto fail = [](Fault f, std::uint64_t detail = 0) { return std::unexpected(error(f, Site::chunk_table, detail)); };
    if (format == format_render && chunks.method != Method::zstd) return fail(Fault::bad_value, 1);
    if (format == format_elements && chunks.method != Method::stored) return fail(Fault::bad_value, 2);
    Reader r(bytes);
    const std::uint32_t n = r.count();
    if (!r.ok()) return fail(r.fault());
    constexpr std::size_t smallest_entry = 1 + 5 + 32; // a zoom byte, five one-byte varints, the digest
    if (n > r.remaining() / smallest_entry) return fail(Fault::bad_count, n);

    ChunkTable t;
    t.chunks_.reserve(n);
    std::uint64_t stored_sum = 0;
    std::uint64_t raw_sum = 0;
    std::size_t level = 0;
    for (std::uint32_t i = 0; i < n; ++i) {
        ChunkRef c;
        c.tile.z = r.u8();
        c.tile.x = r.count();
        c.tile.y = r.count();
        c.stored = r.count();
        c.raw = r.count();
        c.count = r.count();
        const std::span<const std::byte> digest = r.bytes(c.sha256.size());
        if (!r.ok()) return fail(r.fault(), i);
        std::ranges::copy(digest, c.sha256.begin());
        if (c.tile.z > max_zoom) return fail(Fault::bad_value, i);
        const std::uint64_t side = std::uint64_t{1} << c.tile.z;
        if (c.tile.x >= side || c.tile.y >= side) return fail(Fault::bad_value, i);
        if (c.raw > (format == format_render ? max_tile_bytes : max_element_chunk_raw)) return fail(Fault::too_big, i);
        if (format == format_render) {
            // the chunks of each level together, the levels in rhead's order (a level may hold none)
            while (level < levels.size() && levels[level] != c.tile.z) ++level;
            if (level == levels.size()) return fail(Fault::bad_value, i);
        } else if (!t.zooms_.empty() && c.tile.z > t.zooms_.back()) {
            return fail(Fault::bad_value, i); // an element pack's levels go from fine to coarse
        }
        if (t.zooms_.empty() || t.zooms_.back() != c.tile.z) {
            if (std::ranges::contains(t.zooms_, c.tile.z)) return fail(Fault::bad_value, i); // a level split in two
            t.zooms_.push_back(c.tile.z);
            t.level_first_.push_back(i);
        }
        c.offset = chunks.offset + stored_sum;
        stored_sum += c.stored;
        raw_sum += c.raw;
        if (stored_sum > chunks.stored) return fail(Fault::bad_count, i);
        t.chunks_.push_back(c);
    }
    if (!r.done()) return fail(Fault::left_over);
    t.level_first_.push_back(n);
    if (stored_sum != chunks.stored) return fail(Fault::bad_count, stored_sum);
    if (format == format_render && raw_sum != chunks.raw) return fail(Fault::bad_count, raw_sum);

    std::vector<std::pair<std::uint64_t, std::uint32_t>> index;
    index.reserve(n);
    for (std::uint32_t i = 0; i < n; ++i) index.emplace_back(t.chunks_[i].tile.packed(), i);
    std::ranges::sort(index);
    t.keys_.reserve(n);
    t.order_.reserve(n);
    for (const auto& [key, i] : index) {
        if (!t.keys_.empty() && t.keys_.back() == key) return fail(Fault::duplicate, i);
        t.keys_.push_back(key);
        t.order_.push_back(i);
    }
    return t;
}

Result<void> read_reach(std::span<const std::byte> bytes, ChunkTable& table) {
    auto fail = [](Fault f, std::uint64_t detail = 0) { return std::unexpected(error(f, Site::reach, detail)); };
    if (bytes.size() < reach_header_bytes) return fail(Fault::truncated, bytes.size());
    Reader r(bytes);
    if (r.u32le() != reach_magic) return fail(Fault::bad_magic);
    const std::uint16_t payload = r.u16le();
    if (payload != payload_version) return fail(Fault::bad_version, payload);
    const std::uint16_t entry = r.u16le();
    const std::uint32_t n = r.u32le();
    if (entry < reach_entry_bytes) return fail(Fault::bad_value, entry);
    if (n != table.chunks_.size()) return fail(Fault::bad_count, n);
    if (r.remaining() != std::uint64_t{n} * entry) return fail(Fault::bad_count, r.remaining());
    for (ChunkRef& c : table.chunks_) {
        Reach& reach = c.reach;
        reach.box.south = static_cast<std::int32_t>(r.u32le());
        reach.box.west = static_cast<std::int32_t>(r.u32le());
        reach.box.north = static_cast<std::int32_t>(r.u32le());
        reach.box.east = static_cast<std::int32_t>(r.u32le());
        reach.top_cm = static_cast<std::int32_t>(r.u32le());
        r.skip(entry - reach_entry_bytes); // fields of a later payload
        if (reach.box.south > reach.box.north || reach.box.west > reach.box.east) {
            return fail(Fault::bad_value, static_cast<std::uint64_t>(&c - table.chunks_.data()));
        }
    }
    if (!r.ok()) return fail(r.fault());
    return {};
}

// ---- zstd

struct Zstd::Context {
    ZSTD_DCtx* dctx = ZSTD_createDCtx();

    Context() = default;
    Context(const Context&) = delete;
    Context& operator=(const Context&) = delete;
    Context(Context&&) = delete;
    Context& operator=(Context&&) = delete;
    ~Context() { ZSTD_freeDCtx(dctx); }
};

Zstd::Zstd() : context_(std::make_unique<Context>()) {
    ATLAS_ASSERT(context_->dctx != nullptr, "zstd could not make a decompression context");
}

Zstd::~Zstd() = default;
Zstd::Zstd(Zstd&&) noexcept = default;
Zstd& Zstd::operator=(Zstd&&) noexcept = default;

Result<void> Zstd::frame(std::span<const std::byte> stored, std::span<std::byte> out, Site site,
                         std::uint64_t detail) noexcept {
    const std::size_t frame = ZSTD_findFrameCompressedSize(stored.data(), stored.size());
    if (ZSTD_isError(frame) != 0U || frame != stored.size())
        return std::unexpected(error(Fault::damaged, site, detail));
    const unsigned long long declared = ZSTD_getFrameContentSize(stored.data(), stored.size());
    if (declared == ZSTD_CONTENTSIZE_ERROR) return std::unexpected(error(Fault::damaged, site, detail));
    if (declared != ZSTD_CONTENTSIZE_UNKNOWN && declared != out.size()) {
        return std::unexpected(error(Fault::bad_count, site, detail));
    }
    std::array<std::byte, 1> none{};
    void* to = out.empty() ? none.data() : out.data();
    const std::size_t n = ZSTD_decompressDCtx(context_->dctx, to, out.size(), stored.data(), stored.size());
    if (ZSTD_isError(n) != 0U) return std::unexpected(error(Fault::damaged, site, detail));
    if (n != out.size()) return std::unexpected(error(Fault::bad_count, site, detail));
    return {};
}

Result<void> unpack_chunk(Zstd& zstd, const ChunkRef& chunk, std::span<const std::byte> stored, std::uint8_t format,
                          std::span<std::byte> out, bool verify) noexcept {
    const std::uint64_t where = chunk.tile.packed();
    if (format != format_render) return std::unexpected(error(Fault::unsupported, Site::chunk, where));
    if (stored.size() != chunk.stored || out.size() != chunk.raw) {
        return std::unexpected(error(Fault::bad_count, Site::chunk, where));
    }
    if (auto done = zstd.frame(stored, out, Site::chunk, where); !done) return done;
    if (verify && Sha256::of(out) != chunk.sha256) return std::unexpected(error(Fault::damaged, Site::chunk, where));
    return {};
}

} // namespace atlas::tiles
