#include "SimpleResampler.h"
#include <cmath>

// --- Speex-based implementation (sinc polyphase, quality 6) ---

SimpleResampler::SimpleResampler(int inSampleRate, int outSampleRate)
    : mInRate(inSampleRate), mOutRate(outSampleRate) {
    int err;
    // Quality 6: ottimo equilibrio tra qualità (filtro sinc polifase) e latenza minima.
    // Range: 0 (lowest) – 10 (highest). 6 è il punto "desktop" della libreria.
    mResampler = speex_resampler_init(1, inSampleRate, outSampleRate, 6, &err);
}

SimpleResampler::~SimpleResampler() {
    if (mResampler) speex_resampler_destroy(mResampler);
}

int SimpleResampler::resample(const float* input, int inputFrames, float* output) {
    if (inputFrames == 0) return 0;

    spx_uint32_t inLen  = (spx_uint32_t)inputFrames;
    spx_uint32_t outLen = (spx_uint32_t)((long long)inputFrames * mOutRate / mInRate + 16);

    speex_resampler_process_float(mResampler, 0, input, &inLen, output, &outLen);
    return (int)outLen;
}

// --- Old linear-interpolation implementation (kept for reference) ---
//
// SimpleResampler::SimpleResampler(int inSampleRate, int outSampleRate)
//     : mInRate(inSampleRate), mOutRate(outSampleRate), mPhase(0.0), mLastSample(0.0f) {
// }
//
// int SimpleResampler::resample(const float* input, int inputFrames, float* output) {
//     if (inputFrames == 0) return 0;
//
//     if (mInRate == mOutRate) {
//         for (int i = 0; i < inputFrames; ++i) output[i] = input[i];
//         mLastSample = input[inputFrames - 1];
//         return inputFrames;
//     }
//
//     int outIndex = 0;
//     double phaseInc = (double)mInRate / mOutRate;
//
//     while (true) {
//         int inIndex = (int)std::floor(mPhase);
//         if (inIndex >= inputFrames - 1) break;
//         double frac = mPhase - inIndex;
//         float s1 = (inIndex < 0) ? mLastSample : input[inIndex];
//         float s2 = input[inIndex + 1];
//         output[outIndex++] = s1 + (s2 - s1) * (float)frac;
//         mPhase += phaseInc;
//     }
//
//     mLastSample = input[inputFrames - 1];
//     mPhase -= inputFrames;
//     return outIndex;
// }