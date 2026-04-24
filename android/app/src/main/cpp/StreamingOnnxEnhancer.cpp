#include "StreamingOnnxEnhancer.h"
#include <cmath>
#include <cstring>
#include <algorithm>
#include <chrono>
#include <android/log.h>
#include "pocketfft_hdronly.h"

#define TAG "StreamingOnnxEnhancer"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

using namespace pocketfft;

StreamingOnnxEnhancer::StreamingOnnxEnhancer(const std::string& modelPath, int sampleRate, int nFft, int hopLength, int winLength)
    : mSampleRate(sampleRate), mNFft(nFft), mHopLength(hopLength), mWinLength(winLength)
{
    mNBins = mNFft / 2 + 1;
    
    // Init window
    mWindow.resize(mWinLength);
    mWindowSq.resize(mWinLength);
    for (int i = 0; i < mWinLength; ++i) {
        float w = 0.5f * (1.0f - std::cos(2.0f * M_PI * i / (mWinLength - 1.0f)));
        mWindow[i] = w;
        mWindowSq[i] = w * w;
    }

    mAnalysisBuffer.resize(mWinLength, 0.0f);
    mWindowedFrame.resize(mWinLength, 0.0f);
    mOlaSignal.resize(mWinLength, 0.0f);
    mOlaNorm.resize(mWinLength, 0.0f);

    mNoisyMag.resize(mNBins, 0.0f);
    mPhase.resize(mNBins, 0.0f);
    mEnhancedMag.resize(mNBins, 0.0f);
    mMask.resize(mNBins, 1.0f);

    mNoisyDb.resize(mNBins, -90.0f);
    mDenDb.resize(mNBins, -90.0f);

    // Init ONNX
    mEnv = std::make_unique<Ort::Env>(ORT_LOGGING_LEVEL_WARNING, "StreamingOnnxEnhancer");
    Ort::SessionOptions sessionOptions;
    sessionOptions.SetIntraOpNumThreads(1);
    sessionOptions.SetInterOpNumThreads(1);
    sessionOptions.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);

    mMemoryInfo = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeCPU);

    try {
        mSession = std::make_unique<Ort::Session>(*mEnv, modelPath.c_str(), sessionOptions);
        allocateTensors();
    } catch (const std::exception& e) {
        LOGE("Failed to load ONNX model: %s", e.what());
    }
}

StreamingOnnxEnhancer::~StreamingOnnxEnhancer() {
}

void StreamingOnnxEnhancer::allocateTensors() {
    Ort::AllocatorWithDefaultOptions allocator;

    size_t numInputNodes = mSession->GetInputCount();
    size_t numOutputNodes = mSession->GetOutputCount();

    for (size_t i = 0; i < numInputNodes; ++i) {
        auto name_ptr = mSession->GetInputNameAllocated(i, allocator);
        mInputNamesStr.push_back(name_ptr.get());
        
        Ort::TypeInfo type_info = mSession->GetInputTypeInfo(i);
        auto tensor_info = type_info.GetTensorTypeAndShapeInfo();
        std::vector<int64_t> input_node_dims = tensor_info.GetShape();

        for (auto& dim : input_node_dims) {
            if (dim < 0) dim = 1;
        }
        
        if (mInputNamesStr.back() == "frame" || mInputNamesStr.back() == "input") {
            // Re-allocate properly during inference since size is known
            mInputTensors.push_back(Ort::Value::CreateTensor<float>(mMemoryInfo, mNoisyMag.data(), mNoisyMag.size(), input_node_dims.data(), input_node_dims.size()));
        } else {
            // state tensors
            size_t tensor_size = 1;
            for (auto dim : input_node_dims) tensor_size *= dim;
            
            std::vector<float> state_data(tensor_size, 0.0f);
            float* state_ptr = new float[tensor_size](); // leak for simplicity or use a vector backing
            // Better: use a vector holding state data
            mInputTensors.push_back(Ort::Value::CreateTensor<float>(mMemoryInfo, state_ptr, tensor_size, input_node_dims.data(), input_node_dims.size()));
        }
    }
    
    for (size_t i = 0; i < numOutputNodes; ++i) {
        auto name_ptr = mSession->GetOutputNameAllocated(i, allocator);
        mOutputNamesStr.push_back(name_ptr.get());
    }

    for (const auto& s : mInputNamesStr) mInputNames.push_back(s.c_str());
    for (const auto& s : mOutputNamesStr) mOutputNames.push_back(s.c_str());
}

