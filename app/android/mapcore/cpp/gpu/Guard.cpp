// The crash guard (gpu/Guard.hpp).
#include "gpu/Guard.hpp"

#include "base/log.hpp"

#include <algorithm>
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

constexpr std::array stage_names{"instance", "device", "cache", "pipelines", "frames", "colour"};

/// The colour stage's name, as markers and off lines spell it.
constexpr std::string_view colour = "colour";

/// Writes `text` to `path` whole: into `path.tmp`, synced, then renamed over `path`, so that a crash at any moment
/// leaves the old text or the new one on disk, never a part of either.
void write_synced(const std::filesystem::path& path, std::string_view text) {
    std::error_code ec;
    std::filesystem::create_directories(path.parent_path(), ec);
    const std::filesystem::path tmp = path.string() + ".tmp";
    const int fd = ::open(tmp.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC,
                          0600); // NOLINT(cppcoreguidelines-pro-type-vararg)
    if (fd < 0) {
        log::error("vk guard cannot write {}", tmp.string());
        return;
    }
    std::size_t done = 0;
    while (done < text.size()) {
        const ssize_t n = ::write(fd, text.data() + done, text.size() - done);
        if (n <= 0) break;
        done += static_cast<std::size_t>(n);
    }
    const bool synced = done == text.size() && ::fsync(fd) == 0;
    ::close(fd);
    if (!synced || ::rename(tmp.c_str(), path.c_str()) != 0) {
        log::error("vk guard cannot write {}", path.string());
        (void)::unlink(tmp.c_str());
    }
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

/// A value for a line of the off file or the marker: '|' would split its fields, a line break its lines.
[[nodiscard]] std::string clean(std::string_view value) {
    std::string out(value);
    std::ranges::replace_if(out, [](char c) { return c == '|' || c == '\n' || c == '\r'; }, ' ');
    return out;
}

/// The fields of one line of the off file, split at '|'.
[[nodiscard]] std::vector<std::string_view> fields_of(std::string_view line) {
    std::vector<std::string_view> out;
    std::size_t at = 0;
    for (;;) {
        const std::size_t bar = line.find('|', at);
        out.push_back(line.substr(at, bar == std::string_view::npos ? std::string_view::npos : bar - at));
        if (bar == std::string_view::npos) return out;
        at = bar + 1;
    }
}

/// The `vk guard` line (plan-vector Appendix C), with what the driver was making ("pipeline <…>" or "module <…>")
/// and the core's build.
[[nodiscard]] std::string guard_line(std::string_view stage, std::string_view driver, std::string_view version,
                                     std::uint32_t attempt, std::string_view what, std::string_view build) {
    std::string line = std::format("vk guard {} {} {} attempt {}", stage, driver, version, attempt);
    if (!what.empty()) line += std::format(" {}", what);
    line += std::format(" build {}", build);
    return line;
}

} // namespace

std::string_view name_of(Stage stage) noexcept { return stage_names.at(static_cast<std::size_t>(stage)); }

std::optional<Stage> stage_named(std::string_view name) noexcept {
    for (std::size_t i = 0; i < stage_names.size(); ++i) {
        if (name == stage_names[i]) return static_cast<Stage>(i);
    }
    return std::nullopt;
}

Guard::Guard(std::filesystem::path dir, std::string system, std::string build, std::string plant)
    : dir_(std::move(dir)), system_(std::move(system)), build_(std::move(build)), plant_(std::move(plant)) {
    if (system_.empty()) system_ = "unknown";
    // Never empty: an off line from before build IDs has an empty build, which must match no build.
    if (build_.empty()) build_ = "unknown";
    system_ = clean(system_);
    build_ = clean(build_);
}

