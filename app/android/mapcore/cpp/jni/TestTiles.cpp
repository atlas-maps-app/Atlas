// The tile reader's test entry points in the host build (plan-vector K3, L0 (b)), for :mapcorecheck's MT/tiles tests on
// the JVM: the core's schema, a pack as the core opens it (sections, chunks, rhead, style), a tile's canonical dump and
// hash, raw tile bytes decoded (the planted and the differential checks), and rings simplified at the ladder's levels.
// They bind to the Kotlin object app.atlas.mapcorecheck.tiles.TileHost by JNI's names; nothing of them is in the
// phone's library.
#include "tiles/Checksums.hpp"
#include "tiles/Container.hpp"
#include "tiles/Dump.hpp"
#include "tiles/Payload.hpp"
#include "tiles/Schema.hpp"
#include "tiles/Sections.hpp"
#include "tiles/Simplify.hpp"
#include "tiles/Source.hpp"
#include "tiles/TileBox.hpp"

#include <jni.h>

#include <bit>
#include <chrono>
#include <format>
#include <optional>
#include <span>
#include <string>
#include <utility>
#include <vector>

namespace {

using namespace atlas::tiles;

/// The layout version of these entry points; TileHost refuses another.
constexpr jint test_tiles_abi = 2;

std::string string_of(JNIEnv* env, jstring s) {
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring text(JNIEnv* env, const std::string& s) {
    // Java's modified UTF-8 differs from UTF-8 for NUL and supplementary characters: hand the bytes to a String
    // constructor instead, so the text arrives exactly.
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte*>(s.data())); // NOLINT
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID init = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    // NOLINTNEXTLINE(cppcoreguidelines-pro-type-static-cast-downcast): JNI hands a String as jobject
    auto* out = static_cast<jstring>(env->NewObject(string_class, init, bytes, charset));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    return out;
}

std::string bits(double v) { return std::format("{:016x}", std::bit_cast<std::uint64_t>(v)); }

std::string error_text(const TileError& e) {
    return std::format("error={} site={} layer={} column={} detail={}\n", name_of(e.fault), name_of(e.site),
                       name_of(e.layer), e.column_name(), e.detail);
}

/// A value as CanonicalDump writes one (strings quoted, doubles as bits).
void value_text(std::string& out, const Value& v) {
    switch (v.type()) {
    case ValueType::null: out += "null"; return;
    case ValueType::boolean_false: out += "false"; return;
    case ValueType::boolean_true: out += "true"; return;
    case ValueType::int32:
    case ValueType::int64: out += std::format("{}", v.integer().value_or(0)); return;
    case ValueType::real: out += std::format("d{:x}", std::bit_cast<std::uint64_t>(v.real().value_or(0.0))); return;
    case ValueType::string: out += std::format("\"{}\"", v.string().value_or(std::string_view{})); return;
    case ValueType::list:
        out += '<';
        for (std::size_t i = 0; i < v.size(); ++i) {
            if (i > 0) out += ',';
            value_text(out, v.child(i));
        }
        out += '>';
        return;
    case ValueType::map:
        out += '{';
        for (std::size_t i = 0; i < v.size(); ++i) {
            if (i > 0) out += ',';
            out += std::format("\"{}\":", v.key(i));
            value_text(out, v.child(i));
        }
        out += '}';
        return;
    }
}

/// The pack opened from `path`, or its error as text.
Result<Pack> open(const std::string& path) {
    auto source = MappedSource::open(path);
    if (!source) return std::unexpected(source.error());
    return Pack::open(std::move(*source));
}

/// The chunk of z/x/y decompressed, decoded into `arena` and checked against the pack (its key, its reach).
Result<Tile> load_tile(const Pack& pack, TileKey key, std::vector<std::byte>& raw, atlas::Arena& arena,
                       TileDecoder& decoder) {
    const ChunkRef* c = pack.chunks().find(key);
    if (c == nullptr) return std::unexpected(error(Fault::missing, Site::chunk, key.packed()));
    Zstd zstd;
    std::vector<std::byte> scratch;
    return pack.decode(*c, zstd, decoder, arena, raw, scratch);
}

std::span<const std::byte> buffer_bytes(JNIEnv* env, jobject buffer) {
    void* p = buffer != nullptr ? env->GetDirectBufferAddress(buffer) : nullptr;
    const jlong n = buffer != nullptr ? env->GetDirectBufferCapacity(buffer) : -1;
    if (p == nullptr || n < 0) return {};
    return {static_cast<const std::byte*>(p), static_cast<std::size_t>(n)};
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_app_atlas_mapcorecheck_tiles_TileHost_abi(JNIEnv*, jclass) { return test_tiles_abi; }

/// The layers and columns the core reads: "layer <id> <label>" then "column <name> <codec id>" for each of its columns.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_tiles_TileHost_schema(JNIEnv* env, jclass) {
    std::string out;
    for (const LayerSchema* s : schemas()) {
        out += std::format("layer {} {}\n", static_cast<int>(s->id), s->label);
        for (const ColumnSpec& c : s->columns) out += std::format("column {} {}\n", c.name, static_cast<int>(c.codec));
    }
    out += "keep";
    for (const double t : keep::tolerances) out += std::format(" {}", bits(t));
    out += std::format("\nkeep_all {} min_points {} payload {}\n", keep::all, keep::min_points, payload_version);
    return text(env, out);
}

/// A pack as the core opens it: format, sections, chunks and their reach, rhead (its views with their tolerances, its
/// bands) and style; or "error=..." when it refuses it.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_tiles_TileHost_pack(JNIEnv* env, jclass, jstring path) {
    auto pack = open(string_of(env, path));
    if (!pack) return text(env, error_text(pack.error()));
    std::string out = std::format("format {}\n", pack->format());
    for (const Section& s : pack->sections()) {
        out += std::format("section {} {} {} {} {:08x} {}\n", s.name, s.offset, s.stored, s.raw, s.crc,
                           static_cast<int>(s.method));
    }
    for (const ChunkRef& c : pack->chunks().chunks()) {
        out += std::format("chunk {}/{}/{} {} {} {} {} {}\n", c.tile.z, c.tile.x, c.tile.y, c.offset, c.stored, c.raw,
                           c.count, hex(c.sha256));
    }
    if (pack->is_render()) {
        for (const ChunkRef& c : pack->chunks().chunks()) {
            out += std::format("reach {}/{}/{} {} {} {} {} {}\n", c.tile.z, c.tile.x, c.tile.y, c.reach.box.south,
                               c.reach.box.west, c.reach.box.north, c.reach.box.east, c.reach.top_cm);
        }
    }
    if (const RenderHead* h = pack->head()) {
        out += std::format("profile {}\nlevels", static_cast<int>(h->profile));
        for (const std::uint8_t z : h->levels) out += std::format(" {}", z);
        out += std::format("\nframe {} {} {} {}\nregion {}\nengine {}\nstyle_version {}\nsource {}\n",
                           bits(h->frame.lat0), bits(h->frame.lon0), bits(h->frame.kx), bits(h->frame.kz), h->region,
                           h->engine_version, h->style_version, h->source);
        for (const HeadView& v : h->views()) {
            out += std::format("view {} {} ", v.name, v.tolerance);
            value_text(out, v.camera);
            out += '\n';
        }
        for (const HeadBand& b : h->bands()) {
            out += std::format("band {} {}\n", b.tolerance, b.up_to ? bits(*b.up_to) : std::string("last"));
        }
    }
    if (const StyleTables* s = pack->style()) {
        for (const auto& [name, value] : s->tables()) {
            out += std::format("table {} ", name);
            value_text(out, value);
            out += '\n';
        }
    }
    auto verified = pack->verify();
    out += verified ? "verify ok\n" : "verify " + error_text(verified.error());
    return text(env, out);
}

