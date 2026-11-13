// The world's index (tiles/WorldIndex.hpp).
#include "tiles/WorldIndex.hpp"

#include "tiles/Bytes.hpp"
#include "tiles/Store.hpp"

#include <algorithm>
#include <cerrno>
#include <fcntl.h>
#include <format>
#include <fstream>
#include <iterator>
#include <sys/stat.h>
#include <unistd.h>

namespace atlas::tiles {

namespace {

/// A little-endian reader that fails once its bytes run out.
class LeReader {
public:
    explicit LeReader(std::span<const std::byte> bytes) noexcept : bytes_(bytes) {}

    [[nodiscard]] std::uint64_t le(std::size_t n) noexcept {
        if (!take(n)) return 0;
        std::uint64_t v = 0;
        for (std::size_t k = 0; k < n; ++k) {
            v |= std::uint64_t{std::to_integer<std::uint8_t>(bytes_[at_ + k])} << (8U * k);
        }
        at_ += n;
        return v;
    }
    [[nodiscard]] std::span<const std::byte> raw(std::size_t n) noexcept {
        if (!take(n)) return {};
        const auto out = bytes_.subspan(at_, n);
        at_ += n;
        return out;
    }
    [[nodiscard]] bool ok() const noexcept { return ok_; }
    [[nodiscard]] std::size_t left() const noexcept { return ok_ ? bytes_.size() - at_ : 0; }

private:
    [[nodiscard]] bool take(std::size_t n) noexcept {
        if (!ok_ || bytes_.size() - at_ < n) ok_ = false;
        return ok_;
    }

    std::span<const std::byte> bytes_;
    std::size_t at_ = 0;
    bool ok_ = true;
};

/// The file at `path`, whole; nothing when it cannot be read.
[[nodiscard]] std::optional<std::vector<std::byte>> read_file(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return std::nullopt;
    std::vector<char> bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    std::vector<std::byte> out(bytes.size());
    std::ranges::transform(bytes, out.begin(), [](char c) { return static_cast<std::byte>(c); });
    return out;
}

} // namespace

std::optional<WorldIndex> read_world_index(std::span<const std::byte> bytes) {
    LeReader r(bytes);
    const auto magic = r.raw(world_index_magic.size());
    if (!r.ok() || !std::ranges::equal(magic, as_bytes(world_index_magic))) return std::nullopt;
    if (r.le(2) != world_index_version) return std::nullopt;
    (void)r.le(2);
    WorldIndex index;
    index.date = static_cast<std::int64_t>(r.le(8));
    const std::uint64_t parts = r.le(2);
    for (std::uint64_t p = 0; p < parts && r.ok(); ++p) {
        const auto name = r.raw(r.le(1));
        const std::uint64_t length = r.le(8);
        std::string text(name.size(), '\0');
        std::ranges::transform(name, text.begin(), [](std::byte b) { return static_cast<char>(b); });
        index.parts.emplace_back(std::move(text), length);
    }
    const std::uint64_t count = r.le(4);
    if (!r.ok() || r.left() != count * world_entry_bytes) return std::nullopt;
    for (std::uint64_t i = 0; i < count; ++i) {
        WorldEntry e;
        e.z = static_cast<std::uint8_t>(r.le(1));
        e.first = static_cast<std::uint8_t>(r.le(1));
        e.last = static_cast<std::uint8_t>(r.le(1));
        e.part = static_cast<std::uint8_t>(r.le(1));
        e.x = static_cast<std::uint32_t>(r.le(4));
        e.y = static_cast<std::uint32_t>(r.le(4));
        e.offset = r.le(8);
        e.length = r.le(8);
        e.tiles = static_cast<std::uint32_t>(r.le(4));
        for (std::int32_t& v : e.box) v = static_cast<std::int32_t>(static_cast<std::uint32_t>(r.le(4)));
        std::ranges::copy(r.raw(e.sha256.size()), e.sha256.begin());
        index.entries.push_back(e);
    }
    if (!r.ok()) return std::nullopt;
    return index;
}

std::string world_pack_id(const WorldEntry& entry) { return std::format("world/{}/{}/{}", entry.z, entry.x, entry.y); }

std::string terrain_pack_id(std::string_view name, const WorldEntry& entry) {
    return std::format("terrain/{}/{}/{}/{}", name, entry.z, entry.x, entry.y);
}

Result<WorldAdded> add_terrain(Store& store, const std::string& index_path) {
    const auto bytes = read_file(index_path);
    if (!bytes) return std::unexpected(error(Fault::io, Site::file, static_cast<std::uint64_t>(ENOENT)));
    const std::optional<WorldIndex> index = read_world_index(*bytes);
    if (!index) return std::unexpected(error(Fault::bad_magic, Site::file));
    const std::size_t slash = index_path.find_last_of('/');
    const std::string dir = slash == std::string::npos ? std::string(".") : index_path.substr(0, slash);
    std::string name = slash == std::string::npos ? index_path : index_path.substr(slash + 1);
    if (const std::size_t dot = name.find_last_of('.'); dot != std::string::npos) name.resize(dot);
    WorldAdded added;
    for (const WorldEntry& e : index->entries) {
        if (e.part >= index->parts.size()) return std::unexpected(error(Fault::bad_value, Site::file, e.part));
        auto opened = store.add({.id = terrain_pack_id(name, e),
                                 .kind = PackKind::terrain_country,
                                 .path = std::format("{}/{}", dir, index->parts[e.part].first),
                                 .offset = e.offset,
                                 .length = e.length});
        if (!opened) return std::unexpected(opened.error());
        ++added.packs;
        added.tiles += e.tiles;
    }
    return added;
}

Result<WorldAdded> add_world(Store& store, const std::string& dir) {
    const auto bytes = read_file(dir + "/world.idx");
    if (!bytes) return std::unexpected(error(Fault::io, Site::file, static_cast<std::uint64_t>(ENOENT)));
    const std::optional<WorldIndex> index = read_world_index(*bytes);
    if (!index) return std::unexpected(error(Fault::bad_magic, Site::file));
    WorldAdded added;
    for (const WorldEntry& e : index->entries) {
        if (e.part >= index->parts.size()) return std::unexpected(error(Fault::bad_value, Site::file, e.part));
        const std::string path = std::format("{}/{}", dir, index->parts[e.part].first);
        const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC); // NOLINT(cppcoreguidelines-pro-type-vararg)
        if (fd < 0) return std::unexpected(error(Fault::io, Site::file, static_cast<std::uint64_t>(errno)));
        struct stat st{};
        if (::fstat(fd, &st) != 0 || std::cmp_less(st.st_size, e.offset + e.length)) {
            (void)::close(fd);
            return std::unexpected(error(Fault::truncated, Site::file, e.offset + e.length));
        }
        auto opened = store.add(
            {.id = world_pack_id(e), .kind = PackKind::world_mid, .fd = fd, .offset = e.offset, .length = e.length});
        (void)::close(fd); // the mapping outlives the descriptor
        if (!opened) return std::unexpected(opened.error());
        ++added.packs;
        added.tiles += e.tiles;
    }
    const std::string globe = std::format("{}/{}", dir, world_globe_file);
    struct stat st{};
    if (::stat(globe.c_str(), &st) == 0) {
        auto opened = store.add({.id = std::string(world_globe_id), .kind = PackKind::world_base, .path = globe});
        if (!opened) return std::unexpected(opened.error());
        added.globe = true;
    }
    return added;
}

} // namespace atlas::tiles
