// A building tradition, typed (load/Tradition.hpp).
#include "load/Tradition.hpp"

#include <array>
#include <format>
#include <limits>

namespace atlas::load {

using tiles::Value;
using tiles::ValueType;

/// Reads a tradition's tree into the typed tradition; the first value it cannot use stops it, named.
struct TraditionReader {
    Tradition& t;
    std::optional<TraditionError> error;

    bool fail(std::string at, std::string_view what) {
        if (!error) error = TraditionError{.json = std::nullopt, .at = std::move(at), .what = what};
        return false;
    }

    [[nodiscard]] static std::string path(std::string_view at, std::string_view key) {
        return at.empty() ? std::string(key) : std::format("{}.{}", at, key);
    }
    [[nodiscard]] static std::string path(std::string_view at, std::size_t i) { return std::format("{}[{}]", at, i); }

    /// Python's truth of a map or a list: there, and not empty (null is false).
    [[nodiscard]] static bool truthy(const Value& v) noexcept {
        return (v.type() == ValueType::map || v.type() == ValueType::list) && v.size() > 0;
    }

    bool string(const Value& v, const std::string& at, std::string_view& out) {
        const auto s = v.string();
        if (!s) return fail(at, "not a string");
        out = *s;
        return true;
    }

    bool number(const Value& v, const std::string& at, double& out) {
        const auto n = v.number();
        if (!n) return fail(at, "not a number");
        out = *n;
        return true;
    }

    bool strings(const Value& v, const std::string& at, std::vector<std::string_view>& out, bool nonempty) {
        if (v.type() != ValueType::list) return fail(at, "not a list");
        if (nonempty && v.size() == 0) return fail(at, "empty: Python's choice raises");
        out.clear();
        for (std::size_t i = 0; i < v.size(); ++i) {
            std::string_view s;
            if (!string(v.child(i), path(at, i), s)) return false;
            out.push_back(s);
        }
        return true;
    }

    bool pair(const Value& v, const std::string& at, std::pair<double, double>& out) {
        if (v.type() != ValueType::list || v.size() != 2) return fail(at, "not a list of two numbers");
        return number(v.child(0), path(at, 0), out.first) && number(v.child(1), path(at, 1), out.second);
    }

    /// The value of `key` in map `m`, which must be there.
    bool need(const Value& m, std::string_view key, const std::string& at, Value& out) {
        const auto v = m.find(key);
        if (!v) return fail(path(at, key), "missing");
        out = *v;
        return true;
    }

    /// A weighted pick's options: [values..., weight] each, with `n` values.
    template <class T, class Make>
    bool options(const Value& v, const std::string& at, std::size_t n, std::vector<T>& out, Make make) {
        if (v.type() != ValueType::list) return fail(at, "not a list of options");
        if (v.size() == 0) return fail(at, "no options: Python's pick raises");
        out.clear();
        for (std::size_t i = 0; i < v.size(); ++i) {
            const Value o = v.child(i);
            const std::string here = path(at, i);
            if (o.type() != ValueType::list || o.size() != n + 1) return fail(here, "not [values..., weight]");
            std::array<std::string_view, 2> values{};
            for (std::size_t k = 0; k < n; ++k) {
                if (!string(o.child(k), path(here, k), values.at(k))) return false;
            }
            double w = 0.0;
            if (!number(o.child(n), path(here, n), w)) return false;
            out.push_back(make(values, w));
        }
        return true;
    }

    bool walls(const Value& v, const std::string& at, std::vector<WallOption>& out) {
        return options(v, at, 2, out, [](const std::array<std::string_view, 2>& s, double w) {
            return WallOption{.material = s[0], .colour = s[1], .weight = w};
        });
    }

    bool roof(const Value& v, const std::string& at, RoofEntry& out) {
        if (v.type() != ValueType::map) return fail(at, "not a roof");
        Value shapes;
        Value material;
        Value colours;
        if (!need(v, "shapes", at, shapes) || !need(v, "material", at, material) || !need(v, "colours", at, colours))
            return false;
        if (!options(shapes, path(at, "shapes"), 1, out.shapes, [](const std::array<std::string_view, 2>& s, double w) {
                return ShapeOption{.shape = s[0], .weight = w};
            }))
            return false;
        if (!string(material, path(at, "material"), out.material)) return false;
        if (!strings(colours, path(at, "colours"), out.colours, true)) return false;
        if (const auto p = v.find("pitch")) {
            double d = 0.0;
            if (!number(*p, path(at, "pitch"), d)) return false;
            out.pitch = d;
        }
        if (const auto a = v.find("terrace_across")) {
            const auto b = a->boolean();
            if (!b && !a->is_null()) return fail(path(at, "terrace_across"), "not true or false");
            out.terrace_across = b.value_or(false);
        }
        return true;
    }