/// The canonical dump of tile z/x/y of the pack at `path`, or "error=...".
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_tiles_TileHost_dump(JNIEnv* env, jclass, jstring path, jint z,
                                                                          jint x, jint y) {
    auto pack = open(string_of(env, path));
    if (!pack) return text(env, error_text(pack.error()));
    std::vector<std::byte> raw;
    atlas::Arena arena(1U << 20U, atlas::Growth::grows, "tile");
    TileDecoder decoder;
    auto tile =
        load_tile(*pack, {static_cast<std::uint8_t>(z), static_cast<std::uint32_t>(x), static_cast<std::uint32_t>(y)},
                  raw, arena, decoder);
    if (!tile) return text(env, error_text(tile.error()));
    return text(env, canonical_dump(*tile));
}

/// The SHA-256 of tile z/x/y's canonical dump, or "error=...".
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_tiles_TileHost_hash(JNIEnv* env, jclass, jstring path, jint z,
                                                                          jint x, jint y) {
    auto pack = open(string_of(env, path));
    if (!pack) return text(env, error_text(pack.error()));
    std::vector<std::byte> raw;
    atlas::Arena arena(1U << 20U, atlas::Growth::grows, "tile");
    TileDecoder decoder;
    auto tile =
        load_tile(*pack, {static_cast<std::uint8_t>(z), static_cast<std::uint32_t>(x), static_cast<std::uint32_t>(y)},
                  raw, arena, decoder);
    if (!tile) return text(env, error_text(tile.error()));
    return text(env, hex(canonical_hash(*tile)));
}

