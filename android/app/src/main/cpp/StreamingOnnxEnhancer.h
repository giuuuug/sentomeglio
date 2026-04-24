#ifndef STREAMINGONNXENHANCER_H
#define STREAMINGONNXENHANCER_H

#include <vector>
#include <string>
#include <memory>
#include <atomic>
#include <onnxruntime_cxx_api.h>

class StreamingOnnxEnhancer {
public:
    StreamingOnnxEnhancer(const std::string& modelPath, int sampleRate, int nFft, int hopLength, int winLength);
    ~StreamingOnnxEnhancer();

    // Process a hop-sized block. Expects input length == hopLength, outputs length == hopLength.
    void processHop(const float* input, float* output);

    // Get current magnitudes (db) for spectrograms
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

    // ONNX IO names
    std::vector<const char*> mInputNames;
    std::vector<const char*> mOutputNames;
    
    std::vector<std::string> mInputNamesStr;
    std::vector<std::string> mOutputNamesStr;

    // Input/Output Tensors
    std::vector<Ort::Value> mInputTensors;
    std::vector<Ort::Value> mOutputTensors;
    
    // Internal buffers for magnitudes
    std::vector<float> mNoisyMag;
    std::vector<float> mPhase;
    std::vector<float> mEnhancedMag;
    std::vector<float> mMask;

    std::vector<float> mNoisyDb;
    std::vector<float> mDenDb;

    void reset();
    void allocateTensors();
};

#endif // STREAMINGONNXENHANCER_H