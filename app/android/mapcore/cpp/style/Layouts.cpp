// The shared layouts' tables, from the structs and constants themselves, so a table cannot disagree with its source.
#include "style/Layouts.hpp"

#include "style/CameraState.hpp"
#include "style/Light.hpp"
#include "style/Palette.hpp"
#include "style/Theme.hpp"

#include <array>
#include <bit>
#include <cstddef>

namespace atlas::style {

namespace {

template <class T>
constexpr std::uint32_t size_of() noexcept {
    return static_cast<std::uint32_t>(sizeof(T));
}

#define ATLAS_FIELD(label, type, member)                                                                               \
    Field {                                                                                                            \
        .name = (label), .offset = static_cast<std::uint32_t>(offsetof(type, member)),                                 \
        .size = static_cast<std::uint32_t>(sizeof(type::member))                                                       \
    }

// The theme block's fields, by the names of the port's theme dict where it has one.
constexpr std::array theme_block_table = {
    ATLAS_FIELD("version", ThemeBlock, version),
    ATLAS_FIELD("flags", ThemeBlock, flags),
    ATLAS_FIELD("year", ThemeBlock, year),
    ATLAS_FIELD("month", ThemeBlock, month),
    ATLAS_FIELD("day", ThemeBlock, day),
    ATLAS_FIELD("hour", ThemeBlock, hour),
    ATLAS_FIELD("minute", ThemeBlock, minute),
    ATLAS_FIELD("name", ThemeBlock, moment),
    ATLAS_FIELD("wx.code", ThemeBlock, weather_code),
    ATLAS_FIELD("sky_top", ThemeBlock, sky_top),
    ATLAS_FIELD("sky_hor", ThemeBlock, sky_horizon),
    ATLAS_FIELD("haze", ThemeBlock, haze),
    ATLAS_FIELD("sun", ThemeBlock, sun),
    ATLAS_FIELD("amb", ThemeBlock, ambient),
    ATLAS_FIELD("ground", ThemeBlock, ground),
    ATLAS_FIELD("road", ThemeBlock, road),
    ATLAS_FIELD("water", ThemeBlock, water),
    ATLAS_FIELD("water_top", ThemeBlock, water_top),
    ATLAS_FIELD("water_bot", ThemeBlock, water_bottom),
    ATLAS_FIELD("shadow", ThemeBlock, shadow),
    ATLAS_FIELD("tone", ThemeBlock, tone),
    ATLAS_FIELD("haze_max", ThemeBlock, haze_max),
    ATLAS_FIELD("sun_k", ThemeBlock, sun_k),
    ATLAS_FIELD("amb_k", ThemeBlock, amb_k),
    ATLAS_FIELD("sky_k", ThemeBlock, sky_k),
    ATLAS_FIELD("shadow_op", ThemeBlock, shadow_opacity),
    ATLAS_FIELD("shadow_blur", ThemeBlock, shadow_blur),
    ATLAS_FIELD("cloud_shadows", ThemeBlock, cloud_shadows),
    ATLAS_FIELD("_dusk", ThemeBlock, dusk),
    ATLAS_FIELD("_L", ThemeBlock, sun_vector),
    ATLAS_FIELD("az", ThemeBlock, sun_azimuth),
    ATLAS_FIELD("el", ThemeBlock, sun_elevation),
    ATLAS_FIELD("wx.cloud", ThemeBlock, cloud),
    ATLAS_FIELD("wx.low", ThemeBlock, cloud_low),
    ATLAS_FIELD("wx.rain", ThemeBlock, rain),
    ATLAS_FIELD("wx.wind", ThemeBlock, wind),
    ATLAS_FIELD("wx.gust", ThemeBlock, gust),
    ATLAS_FIELD("wx.wind_dir", ThemeBlock, wind_direction),
    ATLAS_FIELD("wx.temp", ThemeBlock, temperature),
    ATLAS_FIELD("wx.pressure", ThemeBlock, pressure),
    ATLAS_FIELD("wx.vis", ThemeBlock, visibility),
    ATLAS_FIELD("wx.hum", ThemeBlock, humidity),
    ATLAS_FIELD("reserved1", ThemeBlock, reserved1),
};

constexpr std::uint32_t keep_out_at = static_cast<std::uint32_t>(offsetof(CameraState, keep_out));
constexpr std::uint32_t location_at = static_cast<std::uint32_t>(offsetof(CameraState, location));

constexpr Field keep_out_field(std::string_view name, std::size_t offset, std::size_t size) noexcept {
    return Field{.name = name,
                 .offset = keep_out_at + static_cast<std::uint32_t>(offset),
                 .size = static_cast<std::uint32_t>(size)};
}

constexpr Field location_field(std::string_view name, std::size_t offset, std::size_t size) noexcept {
    return Field{.name = name,
                 .offset = location_at + static_cast<std::uint32_t>(offset),
                 .size = static_cast<std::uint32_t>(size)};
}

// The camera state's fields; then the keep-out shapes as the first one's fields and the second one's start (the
// stride), and the location's.
constexpr std::array camera_state_table = {
    ATLAS_FIELD("version", CameraState, version),
    ATLAS_FIELD("flags", CameraState, flags),
    ATLAS_FIELD("sequence", CameraState, sequence),
    ATLAS_FIELD("latitude", CameraState, latitude),
    ATLAS_FIELD("longitude", CameraState, longitude),
    ATLAS_FIELD("bearing", CameraState, bearing),
    ATLAS_FIELD("pitch", CameraState, pitch),
    ATLAS_FIELD("distance", CameraState, distance),
    ATLAS_FIELD("principal_x", CameraState, principal_x),
    ATLAS_FIELD("principal_y", CameraState, principal_y),
    ATLAS_FIELD("density", CameraState, density),
    ATLAS_FIELD("width_px", CameraState, width_px),
    ATLAS_FIELD("height_px", CameraState, height_px),
    ATLAS_FIELD("width_dp", CameraState, width_dp),
    ATLAS_FIELD("height_dp", CameraState, height_dp),
    ATLAS_FIELD("pack_id", CameraState, pack_id),
    ATLAS_FIELD("profile", CameraState, profile),
    ATLAS_FIELD("theme_revision", CameraState, theme_revision),
    ATLAS_FIELD("labels_revision", CameraState, labels_revision),
    ATLAS_FIELD("overlays_revision", CameraState, overlays_revision),
    ATLAS_FIELD("keep_out_count", CameraState, keep_out_count),
    ATLAS_FIELD("reserved", CameraState, reserved),
    ATLAS_FIELD("keep_out", CameraState, keep_out),
    ATLAS_FIELD("location", CameraState, location),
    ATLAS_FIELD("parts", CameraState, parts),
    keep_out_field("keep_out.left", offsetof(KeepOut, left), sizeof(KeepOut::left)),
    keep_out_field("keep_out.top", offsetof(KeepOut, top), sizeof(KeepOut::top)),
    keep_out_field("keep_out.right", offsetof(KeepOut, right), sizeof(KeepOut::right)),
    keep_out_field("keep_out.bottom", offsetof(KeepOut, bottom), sizeof(KeepOut::bottom)),
    keep_out_field("keep_out.radius", offsetof(KeepOut, radius), sizeof(KeepOut::radius)),
    keep_out_field("keep_out.blur16", offsetof(KeepOut, blur16), sizeof(KeepOut::blur16)),
    keep_out_field("keep_out.saturation", offsetof(KeepOut, saturation), sizeof(KeepOut::saturation)),
    keep_out_field("keep_out.flags", offsetof(KeepOut, flags), sizeof(KeepOut::flags)),
    keep_out_field("keep_out[1]", sizeof(KeepOut), sizeof(KeepOut)),
    location_field("location.latitude", offsetof(Location, latitude), sizeof(Location::latitude)),
    location_field("location.longitude", offsetof(Location, longitude), sizeof(Location::longitude)),
    location_field("location.accuracy", offsetof(Location, accuracy), sizeof(Location::accuracy)),
    location_field("location.heading", offsetof(Location, heading), sizeof(Location::heading)),
    location_field("location.reserved0", offsetof(Location, reserved0), sizeof(Location::reserved0)),
    location_field("location.reserved1", offsetof(Location, reserved1), sizeof(Location::reserved1)),
};

// theme.slang's Theme as the twin lays it out.
constexpr std::array shader_theme_table = {
    ATLAS_FIELD("ambient", shaders::Theme, ambient),
    ATLAS_FIELD("sun", shaders::Theme, sun),
    ATLAS_FIELD("light", shaders::Theme, light),
    ATLAS_FIELD("haze", shaders::Theme, haze),
    ATLAS_FIELD("sky_top", shaders::Theme, sky_top),
    ATLAS_FIELD("sky_horizon", shaders::Theme, sky_horizon),
    ATLAS_FIELD("ground", shaders::Theme, ground),
    ATLAS_FIELD("road", shaders::Theme, road),
    ATLAS_FIELD("water", shaders::Theme, water),
    ATLAS_FIELD("water_top", shaders::Theme, water_top),
    ATLAS_FIELD("water_bottom", shaders::Theme, water_bottom),
    ATLAS_FIELD("shadow", shaders::Theme, shadow),
    ATLAS_FIELD("weather", shaders::Theme, weather),
    ATLAS_FIELD("wind", shaders::Theme, wind),
    ATLAS_FIELD("flags", shaders::Theme, flags),
    ATLAS_FIELD("bits_amb_sun", shaders::Theme, bits_amb_sun),
    ATLAS_FIELD("bits_sky_lx", shaders::Theme, bits_sky_lx),
    ATLAS_FIELD("bits_lyz", shaders::Theme, bits_lyz),
};

#undef ATLAS_FIELD

constexpr std::uint32_t moment(MomentName m) noexcept { return static_cast<std::uint32_t>(m); }
constexpr std::uint32_t profile(Profile p) noexcept { return static_cast<std::uint32_t>(p); }
/// A part's bit number (CameraWriter.PART_*), of a camera_part bit.
constexpr std::uint32_t part(std::uint64_t bit) noexcept { return static_cast<std::uint32_t>(std::countr_zero(bit)); }

// Every number of the layouts that Kotlin writes, by Kotlin's name for it.
constexpr std::array constants_table = {
    Constant{.name = "ThemeBlock.VERSION", .value = theme_block_version},
    Constant{.name = "ThemeBlock.SIZE", .value = static_cast<std::uint32_t>(theme_block_size)},
    Constant{.name = "ThemeBlock.NIGHT", .value = theme_flag::night},
    Constant{.name = "ThemeBlock.WET", .value = theme_flag::wet},
    Constant{.name = "ThemeBlock.WEATHER", .value = theme_flag::weather},
    Constant{.name = "ThemeBlock.OVERCAST", .value = theme_flag::overcast},
    Constant{.name = "ThemeBlock.SHADOW_BLUR", .value = theme_flag::shadow_blur},
    Constant{.name = "ThemeBlock.CLOUD_SHADOWS", .value = theme_flag::cloud_shadows},
    Constant{.name = "ThemeBlock.WATER_GRADIENT", .value = theme_flag::water_gradient},
    Constant{.name = "ThemeBlock.DUSK", .value = theme_flag::dusk},
    Constant{.name = "ThemeBlock.WET_SET", .value = theme_flag::wet_set},
    Constant{.name = "ThemeBlock.GOLDEN", .value = theme_flag::golden},
    Constant{.name = "ThemeBlock.TONE", .value = theme_flag::tone},
    Constant{.name = "ThemeBlock.TONE_STRENGTH", .value = theme_flag::tone_strength},
    Constant{.name = "ThemeBlock.MOMENTS[]", .value = moment(MomentName::other)},
    Constant{.name = "ThemeBlock.MOMENTS[sunny]", .value = moment(MomentName::sunny)},
    Constant{.name = "ThemeBlock.MOMENTS[grey]", .value = moment(MomentName::grey)},
    Constant{.name = "ThemeBlock.MOMENTS[wet]", .value = moment(MomentName::wet)},
    Constant{.name = "ThemeBlock.MOMENTS[night]", .value = moment(MomentName::night)},
    Constant{.name = "ThemeBlock.MOMENTS[golden]", .value = moment(MomentName::golden)},
    Constant{.name = "ThemeBlock.MOMENTS[noon]", .value = moment(MomentName::noon)},
    Constant{.name = "ThemeBlock.MOMENTS[truth]", .value = moment(MomentName::truth)},
    Constant{.name = "CameraWriter.VERSION", .value = camera_state_version},
    Constant{.name = "CameraWriter.SIZE", .value = static_cast<std::uint32_t>(camera_state_size)},
    Constant{.name = "CameraWriter.MAX_KEEP_OUTS", .value = static_cast<std::uint32_t>(max_keep_outs)},
    Constant{.name = "CameraWriter.KEEP_OUT_SIZE", .value = size_of<KeepOut>()},
    Constant{.name = "CameraWriter.REST", .value = camera_flag::rest},
    Constant{.name = "CameraWriter.ENGINE_MODE", .value = camera_flag::engine_mode},
    Constant{.name = "CameraWriter.RADIUS_CUT", .value = camera_flag::radius_cut},
    Constant{.name = "CameraWriter.FOLLOW", .value = camera_flag::follow},
    Constant{.name = "CameraWriter.LOCATION", .value = camera_flag::location},
    Constant{.name = "CameraWriter.HEADING", .value = camera_flag::heading},
    Constant{.name = "CameraWriter.PART_GROUND", .value = part(camera_part::ground)},
    Constant{.name = "CameraWriter.PART_WATER", .value = part(camera_part::water)},
    Constant{.name = "CameraWriter.PART_CANOPY", .value = part(camera_part::canopy)},
    Constant{.name = "CameraWriter.PART_GROUND_LINES", .value = part(camera_part::ground_lines)},
    Constant{.name = "CameraWriter.PART_SETTS", .value = part(camera_part::setts)},
    Constant{.name = "CameraWriter.PART_GROUND_TEXT", .value = part(camera_part::ground_text)},
    Constant{.name = "CameraWriter.PART_QUAYS", .value = part(camera_part::quays)},
    Constant{.name = "CameraWriter.PART_SKY", .value = part(camera_part::sky)},
    Constant{.name = "CameraWriter.PART_BUILDING_SHADOWS", .value = part(camera_part::building_shadows)},
    Constant{.name = "CameraWriter.PART_PARK_SHADOWS", .value = part(camera_part::park_shadows)},
    Constant{.name = "CameraWriter.PART_CLOUD_SHADOWS", .value = part(camera_part::cloud_shadows)},
    Constant{.name = "CameraWriter.PART_REFLECTIONS", .value = part(camera_part::reflections)},
    Constant{.name = "CameraWriter.PART_RIPPLE", .value = part(camera_part::ripple)},
    Constant{.name = "CameraWriter.PART_GLITTER", .value = part(camera_part::glitter)},
    Constant{.name = "CameraWriter.PART_RINGS", .value = part(camera_part::rings)},
    Constant{.name = "CameraWriter.PART_GLINTS", .value = part(camera_part::glints)},
    Constant{.name = "CameraWriter.PART_WATER_STREAKS", .value = part(camera_part::water_streaks)},
    Constant{.name = "CameraWriter.PART_LIT_STREETS", .value = part(camera_part::lit_streets)},
    Constant{.name = "CameraWriter.PART_WET_CORES", .value = part(camera_part::wet_cores)},
    Constant{.name = "CameraWriter.PART_LAMP_POOLS", .value = part(camera_part::lamp_pools)},
    Constant{.name = "CameraWriter.PART_LAMP_HALOS", .value = part(camera_part::lamp_halos)},
    Constant{.name = "CameraWriter.PART_LAMP_STREAKS", .value = part(camera_part::lamp_streaks)},
    Constant{.name = "CameraWriter.PART_RAIN", .value = part(camera_part::rain)},
    Constant{.name = "CameraWriter.PART_GOLDEN_WASH", .value = part(camera_part::golden_wash)},
    Constant{.name = "CameraWriter.PART_WALLS", .value = part(camera_part::walls)},
    Constant{.name = "CameraWriter.PART_ROOFS", .value = part(camera_part::roofs)},
    Constant{.name = "CameraWriter.PART_ROOF_EDGES", .value = part(camera_part::roof_edges)},
    Constant{.name = "CameraWriter.PART_WINDOWS", .value = part(camera_part::windows)},
    Constant{.name = "CameraWriter.PART_WINDOW_DEPTH", .value = part(camera_part::window_depth)},
    Constant{.name = "CameraWriter.PART_SHOP_GLAZING", .value = part(camera_part::shop_glazing)},
    Constant{.name = "CameraWriter.PART_CHURCH_WINDOWS", .value = part(camera_part::church_windows)},
    Constant{.name = "CameraWriter.PART_MATERIAL_FACES", .value = part(camera_part::material_faces)},
    Constant{.name = "CameraWriter.PART_HOIST_BEAMS", .value = part(camera_part::hoist_beams)},
    Constant{.name = "CameraWriter.PART_KIT_DETAILS", .value = part(camera_part::kit_details)},
    Constant{.name = "CameraWriter.PART_DOORS", .value = part(camera_part::doors)},
    Constant{.name = "CameraWriter.PART_HOUSE_NUMBERS", .value = part(camera_part::house_numbers)},
    Constant{.name = "CameraWriter.PART_TREES", .value = part(camera_part::trees)},
    Constant{.name = "CameraWriter.PART_LEAF_FILTERS", .value = part(camera_part::leaf_filters)},
    Constant{.name = "CameraWriter.PART_FURNITURE", .value = part(camera_part::furniture)},
    Constant{.name = "CameraWriter.PART_REGION_ITEMS", .value = part(camera_part::region_items)},
    Constant{.name = "CameraWriter.PART_LABELS", .value = part(camera_part::labels)},
    Constant{.name = "CameraWriter.PART_ROUTES", .value = part(camera_part::routes)},
    Constant{.name = "CameraWriter.PART_MARKERS", .value = part(camera_part::markers)},
    Constant{.name = "CameraWriter.PART_LOCATION", .value = part(camera_part::location)},
    Constant{.name = "CameraWriter.PART_FROSTED", .value = part(camera_part::frosted)},
    Constant{.name = "CameraWriter.PART_SHADOW_BLUR", .value = part(camera_part::shadow_blur)},
    Constant{.name = "CameraWriter.PART_NEAR_CELLS", .value = part(camera_part::near_cells)},
    Constant{.name = "CameraWriter.PART_KEPT_CULL", .value = part(camera_part::kept_cull)},
    Constant{.name = "CameraWriter.PART_PLINTHS", .value = part(camera_part::plinths)},
    Constant{.name = "CameraWriter.PART_CORNICES", .value = part(camera_part::cornices)},
    Constant{.name = "CameraWriter.PART_QUAY_WATERLINES", .value = part(camera_part::quay_waterlines)},
    Constant{.name = "CameraWriter.PART_QUAY_TOPS", .value = part(camera_part::quay_tops)},
    Constant{.name = "CameraWriter.PART_SHOPFRONTS", .value = part(camera_part::shopfronts)},
    Constant{.name = "CameraWriter.PART_TERRACES", .value = part(camera_part::terraces)},
    Constant{.name = "CameraWriter.PART_OPEN_GLOWS", .value = part(camera_part::open_glows)},
    Constant{.name = "CameraWriter.PARTS", .value = part(camera_part::known + 1U)},
    Constant{.name = "CameraWriter.PROFILE_NONE", .value = profile(Profile::none)},
    Constant{.name = "CameraWriter.PROFILE_LIVING", .value = profile(Profile::living)},
    Constant{.name = "CameraWriter.PROFILE_REGION", .value = profile(Profile::region)},
    Constant{.name = "CameraWriter.PROFILE_WILLIAMSBURG", .value = profile(Profile::williamsburg)},
    Constant{.name = "CameraWriter.PROFILE_WORLD_BASE", .value = profile(Profile::world_base)},
    Constant{.name = "PaletteBlock.VERSION", .value = palette_block_version},
    Constant{.name = "PaletteBlock.SIZE", .value = static_cast<std::uint32_t>(palette_block_size)},
    Constant{.name = "PaletteFlags.NO_GUESSES", .value = palette_flag::no_guesses},
    Constant{.name = "PaletteFlags.GRASS", .value = palette_flag::grass},
    Constant{.name = "PaletteFlags.MAPPED_FOLLOW", .value = palette_flag::mapped_follow},
    Constant{.name = "PaletteFlags.FLAT_GREYS", .value = palette_flag::flat_greys},
    Constant{.name = "Slot.LEAVES", .value = static_cast<std::uint32_t>(palette_slot::leaves)},
    Constant{.name = "Slot.EVERGREEN", .value = static_cast<std::uint32_t>(palette_slot::evergreen)},
    Constant{.name = "Slot.BARK", .value = static_cast<std::uint32_t>(palette_slot::bark)},
    Constant{.name = "Slot.WOODS", .value = static_cast<std::uint32_t>(palette_slot::woods)},
    Constant{.name = "Slot.BENCHES", .value = static_cast<std::uint32_t>(palette_slot::benches)},
    Constant{.name = "Slot.LAMP_POSTS", .value = static_cast<std::uint32_t>(palette_slot::lamp_posts)},
    Constant{.name = "Slot.BOLLARDS", .value = static_cast<std::uint32_t>(palette_slot::bollards)},
    Constant{.name = "Slot.TURBINES", .value = static_cast<std::uint32_t>(palette_slot::turbines)},
    Constant{.name = "Slot.PYLONS", .value = static_cast<std::uint32_t>(palette_slot::pylons)},
    Constant{.name = "Slot.WOODS.today", .value = woods_own},
    Constant{.name = "Slot.PAPER.today", .value = paper_own},
    Constant{.name = "Slot.PAPER", .value = static_cast<std::uint32_t>(palette_slot::paper)},
    Constant{.name = "Slot.LAWN", .value = static_cast<std::uint32_t>(palette_slot::lawn)},
    Constant{.name = "Slot.SQUARES", .value = static_cast<std::uint32_t>(palette_slot::squares)},
    Constant{.name = "Slot.PARKING", .value = static_cast<std::uint32_t>(palette_slot::parking)},
    Constant{.name = "Slot.PLAYGROUND", .value = static_cast<std::uint32_t>(palette_slot::playground)},
    Constant{.name = "Slot.MEMORIAL", .value = static_cast<std::uint32_t>(palette_slot::memorial)},
    Constant{.name = "Slot.BRIDGE_DECK", .value = static_cast<std::uint32_t>(palette_slot::bridge_deck)},
    Constant{.name = "Slot.QUAY_WALLS", .value = static_cast<std::uint32_t>(palette_slot::quay_walls)},
    Constant{.name = "Slot.QUAY_WALLS.today", .value = quay_walls_own},
    Constant{.name = "Slot.WATER", .value = static_cast<std::uint32_t>(palette_slot::water)},
    Constant{.name = "Slot.WATER_SKY", .value = static_cast<std::uint32_t>(palette_slot::water_sky)},
    Constant{.name = "Slot.WATER_DEEP", .value = static_cast<std::uint32_t>(palette_slot::water_deep)},
    Constant{.name = "Slot.STREET", .value = static_cast<std::uint32_t>(palette_slot::street)},
    Constant{.name = "Slot.NIGHT_GROUND", .value = static_cast<std::uint32_t>(palette_slot::night_ground)},
    Constant{.name = "Slot.NIGHT_WATER", .value = static_cast<std::uint32_t>(palette_slot::night_water)},
    Constant{.name = "BuildingSlots.MAPPED", .value = slot_mapped},
    Constant{.name = "BuildingSlots.GUESSED", .value = slot_guessed},
    Constant{.name = "Slot.OLD_BRICK_1", .value = wall_slots[0].code},
    Constant{.name = "Slot.OLD_BRICK_1.today", .value = wall_slots[0].today},
    Constant{.name = "Slot.OLD_BRICK_2", .value = wall_slots[1].code},
    Constant{.name = "Slot.OLD_BRICK_2.today", .value = wall_slots[1].today},
    Constant{.name = "Slot.OLD_BRICK_3", .value = wall_slots[2].code},
    Constant{.name = "Slot.OLD_BRICK_3.today", .value = wall_slots[2].today},
    Constant{.name = "Slot.OLD_BRICK_4", .value = wall_slots[3].code},
    Constant{.name = "Slot.OLD_BRICK_4.today", .value = wall_slots[3].today},
    Constant{.name = "Slot.OLD_BRICK_5", .value = wall_slots[4].code},
    Constant{.name = "Slot.OLD_BRICK_5.today", .value = wall_slots[4].today},
    Constant{.name = "Slot.OLD_BRICK_6", .value = wall_slots[5].code},
    Constant{.name = "Slot.OLD_BRICK_6.today", .value = wall_slots[5].today},
    Constant{.name = "Slot.PLASTER_1", .value = wall_slots[6].code},
    Constant{.name = "Slot.PLASTER_1.today", .value = wall_slots[6].today},
    Constant{.name = "Slot.PLASTER_2", .value = wall_slots[7].code},
    Constant{.name = "Slot.PLASTER_2.today", .value = wall_slots[7].today},
    Constant{.name = "Slot.PLASTER_3", .value = wall_slots[8].code},
    Constant{.name = "Slot.PLASTER_3.today", .value = wall_slots[8].today},
    Constant{.name = "Slot.NEW_BRICK_1", .value = wall_slots[9].code},
    Constant{.name = "Slot.NEW_BRICK_1.today", .value = wall_slots[9].today},
    Constant{.name = "Slot.NEW_BRICK_2", .value = wall_slots[10].code},
    Constant{.name = "Slot.NEW_BRICK_2.today", .value = wall_slots[10].today},
    Constant{.name = "Slot.NEW_BRICK_3", .value = wall_slots[11].code},
    Constant{.name = "Slot.NEW_BRICK_3.today", .value = wall_slots[11].today},
    Constant{.name = "Slot.NEW_BRICK_4", .value = wall_slots[12].code},
    Constant{.name = "Slot.NEW_BRICK_4.today", .value = wall_slots[12].today},
    Constant{.name = "Slot.NEW_BRICK_5", .value = wall_slots[13].code},
    Constant{.name = "Slot.NEW_BRICK_5.today", .value = wall_slots[13].today},
    Constant{.name = "Slot.LIMESTONE", .value = wall_slots[14].code},
    Constant{.name = "Slot.LIMESTONE.today", .value = wall_slots[14].today},
    Constant{.name = "Slot.CONCRETE", .value = wall_slots[15].code},
    Constant{.name = "Slot.CONCRETE.today", .value = wall_slots[15].today},
    Constant{.name = "Slot.RED_TILES", .value = roof_slots[0].code},
    Constant{.name = "Slot.RED_TILES.today", .value = roof_slots[0].today},
    Constant{.name = "Slot.DARK_TILES_1", .value = roof_slots[1].code},
    Constant{.name = "Slot.DARK_TILES_1.today", .value = roof_slots[1].today},
    Constant{.name = "Slot.DARK_TILES_2", .value = roof_slots[2].code},
    Constant{.name = "Slot.DARK_TILES_2.today", .value = roof_slots[2].today},
    Constant{.name = "Slot.DARK_TILES_3", .value = roof_slots[3].code},
    Constant{.name = "Slot.DARK_TILES_3.today", .value = roof_slots[3].today},
    Constant{.name = "Slot.FLAT_GREY_1", .value = roof_slots[4].code},
    Constant{.name = "Slot.FLAT_GREY_1.today", .value = roof_slots[4].today},
    Constant{.name = "Slot.FLAT_GREY_2", .value = roof_slots[5].code},
    Constant{.name = "Slot.FLAT_GREY_2.today", .value = roof_slots[5].today},
    Constant{.name = "Slot.FLAT_GREY_3", .value = roof_slots[6].code},
    Constant{.name = "Slot.FLAT_GREY_3.today", .value = roof_slots[6].today},
    Constant{.name = "Slot.BITUMEN", .value = roof_slots[7].code},
    Constant{.name = "Slot.BITUMEN.today", .value = roof_slots[7].today},
    Constant{.name = "Slot.GRAVEL", .value = roof_slots[8].code},
    Constant{.name = "Slot.GRAVEL.today", .value = roof_slots[8].today},
    Constant{.name = "Slot.SLATE", .value = roof_slots[9].code},
    Constant{.name = "Slot.SLATE.today", .value = roof_slots[9].today},
    Constant{.name = "Slot.ZINC", .value = roof_slots[10].code},
    Constant{.name = "Slot.ZINC.today", .value = roof_slots[10].today},
    Constant{.name = "Slot.LEAD", .value = roof_slots[11].code},
    Constant{.name = "Slot.LEAD.today", .value = roof_slots[11].today},
    Constant{.name = "Slot.ASPHALT", .value = static_cast<std::uint32_t>(palette_slot::asphalt)},
    Constant{.name = "Slot.PAVING_STONES", .value = static_cast<std::uint32_t>(palette_slot::paving_stones)},
    Constant{.name = "Slot.SETTS", .value = static_cast<std::uint32_t>(palette_slot::setts)},
    Constant{.name = "Slot.CONCRETE_PAVING", .value = static_cast<std::uint32_t>(palette_slot::concrete_paving)},
    Constant{.name = "Slot.BRICK_PAVING", .value = static_cast<std::uint32_t>(palette_slot::brick_paving)},
    Constant{.name = "Slot.BOARDWALK", .value = static_cast<std::uint32_t>(palette_slot::boardwalk)},
};

// Each table spans its struct without gaps or overlaps (fields in order, each starting where the last ended).
template <std::size_t N>
constexpr bool tiles(const std::array<Field, N>& fields, std::uint32_t size, std::size_t count) noexcept {
    std::uint32_t at = 0;
    for (std::size_t i = 0; i < count; ++i) {
        if (fields[i].offset != at) {
            return false;
        }
        at += fields[i].size;
    }
    return at == size;
}

static_assert(tiles(theme_block_table, size_of<ThemeBlock>(), theme_block_table.size()));
static_assert(tiles(camera_state_table, size_of<CameraState>(), 25)); // up to the parts, after the sixteen shapes
static_assert(tiles(shader_theme_table, size_of<shaders::Theme>(), shader_theme_table.size()));
// The flag bits are the known ones, each once.
static_assert((theme_flag::night | theme_flag::wet | theme_flag::weather | theme_flag::overcast |
               theme_flag::shadow_blur | theme_flag::cloud_shadows | theme_flag::water_gradient | theme_flag::dusk |
               theme_flag::wet_set | theme_flag::golden | theme_flag::tone | theme_flag::tone_strength) ==
              theme_flag::known);
static_assert((camera_flag::rest | camera_flag::engine_mode | camera_flag::radius_cut | camera_flag::follow |
               camera_flag::location | camera_flag::heading) == camera_flag::known);
// The parts are the known ones, each once, in the order of their bits.
constexpr std::array part_bits{camera_part::ground,
                               camera_part::water,
                               camera_part::canopy,
                               camera_part::ground_lines,
                               camera_part::setts,
                               camera_part::ground_text,
                               camera_part::quays,
                               camera_part::sky,
                               camera_part::building_shadows,
                               camera_part::park_shadows,
                               camera_part::cloud_shadows,
                               camera_part::reflections,
                               camera_part::ripple,
                               camera_part::glitter,
                               camera_part::rings,
                               camera_part::glints,
                               camera_part::water_streaks,
                               camera_part::lit_streets,
                               camera_part::wet_cores,
                               camera_part::lamp_pools,
                               camera_part::lamp_halos,
                               camera_part::lamp_streaks,
                               camera_part::rain,
                               camera_part::golden_wash,
                               camera_part::walls,
                               camera_part::roofs,
                               camera_part::roof_edges,
                               camera_part::windows,
                               camera_part::window_depth,
                               camera_part::shop_glazing,
                               camera_part::church_windows,
                               camera_part::material_faces,
                               camera_part::hoist_beams,
                               camera_part::kit_details,
                               camera_part::doors,
                               camera_part::house_numbers,
                               camera_part::trees,
                               camera_part::leaf_filters,
                               camera_part::furniture,
                               camera_part::region_items,
                               camera_part::labels,
                               camera_part::routes,
                               camera_part::markers,
                               camera_part::location,
                               camera_part::frosted,
                               camera_part::shadow_blur,
                               camera_part::near_cells,
                               camera_part::kept_cull,
                               camera_part::plinths,
                               camera_part::cornices,
                               camera_part::quay_waterlines,
                               camera_part::quay_tops,
                               camera_part::shopfronts,
                               camera_part::terraces,
                               camera_part::open_glows};
constexpr bool parts_in_order() noexcept {
    std::uint64_t all = 0;
    for (std::size_t i = 0; i < part_bits.size(); ++i) {
        if (part_bits[i] != (std::uint64_t{1} << i)) return false;
        all |= part_bits[i];
    }
    return all == camera_part::known;
}
static_assert(parts_in_order());

} // namespace

std::span<const Field> theme_block_fields() noexcept { return theme_block_table; }
std::span<const Field> camera_state_fields() noexcept { return camera_state_table; }
std::span<const Field> shader_theme_fields() noexcept { return shader_theme_table; }
std::span<const Constant> layout_constants() noexcept { return constants_table; }

} // namespace atlas::style
