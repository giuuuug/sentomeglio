#ifndef STREAMINGONNXENHANCER_H
#define STREAMINGONNXENHANCER_H

#include <vector>
#include <string>
#include <complex>
#include <memory>
#include <atomic>
#include <mutex>
#include <onnxruntime_cxx_api.h>

class StreamingOnnxEnhancer {
public:
    StreamingOnnxEnhancer(const std::string& modelPath, int sampleRate, int nFft, int hopLength, int winLength);
    ~StreamingOnnxEnhancer();

    void processHop(const float* input, float* output);
    void getMagnitudesDb(std::vector<float>& noisyDb, std::vector<float>& denDb);

    int getHopLength() const { return mHopLength; }
    double getInferenceLatencyMs() const { return mInferenceEmaMs.load(); }
    double getDspLatencyMs() const { return mDspEmaMs.load(); }

private:
    std::atomic<double> mLastInferenceMs{0.0};
    std::atomic<double> mInferenceEmaMs{0.0};
    std::atomic<double> mDspEmaMs{0.0};
    static constexpr double kEmaAlpha = 0.1;

    int mSampleRate;
    int mNFft;
    int mHopLength;
    int mWinLength;
    int mNBins;

    std::unique_ptr<Ort::Env> mEnv;
    std::unique_ptr<Ort::Session> mSession;
    Ort::MemoryInfo mMemoryInfo{nullptr};

    std::vector<float> mWindow;
    std::vector<float> mWindowSq;

    std::vector<float> mAnalysisBuffer;
    int mAnalysisWriteIdx = 0;

    std::vector<float> mWindowedFrame;
    std::vector<float> mOlaSignal;
    std::vector<float> mOlaNorm;

    std::vector<const char*> mInputNames;
    std::vector<const char*> mOutputNames;
    std::vector<std::string> mInputNamesStr;
    std::vector<std::string> mOutputNamesStr;

    std::vector<Ort::Value> mInputTensors;
    std::vector<Ort::Value> mOutputTensors;
    std::vector<std::vector<float>> mStateBacking;

    std::vector<float> mNoisyMag;
    std::vector<float> mPhase;
    std::vector<float> mEnhancedMag;
    std::vector<float> mMask;

    // Protected by mSpecMutex — written by inference thread, read by UI thread
    std::mutex mSpecMutex;
    std::vector<float> mNoisyDb;
    std::vector<float> mDenDb;

    std::vector<float> mFftIn;
    std::vector<std::complex<float>> mFftOut;
    std::vector<std::complex<float>> mIfftIn;
    std::vector<float> mIfftOut;

    void allocateTensors();
};

#endif // STREAMINGONNXENHANCER_H
