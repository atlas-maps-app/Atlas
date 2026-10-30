// VK facts' blur check (CP/passes/BlurCheck.hpp): its pattern's rectangles drawn into the soft shadows' mask pass,
// each in whole texels of the mask, an instance a rectangle, which the frame's own mask_fs (ground.slang) colours; and
// its grid, worked out on the GPU as shadow_grid_cs works out the frame's: the box, its rounding, and its division.

#include "shared/shadow_grid.slang"

/// The rectangles: left, top, right and bottom in the mask's texels.
[[vk::binding(0, 0)]] StructuredBuffer<int4> check_rects;
/// The grid (shared/shadow_grid.slang's ShadowGrid as words), which check_grid_cs writes.
[[vk::binding(1, 0)]] RWStructuredBuffer<uint> check_grid;

struct CheckPush {
    uint side; // the mask's texels each way
    uint box;  // the blur's box (odd)
    uint pad0;
    uint pad1;
};

[[vk::push_constant]] ConstantBuffer<CheckPush> push;

struct CheckOut {
    float4 position : SV_Position;
};

/// Rectangle `instance`'s corner for `vertex`: two triangles, left top, right top, left bottom, then left bottom,
/// right top, right bottom (a corner's bit 0 its right, bit 1 its bottom).
[shader("vertex")]
CheckOut rect_vs(uint vertex : SV_VulkanVertexID, uint instance : SV_VulkanInstanceID) {
    int4 r = check_rects[instance];
    uint c = (0x312210u >> (vertex * 4u)) & 15u;
    float x = float((c & 1u) != 0u ? r.z : r.x);
    float y = float((c & 2u) != 0u ? r.w : r.y);
    float s = float(push.side);
    CheckOut o;
    o.position = float4((x / s) * 2.0f - 1.0f, (y / s) * 2.0f - 1.0f, 0.0f, 1.0f);
    return o;
}

/// The check's grid: the whole mask its input and its output, the box, half of it and box² / 2, drawn, unfaded
/// (opacity 1), and 1 / box² divided as shadow_grid_cs divides it.
[shader("compute")]
[numthreads(1, 1, 1)]
void check_grid_cs() {
    uint box = push.box;
    for (uint i = 0u; i < 24u; ++i) {
        check_grid[i] = 0u;
    }
    check_grid[4] = asuint(1.0f);
    check_grid[5] = asuint(1.0f);
    check_grid[6] = asuint(1.0f);
    check_grid[10] = push.side;
    check_grid[11] = push.side;
    check_grid[14] = push.side;
    check_grid[15] = push.side;
    check_grid[16] = box;
    check_grid[17] = box / 2u;
    check_grid[18] = (box * box) / 2u;
    check_grid[19] = SHADOW_GRID_DRAWN;
    check_grid[20] = asuint(box > 0u ? 1.0f / float(box * box) : 0.0f);
}
