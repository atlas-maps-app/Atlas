#include "tiles/ExpandedCache.hpp"

#include "tiles/Bytes.hpp"

#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <fcntl.h>
#include <filesystem>
#include <format>
#include <sys/stat.h>
#include <system_error>
#include <unistd.h>

namespace atlas::tiles {
namespace {

constexpr std::uint32_t cache_magic = 0x4358'5441U; // "ATXC"
constexpr std::uint16_t cache_format = 1;
constexpr std::string_view entry_suffix = ".x";
constexpr std::string_view temp_marker = ".tmp-";

void put_le(std::span<std::byte> out, std::size_t at, std::uint64_t v, std::size_t n) noexcept {
    for (std::size_t k = 0; k < n; ++k) out[at + k] = static_cast<std::byte>(v >> (8U * k));
}

[[nodiscard]] std::uint64_t get_le(std::span<const std::byte> in, std::size_t at, std::size_t n) noexcept {
    std::uint64_t v = 0;
    for (std::size_t k = 0; k < n; ++k) v |= std::to_integer<std::uint64_t>(in[at + k]) << (8U * k);
    return v;
}

[[nodiscard]] std::uint64_t now_ns() noexcept {
    return static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::system_clock::now().time_since_epoch())
            .count());
}

/// Writes all of `bytes` to `fd`.
[[nodiscard]] bool write_all(int fd, std::span<const std::byte> bytes) noexcept {
    while (!bytes.empty()) {
        const ssize_t n = ::write(fd, bytes.data(), bytes.size());
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        bytes = bytes.subspan(static_cast<std::size_t>(n));
    }
    return true;
}

