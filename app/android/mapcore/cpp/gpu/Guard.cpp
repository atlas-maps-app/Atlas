// The crash guard (gpu/Guard.hpp).
#include "gpu/Guard.hpp"

#include "base/log.hpp"

#include <algorithm>
#include <array>
#include <charconv>
#include <chrono>
#include <cstdlib>
#include <fcntl.h>
#include <format>
#include <fstream>
#include <sstream>
#include <unistd.h>
#include <vector>

namespace atlas::gpu {

namespace {

constexpr std::array stage_names{"instance",  "facts",  "device",   "cache",  "surface",
                                 "pipelines", "frames", "profiles", "colour", "close"};
static_assert(stage_names.size() == static_cast<std::size_t>(Stage::close) + 1);

/// The colour stage's and the profiles stage's names, as markers and off lines spell them: the rungs below Vulkan off,
/// whose crash turns off only what they guard, at once.
constexpr std::string_view colour = "colour";
constexpr std::string_view profiles = "profiles";

[[nodiscard]] bool rung_below(std::string_view stage) noexcept { return stage == colour || stage == profiles; }

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

/// A whole decimal number, or nullopt.
[[nodiscard]] std::optional<std::int64_t> number(std::string_view text) noexcept {
    std::int64_t value = 0;
    const auto [end, error] = std::from_chars(text.data(), text.data() + text.size(), value);
    if (error != std::errc() || end != text.data() + text.size() || text.empty()) return std::nullopt;
    return value;
}

/// A value for a line of the off file or the marker: '|' would split its fields, a line break its lines.
[[nodiscard]] std::string clean(std::string_view value) {
    std::string out(value);
    std::ranges::replace_if(out, [](char c) { return c == '|' || c == '\n' || c == '\r'; }, ' ');
    return out;
}

/// The fields of one line, split at `separator`.
[[nodiscard]] std::vector<std::string_view> split(std::string_view line, char separator) {
    std::vector<std::string_view> out;
    std::size_t at = 0;
    for (;;) {
        const std::size_t bar = line.find(separator, at);
        out.push_back(line.substr(at, bar == std::string_view::npos ? std::string_view::npos : bar - at));
        if (bar == std::string_view::npos) return out;
        at = bar + 1;
    }
}

/// The `vk guard` line (plan-vector Appendix C), with what the driver was making or destroying ("pipeline <…>",
/// "module <…>", "release <…>") and the core's build.
[[nodiscard]] std::string guard_line(std::string_view stage, std::string_view driver, std::string_view version,
                                     std::uint32_t attempt, std::string_view what, std::string_view build) {
    std::string line = std::format("vk guard {} {} {} attempt {}", stage, driver, version, attempt);
    if (!what.empty()) line += std::format(" {}", what);
    line += std::format(" build {}", build);
    return line;
}

/// The attempt an off line names: its own, or for a line from before attempts 2 for Vulkan off (it is written at the
/// second crash) and 1 for the rungs below it (at the first).
[[nodiscard]] std::uint32_t attempt_of(const GuardOff& o) noexcept {
    if (o.attempt != 0) return o.attempt;
    return rung_below(o.stage) ? 1U : 2U;
}

[[nodiscard]] std::int64_t now_ms() noexcept {
    return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch())
        .count();
}

} // namespace

std::string_view name_of(Stage stage) noexcept { return stage_names.at(static_cast<std::size_t>(stage)); }

std::optional<Stage> stage_named(std::string_view name) noexcept {
    for (std::size_t i = 0; i < stage_names.size(); ++i) {
        if (name == stage_names[i]) return static_cast<Stage>(i);
    }
    return std::nullopt;
}

ProcessExits parse_exits(std::string_view text) {
    ProcessExits out;
    if (text.empty()) return out;
    out.known = true;
    for (const std::string_view line : split(text, '\n')) {
        const std::vector<std::string_view> f = split(line, ' ');
        if (f.size() < 4) continue; // "none", or a line that does not read
        const auto pid = number(f[0]);
        const auto at = number(f[1]);
        if (!pid || !at || (f[2] != "0" && f[2] != "1")) continue;
        std::string reason(f[3]);
        for (std::size_t i = 4; i < f.size(); ++i) reason += std::format(" {}", f[i]);
        out.exits.push_back({.pid = *pid, .at_ms = *at, .counted = f[2] == "1", .reason = std::move(reason)});
    }
    return out;
}

