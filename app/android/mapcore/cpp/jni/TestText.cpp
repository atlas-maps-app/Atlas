// The text module's test entry points in the host build (plan-vector K13), for :mapcorecheck's MT/text tests on the
// JVM: a bundled pack's canal names at a camera as the renders' rule draws them (L1's text runs at the home views),
// one ground_text, a runway's designators, and str.upper() of single code points. They bind to the Kotlin object
// app.atlas.mapcorecheck.text.TextHost by JNI's names; nothing of them is in the phone's library.
#include "base/arena.hpp"
#include "text/CanalNames.hpp"
#include "text/Fonts.hpp"
#include "text/GroundText.hpp"
#include "text/Shaper.hpp"
#include "tiles/Chunks.hpp"
#include "tiles/Container.hpp"
#include "tiles/Error.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Source.hpp"

#include <jni.h>

#include <algorithm>
#include <array>
#include <bit>
#include <chrono>
#include <cmath>
#include <expected>
#include <format>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

namespace {

using namespace atlas;
namespace tl = atlas::tiles;

/// The layout version of these entry points; TextHost refuses another.
constexpr jint test_text_abi = 1;

std::string path_of(JNIEnv* env, jstring s) {
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

void append_utf8(std::string& out, char32_t c) {
    if (c < 0x80) {
        out += static_cast<char>(c);
    } else if (c < 0x800) {
        out += static_cast<char>(0xC0U | (c >> 6U));
        out += static_cast<char>(0x80U | (c & 0x3FU));
    } else if (c < 0x10000) {
        out += static_cast<char>(0xE0U | (c >> 12U));
        out += static_cast<char>(0x80U | ((c >> 6U) & 0x3FU));
        out += static_cast<char>(0x80U | (c & 0x3FU));
    } else {
        out += static_cast<char>(0xF0U | (c >> 18U));
        out += static_cast<char>(0x80U | ((c >> 12U) & 0x3FU));
        out += static_cast<char>(0x80U | ((c >> 6U) & 0x3FU));
        out += static_cast<char>(0x80U | (c & 0x3FU));
    }
}

/// A Java string as UTF-8 (JNI's own UTF is modified UTF-8, which differs past the BMP); a lone surrogate is U+FFFD.
std::string utf8_of(JNIEnv* env, jstring s) {
    const jsize n = env->GetStringLength(s);
    std::vector<jchar> units(static_cast<std::size_t>(n));
    env->GetStringRegion(s, 0, n, units.data());
    std::string out;
    for (std::size_t i = 0; i < units.size(); ++i) {
        char32_t c = units[i];
        if (c >= 0xD800 && c < 0xDC00 && i + 1 < units.size() && units[i + 1] >= 0xDC00 && units[i + 1] < 0xE000) {
            c = 0x10000 + ((c - 0xD800) << 10U) + (units[i + 1] - 0xDC00U);
            ++i;
        } else if (c >= 0xD800 && c < 0xE000) {
            c = 0xFFFD;
        }
        append_utf8(out, c);
    }
    return out;
}

/// UTF-8 text as a Java string.
jstring java_of(JNIEnv* env, std::string_view utf8) {
    std::vector<jchar> units;
    for (const char32_t c : text::code_points(utf8)) {
        if (c >= 0x10000) {
            units.push_back(static_cast<jchar>(0xD800 + ((c - 0x10000) >> 10U)));
            units.push_back(static_cast<jchar>(0xDC00 + ((c - 0x10000) & 0x3FFU)));
        } else {
            units.push_back(static_cast<jchar>(c));
        }
    }
    return env->NewString(units.data(), static_cast<jsize>(units.size()));
}

/// The doubles of a Java array.
std::vector<double> doubles_of(JNIEnv* env, jdoubleArray a) {
    std::vector<double> out(static_cast<std::size_t>(env->GetArrayLength(a)));
    env->GetDoubleArrayRegion(a, 0, static_cast<jsize>(out.size()), out.data());
    return out;
}

/// A camera's settings from the port's cfg, as 11 doubles: target x, y, z, yaw, pitch, dist, fov, W, H, cx, cy (NaN
/// for a principal point the cfg does not name).
std::optional<view::CameraSpec> spec_of(const std::vector<double>& v) {
    if (v.size() < 11) return std::nullopt;
    return view::CameraSpec{.target = {v[0], v[1], v[2]},
                            .yaw = v[3],
                            .pitch = v[4],
                            .dist = v[5],
                            .fov = v[6],
                            .width = v[7],
                            .height = v[8],
                            .cx = std::isnan(v[9]) ? std::nullopt : std::optional<double>(v[9]),
                            .cy = std::isnan(v[10]) ? std::nullopt : std::optional<double>(v[10])};
}

/// A pack's canal name candidates, by source order: its finest level's label layers, read once per JVM.
struct Candidates {
    std::vector<text::CanalCandidate> all;
    double millis = 0.0;
};

std::expected<std::shared_ptr<const Candidates>, std::string> candidates_of(const std::string& path) {
    static std::mutex mutex;
    static std::map<std::string, std::shared_ptr<const Candidates>> read;
    const std::lock_guard lock(mutex);
    if (const auto it = read.find(path); it != read.end()) return it->second;
    auto source = tl::MappedSource::open(path);
    if (!source) return std::unexpected(tl::describe(source.error()));
    auto pack = tl::Pack::open(std::move(*source));
    if (!pack) return std::unexpected(tl::describe(pack.error()));
    if (pack->head() == nullptr || pack->head()->levels.empty()) return std::unexpected("the pack has no render head");
    const auto t0 = std::chrono::steady_clock::now();
    auto out = std::make_shared<Candidates>();
    tl::Zstd zstd;
    tl::TileDecoder decoder;
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    const std::uint8_t finest = pack->head()->levels.front();
    for (const tl::ChunkRef& c : pack->chunks().chunks()) {
        if (c.tile.z != finest) continue;
        Arena arena(1U << 20U, Growth::grows, "labels");
        auto tile = pack->decode(c, zstd, decoder, arena, raw, scratch);
        if (!tile) return std::unexpected(tl::describe(tile.error()));
        if (const tl::Layer* labels = tile->layer(tl::LayerId::label)) {
            text::canal_candidates(*labels, pack->head()->frame, out->all);
        }
    }
    std::ranges::sort(out->all, {}, &text::CanalCandidate::index);
    const auto [first, last] = std::ranges::unique(out->all, {}, &text::CanalCandidate::index);
    out->all.erase(first, last);
    out->millis = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    read.emplace(path, out);
    return out;
}

std::string hex_of(const std::array<float, 6>& m) {
    std::string out;
    for (const float v : m) out += std::format("{:08x} ", std::bit_cast<std::uint32_t>(v));
    return out;
}

std::string_view face_name(text::Face face) {
    switch (face) {
    case text::Face::sans: return "geist";
    case text::Face::serif: return "gelasio";
    case text::Face::serif_italic: return "gelasio-italic";
    }
    return "?";
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_app_atlas_mapcorecheck_text_TextHost_abi(JNIEnv*, jclass) { return test_text_abi; }

/// The canal names of the pack at `path` as the renders' rule draws them at a camera: `camera` as spec_of reads it,
/// then the radius and the water's level; `night` and `weather` the theme's. `candidates <n> ms <time>`, then one
/// `run <source index> <turned 0|1> <face> <weight> <the snapped matrix's six float bits> <the element>` line a name,
/// in the order the rule draws them; `error=<why>` when the pack cannot be read.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_text_TextHost_canalRule(JNIEnv* env, jclass, jstring path,
                                                                              jdoubleArray camera, jboolean night,
                                                                              jboolean weather) {
    const auto candidates = candidates_of(path_of(env, path));
    if (!candidates) return java_of(env, "error=" + candidates.error());
    const std::vector<double> v = doubles_of(env, camera);
    const auto spec = spec_of(v);
    if (!spec || v.size() < 13) return java_of(env, "error=the camera needs 13 values");
    const view::Camera cam(*spec);
    const text::CanalView canal{.camera = &cam,
                                .target_x = spec->target.x,
                                .target_z = spec->target.z,
                                .radius = v[11],
                                .pitch = spec->pitch,
                                .night = night == JNI_TRUE,
                                .weather = weather == JNI_TRUE,
                                .water_level = v[12],
                                .rest = true,
                                .seconds = 0.0};
    const auto rule = text::canal_rule((*candidates)->all, canal);
    std::string out = std::format("candidates {} ms {:.1f}\n", (*candidates)->all.size(), (*candidates)->millis);
    for (const text::RuleName& r : rule) {
        const auto font = text::resolve(r.run.family, r.run.weight, false);
        out += std::format("run {} {} {} {} {}{}\n", (*candidates)->all[r.candidate].index,
                           text::beyond(r.run.matrix, text::turn_cos) ? 1 : 0,
                           font ? face_name(font->face) : std::string_view("none"), font ? font->weight : 0,
                           hex_of(r.run.snapped_matrix()), r.run.svg());
    }
    return java_of(env, out);
}

/// One ground_text: `camera` as spec_of reads it, `geometry` p0, u and v (9 doubles); `anchor` 0 start, 1 middle,
/// 2 end. The element, or an empty string where ground_text draws none.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_text_TextHost_groundText(JNIEnv* env, jclass, jdoubleArray camera,
                                                                               jstring text, jdoubleArray geometry,
                                                                               jdouble size, jint fill, jdouble opacity,
                                                                               jint weight, jdouble spacing,
                                                                               jint anchor) {
    const auto spec = spec_of(doubles_of(env, camera));
    const std::vector<double> g = doubles_of(env, geometry);
    if (!spec || g.size() < 9) return java_of(env, "error=the camera needs 11 values, the geometry 9");
    const view::Camera cam(*spec);
    const auto run = text::ground_text(cam, utf8_of(env, text), {g[0], g[1], g[2]}, {g[3], g[4], g[5]},
                                       {g[6], g[7], g[8]}, size, static_cast<std::uint32_t>(fill) & 0xFFFFFFU, opacity,
                                       weight, spacing, static_cast<text::Anchor>(std::clamp(anchor, 0, 2)));
    return java_of(env, run ? run->svg() : std::string());
}

/// A runway's designators: `camera` as spec_of reads it, `points` x and z pairs, its ref; the elements, a line each.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_text_TextHost_runways(JNIEnv* env, jclass, jdoubleArray camera,
                                                                            jdoubleArray points, jstring ref,
                                                                            jboolean night) {
    const auto spec = spec_of(doubles_of(env, camera));
    if (!spec) return java_of(env, "error=the camera needs 11 values");
    const std::vector<double> p = doubles_of(env, points);
    text::Runway runway{.points = {}, .ref = utf8_of(env, ref)};
    for (std::size_t i = 0; i + 1 < p.size(); i += 2) runway.points.push_back({p[i], p[i + 1]});
    const view::Camera cam(*spec);
    std::string out;
    for (const text::TextRun& run : text::runway_designators(cam, runway, night == JNI_TRUE)) out += run.svg() + "\n";
    return java_of(env, out);
}

/// str.upper() of each code point alone: per code point, the number of code points of its capital form, then they.
JNIEXPORT jintArray JNICALL Java_app_atlas_mapcorecheck_text_TextHost_upper(JNIEnv* env, jclass,
                                                                            jintArray code_points) {
    std::vector<jint> in(static_cast<std::size_t>(env->GetArrayLength(code_points)));
    env->GetIntArrayRegion(code_points, 0, static_cast<jsize>(in.size()), in.data());
    std::vector<jint> out;
    out.reserve(in.size() * 2);
    std::string utf8;
    for (const jint c : in) {
        utf8.clear();
        append_utf8(utf8, static_cast<char32_t>(c));
        const std::u32string capital = text::code_points(text::upper(utf8));
        out.push_back(static_cast<jint>(capital.size()));
        for (const char32_t u : capital) out.push_back(static_cast<jint>(u));
    }
    jintArray result = env->NewIntArray(static_cast<jsize>(out.size()));
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    return result;
}

} // extern "C"
