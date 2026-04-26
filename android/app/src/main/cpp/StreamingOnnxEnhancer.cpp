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

StreamingOnnxEnhancer::StreamingOnnxEnhancer(
    const std::string& modelPath, int sampleRate, int nFft, int hopLength, int winLength)
    : mSampleRate(sampleRate), mNFft(nFft), mHopLength(hopLength), mWinLength(winLength)
{
    mNBins = mNFft / 2 + 1;

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

    mFftIn.resize(mNFft, 0.0f);
    mFftOut.resize(mNBins);
    mIfftIn.resize(mNBins);
    mIfftOut.resize(mNFft);

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

StreamingOnnxEnhancer::~StreamingOnnxEnhancer() {}

void StreamingOnnxEnhancer::allocateTensors() {
    Ort::AllocatorWithDefaultOptions allocator;

    size_t numInputNodes  = mSession->GetInputCount();
    size_t numOutputNodes = mSession->GetOutputCount();

    for (size_t i = 0; i < numInputNodes; ++i) {
        auto name_ptr = mSession->GetInputNameAllocated(i, allocator);
        mInputNamesStr.push_back(name_ptr.get());

        const std::string& inputName = mInputNamesStr.back();
        bool isFrameInput = (inputName == "frame" || inputName == "input");

        if (isFrameInput) {
            // Fix: always use the correct shape {1, nBins, 1} regardless of model's
            // declared dims (which may all be -1 for dynamic models).
            std::vector<int64_t> frame_shape = {1, (int64_t)mNBins, 1};
            mInputTensors.push_back(Ort::Value::CreateTensor<float>(
                mMemoryInfo, mNoisyMag.data(), mNoisyMag.size(),
                frame_shape.data(), frame_shape.size()));
        } else {
            Ort::TypeInfo type_info = mSession->GetInputTypeInfo(i);
            auto tensor_info = type_info.GetTensorTypeAndShapeInfo();
            std::vector<int64_t> dims = tensor_info.GetShape();
            for (auto& d : dims) { if (d < 0) d = 1; }

            size_t tensor_size = 1;
            for (auto d : dims) tensor_size *= (size_t)d;

            mStateBacking.emplace_back(tensor_size, 0.0f);
            float* ptr = mStateBacking.back().data();
            mInputTensors.push_back(Ort::Value::CreateTensor<float>(
                mMemoryInfo, ptr, tensor_size, dims.data(), dims.size()));
        }
    }

    for (size_t i = 0; i < numOutputNodes; ++i) {
        auto name_ptr = mSession->GetOutputNameAllocated(i, allocator);
        mOutputNamesStr.push_back(name_ptr.get());
    }

    for (const auto& s : mInputNamesStr)  mInputNames.push_back(s.c_str());
    for (const auto& s : mOutputNamesStr) mOutputNames.push_back(s.c_str());
}

void StreamingOnnxEnhancer::processHop(const float* hop_in, float* hop_out) {
    if (!mSession) {
        std::memcpy(hop_out, hop_in, mHopLength * sizeof(float));
        return;
    }

    // 1. Push new hop into ring buffer
    int end = mAnalysisWriteIdx + mHopLength;
    if (end <= mWinLength) {
        std::memcpy(&mAnalysisBuffer[mAnalysisWriteIdx], hop_in, mHopLength * sizeof(float));
    } else {
        int split = mWinLength - mAnalysisWriteIdx;
        std::memcpy(&mAnalysisBuffer[mAnalysisWriteIdx], hop_in, split * sizeof(float));
        std::memcpy(&mAnalysisBuffer[0], hop_in + split, (mHopLength - split) * sizeof(float));
    }
    mAnalysisWriteIdx = end % mWinLength;

    // 2. Linearize ring buffer into mWindowedFrame (oldest → newest)
    if (mAnalysisWriteIdx == 0) {
        std::memcpy(mWindowedFrame.data(), mAnalysisBuffer.data(), mWinLength * sizeof(float));
    } else {
        int tail = mWinLength - mAnalysisWriteIdx;
        std::memcpy(mWindowedFrame.data(), &mAnalysisBuffer[mAnalysisWriteIdx], tail * sizeof(float));
        std::memcpy(mWindowedFrame.data() + tail, mAnalysisBuffer.data(), mAnalysisWriteIdx * sizeof(float));
    }

    // 3. Apply window + zero-pad to nFft
    auto dsp_start = std::chrono::high_resolution_clock::now();
    std::memset(mFftIn.data(), 0, mNFft * sizeof(float));
    for (int i = 0; i < mWinLength; ++i) {
        mFftIn[i] = mWindowedFrame[i] * mWindow[i];
    }

    // 4. RFFT
    shape_t  shape_in   = {(size_t)mNFft};
    stride_t stride_in  = {sizeof(float)};
    stride_t stride_out = {sizeof(std::complex<float>)};
    pocketfft::r2c(shape_in, stride_in, stride_out, {0}, true,
                   mFftIn.data(), mFftOut.data(), 1.0f);

    // 5. Magnitude + phase — update mNoisyMag in-place (tensor already points here)
    for (int i = 0; i < mNBins; ++i) {
        float r  = mFftOut[i].real();
        float im = mFftOut[i].imag();
        mNoisyMag[i] = std::sqrt(r * r + im * im);
        mPhase[i]    = std::atan2(im, r);
    }
    auto dsp_mid = std::chrono::high_resolution_clock::now();

    // 6. ONNX inference
    // mInputTensors[0] already wraps mNoisyMag.data() — no re-creation needed.
    auto inf_start = std::chrono::high_resolution_clock::now();
    auto output_tensors = mSession->Run(
        Ort::RunOptions{nullptr},
        mInputNames.data(), mInputTensors.data(), mInputTensors.size(),
        mOutputNames.data(), mOutputNames.size());
    auto inf_end = std::chrono::high_resolution_clock::now();

    double raw = std::chrono::duration<double, std::milli>(inf_end - inf_start).count();
    mLastInferenceMs.store(raw);
    double prev = mInferenceEmaMs.load();
    mInferenceEmaMs.store(prev == 0.0 ? raw : kEmaAlpha * raw + (1.0 - kEmaAlpha) * prev);

    // 7. Update recurrent states (output[1..N] → input[1..N])
    for (size_t i = 1; i < mInputTensors.size(); ++i) {
        float* out_ptr = output_tensors[i].GetTensorMutableData<float>();
        float* in_ptr  = mInputTensors[i].GetTensorMutableData<float>();
        size_t count   = mInputTensors[i].GetTensorTypeAndShapeInfo().GetElementCount();
        std::memcpy(in_ptr, out_ptr, count * sizeof(float));
    }

    // 8. Apply mask → enhanced magnitude
    float* mask_ptr = output_tensors[0].GetTensorMutableData<float>();
    for (int i = 0; i < mNBins; ++i) {
        mMask[i]        = mask_ptr[i];
        mEnhancedMag[i] = mNoisyMag[i] * mMask[i];
    }

    // 9. IRFFT
    auto istft_start = std::chrono::high_resolution_clock::now();
    for (int i = 0; i < mNBins; ++i) {
        mIfftIn[i] = std::complex<float>(
            mEnhancedMag[i] * std::cos(mPhase[i]),
            mEnhancedMag[i] * std::sin(mPhase[i]));
    }
    pocketfft::c2r(shape_in, stride_out, stride_in, {0}, false,
                   mIfftIn.data(), mIfftOut.data(), 1.0f / mNFft);

    // 10. OLA (overlap-add with synthesis window)
    for (int i = 0; i < mWinLength; ++i) {
        mOlaSignal[i] += mIfftOut[i] * mWindow[i];
        mOlaNorm[i]   += mWindowSq[i];
    }
    for (int i = 0; i < mHopLength; ++i) {
        hop_out[i] = mOlaSignal[i] / std::max(mOlaNorm[i], 1e-8f);
    }

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

    // Update spectrogram buffers for UI (lock only while writing)
    {
        std::lock_guard<std::mutex> lock(mSpecMutex);
        for (int i = 0; i < mNBins; ++i) {
            mNoisyDb[i] = 20.0f * std::log10(std::max(mNoisyMag[i],   1e-8f));
            mDenDb[i]   = 20.0f * std::log10(std::max(mEnhancedMag[i], 1e-8f));
        }
    }
}

void StreamingOnnxEnhancer::getMagnitudesDb(std::vector<float>& noisyDb, std::vector<float>& denDb) {
    std::lock_guard<std::mutex> lock(mSpecMutex);
    noisyDb = mNoisyDb;
    denDb   = mDenDb;
}
