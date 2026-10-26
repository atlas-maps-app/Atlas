// The pipeline cache on disk (gpu/PipelineCache.hpp).
#include "gpu/PipelineCache.hpp"

#include "base/log.hpp"

#include <algorithm>
#include <cstring>
#include <fcntl.h>
#include <format>
#include <unistd.h>
#include <vector>

namespace atlas::gpu {

namespace {

/// The whole file, or empty when it cannot be read.
[[nodiscard]] std::vector<std::byte> read_file(const std::filesystem::path& path) {
    std::vector<std::byte> out;
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC); // NOLINT(cppcoreguidelines-pro-type-vararg)
    if (fd < 0) return out;
    std::array<std::byte, 1 << 16> buffer{};
    for (;;) {
        const ssize_t n = ::read(fd, buffer.data(), buffer.size());
        if (n <= 0) break;
        out.insert(out.end(), buffer.begin(), buffer.begin() + n);
    }
    ::close(fd);
    return out;
}

/// Writes `bytes` to `path` through `path.tmp`, synced, then renamed: a crash leaves the old file or the new one.
[[nodiscard]] bool write_file(const std::filesystem::path& path, std::span<const std::byte> head,
                              std::span<const std::byte> body) {
    std::error_code ec;
    std::filesystem::create_directories(path.parent_path(), ec);
    const std::filesystem::path tmp = path.string() + ".tmp";
    const int fd = ::open(tmp.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC,
                          0600); // NOLINT(cppcoreguidelines-pro-type-vararg)
    if (fd < 0) return false;
    bool ok = true;
    for (const std::span<const std::byte> part : {head, body}) {
        std::size_t done = 0;
        while (ok && done < part.size()) {
            const ssize_t n = ::write(fd, part.data() + done, part.size() - done);
            if (n <= 0)
                ok = false;
            else
                done += static_cast<std::size_t>(n);
        }
    }
    ok = ok && ::fsync(fd) == 0;
    ok = ::close(fd) == 0 && ok;
    if (!ok || ::rename(tmp.c_str(), path.c_str()) != 0) {
        (void)::unlink(tmp.c_str());
        return false;
    }
    return true;
}

} // namespace

std::uint64_t fnv1a(std::span<const std::byte> bytes) noexcept {
    std::uint64_t h = 0xcbf29ce484222325ULL;
    for (const std::byte b : bytes) {
        h ^= static_cast<std::uint64_t>(b);
        h *= 0x100000001b3ULL;
    }
    return h;
}

std::uint64_t spirv_hash(std::span<const std::string_view> module_hashes) noexcept {
    std::uint64_t h = 0xcbf29ce484222325ULL;
    for (const std::string_view s : module_hashes) {
        for (const char c : s) {
            h ^= static_cast<std::uint8_t>(c);
            h *= 0x100000001b3ULL;
        }
        h ^= 0xFFU;
        h *= 0x100000001b3ULL;
    }
    return h;
}

CacheHeader header_for(const Facts& facts, std::uint64_t spirv) noexcept {
    CacheHeader h;
    h.vendor_id = facts.vendor_id;
    h.device_id = facts.device_id;
    h.driver_version = facts.driver_version;
    h.uuid = facts.pipeline_cache_uuid;
    h.spirv = spirv;
    return h;
}

void PipelineCache::remove(const std::filesystem::path& path) noexcept {
    std::error_code ec;
    std::filesystem::remove(path, ec);
}

Result<PipelineCache> PipelineCache::open(const vk::raii::Device& device, const Facts& facts,
                                          std::filesystem::path path, std::uint64_t spirv, bool use_file) {
    PipelineCache cache;
    cache.path_ = use_file ? std::move(path) : std::filesystem::path();
    cache.header_ = header_for(facts, spirv);
    std::vector<std::byte> file;
    if (!cache.path_.empty()) {
        file = read_file(cache.path_);
        cache.status_ = file.empty() ? "miss: no file" : "miss: damaged";
    }
    std::span<const std::byte> data;
    if (file.size() >= sizeof(CacheHeader)) {
        CacheHeader stored;
        std::memcpy(&stored, file.data(), sizeof stored);
        const std::span<const std::byte> body = std::span(file).subspan(sizeof(CacheHeader));
        const bool same_driver =
            stored.vendor_id == cache.header_.vendor_id && stored.device_id == cache.header_.device_id &&
            stored.driver_version == cache.header_.driver_version && stored.uuid == cache.header_.uuid;
        if (stored.magic != CacheHeader::magic_value || stored.version != CacheHeader::version_value) {
            cache.status_ = "miss: not a cache of this core";
        } else if (!same_driver) {
            cache.status_ = "miss: another driver";
        } else if (stored.spirv != cache.header_.spirv) {
            cache.status_ = "miss: other shaders";
        } else if (stored.size != body.size() || stored.checksum != fnv1a(body)) {
            cache.status_ = "miss: damaged";
        } else {
            data = body;
            cache.status_ = "hit";
            cache.loaded_bytes_ = file.size();
        }
    }
    ATLAS_VK_LET(handle, device.createPipelineCache({.initialDataSize = data.size(), .pInitialData = data.data()}),
                 "the pipeline cache");
    cache.cache_ = std::move(handle);
    cache.hit_ = !data.empty();
    if (cache.path_.empty()) cache.status_ = "off";
    return cache;
}

Result<std::size_t> PipelineCache::save() const {
    if (path_.empty() || !changed()) return std::size_t{0};
    ATLAS_VK_LET(data, cache_.getData(), "the pipeline cache's data");
    CacheHeader h = header_;
    const auto body = std::as_bytes(std::span(data));
    h.size = body.size();
    h.checksum = fnv1a(body);
    if (!write_file(path_, std::as_bytes(std::span(&h, 1)), body))
        return fail(std::format("cannot write {}", path_.string()));
    std::atomic_ref(changed_).store(false, std::memory_order_relaxed);
    return sizeof h + body.size();
}

} // namespace atlas::gpu