/// Raw tile bytes (a direct buffer) decoded: the canonical dump, or "error=...".
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_tiles_TileHost_decode(JNIEnv* env, jclass, jobject raw) {
    const std::span<const std::byte> bytes = buffer_bytes(env, raw);
    atlas::Arena arena(1U << 20U, atlas::Growth::grows, "tile");
    TileDecoder decoder;
    auto tile = decoder.decode(bytes, arena);
    if (!tile) return text(env, error_text(tile.error()));
    return text(env, canonical_dump(*tile));
}

/// Where each column of each layer of raw tile bytes lies: "<layer> <column> <offset> <length>" per line.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_tiles_TileHost_columns(JNIEnv* env, jclass, jobject raw) {
    const std::span<const std::byte> bytes = buffer_bytes(env, raw);
    std::string out;
    Reader h(bytes);
    h.skip(48);
    const std::uint16_t layers = h.u16le();
    h.skip(2);
    for (std::uint16_t i = 0; i < layers && h.ok(); ++i) {
        const std::uint8_t id = h.u8();
        h.skip(1);
        const std::uint16_t columns = h.u16le();
        h.skip(4);
        const std::uint32_t at = h.u32le();
        const std::uint32_t length = h.u32le();
        if (!h.ok() || at > bytes.size() || length > bytes.size() - at) break;
        Reader l(bytes.subspan(at, length));
        // the tile's strings, for the columns' names
        Reader hs(bytes.subspan(32, 8));
        const std::uint32_t s_at = hs.u32le();
        const std::uint32_t s_len = hs.u32le();
        std::vector<std::string_view> names;
        if (s_at <= bytes.size() && s_len <= bytes.size() - s_at) {
            Reader s(bytes.subspan(s_at, s_len));
            const std::uint32_t n = s.count();
            for (std::uint32_t k = 0; k < n && s.ok(); ++k) names.push_back(s.utf8());
        }
        for (std::uint16_t k = 0; k < columns && l.ok(); ++k) {
            const std::uint32_t name = l.count();
            (void)l.u8();
            const std::uint32_t n = l.count();
            const std::size_t from = at + l.position();
            l.skip(n);
            if (!l.ok()) break;
            const LayerSchema* schema = schema_of(static_cast<LayerId>(id));
            out += std::format("{} {} {} {}\n", schema != nullptr ? schema->label : "?",
                               name < names.size() ? names[name] : "?", from, n);
        }
    }
    return text(env, out);
}

