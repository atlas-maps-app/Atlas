// The region items' decide stage as it was before it was split by kind: items_cs, one entry point over every kind's
// jobs (the audit of 17 November, change 7: it had grown to 418,600 bytes of SPIR-V, twice shaders/spirv.budget's
// line; shaders/item/draw.slang's kinds' stages took its place). tests/item/items_vk_test.cpp runs it in place of the
// kinds' stages over the same frame's jobs and holds their records to its, byte for byte. The tests' alone: their build
// compiles it (CMakeLists.txt), and it is neither embedded in the core nor held to spirv.budget.
#include "item/draw.slang"

[shader("compute")]
[numthreads(64, 1, 1)]
void items_cs(uint3 group : SV_GroupID, uint3 thread : SV_GroupThreadID) {
    uint index = (group.y * ITEM_JOB_ROW + group.x) * 64u + thread.x;
    if (index >= push.at.x) {
        return;
    }
    uint at = index * ITEM_JOB_VECTORS;
    ItemJob j = item_job(item_jobs[at], item_jobs[at + 1u], item_jobs[at + 2u], item_jobs[at + 3u],
                         item_jobs[at + 4u]);
    ItemWords w = item_words(points_heap[j.item], points_heap[j.item + 1u], points_heap[j.item + 2u]);
    uint kind = j.flags & ITEM_JOB_KIND;
    // PLANT (budgetcheck-plant, never on budgetcheck): the towers taken out, their records left alone
    if (kind == ITEM_TOWER) {
        return;
    }
    // every record of the job's starts empty
    uint faces = item_face_count(kind, uint(w.extra));
    for (uint i = 0u; i < faces; ++i) {
        put(item_faces_out, j.faces + i, item_nothing());
    }
    for (uint i = 0u; i < item_part_count(kind); ++i) {
        put(item_parts_out, j.parts + i, item_nothing());
    }
    for (uint i = 0u; i < item_side_count(kind); ++i) {
        put(item_sides_out, j.sides + i, item_nothing());
    }
    FrameCamera c = frame.camera;
    float3 foot = item_eye(j.place, w.foot);
    float3 end_local = {w.end.x, w.foot.y, w.end.y};
    float3 end = item_eye(j.place, end_local);
    if (kind == ITEM_TOWER) {
        tower(c, j, w, foot);
    } else if (kind == ITEM_CRANE) {
        crane(c, j, w, foot);
    } else if (kind == ITEM_MAST) {
        mast(c, j, w, foot);
    } else if (kind == ITEM_GANTRY || kind == ITEM_CONVEYOR) {
        boxes(c, j, w, foot, end, kind);
    } else if (kind == ITEM_TURBINE) {
        turbine(c, j, w, foot);
    } else if (kind == ITEM_POWER) {
        power(c, j, w, foot, end);
    } else if (kind == ITEM_COASTER) {
        coaster(c, j, w, foot, end);
    } else if (kind == ITEM_WOOD) {
        wood(c, j, w, foot, points_heap[j.item + 3u], points_heap[j.item + 4u], points_heap[j.item + 5u],
             points_heap[j.item + 6u]);
    }
}
