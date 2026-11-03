// The kept walls' and near cells' shape records (shaders/building/building.slangh's BuildingShapeRecord, compiled as
// C++; passes/Kept.cpp): the cull's last stage decides each kept wall once into its record, and the vertex stage places
// each of its points from it, so a point placed from the record must be the point deciding the wall there places, bit
// for bit: each of its 16 outline points, its face plane's reciprocal depth there and the depth it draws at; its
// colour, and whether the app asks about its building. Over random shapes of every count, lead, slot and span, at rest
// and moving, on float and 24-bit depth, and under the painter switch.
#include "twin/building/BuildingShader.hpp"
#include "view/Camera.hpp"

#include <doctest/doctest.h>

#include <array>
#include <bit>
#include <cstdint>
#include <optional>
#include <random>
#include <vector>

namespace {

namespace sh = atlas::shaders;
namespace view = atlas::view;
using atlas::hlsl::float2;
using atlas::hlsl::float3;
using atlas::hlsl::float4;

/// A generator of a seed given (the test's sequence is fixed, so every run checks the same shapes).
[[nodiscard]] std::mt19937_64 seeded(std::uint64_t seed) { return std::mt19937_64(seed); }

/// Whether two floats are the same bits.
[[nodiscard]] bool same(float a, float b) noexcept {
    return std::bit_cast<std::uint32_t>(a) == std::bit_cast<std::uint32_t>(b);
}

/// The cameras the records are held at: a phone's view, moving and at rest, on float and 24-bit depth, and the painter
/// switch on each.
[[nodiscard]] std::vector<sh::FrameCamera> cameras() {
    const view::View v({.target = {120, 0, -340},
                        .yaw = 52,
                        .pitch = 38,
                        .dist = 430,
                        .fov = 36,
                        .width = 412,
                        .height = 916,
                        .cx = std::nullopt,
                        .cy = 440},
                       {.density = 1080.0 / 412.0, .width_px = 1080, .height_px = 2400, .quarter_turns = 0});
    std::vector<sh::FrameCamera> out;
    for (const bool rest : {false, true}) {
        for (const std::uint32_t flags :
             {0U, sh::CAMERA_DEPTH24, sh::CAMERA_PAINTER, sh::CAMERA_DEPTH24 | sh::CAMERA_PAINTER}) {
            sh::FrameCamera cam = v.block(rest, false, 0);
            cam.flags.x |= flags;
            out.push_back(cam);
        }
    }
    return out;
}

/// Every lead building_shape gives a shape: a cell's (its row and column modulo the cells' leads, a kit's fewer), a
/// roof's, a kit eaves', a kit's band's and its corner board's.
[[nodiscard]] std::vector<float> leads() {
    std::vector<float> out;
    for (std::uint32_t i = 0; i < sh::BUILDING_CELL_LEADS; ++i)
        out.push_back(static_cast<float>(i) * sh::BUILDING_CELL_LEAD);
    out.push_back(sh::BUILDING_ROOF_LEAD);
    out.push_back(sh::BUILDING_EAVES_LEAD);
    out.push_back(sh::BUILDING_KIT_BAND_LEAD);
    out.push_back(sh::BUILDING_KIT_CORNER_LEAD);
    return out;
}

/// A random shape of building_shape's kinds: 3 to 5 corners in and about the frame, some repeated or in a line (the
/// outline's point, segment and flat cases), reciprocal depths and a plane of either sign, a span about them, a lead,
/// a painter slot and strokes; on its wall's plane or not.
[[nodiscard]] sh::BuildingShape random_shape(std::mt19937_64& g, const sh::FrameCamera& cam,
                                             const std::vector<float>& leads) {
    std::uniform_real_distribution<float> x(-320.0F, 740.0F);
    std::uniform_real_distribution<float> y(-320.0F, 1240.0F);
    std::uniform_real_distribution<float> unit(-1.0F, 1.0F);
    std::uniform_real_distribution<float> recip(1e-5F, 1.0F);
    std::uniform_real_distribution<float> reach(20.0F, 400.0F);
    std::uniform_int_distribution<std::uint32_t> pick(0, 7);
    sh::BuildingShape s = sh::building_nothing();
    s.count = 3 + (pick(g) % 3);
    const std::uint32_t kind = pick(g);
    for (std::uint32_t i = 0; i < s.count; ++i) {
        float2 p = {x(g), y(g)};
        if (kind == 0 && i > 0) p = sh::building_corner(s, 0);                                    // a point
        if (kind == 1 && i > 1) p = sh::building_corner(s, i - 1);                                // repeated corners
        if (kind == 2 && i > 1) p = sh::building_corner(s, 1) * 2.0F - sh::building_corner(s, 0); // in a line
        s = sh::building_with_corner(s, i, p, recip(g));
    }
    const float4 plane = {unit(g), unit(g), unit(g), reach(g) * (pick(g) < 4 ? 1.0F : -1.0F)};
    s.plane = plane;
    const float a = recip(g);
    const float b = recip(g);
    s.lo = (a < b ? a : b) * (1.0F - sh::BUILDING_PLANE_SPAN);
    s.hi = (a < b ? b : a) * (1.0F + sh::BUILDING_PLANE_SPAN);
    s.lead = leads[std::uniform_int_distribution<std::size_t>(0, leads.size() - 1)(g)];
    s.slot = std::uniform_int_distribution<std::uint32_t>(0, sh::PAINTER_SLOTS - 1)(g);
    s.depth = sh::painter_depth(cam, s.slot);
    s.strokes = pick(g) * 4;
    s.on_plane = pick(g) < 2;
    return s;
}

/// A face's colour at a moment, as colour_at makes it: its lit and unlit light, cross-faded.
[[nodiscard]] float3 random_colour(std::mt19937_64& g) {
    std::uniform_int_distribution<std::uint32_t> light(0, 0xFFFFFF);
    std::uniform_real_distribution<float> amount(0.0F, 1.0F);
    return sh::building_colour(light(g), light(g), amount(g));
}

/// The stroke half widths the walls' outlines take (the style's same-colour strokes').
constexpr std::array<float, 3> halves{0.15F, 0.2F, 0.5F};

} // namespace