GuardVerdict Guard::check_start() {
    GuardVerdict v;
    if (dir_.empty()) return v;
    const std::string marker = read_text(dir_ / "guard");
    if (!marker.empty()) {
        std::string stage = field(marker, "stage");
        std::string driver = field(marker, "driver");
        std::string version = field(marker, "version");
        const std::string pipeline = field(marker, "pipeline");
        const std::string module = field(marker, "module");
        const std::string what = !pipeline.empty() ? "pipeline " + pipeline
                                 : !module.empty() ? "module " + module
                                                   : std::string();
        const std::string build = field(marker, "build"); // empty: a marker from before build IDs
        if (stage.empty()) stage = "unknown";
        if (driver.empty()) driver = "unknown";
        if (version.empty()) version = system_;
        const auto attempt =
            std::max(static_cast<std::uint32_t>(std::strtoul(field(marker, "attempt").c_str(), nullptr, 10)), 1U);
        std::error_code ec;
        std::filesystem::remove(dir_ / "guard", ec);
        v.line = guard_line(stage, driver, version, attempt, what, build.empty() ? "unknown" : build);
        if (build != build_) {
            // Another build of the core crashed: this one is not held to it, and starts as a first attempt.
            v.line += std::format(" (another build; this build {} starts afresh)", build_);
            v.retry_without_cache = true;
            log::warn("{}: starting without the pipeline cache", v.line);
            return v;
        }
        // What the crash turns off, for this driver and version (or this system build, before the driver was known)
        // and this build of the core.
        const auto turn_off = [&] {
            const bool known = driver != "unknown";
            write_synced(dir_ / "off",
                         read_text(dir_ / "off") + std::format("{}|{}|{}|{}|{}\n", known ? clean(driver) : "system",
                                                               known ? clean(version) : system_, build_, clean(stage),
                                                               clean(what)));
        };
        if (stage == colour) {
            // VK bench's colour stage crashed: 10 bits off at once (the frame's own 8 bits never needed them, and a
            // retry could cost the bench again), and this start goes on at 8 bits without the pipeline cache, which
            // may hold the 10-bit pipelines.
            turn_off();
            v.retry_without_cache = true;
            log::error("{}: 10 bits are off for this driver and this build; the frame stays at 8", v.line);
            return v;
        }
        if (attempt >= 2) {
            // The retry crashed too: Vulkan off.
            turn_off();
            v.off = true;
            log::error("{}: Vulkan is off for this driver and this build", v.line);
        } else {
            v.retry_without_cache = true;
            attempt_ = 2;
            log::warn("{}: retrying once without the pipeline cache", v.line);
        }
        return v;
    }
    for (const GuardOff& o : turned_off()) {
        if (o.stage != colour && o.driver == "system" && o.version == system_ && o.build == build_) {
            v.off = true;
            v.line = guard_line(o.stage, "unknown", system_, 2, o.what, build_);
            return v;
        }
    }
    return v;
}

GuardVerdict Guard::check_driver(std::string_view driver, std::string_view version) {
    driver_ = clean(driver);
    version_ = clean(version);
    GuardVerdict v;
    if (dir_.empty()) return v;
    for (const GuardOff& o : turned_off()) {
        if (o.stage != colour && o.driver == driver_ && o.version == version_ && o.build == build_) {
            v.off = true;
            v.line = guard_line(o.stage, driver_, version_, 2, o.what, build_);
            return v;
        }
    }
    return v;
}

std::string Guard::ten_bit_off() const {
    for (const GuardOff& o : turned_off()) {
        if (o.stage != colour || o.build != build_) continue;
        const bool system = o.driver == "system";
        if (system ? o.version != system_ : (o.driver != driver_ || o.version != version_)) continue;
        return guard_line(o.stage, system ? "unknown" : o.driver, o.version, 1, o.what, build_);
    }
    return {};
}

void Guard::write_marker() const {
    if (dir_.empty() || (!current_ && !colour_)) return;
    std::string text = std::format("stage {}\n", colour_ ? colour : name_of(*current_));
    if (!making_.empty()) text += making_ + "\n";
    text += std::format("driver {}\nversion {}\nbuild {}\nattempt {}\n", driver_, version_.empty() ? system_ : version_,
                        build_, attempt_);
    write_synced(dir_ / "guard", text);
}

