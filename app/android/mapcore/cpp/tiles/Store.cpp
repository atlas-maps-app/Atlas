#include "tiles/Store.hpp"

#include "base/log.hpp"
#include "tiles/Bytes.hpp"
#include "tiles/PackWriter.hpp"
#include "tiles/SectionWriter.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <format>
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

/// A pack's bytes as a shared source: a file or a range of one, mapped.
Result<std::shared_ptr<const ByteSource>> shared_source(const PackRef& ref) {
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

} // namespace

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
    bool cached = false;
    if (cache_ != nullptr) {
        if (auto entry = cache_->get(head_key)) {
            cached = decode_ranges(entry->payload, ref.length, state->ranges);
            if (!cached) {
                state->ranges.clear();
                cache_->remove(head_key);
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
    auto pack = Pack::open(std::make_unique<RangeSource>(ref.length, std::move(fetch)));
    if (!pack && cached) {
        // a cached head that no longer opens (another pack under the same identity, or damage): fetch it afresh
        cache_->remove(head_key);
        return open_remote(ref);
    }
    if (!pack) return std::unexpected(pack.error());
    std::vector<std::byte> payload;
    {
        const std::scoped_lock lock(state->mutex);
        if (state->recording) payload = encode_ranges(state->ranges);
        state->recording = false;
    }
    if (cache_ != nullptr && !cached) {
        // a head the cache cannot keep (a full disk) only costs the next start its fetch
        if (const auto kept = cache_->put(head_key, payload); !kept)
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
    } else if (ref.fd >= 0) {
        auto source = MappedSource::open(ref.fd, ref.offset, ref.length);
        if (!source) return std::unexpected(source.error());
        pack = Pack::open(std::move(*source));
    } else if (!ref.path.empty()) {
        auto source = MappedSource::open(ref.path);
        if (!source) return std::unexpected(source.error());
        pack = Pack::open(std::move(*source));
    }
    if (!pack) return std::unexpected(pack.error());
    if (!pack->is_render()) return std::unexpected(error(Fault::unsupported, Site::header, pack->format()));
    insert(std::make_shared<StorePack>(StorePack{
        .id = ref.id, .kind = ref.kind, .identity = ref.identity, .remote = remote, .pack = std::move(*pack)}));
    return {};
}

void Store::insert(std::shared_ptr<const StorePack> entry) {
    const std::scoped_lock lock(mutex_);
    std::erase_if(packs_, [&](const auto& p) { return p->id == entry->id; });
    const auto at = std::ranges::find_if(packs_, [&](const auto& p) { return p->kind > entry->kind; });
    packs_.insert(at, std::move(entry));
}

Result<void> Store::add_window(const PackRef& ref) {
    auto source = shared_source(ref);
    if (!source) return std::unexpected(source.error());
    auto pack = Pack::open(std::make_unique<SharedSource>(*source));
    if (!pack) return std::unexpected(pack.error());
    if (!pack->is_render() || pack->head() == nullptr) {
        return std::unexpected(error(Fault::unsupported, Site::header, pack->format()));
    }
    const RenderHead& head = *pack->head();
    const std::span<const ChunkRef> chunks = pack->chunks().chunks();
    if (head.levels.size() != 1 || head.levels[0] != 14 || chunks.size() > 1 ||
        (!chunks.empty() && chunks[0].tile.z != 14)) {
        return std::unexpected(error(Fault::bad_value, Site::head, chunks.size()));
    }
    Window w{.id = ref.id, .source = *source, .frame = head.frame, .style_version = std::string(head.style_version)};
    if (!chunks.empty()) w.chunk = chunks[0];
    Zstd zstd;
    for (const Section& section : pack->sections()) {
        if (section.name != section_head && section.name != section_style) continue;
        auto bytes = read_section(**source, section, section.name == section_head ? Site::head : Site::style,
                                  max_section_raw, zstd);
        if (!bytes) return std::unexpected(bytes.error());
        (section.name == section_head ? w.head : w.style) = std::move(*bytes);
    }
    const TileKey tile = w.chunk.tile;
    const TileKey cell(cell_level, tile.x >> 4U, tile.y >> 4U);
    const std::scoped_lock lock(cells_mutex_);
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
    std::erase_if(it->windows, [&](const Window& o) { return o.id == w.id; });
    if (chunks.empty()) {
        // a window without features adds nothing to draw
        if (it->windows.empty()) cells_.erase(it);
        return {};
    }
    it->windows.push_back(std::move(w));
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
    // the cell's head: its first window's, under the cell's name
    const Window& first = cell.windows.front();
    auto head = read_head(first.head);
    if (!head) return std::unexpected(head.error());
    std::vector<HeadViewOut> views;
    for (const HeadView& v : head->views())
        views.push_back({.name = v.name, .camera = v.camera, .tolerance = v.tolerance});
    const std::string name = cell_id(cell.key);
    const auto head_bytes = encode_head({.profile = head->profile,
                                         .levels = head->levels,
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
    auto source =
        compose_pack(chunks, {.levels = head->levels, .manifest = manifest, .head = *head_bytes, .style = first.style});
    if (!source) return std::unexpected(source.error());
    auto pack = Pack::open(std::move(*source));
    if (!pack) return std::unexpected(pack.error());
    return std::make_shared<const StorePack>(
        StorePack{.id = name, .kind = PackKind::window, .identity = {}, .remote = false, .pack = std::move(*pack)});
}

bool Store::remove(std::string_view id) {
    {
        const std::scoped_lock lock(cells_mutex_);
        for (auto it = cells_.begin(); it != cells_.end(); ++it) {
            if (std::erase_if(it->windows, [&](const Window& w) { return w.id == id; }) == 0) continue;
            const std::string name = cell_id(it->key);
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
    return std::erase_if(packs_, [&](const auto& p) { return p->id == id; }) > 0;
}

std::vector<std::shared_ptr<const StorePack>> Store::packs() const {
    const std::scoped_lock lock(mutex_);
    return packs_;
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
    if (cache_ != nullptr) {
        if (auto entry = cache_->get(key)) {
            raw.resize(c.raw);
            if (unpack_chunk(zstd, c, entry->payload, p.pack.format(), raw)) return {};
            cache_->remove(key); // a stale or damaged copy: fetch it again
        }
    }
    auto stored = p.pack.stored(c, scratch);
    if (!stored) return std::unexpected(stored.error());
    raw.resize(c.raw);
    if (auto done = unpack_chunk(zstd, c, *stored, p.pack.format(), raw); !done) return done;
    if (cache_ != nullptr) {
        if (const auto kept = cache_->put(key, *stored); !kept)
            log::warn("tiles: a fetched chunk was not cached: {}", describe(kept.error()));
    }
    return {};
}

} // namespace atlas::tiles
