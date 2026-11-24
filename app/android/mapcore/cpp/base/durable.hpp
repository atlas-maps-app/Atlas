// A file put in place durably: written beside its place, synced, renamed over it, and its folder synced, so that after
// a power cut or a kernel panic within the writeback window the next start finds the old file or the new one, whole.
// An app's crash or kill needs none of it: what the kernel holds outlives the process. Used where losing a file costs
// more than its sync: the world cache's pins (tiles/ExpandedCache.cpp; without them the next start evicts a kept
// country's world down to the budget) and the pipeline cache (gpu/PipelineCache.cpp). Header-only, so stage P (whose
// library links no other part of base) and the core share it.
#pragma once

#include <cerrno>
#include <cstddef>
#include <fcntl.h>
#include <string>
#include <string_view>
#include <unistd.h>

#if defined(ATLAS_TESTS)
#include <mutex>
#include <vector>
#endif

namespace atlas::durable {

#if defined(ATLAS_TESTS)
/// The steps replace() took, in order, for the tests: `sync <temp>`, `rename <path>`, `sync <folder>`. Only where
/// ATLAS_TESTS is defined.
struct Steps {
    std::mutex mutex;
    std::vector<std::string> taken;
};

[[nodiscard]] inline Steps& steps() {
    static Steps s;
    return s;
}

inline void note(std::string_view step, std::string_view path) {
    Steps& s = steps();
    const std::scoped_lock lock(s.mutex);
    s.taken.push_back(std::string(step) + " " + std::string(path));
}
#else
inline void note(std::string_view /*step*/, std::string_view /*path*/) noexcept {}
#endif

/// The folder that holds `path` ("." for a bare name).
[[nodiscard]] inline std::string folder_of(const std::string& path) {
    const std::size_t slash = path.find_last_of('/');
    if (slash == std::string::npos) return ".";
    return slash == 0 ? std::string("/") : path.substr(0, slash);
}

/// Puts the file written through `fd` (open for writing on `temp`, beside `path`) in place: syncs it, closes it,
/// renames it over `path`, then syncs the folder, which holds the rename. 0, or the errno of the step that failed,
/// `fd` then closed and `temp` removed. The folder's sync is the system's best: where the folder cannot be synced the
/// new file is in place all the same, and only a power cut could bring the old one back.
[[nodiscard]] inline int replace(int fd, const std::string& temp, const std::string& path) {
    const auto failed = [&](bool open) {
        const int e = errno;
        if (open) (void)::close(fd);
        (void)::unlink(temp.c_str());
        return e;
    };
    note("sync", temp);
    if (::fsync(fd) != 0) return failed(true);
    if (::close(fd) != 0) return failed(false);
    note("rename", path);
    if (::rename(temp.c_str(), path.c_str()) != 0) return failed(false);
    const std::string folder = folder_of(path);
    note("sync", folder);
    const int dir =
        ::open(folder.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC); // NOLINT(cppcoreguidelines-pro-type-vararg)
    if (dir >= 0) {
        (void)::fsync(dir);
        (void)::close(dir);
    }
    return 0;
}

} // namespace atlas::durable