bool Guard::planted_at(std::string_view name, bool pipeline) const {
    // The plant of the stage the driver is making it in: the colour stage's inside it, else the pipelines stage's.
    const std::string_view stage = colour_ ? colour : name_of(Stage::pipelines);
    if (!plant_.starts_with(stage)) return false;
    const std::string_view rest = std::string_view(plant_).substr(stage.size());
    if (rest.empty()) return pipeline; // the stage's first pipeline
    return rest.size() > 1 && rest.front() == ':' && rest.substr(1) == name;
}

void Guard::abort_planted(std::string_view where) const {
    log::error("vk guard planted crash at {} (attempt {})", where, attempt_);
    std::abort();
}

void Guard::enter(Stage stage) {
    if (stage == Stage::colour) {
        enter_colour();
        return;
    }
    current_ = stage;
    making_.clear();
    write_marker();
    // The pipelines stage's plant waits for its first pipeline, so that the marker names it.
    if (stage != Stage::pipelines && !plant_.empty() && plant_ == name_of(stage)) abort_planted(name_of(stage));
}

void Guard::before(const PipelineLabel& label) {
    if (current_ != Stage::pipelines && !colour_) return;
    making_ = "pipeline " + clean(describe(label));
    write_marker();
    if (planted_at(label.name, true))
        abort_planted(std::format("{}, {}", colour_ ? colour : name_of(Stage::pipelines), making_));
}

void Guard::before_module(std::string_view module) {
    if (current_ != Stage::pipelines && !colour_) return;
    making_ = "module " + clean(module);
    write_marker();
    if (planted_at(module, false))
        abort_planted(std::format("{}, {}", colour_ ? colour : name_of(Stage::pipelines), making_));
}

void Guard::leave() {
    if (!current_) return;
    current_.reset();
    making_.clear();
    if (dir_.empty()) return;
    if (colour_) {
        write_marker(); // the colour stage goes on
        return;
    }
    std::error_code ec;
    std::filesystem::remove(dir_ / "guard", ec);
}

void Guard::enter_colour() {
    if (colour_) return;
    colour_ = true;
    making_.clear();
    write_marker();
}

