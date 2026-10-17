// The crash guard (gpu/Guard.hpp).
#include "gpu/Guard.hpp"

#include "base/log.hpp"

#include <array>
#include <cstdlib>
#include <fcntl.h>
#include <format>
#include <fstream>
#include <sstream>
#include <unistd.h>
#include <vector>

namespace atlas::gpu {

namespace {

constexpr std::array stage_names{"instance", "device", "cache", "pipelines", "frames"};

/// Writes `text` to `path` and syncs it, so a crash right after leaves it on disk.
void write_synced(const std::filesystem::path& path, std::string_view text) {
    std::error_code ec;
    std::filesystem::create_directories(path.parent_path(), ec);
    const int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC,
                          0600); // NOLINT(cppcoreguidelines-pro-type-vararg)
    if (fd < 0) {
        log::error("vk guard cannot write {}", path.string());
        return;
    }
    std::size_t done = 0;
    while (done < text.size()) {
        const ssize_t n = ::write(fd, text.data() + done, text.size() - done);
        if (n <= 0) break;
        done += static_cast<std::size_t>(n);
    }
    (void)::fsync(fd);
    ::close(fd);
}

[[nodiscard]] std::string read_text(const std::filesystem::path& path) {
    std::ifstream in(path);
    if (!in) return {};
    std::ostringstream out;
    out << in.rdbuf();
    return out.str();
}

/// The value after `key ` on its own line of `text`.
[[nodiscard]] std::string field(std::string_view text, std::string_view key) {
    std::size_t at = 0;
    while (at < text.size()) {
        const std::size_t end = std::min(text.find('\n', at), text.size());
        const std::string_view line = text.substr(at, end - at);
        if (line.size() > key.size() && line.starts_with(key) && line[key.size()] == ' ')
            return std::string(line.substr(key.size() + 1));
        at = end + 1;
    }
    return {};
}

} // namespace

std::string_view name_of(Stage stage) noexcept { return stage_names.at(static_cast<std::size_t>(stage)); }

std::optional<Stage> stage_named(std::string_view name) noexcept {
    for (std::size_t i = 0; i < stage_names.size(); ++i) {
        if (name == stage_names[i]) return static_cast<Stage>(i);
    }
    return std::nullopt;
}

Guard::Guard(std::filesystem::path dir, std::string system, std::string plant)
    : dir_(std::move(dir)), system_(std::move(system)), plant_(std::move(plant)) {
    if (system_.empty()) system_ = "unknown";
}

GuardVerdict Guard::check_start() {
    GuardVerdict v;
    if (dir_.empty()) return v;
    const std::string marker = read_text(dir_ / "guard");
    if (!marker.empty()) {
        const std::string stage = field(marker, "stage");
        const std::string driver = field(marker, "driver");
        const std::string version = field(marker, "version");
        const std::uint32_t attempt =
            static_cast<std::uint32_t>(std::strtoul(field(marker, "attempt").c_str(), nullptr, 10));
        std::error_code ec;
        std::filesystem::remove(dir_ / "guard", ec);
        v.line = std::format("vk guard {} {} {} attempt {}", stage.empty() ? "unknown" : stage,
                             driver.empty() ? "unknown" : driver, version.empty() ? system_ : version,
                             std::max(attempt, 1U));
        if (attempt >= 2) {
            // The retry crashed too: off for this driver and version (or this system build, before the driver was
            // known).
            const std::string key =
                driver.empty() || driver == "unknown" ? "system|" + system_ : driver + "|" + version;
            std::ofstream off(dir_ / "off", std::ios::app);
            off << key << "|" << stage << "\n";
            v.off = true;
            log::error("{}: Vulkan is off for this driver", v.line);
        } else {
            v.retry_without_cache = true;
            attempt_ = 2;
            log::warn("{}: retrying once without the pipeline cache", v.line);
        }
        return v;
    }
    const std::string off = read_text(dir_ / "off");
    const std::string key = "system|" + system_ + "|";
    if (const std::size_t at = off.find(key); at != std::string::npos) {
        const std::string stage = off.substr(at + key.size(), off.find('\n', at) - at - key.size());
        v.off = true;
        v.line = std::format("vk guard {} unknown {} attempt 2", stage, system_);
    }
    return v;
}