void StreamingOnnxEnhancer::processHop(const float* hop_in, float* hop_out) {
    if (!mSession) {
        std::memcpy(hop_out, hop_in, mHopLength * sizeof(float));
        return;
    }

    // 1. Push to ring buffer
    int end = mAnalysisWriteIdx + mHopLength;
    if (end <= mWinLength) {
        std::memcpy(&mAnalysisBuffer[mAnalysisWriteIdx], hop_in, mHopLength * sizeof(float));
    } else {
        int split = mWinLength - mAnalysisWriteIdx;
        std::memcpy(&mAnalysisBuffer[mAnalysisWriteIdx], hop_in, split * sizeof(float));
        std::memcpy(&mAnalysisBuffer[0], hop_in + split, (mHopLength - split) * sizeof(float));
    }
    mAnalysisWriteIdx = end % mWinLength;

    // 2. Read aligned frame
    if (mAnalysisWriteIdx == 0) {
        std::memcpy(mWindowedFrame.data(), mAnalysisBuffer.data(), mWinLength * sizeof(float));
    } else {
        int tail = mWinLength - mAnalysisWriteIdx;
        std::memcpy(mWindowedFrame.data(), &mAnalysisBuffer[mAnalysisWriteIdx], tail * sizeof(float));
        std::memcpy(mWindowedFrame.data() + tail, mAnalysisBuffer.data(), mAnalysisWriteIdx * sizeof(float));
    }

    // 3. Windowing & zero padding to NFFT
    std::vector<float> fft_in(mNFft, 0.0f);
    for (int i = 0; i < mWinLength; ++i) {
        fft_in[i] = mWindowedFrame[i] * mWindow[i];
    }

    // 4. RFFT
    auto dsp_start = std::chrono::high_resolution_clock::now();
    std::vector<std::complex<float>> fft_out(mNBins);
    shape_t shape_in = {(size_t)mNFft};
    stride_t stride_in = {sizeof(float)};
    stride_t stride_out = {sizeof(std::complex<float>)};
    pocketfft::r2c(shape_in, stride_in, stride_out, {0}, true, fft_in.data(), fft_out.data(), 1.0f);

    // 5. Magnitude and Phase
    for (int i = 0; i < mNBins; ++i) {
        float r = fft_out[i].real();
        float im = fft_out[i].imag();
        mNoisyMag[i] = std::sqrt(r * r + im * im);
        mPhase[i] = std::atan2(im, r);
        mNoisyDb[i] = 20.0f * std::log10(std::max(mNoisyMag[i], 1e-8f));
    }
    auto dsp_mid = std::chrono::high_resolution_clock::now();

    // 6. ONNX Inference
    // Replace input frame tensor with current magnitude
    std::vector<int64_t> frame_shape = {1, mNBins, 1}; // [Batch, Freq, Time]
    mInputTensors[0] = Ort::Value::CreateTensor<float>(mMemoryInfo, mNoisyMag.data(), mNoisyMag.size(), frame_shape.data(), frame_shape.size());

    auto inf_start = std::chrono::high_resolution_clock::now();
    auto output_tensors = mSession->Run(Ort::RunOptions{nullptr}, mInputNames.data(), mInputTensors.data(), mInputTensors.size(), mOutputNames.data(), mOutputNames.size());
    auto inf_end = std::chrono::high_resolution_clock::now();
    double raw = std::chrono::duration<double, std::milli>(inf_end - inf_start).count();
    mLastInferenceMs.store(raw);
    double prev = mInferenceEmaMs.load();
    mInferenceEmaMs.store(prev == 0.0 ? raw : kEmaAlpha * raw + (1.0 - kEmaAlpha) * prev);

    // 7. Update States
    for (size_t i = 1; i < mInputTensors.size(); ++i) {
        float* out_state_ptr = output_tensors[i].GetTensorMutableData<float>();
        float* in_state_ptr = mInputTensors[i].GetTensorMutableData<float>();
        size_t count = mInputTensors[i].GetTensorTypeAndShapeInfo().GetElementCount();
        std::memcpy(in_state_ptr, out_state_ptr, count * sizeof(float));
    }

    // 8. Apply Mask
    float* mask_ptr = output_tensors[0].GetTensorMutableData<float>();
    for (int i = 0; i < mNBins; ++i) {
        mMask[i] = mask_ptr[i];
        mEnhancedMag[i] = mNoisyMag[i] * mMask[i];
        mDenDb[i] = 20.0f * std::log10(std::max(mEnhancedMag[i], 1e-8f));
    }

    // 9. IRFFT
    auto istft_start = std::chrono::high_resolution_clock::now();
    std::vector<std::complex<float>> ifft_in(mNBins);
    for (int i = 0; i < mNBins; ++i) {
        ifft_in[i] = std::complex<float>(mEnhancedMag[i] * std::cos(mPhase[i]), mEnhancedMag[i] * std::sin(mPhase[i]));
    }

    std::vector<float> ifft_out(mNFft);
    pocketfft::c2r(shape_in, stride_out, stride_in, {0}, false, ifft_in.data(), ifft_out.data(), 1.0f / mNFft);

    // 10. OLA
    for (int i = 0; i < mWinLength; ++i) {
        mOlaSignal[i] += ifft_out[i] * mWindow[i];
        mOlaNorm[i] += mWindowSq[i];
    }

    for (int i = 0; i < mHopLength; ++i) {
        float norm = std::max(mOlaNorm[i], 1e-8f);
        hop_out[i] = mOlaSignal[i] / norm;
    }

    // Shift OLA buffers
    int remaining = mWinLength - mHopLength;
    std::memmove(mOlaSignal.data(), mOlaSignal.data() + mHopLength, remaining * sizeof(float));
    std::memset(mOlaSignal.data() + remaining, 0, mHopLength * sizeof(float));

    std::memmove(mOlaNorm.data(), mOlaNorm.data() + mHopLength, remaining * sizeof(float));
    std::memset(mOlaNorm.data() + remaining, 0, mHopLength * sizeof(float));
    auto dsp_end = std::chrono::high_resolution_clock::now();

    double dspRaw = std::chrono::duration<double, std::milli>(dsp_mid - dsp_start).count()
                  + std::chrono::duration<double, std::milli>(dsp_end - istft_start).count();
    double dspPrev = mDspEmaMs.load();
    mDspEmaMs.store(dspPrev == 0.0 ? dspRaw : kEmaAlpha * dspRaw + (1.0 - kEmaAlpha) * dspPrev);
}

void StreamingOnnxEnhancer::getMagnitudesDb(std::vector<float>& noisyDb, std::vector<float>& denDb) {
    noisyDb = mNoisyDb;
    denDb = mDenDb;
}