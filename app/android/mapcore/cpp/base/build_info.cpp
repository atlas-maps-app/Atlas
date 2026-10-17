#include "base/build_info.hpp"

#include "base/std_check.hpp"
#include "gen/spirv/modules.hpp"

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <format>
#include <span>
#include <string>
#include <version>

#if defined(__APPLE__)
#include <dlfcn.h>
#include <mach-o/loader.h>
#elif defined(__linux__) || defined(__ANDROID__)
#include <elf.h>
#include <link.h>
#endif

// Set by CMake (cmake/Toolchains.cmake); the defaults keep the file compiling on its own.
#ifndef ATLAS_VERSION
#define ATLAS_VERSION "0"
#endif
#ifndef ATLAS_BUILD_KIND
#define ATLAS_BUILD_KIND "unknown"
#endif
#ifndef ATLAS_SLANG_VERSION
#define ATLAS_SLANG_VERSION "none"
#endif
#ifndef ATLAS_CORE_FLAGS
#define ATLAS_CORE_FLAGS "flags unknown"
#endif

#define ATLAS_STRINGIFY_INNER(x) #x
#define ATLAS_STRINGIFY(x) ATLAS_STRINGIFY_INNER(x)

#if defined(__ANDROID__)
#define ATLAS_TARGET_OS "android-" ATLAS_STRINGIFY(__ANDROID_API__)
#elif defined(__APPLE__)
#define ATLAS_TARGET_OS "macos"
#elif defined(__linux__)
#define ATLAS_TARGET_OS "linux"
#else
#define ATLAS_TARGET_OS "other"
#endif

#if defined(__aarch64__)
#define ATLAS_TARGET_ARCH "arm64"
#elif defined(__x86_64__)
#define ATLAS_TARGET_ARCH "x86_64"
#else
#define ATLAS_TARGET_ARCH "other"
#endif

#if defined(__has_feature)
#if __has_feature(address_sanitizer)
#define ATLAS_SANITIZER_ADDRESS " asan"
#endif
#if __has_feature(hwaddress_sanitizer)
#define ATLAS_SANITIZER_HWADDRESS " hwasan"
#endif
#if __has_feature(thread_sanitizer)
#define ATLAS_SANITIZER_THREAD " tsan"
#endif
#endif
#ifndef ATLAS_SANITIZER_ADDRESS
#define ATLAS_SANITIZER_ADDRESS ""
#endif
#ifndef ATLAS_SANITIZER_HWADDRESS
#define ATLAS_SANITIZER_HWADDRESS ""
#endif
#ifndef ATLAS_SANITIZER_THREAD
#define ATLAS_SANITIZER_THREAD ""
#endif

namespace {

constexpr const char* line =
    "atlasmap " ATLAS_VERSION " (" ATLAS_BUILD_KIND "); " __VERSION__
    "; libc++ " ATLAS_STRINGIFY(_LIBCPP_VERSION) "; C++ " ATLAS_STRINGIFY(
        __cplusplus) "; " ATLAS_TARGET_OS " " ATLAS_TARGET_ARCH
                     "; " ATLAS_CORE_FLAGS ATLAS_SANITIZER_ADDRESS ATLAS_SANITIZER_HWADDRESS ATLAS_SANITIZER_THREAD
                     "; slang " ATLAS_SLANG_VERSION;

} // namespace

