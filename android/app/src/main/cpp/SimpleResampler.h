#ifndef SIMPLERESAMPLER_H
#define SIMPLERESAMPLER_H

#include <vector>

class SimpleResampler {
public:
    SimpleResampler(int inSampleRate, int outSampleRate);
    
    // Returns the number of frames generated.
    // 'output' buffer must be large enough to hold at least (inputFrames * outSampleRate / inSampleRate) + 2 frames.
    int resample(const float* input, int inputFrames, float* output);

private:
    int mInRate;
    int mOutRate;
    double mPhase;
    float mLastSample;
};

#endif // SIMPLERESAMPLER_H