// The standing fades (ENGINE.md's fades): what stands up of a tile fading in is drawn on the pixels an ordered dither
// puts below the tile's fade, and what stands up of the tile kept under it on the rest, each at its own depth as at
// rest, so the two trade places pixel by pixel over the fade with true depth between them. Every sample of a pixel is
// one tile's: the composite reads a pixel whose samples are the objects' in part as a silhouette (mat's coverage rule,
// frame/composite.slang), so a dither within the pixel would show what fades in mat's edge colours. Each of them keeps
// a range of the dither's levels, from its place's offset.w to its scale.w (scene/Cover.hpp's upright): a tile drawn
// from 0 to its fade (0 to 1 drawn whole), a tile kept under one fading in from that tile's fade to 1, or to its own
// fade where it fades in over one kept under it in turn (a chain of levels zoomed through), so that every sample is one
// tile's. A vertex stage hands the range on in a flat word (fade_word, or a float lane: fade_lane) and its fragments
// keep fade_mask's samples: in a pipeline used only while a tile fades, or where the fragments write their samples'
// mask anyway, at rest too (a word of 0 keeps them all). Included by the twin's C++ as well (base/hlsl.hpp).
#pragma once

/// Where a flat word carries the range, in 255ths: how far its end lies short of 1 in bits 8 to 15 and its start in
/// bits 16 to 23 (bits 0 to 7 the carrier's own), so that a word of 0 keeps every sample (the whole range: a tile at
/// rest, or what sets no fade).
static const uint FADE_SHIFT = 8u;

/// The range of the dither's levels from `start` to `end` (a place's offset.w and scale.w, 0 to 1) in bits 8 to 23 of a
/// flat word, the rest left 0: 0 from 0 to 1.
inline uint fade_word(float start, float end) {
    uint s = uint(floor(saturate(start) * 255.0f + 0.5f));
    uint e = uint(floor(saturate(end) * 255.0f + 0.5f));
    return ((s << 8u) | (255u - e)) << FADE_SHIFT;
}

/// Fade word `word` (fade_word) in a float lane, its range's 16 bits the float's whole value (exact): for the spare
/// lane of a flat float vector the vertex stage writes anyway, whose other lanes the fragments of the pipeline at rest
/// read, so that the stage at rest leaves no output unread (lane_fade gives the word back).
inline float fade_lane(uint word) {
    return float(word >> FADE_SHIFT);
}

/// The fade word float lane `lane` carries (fade_lane).
inline uint lane_fade(float lane) {
    return uint(lane) << FADE_SHIFT;
}

/// The 4 x 4 ordered dither's rank of device pixel `p`, 0 to 15 (Bayer's matrix, rows 0 8 2 10, 12 4 14 6, 3 11 1 9,
/// 15 7 13 5: each rank's neighbours far apart): four times the 2 x 2 matrix's rank (2 (x ^ y) + y) of the low bits
/// plus that of the high bits.
inline uint fade_rank(int2 p) {
    uint x = uint(p.x) & 3u;
    uint y = uint(p.y) & 3u;
    uint d = x ^ y;
    uint low = ((d & 1u) << 1u) | (y & 1u);
    uint high = (d & 2u) | (y >> 1u);
    return (low << 2u) | high;
}

/// The samples of the `n` a pixel holds (1, 2 or 4; the frame block's info.w) at device pixel `p` that the range of
/// fade word `word` (fade_word) keeps: every one where the pixel's level, its rank of 16, lies within it, start <=
/// (rank + 1/2) / 16 < end, and else none. From 0 to 1 every sample, an empty range none; ranges that meet end to end
/// share no pixel and leave none out.
inline uint fade_mask(int2 p, uint n, uint word) {
    uint e = 255u - ((word >> FADE_SHIFT) & 255u);
    uint s = (word >> (FADE_SHIFT + 8u)) & 255u;
    uint samples = n < 1u ? 1u : (n > 4u ? 4u : n);
    uint all = (1u << samples) - 1u;
    if (s == 0u && e == 255u) {
        return all; // the whole range (a tile at rest): the level left uncounted
    }
    uint at = (2u * fade_rank(p) + 1u) * 255u; // the level's middle, times 2 x 255 x 16
    return at >= 32u * s && at < 32u * e ? all : 0u;
}
