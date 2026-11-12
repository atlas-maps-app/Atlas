#include "tiles/Store.hpp"

#include "base/log.hpp"
#include "tiles/Bytes.hpp"
#include "tiles/PackWriter.hpp"
#include "tiles/SectionWriter.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cerrno>
#include <fcntl.h>
#include <format>
#include <functional>
#include <unistd.h>
#include <utility>

namespace atlas::tiles {
namespace {

/// A remote pack's bytes the store already has (its cached head), and while it opens, a record of what it reads.
struct RemoteState {
    struct Range {
        std::uint64_t offset = 0;
        std::vector<std::byte> bytes;
    };
    std::mutex mutex;
    std::vector<Range> ranges;
    bool recording = false;
};

/// The cache entry of a remote pack's head: a varint range count, then per range its offset, length and bytes.
[[nodiscard]] std::vector<std::byte> encode_ranges(const std::vector<RemoteState::Range>& ranges) {
    std::vector<std::byte> out;
    auto varu = [&out](std::uint64_t v) {
        while (v >= 0x80U) {
            out.push_back(static_cast<std::byte>((v & 0x7FU) | 0x80U));
            v >>= 7U;
        }
        out.push_back(static_cast<std::byte>(v));
    };
    varu(ranges.size());
    for (const auto& r : ranges) {
        varu(r.offset);
        varu(r.bytes.size());
        out.insert(out.end(), r.bytes.begin(), r.bytes.end());
    }
    return out;
}

[[nodiscard]] bool decode_ranges(std::span<const std::byte> payload, std::uint64_t pack_size,
                                 std::vector<RemoteState::Range>& out) {
    Reader r(payload);
    const std::uint32_t n = r.count();
    if (!r.ok() || n > r.remaining()) return false;
    for (std::uint32_t i = 0; i < n; ++i) {
        const std::uint64_t offset = r.varu();
        const std::uint64_t length = r.varu();
        if (!r.ok() || length > r.remaining() || offset > pack_size || length > pack_size - offset) return false;
        const std::span<const std::byte> bytes = r.bytes(static_cast<std::size_t>(length));
        out.push_back({offset, std::vector<std::byte>(bytes.begin(), bytes.end())});
    }
    return r.done();
}

/// A source shared with others (a window's bytes, which its cell's pack reads too).
class SharedSource final : public ByteSource {
public:
    explicit SharedSource(std::shared_ptr<const ByteSource> source) noexcept : source_(std::move(source)) {}
    [[nodiscard]] std::uint64_t size() const noexcept override { return source_->size(); }
    [[nodiscard]] std::span<const std::byte> view(std::uint64_t offset, std::uint64_t length) const noexcept override {
        return source_->view(offset, length);
    }
    [[nodiscard]] Result<void> read(std::uint64_t offset, std::span<std::byte> out) const override {
        return source_->read(offset, out);
    }

private:
    std::shared_ptr<const ByteSource> source_;
};

/// A world or terrain pack's range of its part named by path (the app lists the world so: MapCore.PackRef.WORLD_MID),
/// mapped and the descriptor closed.
Result<std::unique_ptr<MappedSource>> open_range(const PackRef& ref) {
    const int fd = ::open(ref.path.c_str(), O_RDONLY | O_CLOEXEC); // NOLINT(cppcoreguidelines-pro-type-vararg)
    if (fd < 0) return std::unexpected(error(Fault::io, Site::file, static_cast<std::uint64_t>(errno)));
    auto source = MappedSource::open(fd, ref.offset, ref.length);
    (void)::close(fd); // the mapping outlives the descriptor
    return source;
}

/// Whether `ref` names a range of a file by its path: a world pack's, or a terrain pack's.
[[nodiscard]] bool names_range(const PackRef& ref) noexcept {
    return ref.fd < 0 && (ref.kind == PackKind::world_mid || is_terrain(ref.kind)) && !ref.path.empty() &&
           ref.length > 0;
}

/// A pack's bytes as a shared source: a file or a range of one, mapped.
Result<std::shared_ptr<const ByteSource>> shared_source(const PackRef& ref) {
    if (names_range(ref)) {
        auto source = open_range(ref);
        if (!source) return std::unexpected(source.error());
        return std::shared_ptr<const ByteSource>(std::move(*source));
    }
    if (ref.fd >= 0) {
        auto source = MappedSource::open(ref.fd, ref.offset, ref.length);
        if (!source) return std::unexpected(source.error());
        return std::shared_ptr<const ByteSource>(std::move(*source));
    }
    if (!ref.path.empty()) {
        auto source = MappedSource::open(ref.path);
        if (!source) return std::unexpected(source.error());
        return std::shared_ptr<const ByteSource>(std::move(*source));
    }
    return std::unexpected(error(Fault::missing, Site::file));
}

/// Whether `pack`'s levels are the world's of `kind` (ENGINE.md step 5): a world pack's L6 to L11, the globe's L0 to
/// L5, so a country's tile (L12 and finer) is never found in one, nor a world tile in a country's pack. Other kinds
/// pass.
/// Likewise a terrain pack's are its kind's (a country's L12 to L14), and it is a terrain pack (`rhead`'s profile
/// terrain) exactly when its kind says so: a map pack is never listed as terrain, nor a terrain pack as the map.
[[nodiscard]] Result<void> world_levels(const Pack& pack, PackKind kind) {
    const RenderHead* head = pack.head();
    if (head != nullptr && (head->profile == Profile::terrain) != is_terrain(kind)) {
        return std::unexpected(error(Fault::bad_value, Site::head, static_cast<std::uint64_t>(head->profile)));
    }
    if (kind != PackKind::world_mid && kind != PackKind::world_base && !is_terrain(kind)) return {};
    std::uint8_t lo = 0;
    std::uint8_t hi = Store::world_mid_first - 1;
    if (kind == PackKind::world_mid) {
        lo = Store::world_mid_first;
        hi = Store::world_mid_last;
    } else if (kind == PackKind::terrain_country) {
        lo = Store::terrain_country_first;
        hi = Store::terrain_country_last;
    }
    if (head == nullptr || head->levels.empty() ||
        std::ranges::any_of(head->levels, [&](std::uint8_t z) { return z < lo || z > hi; })) {
        return std::unexpected(error(Fault::bad_value, Site::head, head == nullptr ? 0 : head->levels.size()));
    }
    return {};
}

/// Reads from `ranges` alone: a read one of them holds whole, else absent (Fault::missing).
[[nodiscard]] Result<void> read_ranges(const std::vector<RemoteState::Range>& ranges, std::uint64_t offset,
                                       std::span<std::byte> out) {
    for (const auto& r : ranges) {
        if (offset >= r.offset && out.size() <= r.bytes.size() && offset - r.offset <= r.bytes.size() - out.size()) {
            const auto from = r.bytes.begin() + static_cast<std::ptrdiff_t>(offset - r.offset);
            std::copy(from, from + static_cast<std::ptrdiff_t>(out.size()), out.begin());
            return {};
        }
    }
    return std::unexpected(error(Fault::missing, Site::file, offset));
}

} // namespace

void Store::set_cache(std::shared_ptr<ExpandedCache> cache) {
    const std::scoped_lock lock(cache_mutex_);
    cache_ = std::move(cache);
}

std::shared_ptr<ExpandedCache> Store::cache() const {
    const std::scoped_lock lock(cache_mutex_);
    return cache_;
}

RangeSource::Fetch Store::absent() {
    return [](std::uint64_t offset, std::span<std::byte> /*out*/) -> Result<void> {
        return std::unexpected(error(Fault::missing, Site::file, offset));
    };
}

Result<void> Store::keep_head(ExpandedCache& cache, const PackRef& ref, std::span<const PackBytes> ranges,
                              const Style* world_style) {
    auto state = std::make_shared<std::vector<RemoteState::Range>>();
    for (const PackBytes& r : ranges) {
        if (r.offset > ref.length || r.bytes.size() > ref.length - r.offset) {
            return std::unexpected(error(Fault::bad_value, Site::file, r.offset));
        }
        state->push_back({r.offset, std::vector<std::byte>(r.bytes.begin(), r.bytes.end())});
    }
    auto pack = Pack::open(std::make_unique<RangeSource>(ref.length,
                                                         [state](std::uint64_t offset, std::span<std::byte> out) {
                                                             return read_ranges(*state, offset, out);
                                                         }),
                           world_style);
    if (!pack) return std::unexpected(pack.error());
    if (!pack->is_render()) return std::unexpected(error(Fault::unsupported, Site::header, pack->format()));
    if (auto levels = world_levels(*pack, ref.kind); !levels) return levels;
    return cache.put({.kind = CacheKind::pack_head, .source = ref.identity}, encode_ranges(*state));
}

KeptChunks Store::keep(ExpandedCache& cache, const StorePack& pack, const PackBytes& range, Zstd& zstd) {
    KeptChunks out;
    std::vector<std::byte> raw;
    const std::uint64_t end = range.offset + range.bytes.size();
    for (const ChunkRef& c : pack.pack.chunks().chunks()) {
        if (c.offset < range.offset || c.offset > end || c.stored > end - c.offset) continue;
        const std::span<const std::byte> stored =
            range.bytes.subspan(static_cast<std::size_t>(c.offset - range.offset), c.stored);
        raw.resize(c.raw);
        if (auto done = unpack_chunk(zstd, c, stored, pack.pack.format(), raw); !done) {
            log::warn("tiles: {}'s chunk of {}/{}/{} is not the one its table names: {}", pack.id, c.tile.z, c.tile.x,
                      c.tile.y, describe(done.error()));
            out.refused.push_back(c.tile);
            continue;
        }
        if (auto kept = cache.put({.kind = CacheKind::chunk, .source = c.sha256}, stored); !kept) {
            // a chunk the cache cannot keep (a full disk) is not asked for again until the next start
            log::warn("tiles: {}'s chunk of {}/{}/{} was not kept: {}", pack.id, c.tile.z, c.tile.x, c.tile.y,
                      describe(kept.error()));
            out.refused.push_back(c.tile);
            continue;
        }
        out.kept.push_back(c.tile);
    }
    return out;
}

std::string Store::cell_id(TileKey cell) { return std::format("cells/{}/{}/{}", cell.z, cell.x, cell.y); }

std::string Store::holder_of(std::string_view id) const {
    const std::scoped_lock lock(cells_mutex_);
    for (const Cell& c : cells_) {
        if (std::ranges::any_of(c.windows, [&](const Window& w) { return w.id == id; })) return cell_id(c.key);
    }
    return std::string(id);
}

Result<Pack> Store::open_remote(const PackRef& ref) {
    auto state = std::make_shared<RemoteState>();
    const CacheKey head_key{.kind = CacheKind::pack_head, .source = ref.identity};
    const std::shared_ptr<ExpandedCache> cache = this->cache();
    bool cached = false;
    if (cache != nullptr) {
        if (auto entry = cache->get(head_key)) {
            cached = decode_ranges(entry->payload, ref.length, state->ranges);
            if (!cached) {
                state->ranges.clear();
                cache->remove(head_key);
            }
        }
    }
    // Reads come from the cached head where it covers them, else from the app's fetch (recorded while the pack opens).
    RangeSource::Fetch fetch = [state, network = ref.fetch](std::uint64_t offset,
                                                            std::span<std::byte> out) -> Result<void> {
        {
            const std::scoped_lock lock(state->mutex);
            for (const auto& r : state->ranges) {
                if (offset >= r.offset && out.size() <= r.bytes.size() &&
                    offset - r.offset <= r.bytes.size() - out.size()) {
                    const auto from = r.bytes.begin() + static_cast<std::ptrdiff_t>(offset - r.offset);
                    std::copy(from, from + static_cast<std::ptrdiff_t>(out.size()), out.begin());
                    return {};
                }
            }
        }
        if (!network) return std::unexpected(error(Fault::io, Site::file, offset));
        if (auto done = network(offset, out); !done) return done;
        const std::scoped_lock lock(state->mutex);
        if (state->recording) state->ranges.push_back({offset, std::vector<std::byte>(out.begin(), out.end())});
        return {};
    };
    {
        const std::scoped_lock lock(state->mutex);
        state->recording = !cached;
    }
    auto pack = Pack::open(std::make_unique<RangeSource>(ref.length, std::move(fetch)), world_style_);
    if (!pack && cached) {
        // a cached head that no longer opens (another pack under the same identity, or damage): fetch it afresh
        cache->remove(head_key);
        return open_remote(ref);
    }
    if (!pack) return std::unexpected(pack.error());
    std::vector<std::byte> payload;
    {
        const std::scoped_lock lock(state->mutex);
        if (state->recording) payload = encode_ranges(state->ranges);
        state->recording = false;
    }
    if (cache != nullptr && !cached) {
        // a head the cache cannot keep (a full disk) only costs the next start its fetch
        if (const auto kept = cache->put(head_key, payload); !kept)
            log::warn("tiles: a remote pack's head was not cached: {}", describe(kept.error()));
    }
    return pack;
}

Result<void> Store::add(const PackRef& ref) {
    if (ref.kind == PackKind::window) return add_window(ref);
    Result<Pack> pack = std::unexpected(error(Fault::missing, Site::file));
    bool remote = false;
    if (ref.fetch) {
        remote = true;
        pack = open_remote(ref);
    } else if (names_range(ref)) {
        auto source = open_range(ref);
        if (!source) return std::unexpected(source.error());
        pack = Pack::open(std::move(*source), world_style_);
    } else if (ref.fd >= 0) {
        auto source = MappedSource::open(ref.fd, ref.offset, ref.length);
        if (!source) return std::unexpected(source.error());
        pack = Pack::open(std::move(*source), world_style_);
    } else if (!ref.path.empty()) {
        auto source = MappedSource::open(ref.path);
        if (!source) return std::unexpected(source.error());
        pack = Pack::open(std::move(*source), world_style_);
    }
    if (!pack) return std::unexpected(pack.error());
    if (!pack->is_render()) return std::unexpected(error(Fault::unsupported, Site::header, pack->format()));
    if (auto levels = world_levels(*pack, ref.kind); !levels) return std::unexpected(levels.error());
    insert(std::make_shared<StorePack>(StorePack{
        .id = ref.id, .kind = ref.kind, .identity = ref.identity, .remote = remote, .pack = std::move(*pack)}));
    return {};
}

void Store::insert(std::shared_ptr<const StorePack> entry) {
    const std::scoped_lock lock(mutex_);
    std::erase_if(packs_, [&](const auto& p) { return p->id == entry->id; });
    std::erase_if(terrain_, [&](const auto& p) { return p->id == entry->id; });
    auto& list = is_terrain(entry->kind) ? terrain_ : packs_;
    const auto at = std::ranges::find_if(list, [&](const auto& p) { return p->kind > entry->kind; });
    list.insert(at, std::move(entry));
}

Result<void> Store::add_window(const PackRef& ref) {
    auto source = shared_source(ref);
    if (!source) return std::unexpected(source.error());
    // A window of the style the last one had shares it, read and compared but not parsed again (tiles::PackStyle).
    std::shared_ptr<const PackStyle> style;
    {
        const std::scoped_lock lock(cells_mutex_);
        style = window_style_;
    }
    auto pack = Pack::open(std::make_unique<SharedSource>(*source), nullptr, style);
    if (!pack) return std::unexpected(pack.error());
    if (!pack->is_render() || pack->head() == nullptr) {
        return std::unexpected(error(Fault::unsupported, Site::header, pack->format()));
    }
    const RenderHead& head = *pack->head();
    const std::span<const ChunkRef> chunks = pack->chunks().chunks();
    if (head.levels.size() != 1 || (head.levels[0] != window_level && !derived(head.levels[0])) || chunks.size() > 1 ||
        (!chunks.empty() && chunks[0].tile.z != head.levels[0])) {
        return std::unexpected(error(Fault::bad_value, Site::head, chunks.size()));
    }
    if (chunks.empty()) {
        // A window without features holds nothing to draw (the open sea, the middle of a field; the app does not list
        // one): it joins no cell, and an earlier build of it, which held a tile, leaves its cell.
        (void)remove(ref.id);
        return {};
    }
    // What its cell's composition would refuse it for (compose_pack: a chunk without bytes, a tile twice, below) is
    // refused as it joins, so that a held cell composes at release with no window to take back (its chunk table holds
    // its tile's size within max_tile_bytes already).
    if (chunks[0].stored == 0) return std::unexpected(error(Fault::missing, Site::chunk, chunks[0].tile.packed()));
    Window w{.id = ref.id,
             .chunk = chunks[0],
             .source = *source,
             .head = std::vector<std::byte>(head.bytes().begin(), head.bytes().end()),
             .style = pack->shared_style(),
             .frame = head.frame,
             .style_version = std::string(head.style_version)};
    const TileKey tile = w.chunk.tile;
    const auto shift = static_cast<std::uint32_t>(tile.z - cell_level);
    const TileKey cell(cell_level, tile.x >> shift, tile.y >> shift);
    const std::scoped_lock lock(cells_mutex_);
    window_style_ = w.style;
    auto it = std::ranges::find_if(cells_, [&](const Cell& c) { return c.key.packed() == cell.packed(); });
    if (it == cells_.end()) it = cells_.insert(cells_.end(), Cell{.key = cell, .windows = {}});
    // every window of a cell in one frame and of one derivation (their tiles are drawn together)
    for (const Window& o : it->windows) {
        if (o.id == w.id) continue;
        const bool same = std::bit_cast<std::array<std::uint64_t, 4>>(o.frame) ==
                          std::bit_cast<std::array<std::uint64_t, 4>>(w.frame);
        if (!same || o.style_version != w.style_version) {
            return std::unexpected(error(Fault::bad_value, Site::head, cell.packed()));
        }
        break;
    }
    // a tile once in a cell (another window's of another id: compose_pack's duplicate)
    if (std::ranges::any_of(it->windows, [&](const Window& o) { return o.id != w.id && o.chunk.tile == tile; })) {
        return std::unexpected(error(Fault::duplicate, Site::chunk_table, tile.packed()));
    }
    std::erase_if(it->windows, [&](const Window& o) { return o.id == w.id; });
    it->windows.push_back(std::move(w));
    if (held_) {
        touch(cell);
        return {};
    }
    auto composed = compose(*it);
    if (!composed) {
        it->windows.pop_back();
        return std::unexpected(composed.error());
    }
    insert(std::move(*composed));
    return {};
}

Result<std::shared_ptr<const StorePack>> Store::compose(const Cell& cell) const {
    std::vector<ComposedChunk> chunks;
    chunks.reserve(cell.windows.size());
    for (const Window& w : cell.windows) {
        chunks.push_back({.key = w.chunk.tile,
                          .raw = w.chunk.raw,
                          .count = w.chunk.count,
                          .sha256 = w.chunk.sha256,
                          .reach = w.chunk.reach,
                          .frame = {.source = w.source, .offset = w.chunk.offset, .length = w.chunk.stored}});
    }
    // the cell's head: its first window's, under the cell's name, with the levels its windows hold (finest first)
    std::vector<std::uint8_t> levels;
    for (const Window& w : cell.windows) {
        if (std::ranges::find(levels, w.chunk.tile.z) == levels.end()) levels.push_back(w.chunk.tile.z);
    }
    std::ranges::sort(levels, std::ranges::greater{});
    const Window& first = cell.windows.front();
    auto head = read_head(first.head);
    if (!head) return std::unexpected(head.error());
    std::vector<HeadViewOut> views;
    for (const HeadView& v : head->views())
        views.push_back({.name = v.name, .camera = v.camera, .tolerance = v.tolerance});
    const std::string name = cell_id(cell.key);
    const auto head_bytes = encode_head({.profile = head->profile,
                                         .levels = levels,
                                         .frame = head->frame,
                                         .region = name,
                                         .engine_version = head->engine_version,
                                         .style_version = head->style_version,
                                         .source = {},
                                         .views = views,
                                         .bands = head->bands()});
    if (!head_bytes) return std::unexpected(head_bytes.error());
    const std::string manifest =
        std::format(R"({{"layout":"render-tiles","payload":{},"builder":"core cell","cell":"{}","windows":{}}})",
                    payload_version, name, cell.windows.size());
    auto source = compose_pack(
        chunks, {.levels = levels, .manifest = manifest, .head = *head_bytes, .style = first.style->tables.bytes()});
    if (!source) return std::unexpected(source.error());
    // its style the first window's, read once (the section holds its bytes)
    auto pack = Pack::open(std::move(*source), nullptr, first.style);
    if (!pack) return std::unexpected(pack.error());
    return std::make_shared<const StorePack>(
        StorePack{.id = name, .kind = PackKind::window, .identity = {}, .remote = false, .pack = std::move(*pack)});
}

void Store::touch(TileKey cell) {
    std::erase(touched_, cell);
    touched_.push_back(cell);
}

void Store::hold() {
    const std::scoped_lock lock(cells_mutex_);
    held_ = true;
}

void Store::release() {
    const std::scoped_lock lock(cells_mutex_);
    held_ = false;
    for (const TileKey& key : touched_) {
        const std::string name = cell_id(key);
        const auto it = std::ranges::find_if(cells_, [&](const Cell& c) { return c.key.packed() == key.packed(); });
        if (it == cells_.end()) { // its last window left
            const std::scoped_lock packs(mutex_);
            std::erase_if(packs_, [&](const auto& p) { return p->id == name; });
            continue;
        }
        if (auto composed = compose(*it)) {
            insert(std::move(*composed));
        } else {
            log::warn("tiles: the windows of {} no longer compose: {}", name, describe(composed.error()));
            const std::scoped_lock packs(mutex_);
            std::erase_if(packs_, [&](const auto& p) { return p->id == name; });
        }
    }
    touched_.clear();
}

bool Store::remove(std::string_view id) {
    {
        const std::scoped_lock lock(cells_mutex_);
        for (auto it = cells_.begin(); it != cells_.end(); ++it) {
            if (std::erase_if(it->windows, [&](const Window& w) { return w.id == id; }) == 0) continue;
            const std::string name = cell_id(it->key);
            if (held_) {
                const TileKey key = it->key;
                if (it->windows.empty()) cells_.erase(it);
                touch(key);
                return true;
            }
            if (it->windows.empty()) {
                cells_.erase(it);
                const std::scoped_lock packs(mutex_);
                std::erase_if(packs_, [&](const auto& p) { return p->id == name; });
                return true;
            }
            if (auto composed = compose(*it)) {
                insert(std::move(*composed));
            } else {
                log::warn("tiles: the windows of {} no longer compose: {}", name, describe(composed.error()));
                const std::scoped_lock packs(mutex_);
                std::erase_if(packs_, [&](const auto& p) { return p->id == name; });
            }
            return true;
        }
    }
    const std::scoped_lock lock(mutex_);
    const std::size_t map = std::erase_if(packs_, [&](const auto& p) { return p->id == id; });
    return map + std::erase_if(terrain_, [&](const auto& p) { return p->id == id; }) > 0;
}

std::vector<std::shared_ptr<const StorePack>> Store::packs() const {
    const std::scoped_lock lock(mutex_);
    return packs_;
}

std::vector<std::shared_ptr<const StorePack>> Store::terrain_packs() const {
    const std::scoped_lock lock(mutex_);
    return terrain_;
}

std::optional<StoreHit> Store::heights(TileKey tile) const {
    const auto packs = terrain_packs();
    if (packs.empty()) return std::nullopt;
    // the tile's own raster, else its nearest ancestor's: a scan of the packs a level (a country's are some hundreds,
    // each a binary search; the world's terrain may want the index its packs come with)
    for (std::uint32_t d = 0; d <= tile.z; ++d) {
        const TileKey at(static_cast<std::uint8_t>(tile.z - d), tile.x >> d, tile.y >> d);
        for (const auto& p : packs) {
            if (const ChunkRef* c = p->pack.chunks().find(at)) return StoreHit{.pack = p, .chunk = c};
        }
    }
    return std::nullopt;
}

std::vector<StoreHit> Store::find(TileKey tile) const {
    std::vector<StoreHit> hits;
    for (const auto& p : packs()) {
        if (const ChunkRef* c = p->pack.chunks().find(tile)) hits.push_back({p, c});
    }
    return hits;
}

std::vector<StoreHit> Store::reaching(std::uint8_t z, const GridBox& view) const {
    std::vector<StoreHit> hits;
    for (const auto& p : packs()) {
        for (const ChunkRef& c : p->pack.chunks().level(z)) {
            if (c.reach.meets(view)) hits.push_back({p, &c});
        }
    }
    return hits;
}

Result<Tile> Store::decode(const StoreHit& hit, Zstd& zstd, TileDecoder& decoder, Arena& arena,
                           std::vector<std::byte>& raw) const {
    if (auto done = load(hit, zstd, raw); !done) return std::unexpected(done.error());
    auto tile = decoder.decode(raw, arena);
    if (!tile) return tile;
    if (auto checked = check_against(*tile, *hit.chunk); !checked) return std::unexpected(checked.error());
    return tile;
}

Result<void> Store::load(const StoreHit& hit, Zstd& zstd, std::vector<std::byte>& raw) const {
    const StorePack& p = *hit.pack;
    const ChunkRef& c = *hit.chunk;
    std::vector<std::byte> scratch;
    if (!p.remote) return p.pack.unpack(c, zstd, raw, scratch);
    const CacheKey key{.kind = CacheKind::chunk, .source = c.sha256};
    const std::shared_ptr<ExpandedCache> cache = this->cache();
    if (cache != nullptr) {
        if (auto entry = cache->get(key)) {
            raw.resize(c.raw);
            if (unpack_chunk(zstd, c, entry->payload, p.pack.format(), raw)) return {};
            cache->remove(key); // a stale or damaged copy: fetch it again
        }
    }
    auto stored = p.pack.stored(c, scratch);
    if (!stored) return std::unexpected(stored.error());
    raw.resize(c.raw);
    if (auto done = unpack_chunk(zstd, c, *stored, p.pack.format(), raw); !done) return done;
    if (cache != nullptr) {
        if (const auto kept = cache->put(key, *stored); !kept)
            log::warn("tiles: a fetched chunk was not cached: {}", describe(kept.error()));
    }
    return {};
}

} // namespace atlas::tiles