Guard::Guard(std::filesystem::path dir, std::string system, std::string build, std::string plant)
    : dir_(std::move(dir)), system_(std::move(system)), build_(std::move(build)), plant_(std::move(plant)),
      pid_(static_cast<std::int64_t>(::getpid())), start_ms_(now_ms()) {
    if (system_.empty()) system_ = "unknown";
    // Never empty: an off line from before build IDs has an empty build, which must match no build.
    if (build_.empty()) build_ = "unknown";
    system_ = clean(system_);
    build_ = clean(build_);
}

void Guard::set_process_start(std::int64_t ms) noexcept {
    if (ms > 0) start_ms_ = ms;
}

GuardVerdict Guard::check_start(const ProcessExits& exits) {
    GuardVerdict v;
    if (dir_.empty()) return v;
    if (const std::string marker = read_text(dir_ / "guard"); !marker.empty()) {
        if (read_marker(marker, exits, v)) return v;
    }
    for (const GuardOff& o : turned_off()) {
        if (!rung_below(o.stage) && o.driver == "system" && o.version == system_ && o.build == build_) {
            v.off = true;
            v.line = guard_line(o.stage, "unknown", system_, attempt_of(o), o.what, build_);
            return v;
        }
    }
    return v;
}

bool Guard::read_marker(const std::string& marker, const ProcessExits& exits, GuardVerdict& v) {
    std::string stage = field(marker, "stage");
    std::string driver = field(marker, "driver");
    std::string version = field(marker, "version");
    const std::string pipeline = field(marker, "pipeline");
    const std::string module = field(marker, "module");
    const std::string release = field(marker, "release");
    const std::string what = !pipeline.empty()  ? "pipeline " + pipeline
                             : !module.empty()  ? "module " + module
                             : !release.empty() ? "release " + release
                                                : std::string();
    const std::string build = field(marker, "build"); // empty: a marker from before build IDs
    if (stage.empty()) stage = "unknown";
    if (driver.empty()) driver = "unknown";
    if (version.empty()) version = system_;
    const auto attempt = static_cast<std::uint32_t>(
        std::clamp<std::int64_t>(number(field(marker, "attempt")).value_or(1), 1, 1'000'000));
    std::error_code ec;
    std::filesystem::remove(dir_ / "guard", ec);

    // Kills are not crashes: the marker's process, among the exits Android recorded, was stopped by the user, the
    // system or the package's update, or it is this very process (an earlier core in it left the marker, and it runs).
    // A marker without a process (from before alpha 7's second round), or exits not known (API 29), counts.
    const std::optional<std::int64_t> pid = number(field(marker, "pid"));
    const std::optional<std::int64_t> start = number(field(marker, "start"));
    if (pid && start && exits.known) {
        if (*pid == pid_) {
            v.discarded = std::format("vk guard: {} marker of this running process, not counted", stage);
        } else {
            // The process's exit: the first after its start among those of its id (ids are reused).
            const ProcessExit* exit = nullptr;
            for (const ProcessExit& e : exits.exits) {
                if (e.pid == *pid && e.at_ms >= *start && (exit == nullptr || e.at_ms < exit->at_ms)) exit = &e;
            }
            if (exit != nullptr && !exit->counted) {
                v.discarded = std::format("vk guard: {} marker after {}, not counted", stage, exit->reason);
            } else {
                v.exit = exit != nullptr ? exit->reason : std::string("no record");
            }
        }
        if (!v.discarded.empty()) {
            log::warn("{}", v.discarded);
            // It does not reset the ladder either: a retry that was stopped goes on as the retry.
            if (build == build_ && !rung_below(stage) && attempt >= 2) {
                attempt_ = attempt;
                v.retry_without_cache = true;
            }
            return false; // the off file may still turn Vulkan off for this system build
        }
    }

    v.line = guard_line(stage, driver, version, attempt, what, build.empty() ? "unknown" : build);
    // How its process ended, where Android recorded it, for the log's line of the verdict.
    const std::string ended = v.exit.empty() ? std::string() : std::format(" (its process: {})", v.exit);
    if (build != build_) {
        // Another build of the core crashed: this one is not held to it, and starts as a first attempt.
        v.line += std::format(" (another build; this build {} starts afresh)", build_);
        v.retry_without_cache = true;
        log::warn("{}{}: starting without the pipeline cache", v.line, ended);
        return true;
    }
    // What the crash turns off, for this driver and version (or this system build, before the driver was known) and
    // this build of the core, with the attempt it crashed in.
    const auto turn_off = [&] {
        const bool known = driver != "unknown";
        const std::string key_driver = known ? clean(driver) : std::string("system");
        const std::string key_version = known ? clean(version) : system_;
        // Once a driver and build: a verdict already on file stands as it was written.
        for (const GuardOff& o : turned_off()) {
            if (o.driver == key_driver && o.version == key_version && o.build == build_ && o.stage == clean(stage))
                return;
        }
        write_synced(dir_ / "off", read_text(dir_ / "off") + std::format("{}|{}|{}|{}|{}|{}\n", key_driver, key_version,
                                                                         build_, clean(stage), clean(what), attempt));
    };
    if (stage == colour) {
        // VK bench's colour stage crashed: 10 bits off at once (the frame's own 8 bits never needed them, and a retry
        // could cost the bench again). The pipeline cache on disk never holds a 10-bit pipeline (the stage has its
        // own), so this start keeps it and goes on at 8 bits.
        turn_off();
        log::error("{}{}: 10 bits are off for this driver and this build; the frame stays at 8", v.line, ended);
        return true;
    }
    if (stage == profiles) {
        // The Profiles library's checks crashed: they decide nothing (VK facts reports them), so they alone are off, at
        // once, and this start goes on as it was, its cache kept.
        turn_off();
        log::error("{}{}: the profiles check is off for this driver and this build", v.line, ended);
        return true;
    }
    if (attempt >= 2) {
        // The retry crashed too: Vulkan off.
        turn_off();
        v.off = true;
        log::error("{}{}: Vulkan is off for this driver and this build", v.line, ended);
    } else {
        v.retry_without_cache = true;
        attempt_ = 2;
        log::warn("{}{}: retrying once without the pipeline cache", v.line, ended);
    }
    return true;
}

