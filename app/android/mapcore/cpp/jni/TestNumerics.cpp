// pymath's test entry points in the host build (plan-vector K3b, L0 d), for :mapcorecheck's MT/numerics tests on the
// JVM: every pymath function over arrays of arguments the tests generate, the random streams over op scripts, the
// hashes, the check (check/NumericsCheck.hpp), the platform's own sin for the planted failure, and sin's timing. They
// bind to the Kotlin object app.atlas.mapcorecheck.numerics.NumericsHost by JNI's names; none is in the phone's
// library.
#include "base/build_info.hpp"
#include "base/pymath/PyHash.hpp"
#include "base/pymath/PyMath.hpp"
#include "base/pymath/PyRandom.hpp"
#include "check/NumericsCheck.hpp"

#include <jni.h>

#include <array>
#include <bit>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace {

namespace pm = atlas::pymath;

constexpr std::int64_t error_mark = static_cast<std::int64_t>(0xE5E5E5E5E5E5E5E5U);

std::vector<double> doubles(JNIEnv* env, jdoubleArray a) {
    std::vector<double> v(a != nullptr ? static_cast<std::size_t>(env->GetArrayLength(a)) : 0U);
    if (!v.empty()) {
        env->GetDoubleArrayRegion(a, 0, static_cast<jsize>(v.size()), v.data());
    }
    return v;
}

std::vector<std::int64_t> longs(JNIEnv* env, jlongArray a) {
    std::vector<std::int64_t> v(a != nullptr ? static_cast<std::size_t>(env->GetArrayLength(a)) : 0U);
    if (!v.empty()) {
        env->GetLongArrayRegion(a, 0, static_cast<jsize>(v.size()), reinterpret_cast<jlong*>(v.data()));
    }
    return v;
}

std::vector<std::int32_t> ints(JNIEnv* env, jintArray a) {
    std::vector<std::int32_t> v(a != nullptr ? static_cast<std::size_t>(env->GetArrayLength(a)) : 0U);
    if (!v.empty()) {
        env->GetIntArrayRegion(a, 0, static_cast<jsize>(v.size()), reinterpret_cast<jint*>(v.data()));
    }
    return v;
}

/// The UTF-8 bytes of each byte[] of a byte[][].
std::vector<std::string> texts(JNIEnv* env, jobjectArray a) {
    std::vector<std::string> out;
    const jsize n = a != nullptr ? env->GetArrayLength(a) : 0;
    out.reserve(static_cast<std::size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        // NOLINTNEXTLINE(cppcoreguidelines-pro-type-static-cast-downcast): JNI's own types, a byte[] element
        auto* bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(a, i));
        std::string s(static_cast<std::size_t>(env->GetArrayLength(bytes)), '\0');
        env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(s.size()), reinterpret_cast<jbyte*>(s.data()));
        env->DeleteLocalRef(bytes);
        out.push_back(std::move(s));
    }
    return out;
}

void put(JNIEnv* env, jdoubleArray a, const std::vector<double>& v) {
    env->SetDoubleArrayRegion(a, 0, static_cast<jsize>(v.size()), v.data());
}

void put(JNIEnv* env, jlongArray a, const std::vector<std::int64_t>& v) {
    env->SetLongArrayRegion(a, 0, static_cast<jsize>(v.size()), reinterpret_cast<const jlong*>(v.data()));
}

void put(JNIEnv* env, jbyteArray a, const std::vector<std::int8_t>& v) {
    env->SetByteArrayRegion(a, 0, static_cast<jsize>(v.size()), reinterpret_cast<const jbyte*>(v.data()));
}

/// The error byte the tests read: 0 for a value, else the Error's index + 1.
template <class T>
std::int8_t error_code(const pm::Result<T>& r) {
    return r ? std::int8_t{0} : static_cast<std::int8_t>(static_cast<int>(r.error()) + 1);
}

/// A double result, or NaN with its error code.
double value(const pm::Result<double>& r, std::int8_t& error) {
    error = error_code(r);
    return r ? *r : std::nan("");
}

[[nodiscard]] pm::Compat compat(jint minor) { return pm::Compat{static_cast<int>(minor)}; }

/// Kotlin's Double.toInt(): truncation, saturating at Int's limits, 0 for NaN (C++'s cast would be undefined there).
[[nodiscard]] int kotlin_to_int(double x) {
    if (std::isnan(x)) {
        return 0;
    }
    if (x >= 2147483647.0) {
        return 2147483647;
    }
    if (x <= -2147483648.0) {
        return -2147483647 - 1;
    }
    return static_cast<int>(x);
}

