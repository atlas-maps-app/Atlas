// The twin (plan-vector T6, D16's L1): what the frame draws at its camera, emitted on the CPU by every pass
// (frame::Pass::emit) as the display list the port's SVG is, so L1 can match the two element by element and RefRaster
// can rasterise it for L2's frame check. It is written as a stream (Stream), which MT/twin/TwinCodec.kt reads on the
// JVM into the SVG model the port's render parses into (AN/svg/SvgModel.kt: the documents AnchorCompiler's parse
// makes) and so into G1's primitive model (AN/Anchor.kt); MT/twin/L1Diff.kt matches kind, paint, vertices after %.1f,
// group and filter chain. The phone's VK check reads it too (decode).
//
// What a pass emits, in the port's terms:
//   - shapes and text runs (path, rect, circle, ellipse, text): an SVG element each, every coordinate in the frame's
//     CSS px as the GPU draws it (the vertex stage's float maths, snapped at rest: twin/Emit.hpp's Projector), with its
//     style (fill and stroke, their paints and opacities, the fill rule, the stroke's width, caps, joins and dashes)
//     and its node's own properties (opacity, blend, filter, clip, transform: what an SVG element carries itself);
//   - the definitions they refer to, once a frame: gradients, filters, patterns, clip paths, by the SVG's ids;
//   - where each element goes: its layer (the render's ground or objects image), its group (an SVG <g> with an
//     effect, nested by parent: the objects' stroke-linejoin and `mat` group, the areas' `mat` group, the root's clip),
//     its item (the painter unit of the objects it belongs to, with its OSM owner) and its painter slot.
// Passes emit in any order: the reader orders each layer's elements by painter slot, keeping the emission order within
// a slot (a stable sort), and wraps consecutive elements of one group in one instance of it. The screen-fixed steps the
// renders draw after every object (wfx, the haze, fin) take slots past the frame's (Stream::after).
//
// The stream, version 1, little-endian: "TWN1", u32 version, then records, each a u8 opcode and its fields (opcode
// below). Strings are u32 byte counts and UTF-8 bytes; points are f32 pairs; a node is f32 opacity, u8 blend, i32
// filter, i32 clip, u8 has transform and then six f32 (a b c d e f); a paint is u8 kind, u32 argb, i32 ref; a style is
// the fill's paint, f32 fill opacity, u8 even-odd, the stroke's paint, f32 stroke opacity, f32 width, u8 cap, u8 join,
// f32 miter limit, u32 dash count and the dashes (f32), f32 dash offset.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace atlas::twin {

/// The stream's layout version (TwinCodec.VERSION in Kotlin).
inline constexpr std::uint32_t stream_version = 1;

/// The render's images an element goes into (the port's layers: `g` and `o`, with `o2` read as `o`; `t` the relief).
enum class Layer : std::uint8_t { ground = 0, raster = 1, objects = 2 };

/// CSS's mix-blend-mode, as SvgBlend.
enum class Blend : std::uint8_t {
    normal = 0,
    multiply = 1,
    screen = 2,
    overlay = 3,
    soft_light = 4,
    darken = 5,
    lighten = 6
};

/// A stroke's caps and joins, as SvgStyle's.
enum class Cap : std::uint8_t { butt = 0, round = 1, square = 2 };
enum class Join : std::uint8_t { miter = 0, round = 1, bevel = 2 };

/// A text run's anchor, as SvgFont's.
enum class TextAnchor : std::uint8_t { start = 0, middle = 1, end = 2 };

/// Whether a gradient's, pattern's or clip's coordinates are user units or fractions of the bounding box.
enum class Units : std::uint8_t { user_space = 0, bounding_box = 1 };

/// A gradient's spread.
enum class Spread : std::uint8_t { pad = 0, reflect = 1, repeat = 2 };

/// OSM element kinds of an item's owner, as AnchorItems' OwnerType.
enum class Owner : std::uint8_t { none = 0, node = 1, way = 2, relation = 3, area = 4, other = 5 };

/// No index: no filter, no clip, no group, no item.
inline constexpr std::int32_t none = -1;

/// An affine transform `a b c d e f` (x' = a·x + c·y + e, y' = b·x + d·y + f).
using Affine = std::array<float, 6>;

/// What an SVG element carries itself (SvgNode): its opacity, blend, filter, clip path and transform.
struct Node {
    float opacity = 1.0F;
    Blend blend = Blend::normal;
    std::int32_t filter = none; ///< Stream::filter's index
    std::int32_t clip = none;   ///< Stream::begin_clip's index
    bool transformed = false;
    Affine transform{1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F};
};

/// A fill's or a stroke's paint (SvgPaint): none, a colour (its own alpha), or a gradient's or pattern's index.
struct Paint {
    enum class Kind : std::uint8_t { none = 0, colour = 1, gradient = 2, pattern = 3 } kind = Kind::none;
    std::uint32_t argb = 0;
    std::int32_t ref = none;

