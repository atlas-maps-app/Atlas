#include "tiles/Sections.hpp"

#include "tiles/Bytes.hpp"
#include "tiles/Chunks.hpp"

#include <algorithm>
#include <cmath>
#include <utility>

namespace atlas::tiles {

std::optional<HeadView> RenderHead::view(std::string_view name) const noexcept {
    const auto it = std::ranges::find(views_, name, &HeadView::name);
    if (it == views_.end()) return std::nullopt;
    return *it;
}

std::optional<std::uint8_t> RenderHead::tolerance_of(std::optional<std::string_view> view,
                                                     double distance) const noexcept {
    if (view) {
        const auto v = this->view(*view);
        if (!v) return std::nullopt;
        return v->tolerance;
    }
    for (const HeadBand& b : bands_) {
        if (!b.up_to || distance < *b.up_to) return b.tolerance;
    }
    return std::nullopt; // unreachable: the last band has no end
}

bool RenderHead::never_simplified() const noexcept {
    return std::ranges::all_of(views_, [](const HeadView& v) { return v.tolerance == 0; }) &&
           std::ranges::all_of(bands_, [](const HeadBand& b) { return b.tolerance == 0; });
}

Result<RenderHead> read_head(std::span<const std::byte> section) {
    RenderHead h;
    h.bytes_.assign(section.begin(), section.end());
    Reader r(h.bytes_);
    auto fail = [&r](Fault f, std::uint64_t detail = 0) {
        return std::unexpected(error(r.ok() ? f : r.fault(), Site::head, detail));
    };
    if (r.u32le() != head_magic) return fail(Fault::bad_magic);
    const std::uint16_t payload = r.u16le();
    if (!r.ok()) return fail(Fault::truncated);
    if (payload != payload_version) return fail(Fault::bad_version, payload);
    const std::uint8_t profile = r.u8();
    if (profile > static_cast<std::uint8_t>(Profile::world_base)) return fail(Fault::bad_value, profile);
    h.profile = static_cast<Profile>(profile);
    const std::uint8_t level_count = r.u8();
    for (std::uint8_t i = 0; i < level_count; ++i) {
        const std::uint8_t z = r.u8();
        if (z > max_zoom || (!h.levels.empty() && z >= h.levels.back())) return fail(Fault::bad_value, z);
        h.levels.push_back(z);
    }
    h.frame.lat0 = r.f64le();
    h.frame.lon0 = r.f64le();
    h.frame.kx = r.f64le();
    h.frame.kz = r.f64le();
    h.region = r.utf8();
    h.engine_version = r.utf8();
    h.style_version = r.utf8();
    h.source = r.utf8();
    const std::uint32_t n = r.count();
    if (!r.ok()) return fail(Fault::truncated);
    if (n > r.remaining()) return fail(Fault::bad_count, n);
    // a ladder index: one of keep::tolerances
    auto ladder = [&r]() -> std::optional<std::uint8_t> {
        const std::uint8_t i = r.u8();
        if (!r.ok() || i >= keep::tolerances.size()) return std::nullopt;
        return i;
    };
    ValueReader values({}, true);
    struct Root {
        std::string_view name;
        std::uint32_t root = 0;
        std::uint8_t tolerance = 0;
    };
    std::vector<Root> roots;
    for (std::uint32_t i = 0; i < n; ++i) {
        const std::string_view name = r.utf8();
        const auto root = values.read(r);
        if (!root) return fail(root.error().fault, i);
        if (values.nodes()[*root].type != ValueType::map) return fail(Fault::bad_value, i); // a view's camera is a map
        const auto tolerance = ladder();
        if (!tolerance) return fail(Fault::bad_value, i);
        if (std::ranges::contains(roots, name, &Root::name)) return fail(Fault::duplicate, i);
        roots.push_back({.name = name, .root = *root, .tolerance = *tolerance});
    }
    const std::uint8_t band_count = r.u8();
    if (!r.ok()) return fail(Fault::truncated);
    if (band_count == 0) return fail(Fault::missing, n);
    for (std::uint8_t b = 0; b < band_count; ++b) {
        const auto tolerance = ladder();
        if (!tolerance) return fail(Fault::bad_value, b);
        h.bands_.push_back({.tolerance = *tolerance, .up_to = std::nullopt});
    }
    for (std::uint8_t b = 0; b + 1 < band_count; ++b) {
        const double end = r.f64le();
        if (!r.ok()) return fail(Fault::truncated);
        if (std::isnan(end)) return fail(Fault::bad_value, b);
        h.bands_[b].up_to = end;
    }
    if (!r.done()) return fail(Fault::left_over);
    h.nodes_ = values.take_nodes();
    h.strings_ = values.take_strings();
    for (const Root& v : roots)
        h.views_.push_back({.name = v.name, .camera = Value(h.nodes_, h.strings_, v.root), .tolerance = v.tolerance});
    return h;
}

std::optional<Value> StyleTables::table(std::string_view name) const noexcept {
    const auto it = std::ranges::find(tables_, name, &std::pair<std::string_view, Value>::first);
    if (it == tables_.end()) return std::nullopt;
    return it->second;
}

Result<StyleTables> read_style(std::vector<std::byte> raw) {
    StyleTables s;
    s.bytes_ = std::move(raw);
    Reader r(s.bytes_);
    auto fail = [&r](Fault f, std::uint64_t detail = 0) {
        return std::unexpected(error(r.ok() ? f : r.fault(), Site::style, detail));
    };
    if (r.u32le() != style_magic) return fail(Fault::bad_magic);
    const std::uint16_t payload = r.u16le();
    if (!r.ok()) return fail(Fault::truncated);
    if (payload != payload_version) return fail(Fault::bad_version, payload);
    const std::uint32_t n = r.count();
    if (!r.ok()) return fail(Fault::truncated);
    if (n > r.remaining()) return fail(Fault::bad_count, n);
    ValueReader values({}, true);
    std::vector<std::pair<std::string_view, std::uint32_t>> roots;
    for (std::uint32_t i = 0; i < n; ++i) {
        const std::string_view name = r.utf8();
        const auto root = values.read(r);
        if (!root) return fail(root.error().fault, i);
        if (std::ranges::contains(roots, name, &std::pair<std::string_view, std::uint32_t>::first)) {
            return fail(Fault::duplicate, i);
        }
        roots.emplace_back(name, *root);
    }
    if (!r.done()) return fail(Fault::left_over);
    s.nodes_ = values.take_nodes();
    s.strings_ = values.take_strings();
    for (const auto& [name, root] : roots) s.tables_.emplace_back(name, Value(s.nodes_, s.strings_, root));
    return s;
}

} // namespace atlas::tiles
