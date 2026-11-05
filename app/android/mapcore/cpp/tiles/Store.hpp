// The tile store (plan-vector D3, "Packs on the phone"): the packs the app lists (MapCore.packs: bundled tiles, render
// packs, window tiles, the world bases), each opened once, and a tile's chunks found in them in the plan's order:
// bundled, render packs, windows, the world mid-zoom base, the world base. A bundled pack is a range of the APK mapped
// through the asset's descriptor, a downloaded pack a mapped file, a remote pack (the world mid-zoom base's release
// assets) byte ranges fetched by the app; a remote pack's head and every chunk it fetched are kept in the expanded-tile
// cache, so a place seen once works offline. Chunks come back decompressed and checked against their SHA-256.
//
// A frame asks for the chunks a view needs by their reach (every chunk of a level whose reach meets the view, with the
// margin the renders cull by; Chunks.hpp), or for a tile by its key. Which pack a frame draws from where two overlap is
// the frame's choice (K7): the store lists every holder. Thread-safe: the pack list is swapped whole, and readers keep
// the packs they found alive.
#pragma once

#include "tiles/Chunks.hpp"
#include "tiles/Container.hpp"
#include "tiles/ExpandedCache.hpp"
#include "tiles/Source.hpp"

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::tiles {

/// What a pack is to the store; its order is the order tiles are looked up in.
enum class PackKind : std::uint8_t {
    bundled = 0,    ///< the APK's tiles (the Westerkerk, the canal ring)
    render = 1,     ///< a downloaded render pack (the renders' regions, K20a; regions.json's, K20)
    window = 2,     ///< an Overpass window built on the phone (K20)
    world_mid = 3,  ///< the world's mid-zoom base L6–L11 (ENGINE.md step 5): a pack a range of its part, fetched
    world_base = 4, ///< the globe L0–L5 (ENGINE.md step 5), fetched
};

/// Where a pack's bytes are: a file; a range of an open file (an APK asset); or a remote pack read by ranges.
struct PackRef {
    std::string id;
    PackKind kind = PackKind::render;
    std::string path; ///< a file, or empty
    int fd = -1;      ///< an open file (with offset and length), or -1
    /// With fd, its range. With a path, the file's time and size as the app listed it, which the file is not opened by:
    /// a file built again at the same path (a window's) is then other bytes, which the map opens anew (MapFrame); but a
    /// world_mid pack's path is its part's, and these are its range there (the app's world.idx).
    std::uint64_t offset = 0;
    std::uint64_t length = 0;
    RangeSource::Fetch fetch; ///< a remote pack's ranges (with its size in `length`), or empty
    Sha256Digest identity{};  ///< the pack's SHA-256 as the app knows it: a remote pack's head is cached under it
};

/// An open pack of the store.
struct StorePack {
    std::string id;
    PackKind kind = PackKind::render;
    Sha256Digest identity{};
    bool remote = false;
    Pack pack;
};

/// A chunk of a tile, and the pack it is in.
struct StoreHit {
    std::shared_ptr<const StorePack> pack;
    const ChunkRef* chunk = nullptr;
};

class Store {
public:
    /// A store that keeps remote packs' heads and chunks in `cache` (null: remote packs are fetched every time).
    explicit Store(ExpandedCache* cache = nullptr) noexcept : cache_(cache) {}

    /// The style the world's packs are drawn in (Pack::open: the core's own, load::world_style), or null for each
    /// pack's own. Set before any pack is added (scene::World does as it is made).
    void set_world_style(const Style* style) noexcept { world_style_ = style; }

    /// Opens a pack and adds it (replacing one of the same id). A remote pack opens from its cached head when the cache
    /// has it, so it opens offline; else its head is fetched and cached. A window (PackKind::window: a pack of one L14
    /// tile the core built, load/Window.hpp, or of one tile of one of the cell's levels, load/Levels.hpp) joins the
    /// pack of its frame cell instead (cell_id), which holds every window of the cell and replaces the cell's last one:
    /// composed, nothing copied (compose_pack), its head the first window's under the cell's name with the levels its
    /// windows hold, its style the first window's. A window whose frame or style version is not its cell's is refused.
    /// A window without a tile (it holds nothing to draw) joins no cell, and an earlier build of it leaves its cell.
    [[nodiscard]] Result<void> add(const PackRef& ref);

    /// Removes the pack `id` (a window: from its cell's pack); returns whether there was one. Readers holding it keep
    /// it until they let go.
    bool remove(std::string_view id);

    /// Holds the frame cells' compositions while a listing's packs go and come (MapFrame::set_packs): a window joining
    /// or leaving its cell changes the cell's windows alone, and release() composes each cell they changed once, so a
    /// listing of k windows of a cell composes and opens it once, not k times (or 2k, each window built again leaving
    /// and joining). A window is refused as it joins, as before, where the cell's composition would refuse it.
    void hold();
    /// Ends the hold: each cell whose windows changed since hold() composed once, in the order they last changed (its
    /// pack replaced, as add's; or gone with its last window).
    void release();

    /// The id of the pack holding the windows of the frame cell `cell` (an L10 tile): `cells/<z>/<x>/<y>`.
    [[nodiscard]] static std::string cell_id(TileKey cell);

    /// The id of the open pack that holds pack `id`: a window's frame cell's pack, where the window joined it; else
    /// `id` itself. The app names a window by its own id (MapCore.PackRef), which the store holds in its cell's pack.
    [[nodiscard]] std::string holder_of(std::string_view id) const;

    /// Every open pack, in lookup order (by kind, then in the order added).
    [[nodiscard]] std::vector<std::shared_ptr<const StorePack>> packs() const;

    /// Every chunk of tile `tile`, in lookup order.
    [[nodiscard]] std::vector<StoreHit> find(TileKey tile) const;

    /// Every chunk of zoom `z` whose reach meets `view`, pack by pack in lookup order, each pack's in its table's
    /// order. A scan of the level's chunks (a region's level holds hundreds; the world packs may want an index, K20b).
    [[nodiscard]] std::vector<StoreHit> reaching(std::uint8_t z, const GridBox& view) const;

    /// A hit's chunk, decompressed and checked against its SHA-256, into `raw`. A remote pack's chunk comes from the
    /// cache when it holds it, else is fetched, checked and cached.
    [[nodiscard]] Result<void> load(const StoreHit& hit, Zstd& zstd, std::vector<std::byte>& raw) const;

    /// A hit's tile: loaded into `raw` (as load does), decoded into `arena` and checked against its chunk
    /// (check_against). The tile views `raw` and `arena`.
    [[nodiscard]] Result<Tile> decode(const StoreHit& hit, Zstd& zstd, TileDecoder& decoder, Arena& arena,
                                      std::vector<std::byte>& raw) const;

    /// The level of the frame cells windows are grouped by (load/Window.hpp's frame_cell_level), a window's (its
    /// window_level) and the cell's coarse and far levels' (load/Levels.hpp's coarse_level and far_level).
    /// The world's levels (ENGINE.md step 5): its mid-zoom base's, a world_mid pack's (L6 to L11); the globe's below.
    static constexpr std::uint8_t world_mid_first = 6;
    static constexpr std::uint8_t world_mid_last = 11;
    static constexpr std::uint8_t cell_level = 10;
    static constexpr std::uint8_t window_level = 14;
    static constexpr std::uint8_t coarse_level = 13;
    static constexpr std::uint8_t far_level = 12;

    /// Whether `level` is one of a cell's levels derived from its windows (drawn beyond the windows' reach).
    [[nodiscard]] static constexpr bool derived(std::uint8_t level) noexcept {
        return level == coarse_level || level == far_level;
    }

private:
    /// A window in its cell: its id, its tile and chunk, its bytes, its head's, its style read (shared by every window
    /// of the same style and the cell's compositions: tiles::PackStyle).
    struct Window {
        std::string id;
        ChunkRef chunk;
        std::shared_ptr<const ByteSource> source;
        std::vector<std::byte> head;
        std::shared_ptr<const PackStyle> style;
        Frame frame;
        std::string style_version;
    };
    /// A frame cell's windows, by id.
    struct Cell {
        TileKey key;
        std::vector<Window> windows;
    };

    [[nodiscard]] Result<Pack> open_remote(const PackRef& ref);
    [[nodiscard]] Result<void> add_window(const PackRef& ref);
    /// Puts `entry` in the lookup order (replacing a pack of its id); under mutex_.
    void insert(std::shared_ptr<const StorePack> entry);
    /// The pack of `cell`'s windows, or nothing for a cell without one; under cells_mutex_.
    [[nodiscard]] Result<std::shared_ptr<const StorePack>> compose(const Cell& cell) const;
    /// Notes that `cell`'s windows changed while held (its composition left for release), last; under cells_mutex_.
    void touch(TileKey cell);

    ExpandedCache* cache_;
    const Style* world_style_ = nullptr;
    mutable std::mutex mutex_;
    std::vector<std::shared_ptr<const StorePack>> packs_; ///< in lookup order
    mutable std::mutex cells_mutex_;                      ///< taken before mutex_, never after
    std::vector<Cell> cells_;
    bool held_ = false;            ///< hold(): compositions wait for release (under cells_mutex_)
    std::vector<TileKey> touched_; ///< the cells changed while held, the last changed last (under cells_mutex_)
    std::shared_ptr<const PackStyle> window_style_; ///< the last window's style, which the next shares where it is
                                                    ///< the same (under cells_mutex_)
};

} // namespace atlas::tiles
