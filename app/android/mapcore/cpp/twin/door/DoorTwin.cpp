#include "twin/door/DoorTwin.hpp"

#include "frame/FrameBlock.hpp"
#include "frame/FrameBuilder.hpp"
#include "style/Theme.hpp"
#include "text/GroundText.hpp"

#include <array>

namespace atlas::twin {

namespace {

[[nodiscard]] Paint colour(std::uint32_t rgb) noexcept { return Paint::colour(0xFF00'0000U | rgb); }

} // namespace

void emit_doors(const frame::FrameState& state, std::span<const passes::DoorShape> doors,
                const tiles::DoorsStyle& style, Stream& out) {
    if (doors.empty()) return;
    const bool night = state.theme.has(style::theme_flag::night);
    const bool rest = state.rest;
    const auto at = [rest](double v) { return rest ? text::printed_float(v, 1) : static_cast<float>(v); };
    out.layer(Layer::objects); // the port's objects image (`o`)
    // the objects group: the port's `<g stroke-linejoin="round" filter="url(#mat)">` (doors draw with weather alone)
    const std::int32_t objects = out.group({.node = {.filter = out.filter("mat")}, .parent = out.root()});
    for (const passes::DoorShape& d : doors) {
        const std::uint32_t slot = shaders::rank_slot(d.rank);
        const std::int32_t item = out.item({.owner = Owner::node, .id = d.id, .kind = "door"});
        const auto face = [&](const passes::DoorFace& f, std::uint32_t k, const Style& s) {
            if (f.count == 0) return;
            std::array<Point, 5> pts{};
            for (std::uint32_t i = 0; i < f.count; ++i)
                pts.at(i) = {.x = at(f.points.at(i).x), .y = at(f.points.at(i).y)};
            out.at(slot + k, objects, item);
            out.path(s, std::span<const Point>(pts.data(), f.count), true);
        };
        // face(cam, quad(1.15, 0.0, 2.45), colour, ' stroke="#E8E2D6" stroke-width=".5"'), then the stoop
        face(d.door, 0,
             {.fill = colour(d.colour),
              .stroke = colour(style.outline),
              .stroke_width = style.outline_width,
              .join = Join::round});
        face(d.stoop, 2, {.fill = colour(night ? style.stoop.night : style.stoop.day)});
        if (!d.plate) continue;
        // '<rect x y width height rx fill/><text x y text-anchor="middle" font-family="Georgia, serif" font-size fill>'
        const float rx = at(d.rx);
        out.at(slot + 3, objects, item);
        out.rect({.fill = colour(night ? style.plate.night : style.plate.day)}, at(d.x), at(d.y), at(d.width),
                 at(d.height), rx, rx);
        const Font font{.family = door_number_family,
                        .size = text::printed_float(d.size, 1),
                        .weight = 400,
                        .italic = false,
                        .letter_spacing = 0.0F,
                        .anchor = TextAnchor::middle};
        out.at(slot + 4, objects, item);
        out.text({.fill = colour(night ? style.number.night : style.number.day)}, d.number, at(d.text_x), at(d.text_y),
                 font);
    }
}

} // namespace atlas::twin
