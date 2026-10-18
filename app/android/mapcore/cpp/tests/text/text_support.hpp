// What the text tests share: the bundled fonts, loaded once, and the rows of Android's shaping (android_shaping.txt,
// written by mapview's TextShapingTest from Robolectric's text stack).
#pragma once

#include "text/Fonts.hpp"
#include "text/Shaper.hpp"

#include <fstream>
#include <iterator>
#include <string>
#include <vector>

namespace atlas::test {

/// The bundled fonts (ATLAS_FONTS_DIR), loaded once per run; nullptr when they cannot be read.
[[nodiscard]] inline const text::Fonts* bundled_fonts() {
    static const text::Fonts* const fonts = [] {
        static text::Fonts loaded;
        return loaded.load_folder(ATLAS_FONTS_DIR) ? &loaded : nullptr;
    }();
    return fonts;
}

/// tests/text/android_shaping.txt, whole.
[[nodiscard]] inline std::string android_shaping() {
    std::ifstream in(std::string(ATLAS_TESTS_DIR) + "/text/android_shaping.txt", std::ios::binary);
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

/// Every row of tests/text/android_shaping.txt.
[[nodiscard]] inline std::vector<text::PlatformRun> android_runs() {
    std::vector<text::PlatformRun> out;
    std::string_view rest;
    const std::string file = android_shaping();
    rest = file;
    while (!rest.empty()) {
        const std::size_t end = rest.find('\n');
        if (auto run = text::parse_platform_run(rest.substr(0, end))) out.push_back(std::move(*run));
        rest.remove_prefix(end == std::string_view::npos ? rest.size() : end + 1);
    }
    return out;
}

} // namespace atlas::test
