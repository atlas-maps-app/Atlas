// A file put in place durably (base/durable.hpp): synced before its rename and its folder after it, the new bytes in
// place and no temporary file left; a step that fails leaves what was in place and no temporary file.
#include "base/durable.hpp"

#include <doctest/doctest.h>

#include <fcntl.h>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <mutex>
#include <string>
#include <string_view>
#include <system_error>
#include <unistd.h>
#include <vector>

namespace {

namespace fs = std::filesystem;
namespace durable = atlas::durable;

[[nodiscard]] std::string text_of(const fs::path& path) {
    std::ifstream in(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

/// `text` written to a new file `temp`, its descriptor left open as the callers leave it for replace(); -1 where not.
[[nodiscard]] int written(const std::string& temp, std::string_view text) {
    const int fd = ::open(temp.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) return -1;
    if (::write(fd, text.data(), text.size()) != static_cast<ssize_t>(text.size())) {
        (void)::close(fd);
        return -1;
    }
    return fd;
}

/// The steps replace() took since the last call.
[[nodiscard]] std::vector<std::string> taken() {
    durable::Steps& s = durable::steps();
    const std::scoped_lock lock(s.mutex);
    std::vector<std::string> out = std::move(s.taken);
    s.taken.clear();
    return out;
}

/// An empty folder of the tests' scratch, removed again when it goes.
class Scratch {
public:
    explicit Scratch(std::string_view name) : path_(fs::path(ATLAS_TEST_SCRATCH) / name) {
        std::error_code ec;
        fs::remove_all(path_, ec);
        fs::create_directories(path_, ec);
    }
    Scratch(const Scratch&) = delete;
    Scratch& operator=(const Scratch&) = delete;
    Scratch(Scratch&&) = delete;
    Scratch& operator=(Scratch&&) = delete;
    ~Scratch() {
        std::error_code ec;
        fs::remove_all(path_, ec);
    }
    [[nodiscard]] const fs::path& path() const noexcept { return path_; }

private:
    fs::path path_;
};

} // namespace

TEST_CASE("durable: a file put in place is synced before its rename and its folder after it, and holds the new bytes") {
    const Scratch dir("durable-replace");
    const std::string path = (dir.path() / "file").string();
    std::ofstream(path, std::ios::binary) << "old";
    const std::string temp = path + ".tmp";
    const int fd = written(temp, "new");
    REQUIRE(fd >= 0);
    (void)taken();
    CHECK(durable::replace(fd, temp, path) == 0);
    CHECK(taken() == std::vector<std::string>{"sync " + temp, "rename " + path, "sync " + dir.path().string()});
    CHECK(text_of(path) == "new");
    CHECK_FALSE(fs::exists(temp));
}

TEST_CASE("durable: a rename that fails leaves what was in place and no temporary file") {
    // the place a folder that holds a file: a file cannot be renamed over it
    const Scratch dir("durable-refused");
    const fs::path place = dir.path() / "place";
    fs::create_directories(place / "inside");
    const std::string temp = place.string() + ".tmp";
    const int fd = written(temp, "new");
    REQUIRE(fd >= 0);
    CHECK(durable::replace(fd, temp, place.string()) != 0);
    CHECK(fs::is_directory(place / "inside"));
    CHECK_FALSE(fs::exists(temp));
    (void)taken();
}

TEST_CASE("durable: the folder that holds a path") {
    CHECK(durable::folder_of("a/b/c") == "a/b");
    CHECK(durable::folder_of("/a/c") == "/a");
    CHECK(durable::folder_of("/c") == "/");
    CHECK(durable::folder_of("c") == ".");
}
