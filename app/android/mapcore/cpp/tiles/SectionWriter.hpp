// A render pack's `rhead` and `style` written (rendertiles' RenderHead.toBytes and StyleTables.toBytes;
// tiles/Sections.hpp reads them), so the packs and windows the core builds carry their own (ENGINE.md step 3: built
// tiles become local packs; tiles/PackWriter.hpp writes the pack around them). The bytes are the packer's for the same
// values: what a head holds and the style's tables in their order, every value typed (tiles/ValueWriter.hpp).
#pragma once

#include "tiles/Error.hpp"
#include "tiles/Sections.hpp"
#include "tiles/Sink.hpp"
#include "tiles/ValueWriter.hpp"

#include <cstddef>
#include <cstdint>
#include <span>
#include <string_view>
#include <vector>

namespace atlas::tiles {

/// One of the head's views to write: its name, its camera (a typed map, as a pack or a document holds it) and the
/// ladder index of the tolerance its renders load the region at.
struct HeadViewOut {
    std::string_view name;
    Value camera;
    std::uint8_t tolerance = 0;
};

/// What `rhead` holds (tiles/Sections.hpp says what each is).
struct HeadOut {
    Profile profile = Profile::region;
    std::span<const std::uint8_t> levels; ///< finest first
    Frame frame;
    std::string_view region;
    std::string_view engine_version;
    std::string_view style_version;
    std::string_view source;
    std::span<const HeadViewOut> views;
    std::span<const HeadBand> bands; ///< nearest first; every one but the last ends
};

/// `rhead`'s bytes, or why a head cannot hold these values (RenderHead's own requirements): no level or more than 255,
/// no band or more than 255, a band but the last without an end or the last with one, an end that is NaN, a tolerance
/// off the ladder, a view's camera that is not a map.
[[nodiscard]] Result<std::vector<std::byte>> encode_head(const HeadOut& head);

/// `style`'s tables, written one after another, each a name and one typed value.
class StyleOut {
public:
    /// Starts a table named `name`: write its one value into the sink returned, before the next table.
    [[nodiscard]] ValueSink table(std::string_view name);

    /// The section's bytes, before compression (PackParts::style): magic, payload version, the tables' count, then
    /// each.
    [[nodiscard]] std::vector<std::byte> bytes() const;

private:
    Sink body_;
    std::size_t count_ = 0;
};

} // namespace atlas::tiles
