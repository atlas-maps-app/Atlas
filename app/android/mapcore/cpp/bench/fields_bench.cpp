// The screen-wide fields' timings (plan-vector K17a, §3: the five at S1 in at most 300 ms on one Mac core, N17's 227 ms
// measured with NEON): each field's noise over S1 by the lanes and by the scalar reference, the five with their kept
// bytes on one thread and on four, and a warm start's mapping from the disk cache. With ATLAS_TIMED=1 (a quiet
// machine) the five on one thread over 300 ms fails the run; otherwise it prints. The `fields` line is VK facts'.
#include "fx/Fields.hpp"
#include "fx/Noise.hpp"

#include <nanobench.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <string>
#include <vector>

using namespace atlas::fx;

int main() {
    const char* timed = std::getenv("ATLAS_TIMED"); // NOLINT(concurrency-mt-unsafe): read once, before any thread
    const FieldSize s1 = size_of(1080, 2400, 1080.0 / 412.0);
    const Grid grid = grid_of(s1.scale, s1.scale);
    const Block frame{.left = 0, .top = 0, .width = 1080, .height = 2400};
    std::vector<Argb> out(static_cast<std::size_t>(s1.width) * s1.height);

    ankerl::nanobench::Bench bench;
    bench.title("screen-wide fields at S1, one thread").unit("frame").warmup(1).minEpochIterations(1).epochs(3);
    for (const FieldSpec& f : screen_fields) {
        const Lattice l(f.noise.seed);
        bench.run(std::string(f.name) + " noise, lanes",
                  [&] { fill_lanes(l, f.noise, grid, frame, f.need, out, s1.width); });
        bench.run(std::string(f.name) + " noise, scalar",
                  [&] { fill(l, f.noise, grid, frame, f.need, out, s1.width); });
    }
    bench.run("the five, kept, lanes",
              [&] { ankerl::nanobench::doNotOptimizeAway(make_fields(s1, MakeOptions{.threads = 1})); });
    bench.run("the five, kept, lanes, four threads",
              [&] { ankerl::nanobench::doNotOptimizeAway(make_fields(s1, MakeOptions{.threads = 4})); });

    // a warm start: the fields mapped from the disk cache and checked
    const std::filesystem::path dir = std::filesystem::temp_directory_path() / "atlas-fields-bench";
    const FieldDisk disk(dir);
    (void)disk.write(*make_fields(s1, MakeOptions{.threads = 4}));
    bench.run("a warm start: mapped from disk", [&] { ankerl::nanobench::doNotOptimizeAway(disk.read(s1)); });
    std::error_code ec;
    std::filesystem::remove_all(dir, ec);

    double best = 1e9;
    for (int run = 0; run < 5; ++run) {
        const auto t0 = std::chrono::steady_clock::now();
        ankerl::nanobench::doNotOptimizeAway(make_fields(s1, MakeOptions{.threads = 1}));
        const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        best = ms < best ? ms : best;
    }
    (void)std::printf("%s\n", fields_line(s1, 4).c_str());
    (void)std::printf("fields: the five at S1 on one thread %.1f ms (budget 300 ms)\n", best);
    if (timed != nullptr && std::string(timed) == "1" && best > 300.0) {
        (void)std::printf("fields: over budget\n");
        return 1;
    }
    return 0;
}