[[nodiscard]] std::int64_t bits_of(double x) { return std::bit_cast<std::int64_t>(x); }
[[nodiscard]] double double_of(std::int64_t b) { return std::bit_cast<double>(b); }

} // namespace

extern "C" {

/// StrictMath: fn 0..14 is sin, cos, tan, asin, acos, atan, atan2(x, y), exp, log, log10, pow(x, y), cbrt, expm1,
/// log1p, sinh, over x (and y).
JNIEXPORT void JNICALL Java_app_atlas_mapcorecheck_numerics_NumericsHost_strict(JNIEnv* env, jclass, jint fn,
                                                                                jdoubleArray xs, jdoubleArray ys,
                                                                                jdoubleArray out) {
    const std::vector<double> x = doubles(env, xs);
    const std::vector<double> y = doubles(env, ys);
    std::vector<double> r(x.size());
    for (std::size_t i = 0; i < x.size(); ++i) {
        switch (fn) {
        case 0: r[i] = pm::strict::sin(x[i]); break;
        case 1: r[i] = pm::strict::cos(x[i]); break;
        case 2: r[i] = pm::strict::tan(x[i]); break;
        case 3: r[i] = pm::strict::asin(x[i]); break;
        case 4: r[i] = pm::strict::acos(x[i]); break;
        case 5: r[i] = pm::strict::atan(x[i]); break;
        case 6: r[i] = pm::strict::atan2(x[i], y[i]); break;
        case 7: r[i] = pm::strict::exp(x[i]); break;
        case 8: r[i] = pm::strict::log(x[i]); break;
        case 9: r[i] = pm::strict::log10(x[i]); break;
        case 10: r[i] = pm::strict::pow(x[i], y[i]); break;
        case 11: r[i] = pm::strict::cbrt(x[i]); break;
        case 12: r[i] = pm::strict::expm1(x[i]); break;
        case 13: r[i] = pm::strict::log1p(x[i]); break;
        default: r[i] = pm::strict::sinh(x[i]); break;
        }
    }
    put(env, out, r);
}

/// Python's math with its errors: fn 0 sqrt, 1 asin, 2 acos, 3 exp, 4 log, 5 log10, 6 log2, 7 log(x, y), 8 x ** y,
/// 9 x % y, 10 x // y, 11 fmod, 12 hypot(x, y), 13 radians, 14 degrees, 15 ldexp(x, int(y)), 16 copysign, 17 floor,
/// 18 ceil, 19 max, 20 min, 21 frexp's mantissa, 22 round(x, int(y)), 23 isclose as 1 or 0, 24 sin, 25 sinh,
/// 26 Java's Math.max, 27 Java's Math.min.
JNIEXPORT void JNICALL Java_app_atlas_mapcorecheck_numerics_NumericsHost_python(JNIEnv* env, jclass, jint fn,
                                                                                jint minor, jdoubleArray xs,
                                                                                jdoubleArray ys, jdoubleArray out,
                                                                                jbyteArray errors) {
    const std::vector<double> x = doubles(env, xs);
    const std::vector<double> y = doubles(env, ys);
    std::vector<double> r(x.size());
    std::vector<std::int8_t> e(x.size());
    const pm::Compat c = compat(minor);
    for (std::size_t i = 0; i < x.size(); ++i) {
        const double b = y.empty() ? 0.0 : y[i];
        switch (fn) {
        case 0: r[i] = value(pm::checked::sqrt(x[i]), e[i]); break;
        case 1: r[i] = value(pm::checked::asin(x[i]), e[i]); break;
        case 2: r[i] = value(pm::checked::acos(x[i]), e[i]); break;
        case 3: r[i] = value(pm::checked::exp(x[i]), e[i]); break;
        case 4: r[i] = value(pm::checked::log(x[i]), e[i]); break;
        case 5: r[i] = value(pm::checked::log10(x[i]), e[i]); break;
        case 6: r[i] = value(pm::checked::log2(x[i]), e[i]); break;
        case 7: r[i] = value(pm::checked::log(x[i], b), e[i]); break;
        case 8: r[i] = value(pm::checked::pow(x[i], b), e[i]); break;
        case 9: r[i] = value(pm::checked::mod(x[i], b), e[i]); break;
        case 10: r[i] = value(pm::checked::floor_div(x[i], b), e[i]); break;
        case 11: r[i] = value(pm::checked::fmod(x[i], b), e[i]); break;
        case 12: r[i] = pm::hypot(x[i], b, c); break;
        case 13: r[i] = pm::radians(x[i]); break;
        case 14: r[i] = pm::degrees(x[i]); break;
        case 15: r[i] = pm::ldexp(x[i], kotlin_to_int(b)); break;
        case 16: r[i] = pm::copysign(x[i], b); break;
        case 17: r[i] = pm::floor(x[i]); break;
        case 18: r[i] = pm::ceil(x[i]); break;
        case 19: r[i] = pm::max(x[i], b); break;
        case 20: r[i] = pm::min(x[i], b); break;
        case 21: r[i] = pm::frexp(x[i]).mantissa; break;
        case 22: r[i] = pm::round(x[i], kotlin_to_int(b)); break;
        case 23: r[i] = pm::isclose(x[i], b) ? 1.0 : 0.0; break;
        case 24: r[i] = pm::sin(x[i]); break;
        case 25: r[i] = value(pm::checked::sinh(x[i]), e[i]); break;
        case 26: r[i] = pm::java::max(x[i], b); break;
        default: r[i] = pm::java::min(x[i], b); break;
        }
    }
    put(env, out, r);
    put(env, errors, e);
}

/// Python's conversions to int: fn 0 round(x), 1 int(x), 2 math.floor, 3 math.ceil, 4 frexp's exponent.
JNIEXPORT void JNICALL Java_app_atlas_mapcorecheck_numerics_NumericsHost_pythonLong(JNIEnv* env, jclass, jint fn,
                                                                                    jdoubleArray xs, jlongArray out,
                                                                                    jbyteArray errors) {
    const std::vector<double> x = doubles(env, xs);
    std::vector<std::int64_t> r(x.size());
    std::vector<std::int8_t> e(x.size());
    for (std::size_t i = 0; i < x.size(); ++i) {
        pm::Result<std::int64_t> v = 0;
        switch (fn) {
        case 0: v = pm::checked::round(x[i]); break;
        case 1: v = pm::checked::to_int(x[i]); break;
        case 2: v = pm::checked::floor_int(x[i]); break;
        case 3: v = pm::checked::ceil_int(x[i]); break;
        default: v = pm::frexp_exp(x[i]); break;
        }
        e[i] = error_code(v);
        r[i] = v ? *v : 0;
    }
    put(env, out, r);
    put(env, errors, e);
}

/// Java's Math.round(double), the Kotlin writer's rounding.
JNIEXPORT void JNICALL Java_app_atlas_mapcorecheck_numerics_NumericsHost_javaRound(JNIEnv* env, jclass, jdoubleArray xs,
                                                                                   jlongArray out) {
    const std::vector<double> x = doubles(env, xs);
    std::vector<std::int64_t> r(x.size());
    for (std::size_t i = 0; i < x.size(); ++i) {
        r[i] = pm::java::round(x[i]);
    }
    put(env, out, r);
}

/// Python's int arithmetic: fn 0 a % b, 1 a // b, 2 round(a, int(b)).
JNIEXPORT void JNICALL Java_app_atlas_mapcorecheck_numerics_NumericsHost_intOps(JNIEnv* env, jclass, jint fn,
                                                                                jlongArray as, jlongArray bs,
                                                                                jlongArray out, jbyteArray errors) {
    const std::vector<std::int64_t> a = longs(env, as);
    const std::vector<std::int64_t> b = longs(env, bs);
    std::vector<std::int64_t> r(a.size());
    std::vector<std::int8_t> e(a.size());
    for (std::size_t i = 0; i < a.size(); ++i) {
        pm::Result<std::int64_t> v = 0;
        switch (fn) {
        case 0: v = pm::checked::mod(a[i], b[i]); break;
        case 1: v = pm::checked::floor_div(a[i], b[i]); break;
        default: v = pm::round(a[i], static_cast<int>(b[i])); break;
        }
        e[i] = error_code(v);
        r[i] = v ? *v : 0;
    }
    put(env, out, r);
    put(env, errors, e);
}

/// Functions of vectors, over consecutive groups of `sizes` values: fn 0 hypot(*v), 1 dist(p, q) (each group p then
/// q), 2 sum(v), 3 sum by Summer.
JNIEXPORT void JNICALL Java_app_atlas_mapcorecheck_numerics_NumericsHost_vectors(JNIEnv* env, jclass, jint fn,
                                                                                 jint minor, jdoubleArray values,
                                                                                 jintArray sizes, jdoubleArray out) {
    const std::vector<double> v = doubles(env, values);
    const std::vector<std::int32_t> n = ints(env, sizes);
    std::vector<double> r(n.size());
    const pm::Compat c = compat(minor);
    std::size_t at = 0;
    for (std::size_t g = 0; g < n.size(); ++g) {
        const auto size = static_cast<std::size_t>(n[g]);
        const std::span<const double> group(v.data() + at, fn == 1 ? 2 * size : size);
        switch (fn) {
        case 0: r[g] = pm::hypot(group, c); break;
        case 1: r[g] = pm::dist(group.first(size), group.subspan(size), c); break;
        case 2: r[g] = pm::sum(group, c); break;
        default: {
            pm::Summer s(c);
            for (const double x : group) {
                s.add(x);
            }
            r[g] = s.value();
            break;
        }
        }
        at += group.size();
    }
    put(env, out, r);
}

/// Random streams: for each seed (kind 0 an int, 1 an unsigned 64-bit int, 2 a float's bits), the script `ops` with
/// three arguments each in `args` (doubles as their bits): 0 random, 1 uniform(a, b), 2 gauss(mu, sigma), 3
/// randbelow(n), 4 randrange(start, stop, step), 5 randint(a, b), 6 getrandbits(k), 7 choice's index in n, 8
/// shuffle(range(n)) (n results), 9 genrand_uint32, 10 randrange(stop). Results go to `out` as longs (doubles as their
/// bits, errors as the error mark); returns how many.
JNIEXPORT jint JNICALL Java_app_atlas_mapcorecheck_numerics_NumericsHost_randomStreams(
    JNIEnv* env, jclass, jintArray kinds, jlongArray seeds, jintArray ops, jlongArray args, jlongArray out) {
    const std::vector<std::int32_t> kind = ints(env, kinds);
    const std::vector<std::int64_t> seed = longs(env, seeds);
    const std::vector<std::int32_t> op = ints(env, ops);
    const std::vector<std::int64_t> arg = longs(env, args);
    std::vector<std::int64_t> r;
    r.reserve(static_cast<std::size_t>(env->GetArrayLength(out)));
    for (std::size_t s = 0; s < seed.size(); ++s) {
        pm::PyRandom rnd(0);
        if (kind[s] == 0) {
            rnd.seed(seed[s]);
        } else if (kind[s] == 1) {
            rnd.seed_unsigned(static_cast<std::uint64_t>(seed[s]));
        } else if (!rnd.seed_float(double_of(seed[s]))) {
            r.push_back(error_mark);
            continue;
        }
        for (std::size_t j = 0; j < op.size(); ++j) {
            const std::int64_t a = arg[3 * j];
            const std::int64_t b = arg[3 * j + 1];
            const std::int64_t c = arg[3 * j + 2];
            switch (op[j]) {
            case 0: r.push_back(bits_of(rnd.random())); break;
            case 1: r.push_back(bits_of(rnd.uniform(double_of(a), double_of(b)))); break;
            case 2: r.push_back(bits_of(rnd.gauss(double_of(a), double_of(b)))); break;
            case 3: r.push_back(rnd.randbelow(a)); break;
            case 4: {
                const auto v = rnd.checked_randrange(a, b, c);
                r.push_back(v ? *v : error_mark);
                break;
            }
            case 5: r.push_back(rnd.randint(a, b)); break;
            case 6: r.push_back(static_cast<std::int64_t>(rnd.getrandbits(static_cast<int>(a)))); break;
            case 7: {
                const auto v = rnd.checked_choice_index(static_cast<std::size_t>(a));
                r.push_back(v ? static_cast<std::int64_t>(*v) : error_mark);
                break;
            }
            case 8: {
                std::vector<std::int64_t> deck(static_cast<std::size_t>(a));
                for (std::size_t k = 0; k < deck.size(); ++k) {
                    deck[k] = static_cast<std::int64_t>(k);
                }
                rnd.shuffle(std::span(deck));
                r.insert(r.end(), deck.begin(), deck.end());
                break;
            }
            case 9: r.push_back(rnd.genrand_uint32()); break;
            default: {
                const auto v = rnd.checked_randrange(a);
                r.push_back(v ? *v : error_mark);
                break;
            }
            }
        }
    }
    put(env, out, r);
    return static_cast<jint>(r.size());
}

/// Streams from big int seeds: each seed is `counts[s]` 32-bit words of `words` (least significant first); `draws`
/// random() results each, as bits.
JNIEXPORT void JNICALL Java_app_atlas_mapcorecheck_numerics_NumericsHost_randomBig(JNIEnv* env, jclass, jintArray words,
                                                                                   jintArray counts, jint draws,
                                                                                   jlongArray out) {
    const std::vector<std::int32_t> w = ints(env, words);
    const std::vector<std::int32_t> n = ints(env, counts);
    std::vector<std::int64_t> r;
    r.reserve(n.size() * static_cast<std::size_t>(draws > 0 ? draws : 0));
    std::size_t at = 0;
    for (const std::int32_t count : n) {
        std::vector<std::uint32_t> magnitude(static_cast<std::size_t>(count));
        for (auto& m : magnitude) {
            m = static_cast<std::uint32_t>(w[at++]);
        }
        pm::PyRandom rnd(0);
        rnd.seed_words(magnitude);
        for (jint d = 0; d < draws; ++d) {
            r.push_back(bits_of(rnd.random()));
        }
    }
    put(env, out, r);
}

/// hash() of ints.
JNIEXPORT void JNICALL Java_app_atlas_mapcorecheck_numerics_NumericsHost_hashLongs(JNIEnv* env, jclass, jlongArray vs,
                                                                                   jlongArray out) {
    const std::vector<std::int64_t> v = longs(env, vs);
    std::vector<std::int64_t> r(v.size());
    for (std::size_t i = 0; i < v.size(); ++i) {
        r[i] = pm::hash_int(v[i]);
    }
    put(env, out, r);
}

/// hash() of floats, with their errors.
JNIEXPORT void JNICALL Java_app_atlas_mapcorecheck_numerics_NumericsHost_hashDoubles(JNIEnv* env, jclass,
                                                                                     jdoubleArray vs, jlongArray out,
                                                                                     jbyteArray errors) {
    const std::vector<double> v = doubles(env, vs);
    std::vector<std::int64_t> r(v.size());
    std::vector<std::int8_t> e(v.size());
    for (std::size_t i = 0; i < v.size(); ++i) {
        const auto h = pm::hash_float(v[i]);
        e[i] = error_code(h);
        r[i] = h ? *h : 0;
    }
    put(env, out, r);
    put(env, errors, e);
}

/// hash() of strs (their UTF-8) and of the same bytes as bytes.
JNIEXPORT void JNICALL Java_app_atlas_mapcorecheck_numerics_NumericsHost_hashTexts(JNIEnv* env, jclass,
                                                                                   jobjectArray utf8, jint minor,
                                                                                   jlongArray strs, jlongArray bytes,
                                                                                   jbyteArray errors) {
    const std::vector<std::string> t = texts(env, utf8);
    std::vector<std::int64_t> s(t.size());
    std::vector<std::int64_t> b(t.size());
    std::vector<std::int8_t> e(t.size());
    const pm::Compat c = compat(minor);
    for (std::size_t i = 0; i < t.size(); ++i) {
        const auto h = pm::hash_str(t[i], c);
        e[i] = error_code(h);
        s[i] = h ? *h : 0;
        b[i] = pm::hash_bytes(std::as_bytes(std::span(t[i].data(), t[i].size())), c);
    }
    put(env, strs, s);
    put(env, bytes, b);
    put(env, errors, e);
}

/// hash() of tuples: each tuple `sizes[t]` items, each item of `kinds` (0 an int from `ints`, 1 a float from
/// `floats`, 2 a str from `texts`, 3 a bool from `ints`), the values taken in order.
JNIEXPORT void JNICALL Java_app_atlas_mapcorecheck_numerics_NumericsHost_hashTuples(
    JNIEnv* env, jclass, jintArray sizes, jintArray kinds, jlongArray ints_in, jdoubleArray floats_in,
    jobjectArray texts_in, jint minor, jlongArray out, jbyteArray errors) {
    const std::vector<std::int32_t> n = ints(env, sizes);
    const std::vector<std::int32_t> k = ints(env, kinds);
    const std::vector<std::int64_t> iv = longs(env, ints_in);
    const std::vector<double> fv = doubles(env, floats_in);
    const std::vector<std::string> tv = texts(env, texts_in);
    std::vector<std::int64_t> r(n.size());
    std::vector<std::int8_t> e(n.size());
    const pm::Compat c = compat(minor);
    std::size_t ki = 0;
    std::size_t ii = 0;
    std::size_t fi = 0;
    std::size_t ti = 0;
    for (std::size_t t = 0; t < n.size(); ++t) {
        pm::TupleHash h;
        pm::Result<std::int64_t> lane = 0;
        for (std::int32_t item = 0; item < n[t]; ++item) {
            switch (k[ki++]) {
            case 0: lane = pm::hash_int(iv[ii++]); break;
            case 1: lane = pm::hash_float(fv[fi++]); break;
            case 2: lane = pm::hash_str(tv[ti++], c); break;
            default: lane = iv[ii++] != 0 ? 1 : 0; break;
            }
            if (lane) {
                h.add_hash(*lane);
            } else {
                e[t] = error_code(lane);
            }
        }
        r[t] = e[t] == 0 ? h.value() : 0;
    }
    put(env, out, r);
    put(env, errors, e);
}

/// The check's part hashes, in order.
JNIEXPORT jlongArray JNICALL Java_app_atlas_mapcorecheck_numerics_NumericsHost_checkParts(JNIEnv* env, jclass) {
    const atlas::check::Numerics n = atlas::check::numerics();
    std::vector<std::int64_t> v;
    v.reserve(n.parts.size());
    for (const auto& p : n.parts) {
        v.push_back(static_cast<std::int64_t>(p.hash));
    }
    jlongArray out = env->NewLongArray(static_cast<jsize>(v.size()));
    put(env, out, v);
    return out;
}

/// The check's part names and result counts, one `name count` line each.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_numerics_NumericsHost_checkNames(JNIEnv* env, jclass) {
    const atlas::check::Numerics n = atlas::check::numerics();
    std::string text;
    for (const auto& p : n.parts) {
        text += std::string(p.name) + " " + std::to_string(p.results) + "\n";
    }
    return env->NewStringUTF(text.c_str());
}

