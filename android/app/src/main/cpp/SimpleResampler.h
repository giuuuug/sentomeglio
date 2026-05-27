#ifndef SIMPLERESAMPLER_H
#define SIMPLERESAMPLER_H

#include <speex/speex_resampler.h>

class SimpleResampler {
public:
    SimpleResampler(int inSampleRate, int outSampleRate);
    ~SimpleResampler();

    int resample(const float* input, int inputFrames, float* output);

private:
    int mInRate;
    int mOutRate;
    SpeexResamplerState* mResampler;

    // -- Old linear-interpolation state (kept for reference) --
    // double mPhase;
    // float mLastSample;
};

#endif // SIMPLERESAMPLER_H