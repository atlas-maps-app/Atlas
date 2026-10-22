// A JSON document as typed values (tiles/Json.hpp).
#include "tiles/Json.hpp"

#include "base/pymath/PyMath.hpp"
#include "tiles/Bytes.hpp"

#include <algorithm>
#include <bit>
#include <limits>
#include <optional>
#include <string>
#include <string_view>
#include <utility>

namespace atlas::tiles {

namespace {

using JsonFault = JsonError::Fault;

/// A string's bytes in the text, by offset while the text still grows.
struct Span {
    std::size_t offset = 0;
    std::size_t size = 0;
};

/// A recursive-descent reader. A list's or a map's children are placed together once it closes, so every container's
/// children are consecutive and the root comes last. The first failure sticks.
class Parser {
public:
    Parser(std::span<const std::byte> json, JsonOptions options, std::vector<ValueNode>& nodes, std::vector<char>& text,
           std::vector<Span>& spans)
        : begin_(reinterpret_cast<const char*>(json.data())), // NOLINT: the bytes read as the characters they are
          at_(begin_), end_(begin_ + json.size()), comments_(options.comments), nodes_(&nodes), text_(&text),
          spans_(&spans) {}

    [[nodiscard]] const std::optional<JsonError>& error() const noexcept { return error_; }

    /// The document: one value, then nothing but white space. The root's node is placed last.
    bool document() {
        ValueNode root;
        if (!value(root, 0)) return false;
        space();
        if (error_) return false; // an unclosed comment after the value
        if (at_ != end_) return fail(JsonFault::syntax);
        nodes_->push_back(root);
        return true;
    }

private:
    bool fail(JsonFault f) {
        if (!error_) error_ = JsonError{.fault = f, .at = static_cast<std::size_t>(at_ - begin_)};
        at_ = end_;
        return false;
    }

    /// White space, and with comments on, the comments among it (an unclosed `/*` is a truncated document).
    void space() {
        for (;;) {
            while (at_ < end_ && (*at_ == ' ' || *at_ == '\t' || *at_ == '\n' || *at_ == '\r')) ++at_;
            if (!comments_ || end_ - at_ < 2 || *at_ != '/') return;
            if (at_[1] == '/') {
                while (at_ < end_ && *at_ != '\n') ++at_;
            } else if (at_[1] == '*') {
                const std::string_view rest(at_ + 2, static_cast<std::size_t>(end_ - at_ - 2));
                const std::size_t close = rest.find("*/");
                if (close == std::string_view::npos) {
                    fail(JsonFault::truncated);
                    return;
                }
                at_ += 2 + close + 2;
            } else {
                return;
            }
        }
    }

    bool expect(char c) {
        space();
        if (at_ == end_) return fail(JsonFault::truncated);
        if (*at_ != c) return fail(JsonFault::syntax);
        ++at_;
        return true;
    }

    bool peek(char c) {
        space();
        return at_ < end_ && *at_ == c;
    }

    /// After a value of a list or object: true at a comma (taken), false at its end `close` (left for expect).
    bool more(char close) {
        space();
        if (at_ < end_ && *at_ == ',') {
            ++at_;
            return true;
        }
        if (at_ == end_)
            fail(JsonFault::truncated);
        else if (*at_ != close)
            fail(JsonFault::syntax);
        return false;
    }

    bool value(ValueNode& out, unsigned depth) {
        if (depth > max_value_depth) return fail(JsonFault::too_deep);
        space();
        if (at_ == end_) return fail(JsonFault::truncated);
        out = {};
        switch (*at_) {
        case '{': ++at_; return map(out, depth);
        case '[': ++at_; return list(out, depth);
        case '"': {
            out.type = ValueType::string;
            return string(out.first);
        }
        case 't': out.type = ValueType::boolean_true; return literal("true");
        case 'f': out.type = ValueType::boolean_false; return literal("false");
        case 'n': out.type = ValueType::null; return literal("null");
        default: return number(out);
        }
    }

    bool list(ValueNode& out, unsigned depth) {
        std::vector<ValueNode> children;
        if (!peek(']')) {
            do {
                ValueNode c;
                if (!value(c, depth + 1)) return false;
                children.push_back(c);
            } while (more(']'));
        }
        if (!expect(']')) return false;
        place(out, ValueType::list, children);
        return true;
    }

    bool map(ValueNode& out, unsigned depth) {
        std::vector<ValueNode> children;
        if (!peek('}')) {
            do {
                std::uint32_t key = 0;
                space();
                if (at_ == end_) return fail(JsonFault::truncated);
                if (*at_ != '"') return fail(JsonFault::syntax);
                const char* const key_at = at_;
                if (!string(key) || !expect(':')) return false;
                ValueNode c;
                if (!value(c, depth + 1)) return false;
                c.key = key;
                if (!unique(children, key)) {
                    at_ = key_at;
                    return fail(JsonFault::duplicate);
                }
                children.push_back(c);
            } while (more('}'));
        }
        if (!expect('}')) return false;
        place(out, ValueType::map, children);
        return true;
    }