void Guard::leave_colour() {
    if (!colour_) return;
    colour_ = false;
    making_.clear();
    if (dir_.empty()) return;
    if (current_) {
        write_marker(); // the stage it ran inside goes on
        return;
    }
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

std::vector<GuardOff> Guard::turned_off() const {
    std::vector<GuardOff> out;
    if (dir_.empty()) return out;
    const std::string text = read_text(dir_ / "off");
    const std::string_view all(text);
    std::size_t at = 0;
    while (at < all.size()) {
        const std::size_t end = std::min(all.find('\n', at), all.size());
        const std::vector<std::string_view> f = fields_of(all.substr(at, end - at));
        at = end + 1;
        if (f.size() == 5) {
            out.push_back({.driver = std::string(f[0]),
                           .version = std::string(f[1]),
                           .build = std::string(f[2]),
                           .stage = std::string(f[3]),
                           .what = std::string(f[4])});
        } else if (f.size() == 3) {
            // From before build IDs (alpha 6): driver|version|stage.
            out.push_back({.driver = std::string(f[0]),
                           .version = std::string(f[1]),
                           .build = {},
                           .stage = std::string(f[2]),
                           .what = {}});
        }
    }
    return out;
}

std::vector<std::string> Guard::state() const {
    std::vector<std::string> out;
    out.push_back(std::format("guard.build: {}", build_));
    out.push_back(std::format("guard.driver: {} {}", driver_, version_.empty() ? std::string("unknown") : version_));
    const std::vector<GuardOff> off = turned_off();
    if (off.empty()) out.emplace_back("guard.off: none");
    for (const GuardOff& o : off) {
        const bool system = o.driver == "system";
        const bool here = system ? o.version == system_ : o.driver == driver_ && o.version == version_;
        const std::string_view gone = o.stage == colour ? "10 bits are off" : "Vulkan is off";
        std::string whose = "another driver or version: not off here";
        if (o.build.empty()) {
            whose = "a build from before build IDs: not off for this one";
        } else if (o.build != build_) {
            whose = "another build of the core: not off for this one";
        } else if (here) {
            whose = std::format("this {} and build: {}", system ? "system" : "driver", gone);
        } else if (!system && driver_ == "unknown") {
            whose = std::format("this build: {} for that driver", gone); // this start has not read its driver yet
        }
        out.push_back(std::format("guard.off: {} {} at {}{}{} build {} ({})", o.driver, o.version, o.stage,
                                  o.what.empty() ? "" : ", ", o.what,
                                  o.build.empty() ? std::string("unknown") : o.build, whose));
    }
    return out;
}

std::string Guard::clear(const std::filesystem::path& cache_file) const {
    std::vector<std::string> forgot;
    std::error_code ec;
    if (!dir_.empty()) {
        if (std::filesystem::remove(dir_ / "guard", ec)) forgot.emplace_back("a crash not read yet");
        const std::vector<GuardOff> off = turned_off();
        const auto ten = static_cast<std::size_t>(std::ranges::count(off, colour, &GuardOff::stage));
        if (const std::size_t vulkan = off.size() - ten; vulkan > 0)
            forgot.push_back(std::format("{} {} turned off", vulkan, vulkan == 1 ? "driver" : "drivers"));
        if (ten > 0)
            forgot.push_back(std::format("10 bits turned off for {} {}", ten, ten == 1 ? "driver" : "drivers"));
        std::filesystem::remove(dir_ / "off", ec);
        std::filesystem::remove(dir_ / "off.tmp", ec);
        std::filesystem::remove(dir_ / "guard.tmp", ec);
    }
    if (!cache_file.empty() && std::filesystem::remove(cache_file, ec)) forgot.emplace_back("the pipeline cache");
    if (forgot.empty()) return "vk guard reset: nothing to forget";
    std::string said = "vk guard reset: forgot ";
    for (std::size_t i = 0; i < forgot.size(); ++i) {
        if (i > 0) said += i + 1 == forgot.size() ? " and " : ", ";
        said += forgot[i];
    }
    return said;
}

std::pair<std::string, std::string> guard_key(const Facts& facts) {
    std::string driver =
        facts.driver_properties ? vk::to_string(facts.driver_id) : std::string(vendor_name(facts.vendor_id));
    if (driver.empty() || driver == "unknown") driver = std::format("vendor-0x{:04x}", facts.vendor_id);
    return {driver, std::format("0x{:08x}", facts.driver_version)};
}

std::expected<Started, StartRefusal> start_guarded(const StartOptions& options) {
    Started s;
    s.guard = std::make_unique<Guard>(options.guard_dir, options.system, options.build, options.plant);
    Guard& guard = *s.guard;
    auto refuse = [&](std::string line, std::string reason) {
        return std::unexpected(StartRefusal{.line = std::move(line),
                                            .reason = std::move(reason),
                                            .kept_facts = guard.kept_facts(),
                                            .guard = guard.state()});
    };
    constexpr std::string_view twice = "the graphics driver crashed twice under this build; Vulkan is off for it";
    const GuardVerdict start = guard.check_start();
    if (start.off) return refuse(start.line, std::string(twice));
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

    s.device_options = {.present = options.present, .msaa2_close = options.msaa2_close};
    auto picked = Device::pick(*s.instance, s.device_options);
    if (!picked) return refuse({}, picked.error().text());
    const auto [driver, version] = guard_key(picked->facts);
    const GuardVerdict driver_verdict = guard.check_driver(driver, version);
    if (driver_verdict.off) return refuse(driver_verdict.line, std::string(twice));

    guard.enter(Stage::device);
    auto device = Device::open(*s.instance, std::move(*picked), s.device_options);
    guard.leave();
    if (!device) return refuse({}, device.error().text());
    s.device = std::move(*device);
    // After a crash in VK bench's colour stage under this driver and build (found now or by an earlier start), 10 bits
    // are off: the frame's colour never takes them, and the facts say so.
    s.device->facts().ten_bit_off = guard.ten_bit_off();
    decide_colour(s.device->facts());
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
