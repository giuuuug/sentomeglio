#ifndef SIMPLERESAMPLER_H
#define SIMPLERESAMPLER_H

#include <vector>

class SimpleResampler {
public:
    SimpleResampler(int inSampleRate, int outSampleRate);
    
    int resample(const float* input, int inputFrames, float* output);

private:
    int mInRate;
    int mOutRate;
    double mPhase;
    float mLastSample;
};

#endif // SIMPLERESAMPLER_H