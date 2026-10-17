// The core's unit tests (doctest). `atlas_tests --probe` prints the build line and the C++ feature table instead: K0's
// feature probe, recorded per toolchain (plan-vector T3).
#define DOCTEST_CONFIG_IMPLEMENT
#include "base/build_info.hpp"
#include "tests/base/end_of_run.hpp"
#include "tests/base/probe.hpp"

#include <doctest/doctest.h>

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <ranges>
#include <span>
#include <sstream>
#include <string>
#include <string_view>
#include <vector>

#if defined(__linux__)
#include <dlfcn.h>
#endif

namespace {

#if defined(__linux__)
// The library a Vulkan manifest (an ICD's or a layer's JSON) names, as the loader resolves it: a path with a slash is
// relative to the manifest, a bare name is searched for (LD_LIBRARY_PATH). Empty when the manifest cannot be read.
std::string manifest_library(const std::string& manifest) {
    std::ifstream in(manifest);
    std::stringstream text;
    text << in.rdbuf();
    const std::string json = text.str();
    const std::size_t key = json.find("\"library_path\"");
    if (key == std::string::npos) return {};
    const std::size_t open = json.find('"', json.find(':', key) + 1);
    const std::size_t close = open == std::string::npos ? open : json.find('"', open + 1);
    if (close == std::string::npos) return {};
    std::string path = json.substr(open + 1, close - open - 1);
    if (path.find('/') != std::string::npos && path.front() != '/') {
        path = manifest.substr(0, manifest.rfind('/') + 1) + path;
    }
    return path;
}

// LeakSanitizer checks at exit, after the Vulkan loader has unloaded lavapipe and the validation layer (at
// vkDestroyInstance): their process-lifetime allocations would then point into unmapped code, so neither their stacks
// nor core.yml's suppressions (by module name) could name them. Loaded first with RTLD_NODELETE, the loader, the
// driver and the layer stay mapped until exit, whoever opens and closes them. A no-op where the variables are unset
// (Android, macOS's own fallback to the emulator package).
void keep_vulkan_resident() {
    auto keep = [](const std::string& library) {
        if (!library.empty()) (void)::dlopen(library.c_str(), RTLD_NOW | RTLD_LOCAL | RTLD_NODELETE);
    };
    if (const char* loader = std::getenv("ATLAS_VULKAN_LOADER")) keep(loader);         // NOLINT(concurrency-mt-unsafe)
    if (const char* icd = std::getenv("VK_DRIVER_FILES")) keep(manifest_library(icd)); // NOLINT(concurrency-mt-unsafe)
    if (const char* layers = std::getenv("VK_ADD_LAYER_PATH")) {                       // NOLINT(concurrency-mt-unsafe)
        keep(manifest_library(std::string(layers) + "/VkLayer_khronos_validation.json"));
    }
}
#endif

std::vector<void (*)()>& releases() {
    static std::vector<void (*)()> list;
    return list;
}

} // namespace

void atlas::test::at_end_of_run(void (*release)()) { releases().push_back(release); }

int main(int argc, char** argv) {
    const std::span<char*> args(argv, static_cast<std::size_t>(argc));
    for (const char* arg : args.subspan(1)) {
        if (std::string_view(arg) == "--probe") {
            const std::string_view info = atlas::build_info();
            (void)std::printf("%.*s\n", static_cast<int>(info.size()), info.data());
            atlas::test::print_features(stdout);
            return 0;
        }
    }
#if defined(__linux__)
    keep_vulkan_resident();
#endif
    doctest::Context context(argc, argv);
    const int result = context.run();
    for (void (*release)() : releases() | std::views::reverse) release();
    releases().clear();
    return result;
}