    /// An entry or an era of entries; `is_entry` says which a map is (era() returns a roof as it is).
    template <class T, class Read, class IsEntry>
    bool era(const Value& v, const std::string& at, Era<T>& out, Read read, IsEntry is_entry) {
        const auto one = [&](const Value& e, const std::string& here, std::optional<T>& slot) {
            if (e.is_null()) return true; // None: the next key is tried
            T value;
            if (!(this->*read)(e, here, value)) return false;
            slot = std::move(value);
            return true;
        };
        if (v.type() != ValueType::map || is_entry(v)) return one(v, at, out.fallback);
        for (std::size_t i = 0; i < v.size(); ++i) {
            const std::string_view k = v.key(i);
            const std::string here = path(at, k);
            const bool before = k.starts_with("before:");
            if (!before && !k.starts_with("after:")) {
                if (k == "default" && !one(v.child(i), here, out.fallback)) return false;
                continue; // era() reads no other key
            }
            const std::string_view digits = k.substr(before ? 7 : 6);
            std::int64_t year = 0;
            bool ok = !digits.empty() && digits.size() <= 9;
            for (const char c : digits) {
                ok = ok && c >= '0' && c <= '9';
                year = (year * 10) + (c - '0');
            }
            if (!ok) return fail(here, "a year that is not a whole number");
            typename Era<T>::Branch b{.before = before, .year = year, .value = std::nullopt};
            if (!one(v.child(i), here, b.value)) return false;
            out.branches.push_back(std::move(b));
        }
        return true;
    }

    bool when(const Value& v, const std::string& at, RuleWhen& out) {
        if (v.type() != ValueType::map) return fail(at, "not a map");
        const auto list = [&](std::string_view key, std::optional<std::vector<std::string_view>>& slot) {
            const auto e = v.find(key);
            if (!e) return true;
            slot.emplace();
            return strings(*e, path(at, key), *slot, false);
        };
        const auto two = [&](std::string_view key, std::optional<std::pair<double, double>>& slot) {
            const auto e = v.find(key);
            if (!e) return true;
            slot.emplace();
            return pair(*e, path(at, key), *slot);
        };
        const auto one = [&](std::string_view key, std::optional<double>& slot) {
            const auto e = v.find(key);
            if (!e) return true;
            slot.emplace();
            return number(*e, path(at, key), *slot);
        };
        if (!list("kind", out.kinds) || !list("building", out.buildings) || !list("material", out.materials) ||
            !two("levels", out.levels) || !two("area", out.area) || !one("narrow", out.narrow) ||
            !one("min_h", out.min_h))
            return false;
        if (const auto y = v.find("year")) {
            const std::string here = path(at, "year");
            if (y->type() != ValueType::map) return fail(here, "not a map");
            out.year = true;
            if (const auto f = y->find("from"); f && !number(*f, path(here, "from"), out.year_from)) return false;
            if (const auto f = y->find("to"); f && !number(*f, path(here, "to"), out.year_to)) return false;
            if (const auto u = y->find("unknown")) {
                const auto b = u->boolean();
                if (!b && !u->is_null()) return fail(path(here, "unknown"), "not true or false");
                out.year_unknown = b.value_or(false);
            }
        }
        return true;
    }