/// The check's total, as this build computes it.
JNIEXPORT jlong JNICALL Java_app_atlas_mapcorecheck_numerics_NumericsHost_checkTotal(JNIEnv*, jclass) {
    return static_cast<jlong>(atlas::check::numerics().total);
}

/// The total compiled into the core as the port's.
JNIEXPORT jlong JNICALL Java_app_atlas_mapcorecheck_numerics_NumericsHost_checkExpected(JNIEnv*, jclass) {
    return static_cast<jlong>(atlas::check::numerics_expected);
}

/// The check's log line, as the emulator prints it.
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_numerics_NumericsHost_checkLine(JNIEnv* env, jclass) {
    return env->NewStringUTF(std::string(atlas::check::numerics_line()).c_str());
}

/// The platform's own sin (this host's libm), for the planted failure L0 d must catch (plan-vector D16).
JNIEXPORT void JNICALL Java_app_atlas_mapcorecheck_numerics_NumericsHost_platformSin(JNIEnv* env, jclass,
                                                                                     jdoubleArray xs,
                                                                                     jdoubleArray out) {
    const std::vector<double> x = doubles(env, xs);
    std::vector<double> r(x.size());
    for (std::size_t i = 0; i < x.size(); ++i) {
        r[i] = std::sin(x[i]); // NOLINT(bugprone-unsafe-functions): the planted platform sin is the point
    }
    put(env, out, r);
}

/// The build line of this library (its kind: debug, bench or release; its compiler and flags).
JNIEXPORT jstring JNICALL Java_app_atlas_mapcorecheck_numerics_NumericsHost_buildInfo(JNIEnv* env, jclass) {
    return env->NewStringUTF(std::string(atlas::build_info()).c_str());
}

/// Nanoseconds for `n` calls of pymath's sin over arguments spread across [-2pi, 2pi] (one thread, this core).
JNIEXPORT jlong JNICALL Java_app_atlas_mapcorecheck_numerics_NumericsHost_timeSin(JNIEnv*, jclass, jint n) {
    const double step = 4.0 * pm::pi / static_cast<double>(n > 0 ? n : 1);
    double x = -2.0 * pm::pi;
    double sum = 0.0;
    const auto start = std::chrono::steady_clock::now();
    for (jint i = 0; i < n; ++i) {
        sum += pm::strict::sin(x);
        x += step;
    }
    const auto end = std::chrono::steady_clock::now();
    volatile double sink = sum; // keeps the loop
    (void)sink;
    return static_cast<jlong>(std::chrono::duration_cast<std::chrono::nanoseconds>(end - start).count());
}

} // extern "C"
