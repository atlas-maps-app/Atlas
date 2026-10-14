/* The reference feTurbulence code from the Filter Effects spec (W3C, section 9.8), used as an oracle. */
#include <stdio.h>
#include <math.h>
#include <stdlib.h>
#define BSize 0x100
#define BM 0xff
#define PerlinN 0x1000
#define NP 12
#define NM 0xfff
static int uLatticeSelector[BSize + BSize + 2];
static double fGradient[4][BSize + BSize + 2][2];
struct StitchInfo { int nWidth; int nHeight; int nWrapX; int nWrapY; };
#define RAND_m 2147483647
#define RAND_a 16807
#define RAND_q 127773
#define RAND_r 2836
long setup_seed(long lSeed) {
  if (lSeed <= 0) lSeed = -(lSeed % (RAND_m - 1)) + 1;
  if (lSeed > RAND_m - 1) lSeed = RAND_m - 1;
  return lSeed;
}
long rnd(long lSeed) {
  long result;
  result = RAND_a * (lSeed % RAND_q) - RAND_r * (lSeed / RAND_q);
  if (result <= 0) result += RAND_m;
  return result;
}
static void init(long lSeed) {
  double s; int i, j, k;
  lSeed = setup_seed(lSeed);
  for (k = 0; k < 4; k++) {
    for (i = 0; i < BSize; i++) {
      uLatticeSelector[i] = i;
      for (j = 0; j < 2; j++)
        fGradient[k][i][j] = (double)(((lSeed = rnd(lSeed)) % (BSize + BSize)) - BSize) / BSize;
      s = (double)(sqrt(fGradient[k][i][0] * fGradient[k][i][0] + fGradient[k][i][1] * fGradient[k][i][1]));
      fGradient[k][i][0] /= s;
      fGradient[k][i][1] /= s;
    }
  }
  while (--i) {
    k = uLatticeSelector[i];
    uLatticeSelector[i] = uLatticeSelector[j = (lSeed = rnd(lSeed)) % BSize];
    uLatticeSelector[j] = k;
  }
  for (i = 0; i < BSize + 2; i++) {
    uLatticeSelector[BSize + i] = uLatticeSelector[i];
    for (k = 0; k < 4; k++)
      for (j = 0; j < 2; j++)
        fGradient[k][BSize + i][j] = fGradient[k][i][j];
  }
}
#define s_curve(t) ( t * t * (3. - 2. * t) )
#define lerp(t, a, b) ( a + t * (b - a) )
static double noise2(int nColorChannel, double vec[2], struct StitchInfo *pStitchInfo) {
  int bx0, bx1, by0, by1, b00, b10, b01, b11;
  double rx0, rx1, ry0, ry1, *q, sx, sy, a, b, t, u, v;
  int i, j;
  t = vec[0] + PerlinN; bx0 = ((int)t) & BM; bx1 = (bx0 + 1) & BM; rx0 = t - (int)t; rx1 = rx0 - 1.0f;
  t = vec[1] + PerlinN; by0 = ((int)t) & BM; by1 = (by0 + 1) & BM; ry0 = t - (int)t; ry1 = ry0 - 1.0f;
  if (pStitchInfo != NULL) {
    if (bx0 >= pStitchInfo->nWrapX) bx0 -= pStitchInfo->nWidth;
    if (bx1 >= pStitchInfo->nWrapX) bx1 -= pStitchInfo->nWidth;
    if (by0 >= pStitchInfo->nWrapY) by0 -= pStitchInfo->nHeight;
    if (by1 >= pStitchInfo->nWrapY) by1 -= pStitchInfo->nHeight;
  }
  bx0 &= BM; bx1 &= BM; by0 &= BM; by1 &= BM;
  i = uLatticeSelector[bx0]; j = uLatticeSelector[bx1];
  b00 = uLatticeSelector[i + by0]; b10 = uLatticeSelector[j + by0];
  b01 = uLatticeSelector[i + by1]; b11 = uLatticeSelector[j + by1];
  sx = (double)(s_curve(rx0)); sy = (double)(s_curve(ry0));
  q = fGradient[nColorChannel][b00]; u = rx0 * q[0] + ry0 * q[1];
  q = fGradient[nColorChannel][b10]; v = rx1 * q[0] + ry0 * q[1];
  a = lerp(sx, u, v);
  q = fGradient[nColorChannel][b01]; u = rx0 * q[0] + ry1 * q[1];
  q = fGradient[nColorChannel][b11]; v = rx1 * q[0] + ry1 * q[1];
  b = lerp(sx, u, v);
  return lerp(sy, a, b);
}
double turbulence(int nColorChannel, double *point, double fBaseFreqX, double fBaseFreqY,
                  int nNumOctaves, int bFractalSum, int bDoStitching,
                  double fTileX, double fTileY, double fTileWidth, double fTileHeight) {
  struct StitchInfo stitch; struct StitchInfo *pStitchInfo = NULL;
  if (bDoStitching) {
    if (fBaseFreqX != 0.0) { double fLoFreq = (double)(floor(fTileWidth * fBaseFreqX)) / fTileWidth; double fHiFreq = (double)(ceil(fTileWidth * fBaseFreqX)) / fTileWidth;
      if (fBaseFreqX / fLoFreq < fHiFreq / fBaseFreqX) fBaseFreqX = fLoFreq; else fBaseFreqX = fHiFreq; }
    if (fBaseFreqY != 0.0) { double fLoFreq = (double)(floor(fTileHeight * fBaseFreqY)) / fTileHeight; double fHiFreq = (double)(ceil(fTileHeight * fBaseFreqY)) / fTileHeight;
      if (fBaseFreqY / fLoFreq < fHiFreq / fBaseFreqY) fBaseFreqY = fLoFreq; else fBaseFreqY = fHiFreq; }
    pStitchInfo = &stitch;
    stitch.nWidth = (int)(fTileWidth * fBaseFreqX + 0.5f);
    stitch.nWrapX = fTileX * fBaseFreqX + PerlinN + stitch.nWidth;
    stitch.nHeight = (int)(fTileHeight * fBaseFreqY + 0.5f);
    stitch.nWrapY = fTileY * fBaseFreqY + PerlinN + stitch.nHeight;
  }
  double fSum = 0.0f; double vec[2]; vec[0] = point[0] * fBaseFreqX; vec[1] = point[1] * fBaseFreqY;
  double ratio = 1;
  for (int nOctave = 0; nOctave < nNumOctaves; nOctave++) {
    if (bFractalSum) fSum += (double)(noise2(nColorChannel, vec, pStitchInfo) / ratio);
    else fSum += (double)(fabs(noise2(nColorChannel, vec, pStitchInfo)) / ratio);
    vec[0] *= 2; vec[1] *= 2; ratio *= 2;
    if (pStitchInfo != NULL) {
      stitch.nWidth *= 2; stitch.nWrapX = 2 * stitch.nWrapX - PerlinN;
      stitch.nHeight *= 2; stitch.nWrapY = 2 * stitch.nWrapY - PerlinN;
    }
  }
  return fSum;
}
/* Prints, for a few seeds, frequencies and octaves, the turbulence of each channel at sample points. */
int main(void) {
  long seeds[] = {0, 1, 2, 3, 4, 5, 8, 13, 17, 23, 31, -7, 2147483647};
  double freqs[][2] = {{.55, .55}, {.16, .75}, {.0055, .017}, {1.4, 1.4}, {0.0200, 0.3000}};
  int octs[] = {1, 2, 4};
  printf("# seed fx fy octaves fractal x y r g b a\n");
  for (int si = 0; si < 13; si++) {
    init(seeds[si]);
    for (int fi = 0; fi < 5; fi++) for (int oi = 0; oi < 3; oi++) for (int fr = 0; fr < 2; fr++)
      for (int p = 0; p < 6; p++) {
        double pt[2] = {p * 37.25 - 20.5, p * 113.75 + 0.3};
        printf("%ld %.17g %.17g %d %d %.17g %.17g", seeds[si], freqs[fi][0], freqs[fi][1], octs[oi], fr, pt[0], pt[1]);
        for (int c = 0; c < 4; c++) printf(" %.17g", turbulence(c, pt, freqs[fi][0], freqs[fi][1], octs[oi], fr, 0, 0, 0, 0, 0));
        printf("\n");
      }
  }
  return 0;
}