namespace {

/// An address inside the core's own binary: the image whose identity is read.
[[nodiscard]] std::uintptr_t own_address() noexcept {
    return reinterpret_cast<std::uintptr_t>(&atlas_build_info); // NOLINT(cppcoreguidelines-pro-type-reinterpret-cast)
}

[[nodiscard]] std::string hex_of(std::span<const unsigned char> bytes) {
    std::string out;
    out.reserve(bytes.size() * 2);
    for (const unsigned char b : bytes) out += std::format("{:02x}", b);
    return out;
}

#if defined(__APPLE__)

/// LC_UUID of the Mach-O image that holds the core.
[[nodiscard]] std::string read_linker_id() {
    Dl_info info{};
    // NOLINTNEXTLINE(performance-no-int-to-ptr): the address of one of the core's own functions
    if (::dladdr(reinterpret_cast<const void*>(own_address()), &info) == 0 || info.dli_fbase == nullptr) return {};
    const auto* base = static_cast<const unsigned char*>(info.dli_fbase);
    mach_header_64 header{};
    std::memcpy(&header, base, sizeof header);
    if (header.magic != MH_MAGIC_64) return {};
    std::size_t at = sizeof header;
    for (std::uint32_t i = 0; i < header.ncmds; ++i) {
        load_command command{};
        std::memcpy(&command, base + at, sizeof command);
        if (command.cmd == LC_UUID && command.cmdsize >= sizeof(uuid_command)) {
            uuid_command uuid{};
            std::memcpy(&uuid, base + at, sizeof uuid);
            return hex_of(uuid.uuid);
        }
        if (command.cmdsize == 0) break;
        at += command.cmdsize;
    }
    return {};
}

#elif defined(__linux__) || defined(__ANDROID__)

/// The NT_GNU_BUILD_ID note of the ELF object that holds the core.
[[nodiscard]] std::string read_linker_id() {
    struct Search {
        std::uintptr_t address;
        std::string id;
    } search{.address = own_address(), .id = {}};
    ::dl_iterate_phdr(
        [](dl_phdr_info* object, std::size_t /*size*/, void* data) -> int {
            Search& s = *static_cast<Search*>(data);
            const std::span<const ElfW(Phdr)> headers(object->dlpi_phdr, object->dlpi_phnum);
            bool holds = false;
            for (const ElfW(Phdr) & h : headers) {
                const std::uintptr_t start = object->dlpi_addr + h.p_vaddr;
                holds = holds || (h.p_type == PT_LOAD && s.address >= start && s.address - start < h.p_memsz);
            }
            if (!holds) return 0;
            for (const ElfW(Phdr) & h : headers) {
                if (h.p_type != PT_NOTE) continue;
                // NOLINTNEXTLINE(performance-no-int-to-ptr): the note segment as the loader mapped it
                const auto* notes = reinterpret_cast<const unsigned char*>(object->dlpi_addr + h.p_vaddr);
                s.id = atlas::gnu_build_id(std::span(notes, h.p_memsz));
                if (!s.id.empty()) return 1;
            }
            return 1; // the core's object, without a build ID
        },
        &search);
    return search.id;
}

#else

[[nodiscard]] std::string read_linker_id() { return {}; }

#endif

/// FNV-1a over bytes, continuing from `h`.
[[nodiscard]] std::uint64_t fnv1a(std::uint64_t h, std::string_view bytes) noexcept {
    for (const char c : bytes) {
        h ^= static_cast<std::uint8_t>(c);
        h *= 0x100000001b3ULL;
    }
    // A separator, so that two fields never run into each other.
    h ^= 0xFFU;
    h *= 0x100000001b3ULL;
    return h;
}

} // namespace

namespace atlas {

std::string_view build_info() noexcept { return line; }

std::string gnu_build_id(std::span<const unsigned char> notes) {
    constexpr std::uint32_t nt_gnu_build_id = 3;
    constexpr auto aligned = [](std::uint32_t n) { return (std::size_t{n} + 3) & ~std::size_t{3}; };
    std::size_t at = 0;
    while (notes.size() - at >= 3 * sizeof(std::uint32_t)) {
        std::array<std::uint32_t, 3> head{}; // the name's size, the description's size, the type
        std::memcpy(head.data(), notes.data() + at, sizeof head);
        const std::size_t name_at = at + sizeof head;
        const std::size_t name_size = aligned(head[0]);
        const std::size_t desc_size = aligned(head[1]);
        if (name_size > notes.size() - name_at || desc_size > notes.size() - name_at - name_size) break;
        const std::size_t desc_at = name_at + name_size;
        if (head[2] == nt_gnu_build_id && head[0] == 4 && std::memcmp(notes.data() + name_at, "GNU", 4) == 0)
            return hex_of(notes.subspan(desc_at, head[1]));
        at = desc_at + desc_size;
    }
    return {};
}

std::string_view linker_id() noexcept {
    static const std::string id = read_linker_id();
    return id;
}

std::string_view build_id() noexcept {
    static const std::string id = [] {
        std::uint64_t h = fnv1a(0xcbf29ce484222325ULL, line);
        for (const std::string_view module : spirv::hashes) h = fnv1a(h, module);
        h = fnv1a(h, linker_id());
        return std::format("{:016x}", h);
    }();
    return id;
}

} // namespace atlas

extern "C" const char* atlas_build_info() noexcept { return line; }
