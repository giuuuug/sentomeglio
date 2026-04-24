#include "SimpleResampler.h"
#include <cmath>

SimpleResampler::SimpleResampler(int inSampleRate, int outSampleRate)
    : mInRate(inSampleRate), mOutRate(outSampleRate), mPhase(0.0), mLastSample(0.0f) {
}

int SimpleResampler::resample(const float* input, int inputFrames, float* output) {
    if (inputFrames == 0) return 0;
    
    if (mInRate == mOutRate) {
        for (int i = 0; i < inputFrames; ++i) {
            output[i] = input[i];
        }
        if (inputFrames > 0) {
            mLastSample = input[inputFrames - 1];
        }
        return inputFrames;
    }

    int outIndex = 0;
    double phaseInc = (double)mInRate / mOutRate;

    while (true) {
        int inIndex = (int)std::floor(mPhase);

        if (inIndex >= inputFrames - 1) {
            break;
        }

        double frac = mPhase - inIndex;
        float s1 = (inIndex < 0) ? mLastSample : input[inIndex];
        float s2 = input[inIndex + 1];

        output[outIndex++] = s1 + (s2 - s1) * (float)frac;
        mPhase += phaseInc;
    }

    mLastSample = input[inputFrames - 1];
    mPhase -= inputFrames;

    return outIndex;
}