TEST_CASE("buildings: a wall's outline record places its 16 points where deciding it at each point places them") {
    const std::vector<float> lead_list = leads();
    std::mt19937_64 g = seeded(20261103);
    std::uint32_t wrong = 0;
    std::uint32_t shapes = 0;
    for (const sh::FrameCamera& cam : cameras()) {
        for (std::uint32_t n = 0; n < 4000; ++n) {
            const sh::BuildingShape s = random_shape(g, cam, lead_list);
            const float3 colour = random_colour(g);
            const std::uint32_t asked = n % 2;
            const sh::BuildingShapeRecord r = sh::building_outline_record(s, colour, asked);
            const sh::BuildingShape t = sh::building_outline_shape(cam, r);
            bool ok = sh::building_shape_count(r.v0.x) == s.count && sh::building_shape_asked(r.v0.x) == asked;
            const float3 c = sh::building_shape_colour(r);
            ok = ok && same(c.x, colour.x) && same(c.y, colour.y) && same(c.z, colour.z);
            for (const float half : halves) {
                for (std::uint32_t v = 0; v < sh::BUILDING_EDGED_VERTICES; ++v) {
                    const float2 at = sh::building_outline(s, v, half);
                    const float2 placed = sh::building_outline(t, v, half);
                    const float rs = sh::building_plane_recip(cam, s, at);
                    const float rt = sh::building_plane_recip(cam, t, placed);
                    ok = ok && same(at.x, placed.x) && same(at.y, placed.y) && same(rs, rt) &&
                         same(sh::building_depth(cam, s, rs), sh::building_depth(cam, t, rt));
                }
            }
            wrong += ok ? 0 : 1;
            ++shapes;
        }
    }
    MESSAGE(shapes << " walls' records, " << wrong << " placing a point elsewhere");
    CHECK(wrong == 0);
}

TEST_CASE("buildings: a shape record's header holds every count, lead and painter slot building_shape gives") {
    const sh::FrameCamera cam = cameras().front();
    const std::vector<float> lead_list = leads();
    std::mt19937_64 g = seeded(20261105);
    std::uniform_int_distribution<std::uint32_t> any_slot(0, sh::PAINTER_SLOTS - 1);
    std::uint32_t wrong = 0;
    for (const float lead : lead_list) {
        for (std::uint32_t count = 0; count <= 5; ++count) {
            for (const std::uint32_t slot : {0U, 1U, sh::PAINTER_SLOTS - 1, any_slot(g)}) {
                for (const std::uint32_t asked : {0U, 1U}) {
                    sh::BuildingShape s = sh::building_nothing();
                    s.count = count;
                    s.lead = lead;
                    s.slot = slot;
                    const std::uint32_t header = sh::building_shape_header(s, asked);
                    const bool ok =
                        sh::building_shape_count(header) == count && sh::building_shape_asked(header) == asked &&
                        same(sh::building_shape_lead(header), lead) && sh::building_shape_slot(header) == slot;
                    // the depth of the slot, and the lead, as the record's shape has them
                    const sh::BuildingShapeRecord r = sh::building_outline_record(s, {}, asked);
                    const sh::BuildingShape outlined = sh::building_outline_shape(cam, r);
                    const bool held = same(outlined.depth, sh::painter_depth(cam, slot)) && same(outlined.lead, lead) &&
                                      outlined.count == count;
                    wrong += ok && held ? 0 : 1;
                }
            }
        }
    }
    CHECK(wrong == 0);
    // a shape that draws nothing writes a header of no corners, which the vertex stage puts nowhere
    CHECK(sh::building_shape_count(0U) == 0);
}
