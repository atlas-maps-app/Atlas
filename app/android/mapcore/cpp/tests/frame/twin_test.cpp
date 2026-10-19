// The twin's stream (twin/Twin.hpp) and the vertex stage on the CPU (twin/Emit.hpp): every record a pass can emit reads
// back as it was written, a layer's elements come back in painter's order (by slot, the emission order within one),
// a stream cut short or naming a definition it lacks is refused at its first bad record, and the projector puts a point
// where the camera's double maths does, within the float camera's error, snapped as '%.1f' prints at rest.
#include "twin/Emit.hpp"
#include "twin/Twin.hpp"
#include "view/Camera.hpp"
#include "view/Snap.hpp"

#include <doctest/doctest.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <format>
#include <string>
#include <vector>

using namespace atlas;

namespace {

/// A frame's worth of every record: definitions, a ground and an objects layer, groups, items, clip geometry, every
/// kind of element.
[[nodiscard]] twin::Stream sample() {
    twin::Stream out;
    const std::array<float, 3> offsets{0.0F, 0.5F, 1.0F};
    const std::array<std::uint32_t, 3> colours{0xFF102030U, 0x80405060U, 0x00708090U};
    const std::int32_t haze = out.gradient({.id = "haze",
                                            .radial = false,
                                            .coords = {0.0F, 120.5F, 0.0F, 916.0F, 0.0F},
                                            .units = twin::Units::user_space,
                                            .transformed = false,
                                            .transform = {1, 0, 0, 1, 0, 0},
                                            .spread = twin::Spread::pad,
                                            .offsets = offsets,
                                            .colours = colours});
    const std::int32_t mat = out.filter("mat");
    const std::int32_t scaf = out.pattern("scaf");
    const std::int32_t vb = out.begin_clip("vb");
    out.rect({}, 0, 0, 412, 916);
    out.end_clip();
    const std::int32_t root = out.group({.node = {.opacity = 1, .clip = vb}, .parent = twin::none});
    const std::int32_t objects = out.group({.node = {.opacity = 1, .filter = mat}, .parent = root});
    const std::int32_t church = out.item({.owner = twin::Owner::way, .id = 11'111'111, .kind = "building"});
    const std::int32_t tree = out.item({.owner = twin::Owner::node, .id = -7, .kind = "tree"});

    out.layer(twin::Layer::ground);
    out.at(0, root);
    const std::array<twin::Point, 4> square{
        {{.x = 10, .y = 10}, {.x = 50, .y = 10}, {.x = 50, .y = 50}, {.x = 10, .y = 50}}};
    const std::array<twin::Point, 3> hole{{{.x = 20, .y = 20}, {.x = 30, .y = 20}, {.x = 25, .y = 30}}};
    const std::array<twin::Subpath, 2> rings{{{.points = square, .closed = true}, {.points = hole, .closed = true}}};
    out.path({.fill = twin::Paint::colour(0xFFDCD3C4U), .fill_opacity = 1, .even_odd = true}, rings);
    out.layer(twin::Layer::objects);
    // Emitted out of painter's order: the tree (slot 9) before the church (slot 3), each item's elements in order.
    out.at(9, objects, tree);
    out.circle({.fill = twin::Paint::colour(0xFF3A5A30U)}, 100.25F, 200.75F, 6.5F);
    const std::array<float, 2> dashes{3.0F, 1.5F};
    out.at(3, objects, church);
    const std::array<twin::Point, 3> roof{{{.x = 60, .y = 60}, {.x = 80, .y = 40}, {.x = 100, .y = 60}}};
    out.path({.fill = twin::Paint::pattern(scaf),
              .stroke = twin::Paint::colour(0xFF202020U),
              .stroke_opacity = 0.5F,
              .stroke_width = 0.75F,
              .cap = twin::Cap::round,
              .join = twin::Join::bevel,
              .miter_limit = 2,
              .dashes = dashes,
              .dash_offset = 0.25F},
             roof, false);
    out.ellipse({.fill = twin::Paint::colour(0x80FFFFFFU)}, 70, 70, 4, 2);
    out.text({.fill = twin::Paint::colour(0xFF000000U)}, "Westerkerk", 70.5F, 80.5F,
             {.family = "Georgia, serif",
              .size = 11,
              .weight = 600,
              .italic = true,
              .letter_spacing = 0.5F,
              .anchor = twin::TextAnchor::middle},
             {.opacity = 0.9F, .transformed = true, .transform = {1, 0, 0.2F, 1, 3, 4}});
    out.at(twin::Stream::after);
    out.rect({.fill = twin::Paint::gradient(haze)}, 0, 120.5F, 412, 795.5F, 0, 0,
             {.opacity = 1, .blend = twin::Blend::multiply});
    return out;
}

} // namespace

