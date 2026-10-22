// A render pack's `rhead` and `style` written (tiles/SectionWriter.hpp).
#include "tiles/SectionWriter.hpp"

#include "tiles/Schema.hpp"

#include <cmath>
#include <utility>

namespace atlas::tiles {

Result<std::vector<std::byte>> encode_head(const HeadOut& head) {
    const auto bad = [](std::uint64_t detail = 0) {
        return std::unexpected(error(Fault::bad_value, Site::head, detail));
    };
    if (head.levels.empty() || head.levels.size() > 255) return bad(head.levels.size());
    if (head.bands.empty() || head.bands.size() > 255) return bad(head.bands.size());
    for (std::size_t i = 0; i < head.bands.size(); ++i) {
        const HeadBand& b = head.bands[i];
        if (b.up_to.has_value() == (i + 1 == head.bands.size())) return bad(i);
        if (b.up_to && std::isnan(*b.up_to)) return bad(i);
        if (b.tolerance >= keep::tolerances.size()) return bad(b.tolerance);
    }
    for (const HeadViewOut& v : head.views) {
        if (v.tolerance >= keep::tolerances.size()) return bad(v.tolerance);
        if (v.camera.type() != ValueType::map) return bad();
    }
    Sink out;
    out.u32(head_magic);
    out.u16(payload_version);
    out.u8(static_cast<std::uint8_t>(head.profile));
    out.u8(head.levels.size());
    for (const std::uint8_t z : head.levels) out.u8(z);
    out.f64(head.frame.lat0);
    out.f64(head.frame.lon0);
    out.f64(head.frame.kx);
    out.f64(head.frame.kz);
    out.utf8(head.region);
    out.utf8(head.engine_version);
    out.utf8(head.style_version);
    out.utf8(head.source);
    out.varu(head.views.size());
    ValueSink values(out);
    for (const HeadViewOut& v : head.views) {
        out.utf8(v.name);
        values.value(v.camera);
        out.u8(v.tolerance);
    }
    out.u8(head.bands.size());
    for (const HeadBand& b : head.bands) out.u8(b.tolerance);
    for (const HeadBand& b : head.bands) {
        if (b.up_to) out.f64(*b.up_to);
    }
    return out.take();
}

ValueSink StyleOut::table(std::string_view name) {
    ++count_;
    body_.utf8(name);
    return ValueSink(body_);
}

std::vector<std::byte> StyleOut::bytes() const {
    Sink out;
    out.u32(style_magic);
    out.u16(payload_version);
    out.varu(count_);
    out.raw(body_.bytes());
    return out.take();
}

} // namespace atlas::tiles
