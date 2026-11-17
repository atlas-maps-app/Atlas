// The world's names' sprites (ENGINE.md step 5, "Names"; its step 9): what the core asks the app for and what it
// hands back. The frame says which names it shows and has no sprite of (their id, look and text); the app reads them
// (MapCore.namesWanted), draws each with Android's own text stack as LabelSprites draws a board's label (at its own
// size and at the band's top), and hands them back (MapCore.names); a name the device has no glyphs for comes back
// without an image and is not asked for again. The store keeps the sprites handed, the least lately shown let go
// beyond its budget (a name shown again after that is asked for again). The session's thread's alone: the façade's
// calls and the frames run on it.
#pragma once

#include "labels/Sprites.hpp"

#include <cstdint>
#include <expected>
#include <span>
#include <string>
#include <unordered_map>
#include <vector>

namespace atlas::labels {

/// A name the core asks the app for: its id, its look (scene::NameStyle) and its text, UTF-8.
struct NameWant {
    std::uint32_t id = 0;
    std::uint8_t style = 0;
    std::string text;
};

/// A name's sprite as the store keeps it: the app's (a LabelSprite without points), and its serial, another for
/// every sprite handed, by which a pass knows the texels it copied.
struct NameSprite {
    LabelSprite sprite;
    std::uint64_t serial = 0;
    /// The device has no glyphs for the name: no image, and never asked for again.
    bool skipped = false;
    double shown = 0; ///< when a frame last showed it (or asked whether it is here), seconds
};

/// The names' sprites of a map.
class NameStore {
public:
    /// The texels kept at most (16 MB), and how long a name asked for is not asked for again, seconds.
    static constexpr std::uint64_t max_texels = 4ULL << 20U;
    static constexpr double ask_again_seconds = 2.0;
    /// The names a call asks for at most.
    static constexpr std::size_t most_asked = 32;

    // ---- The frame's side.

    /// The sprite of name `id`, shown at `seconds`; null where the store has none.
    [[nodiscard]] const NameSprite* find(std::uint32_t id, double seconds) noexcept;
    /// The names the frame shows without a sprite, by rank: what the app is asked for from now on.
    void want(std::vector<NameWant> wanted) { wanted_ = std::move(wanted); }
    /// The scale the large images are drawn at, times a name's own size (0: none), and the readable floor, dp (the
    /// least text size a name is drawn at: 12 sp times Android's font scale), as the last sprites handed say.
    [[nodiscard]] float large_scale() const noexcept { return large_scale_; }
    [[nodiscard]] float floor_dp() const noexcept { return floor_dp_; }

    // ---- The app's side.

    /// The names wanted that were not asked for within ask_again_seconds of `seconds`, at most most_asked, by rank;
    /// each is taken as asked for now.
    [[nodiscard]] std::vector<NameWant> ask(double seconds);
    /// Whether any name is wanted (asked for already or not).
    [[nodiscard]] bool wanting() const noexcept { return !wanted_.empty(); }
    /// The sprites the app hands back, their large images at `large_scale` times their own size, their text drawn
    /// no smaller than `floor_dp`: each replaces its id's; one without an image is a name skipped. Or why they were
    /// refused (none is kept then). Sprites at another scale than the store's make it forget its own first (another
    /// density).
    [[nodiscard]] std::expected<void, SpriteError> give(std::vector<LabelSprite> sprites, float large_scale,
                                                        float floor_dp, double seconds);

    /// How many sprites it keeps, their texels, and how many names it knows as skipped (for tests and logs).
    [[nodiscard]] std::size_t size() const noexcept { return sprites_.size(); }
    [[nodiscard]] std::uint64_t texels() const noexcept { return texels_; }
    [[nodiscard]] std::size_t skipped() const noexcept { return skipped_; }
    /// Forgets everything (a new map).
    void reset() noexcept;

private:
    std::unordered_map<std::uint32_t, NameSprite> sprites_;
    std::unordered_map<std::uint32_t, double> asked_; // by id: when it was asked for
    std::vector<NameWant> wanted_;
    std::uint64_t texels_ = 0;
    std::size_t skipped_ = 0;
    std::uint64_t serial_ = 0;
    float large_scale_ = 0.0F;
    float floor_dp_ = 0.0F;
};

} // namespace atlas::labels
