// The world's names' sprites (ENGINE.md step 5, "Names"; labels/Names.hpp): what the frame wants is asked for by rank,
// once every two seconds at most and not once it is here; the sprites handed back are kept by id, each with a serial
// of its own, a name without glyphs as skipped; broken ones are refused whole; beyond the budget the least lately
// shown go; another density's sprites replace the store's.
#include "labels/Names.hpp"
#include "tests/base/require.hpp"

#include <doctest/doctest.h>

#include <string>
#include <vector>

namespace {

using namespace atlas::labels;

[[nodiscard]] SpriteImage image(std::uint32_t width, std::uint32_t height) {
    return {.width = width,
            .height = height,
            .pixels = std::vector<std::uint32_t>(std::size_t{width} * height, 0xFF102030U),
            .anchor_x = static_cast<std::int32_t>(width / 2),
            .anchor_y = static_cast<std::int32_t>(height - 2)};
}

[[nodiscard]] LabelSprite sprite(std::uint32_t id, std::uint32_t width, std::uint32_t height, bool large = true) {
    LabelSprite s{.id = id,
                  .measures = {.half = (static_cast<float>(width) / 2.0F) + 10.5F,
                               .pad = 10.5F,
                               .text_height = static_cast<float>(height),
                               .baseline = -7.0F},
                  .own = image(width, height)};
    if (large) s.large = image(width * 3 / 2, height * 3 / 2);
    return s;
}

[[nodiscard]] std::vector<std::uint32_t> ids(const std::vector<NameWant>& wants) {
    std::vector<std::uint32_t> out;
    out.reserve(wants.size());
    for (const NameWant& w : wants) out.push_back(w.id);
    return out;
}

} // namespace