GuardVerdict Guard::check_driver(std::string_view driver, std::string_view version) {
    driver_ = clean(driver);
    version_ = clean(version);
    GuardVerdict v;
    if (dir_.empty()) return v;
    for (const GuardOff& o : turned_off()) {
        if (!rung_below(o.stage) && o.driver == driver_ && o.version == version_ && o.build == build_) {
            v.off = true;
            v.line = guard_line(o.stage, driver_, version_, attempt_of(o), o.what, build_);
            return v;
        }
    }
    return v;
}

std::string Guard::rung_off(std::string_view stage) const {
    for (const GuardOff& o : turned_off()) {
        if (o.stage != stage || o.build != build_) continue;
        const bool system = o.driver == "system";
        if (system ? o.version != system_ : (o.driver != driver_ || o.version != version_)) continue;
        return guard_line(o.stage, system ? "unknown" : o.driver, o.version, attempt_of(o), o.what, build_);
    }
    return {};
}

std::string Guard::ten_bit_off() const { return rung_off(colour); }

std::string Guard::profiles_off() const { return rung_off(profiles); }

std::string_view Guard::marked() const noexcept {
    if (colour_) return colour;
    return current_ ? name_of(*current_) : std::string_view();
}

void Guard::write_marker() const {
    if (dir_.empty() || (!current_ && !colour_)) return;
    std::string text = std::format("stage {}\n", marked());
    if (!making_.empty()) text += making_ + "\n";
    text += std::format("driver {}\nversion {}\nbuild {}\nattempt {}\npid {}\nstart {}\n", driver_,
                        version_.empty() ? system_ : version_, build_, attempt_, pid_, start_ms_);
    write_synced(dir_ / "guard", text);
}

bool Guard::planted_making(std::string_view name, bool pipeline) const {
    // The plant of the stage the driver is making it in: the colour stage's inside it, else the pipelines stage's.
    const std::string_view stage = colour_ ? colour : name_of(Stage::pipelines);
    if (!plant_.starts_with(stage)) return false;
    const std::string_view rest = std::string_view(plant_).substr(stage.size());
    if (rest.empty()) return pipeline; // the stage's first pipeline
    return rest.size() > 1 && rest.front() == ':' && rest.substr(1) == name;
}

bool Guard::planted_release(const Release& release) const {
    const std::string_view stage = marked();
    if (stage.empty() || !plant_.starts_with(stage)) return false;
    constexpr std::string_view releases = ":release";
    std::string_view rest = std::string_view(plant_).substr(stage.size());
    if (!rest.starts_with(releases)) return false;
    rest.remove_prefix(releases.size());
    // The colour stage's plant takes a thing of 10 bits only, never one of 8 that goes inside the stage; and without a
    // kind, a 10-bit frame or swapchain only (the stage's own cache goes even where the device refused 10 bits).
    if (colour_ && !release.ten_bit) return false;
    if (rest.empty()) return !colour_ || release.kind == "frame" || release.kind == "swapchain";
    return rest.size() > 1 && rest.front() == ':' && rest.substr(1) == release.kind;
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
    // The pipelines stage's plant waits for its first pipeline, so that the marker names it; inside the colour stage
    // no other stage's plant fires (its marker is the colour stage's).
    if (!colour_ && stage != Stage::pipelines && !plant_.empty() && plant_ == name_of(stage))
        abort_planted(name_of(stage));
}

