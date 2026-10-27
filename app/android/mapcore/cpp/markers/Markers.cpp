#include "markers/Markers.hpp"

#include <cmath>
#include <cstring>
#include <format>
#include <tuple>

namespace atlas::markers {

std::string_view describe(MarkerError error) noexcept {
    switch (error) {
    case MarkerError::size: return "an image's pixels are not its width times its height, or an image is empty";
    case MarkerError::large: return "an image is larger than 2048 px on a side";
    case MarkerError::budget: return "the markers' images exceed their 32 MB";
    case MarkerError::look: return "a marker's look is none of the set's";
    case MarkerError::markers: return "more than 65,536 markers, or a marker's place or count is not one";
    case MarkerError::density: return "the density is not from 0.5 to 8";
    }
    return "?";
}

namespace {

/// Whether `image`'s pixels are its width x height, and it has some.
[[nodiscard]] bool sized(const Image& image) noexcept {
    return !image.empty() && image.pixels.size() == std::uint64_t{image.width} * image.height;
}

/// Whether a marker's place is one: a latitude and a longitude in their ranges.
[[nodiscard]] bool placed(const Marker& m) noexcept {
    return std::isfinite(m.latitude) && std::isfinite(m.longitude) && std::fabs(m.latitude) <= 90.0 &&
           std::fabs(m.longitude) <= 360.0;
}

} // namespace

std::expected<std::shared_ptr<const MarkerSet>, MarkerError> MarkerSet::make(std::vector<Marker> markers,
                                                                             std::vector<Image> looks,
                                                                             std::vector<CountImage> counts,
                                                                             float density, std::uint64_t revision) {
    if (!std::isfinite(density) || density < 0.5F || density > 8.0F) return std::unexpected(MarkerError::density);
    if (markers.size() > max_markers) return std::unexpected(MarkerError::markers);
    std::uint64_t total = 0;
    const auto check = [&](const Image& image) -> std::optional<MarkerError> {
        if (!sized(image)) return MarkerError::size;
        if (image.width > max_side || image.height > max_side) return MarkerError::large;
        total += image.pixels.size();
        if (total > max_texels) return MarkerError::budget;
        return std::nullopt;
    };
    for (const Image& image : looks) {
        if (const auto bad = check(image)) return std::unexpected(*bad);
    }
    for (const CountImage& c : counts) {
        if (const auto bad = check(c.image)) return std::unexpected(*bad);
    }
    for (const Marker& m : markers) {
        if (!placed(m) || m.count == 0 || static_cast<std::uint8_t>(m.style) > static_cast<std::uint8_t>(Style::chip)) {
            return std::unexpected(MarkerError::markers);
        }
        // a cluster is the core's own look; every other style has one of the set's
        const bool cluster = m.style == Style::cluster;
        const auto known = [&](std::uint32_t look) { return cluster ? look == no_look : look < looks.size(); };
        if (!known(m.look) || !known(m.look_left)) return std::unexpected(MarkerError::look);
    }
    std::shared_ptr<MarkerSet> set(new MarkerSet());
    set->density_ = density;
    set->revision_ = revision;
    set->texels_.reserve(static_cast<std::size_t>(total));
    const auto keep = [&](Image& image) {
        const auto at = static_cast<std::uint32_t>(set->texels_.size());
        set->texels_.insert(set->texels_.end(), image.pixels.begin(), image.pixels.end());
        image.pixels.clear();
        image.pixels.shrink_to_fit(); // the set keeps one copy of each texel
        return at;
    };
    set->look_offsets_.reserve(looks.size());
    for (Image& image : looks) set->look_offsets_.push_back(keep(image));
    // the counts by ink, then key, for count_of's search; a key twice in an ink keeps its first image
    std::ranges::stable_sort(counts, {}, [](const CountImage& c) { return std::pair(c.ink, c.key); });
    const auto [first, last] =
        std::ranges::unique(counts, {}, [](const CountImage& c) { return std::pair(c.ink, c.key); });
    counts.erase(first, last);
    set->count_offsets_.reserve(counts.size());
    for (CountImage& c : counts) set->count_offsets_.push_back(keep(c.image));
    set->markers_ = std::move(markers);
    set->looks_ = std::move(looks);
    set->counts_ = std::move(counts);
    return std::shared_ptr<const MarkerSet>(std::move(set));
}

std::optional<std::uint32_t> MarkerSet::count_of(std::uint32_t ink, std::uint64_t n) const noexcept {
    const std::pair want(ink, count_key(n));
    const auto it =
        std::ranges::lower_bound(counts_, want, {}, [](const CountImage& c) { return std::pair(c.ink, c.key); });
    if (it == counts_.end() || it->ink != want.first || it->key != want.second) return std::nullopt;
    return static_cast<std::uint32_t>(it - counts_.begin());
}

std::expected<MarkerBlock, std::string> read_marker_block(std::span<const std::byte> block) {
    std::size_t at = 0;
    const auto take = [&](void* out, std::size_t bytes) {
        if (block.size() - at < bytes) return false;
        if (bytes > 0) std::memcpy(out, block.data() + at, bytes); // an empty image's data() may be null
        at += bytes;
        return true;
    };
    // an image's head and pixels; false where the block ends first or the image is larger than any may be
    const auto image = [&](Image& out) {
        if (!take(&out.width, 4) || !take(&out.height, 4) || !take(&out.anchor_x, 4) || !take(&out.anchor_y, 4)) {
            return false;
        }
        if (out.width > MarkerSet::max_side || out.height > MarkerSet::max_side) return false;
        out.pixels.resize(std::size_t{out.width} * out.height);
        return take(out.pixels.data(), out.pixels.size() * 4);
    };
    std::uint32_t magic = 0;
    std::uint32_t looks = 0;
    std::uint32_t markers = 0;
    std::uint32_t counts = 0;
    MarkerBlock out;
    if (!take(&magic, 4) || magic != marker_block_magic) return std::unexpected("not a block of markers");
    if (!take(&out.density, 4) || !take(&looks, 4) || !take(&markers, 4) || !take(&counts, 4)) {
        return std::unexpected("the block ends before its counts");
    }
    if (markers > MarkerSet::max_markers)
        return std::unexpected(std::format("{} markers are more than may be", markers));
    out.looks.reserve(std::min<std::size_t>(looks, block.size() / 16));
    for (std::uint32_t i = 0; i < looks; ++i) {
        if (!image(out.looks.emplace_back())) return std::unexpected(std::format("look {} does not read", i));
    }
    out.markers.reserve(markers);
    for (std::uint32_t i = 0; i < markers; ++i) {
        Marker& m = out.markers.emplace_back();
        std::uint8_t style = 0;
        std::uint8_t selected = 0;
        std::uint8_t value = 0;
        std::uint8_t zero = 0;
        if (!take(&m.latitude, 8) || !take(&m.longitude, 8) || !take(&m.priority, 4) || !take(&style, 1) ||
            !take(&selected, 1) || !take(&value, 1) || !take(&zero, 1) || !take(&m.count, 4) ||
            !take(&m.chip_text, 4) || !take(&m.name, 4) || !take(&m.colour, 4) || !take(&m.ink, 4) ||
            !take(&m.look, 4) || !take(&m.look_left, 4)) {
            return std::unexpected(std::format("the block ends in marker {}", i));
        }
        if (style > static_cast<std::uint8_t>(Style::chip) || selected > 1 || value > 1 || zero != 0) {
            return std::unexpected(std::format("marker {}'s style or flags are none", i));
        }
        m.style = static_cast<Style>(style);
        m.selected = selected != 0;
        m.value = value != 0;
    }
    out.counts.reserve(std::min<std::size_t>(counts, block.size() / 24));
    for (std::uint32_t i = 0; i < counts; ++i) {
        CountImage& c = out.counts.emplace_back();
        if (!take(&c.ink, 4) || !take(&c.key, 4) || !image(c.image)) {
            return std::unexpected(std::format("count {} does not read", i));
        }
    }
    if (at != block.size()) return std::unexpected("the block runs on past its markers");
    return out;
}

} // namespace atlas::markers
