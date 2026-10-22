// The view ladder (view/Ladder.hpp): R/live/ViewLadder.kt line for line.
#include "view/Ladder.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <format>
#include <limits>

namespace atlas::view {

namespace {

/// What a cfg's own keys are: computed for a view, never taken from the nearest view (ViewLadder.OWN).
constexpr std::array<std::string_view, 14> own_keys{"target",   "yaw",   "pitch", "dist",  "fov", "cy", "radius",
                                                    "ground_r", "win_d", "haze0", "haze1", "W",   "H",  "cx"};
/// The distance-driven settings, in the port's order (ViewLadder.SCALED).
constexpr std::array<std::string_view, 4> scaled_keys{"radius", "ground_r", "haze0", "haze1"};

[[nodiscard]] bool is_own(std::string_view key) noexcept { return std::ranges::find(own_keys, key) != own_keys.end(); }

/// The high and low words of a double, and a double of them (fdlibm's __HI, __LO, and Java's __HI(d, high)).
[[nodiscard]] std::int32_t hi_of(double d) noexcept {
    return static_cast<std::int32_t>(std::bit_cast<std::uint64_t>(d) >> 32U);
}
[[nodiscard]] double with_hi(double d, std::int32_t high) noexcept {
    const std::uint64_t low = std::bit_cast<std::uint64_t>(d) & 0xFFFF'FFFFU;
    return std::bit_cast<double>((static_cast<std::uint64_t>(static_cast<std::uint32_t>(high)) << 32U) | low);
}

/// A typed setting of a view's camera value.
[[nodiscard]] Setting setting_of(const tiles::Value& v) noexcept {
    switch (v.type()) {
    case tiles::ValueType::int32:
    case tiles::ValueType::int64:
        return {.kind = Setting::Kind::integer,
                .integer = v.integer().value_or(0),
                .real = 0,
                .boolean = false,
                .triple = {},
                .value = {}};
    case tiles::ValueType::real:
        return {.kind = Setting::Kind::real,
                .integer = 0,
                .real = v.real().value_or(0.0),
                .boolean = false,
                .triple = {},
                .value = {}};
    case tiles::ValueType::boolean_false:
    case tiles::ValueType::boolean_true:
        return {.kind = Setting::Kind::boolean,
                .integer = 0,
                .real = 0,
                .boolean = v.boolean().value_or(false),
                .triple = {},
                .value = {}};
    default: return {.kind = Setting::Kind::value, .integer = 0, .real = 0, .boolean = false, .triple = {}, .value = v};
    }
}

[[nodiscard]] Setting real(double d) noexcept {
    return {.kind = Setting::Kind::real, .integer = 0, .real = d, .boolean = false, .triple = {}, .value = {}};
}
[[nodiscard]] Setting integer(std::int64_t n) noexcept {
    return {.kind = Setting::Kind::integer, .integer = n, .real = 0, .boolean = false, .triple = {}, .value = {}};
}
[[nodiscard]] Setting boolean(bool b) noexcept {
    return {.kind = Setting::Kind::boolean, .integer = 0, .real = 0, .boolean = b, .triple = {}, .value = {}};
}
[[nodiscard]] Setting triple(double x, double y, double z) noexcept {
    return {.kind = Setting::Kind::triple, .integer = 0, .real = 0, .boolean = false, .triple = {x, y, z}, .value = {}};
}

/// A value of a view's camera as the parity tests read it: `i <n>`, `d <bits>`, `b <true|false>`, `s <text>`,
/// `l [..]`, `m {..}`, `n`.
void dump_value(const tiles::Value& v, std::string& out) {
    switch (v.type()) {
    case tiles::ValueType::null: out += "n"; break;
    case tiles::ValueType::boolean_false: out += "b false"; break;
    case tiles::ValueType::boolean_true: out += "b true"; break;
    case tiles::ValueType::int32:
    case tiles::ValueType::int64: out += std::format("i {}", v.integer().value_or(0)); break;
    case tiles::ValueType::real:
        out += std::format("d {:016x}", std::bit_cast<std::uint64_t>(v.real().value_or(0.0)));
        break;
    case tiles::ValueType::string: out += std::format("s {}", v.string().value_or(std::string_view())); break;
    case tiles::ValueType::list:
        out += "l [";
        for (std::size_t i = 0; i < v.size(); ++i) {
            if (i > 0) out += ", ";
            dump_value(v.child(i), out);
        }
        out += "]";
        break;
    case tiles::ValueType::map:
        out += "m {";
        for (std::size_t i = 0; i < v.size(); ++i) {
            if (i > 0) out += ", ";
            out += v.key(i);
            out += ": ";
            dump_value(v.child(i), out);
        }
        out += "}";
        break;
    }
}

} // namespace

double bearing_of(double yaw) noexcept {
    // LiveCamera.bearing: ((yaw % 360.0) + 360.0) % 360.0, Kotlin's % being fmod
    return std::fmod(std::fmod(yaw, 360.0) + 360.0, 360.0);
}

double turn(double from, double to) noexcept {
    // ViewLadder.turn
    double d = std::fmod(to - from, 360.0);
    if (d > 180.0) d -= 360.0;
    if (d < -180.0) d += 360.0;
    return d;
}

double java_hypot(double x, double y) noexcept {
    // Java's FdLibm.Hypot.compute (JDK 17), fdlibm 5.3's e_hypot.c
    double a = std::fabs(x);
    double b = std::fabs(y);
    if (!std::isfinite(a) || !std::isfinite(b)) {
        if (a == std::numeric_limits<double>::infinity() || b == std::numeric_limits<double>::infinity()) {
            return std::numeric_limits<double>::infinity();
        }
        return a + b; // the NaN's bits
    }
    if (b > a) std::swap(a, b);
    std::int32_t ha = hi_of(a);
    std::int32_t hb = hi_of(b);
    if ((ha - hb) > 0x3c0'0000) return a + b; // a / b > 2^60
    int k = 0;
    constexpr double two_minus_600 = 0x1.0p-600;
    constexpr double two_plus_600 = 0x1.0p+600;
    if (a > 0x1.00000ffffffffp500) { // a > ~2^500: scale both by 2^-600
        ha -= 0x2580'0000;
        hb -= 0x2580'0000;
        a = a * two_minus_600;
        b = b * two_minus_600;
        k += 600;
    }
    double t1 = 0;
    double t2 = 0;
    if (b < 0x1.0p-500) {                             // b < 2^-500
        if (b < std::numeric_limits<double>::min()) { // subnormal or 0
            if (b == 0.0) return a;
            t1 = 0x1.0p1022;
            b *= t1;
            a *= t1;
            k -= 1022;
        } else { // scale both by 2^600
            ha += 0x2580'0000;
            hb += 0x2580'0000;
            a = a * two_plus_600;
            b = b * two_plus_600;
            k -= 600;
        }
    }
    // medium size a and b
    double w = a - b;
    if (w > b) {
        t1 = with_hi(0.0, ha);
        t2 = a - t1;
        w = std::sqrt(t1 * t1 - (b * (-b) - t2 * (a + t1)));
    } else {
        a = a + a;
        const double y1 = with_hi(0.0, hb);
        const double y2 = b - y1;
        t1 = with_hi(0.0, ha + 0x0010'0000);
        t2 = a - t1;
        w = std::sqrt(t1 * y1 - (w * (-w) - (t1 * y2 + t2 * b)));
    }
    if (k != 0) return std::ldexp(1.0, k) * w; // Math.powerOfTwoD(k), exact
    return w;
}

std::optional<double> Setting::number() const noexcept {
    switch (kind) {
    case Kind::integer: return static_cast<double>(integer);
    case Kind::real: return real;
    case Kind::value: return value.number();
    case Kind::boolean:
    case Kind::triple: return std::nullopt;
    }
    return std::nullopt;
}

const Setting* Placed::find(std::string_view key) const noexcept {
    for (const auto& [k, v] : settings_) {
        if (k == key) return &v;
    }
    return nullptr;
}

std::optional<double> Placed::number(std::string_view key) const noexcept {
    const Setting* s = find(key);
    if (s == nullptr) return std::nullopt;
    return s->number();
}

bool Placed::truthy(std::string_view key) const noexcept {
    const Setting* s = find(key);
    if (s == nullptr) return false;
    switch (s->kind) {
    case Setting::Kind::integer: return s->integer != 0;
    case Setting::Kind::real: return s->real != 0.0;
    case Setting::Kind::boolean: return s->boolean;
    case Setting::Kind::triple: return true;
    case Setting::Kind::value: {
        const tiles::Value& v = s->value;
        switch (v.type()) {
        case tiles::ValueType::null: return false;
        case tiles::ValueType::string: return !v.string().value_or(std::string_view()).empty();
        case tiles::ValueType::list:
        case tiles::ValueType::map: return v.size() > 0;
        default: return v.number().value_or(0.0) != 0.0 || v.boolean().value_or(false);
        }
    }
    }
    return false;
}

void Placed::set(std::string_view key, Setting value) {
    for (auto& [k, v] : settings_) {
        if (k == key) {
            v = value; // a LinkedHashMap keeps a key's place when its value is replaced
            return;
        }
    }
    settings_.emplace_back(key, value);
}

CameraSpec Placed::camera() const noexcept {
    // Amsterdam.cameraOf
    CameraSpec c;
    if (const Setting* t = find("target"); t != nullptr && t->kind == Setting::Kind::triple) {
        c.target = {t->triple[0], t->triple[1], t->triple[2]};
    } else if (t != nullptr && t->kind == Setting::Kind::value && t->value.size() >= 3) {
        c.target = {t->value.child(0).number().value_or(0.0), t->value.child(1).number().value_or(0.0),
                    t->value.child(2).number().value_or(0.0)};
    }
    c.yaw = number("yaw").value_or(0.0);
    c.pitch = number("pitch").value_or(0.0);
    c.dist = number("dist").value_or(0.0);
    c.fov = number("fov").value_or(field_of_view);
    c.width = number("W").value_or(0.0);
    c.height = number("H").value_or(0.0);
    c.cx = number("cx");
    c.cy = number("cy");
    return c;
}

std::string Placed::dump() const {
    std::string out;
    for (const auto& [k, v] : settings_) {
        out += k;
        out += '=';
        switch (v.kind) {
        case Setting::Kind::integer: out += std::format("i {}", v.integer); break;
        case Setting::Kind::real: out += std::format("d {:016x}", std::bit_cast<std::uint64_t>(v.real)); break;
        case Setting::Kind::boolean: out += v.boolean ? "b true" : "b false"; break;
        case Setting::Kind::triple:
            out += std::format("l [d {:016x}, d {:016x}, d {:016x}]", std::bit_cast<std::uint64_t>(v.triple[0]),
                               std::bit_cast<std::uint64_t>(v.triple[1]), std::bit_cast<std::uint64_t>(v.triple[2]));
            break;
        case Setting::Kind::value: dump_value(v.value, out); break;
        }
        out += '\n';
    }
    return out;
}

std::optional<Ladder> Ladder::of(std::span<const tiles::HeadView> views, GroundFrame frame, bool living) {
    Ladder l;
    l.frame_ = frame;
    l.living_ = living;
    for (const tiles::HeadView& v : views) {
        const tiles::Value& cam = v.camera;
        const auto dist = cam.find("dist").and_then([](const tiles::Value& x) { return x.number(); });
        const auto yaw = cam.find("yaw").and_then([](const tiles::Value& x) { return x.number(); });
        const auto pitch = cam.find("pitch").and_then([](const tiles::Value& x) { return x.number(); });
        const auto target = cam.find("target");
        if (!dist || !yaw || !pitch || !target || target->type() != tiles::ValueType::list || target->size() < 3) {
            return std::nullopt;
        }
        Knot k{.name = v.name, .camera = cam, .dist = *dist, .target = {}, .yaw = *yaw, .pitch = *pitch, .cy = 0};
        for (std::size_t i = 0; i < 3; ++i) {
            const auto t = target->child(i).number();
            if (!t) return std::nullopt;
            k.target[i] = *t;
        }
        const auto cy = cam.find("cy").and_then([](const tiles::Value& x) { return x.number(); });
        k.cy = cy ? *cy : std::numeric_limits<double>::quiet_NaN();
        l.knots_.push_back(k);
    }
    if (l.knots_.empty()) return std::nullopt;
    std::ranges::stable_sort(l.knots_, {}, &Knot::dist);
    return l;
}

std::optional<Ladder> Ladder::of(const tiles::RenderHead& head) {
    return of(head.views(), frame_of(head.frame), head.profile == tiles::Profile::living);
}

Ladder Ladder::bare(GroundFrame frame, bool living) {
    Ladder l;
    l.frame_ = frame;
    l.living_ = living;
    return l;
}

const Ladder::Knot* Ladder::snap(const LiveView& v) const noexcept {
    // ViewLadder.snap
    const double tx = frame_.x(v.latitude, v.longitude);
    const double tz = frame_.z(v.latitude, v.longitude);
    const double dist = v.distance;
    const double pitch = engine_pitch(v.pitch);
    for (const Knot& k : knots_) {
        if (java_hypot(tx - k.target[0], tz - k.target[2]) <= snap_m && std::fabs(turn(v.bearing, k.yaw)) <= snap_deg &&
            std::fabs(pitch - k.pitch) <= snap_deg && std::fabs(dist / k.dist - 1) <= snap_dist &&
            (std::isnan(k.cy) || std::fabs(v.cy_dp - k.cy) <= snap_cy)) {
            return &k;
        }
    }
    return nullptr;
}

Placed Ladder::exact(const Knot& k, std::int32_t width_dp, std::int32_t height_dp) const {
    // ViewLadder.exact: regions.py's cfg_for
    Placed c;
    for (std::size_t i = 0; i < k.camera.size(); ++i) c.set(k.camera.key(i), setting_of(k.camera.child(i)));
    c.set("W", integer(width_dp));
    c.set("H", integer(height_dp));
    if (living_) return c;
    c.set("no_fin", boolean(true));
    c.set("obj_split", integer(obj_split));
    if (c.find("haze0") == nullptr) c.set("haze0", real(c.number("dist").value_or(0.0) * 0.85));
    if (c.find("haze1") == nullptr) c.set("haze1", real(c.number("dist").value_or(0.0) * 2.2));
    return c;
}

double Ladder::value_of(const Knot& k, std::string_view key) const noexcept {
    // ViewLadder.valueOf
    if (const auto v = k.camera.find(key).and_then([](const tiles::Value& x) { return x.number(); })) return *v;
    if (key == "haze0") return living_ ? living_haze0 : k.dist * 0.85;
    if (key == "haze1") return living_ ? living_haze1 : k.dist * 2.2;
    if (key == "ground_r") return default_ground_r;
    if (key == "radius") return k.dist;
    return default_win_d; // win_d
}

double Ladder::scaled(std::string_view key, double dist) const noexcept {
    // ViewLadder.scaled
    const Knot& first = knots_.front();
    const Knot& last = knots_.back();
    if (dist <= first.dist) return value_of(first, key) * dist / first.dist;
    if (dist >= last.dist) return value_of(last, key) * dist / last.dist;
    return between(dist, key);
}

double Ladder::between(double dist, std::string_view key) const noexcept {
    // ViewLadder.between
    if (dist <= knots_.front().dist) return value_of(knots_.front(), key);
    if (dist >= knots_.back().dist) return value_of(knots_.back(), key);
    for (std::size_t i = 1; i < knots_.size(); ++i) {
        const Knot& a = knots_[i - 1];
        const Knot& b = knots_[i];
        if (dist <= b.dist) {
            if (b.dist == a.dist) return value_of(b, key);
            const double t = (dist - a.dist) / (b.dist - a.dist);
            return value_of(a, key) + (value_of(b, key) - value_of(a, key)) * t;
        }
    }
    return value_of(knots_.back(), key);
}

Placed Ladder::place(const LiveView& v) const {
    if (knots_.empty()) {
        // a pack without views: the live view in its frame, as cfg_for gives a view of nothing but its camera
        Placed c;
        const double dist = v.distance;
        c.set("target", triple(frame_.x(v.latitude, v.longitude), 0.0, frame_.z(v.latitude, v.longitude)));
        c.set("yaw", real(bearing_of(v.bearing)));
        c.set("pitch", real(engine_pitch(v.pitch)));
        c.set("dist", real(dist));
        c.set("fov", real(field_of_view));
        c.set("cy", real(v.cy_dp));
        c.set("W", integer(v.width_dp));
        c.set("H", integer(v.height_dp));
        if (!living_) {
            c.set("no_fin", boolean(true));
            c.set("obj_split", integer(obj_split));
            c.set("haze0", real(dist * 0.85));
            c.set("haze1", real(dist * 2.2));
        }
        c.dist_ = dist;
        return c;
    }
    // ViewLadder.place
    if (const Knot* k = snap(v)) {
        Placed p = exact(*k, v.width_dp, v.height_dp);
        p.view_ = k->name;
        p.dist_ = k->dist;
        return p;
    }
    Placed c;
    const double dist = v.distance;
    const double tx = frame_.x(v.latitude, v.longitude);
    const double tz = frame_.z(v.latitude, v.longitude);
    c.set("target", triple(tx, 0.0, tz));
    c.set("yaw", real(bearing_of(v.bearing)));
    c.set("pitch", real(engine_pitch(v.pitch)));
    c.set("dist", real(dist));
    c.set("fov", real(field_of_view));
    c.set("cy", real(v.cy_dp));
    for (const std::string_view key : scaled_keys) c.set(key, real(scaled(key, dist)));
    c.set("win_d", real(between(dist, "win_d")));
    // What is not a matter of distance comes from the nearest view (the first of the nearest).
    const Knot* near = &knots_.front();
    for (const Knot& k : knots_) {
        if (std::fabs(k.dist - dist) < std::fabs(near->dist - dist)) near = &k;
    }
    for (std::size_t i = 0; i < near->camera.size(); ++i) {
        const std::string_view key = near->camera.key(i);
        if (!is_own(key) && c.find(key) == nullptr) c.set(key, setting_of(near->camera.child(i)));
    }
    c.set("W", integer(v.width_dp));
    c.set("H", integer(v.height_dp));
    if (!living_) {
        c.set("no_fin", boolean(true));
        c.set("obj_split", integer(obj_split));
    }
    c.dist_ = dist;
    return c;
}

LiveView live_view(const style::CameraState& c) noexcept {
    return {.latitude = c.latitude,
            .longitude = c.longitude,
            .distance = c.distance,
            .bearing = c.bearing,
            .pitch = c.pitch,
            .width_dp = static_cast<std::int32_t>(c.width_dp),
            .height_dp = static_cast<std::int32_t>(c.height_dp),
            .cy_dp = c.principal_y};
}

} // namespace atlas::view
