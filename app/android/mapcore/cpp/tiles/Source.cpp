#include "tiles/Source.hpp"

#include <algorithm>
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace atlas::tiles {
namespace {

[[nodiscard]] std::unexpected<TileError> io_error() noexcept {
    return std::unexpected(error(Fault::io, Site::file, static_cast<std::uint64_t>(errno)));
}

[[nodiscard]] bool inside(std::uint64_t size, std::uint64_t offset, std::uint64_t length) noexcept {
    return offset <= size && length <= size - offset;
}

/// Closes a descriptor when it goes.
class Descriptor {
public:
    explicit Descriptor(int fd) noexcept : fd_(fd) {}
    Descriptor(const Descriptor&) = delete;
    Descriptor& operator=(const Descriptor&) = delete;
    Descriptor(Descriptor&&) = delete;
    Descriptor& operator=(Descriptor&&) = delete;
    ~Descriptor() {
        if (fd_ >= 0) (void)::close(fd_);
    }
    [[nodiscard]] int get() const noexcept { return fd_; }

private:
    int fd_;
};

} // namespace

std::span<const std::byte> ByteSource::view(std::uint64_t /*offset*/, std::uint64_t /*length*/) const noexcept {
    return {};
}

Result<std::span<const std::byte>> ByteSource::bytes(std::uint64_t offset, std::uint64_t length,
                                                     std::vector<std::byte>& scratch) const {
    if (!inside(size(), offset, length)) return std::unexpected(error(Fault::truncated, Site::file, offset));
    if (length == 0) return std::span<const std::byte>{};
    if (const std::span<const std::byte> v = view(offset, length); !v.empty()) return v;
    scratch.resize(static_cast<std::size_t>(length));
    if (auto done = read(offset, scratch); !done) return std::unexpected(done.error());
    return std::span<const std::byte>(scratch);
}

std::span<const std::byte> MemorySource::view(std::uint64_t offset, std::uint64_t length) const noexcept {
    if (!inside(bytes_.size(), offset, length)) return {};
    return bytes_.subspan(static_cast<std::size_t>(offset), static_cast<std::size_t>(length));
}

Result<void> MemorySource::read(std::uint64_t offset, std::span<std::byte> out) const {
    if (!inside(bytes_.size(), offset, out.size())) return std::unexpected(error(Fault::truncated, Site::file, offset));
    std::ranges::copy(bytes_.subspan(static_cast<std::size_t>(offset), out.size()), out.begin());
    return {};
}

Result<std::unique_ptr<MappedSource>> MappedSource::open(const std::string& path) {
    const Descriptor fd(::open(path.c_str(), O_RDONLY | O_CLOEXEC));
    if (fd.get() < 0) return io_error();
    struct stat st{};
    if (::fstat(fd.get(), &st) != 0) return io_error();
    return open(fd.get(), 0, static_cast<std::uint64_t>(st.st_size));
}

Result<std::unique_ptr<MappedSource>> MappedSource::open(int fd, std::uint64_t offset, std::uint64_t length) {
    struct stat st{};
    if (::fstat(fd, &st) != 0) return io_error();
    if (S_ISREG(st.st_mode) && !inside(static_cast<std::uint64_t>(st.st_size), offset, length)) {
        return std::unexpected(error(Fault::truncated, Site::file, offset));
    }
    if (length == 0) return std::unique_ptr<MappedSource>(new MappedSource(nullptr, 0, {}));
    const long page = ::sysconf(_SC_PAGESIZE);
    const std::uint64_t align = page > 0 ? static_cast<std::uint64_t>(page) : 4096U;
    const std::uint64_t start = offset - (offset % align); // mmap's offset must be a page's
    const std::uint64_t lead = offset - start;
    const auto map_length = static_cast<std::size_t>(lead + length);
    void* map = ::mmap(nullptr, map_length, PROT_READ, MAP_PRIVATE, fd, static_cast<off_t>(start));
    if (map == MAP_FAILED) return io_error();
    const std::span<const std::byte> bytes(static_cast<const std::byte*>(map) + lead, static_cast<std::size_t>(length));
    return std::unique_ptr<MappedSource>(new MappedSource(map, map_length, bytes));
}

MappedSource::~MappedSource() {
    if (map_ != nullptr) (void)::munmap(map_, map_length_);
}

std::span<const std::byte> MappedSource::view(std::uint64_t offset, std::uint64_t length) const noexcept {
    if (!inside(bytes_.size(), offset, length)) return {};
    return bytes_.subspan(static_cast<std::size_t>(offset), static_cast<std::size_t>(length));
}

Result<void> MappedSource::read(std::uint64_t offset, std::span<std::byte> out) const {
    if (!inside(bytes_.size(), offset, out.size())) return std::unexpected(error(Fault::truncated, Site::file, offset));
    std::ranges::copy(bytes_.subspan(static_cast<std::size_t>(offset), out.size()), out.begin());
    return {};
}

Result<void> RangeSource::read(std::uint64_t offset, std::span<std::byte> out) const {
    if (!inside(size_, offset, out.size())) return std::unexpected(error(Fault::truncated, Site::file, offset));
    if (out.empty()) return {};
    return fetch_(offset, out);
}

ConcatSource::ConcatSource(std::vector<SourceSlice> slices) : slices_(std::move(slices)) {
    starts_.reserve(slices_.size());
    for (const SourceSlice& s : slices_) {
        starts_.push_back(size_);
        size_ += s.length;
    }
}

std::size_t ConcatSource::slice_at(std::uint64_t offset) const noexcept {
    const auto it = std::ranges::upper_bound(starts_, offset);
    return static_cast<std::size_t>(it - starts_.begin()) - 1;
}

std::span<const std::byte> ConcatSource::view(std::uint64_t offset, std::uint64_t length) const noexcept {
    if (!inside(size_, offset, length) || length == 0) return {};
    const std::size_t i = slice_at(offset);
    const std::uint64_t within = offset - starts_[i];
    if (within + length > slices_[i].length) return {}; // across two slices: read() copies it
    return slices_[i].source->view(slices_[i].offset + within, length);
}

Result<void> ConcatSource::read(std::uint64_t offset, std::span<std::byte> out) const {
    if (!inside(size_, offset, out.size())) return std::unexpected(error(Fault::truncated, Site::file, offset));
    std::size_t done = 0;
    while (done < out.size()) {
        const std::uint64_t at = offset + done;
        const std::size_t i = slice_at(at);
        const std::uint64_t within = at - starts_[i];
        const auto n = static_cast<std::size_t>(std::min<std::uint64_t>(slices_[i].length - within, out.size() - done));
        if (auto r = slices_[i].source->read(slices_[i].offset + within, out.subspan(done, n)); !r) return r;
        done += n;
    }
    return {};
}

} // namespace atlas::tiles