    [[nodiscard]] static constexpr Paint colour(std::uint32_t argb) noexcept {
        return {.kind = Kind::colour, .argb = argb, .ref = none};
    }
    [[nodiscard]] static constexpr Paint gradient(std::int32_t index) noexcept {
        return {.kind = Kind::gradient, .argb = 0, .ref = index};
    }
    [[nodiscard]] static constexpr Paint pattern(std::int32_t index) noexcept {
        return {.kind = Kind::pattern, .argb = 0, .ref = index};
    }
};

/// An element's presentation (SvgStyle): fill and stroke.
struct Style {
    Paint fill;
    float fill_opacity = 1.0F;
    bool even_odd = false;
    Paint stroke;
    float stroke_opacity = 1.0F;
    float stroke_width = 1.0F;
    Cap cap = Cap::butt;
    Join join = Join::miter;
    float miter_limit = 4.0F;
    std::span<const float> dashes; ///< already doubled when odd, as SvgStyle's; empty for a solid line
    float dash_offset = 0.0F;
};

/// A text run's font (SvgFont).
struct Font {
    std::string_view family;
    float size = 16.0F;
    std::int32_t weight = 400;
    bool italic = false;
    float letter_spacing = 0.0F;
    TextAnchor anchor = TextAnchor::start;
};

/// A gradient (SvgGradient): linear (x1 y1 x2 y2) or radial (cx cy r fx fy), its stops' offsets and colours (each
/// colour's alpha with its stop-opacity folded in).
struct Gradient {
    std::string_view id;
    bool radial = false;
    std::array<float, 5> coords{};
    Units units = Units::bounding_box;
    bool transformed = false;
    Affine transform{1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F};
    Spread spread = Spread::pad;
    std::span<const float> offsets;
    std::span<const std::uint32_t> colours;
};

/// A group (an SVG <g> with an effect): its node and its parent group (none: the layer's root).
struct Group {
    Node node;
    std::int32_t parent = none;
};

/// An item: a painter unit of the objects (a building, a part, a tree, a lamp, ...), its owner and its kind.
struct Item {
    Owner owner = Owner::none;
    std::int64_t id = 0;
    std::string_view kind;
};

/// The records' opcodes, and each record's fields after its opcode.
enum class Op : std::uint8_t {
    /// u8 layer: the layer of the elements that follow.
    layer = 1,
    /// i32 index, i32 parent, node: a group's definition.
    group = 2,
    /// i32 index, u8 owner, i64 id, string kind: an item's definition.
    item = 3,
    /// i32 index, string id, u8 radial, five f32, u8 units, u8 transformed, six f32, u8 spread, u32 n, n × (f32
    /// offset, u32 argb).
    gradient = 4,
    /// i32 index, string id.
    filter = 5,
    /// i32 index, string id.
    pattern = 6,
    /// i32 index, string id, u8 units, i32 parent clip: the shapes until clip_end are its geometry.
    clip_begin = 7,
    /// No fields.
    clip_end = 8,
    /// u32 slot, i32 group, i32 item: where the elements that follow go.
    at = 9,
    /// node, style, u32 subpaths, each u8 closed, u32 n and n points.
    path = 10,
    /// node, style, f32 x y width height rx ry.
    rect = 11,
    /// node, style, f32 cx cy r.
    circle = 12,
    /// node, style, f32 cx cy rx ry.
    ellipse = 13,
    /// node, style, string text, f32 x y, string family, f32 size, i32 weight, u8 italic, f32 letter spacing, u8
    /// anchor.
    text = 14,
};

/// A point of the frame, CSS px.
struct Point {
    float x = 0;
    float y = 0;
};

/// One subpath of a path: its points and whether it closes.
struct Subpath {
    std::span<const Point> points;
    bool closed = true;
};

/// The twin's stream for one frame. Reuses its storage frame to frame; not thread-safe (one pass emits at a time).
class Stream {
public:
    /// A slot after every painter slot of the frame: the screen-fixed steps the renders draw after every object, in
    /// emission order (wfx, the haze, fin).
    static constexpr std::uint32_t after = std::numeric_limits<std::uint32_t>::max();

    Stream();

    /// Empties the stream for a new frame (its definitions' indices start again from 0).
    void clear();

    // ---- Definitions, each once a frame; elements refer to them by the index returned.

    [[nodiscard]] std::int32_t gradient(const Gradient& g);
    [[nodiscard]] std::int32_t filter(std::string_view id);
    [[nodiscard]] std::int32_t pattern(std::string_view id);
    [[nodiscard]] std::int32_t group(const Group& g);
    [[nodiscard]] std::int32_t item(const Item& i);
    /// A clip path: the shapes emitted until end_clip are its geometry (their styles ignored), in user space or the
    /// bounding box's fractions; `parent` clips it in turn.
    [[nodiscard]] std::int32_t begin_clip(std::string_view id, Units units = Units::user_space,
                                          std::int32_t parent = none);
    void end_clip();

