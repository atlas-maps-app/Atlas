// The user's palette (the Map look's presets and colours; mapview's palette package): the colours the core draws in
// place of today's where the user changed them, by slot (PaletteCode's numbers), and the Map look's switches.
// Kotlin's state/PaletteBlock.kt writes it; read_palette_block refuses any other.
//
//   0   u32        version (1)
//   4   u32        flags (palette_flag)
//   8   u64        reserved (0)
//   16  u32 × 256  each slot's colour by its code: 0 where it is today's, else 0x01RRGGBB
//
// Today's look is the block of no colours and no flags: every pass then draws as it did before there were palettes.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <expected>
#include <optional>
#include <span>
#include <string_view>

namespace atlas::style {

inline constexpr std::size_t palette_block_size = 1040;
inline constexpr std::uint32_t palette_block_version = 1;

/// The Map look's switches (PaletteFlags).
namespace palette_flag {
inline constexpr std::uint32_t no_guesses = 1U;    // only what OpenStreetMap maps: Atlas's guesses off
inline constexpr std::uint32_t grass = 2U;         // grass on bare ground instead of paper
inline constexpr std::uint32_t mapped_follow = 4U; // mapped colours move with the palette's nearest
inline constexpr std::uint32_t flat_greys = 8U;    // each guessed flat roof one of three mid greys, as alpha 22's
inline constexpr std::uint32_t known = 0xFU;
} // namespace palette_flag

/// The slots the passes draw by (PaletteCode's numbers: mapview's Slot).
namespace palette_slot {
inline constexpr std::size_t paper = 1;
inline constexpr std::size_t lawn = 2;
inline constexpr std::size_t woods = 3;
inline constexpr std::size_t squares = 4;
inline constexpr std::size_t parking = 5;
inline constexpr std::size_t playground = 6;
inline constexpr std::size_t memorial = 7;
inline constexpr std::size_t bridge_deck = 8;
inline constexpr std::size_t water = 16; // the theme's water (mapview's PaletteTheme), which the ground's pick names
inline constexpr std::size_t water_sky = 17;  // the theme's water_top: the water's gradient with weather, its top
inline constexpr std::size_t water_deep = 18; // the theme's water_bot: its bottom
inline constexpr std::size_t quay_walls = 19;
inline constexpr std::size_t street = 32; // the theme's road, a road's without a mapped surface (the pick names it)
inline constexpr std::size_t asphalt = 33;
inline constexpr std::size_t paving_stones = 34;
inline constexpr std::size_t setts = 35;
inline constexpr std::size_t concrete_paving = 36;
inline constexpr std::size_t brick_paving = 37;
inline constexpr std::size_t boardwalk = 38;
inline constexpr std::size_t flat_grey_1 = 84; // the first of the flat roofs' three mid greys
inline constexpr std::size_t leaves = 112;
inline constexpr std::size_t evergreen = 113;
inline constexpr std::size_t bark = 114;
inline constexpr std::size_t benches = 115;
inline constexpr std::size_t lamp_posts = 116;
inline constexpr std::size_t bollards = 117;
inline constexpr std::size_t turbines = 118;
inline constexpr std::size_t pylons = 119;
inline constexpr std::size_t night_ground = 128; // the theme's ground at night (PaletteTheme), which the pick names
inline constexpr std::size_t night_water = 129;  // the theme's water at night
} // namespace palette_slot

/// The woods' own colour (the Woods slot's today): the canopy's matrix's .16 .24 .12 in linear light as sRGB, which
/// the woods' generic trees' greens are darker tones of.
inline constexpr std::uint32_t woods_own = 0x6F8661U;

/// The bare ground's own colour (the Paper slot's today): the live day's ground, which the built land's colours are
/// lighter or darker tones of.
inline constexpr std::uint32_t paper_own = 0xD6CFC2U;

/// The quay walls' own colour (the Quay walls slot's today): Amsterdam's brick quays (the style's `quay_col` where a
/// region has none of its own), which another region's walls, the copings and the waterlines are tones of.
inline constexpr std::uint32_t quay_walls_own = 0x6E5546U;

/// A wall's or a roof's slot and its colour today (0xRRGGBB): the Colour the map board's Walls and Roofs, which stage P
/// gives each building's walls and roof (the tiles' wall_slot and roof_slot).
struct SlotColour {
    std::uint32_t code = 0;
    std::uint32_t today = 0;
};

/// The walls' slots by code: Amsterdam's old bricks, plasters and new bricks (load/Amsterdam.cpp's palette), then the
/// limestone and the concrete.
inline constexpr std::array<SlotColour, 16> wall_slots{{{.code = 48, .today = 0x6E3B2EU},
                                                        {.code = 49, .today = 0x7A4434U},
                                                        {.code = 50, .today = 0x5C3A30U},
                                                        {.code = 51, .today = 0x8A5040U},
                                                        {.code = 52, .today = 0x6A4A3CU},
                                                        {.code = 53, .today = 0x7E5242U},
                                                        {.code = 54, .today = 0xE8E2D4U},
                                                        {.code = 55, .today = 0xEFE9DCU},
                                                        {.code = 56, .today = 0xD9D2C2U},
                                                        {.code = 57, .today = 0x9C6A52U},
                                                        {.code = 58, .today = 0x8F7F74U},
                                                        {.code = 59, .today = 0xB08A6AU},
                                                        {.code = 60, .today = 0x8C8A86U},
                                                        {.code = 61, .today = 0xA9876EU},
                                                        {.code = 62, .today = 0xD8CCB2U},
                                                        {.code = 63, .today = 0xBEBAB1U}}};

/// The roofs' slots by code: the New looks' red tiles, Amsterdam's dark tiles and flat greys, then the bitumen, the
/// gravel, the slate, the zinc and the lead (load/Generic.cpp's roofs).
inline constexpr std::array<SlotColour, 12> roof_slots{{{.code = 80, .today = 0xAE573FU},
                                                        {.code = 81, .today = 0x4A3E3AU},
                                                        {.code = 82, .today = 0x56504CU},
                                                        {.code = 83, .today = 0x3E4248U},
                                                        {.code = 84, .today = 0x7E7A74U},
                                                        {.code = 85, .today = 0x8C8882U},
                                                        {.code = 86, .today = 0x6E6A66U},
                                                        {.code = 87, .today = 0x56585CU},
                                                        {.code = 88, .today = 0x9C988FU},
                                                        {.code = 89, .today = 0x535C66U},
                                                        {.code = 90, .today = 0x737E88U},
                                                        {.code = 91, .today = 0x7A8187U}}};
static_assert(roof_slots[4].code == palette_slot::flat_grey_1 && roof_slots[4].today == 0x7E7A74U);

/// A wall_slot's or roof_slot's bit for a colour OpenStreetMap maps (its code the nearest slot's): drawn as mapped
/// unless the palette's mapped colours follow.
inline constexpr std::uint32_t slot_mapped = 256U;
/// Their bit for a colour Atlas guessed that goes with no slot (a houseboat's paint, code 0): drawn as it is, and plain
/// where the palette's guesses are off.
inline constexpr std::uint32_t slot_guessed = 512U;
/// A face's slot word (geometry::Face::slot) holds its building's wall_slot or roof_slot and, for a guessed flat roof,
/// above them, which of the three mid greys from palette_slot::flat_grey_1 it takes (1 to 3) where the palette's flat
/// greys are on.
inline constexpr std::uint32_t slot_grey_shift = 10U;

/// The colour a guessed wall or roof is drawn in where the palette's guesses are off: the clay (load/Tables.hpp's
/// clay_rgb), as the cut draws a building the looks guess nothing for.
inline constexpr std::uint32_t plain_building = 0xE4E0D8U;

/// The code of the slot of `slots` nearest `rgb` (0xRRGGBB): the least sum of the channels' squared differences, the
/// first on a tie. Integers alone, so the cut's Kotlin twin (SceneToTiles's BuildingSlots) finds the same.
[[nodiscard]] std::uint32_t nearest_slot(std::span<const SlotColour> slots, std::uint32_t rgb) noexcept;

/// A wall's or a roof's slot's colour today (0xRRGGBB), 0 for a code that is neither.
[[nodiscard]] std::uint32_t building_today(std::uint32_t code) noexcept;

/// A flat roof's slot word with its mid grey (slot_grey_shift): a guessed roof colour (a roof's slot, neither mapped
/// nor without a slot) takes its own where it is one of the three, else the one its OpenStreetMap id `id` picks
/// (id % 3); any other word as it is.
[[nodiscard]] std::uint32_t flat_roof_slot(std::uint32_t slot, std::int64_t id) noexcept;

/// The slot a land kind's colour goes with (the style's `land`): the built land the paper's, the open green land the
/// lawns', the forest, the scrub, the wetland and the heath the woods', a pedestrian area the squares', a parking and
/// an apron the parkings', a playground and a track the playgrounds'; sand, rock and every other kind none.
[[nodiscard]] std::optional<std::size_t> land_slot(std::string_view kind) noexcept;
/// The slot an area kind's colour goes with (the style's `area`; another kind takes `area_other`, the squares').
[[nodiscard]] std::optional<std::size_t> area_slot(std::string_view kind) noexcept;
/// The slot a mapped surface's colour goes with (the style's `surf`), or none.
[[nodiscard]] std::optional<std::size_t> surface_slot(std::string_view surface) noexcept;

struct PaletteBlock {
    std::uint32_t version = palette_block_version;
    std::uint32_t flags = 0;
    std::uint64_t reserved = 0;
    std::array<std::uint32_t, 256> colours{};