/// Whether `name` is an entry's file name: 64 lower-case hex digits and the suffix.
[[nodiscard]] bool is_entry_name(std::string_view name) noexcept {
    if (name.size() != 64 + entry_suffix.size() || !name.ends_with(entry_suffix)) return false;
    return std::ranges::all_of(name.substr(0, 64),
                               [](char c) { return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'); });
}

} // namespace

Sha256Digest CacheKey::digest() const noexcept {
    std::array<std::byte, 16> head{};
    constexpr std::string_view tag = "atlas-xc";
    std::ranges::copy(as_bytes(tag), head.begin());
    head[8] = static_cast<std::byte>(kind);
    put_le(head, 9, format, 4);
    put_le(head, 13, style.size() & 0xFF'FFFFU, 3);
    Sha256 h;
    h.update(head);
    h.update(source);
    h.update(as_bytes(style));
    return h.finish();
}

std::vector<std::byte> ExpandedCache::encode(const CacheKey& key, std::span<const std::byte> payload) {
    std::vector<std::byte> out(cache_header_bytes + payload.size());
    put_le(out, 0, cache_magic, 4);
    put_le(out, 4, cache_format, 2);
    out[6] = static_cast<std::byte>(key.kind);
    out[7] = std::byte{0};
    const Sha256Digest digest = key.digest();
    std::ranges::copy(digest, out.begin() + 8);
    put_le(out, 40, payload.size(), 8);
    put_le(out, 48, crc32(payload), 4);
    put_le(out, 52, crc32(std::span(out).first(52)), 4);
    std::ranges::copy(payload, out.begin() + static_cast<std::ptrdiff_t>(cache_header_bytes));
    return out;
}

Result<std::span<const std::byte>> ExpandedCache::check(std::span<const std::byte> file, const Sha256Digest* key) {
    auto fail = [](Fault f, std::uint64_t detail = 0) { return std::unexpected(error(f, Site::cache, detail)); };
    if (file.size() < cache_header_bytes) return fail(Fault::truncated, file.size());
    if (get_le(file, 0, 4) != cache_magic) return fail(Fault::bad_magic);
    if (get_le(file, 4, 2) != cache_format) return fail(Fault::bad_version, get_le(file, 4, 2));
    const auto kind = std::to_integer<std::uint8_t>(file[6]);
    if (kind < static_cast<std::uint8_t>(CacheKind::built) || kind > static_cast<std::uint8_t>(CacheKind::pack_head) ||
        file[7] != std::byte{0}) {
        return fail(Fault::bad_value, kind);
    }
    if (get_le(file, 52, 4) != crc32(file.first(52))) return fail(Fault::damaged, 1);
    if (key != nullptr && !std::ranges::equal(file.subspan(8, 32), *key)) return fail(Fault::bad_value, 2);
    if (std::ranges::any_of(file.subspan(56, 8), [](std::byte b) { return b != std::byte{0}; }))
        return fail(Fault::bad_value, 3);
    const std::uint64_t length = get_le(file, 40, 8);
    if (length != file.size() - cache_header_bytes) return fail(Fault::bad_count, length);
    const std::span<const std::byte> payload = file.subspan(cache_header_bytes);
    if (get_le(file, 48, 4) != crc32(payload)) return fail(Fault::damaged, 2);
    return payload;
}

Result<std::unique_ptr<ExpandedCache>> ExpandedCache::open(const std::string& directory, std::uint64_t budget) {
    std::error_code ec;
    std::filesystem::create_directories(directory, ec);
    if (ec || !std::filesystem::is_directory(directory, ec)) {
        return std::unexpected(error(Fault::io, Site::cache, static_cast<std::uint64_t>(ec.value())));
    }
    std::unique_ptr<ExpandedCache> cache(new ExpandedCache(directory, budget));
    cache->scan();
    cache->evict_to(budget);
    return cache;
}

std::string ExpandedCache::path_of(const Sha256Digest& digest) const {
    return std::format("{}/{}{}", directory_, hex(digest), entry_suffix);
}

void ExpandedCache::scan() {
    std::error_code ec;
    std::vector<std::string> doomed;
    std::uint64_t newest = now_ns();
    for (std::filesystem::directory_iterator it(directory_, ec), end; !ec && it != end; it.increment(ec)) {
        const std::string name = it->path().filename().string();
        const std::string path = it->path().string();
        if (name.find(temp_marker) != std::string::npos) { // an interrupted write
            doomed.push_back(path);
            continue;
        }
        if (!is_entry_name(name)) continue; // not ours
        struct stat st{};
        if (::stat(path.c_str(), &st) != 0 || !S_ISREG(st.st_mode)) continue;
        // the header alone: a full check of every entry would read the whole cache at each start
        std::array<std::byte, cache_header_bytes> header{};
        bool good = false;
        if (const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC); fd >= 0) {
            good = ::pread(fd, header.data(), header.size(), 0) == static_cast<ssize_t>(header.size());
            (void)::close(fd);
        }
        Sha256Digest digest{};
        std::ranges::copy(std::span(header).subspan(8, digest.size()), digest.begin());
        good = good && get_le(header, 0, 4) == cache_magic && get_le(header, 4, 2) == cache_format &&
               get_le(header, 52, 4) == crc32(std::span(header).first(52)) &&
               get_le(header, 40, 8) == static_cast<std::uint64_t>(st.st_size) - cache_header_bytes &&
               hex(digest) == name.substr(0, 64);
        if (!good) {
            doomed.push_back(path);
            continue;
        }
#if defined(__APPLE__)
        const auto mtime = static_cast<std::uint64_t>(st.st_mtimespec.tv_sec) * 1'000'000'000U +
                           static_cast<std::uint64_t>(st.st_mtimespec.tv_nsec);
#else
        const auto mtime = static_cast<std::uint64_t>(st.st_mtim.tv_sec) * 1'000'000'000U +
                           static_cast<std::uint64_t>(st.st_mtim.tv_nsec);
#endif
        newest = std::max(newest, mtime);
        const std::scoped_lock lock(mutex_);
        index_[name] = {.bytes = static_cast<std::uint64_t>(st.st_size), .used = mtime, .generation = 0};
        total_ += static_cast<std::uint64_t>(st.st_size);
    }
    for (const std::string& path : doomed) (void)::unlink(path.c_str());
    const std::scoped_lock lock(mutex_);
    clock_ = newest;
}

std::optional<ExpandedCache::Entry> ExpandedCache::get(const CacheKey& key) {
    const Sha256Digest digest = key.digest();
    const std::string path = path_of(digest);
    const std::string name = path.substr(directory_.size() + 1);
    std::uint64_t generation = 0;
    {
        const std::scoped_lock lock(mutex_);
        const auto it = index_.find(name);
        if (it == index_.end()) return std::nullopt;
        generation = it->second.generation;
    }
    auto file = MappedSource::open(path);
    auto payload = file ? check((*file)->view(0, (*file)->size()), &digest) : std::unexpected(file.error());
    if (!payload) {
        // the entry read is gone or damaged: delete it, unless another thread wrote it again meanwhile
        const std::scoped_lock lock(mutex_);
        if (const auto it = index_.find(name); it != index_.end() && it->second.generation == generation) {
            total_ -= it->second.bytes;
            index_.erase(it);
            (void)::unlink(path.c_str());
        }
        return std::nullopt;
    }
    (void)::utimensat(AT_FDCWD, path.c_str(), nullptr, 0); // recency, for the next start
    {
        const std::scoped_lock lock(mutex_);
        if (auto it = index_.find(name); it != index_.end()) it->second.used = ++clock_;
    }
    return Entry{.file = std::move(*file), .payload = *payload};
}

Result<void> ExpandedCache::put(const CacheKey& key, std::span<const std::byte> payload) {
    const std::uint64_t size = cache_header_bytes + payload.size();
    if (size > budget_) return std::unexpected(error(Fault::too_big, Site::cache, size));
    const std::vector<std::byte> bytes = encode(key, payload);
    const std::string path = path_of(key.digest());
    const std::string name = path.substr(directory_.size() + 1);
    std::uint64_t serial = 0;
    {
        const std::scoped_lock lock(mutex_);
        serial = ++writes_;
    }
    const std::string temp = std::format("{}{}{}-{}", path, temp_marker, ::getpid(), serial);
    const int fd = ::open(temp.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (fd < 0) return std::unexpected(error(Fault::io, Site::cache, static_cast<std::uint64_t>(errno)));
    const bool written = write_all(fd, bytes);
    const int write_errno = errno;
    const bool closed = ::close(fd) == 0;
    if (!written || !closed) {
        const int e = !written ? write_errno : errno;
        (void)::unlink(temp.c_str());
        return std::unexpected(error(Fault::io, Site::cache, static_cast<std::uint64_t>(e)));
    }
    const std::scoped_lock lock(mutex_);
    if (::rename(temp.c_str(), path.c_str()) != 0) {
        const int e = errno;
        (void)::unlink(temp.c_str());
        return std::unexpected(error(Fault::io, Site::cache, static_cast<std::uint64_t>(e)));
    }
    if (const auto it = index_.find(name); it != index_.end()) total_ -= it->second.bytes;
    index_[name] = {.bytes = size, .used = ++clock_, .generation = serial};
    total_ += size;
    evict_locked(budget_, &name);
    return {};
}

void ExpandedCache::remove(const CacheKey& key) {
    const std::string path = path_of(key.digest());
    const std::string name = path.substr(directory_.size() + 1);
    const std::scoped_lock lock(mutex_);
    if (const auto it = index_.find(name); it != index_.end()) {
        total_ -= it->second.bytes;
        index_.erase(it);
    }
    (void)::unlink(path.c_str());
}

void ExpandedCache::evict_to(std::uint64_t budget) {
    const std::scoped_lock lock(mutex_);
    evict_locked(budget, nullptr);
}

void ExpandedCache::evict_locked(std::uint64_t budget, const std::string* keep) {
    if (total_ <= budget) return;
    std::vector<std::string> victims;
    std::vector<std::pair<std::uint64_t, const std::string*>> order;
    order.reserve(index_.size());
    for (const auto& [name, info] : index_) {
        if (keep == nullptr || name != *keep) order.emplace_back(info.used, &name);
    }
    std::ranges::sort(order);
    std::uint64_t left = total_;
    for (const auto& [used, name] : order) {
        if (left <= budget) break;
        left -= index_.at(*name).bytes;
        victims.push_back(*name);
    }
    for (const std::string& v : victims) {
        const auto it = index_.find(v);
        total_ -= it->second.bytes;
        index_.erase(it);
        (void)::unlink(std::format("{}/{}", directory_, v).c_str());
    }
}

std::uint64_t ExpandedCache::bytes() const {
    const std::scoped_lock lock(mutex_);
    return total_;
}

std::size_t ExpandedCache::entries() const {
    const std::scoped_lock lock(mutex_);
    return index_.size();
}

} // namespace atlas::tiles
