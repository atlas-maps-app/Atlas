// The twin's stream (twin/Twin.hpp).
#include "twin/Twin.hpp"

#include <algorithm>
#include <bit>
#include <cstring>
#include <format>
#include <type_traits>
#include <utility>

namespace atlas::twin {

namespace {

constexpr std::array<std::byte, 4> magic{std::byte{'T'}, std::byte{'W'}, std::byte{'N'}, std::byte{'1'}};

/// Little-endian bytes of an integer.
template <class T>
void put(std::vector<std::byte>& out, T v) {
    const auto u = static_cast<std::make_unsigned_t<T>>(v);
    for (std::size_t i = 0; i < sizeof(T); ++i) out.push_back(static_cast<std::byte>((u >> (8U * i)) & 0xFFU));
}

/// The reader of a stream: fields in order, failing at the first one past the end.
class Reader {
public:
    explicit Reader(std::span<const std::byte> b) noexcept : b_(b) {}

    [[nodiscard]] bool ok() const noexcept { return ok_; }
    [[nodiscard]] bool done() const noexcept { return at_ >= b_.size(); }
    [[nodiscard]] std::size_t at() const noexcept { return at_; }

    template <class T>
    [[nodiscard]] T get() noexcept {
        if (!ok_ || b_.size() - at_ < sizeof(T)) {
            ok_ = false;
            return T{};
        }
        std::make_unsigned_t<T> u = 0;
        for (std::size_t i = 0; i < sizeof(T); ++i) {
            u |= static_cast<std::make_unsigned_t<T>>(static_cast<std::make_unsigned_t<T>>(b_[at_ + i]) << (8U * i));
        }
        at_ += sizeof(T);
        return static_cast<T>(u);
    }
    [[nodiscard]] float f32() noexcept { return std::bit_cast<float>(get<std::uint32_t>()); }
    [[nodiscard]] std::string str() {
        const auto n = get<std::uint32_t>();
        if (!ok_ || b_.size() - at_ < n) {
            ok_ = false;
            return {};
        }
        std::string s(n, '\0');
        std::memcpy(s.data(), b_.data() + at_, n);
        at_ += n;
        return s;
    }
    /// A count of things of at least `each` bytes, refused when the rest of the stream cannot hold them.
    [[nodiscard]] std::uint32_t count(std::size_t each) noexcept {
        const auto n = get<std::uint32_t>();
        if (ok_ && static_cast<std::uint64_t>(n) * each > b_.size() - at_) ok_ = false;
        return ok_ ? n : 0;
    }

private:
    std::span<const std::byte> b_;
    std::size_t at_ = 0;
    bool ok_ = true;
};

[[nodiscard]] Node read_node(Reader& r) noexcept {
    Node n;
    n.opacity = r.f32();
    n.blend = static_cast<Blend>(r.get<std::uint8_t>());
    n.filter = r.get<std::int32_t>();
    n.clip = r.get<std::int32_t>();
    n.transformed = r.get<std::uint8_t>() != 0;
    for (float& v : n.transform) v = r.f32();
    return n;
}

[[nodiscard]] Paint read_paint(Reader& r) noexcept {
    Paint p;
    p.kind = static_cast<Paint::Kind>(r.get<std::uint8_t>());
    p.argb = r.get<std::uint32_t>();
    p.ref = r.get<std::int32_t>();
    return p;
}

void read_style(Reader& r, Element& e) {
    Style& s = e.style;
    s.fill = read_paint(r);
    s.fill_opacity = r.f32();
    s.even_odd = r.get<std::uint8_t>() != 0;
    s.stroke = read_paint(r);
    s.stroke_opacity = r.f32();
    s.stroke_width = r.f32();
    s.cap = static_cast<Cap>(r.get<std::uint8_t>());
    s.join = static_cast<Join>(r.get<std::uint8_t>());
    s.miter_limit = r.f32();
    const std::uint32_t n = r.count(4);
    e.dashes.resize(n);
    for (float& d : e.dashes) d = r.f32();
    s.dash_offset = r.f32();
}

/// Whether a node's values are in range and its references defined.
[[nodiscard]] bool valid(const Node& n, const Document& d) noexcept {
    return static_cast<std::uint8_t>(n.blend) <= 6 && n.filter >= none &&
           (n.filter == none || static_cast<std::size_t>(n.filter) < d.filters.size()) && n.clip >= none &&
           (n.clip == none || static_cast<std::size_t>(n.clip) < d.clips.size());
}

/// Whether a paint's kind is known and its reference defined.
[[nodiscard]] bool valid(const Paint& p, const Document& d) noexcept {
    switch (p.kind) {
    case Paint::Kind::none:
    case Paint::Kind::colour: return true;
    case Paint::Kind::gradient: return p.ref >= 0 && static_cast<std::size_t>(p.ref) < d.gradients.size();
    case Paint::Kind::pattern: return p.ref >= 0 && static_cast<std::size_t>(p.ref) < d.patterns.size();
    }
    return false;
}

/// Whether an element's style and font are in range and its references defined.
[[nodiscard]] bool valid(const Element& e, const Document& d) noexcept {
    const Style& s = e.style;
    return valid(e.node, d) && valid(s.fill, d) && valid(s.stroke, d) && static_cast<std::uint8_t>(s.cap) <= 2 &&
           static_cast<std::uint8_t>(s.join) <= 2 && static_cast<std::uint8_t>(e.font.anchor) <= 2;
}

} // namespace

Stream::Stream() { clear(); }

void Stream::clear() {
    bytes_.clear();
    bytes_.insert(bytes_.end(), magic.begin(), magic.end());
    u32(stream_version);
    gradients_ = filters_ = patterns_ = groups_ = items_ = clips_ = 0;
    root_ = none;
    in_clip_ = false;
    elements_ = 0;
}

void Stream::op(Op o) { u8(static_cast<std::uint8_t>(o)); }
void Stream::u8(std::uint8_t v) { bytes_.push_back(static_cast<std::byte>(v)); }
void Stream::u32(std::uint32_t v) { put(bytes_, v); }
void Stream::i32(std::int32_t v) { put(bytes_, v); }
void Stream::i64(std::int64_t v) { put(bytes_, v); }
void Stream::f32(float v) { put(bytes_, std::bit_cast<std::uint32_t>(v)); }

void Stream::str(std::string_view s) {
    u32(static_cast<std::uint32_t>(s.size()));
    const auto* p = reinterpret_cast<const std::byte*>(s.data()); // NOLINT(cppcoreguidelines-pro-type-reinterpret-cast)
    bytes_.insert(bytes_.end(), p, p + s.size());
}

void Stream::node(const Node& n) {
    f32(n.opacity);
    u8(static_cast<std::uint8_t>(n.blend));
    i32(n.filter);
    i32(n.clip);
    u8(n.transformed ? 1U : 0U);
    for (const float v : n.transform) f32(v);
}

void Stream::paint(const Paint& p) {
    u8(static_cast<std::uint8_t>(p.kind));
    u32(p.argb);
    i32(p.ref);
}

void Stream::style(const Style& s) {
    paint(s.fill);
    f32(s.fill_opacity);
    u8(s.even_odd ? 1U : 0U);
    paint(s.stroke);
    f32(s.stroke_opacity);
    f32(s.stroke_width);
    u8(static_cast<std::uint8_t>(s.cap));
    u8(static_cast<std::uint8_t>(s.join));
    f32(s.miter_limit);
    u32(static_cast<std::uint32_t>(s.dashes.size()));
    for (const float d : s.dashes) f32(d);
    f32(s.dash_offset);
}

void Stream::element() {
    if (!in_clip_) ++elements_;
}

std::int32_t Stream::gradient(const Gradient& g) {
    op(Op::gradient);
    const std::int32_t index = gradients_++;
    i32(index);
    str(g.id);
    u8(g.radial ? 1U : 0U);
    for (const float c : g.coords) f32(c);
    u8(static_cast<std::uint8_t>(g.units));
    u8(g.transformed ? 1U : 0U);
    for (const float v : g.transform) f32(v);
    u8(static_cast<std::uint8_t>(g.spread));
    const std::size_t n = std::min(g.offsets.size(), g.colours.size());
    u32(static_cast<std::uint32_t>(n));
    for (std::size_t i = 0; i < n; ++i) {
        f32(g.offsets[i]);
        u32(g.colours[i]);
    }
    return index;
}

std::int32_t Stream::filter(std::string_view id) {
    op(Op::filter);
    const std::int32_t index = filters_++;
    i32(index);
    str(id);
    return index;
}

std::int32_t Stream::pattern(std::string_view id) {
    op(Op::pattern);
    const std::int32_t index = patterns_++;
    i32(index);
    str(id);
    return index;
}

std::int32_t Stream::group(const Group& g) {
    op(Op::group);
    const std::int32_t index = groups_++;
    i32(index);
    i32(g.parent);
    node(g.node);
    return index;
}

std::int32_t Stream::item(const Item& i) {
    op(Op::item);
    const std::int32_t index = items_++;
    i32(index);
    u8(static_cast<std::uint8_t>(i.owner));
    i64(i.id);
    str(i.kind);
    return index;
}

std::int32_t Stream::begin_clip(std::string_view id, Units units, std::int32_t parent) {
    op(Op::clip_begin);
    const std::int32_t index = clips_++;
    i32(index);
    str(id);
    u8(static_cast<std::uint8_t>(units));
    i32(parent);
    in_clip_ = true;
    return index;
}

void Stream::end_clip() {
    op(Op::clip_end);
    in_clip_ = false;
}

void Stream::layer(Layer l) {
    op(Op::layer);
    u8(static_cast<std::uint8_t>(l));
}

void Stream::at(std::uint32_t slot, std::int32_t group, std::int32_t item) {
    op(Op::at);
    u32(slot);
    i32(group);
    i32(item);
}

void Stream::path(const Style& s, std::span<const Subpath> subpaths, const Node& n) {
    op(Op::path);
    node(n);
    style(s);
    u32(static_cast<std::uint32_t>(subpaths.size()));
    for (const Subpath& sp : subpaths) {
        u8(sp.closed ? 1U : 0U);
        u32(static_cast<std::uint32_t>(sp.points.size()));
        for (const Point& p : sp.points) {
            f32(p.x);
            f32(p.y);
        }
    }
    element();
}

void Stream::path(const Style& s, std::span<const Point> points, bool closed, const Node& n) {
    const std::array<Subpath, 1> one{Subpath{.points = points, .closed = closed}};
    path(s, one, n);
}

void Stream::rect(const Style& s, float x, float y, float width, float height, float rx, float ry, const Node& n) {
    op(Op::rect);
    node(n);
    style(s);
    for (const float v : {x, y, width, height, rx, ry}) f32(v);
    element();
}

void Stream::circle(const Style& s, float cx, float cy, float r, const Node& n) {
    op(Op::circle);
    node(n);
    style(s);
    for (const float v : {cx, cy, r}) f32(v);
    element();
}

void Stream::ellipse(const Style& s, float cx, float cy, float rx, float ry, const Node& n) {
    op(Op::ellipse);
    node(n);
    style(s);
    for (const float v : {cx, cy, rx, ry}) f32(v);
    element();
}

void Stream::text(const Style& s, std::string_view text, float x, float y, const Font& font, const Node& n) {
    op(Op::text);
    node(n);
    style(s);
    str(text);
    f32(x);
    f32(y);
    str(font.family);
    f32(font.size);
    i32(font.weight);
    u8(font.italic ? 1U : 0U);
    f32(font.letter_spacing);
    u8(static_cast<std::uint8_t>(font.anchor));
    element();
}

std::vector<const Element*> Document::order(Layer layer) const {
    std::vector<const Element*> out;
    for (const Element& e : elements) {
        if (e.layer == layer && e.clip == none) out.push_back(&e);
    }
    std::ranges::stable_sort(out, [](const Element* a, const Element* b) { return a->slot < b->slot; });
    return out;
}

Decoded decode(std::span<const std::byte> bytes) {
    Decoded out;
    Document& d = out.document;
    auto fail = [&out](std::string why) {
        out.error = std::move(why);
        return std::move(out);
    };
    if (bytes.size() < 8 || !std::equal(magic.begin(), magic.end(), bytes.begin())) return fail("not a twin stream");
    Reader r(bytes.subspan(4));
    if (const auto v = r.get<std::uint32_t>(); v != stream_version) {
        return fail(std::format("a twin stream of version {}, this reader reads {}", v, stream_version));
    }
    Layer layer = Layer::objects;
    std::uint32_t slot = 0;
    std::int32_t group = none;
    std::int32_t item = none;
    std::int32_t clip = none;
    std::uint64_t sequence = 0;
    // A definition's index must be the next of its kind.
    auto next = [](std::int32_t index, std::size_t have) { return std::cmp_equal(index, have); };
    while (!r.done()) {
        const std::size_t start = r.at() + 4;
        const auto o = static_cast<Op>(r.get<std::uint8_t>());
        switch (o) {
        case Op::layer: {
            const auto l = r.get<std::uint8_t>();
            if (l > 2) return fail(std::format("layer {} at byte {}", l, start));
            layer = static_cast<Layer>(l);
            break;
        }
        case Op::group: {
            const auto index = r.get<std::int32_t>();
            Group g;
            g.parent = r.get<std::int32_t>();
            g.node = read_node(r);
            if (!next(index, d.groups.size()) || g.parent >= index || g.parent < none || !valid(g.node, d)) {
                return fail(std::format("a malformed group at byte {}", start));
            }
            d.groups.push_back(g);
            break;
        }
        case Op::item: {
            const auto index = r.get<std::int32_t>();
            Document::ItemDef i;
            i.owner = static_cast<Owner>(r.get<std::uint8_t>());
            i.id = r.get<std::int64_t>();
            i.kind = r.str();
            if (!next(index, d.items.size()) || static_cast<std::uint8_t>(i.owner) > 5) {
                return fail(std::format("a malformed item at byte {}", start));
            }
            d.items.push_back(std::move(i));
            break;
        }
        case Op::gradient: {
            const auto index = r.get<std::int32_t>();
            Document::GradientDef g;
            g.id = r.str();
            g.radial = r.get<std::uint8_t>() != 0;
            for (float& c : g.coords) c = r.f32();
            g.units = static_cast<Units>(r.get<std::uint8_t>());
            g.transformed = r.get<std::uint8_t>() != 0;
            for (float& v : g.transform) v = r.f32();
            g.spread = static_cast<Spread>(r.get<std::uint8_t>());
            const std::uint32_t n = r.count(8);
            for (std::uint32_t i = 0; i < n; ++i) {
                g.offsets.push_back(r.f32());
                g.colours.push_back(r.get<std::uint32_t>());
            }
            if (!next(index, d.gradients.size()) || static_cast<std::uint8_t>(g.units) > 1 ||
                static_cast<std::uint8_t>(g.spread) > 2) {
                return fail(std::format("a malformed gradient at byte {}", start));
            }
            d.gradients.push_back(std::move(g));
            break;
        }
        case Op::filter:
        case Op::pattern: {
            const auto index = r.get<std::int32_t>();
            std::string id = r.str();
            std::vector<std::string>& defs = o == Op::filter ? d.filters : d.patterns;
            if (!next(index, defs.size())) return fail(std::format("a malformed definition at byte {}", start));
            defs.push_back(std::move(id));
            break;
        }
        case Op::clip_begin: {
            const auto index = r.get<std::int32_t>();
            Document::ClipDef c;
            c.id = r.str();
            c.units = static_cast<Units>(r.get<std::uint8_t>());
            c.parent = r.get<std::int32_t>();
            if (!next(index, d.clips.size()) || clip != none || c.parent >= index || c.parent < none ||
                static_cast<std::uint8_t>(c.units) > 1) {
                return fail(std::format("a malformed clip path at byte {}", start));
            }
            d.clips.push_back(std::move(c));
            clip = index;
            break;
        }
        case Op::clip_end:
            if (clip == none) return fail(std::format("a clip path's end without its beginning at byte {}", start));
            clip = none;
            break;
        case Op::at:
            slot = r.get<std::uint32_t>();
            group = r.get<std::int32_t>();
            item = r.get<std::int32_t>();
            if (group < none || item < none || (group != none && static_cast<std::size_t>(group) >= d.groups.size()) ||
                (item != none && static_cast<std::size_t>(item) >= d.items.size())) {
                return fail(std::format("a malformed place at byte {}", start));
            }
            break;
        case Op::path:
        case Op::rect:
        case Op::circle:
        case Op::ellipse:
        case Op::text: {
            Element e;
            e.kind = o;
            e.layer = layer;
            e.slot = slot;
            e.group = group;
            e.item = item;
            e.clip = clip;
            e.sequence = sequence++;
            e.node = read_node(r);
            read_style(r, e);
            if (o == Op::path) {
                const std::uint32_t subpaths = r.count(5);
                for (std::uint32_t k = 0; k < subpaths; ++k) {
                    e.closed.push_back(r.get<std::uint8_t>());
                    const std::uint32_t n = r.count(8);
                    for (std::uint32_t i = 0; i < n; ++i) e.points.push_back({.x = r.f32(), .y = r.f32()});
                    e.subpath_ends.push_back(static_cast<std::uint32_t>(e.points.size()));
                }
            } else if (o == Op::rect) {
                for (int i = 0; i < 3; ++i) e.points.push_back({.x = r.f32(), .y = r.f32()});
            } else if (o == Op::circle) {
                e.points.push_back({.x = r.f32(), .y = r.f32()});
                e.points.push_back({.x = r.f32(), .y = 0.0F});
            } else if (o == Op::ellipse) {
                for (int i = 0; i < 2; ++i) e.points.push_back({.x = r.f32(), .y = r.f32()});
            } else {
                e.text = r.str();
                e.points.push_back({.x = r.f32(), .y = r.f32()});
                e.family = r.str();
                e.font.size = r.f32();
                e.font.weight = r.get<std::int32_t>();
                e.font.italic = r.get<std::uint8_t>() != 0;
                e.font.letter_spacing = r.f32();
                e.font.anchor = static_cast<TextAnchor>(r.get<std::uint8_t>());
            }
            if (!r.ok() || !valid(e, d)) return fail(std::format("a malformed element at byte {}", start));
            d.elements.push_back(std::move(e));
            break;
        }
        default: return fail(std::format("opcode {} at byte {}", static_cast<unsigned>(o), start));
        }
        if (!r.ok()) return fail(std::format("the stream ends inside a record at byte {}", start));
    }
    if (clip != none) return fail("the stream ends inside a clip path");
    return out;
}

} // namespace atlas::twin