    /// The frame's root group (the SVG's `<g clip-path="url(#vb)">`, which FrameBuilder::emit defines before the passes
    /// emit), for the passes' groups to hang from; none until set. clear forgets it.
    void set_root(std::int32_t group) noexcept { root_ = group; }
    [[nodiscard]] std::int32_t root() const noexcept { return root_; }

    // ---- Where the elements that follow go.

    /// The layer (the render's image) of the elements that follow; the objects by default.
    void layer(Layer l);
    /// The painter slot, innermost group and item of the elements that follow (none for no group or item).
    void at(std::uint32_t slot, std::int32_t group = none, std::int32_t item = none);

    // ---- Elements, in CSS px of the frame.

    void path(const Style& s, std::span<const Subpath> subpaths, const Node& n = {});
    /// One closed or open subpath.
    void path(const Style& s, std::span<const Point> points, bool closed, const Node& n = {});
    void rect(const Style& s, float x, float y, float width, float height, float rx = 0.0F, float ry = 0.0F,
              const Node& n = {});
    void circle(const Style& s, float cx, float cy, float r, const Node& n = {});
    void ellipse(const Style& s, float cx, float cy, float rx, float ry, const Node& n = {});
    void text(const Style& s, std::string_view text, float x, float y, const Font& font, const Node& n = {});

    /// The stream's bytes so far.
    [[nodiscard]] std::span<const std::byte> bytes() const noexcept { return bytes_; }
    /// Elements emitted this frame (clip geometry excluded).
    [[nodiscard]] std::size_t elements() const noexcept { return elements_; }

private:
    void op(Op o);
    void u8(std::uint8_t v);
    void u32(std::uint32_t v);
    void i32(std::int32_t v);
    void i64(std::int64_t v);
    void f32(float v);
    void str(std::string_view s);
    void node(const Node& n);
    void paint(const Paint& p);
    void style(const Style& s);
    void element();

    std::vector<std::byte> bytes_;
    std::int32_t gradients_ = 0;
    std::int32_t filters_ = 0;
    std::int32_t patterns_ = 0;
    std::int32_t groups_ = 0;
    std::int32_t items_ = 0;
    std::int32_t clips_ = 0;
    std::int32_t root_ = none;
    bool in_clip_ = false;
    std::size_t elements_ = 0;
};

/// One element of a decoded stream, with where it goes. It owns its values: its style's dashes are in `dashes` and its
/// font's family in `family` (the style's and the font's views are left empty).
struct Element {
    Op kind = Op::path;
    Layer layer = Layer::objects;
    std::uint32_t slot = 0;
    std::int32_t group = none;
    std::int32_t item = none;
    std::int32_t clip = none; ///< the clip path it is geometry of (none: drawn)
    Node node;
    Style style;
    std::vector<float> dashes;
    /// A path's points, each subpath's end among them and whether it closes; a rect's x y, width height, rx ry; a
    /// circle's centre and r 0; an ellipse's centre and rx ry; a text run's x y.
    std::vector<Point> points;
    std::vector<std::uint32_t> subpath_ends;
    std::vector<std::uint8_t> closed;
    std::string text;
    Font font;
    std::string family;
    std::uint64_t sequence = 0; ///< its place among the stream's elements
};

/// A decoded stream: its definitions, and its elements in the stream's order (order() gives a layer's in painter's
/// order). Everything is owned: no value views the stream.
struct Document {
    struct GradientDef {
        std::string id;
        bool radial = false;
        std::array<float, 5> coords{};
        Units units = Units::bounding_box;
        bool transformed = false;
        Affine transform{1.0F, 0.0F, 0.0F, 1.0F, 0.0F, 0.0F};
        Spread spread = Spread::pad;
        std::vector<float> offsets;
        std::vector<std::uint32_t> colours;
    };
    struct ItemDef {
        Owner owner = Owner::none;
        std::int64_t id = 0;
        std::string kind;
    };
    struct ClipDef {
        std::string id;
        Units units = Units::user_space;
        std::int32_t parent = none;
    };
    std::vector<GradientDef> gradients;
    std::vector<std::string> filters;
    std::vector<std::string> patterns;
    std::vector<Group> groups;
    std::vector<ItemDef> items;
    std::vector<ClipDef> clips;
    std::vector<Element> elements;

    /// The drawn elements of `layer` in painter's order: by slot, the stream's order within a slot.
    [[nodiscard]] std::vector<const Element*> order(Layer layer) const;
};

/// The document of a stream, or the reason it is malformed.
struct Decoded {
    Document document;
    std::string error; ///< empty when the stream decoded whole
};

/// Reads a stream (for the tests and VK check; the JVM reads it with TwinCodec.kt).
[[nodiscard]] Decoded decode(std::span<const std::byte> bytes);

} // namespace atlas::twin
