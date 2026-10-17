// Runs a fuzz target without libFuzzer, for hosts whose clang has none (Apple's): each argument that is a file is run
// once (to replay a crash from CI), and `--seconds=N` feeds random inputs and mutations of the given files for N
// seconds. Coverage-guided fuzzing runs on Linux with libFuzzer (fuzz/CMakeLists.txt); this only makes the targets
// buildable and runnable everywhere, under the same sanitizers.
#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <span>
#include <string>
#include <string_view>
#include <vector>

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* data, std::size_t size);

namespace {

std::vector<std::uint8_t> read_file(const char* path) {
    std::vector<std::uint8_t> bytes;
    std::FILE* f = std::fopen(path, "rb");
    if (f == nullptr) return bytes;
    std::array<std::uint8_t, 4096> chunk{};
    std::size_t n = 0;
    while ((n = std::fread(chunk.data(), 1, chunk.size(), f)) > 0)
        bytes.insert(bytes.end(), chunk.begin(), chunk.begin() + static_cast<std::ptrdiff_t>(n));
    (void)std::fclose(f);
    return bytes;
}

std::uint64_t next(std::uint64_t& state) {
    std::uint64_t z = (state += 0x9E3779B97F4A7C15ULL);
    z = (z ^ (z >> 30U)) * 0xBF58476D1CE4E5B9ULL;
    z = (z ^ (z >> 27U)) * 0x94D049BB133111EBULL;
    return z ^ (z >> 31U);
}

} // namespace

int main(int argc, char** argv) {
    const std::span<char*> args(argv, static_cast<std::size_t>(argc));
    long seconds = 0;
    std::vector<std::vector<std::uint8_t>> corpus;
    for (const char* arg : args.subspan(1)) {
        const std::string_view a(arg);
        if (a.starts_with("--seconds=")) {
            seconds = std::strtol(arg + 10, nullptr, 10);
        } else {
            corpus.push_back(read_file(arg));
            (void)LLVMFuzzerTestOneInput(corpus.back().data(), corpus.back().size());
        }
    }
    if (corpus.empty()) corpus.emplace_back();
    std::uint64_t state = 0x5EED;
    std::uint64_t runs = 0;
    const auto end = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
    std::vector<std::uint8_t> input;
    while (std::chrono::steady_clock::now() < end) {
        for (int batch = 0; batch < 1000; ++batch) {
            const auto& seed = corpus[next(state) % corpus.size()];
            input = seed;
            const std::uint64_t kind = next(state) % 4;
            if (kind == 0 || input.empty()) {
                input.resize(next(state) % 64);
                for (auto& b : input) b = static_cast<std::uint8_t>(next(state));
            } else if (kind == 1) {
                input[next(state) % input.size()] ^= static_cast<std::uint8_t>(1U << (next(state) % 8));
            } else if (kind == 2) {
                input.push_back(static_cast<std::uint8_t>(next(state)));
            } else {
                input.resize(next(state) % (input.size() + 1));
            }
            (void)LLVMFuzzerTestOneInput(input.data(), input.size());
            ++runs;
        }
    }
    (void)std::printf("standalone fuzz: %llu runs in %ld s, no failure\n", static_cast<unsigned long long>(runs),
                      seconds);
    return 0;
}