TEST_CASE("the names a frame wants are asked for by rank, at most every two seconds, and no more once they are here") {
    NameStore store;
    CHECK_FALSE(store.wanting());
    CHECK(store.ask(0.0).empty());
    store.want({{.id = 0x8000'0001U, .style = 0, .text = "Amsterdam"},
                {.id = 0x8000'0002U, .style = 1, .text = "Zaandam"},
                {.id = 0x8000'0003U, .style = 2, .text = "Westzaan"}});
    CHECK(store.wanting());
    const std::vector<NameWant> first = store.ask(0.0);
    REQUIRE(first.size() == 3);
    CHECK(first[0].text == "Amsterdam");
    CHECK(first[1].style == 1);
    CHECK(first[2].id == 0x8000'0003U);
    // asked for: not again before two seconds have gone
    CHECK(store.ask(1.0).empty());
    // one comes; the frame still wants the other two (and a new one), which are asked for again in their time
    std::vector<LabelSprite> one;
    one.push_back(sprite(0x8000'0001U, 40, 20));
    REQUIRE(store.give(std::move(one), 1.5F, 12.0F, 1.0).has_value());
    CHECK(store.large_scale() == 1.5F);
    CHECK(store.floor_dp() == 12.0F);
    store.want({{.id = 0x8000'0002U, .style = 1, .text = "Zaandam"},
                {.id = 0x8000'0003U, .style = 2, .text = "Westzaan"},
                {.id = 0x8000'0004U, .style = 0, .text = "Haarlem"}});
    CHECK(ids(store.ask(1.5)) == std::vector<std::uint32_t>{0x8000'0004U});
    CHECK(ids(store.ask(2.0)) == std::vector<std::uint32_t>{0x8000'0002U, 0x8000'0003U});
    // at most 32 a call
    std::vector<NameWant> many;
    many.reserve(50);
    for (std::uint32_t i = 0; i < 50; ++i) many.push_back({.id = 0x8000'0100U + i, .style = 0, .text = "x"});
    store.want(std::move(many));
    CHECK(store.ask(10.0).size() == NameStore::most_asked);
    CHECK(store.ask(10.0).size() == 50 - NameStore::most_asked);
}

TEST_CASE("the names' sprites are kept by id, each with its own serial; a name without glyphs is skipped; a broken "
          "sprite is refused; beyond the budget the least lately shown go; another density's replace the store's") {
    NameStore store;
    CHECK(store.find(7, 0.0) == nullptr);
    std::vector<LabelSprite> two;
    two.push_back(sprite(7, 40, 20));
    two.push_back(LabelSprite{.id = 8}); // no image: the device has no glyphs for it
    REQUIRE(store.give(std::move(two), 1.5F, 0.0F, 0.0).has_value());
    const NameSprite* seven = store.find(7, 0.0);
    ATLAS_REQUIRE(seven != nullptr);
    CHECK_FALSE(seven->skipped);
    CHECK(seven->sprite.own.width == 40);
    CHECK(seven->sprite.large.width == 60);
    CHECK(seven->sprite.own.pixels.size() == 800);
    const std::uint64_t serial = seven->serial;
    const NameSprite* eight = store.find(8, 0.0);
    ATLAS_REQUIRE(eight != nullptr);
    CHECK(eight->skipped);
    CHECK(store.size() == 2);
    CHECK(store.skipped() == 1);
    CHECK(store.texels() == 800 + (60 * 30));
    // a skipped name is here: never asked for again
    store.want({{.id = 8, .style = 0, .text = "?"}});
    CHECK(store.ask(100.0).empty());
    // handed again, a sprite replaces its id's, with another serial
    std::vector<LabelSprite> again;
    again.push_back(sprite(7, 44, 20));
    REQUIRE(store.give(std::move(again), 1.5F, 0.0F, 1.0).has_value());
    seven = store.find(7, 1.0);
    ATLAS_REQUIRE(seven != nullptr);
    CHECK(seven->sprite.own.width == 44);
    CHECK(seven->serial != serial);
    CHECK(store.size() == 2);
    // refused whole: an image whose pixels are not its size, one too large, a large image without a scale's
    std::vector<LabelSprite> broken;
    broken.push_back(sprite(9, 10, 10));
    broken.push_back(sprite(10, 10, 10));
    broken.back().own.pixels.pop_back();
    CHECK(store.give(std::move(broken), 1.5F, 0.0F, 2.0).error() == SpriteError::size);
    CHECK(store.find(9, 2.0) == nullptr);
    std::vector<LabelSprite> wide;
    wide.push_back(sprite(9, 3000, 1, false));
    CHECK(store.give(std::move(wide), 1.5F, 0.0F, 2.0).error() == SpriteError::large);
    std::vector<LabelSprite> unscaled;
    unscaled.push_back(sprite(9, 10, 10));
    CHECK(store.give(std::move(unscaled), 0.5F, 0.0F, 2.0).error() == SpriteError::scale);
    CHECK(store.size() == 2);
    // the budget: sprites of a million texels each; with the fifth the least lately shown goes
    NameStore full;
    for (std::uint32_t i = 0; i < 4; ++i) {
        std::vector<LabelSprite> big;
        big.push_back(sprite(100 + i, 1024, 1024, false));
        REQUIRE(full.give(std::move(big), 1.5F, 0.0F, static_cast<double>(i)).has_value());
    }
    CHECK(full.size() == 4);
    CHECK(full.texels() == NameStore::max_texels);
    (void)full.find(100, 10.0); // shown again: 101 is now the least lately shown
    std::vector<LabelSprite> fifth;
    fifth.push_back(sprite(104, 1024, 1024, false));
    REQUIRE(full.give(std::move(fifth), 1.5F, 0.0F, 11.0).has_value());
    CHECK(full.size() == 4);
    CHECK(full.find(101, 11.0) == nullptr);
    CHECK(full.find(100, 11.0) != nullptr);
    CHECK(full.find(104, 11.0) != nullptr);
    // another density's sprites: the store's own are forgotten
    std::vector<LabelSprite> dense;
    dense.push_back(sprite(200, 10, 10));
    REQUIRE(full.give(std::move(dense), 2.0F, 0.0F, 12.0).has_value());
    CHECK(full.size() == 1);
    CHECK(full.large_scale() == 2.0F);
    CHECK(full.texels() == 100 + (15 * 15));
}
