// The sun bits a frame draws with (plan-vector D8, K8): per tile, the bits of the latest bucket its samples were tested
// for and of the bucket before, and when the swap happened. A tile keeps its old bits until its new ones are ready;
// the swap cross-fades each face whose bit changed over 300 ms (the light of a face is mixed from its lit and unlit
// colours by `lit_amount`), so a bucket's change never pops.
//
// On the GPU a tile's bits are a run of texels, R32G32_UINT: texel i holds samples 32i to 32i + 31, the new bits in R
// and the old in G (bit k of each is sample 32i + k), with the tile's fade weight beside its draw (K10). `texels`
// writes that run; the shader's read and mix are the twin's `bit` and `lit_amount` below.
//
// Thread-safe: workers publish, the render thread reads; what a reader holds stays valid after newer bits arrive.
#pragma once

#include "sun/Shadows.hpp"
#include "tiles/Chunks.hpp"

#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <span>
#include <unordered_map>
#include <utility>
#include <vector>

namespace atlas::sun {

/// How long a swap of a tile's bits cross-fades its faces, seconds.
inline constexpr double fade_seconds = 0.3;

/// A quay wall's piece as the renders draw it (water_layer's pieces: its edge outside every bridge's deck of the sun's
/// scene, each tile's at its level, as make_tile splits it), with its samples: its own (Kind::quay) and a tall wall's
/// in sun (TONE: `columns` pieces along it, three bands high, from `bands`; none where `columns` is 0).
struct QuayPiece {
    std::uint32_t record = 0; ///< its edge's place in the tile's quay layer
    double ax = 0.0;
    double az = 0.0;
    double bx = 0.0;
    double bz = 0.0;
    std::uint32_t sample = 0;
    std::uint32_t bands = 0;
    std::uint32_t columns = 0;
};

/// The bits of one tile for one bucket: sample i's bit is bit i % 64 of words[i / 64].
struct TileBits {
    std::vector<std::uint64_t> words;
    std::uint32_t count = 0;        ///< the tile's samples
    std::uint32_t windows_from = 0; ///< where its window group begins
    std::uint32_t crowns_from = 0;  ///< where its trees' crowns begin (tree i's sample is crowns_from + i)
    bool windows = false;           ///< whether the window group was tested (else its bits are 0)
    std::uint64_t bucket = 0;       ///< the bucket's key
    /// What its scene held within the march's reach of it (sun::Scene::context): bits made against other solids, a
    /// neighbouring cell's tile come into the scene or a window joined near it, are made again.
    std::uint64_t context = 0;
    Sun sun; ///< the bucket's sun
    /// Its tile's quay walls' pieces, as its samples have them (null: none).
    std::shared_ptr<const std::vector<QuayPiece>> quays;
    /// Its tile's mirrored buildings' first reflection samples, one per mirror in the tile's order (TileCasters::
    /// mirrors; null: none).
    std::shared_ptr<const std::vector<std::uint32_t>> reflections;

    [[nodiscard]] bool bit(std::uint32_t i) const noexcept { return ((words[i / 64] >> (i % 64)) & 1U) != 0; }
};

/// Whether sample `i` is lit in `words` (the shader's read of one plane of a tile's texels).
[[nodiscard]] constexpr bool bit(std::span<const std::uint64_t> words, std::uint32_t i) noexcept {
    return ((words[i / 64] >> (i % 64)) & 1U) != 0;
}

/// How lit a face draws while its tile cross-fades from `before` to `now` at fade weight `w` (0 to 1): 0 unlit, 1 lit.
[[nodiscard]] constexpr double lit_amount(bool before, bool now, double w) noexcept {
    return before == now ? (now ? 1.0 : 0.0) : (now ? w : 1.0 - w);
}

/// The bits a frame draws a tile with.
struct TileView {
    std::shared_ptr<const TileBits> now;
    std::shared_ptr<const TileBits> before; ///< the same as `now` once a fade is over, or for a tile's first bits
    double weight = 1.0;                    ///< the fade from `before` (0) to `now` (1)
};

/// Every tile's bits, as the workers publish them.
class BitTiles {
public:
    /// Publishes `bits` for tile `key` at time `t` (seconds, the frame's clock): the bits shown so far become the old
    /// ones and the fade starts. A tile's first bits show at once. A swap during a fade starts a new one from the
    /// bits that fade was heading to.
    void publish(tiles::TileKey key, TileBits bits, double t);

    /// Publishes a run's bits together at time `t`, each tile's as the one above, and tells the callback once: the
    /// frame loop at rest draws a run's tiles in one frame, however many there are and however slowly the run went.
    void publish(std::vector<std::pair<tiles::TileKey, TileBits>> run, double t);

    /// What a frame at time `t` draws tile `key` with, or nothing before its first bits.
    [[nodiscard]] std::optional<TileView> view(tiles::TileKey key, double t) const;

    /// Forgets a tile (it left the scene).
    void forget(tiles::TileKey key);

    /// Called on the publishing thread after each publication (a run's bits are one), outside the lock (null:
    /// nothing): cheap and never blocking, such as the frame loop's wake (K7's scene/SunGlobals.hpp).
    void on_publish(std::function<void()> callback);

    /// How many tiles have bits.
    [[nodiscard]] std::size_t size() const;

    /// Tile `key`'s texels at time `t`: per 32 samples the new bits, then the old (R32G32_UINT), appended to `out`;
    /// returns its fade weight, or nothing before its first bits.
    [[nodiscard]] std::optional<double> texels(tiles::TileKey key, double t, std::vector<std::uint32_t>& out) const;

private:
    struct Entry {
        std::shared_ptr<const TileBits> now;
        std::shared_ptr<const TileBits> before;
        double swapped = 0.0;
    };

    mutable std::mutex mutex_;
    std::unordered_map<std::uint64_t, Entry> tiles_;
    std::function<void()> on_publish_;
};

/// A fade's weight at time `t` for a swap at `swapped`: 0 at the swap, 1 from 300 ms on.
[[nodiscard]] double fade_weight(double swapped, double t) noexcept;

} // namespace atlas::sun