void Guard::note(std::string what, bool planted) {
    making_ = std::move(what);
    write_marker();
    if (planted) abort_planted(std::format("{}, {}", marked(), making_));
}

void Guard::before(const PipelineLabel& label) {
    if (current_ != Stage::pipelines && !colour_) return;
    note("pipeline " + clean(describe(label)), planted_making(label.name, true));
}

void Guard::before_module(std::string_view module) {
    if (current_ != Stage::pipelines && !colour_) return;
    note("module " + clean(module), planted_making(module, false));
}

void Guard::before_release(const Release& release) {
    if (!current_ && !colour_) return;
    note("release " + clean(describe(release)), planted_release(release));
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
    for (const std::string_view line : split(text, '\n')) {
        const std::vector<std::string_view> f = split(line, '|');
        if (f.size() == 5 || f.size() == 6) {
            // driver|version|build|stage|what, then the attempt (from alpha 7's second round).
            const auto attempt = f.size() == 6 ? number(f[5]).value_or(0) : 0;
            out.push_back({.driver = std::string(f[0]),
                           .version = std::string(f[1]),
                           .build = std::string(f[2]),
                           .stage = std::string(f[3]),
                           .what = std::string(f[4]),
                           .attempt = static_cast<std::uint32_t>(std::clamp<std::int64_t>(attempt, 0, 1'000'000))});
        } else if (f.size() == 3) {
            // From before build IDs (alpha 6): driver|version|stage.
            out.push_back({.driver = std::string(f[0]),
                           .version = std::string(f[1]),
                           .build = {},
                           .stage = std::string(f[2]),
                           .what = {},
                           .attempt = 0});
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
        const std::string_view gone = o.stage == colour     ? "10 bits are off"
                                      : o.stage == profiles ? "the profiles check is off"
                                                            : "Vulkan is off";
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
        const auto checks = static_cast<std::size_t>(std::ranges::count(off, profiles, &GuardOff::stage));
        if (const std::size_t vulkan = off.size() - ten - checks; vulkan > 0)
            forgot.push_back(std::format("{} {} turned off", vulkan, vulkan == 1 ? "driver" : "drivers"));
        if (ten > 0)
            forgot.push_back(std::format("10 bits turned off for {} {}", ten, ten == 1 ? "driver" : "drivers"));
        if (checks > 0) {
            forgot.push_back(
                std::format("the profiles check turned off for {} {}", checks, checks == 1 ? "driver" : "drivers"));
        }
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
    guard.set_process_start(options.process_start_ms);
    auto refuse = [&](std::string line, std::string reason) {
        return std::unexpected(StartRefusal{.line = std::move(line),
                                            .reason = std::move(reason),
                                            .kept_facts = guard.kept_facts(),
                                            .guard = guard.state(),
                                            .discarded = s.discarded_line});
    };
    constexpr std::string_view twice = "the graphics driver crashed twice under this build; Vulkan is off for it";
    const GuardVerdict start = guard.check_start(options.exits);
    s.discarded_line = start.discarded;
    if (start.off) return refuse(start.line, std::string(twice));
    s.guard_line = start.line;
    s.exit = start.exit;
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

    // The device's facts: every query its choice makes of the driver, before the driver is known (keyed by the system
    // build, as the instance's crash is). The 10-bit formats are not among them (VK bench's colour stage asks for
    // them), nor the Profiles library's checks (the profiles stage runs them when VK facts or VK bench asks).
    s.device_options = {.present = options.present, .msaa2_close = options.msaa2_close};
    guard.enter(Stage::facts);
    auto picked = Device::pick(*s.instance, s.device_options);
    guard.leave();
    if (!picked) return refuse({}, picked.error().text());
    const auto [driver, version] = guard_key(picked->facts);
    const GuardVerdict driver_verdict = guard.check_driver(driver, version);
    if (driver_verdict.off) return refuse(driver_verdict.line, std::string(twice));

    guard.enter(Stage::device);
    auto device = Device::open(*s.instance, std::move(*picked), s.device_options);
    guard.leave();
    if (!device) return refuse({}, device.error().text());
    s.device = std::move(*device);
    // After a crash in VK bench's colour stage or in the profiles check under this driver and build (found now or by an
    // earlier start), what they guard is off: the frame's colour never takes 10 bits, the profiles go unread, and the
    // facts say so.
    s.device->facts().ten_bit_off = guard.ten_bit_off();
    s.device->facts().profiles_off = guard.profiles_off();
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