TEST_CASE("every record of the twin's stream reads back as it was written") {
    const twin::Stream out = sample();
    CHECK(out.elements() == 6); // the clip's geometry is not drawn
    const twin::Decoded d = twin::decode(out.bytes());
    REQUIRE_MESSAGE(d.error.empty(), d.error);
    const twin::Document& doc = d.document;
    REQUIRE(doc.gradients.size() == 1);
    CHECK(doc.gradients[0].id == "haze");
    CHECK(doc.gradients[0].coords[1] == 120.5F);
    CHECK(doc.gradients[0].colours == std::vector<std::uint32_t>{0xFF102030U, 0x80405060U, 0x00708090U});
    CHECK(doc.filters == std::vector<std::string>{"mat"});
    CHECK(doc.patterns == std::vector<std::string>{"scaf"});
    REQUIRE(doc.clips.size() == 1);
    CHECK(doc.clips[0].id == "vb");
    REQUIRE(doc.groups.size() == 2);
    CHECK(doc.groups[1].parent == 0);
    CHECK(doc.groups[1].node.filter == 0);
    CHECK(doc.groups[0].node.clip == 0);
    REQUIRE(doc.items.size() == 2);
    CHECK(doc.items[0].owner == twin::Owner::way);
    CHECK(doc.items[0].id == 11'111'111);
    CHECK(doc.items[1].id == -7);
    CHECK(doc.items[1].kind == "tree");
    REQUIRE(doc.elements.size() == 7);
    const twin::Element& clip = doc.elements[0];
    CHECK(clip.clip == 0);
    CHECK(clip.kind == twin::Op::rect);
    const twin::Element& ground = doc.elements[1];
    CHECK(ground.layer == twin::Layer::ground);
    CHECK(ground.style.even_odd);
    CHECK(ground.subpath_ends == std::vector<std::uint32_t>{4, 7});
    CHECK(ground.closed == std::vector<std::uint8_t>{1, 1});
    CHECK(ground.points[5].x == 30.0F);
    const twin::Element& roof = doc.elements[3];
    CHECK(roof.kind == twin::Op::path);
    CHECK(roof.item == 0);
    CHECK(roof.slot == 3);
    CHECK(roof.style.fill.kind == twin::Paint::Kind::pattern);
    CHECK(roof.style.stroke.argb == 0xFF202020U);
    CHECK(roof.style.stroke_opacity == 0.5F);
    CHECK(roof.style.cap == twin::Cap::round);
    CHECK(roof.style.join == twin::Join::bevel);
    CHECK(roof.dashes == std::vector<float>{3.0F, 1.5F});
    CHECK(roof.style.dash_offset == 0.25F);
    CHECK(roof.closed == std::vector<std::uint8_t>{0});
    const twin::Element& label = doc.elements[5];
    CHECK(label.kind == twin::Op::text);
    CHECK(label.text == "Westerkerk");
    CHECK(label.family == "Georgia, serif");
    CHECK(label.font.weight == 600);
    CHECK(label.font.italic);
    CHECK(label.font.anchor == twin::TextAnchor::middle);
    CHECK(label.node.transformed);
    CHECK(label.node.transform[2] == 0.2F);
    CHECK(label.node.opacity == 0.9F);
    const twin::Element& haze = doc.elements[6];
    CHECK(haze.slot == twin::Stream::after);
    CHECK(haze.node.blend == twin::Blend::multiply);
    CHECK(haze.style.fill.kind == twin::Paint::Kind::gradient);
    CHECK(haze.points[1].y == 795.5F);

    // The objects in painter's order: the church's three elements (slot 3) in their order, the tree (9), the haze.
    const auto objects = doc.order(twin::Layer::objects);
    REQUIRE(objects.size() == 5);
    CHECK(objects[0]->kind == twin::Op::path);
    CHECK(objects[1]->kind == twin::Op::ellipse);
    CHECK(objects[2]->kind == twin::Op::text);
    CHECK(objects[3]->kind == twin::Op::circle);
    CHECK(objects[4] == &haze);
    CHECK(doc.order(twin::Layer::ground).size() == 1);
}

