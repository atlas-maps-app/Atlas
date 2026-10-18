// The two-sided stencil of a graphics pipeline (gpu/Pipelines.hpp, GraphicsDesc::stencil_back): the depth and stencil
// state make_graphics gives the driver (depth_stencil_of). With stencil_back empty, as every pipeline made before it
// leaves it (none names it), the state is the one make_graphics built before the field existed, member for member: the
// depth test, write and op, the stencil test, and the front's stencil state on both faces. With it set, the back faces
// take it and nothing else changes. No device is needed: the state is plain data.
#include "gpu/Pipelines.hpp"

#include <doctest/doctest.h>

#include <array>
#include <cstdint>

using namespace atlas;

namespace {

/// The state make_graphics built before stencil_back: the front's stencil state on both faces.
vk::PipelineDepthStencilStateCreateInfo before(const gpu::GraphicsDesc& d) {
    return {.depthTestEnable = d.depth_test ? vk::True : vk::False,
            .depthWriteEnable = d.depth_write ? vk::True : vk::False,
            .depthCompareOp = d.depth_op,
            .stencilTestEnable = d.stencil_test ? vk::True : vk::False,
            .front = d.stencil,
            .back = d.stencil};
}

/// A small generator (splitmix64) for the descs' fields.
struct Draws {
    std::uint64_t s;
    std::uint64_t next() {
        std::uint64_t z = (s += 0x9E37'79B9'7F4A'7C15ULL);
        z = (z ^ (z >> 30U)) * 0xBF58'476D'1CE4'E5B9ULL;
        z = (z ^ (z >> 27U)) * 0x94D0'49BB'1331'11EBULL;
        return z ^ (z >> 31U);
    }
    std::uint32_t below(std::uint32_t n) { return static_cast<std::uint32_t>(next() % n); }
};

constexpr std::array ops{vk::StencilOp::eKeep,
                         vk::StencilOp::eZero,
                         vk::StencilOp::eReplace,
                         vk::StencilOp::eIncrementAndClamp,
                         vk::StencilOp::eDecrementAndClamp,
                         vk::StencilOp::eInvert,
                         vk::StencilOp::eIncrementAndWrap,
                         vk::StencilOp::eDecrementAndWrap};
constexpr std::array compares{vk::CompareOp::eNever,          vk::CompareOp::eLess,    vk::CompareOp::eEqual,
                              vk::CompareOp::eLessOrEqual,    vk::CompareOp::eGreater, vk::CompareOp::eNotEqual,
                              vk::CompareOp::eGreaterOrEqual, vk::CompareOp::eAlways};

vk::StencilOpState state(Draws& r) {
    return {.failOp = ops[r.below(ops.size())],
            .passOp = ops[r.below(ops.size())],
            .depthFailOp = ops[r.below(ops.size())],
            .compareOp = compares[r.below(compares.size())],
            .compareMask = r.below(256),
            .writeMask = r.below(256),
            .reference = r.below(256)};
}

gpu::GraphicsDesc desc(Draws& r) {
    gpu::GraphicsDesc d;
    d.depth_test = r.below(2) != 0;
    d.depth_write = r.below(2) != 0;
    d.depth_op = compares[r.below(compares.size())];
    d.stencil_test = r.below(2) != 0;
    d.stencil = state(r);
    return d;
}

} // namespace

TEST_CASE("a pipeline without stencil_back gets the depth and stencil state it got before the field existed") {
    Draws r{20261018};
    // the frame's own: the composite and the overdraw twins (no depth, no stencil), and the defaults
    CHECK(gpu::depth_stencil_of(gpu::GraphicsDesc{}) == before(gpu::GraphicsDesc{}));
    for (int i = 0; i < 10'000; ++i) {
        const gpu::GraphicsDesc d = desc(r);
        const vk::PipelineDepthStencilStateCreateInfo got = gpu::depth_stencil_of(d);
        REQUIRE(got == before(d));
        REQUIRE(got.back == got.front);
    }
}

TEST_CASE("stencil_back gives the back faces their own stencil state and changes nothing else") {
    Draws r{17};
    for (int i = 0; i < 10'000; ++i) {
        gpu::GraphicsDesc d = desc(r);
        const vk::StencilOpState back = state(r);
        d.stencil_back = back;
        const vk::PipelineDepthStencilStateCreateInfo got = gpu::depth_stencil_of(d);
        vk::PipelineDepthStencilStateCreateInfo want = before(d);
        want.back = back;
        REQUIRE(got == want);
        REQUIRE(got.front == d.stencil);
    }
    // the water's winding count (K9): increment on front faces, decrement on back faces, wrapping
    gpu::GraphicsDesc d;
    d.stencil_test = true;
    d.stencil = {.failOp = vk::StencilOp::eKeep,
                 .passOp = vk::StencilOp::eIncrementAndWrap,
                 .depthFailOp = vk::StencilOp::eIncrementAndWrap,
                 .compareOp = vk::CompareOp::eAlways,
                 .compareMask = 0,
                 .writeMask = 0x07,
                 .reference = 0};
    d.stencil_back = vk::StencilOpState{.failOp = vk::StencilOp::eKeep,
                                        .passOp = vk::StencilOp::eDecrementAndWrap,
                                        .depthFailOp = vk::StencilOp::eDecrementAndWrap,
                                        .compareOp = vk::CompareOp::eAlways,
                                        .compareMask = 0,
                                        .writeMask = 0x07,
                                        .reference = 0};
    const vk::PipelineDepthStencilStateCreateInfo got = gpu::depth_stencil_of(d);
    CHECK(got.front.passOp == vk::StencilOp::eIncrementAndWrap);
    CHECK(got.back.passOp == vk::StencilOp::eDecrementAndWrap);
    CHECK(got.stencilTestEnable == vk::True);
}