    bool rule_roof(const Value& v, const std::string& at, RuleRoof& out) {
        if (v.type() != ValueType::map || v.size() == 0) return fail(at, "not a rule's roof");
        Value shape;
        if (!need(v, "shape", at, shape) || !string(shape, path(at, "shape"), out.shape)) return false;
        if (const auto r = v.find("roof_h_range")) {
            out.roof_h_range.emplace();
            if (!pair(*r, path(at, "roof_h_range"), *out.roof_h_range)) return false;
        }
        if (const auto o = v.find("orient"); o && !o->is_null()) {
            std::string_view s;
            if (!string(*o, path(at, "orient"), s)) return false;
            if (!s.empty()) out.orient = s;
        }
        const auto one = [&](std::string_view key, std::optional<double>& slot) {
            const auto e = v.find(key);
            if (!e) return true;
            slot.emplace();
            return number(*e, path(at, key), *slot);
        };
        if (!one("roof_h", out.roof_h) || !one("pitch", out.pitch)) return false;
        if (const auto m = v.find("material")) {
            out.material.emplace();
            if (!string(*m, path(at, "material"), *out.material)) return false;
        }
        if (const auto c = v.find("colours")) {
            out.colours.emplace();
            if (!strings(*c, path(at, "colours"), *out.colours, true)) return false;
        }
        return true;
    }

    /// A detail handed on whole (`windows`, `boards`, `base_band`, `chimneys`): null where Python's truth is false,
    /// else the map, with the list of strings `key` read from it (`materials`, `kinds`; none where `optional` and not
    /// there).
    bool detail(const Value& root, std::string_view name, Value& out) {
        const auto v = root.find(name);
        if (!v || v->is_null()) return true;
        if (v->type() != ValueType::map) return fail(std::string(name), "not a map or null");
        if (truthy(*v)) out = *v;
        return true;
    }