    [[nodiscard]] bool has(std::uint32_t flag) const noexcept { return (flags & flag) != 0U; }

    /// The user's colour for the slot of `code` (0xRRGGBB), or none where it is today's.
    [[nodiscard]] std::optional<std::uint32_t> colour(std::size_t code) const noexcept {
        if (code >= colours.size() || colours.at(code) == 0U) return std::nullopt;
        return colours.at(code) & 0xFFFFFFU;
    }

    /// True for today's look: no colour changed and no switch set.
    [[nodiscard]] bool today() const noexcept;
};
static_assert(sizeof(PaletteBlock) == palette_block_size);

/// Why a palette block was refused.
enum class PaletteError : std::uint8_t {
    size,     // not 1,040 bytes
    version,  // another layout version
    flags,    // a switch version 1 does not know
    reserved, // the reserved field is not 0
    colour,   // a slot's entry is neither 0 nor 0x01RRGGBB
};

[[nodiscard]] std::string_view name_of(PaletteError error) noexcept;

/// The palette in `bytes` (little-endian, as Kotlin writes it), or why it is refused.
[[nodiscard]] std::expected<PaletteBlock, PaletteError> read_palette_block(std::span<const std::byte> bytes) noexcept;

/// `c` carried along as the colour it goes with, `from`, becomes `to` (0xRRGGBB each): the palette's rule for a
/// slot's night, its lit side, its autumn and the parts drawn with it (mapview's PaletteTheme.move). In Oklab, `c`
/// keeps its lightness against `from`'s (a darker tone the same share of the way to black, a lighter one of the way to
/// white) and its tint against `from`'s, per unit of lightness, turned as `from`'s turns into `to`'s and lessened as
/// `to`'s is greyer, never strengthened. So `from` becomes `to`, a darker tone of it the same tone of `to`, and a grey
/// `to` greys whatever goes with it.
[[nodiscard]] std::uint32_t palette_move(std::uint32_t c, std::uint32_t from, std::uint32_t to) noexcept;

/// A style's colour `c` for the slot of `code`, whose own colour in the style is `own` (0xRRGGBB each), as `palette`
/// has it (null: today's): `c` while the slot is today's, else `c` moved as `own` becomes the slot's colour.
[[nodiscard]] std::uint32_t paint(const PaletteBlock* palette, std::size_t code, std::uint32_t own,
                                  std::uint32_t c) noexcept;

} // namespace atlas::style