/// Every ring of `column` in layer `layer` of tile z/x/y simplified at the ladder's `level`, in the pack's frame: per
/// feature its kept count, then the kept vertices' indexes. Null when the tile does not decode.
JNIEXPORT jintArray JNICALL Java_app_atlas_mapcorecheck_tiles_TileHost_simplify(JNIEnv* env, jclass, jstring path,
                                                                                jint z, jint x, jint y, jint layer,
                                                                                jstring column, jint level) {
    auto pack = open(string_of(env, path));
    if (!pack || pack->head() == nullptr) return nullptr;
    std::vector<std::byte> raw;
    atlas::Arena arena(1U << 20U, atlas::Growth::grows, "tile");
    TileDecoder decoder;
    auto tile =
        load_tile(*pack, {static_cast<std::uint8_t>(z), static_cast<std::uint32_t>(x), static_cast<std::uint32_t>(y)},
                  raw, arena, decoder);
    if (!tile) return nullptr;
    const Layer* l = tile->layer(static_cast<LayerId>(layer));
    std::vector<jint> out;
    if (l != nullptr) {
        const std::uint8_t slot = l->schema->slot(string_of(env, column));
        if (slot == 0xFF) return nullptr;
        std::vector<std::uint32_t> kept;
        for (std::uint32_t i = 0; i < l->count; ++i) {
            const RingView ring = l->ring(slot, i);
            kept.resize(ring.size());
            const Simplified s = simplify(ring, static_cast<unsigned>(level), pack->head()->frame, kept);
            out.push_back(static_cast<jint>(s.count));
            for (std::size_t k = 0; k < s.count; ++k) out.push_back(static_cast<jint>(kept[k]));
        }
    }
    jintArray array = env->NewIntArray(static_cast<jsize>(out.size()));
    env->SetIntArrayRegion(array, 0, static_cast<jsize>(out.size()), out.data());
    return array;
}

/// The ladder index a frame loads the pack at `path` at (RenderHead::tolerance_of): `view`'s when the camera snapped
/// onto one (null: none), else the band of `distance`; -1 when the head has no such view, -2 when the pack does not
/// open.
JNIEXPORT jint JNICALL Java_app_atlas_mapcorecheck_tiles_TileHost_tolerance(JNIEnv* env, jclass, jstring path,
                                                                            jstring view, jdouble distance) {
    auto pack = open(string_of(env, path));
    if (!pack || pack->head() == nullptr) return -2;
    std::optional<std::string> name;
    if (view != nullptr) name = string_of(env, view);
    const auto t = pack->head()->tolerance_of(name ? std::optional<std::string_view>(*name) : std::nullopt, distance);
    return t ? jint{*t} : -1;
}

/// Nanoseconds for decompressing, checking and decoding tile z/x/y once (the best of `repeat`), or -1.
JNIEXPORT jlong JNICALL Java_app_atlas_mapcorecheck_tiles_TileHost_decodeNanos(JNIEnv* env, jclass, jstring path,
                                                                               jint z, jint x, jint y, jint repeat) {
    auto pack = open(string_of(env, path));
    if (!pack) return -1;
    const ChunkRef* c = pack->chunks().find(
        {static_cast<std::uint8_t>(z), static_cast<std::uint32_t>(x), static_cast<std::uint32_t>(y)});
    if (c == nullptr) return -1;
    Zstd zstd;
    TileDecoder decoder;
    atlas::Arena arena(4U << 20U, atlas::Growth::grows, "tile");
    std::vector<std::byte> raw;
    std::vector<std::byte> scratch;
    jlong best = -1;
    for (jint i = 0; i < repeat; ++i) {
        const auto t0 = std::chrono::steady_clock::now();
        arena.reset();
        if (!pack->decode(*c, zstd, decoder, arena, raw, scratch)) return -1;
        const auto ns =
            std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - t0).count();
        if (best < 0 || ns < best) best = static_cast<jlong>(ns);
    }
    return best;
}

/// Every column edge and every row edge of zoom `z` (2^z + 1 of each), as the core computes them: Grid.colEdge and
/// Grid.rowEdge, columns first.
JNIEXPORT jintArray JNICALL Java_app_atlas_mapcorecheck_tiles_TileHost_edges(JNIEnv* env, jclass, jint z) {
    if (z < 0 || std::cmp_greater(z, max_zoom)) return nullptr;
    const auto zoom = static_cast<std::uint8_t>(z);
    const std::uint32_t n = (std::uint32_t{1} << zoom) + 1;
    std::vector<jint> out;
    out.reserve(2 * std::size_t{n});
    for (std::uint32_t i = 0; i < n; ++i) out.push_back(column_edge(zoom, i));
    for (std::uint32_t i = 0; i < n; ++i) out.push_back(row_edge(zoom, i));
    jintArray array = env->NewIntArray(static_cast<jsize>(out.size()));
    env->SetIntArrayRegion(array, 0, static_cast<jsize>(out.size()), out.data());
    return array;
}

} // extern "C"
