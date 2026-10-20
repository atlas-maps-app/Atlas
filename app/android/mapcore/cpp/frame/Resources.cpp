// The passes' resources from files and from the APK's assets (frame/Pass.hpp's files_under and assets_of).
#include "frame/Pass.hpp"

#include <fstream>
#include <iterator>

#if defined(__ANDROID__)
#include <android/asset_manager.h>
#endif

namespace atlas::frame {

namespace {

/// Whether `path` names something inside the folder read: relative, and never through its parent.
[[nodiscard]] bool inside(std::string_view path) noexcept {
    return !path.empty() && path.front() != '/' && path.find("..") == std::string_view::npos;
}

} // namespace

Resources files_under(std::string dir) {
    Resources r;
    r.origin = "files under " + dir;
    r.read = [dir = std::move(dir)](std::string_view path) -> std::optional<std::vector<std::byte>> {
        if (!inside(path)) return std::nullopt;
        std::ifstream in(dir + "/" + std::string(path), std::ios::binary);
        if (!in) return std::nullopt;
        const std::vector<char> chars{std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
        std::vector<std::byte> bytes(chars.size());
        for (std::size_t i = 0; i < chars.size(); ++i) bytes[i] = static_cast<std::byte>(chars[i]);
        return bytes;
    };
    return r;
}

#if defined(__ANDROID__)
Resources assets_of(AAssetManager* assets) {
    Resources r;
    if (assets == nullptr) return r;
    r.origin = "the APK's assets";
    r.read = [assets](std::string_view path) -> std::optional<std::vector<std::byte>> {
        if (!inside(path)) return std::nullopt;
        const std::string name(path);
        AAsset* asset = AAssetManager_open(assets, name.c_str(), AASSET_MODE_BUFFER);
        if (asset == nullptr) return std::nullopt;
        const auto length = AAsset_getLength64(asset);
        std::vector<std::byte> bytes(length > 0 ? static_cast<std::size_t>(length) : 0);
        std::size_t done = 0;
        while (done < bytes.size()) {
            const int got = AAsset_read(asset, bytes.data() + done, bytes.size() - done);
            if (got <= 0) break;
            done += static_cast<std::size_t>(got);
        }
        AAsset_close(asset);
        if (done != bytes.size()) return std::nullopt;
        return bytes;
    };
    return r;
}
#endif

} // namespace atlas::frame