TEST_CASE("a twin stream cut short, or naming a definition it lacks, is refused") {
    const twin::Stream out = sample();
    const std::span<const std::byte> whole = out.bytes();
    // Cut at every byte: refused, except where a record ends.
    std::size_t refused = 0;
    std::size_t whole_records = 0;
    for (std::size_t n = 0; n < whole.size(); ++n) {
        const twin::Decoded d = twin::decode(whole.first(n));
        if (d.error.empty()) {
            ++whole_records;
        } else {
            ++refused;
        }
    }
    CHECK(refused > whole.size() / 2);
    CHECK(whole_records > 5); // a stream cut between records is a shorter frame
    MESSAGE(refused << " cuts refused, " << whole_records << " fall between records");
    // A gradient paint with no gradient defined.
    twin::Stream bad;
    bad.rect({.fill = twin::Paint::gradient(0)}, 0, 0, 1, 1);
    CHECK(twin::decode(bad.bytes()).error.starts_with("a malformed element"));
    // An element placed in a group that does not exist.
    twin::Stream lost;
    lost.at(0, 3);
    CHECK(twin::decode(lost.bytes()).error.starts_with("a malformed place"));
    // Another version.
    std::vector<std::byte> other(whole.begin(), whole.end());
    other[4] = std::byte{2};
    CHECK(twin::decode(other).error.starts_with("a twin stream of version 2"));
    CHECK(twin::decode({}).error == "not a twin stream");
    // A stream reused for the next frame starts again.
    twin::Stream again = sample();
    again.clear();
    CHECK(again.elements() == 0);
    CHECK(twin::decode(again.bytes()).document.elements.empty());
    CHECK(again.filter("grain") == 0);
}

TEST_CASE("the projector puts points where the vertex stage does, snapped as '%.1f' prints at rest") {
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
    const twin::Projector moving(v.block(false, false, 0));
    const twin::Projector resting(v.block(true, false, 0));
    CHECK_FALSE(moving.rests());
    CHECK(resting.rests());
    const view::Vec3 origin{0, 0, -1024};
    const shaders::TilePlace place = v.tile_place(origin, 1.0, 1.0);
    double worst = 0;
    std::size_t near = 0;
    std::size_t checked = 0;
    for (int i = 0; i <= 40; ++i) {
        for (int j = 0; j <= 40; ++j) {
            const hlsl::float3 local{static_cast<float>(i) * 25.0F, static_cast<float>(j % 7) * 6.0F,
                                     static_cast<float>(j) * 25.0F};
            const view::Vec3 world{origin.x + static_cast<double>(local.x), static_cast<double>(local.y),
                                   origin.z + static_cast<double>(local.z)};
            const auto want = v.camera().proj(world);
            const auto got = moving.css(place, local);
            if (!want || !got) {
                near += (!want && !got) ? 1U : 0U;
                continue;
            }
            // Within the frame and a frame around it, the float camera keeps a few thousandths of a pixel.
            if (want->x < -412 || want->x > 824 || want->y < -916 || want->y > 1832) continue;
            worst = std::max({worst, std::fabs(static_cast<double>(got->x) - want->x),
                              std::fabs(static_cast<double>(got->y) - want->y)});
            const auto snapped = resting.css(place, local);
            CHECK(snapped.has_value());
            const twin::Point s = snapped.value_or(twin::Point{.x = -1.0F, .y = -1.0F});
            CHECK(s.x == view::snap_tenth(got->x));
            CHECK(s.y == view::snap_tenth(got->y));
            ++checked;
        }
    }
    CHECK(checked > 500);
    CHECK(worst < 2e-3);
    MESSAGE(checked << " points: the float camera within " << worst << " CSS px of the double one; " << near
                    << " behind the near plane for both");
}