GuardVerdict Guard::check_driver(std::string_view driver, std::string_view version) {
    driver_ = std::string(driver);
    version_ = std::string(version);
    GuardVerdict v;
    if (dir_.empty()) return v;
    const std::string off = read_text(dir_ / "off");
    const std::string key = driver_ + "|" + version_ + "|";
    std::size_t at = 0;
    while ((at = off.find(key, at)) != std::string::npos) {
        if (at == 0 || off[at - 1] == '\n') {
            const std::size_t end = std::min(off.find('\n', at), off.size());
            const std::string stage = off.substr(at + key.size(), end - at - key.size());
            v.off = true;
            v.line = std::format("vk guard {} {} {} attempt 2", stage, driver_, version_);
            return v;
        }
        at += key.size();
    }
    return v;
}

void Guard::write_marker() const {
    if (dir_.empty() || !current_) return;
    write_synced(dir_ / "guard", std::format("stage {}\ndriver {}\nversion {}\nattempt {}\n", name_of(*current_),
                                             driver_, version_.empty() ? system_ : version_, attempt_));
}

void Guard::enter(Stage stage) {
    current_ = stage;
    write_marker();
    if (!plant_.empty() && plant_ == name_of(stage)) {
        log::error("vk guard planted crash at {} (attempt {})", name_of(stage), attempt_);
        std::abort();
    }
}

void Guard::leave() {
    if (!current_) return;
    current_.reset();
    if (dir_.empty()) return;
    std::error_code ec;
    std::filesystem::remove(dir_ / "guard", ec);
}

void Guard::keep_facts(std::string_view text) const {
    if (dir_.empty()) return;
    std::error_code ec;
    std::filesystem::create_directories(dir_, ec);
    std::ofstream out(dir_ / "facts", std::ios::trunc);
    out << text;
}

std::string Guard::kept_facts() const { return dir_.empty() ? std::string() : read_text(dir_ / "facts"); }

void Guard::clear() const {
    if (dir_.empty()) return;
    std::error_code ec;
    std::filesystem::remove(dir_ / "guard", ec);
    std::filesystem::remove(dir_ / "off", ec);
}

std::pair<std::string, std::string> guard_key(const Facts& facts) {
    std::string driver =
        facts.driver_properties ? vk::to_string(facts.driver_id) : std::string(vendor_name(facts.vendor_id));
    if (driver.empty() || driver == "unknown") driver = std::format("vendor-0x{:04x}", facts.vendor_id);
    return {driver, std::format("0x{:08x}", facts.driver_version)};
}

std::expected<Started, StartRefusal> start_guarded(const StartOptions& options) {
    Started s;
    s.guard = std::make_unique<Guard>(options.guard_dir, options.system, options.plant);
    Guard& guard = *s.guard;
    auto refuse = [&](std::string line, std::string reason) {
        return std::unexpected(
            StartRefusal{.line = std::move(line), .reason = std::move(reason), .kept_facts = guard.kept_facts()});
    };
    const GuardVerdict start = guard.check_start();
    if (start.off) return refuse(start.line, "the graphics driver crashed twice; Vulkan is off for it");
    s.guard_line = start.line;
    s.retried = start.retry_without_cache;
    if (start.retry_without_cache && !options.cache_file.empty()) PipelineCache::remove(options.cache_file);

    guard.enter(Stage::instance);
    auto instance = Instance::open({.validation = options.validation,
                                    .require_validation = options.require_validation,
                                    .loader = options.loader,
                                    .max_version = options.max_version});
    guard.leave();
    if (!instance) return refuse({}, instance.error().text());
    s.instance = std::move(*instance);

    const DeviceOptions device_options{.present = options.present, .msaa2_close = options.msaa2_close};
    auto picked = Device::pick(*s.instance, device_options);
    if (!picked) return refuse({}, picked.error().text());
    const auto [driver, version] = guard_key(picked->facts);
    const GuardVerdict driver_verdict = guard.check_driver(driver, version);
    if (driver_verdict.off)
        return refuse(driver_verdict.line, "the graphics driver crashed twice; Vulkan is off for it");

    guard.enter(Stage::device);
    auto device = Device::open(*s.instance, std::move(*picked), device_options);
    guard.leave();
    if (!device) return refuse({}, device.error().text());
    s.device = std::move(*device);
    std::string lines;
    for (const std::string& l : facts_lines(s.device->facts())) lines += l + "\n";
    guard.keep_facts(lines);

    guard.enter(Stage::cache);
    auto cache = PipelineCache::open(s.device->handle(), s.device->facts(), options.cache_file, options.spirv,
                                     !options.cache_file.empty());
    guard.leave();
    if (!cache) return refuse({}, cache.error().text());
    s.cache = std::make_unique<PipelineCache>(std::move(*cache));
    return s;
}

} // namespace atlas::gpu
