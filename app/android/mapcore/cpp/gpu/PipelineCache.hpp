// The pipeline cache on disk (plan-vector D5.6): `cacheDir/vk/pipelines.bin`, the driver's VkPipelineCache data behind
// a header of our own with the vendor, device, driver version, pipelineCacheUUID, the hash of the core's SPIR-V and a
// checksum of the data. Any mismatch or damage discards the file before the driver sees it (a corrupt cache can crash a
// driver, D5.10). Written back atomically after pipelines are made through it and when the app goes to the background,
// only where a pipeline was made through it since its last write (a write is a crash-guard stage of its own).
#pragma once

#include "gpu/Error.hpp"
#include "gpu/Facts.hpp"

#include <array>
#include <cstdint>
#include <filesystem>
#include <optional>
#include <span>
#include <string>
#include <string_view>

namespace atlas::gpu {

/// A hash of the core's SPIR-V, from the SHA-256 strings the build embeds with each module.
[[nodiscard]] std::uint64_t spirv_hash(std::span<const std::string_view> module_hashes) noexcept;

/// The cache file's header, little-endian, 96 bytes.
struct CacheHeader {
    static constexpr std::uint32_t magic_value = 0x43'4B'56'41; // "AVKC"
    static constexpr std::uint32_t version_value = 1;
    std::uint32_t magic = magic_value;
    std::uint32_t version = version_value;
    std::uint32_t vendor_id = 0;
    std::uint32_t device_id = 0;
    std::uint32_t driver_version = 0;
    std::uint32_t reserved = 0;
    std::array<std::uint8_t, VK_UUID_SIZE> uuid{};
    std::uint64_t spirv = 0;
    std::uint64_t size = 0;
    std::uint64_t checksum = 0;
    std::array<std::uint8_t, 32> pad{};
};
static_assert(sizeof(CacheHeader) == 96);

/// The pipeline cache, loaded from its file when the file is this driver's and intact.
class PipelineCache {
public:
    /// Opens the cache for `device`. With an empty path, or `use_file` false, it starts empty and never saves.
    [[nodiscard]] static Result<PipelineCache> open(const vk::raii::Device& device, const Facts& facts,
                                                    std::filesystem::path path, std::uint64_t spirv, bool use_file);

    [[nodiscard]] const vk::raii::PipelineCache& handle() const noexcept { return cache_; }
    /// Whether the driver got data from the file.
    [[nodiscard]] bool hit() const noexcept { return hit_; }
    /// Why the file was used or not: "hit", "miss: no file", "miss: another driver", "miss: damaged", "off".
    [[nodiscard]] const std::string& status() const noexcept { return status_; }
    /// The file's size when it was read.
    [[nodiscard]] std::size_t loaded_bytes() const noexcept { return loaded_bytes_; }
    /// A pipeline is about to be made through it (the driver may add to its data): the next save writes it back.
    void note_made() const noexcept { changed_ = true; }
    /// Whether a pipeline was made through it since it was opened or last written back.
    [[nodiscard]] bool changed() const noexcept { return changed_; }
    /// Writes the cache's data behind its header, through a temporary file renamed into place and synced, when a
    /// pipeline was made through it since it was opened or last written back (changed); else nothing (0 bytes).
    [[nodiscard]] Result<std::size_t> save() const;
    /// Deletes the file (the crash guard's retry without the cache).
    static void remove(const std::filesystem::path& path) noexcept;

private:
    PipelineCache() = default;
    vk::raii::PipelineCache cache_{nullptr};
    std::filesystem::path path_;
    CacheHeader header_;
    bool hit_ = false;
    std::size_t loaded_bytes_ = 0;
    std::string status_ = "off";
    /// A pipeline was made through it since it was opened or last written back (through a const cache, as the driver's
    /// data behind the handle changes).
    mutable bool changed_ = false;
};

/// The header the cache of `facts`' device carries.
[[nodiscard]] CacheHeader header_for(const Facts& facts, std::uint64_t spirv) noexcept;

/// FNV-1a over bytes (the cache's checksum; not cryptographic, it catches truncation and damage).
[[nodiscard]] std::uint64_t fnv1a(std::span<const std::byte> bytes) noexcept;

} // namespace atlas::gpu
