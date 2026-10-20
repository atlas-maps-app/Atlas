// The box blurs (fx/Blur.hpp): up to three boxes along one axis of each rectangle a dispatch lists, as WebKit runs its
// approximation of a Gaussian. A workgroup takes 256 texels of one line of one rectangle and the texels the boxes read
// around them into workgroup memory, runs the boxes there one after another (each texel's window summed once, then
// slid along: two reads a texel), and writes the 256 back; fx::blur_cpu is the same arithmetic on the CPU, and the
// tests hold the two equal bit for bit.
//
// A box writes at p the mean of [p - left, p + right - 1]: the sum of its 8-bit texels as an integer, times 1/size in
// single precision, plus 1/2 to round to the nearest level or nothing to truncate, kept to 8 bits. Past a line's ends
// lies transparent black, or the end texel again (the dispatch's mode).
//
// atlas: exact
// atlas: variant r8 BOXES_R8=1

#ifndef BOXES_R8
#define BOXES_R8 0
#endif

// One rectangle of the dispatch (fx::BoxBlur::write_jobs fills them; gen/layouts.hpp checks the layout).
struct BoxJob {
    int4 rect;   // the rectangle in the image: x, y, width, height
    uint4 left;  // each box's reach to the left (x, y, z: boxes 0-2), and how many boxes (w: 0-3)
    uint4 right; // each box's reach to the right: box i writes at p the mean of [p - left_i, p + right_i - 1]
    float4 inv;  // 1 / each box's size (x, y, z), and what is added before truncating (w: 0.5 or 0)
};

struct BoxPush {
    uint axis;  // 0: the boxes run along x; 1: along y
    uint mode;  // MODE_EXTEND, MODE_OPAQUE
    uint count; // the dispatch's rectangles
    uint first; // the first one's index among the jobs
};

static const uint MODE_EXTEND = 1u; // past a line's ends, its end texel again (else transparent black)
static const uint MODE_OPAQUE = 2u; // every texel read as opaque before the first box (rgba8)

[[vk::push_constant]] ConstantBuffer<BoxPush> push;
#if BOXES_R8
[[vk::binding(0, 0)]] [[vk::image_format("r8")]] RWTexture2D<float> source;
[[vk::binding(1, 0)]] [[vk::image_format("r8")]] RWTexture2D<float> target;
#else
[[vk::binding(0, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> source;
[[vk::binding(1, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> target;
#endif
[[vk::binding(2, 0)]] StructuredBuffer<BoxJob> jobs;

static const uint THREADS = 64u;
static const uint SEGMENT = 256u;                // the texels a workgroup writes
static const uint REACH = 128u;                  // the most a run reads on either side (fx::BoxBlur::max_reach)
static const uint SPAN = SEGMENT + 2u * REACH;   // the texels it holds
static const float INV_255 = 0.0039215688593685627f; // 1/255 in single precision

// The line being blurred, twice: each box reads one and writes the other.
groupshared uint lines[2][SPAN];

uint4 unpack(uint w) {
    return uint4(w & 0xFFu, (w >> 8u) & 0xFFu, (w >> 16u) & 0xFFu, w >> 24u);
}

uint pack(uint4 b) {
    return b.x | (b.y << 8u) | (b.z << 16u) | (b.w << 24u);
}

// A box's mean of `sum`: times 1/size, plus the rounding's offset, truncated.
uint4 mean(uint4 sum, float inv, float offset) {
    return uint4(float4(sum) * inv + offset);
}

int2 texel_at(BoxJob job, bool along_x, int at, int line) {
    return along_x ? int2(job.rect.x + at, job.rect.y + line) : int2(job.rect.x + line, job.rect.y + at);
}

uint load(int2 p) {
#if BOXES_R8
    return uint(source[p] * 255.0f + 0.5f);
#else
    return pack(uint4(source[p] * 255.0f + 0.5f));
#endif
}

void store(int2 p, uint texel) {
#if BOXES_R8
    target[p] = float(texel & 0xFFu) * INV_255;
#else
    target[p] = float4(unpack(texel)) * INV_255;
#endif
}

[shader("compute")]
[numthreads(64, 1, 1)]
void boxes_cs(uint3 group : SV_GroupID, uint3 local : SV_GroupThreadID) {
    // Workgroups past a rectangle's lines or texels have nothing to do (the dispatch covers the largest).
    if (group.z >= push.count) return;
    BoxJob job = jobs[push.first + group.z];
    bool along_x = push.axis == 0u;
    int length = along_x ? job.rect.z : job.rect.w;
    int line = int(group.y);
    int start = int(group.x * SEGMENT);
    if (line >= (along_x ? job.rect.w : job.rect.z) || start >= length) return;

    uint count = job.left.w;
    int reach_left = 0;
    int reach_right = 0;
    for (uint i = 0u; i < count; ++i) {
        reach_left += int(job.left[i]);
        reach_right += int(job.right[i]) - 1;
    }
    int end = min(start + int(SEGMENT), length);
    int first = start - reach_left; // the line's texel at index 0 of the held line
    int span = end - start + reach_left + reach_right;
    bool extend = (push.mode & MODE_EXTEND) != 0u;
    bool opaque = (push.mode & MODE_OPAQUE) != 0u;
    int t = int(local.x);

    for (int k = t; k < span; k += int(THREADS)) {
        int at = first + k;
        if (extend) at = clamp(at, 0, length - 1);
        uint texel = 0u;
        if (at >= 0 && at < length) {
            texel = load(texel_at(job, along_x, at, line));
            if (opaque) texel = texel | 0xFF000000u;
        }
        lines[0][k] = texel;
    }
    GroupMemoryBarrierWithGroupSync();

    // Box i reads [lo, hi) of one copy and writes [lo + left, hi - right + 1) of the other: what the boxes after it read.
    int lo = 0;
    int hi = span;
    for (uint i = 0u; i < count; ++i) {
        int l = int(job.left[i]);
        int r = int(job.right[i]);
        uint src = i & 1u;
        uint dst = src ^ 1u;
        int out_lo = lo + l;
        int out_hi = hi - (r - 1);
        int chunk = (out_hi - out_lo + int(THREADS) - 1) / int(THREADS);
        int k0 = out_lo + t * chunk;
        int k1 = min(k0 + chunk, out_hi);
        if (k0 < k1) {
            uint4 sum = uint4(0u, 0u, 0u, 0u);
            for (int j = k0 - l; j < k0 + r; ++j) sum += unpack(lines[src][j]);
            for (int k = k0; k < k1; ++k) {
                lines[dst][k] = pack(mean(sum, job.inv[i], job.inv.w));
                if (k + 1 < k1) {
                    sum += unpack(lines[src][k + r]);
                    sum -= unpack(lines[src][k - l]);
                }
            }
        }
        GroupMemoryBarrierWithGroupSync();
        // Past the line's ends the next box reads transparent black, or the end texel again.
        if (first + out_lo < 0 || first + out_hi > length) {
            for (int k = out_lo + t; k < out_hi; k += int(THREADS)) {
                int at = first + k;
                if (at < 0 || at >= length) lines[dst][k] = extend ? lines[dst][clamp(at, 0, length - 1) - first] : 0u;
            }
            GroupMemoryBarrierWithGroupSync();
        }
        lo = out_lo;
        hi = out_hi;
    }

    uint last = count & 1u;
    for (int at = start + t; at < end; at += int(THREADS)) store(texel_at(job, along_x, at, line), lines[last][at - first]);
}