    /// Whether no entry of `children` has the key `key` names.
    [[nodiscard]] bool unique(std::span<const ValueNode> children, std::uint32_t key) const noexcept {
        const std::string_view k = view((*spans_)[key]);
        return std::ranges::none_of(children, [&](const ValueNode& c) { return view((*spans_)[c.key]) == k; });
    }

    [[nodiscard]] std::string_view view(Span s) const noexcept {
        return {text_->data() + s.offset, s.size}; // NOLINT: a view of the text's own bytes
    }

    void place(ValueNode& out, ValueType type, std::span<const ValueNode> children) {
        out.type = type;
        out.first = static_cast<std::uint32_t>(nodes_->size());
        out.count = static_cast<std::uint32_t>(children.size());
        nodes_->insert(nodes_->end(), children.begin(), children.end());
    }

    /// Four hexadecimal digits of a \u escape, before `stop` (the string's closing quote).
    bool hex4(std::uint32_t& out, const char* stop) {
        if (stop - at_ < 4) return fail(JsonFault::syntax);
        out = 0;
        for (int i = 0; i < 4; ++i) {
            const char c = *at_++;
            std::uint32_t d = 0;
            if (c >= '0' && c <= '9')
                d = static_cast<std::uint32_t>(c - '0');
            else if (c >= 'a' && c <= 'f')
                d = static_cast<std::uint32_t>(c - 'a' + 10);
            else if (c >= 'A' && c <= 'F')
                d = static_cast<std::uint32_t>(c - 'A' + 10);
            else
                return fail(JsonFault::syntax);
            out = (out << 4U) | d;
        }
        return true;
    }

    void append_utf8(std::uint32_t cp) {
        std::vector<char>& t = *text_;
        if (cp < 0x80U) {
            t.push_back(static_cast<char>(cp));
        } else if (cp < 0x800U) {
            t.push_back(static_cast<char>(0xC0U | (cp >> 6U)));
            t.push_back(static_cast<char>(0x80U | (cp & 0x3FU)));
        } else if (cp < 0x10000U) {
            t.push_back(static_cast<char>(0xE0U | (cp >> 12U)));
            t.push_back(static_cast<char>(0x80U | ((cp >> 6U) & 0x3FU)));
            t.push_back(static_cast<char>(0x80U | (cp & 0x3FU)));
        } else {
            t.push_back(static_cast<char>(0xF0U | (cp >> 18U)));
            t.push_back(static_cast<char>(0x80U | ((cp >> 12U) & 0x3FU)));
            t.push_back(static_cast<char>(0x80U | ((cp >> 6U) & 0x3FU)));
            t.push_back(static_cast<char>(0x80U | (cp & 0x3FU)));
        }
    }

    /// A string, decoded into the text; `index` is its place among the strings.
    bool string(std::uint32_t& index) {
        if (!expect('"')) return false;
        const char* const start = at_;
        while (true) {
            if (at_ == end_) return fail(JsonFault::truncated);
            const auto c = static_cast<unsigned char>(*at_);
            if (c == '"') break;
            if (c < 0x20U) return fail(JsonFault::syntax); // a control character must be escaped
            if (c == '\\') {
                ++at_;
                if (at_ == end_) return fail(JsonFault::truncated);
            }
            ++at_;
        }
        const char* const stop = at_; // the closing quote
        const std::string_view raw(start, static_cast<std::size_t>(stop - start));
        if (!valid_utf8(std::as_bytes(std::span(raw.data(), raw.size())))) {
            at_ = start;
            return fail(JsonFault::bad_utf8);
        }
        const std::size_t offset = text_->size();
        at_ = start;
        while (at_ < stop) {
            const char c = *at_++;
            if (c != '\\') {
                text_->push_back(c);
                continue;
            }
            const char e = *at_++;
            switch (e) {
            case '"': text_->push_back('"'); break;
            case '\\': text_->push_back('\\'); break;
            case '/': text_->push_back('/'); break;
            case 'b': text_->push_back('\b'); break;
            case 'f': text_->push_back('\f'); break;
            case 'n': text_->push_back('\n'); break;
            case 'r': text_->push_back('\r'); break;
            case 't': text_->push_back('\t'); break;
            case 'u': {
                std::uint32_t cp = 0;
                if (!hex4(cp, stop)) return false;
                if (cp >= 0xD800U && cp <= 0xDBFFU) {
                    // a high surrogate: its low one must follow
                    std::uint32_t low = 0;
                    if (stop - at_ < 6 || at_[0] != '\\' || at_[1] != 'u') return fail(JsonFault::bad_utf8);
                    at_ += 2;
                    if (!hex4(low, stop)) return false;
                    if (low < 0xDC00U || low > 0xDFFFU) return fail(JsonFault::bad_utf8);
                    cp = 0x10000U + ((cp - 0xD800U) << 10U) + (low - 0xDC00U);
                } else if (cp >= 0xDC00U && cp <= 0xDFFFU) {
                    return fail(JsonFault::bad_utf8); // a lone low surrogate
                }
                append_utf8(cp);
                break;
            }
            default: return fail(JsonFault::syntax);
            }
        }
        ++at_; // the closing quote
        index = static_cast<std::uint32_t>(spans_->size());
        spans_->push_back({.offset = offset, .size = text_->size() - offset});
        return true;
    }