    bool read(const Value& root) {
        if (root.type() != ValueType::map) return fail("", "not a map");
        Value v;
        if (!need(root, "name", "", v) || !string(v, "name", t.name)) return false;
        if (!need(root, "says", "", v) || !string(v, "says", t.says)) return false;
        if (const auto s = root.find("says_by")) {
            if (s->type() != ValueType::map) return fail("says_by", "not a map");
            for (std::size_t i = 0; i < s->size(); ++i) {
                std::string_view says;
                if (!string(s->child(i), path("says_by", s->key(i)), says)) return false;
                t.says_by.emplace_back(s->key(i), says);
            }
        }
        if (!need(root, "floor_h", "", v) || !number(v, "floor_h", t.floor_h)) return false;
        if (!(t.floor_h > 0)) return fail("floor_h", "not above 0: Python divides by it");
        if (const auto m = root.find("modern_from"); m && !number(*m, "modern_from", t.modern_from)) return false;
        if (!need(root, "levels", "", v)) return false;
        if (v.type() != ValueType::map) return fail("levels", "not a map");
        for (std::size_t i = 0; i < v.size(); ++i) {
            const auto n = v.child(i).integer();
            if (!n || *n < std::numeric_limits<std::int32_t>::min() || *n > std::numeric_limits<std::int32_t>::max()) {
                return fail(path("levels", v.key(i)), "not a whole number of levels"); // the port's Int
            }
            t.levels.emplace_back(v.key(i), *n);
        }
        if (!v.find("default")) return fail("levels.default", "missing");
        if (const auto h = root.find("heights")) {
            if (h->type() != ValueType::map) return fail("heights", "not a map");
            for (std::size_t i = 0; i < h->size(); ++i) {
                double d = 0.0;
                if (!number(h->child(i), path("heights", h->key(i)), d)) return false;
                t.heights.emplace_back(h->key(i), d);
            }
        }
        if (!need(root, "walls", "", v)) return false;
        if (v.type() != ValueType::map) return fail("walls", "not a map");
        for (std::size_t i = 0; i < v.size(); ++i) {
            Era<std::vector<WallOption>> e;
            if (!era(v.child(i), path("walls", v.key(i)), e, &TraditionReader::walls,
                     [](const Value&) { return false; })) // a map in walls is an era (Python would pick from it)
                return false;
            t.walls.emplace_back(v.key(i), std::move(e));
        }
        if (!need(root, "roof", "", v)) return false;
        if (v.type() != ValueType::map) return fail("roof", "not a map");
        for (std::size_t i = 0; i < v.size(); ++i) {
            Era<RoofEntry> e;
            if (!era(v.child(i), path("roof", v.key(i)), e, &TraditionReader::roof,
                     [](const Value& m) { return m.find("shapes").has_value() || m.find("material").has_value(); }))
                return false;
            t.roof.emplace_back(v.key(i), std::move(e));
        }
        const auto* fallback = Tradition::entry(t.roof, "default");
        if (fallback == nullptr) return fail("roof.default", "missing");
        if (!fallback->fallback) return fail("roof.default", "can give None: Python's rf would be None");
        for (const auto& b : fallback->branches) {
            if (!b.value) return fail("roof.default", "can give None: Python's rf would be None");
        }
        if (const auto rs = root.find("rules")) {
            if (rs->type() != ValueType::list) return fail("rules", "not a list");
            for (std::size_t i = 0; i < rs->size(); ++i) {
                const Value r = rs->child(i);
                const std::string here = path("rules", i);
                if (r.type() != ValueType::map) return fail(here, "not a map");
                Rule rule;
                Value w;
                Value rf;
                if (!need(r, "when", here, w) || !when(w, path(here, "when"), rule.when)) return false;
                if (!need(r, "roof", here, rf) || !rule_roof(rf, path(here, "roof"), rule.roof)) return false;
                t.rules.push_back(std::move(rule));
            }
        }
        if (!detail(root, "windows", t.windows)) return false;
        if (!t.windows.is_null()) {
            Value k;
            if (!need(t.windows, "kinds", "windows", k) || !strings(k, "windows.kinds", t.window_kinds, false))
                return false;
            if (const auto m = t.windows.find("materials")) {
                t.window_materials.emplace();
                if (!strings(*m, "windows.materials", *t.window_materials, false)) return false;
            }
        }
        Value shutters;
        if (!detail(root, "shutters", shutters)) return false;
        if (!shutters.is_null()) {
            Shutters& s = t.shutters.emplace();
            Value k;
            Value c;
            if (!need(shutters, "kinds", "shutters", k) || !strings(k, "shutters.kinds", s.kinds, false)) return false;
            if (!need(shutters, "share", "shutters", v) || !number(v, "shutters.share", s.share)) return false;
            if (!need(shutters, "colours", "shutters", c) || !strings(c, "shutters.colours", s.colours, true))
                return false;
        }
        const auto materials = [&](std::string_view name, Value& slot, std::vector<std::string_view>& list) {
            if (!detail(root, name, slot)) return false;
            if (slot.is_null()) return true;
            Value m;
            return need(slot, "materials", std::string(name), m) && strings(m, path(name, "materials"), list, false);
        };
        if (!materials("boards", t.boards, t.board_materials) || !materials("base_band", t.base_band, t.band_materials))
            return false;
        if (!detail(root, "chimneys", t.chimneys)) return false;
        if (!t.chimneys.is_null()) {
            if (!need(t.chimneys, "share", "chimneys", v) || !number(v, "chimneys.share", t.chimney_share))
                return false;
            if (const auto k = t.chimneys.find("kind")) {
                std::string_view kind;
                if (!k->is_null() && !string(*k, "chimneys.kind", kind)) return false;
                t.party_chimneys = kind == "party";
            }
        }
        if (const auto e = root.find("eaves"); e && !number(*e, "eaves", t.eaves)) return false;
        const auto old = [&](std::string_view name, OldDetail& out) {
            const auto d = root.find(name);
            if (!d || d->is_null()) return true;
            if (const auto b = d->boolean()) {
                out.always = *b;
                return true;
            }
            if (d->type() != ValueType::map) return fail(std::string(name), "not true, false or {\"before\": year}");
            Value b;
            double year = 0.0;
            if (!need(*d, "before", std::string(name), b) || !number(b, path(name, "before"), year)) return false;
            out.before = year;
            return true;
        };
        if (!old("cornice", t.cornice) || !old("hoist", t.hoist)) return false;
        if (const auto c = root.find("towers_crenellated")) {
            const auto b = c->boolean();
            if (!b && !c->is_null()) return fail("towers_crenellated", "not true or false");
            t.towers_crenellated = b.value_or(false);
        }
        return true;
    }
};

std::expected<std::shared_ptr<const Tradition>, TraditionError> read_tradition(std::span<const std::byte> json) {
    auto tree = tiles::read_json(json);
    if (!tree) return std::unexpected(TraditionError{.json = tree.error(), .at = {}, .what = "not JSON"});
    auto t = std::make_shared<Tradition>();
    t->json_ = std::move(*tree);
    TraditionReader r{.t = *t, .error = std::nullopt};
    if (!r.read(t->json_.root())) return std::unexpected(r.error ? std::move(*r.error) : TraditionError{});
    return t;
}

} // namespace atlas::load
