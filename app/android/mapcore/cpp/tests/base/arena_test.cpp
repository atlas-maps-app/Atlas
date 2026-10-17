#include "base/arena.hpp"
#include "tests/base/death.hpp"

#include <doctest/doctest.h>

#include <bit>
#include <cstddef>
#include <cstdint>
#include <memory_resource>
#include <numeric>
#include <string>
#include <vector>

namespace {

struct Record {
    float x = 0.0F;
    float y = 0.0F;
    std::uint32_t id = 0;
};

[[nodiscard]] bool aligned(const void* p, std::size_t alignment) {
    return std::bit_cast<std::uintptr_t>(p) % alignment == 0;
}

} // namespace

TEST_CASE("an arena hands out aligned storage and counts what it hands out") {
    atlas::Arena arena(1024);
    void* a = arena.allocate(3, 1);
    void* b = arena.allocate(16, 16);
    void* c = arena.allocate(8, 64);
    CHECK(aligned(b, 16));
    CHECK(aligned(c, 64));
    CHECK(static_cast<std::byte*>(b) >= static_cast<std::byte*>(a) + 3);
    CHECK(arena.used() >= 27);
    CHECK(arena.used() <= 3 + 15 + 16 + 63 + 8);
    CHECK(arena.peak() == arena.used());
}

TEST_CASE("reset frees everything at once and reuses the first block") {
    atlas::Arena arena(256);
    void* first = arena.allocate(64, 8);
    (void)arena.allocate(64, 8);
    const std::size_t peak = arena.used();
    arena.reset();
    CHECK(arena.used() == 0);
    CHECK(arena.peak() == peak);
    CHECK(arena.allocate(64, 8) == first);
}

TEST_CASE("a growing arena takes new blocks, and reset returns them") {
    atlas::Arena arena(128, atlas::Growth::grows, "tile build");
    for (int i = 0; i < 10; ++i) (void)arena.allocate(100, 8);
    CHECK(arena.extra_blocks() > 0);
    CHECK(arena.used() >= 1000);
    void* big = arena.allocate(10000, 256);
    CHECK(aligned(big, 256));
    arena.reset();
    CHECK(arena.extra_blocks() == 0);
    CHECK(arena.used() == 0);
}

TEST_CASE("std::pmr containers allocate from an arena") {
    atlas::Arena arena(4096);
    std::pmr::vector<int> numbers(&arena);
    for (int i = 0; i < 100; ++i) numbers.push_back(i);
    CHECK(std::accumulate(numbers.begin(), numbers.end(), 0) == 4950);
    CHECK(arena.used() >= 100 * sizeof(int));
    std::pmr::string name("a string too long for the small-string buffer", &arena);
    CHECK(name.size() == 45);
}

TEST_CASE("every alignment from 1 to 64 is honoured, across block growth") {
    atlas::Arena arena(256, atlas::Growth::grows);
    std::size_t expected_at_least = 0;
    for (int i = 0; i < 40; ++i) {
        const std::size_t bytes = 8 + ((static_cast<std::size_t>(i) * 13) % 90);
        const std::size_t alignment = std::size_t{1} << (static_cast<unsigned>(i) % 7U);
        CHECK(aligned(arena.allocate(bytes, alignment), alignment));
        expected_at_least += bytes;
    }
    CHECK(arena.extra_blocks() > 0);
    CHECK(arena.used() >= expected_at_least);
}

TEST_CASE("allocate_array gives typed storage that lasts until reset") {
    atlas::Arena arena(1024);
    std::span<Record> records = arena.allocate_array<Record>(10);
    for (std::uint32_t i = 0; i < records.size(); ++i) records[i] = {.x = 1.0F, .y = 2.0F, .id = i};
    CHECK(records[9].id == 9);
    CHECK(aligned(records.data(), alignof(Record)));
}

TEST_CASE("a fixed arena that overflows ends the process with its name") {
    const auto death = atlas::test::run_in_child([] {
        atlas::Arena arena(64, atlas::Growth::fixed, "frame lists");
        (void)arena.allocate(48, 8);
        (void)arena.allocate(48, 8);
    });
    CHECK(death.aborted);
    CHECK(death.stderr_text.find("a fixed arena is full: frame lists") != std::string::npos);
}

TEST_CASE("an InplaceVector holds up to its capacity, inline") {
    atlas::InplaceVector<Record, 4> list;
    CHECK(list.empty());
    CHECK(decltype(list)::capacity() == 4);
    for (std::uint32_t i = 0; i < 4; ++i)
        CHECK(list.try_emplace_back(Record{.x = 0.0F, .y = 0.0F, .id = i}) != nullptr);
    CHECK(list.full());
    CHECK(list.try_emplace_back(Record{}) == nullptr);
    CHECK(list[3].id == 3);
    std::uint32_t sum = 0;
    for (const Record& r : list) sum += r.id;
    CHECK(sum == 6);
    list.pop_back();
    CHECK(list.size() == 3);
    CHECK(list.span().size() == 3);
    list.clear();
    CHECK(list.empty());
    const auto copy = [&] {
        atlas::InplaceVector<Record, 4> other;
        other.push_back({.x = 1.0F, .y = 1.0F, .id = 7});
        return other;
    }();
    CHECK(copy[0].id == 7);
}

TEST_CASE("an InplaceVector that overflows ends the process") {
    const auto death = atlas::test::run_in_child([] {
        atlas::InplaceVector<int, 2> list;
        list.push_back(1);
        list.push_back(2);
        list.push_back(3);
    });
    CHECK(death.aborted);
    CHECK(death.stderr_text.find("an InplaceVector is full") != std::string::npos);
}