    /// A number (RFC 8259's grammar): an int where it has neither a point nor an exponent, else a float.
    bool number(ValueNode& out) {
        bool negative = false;
        if (*at_ == '-') {
            negative = true;
            ++at_;
        }
        const char* const whole_at = at_;
        if (at_ == end_ || *at_ < '0' || *at_ > '9')
            return fail(at_ == end_ ? JsonFault::truncated : JsonFault::syntax);
        if (*at_ == '0') {
            ++at_;
        } else {
            while (at_ < end_ && *at_ >= '0' && *at_ <= '9') ++at_;
        }
        const std::string_view whole(whole_at, static_cast<std::size_t>(at_ - whole_at));
        bool integer = true;
        std::string_view fraction;
        std::int64_t exponent = 0;
        if (at_ < end_ && *at_ == '.') {
            integer = false;
            const char* const fraction_at = ++at_;
            while (at_ < end_ && *at_ >= '0' && *at_ <= '9') ++at_;
            if (at_ == fraction_at) return fail(at_ == end_ ? JsonFault::truncated : JsonFault::syntax);
            fraction = std::string_view(fraction_at, static_cast<std::size_t>(at_ - fraction_at));
        }
        if (at_ < end_ && (*at_ == 'e' || *at_ == 'E')) {
            integer = false;
            ++at_;
            bool minus = false;
            if (at_ < end_ && (*at_ == '+' || *at_ == '-')) minus = *at_++ == '-';
            const char* const digits = at_;
            constexpr std::int64_t cap = 1'000'000'000'000'000; // decimal_to_double's limit
            while (at_ < end_ && *at_ >= '0' && *at_ <= '9') {
                exponent = std::min(cap, (exponent * 10) + (*at_ - '0'));
                ++at_;
            }
            if (at_ == digits) return fail(at_ == end_ ? JsonFault::truncated : JsonFault::syntax);
            if (minus) exponent = -exponent;
        }
        if (integer) {
            if (whole.size() > 19) return fail(JsonFault::bad_number);
            std::uint64_t v = 0;
            for (const char c : whole) v = (v * 10) + static_cast<std::uint64_t>(c - '0'); // 19 digits fit in 64 bits
            const std::uint64_t limit = negative ? std::uint64_t{1} << 63U : (std::uint64_t{1} << 63U) - 1;
            if (v > limit) return fail(JsonFault::bad_number);
            const std::int64_t n = negative ? static_cast<std::int64_t>(~v + 1) : static_cast<std::int64_t>(v);
            const bool small =
                n >= std::numeric_limits<std::int32_t>::min() && n <= std::numeric_limits<std::int32_t>::max();
            out.type = small ? ValueType::int32 : ValueType::int64;
            out.bits = static_cast<std::uint64_t>(n);
            return true;
        }
        double v = 0.0;
        if (fraction.empty()) {
            v = pymath::decimal_to_double(whole, exponent);
        } else {
            digits_.assign(whole);
            digits_.append(fraction);
            v = pymath::decimal_to_double(digits_, exponent - static_cast<std::int64_t>(fraction.size()));
        }
        out.type = ValueType::real;
        out.bits = std::bit_cast<std::uint64_t>(negative ? -v : v);
        return true;
    }

    bool literal(std::string_view word) {
        if (static_cast<std::size_t>(end_ - at_) < word.size()) return fail(JsonFault::truncated);
        if (std::string_view(at_, word.size()) != word) return fail(JsonFault::syntax);
        at_ += word.size();
        return true;
    }

    const char* begin_;
    const char* at_;
    const char* end_;
    bool comments_ = false;
    std::vector<ValueNode>* nodes_;
    std::vector<char>* text_;
    std::vector<Span>* spans_;
    std::string digits_;
    std::optional<JsonError> error_;
};

} // namespace

std::string_view name_of(JsonError::Fault fault) noexcept {
    switch (fault) {
    case JsonFault::truncated: return "truncated";
    case JsonFault::syntax: return "syntax";
    case JsonFault::bad_utf8: return "bad_utf8";
    case JsonFault::bad_number: return "bad_number";
    case JsonFault::duplicate: return "duplicate";
    case JsonFault::too_deep: return "too_deep";
    }
    return "unknown";
}

std::expected<JsonValue, JsonError> read_json(std::span<const std::byte> json, JsonOptions options) {
    JsonValue out;
    std::vector<Span> spans;
    Parser p(json, options, out.nodes_, out.text_, spans);
    if (!p.document()) return std::unexpected(p.error().value_or(JsonError{}));
    out.root_ = static_cast<std::uint32_t>(out.nodes_.size() - 1);
    out.strings_.reserve(spans.size());
    for (const Span& s : spans) {
        out.strings_.emplace_back(out.text_.data() + s.offset, s.size); // NOLINT: a view of the text's own bytes
    }
    return out;
}

} // namespace atlas::tiles
