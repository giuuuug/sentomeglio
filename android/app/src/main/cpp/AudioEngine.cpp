#include "AudioEngine.h"
#include <android/log.h>
#include <chrono>
#include <algorithm>

#define TAG "AudioEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

AudioEngine::AudioEngine() {
}

AudioEngine::~AudioEngine() {
    stop();
}

bool AudioEngine::start(int inputDeviceId, int outputDeviceId, const std::string& modelPath, int nFft, int hopLength, int winLength) {
    if (!openStreams(inputDeviceId, outputDeviceId)) {
        return false;
    }
    
    mEnhancer = std::make_unique<StreamingOnnxEnhancer>(modelPath, 16000, nFft, hopLength, winLength);
    
    oboe::Result result = mRecordingStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Error starting recording stream: %s", oboe::convertToText(result));
        closeStreams();
        return false;
    }

    result = mPlaybackStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Error starting playback stream: %s", oboe::convertToText(result));
        closeStreams();
        return false;
    }

    LOGD("Audio streams started successfully.");
    return true;
}

void AudioEngine::getSpectrograms(std::vector<float>& noisyDb, std::vector<float>& denDb) {
    if (mEnhancer) {
        mEnhancer->getMagnitudesDb(noisyDb, denDb);
    }
}

void AudioEngine::stop() {
    closeStreams();
}

double AudioEngine::getInferenceLatencyMs() const {
    if (mEnhancer) return mEnhancer->getInferenceLatencyMs();
    return 0.0;
}

double AudioEngine::getDspLatencyMs() const {
    if (mEnhancer) return mEnhancer->getDspLatencyMs();
    return 0.0;
}

double AudioEngine::getHwLatencyMs() const {
    double inputLatMs = 0.0, outputLatMs = 0.0;
    if (mRecordingStream) {
        auto result = mRecordingStream->calculateLatencyMillis();
        if (result) inputLatMs = result.value();
    }
    if (mPlaybackStream) {
        auto result = mPlaybackStream->calculateLatencyMillis();
        if (result) outputLatMs = result.value();
    }
    return inputLatMs + outputLatMs;
}

bool AudioEngine::openStreams(int inputDeviceId, int outputDeviceId) {
    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input)
             ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
             ->setSharingMode(oboe::SharingMode::Exclusive)
             ->setFormat(oboe::AudioFormat::Float)
             ->setChannelCount(1)
             ->setDeviceId(inputDeviceId);
    
    oboe::Result result = inBuilder.openStream(mRecordingStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open recording stream. Error: %s", oboe::convertToText(result));
        return false;
    }

    oboe::AudioStreamBuilder outBuilder;
    outBuilder.setDirection(oboe::Direction::Output)
              ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
              ->setSharingMode(oboe::SharingMode::Exclusive)
              ->setFormat(oboe::AudioFormat::Float)
              ->setChannelCount(1)
              ->setSampleRate(mRecordingStream->getSampleRate())
              ->setDeviceId(outputDeviceId)
              ->setDataCallback(this)
              ->setErrorCallback(this);

    result = outBuilder.openStream(mPlaybackStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open playback stream. Error: %s", oboe::convertToText(result));
        mRecordingStream->close();
        return false;
    }

    int hwRate = mRecordingStream->getSampleRate();
    int aiRate = 16000;
    
    mDownsampler = std::make_unique<SimpleResampler>(hwRate, aiRate);
    mUpsampler = std::make_unique<SimpleResampler>(aiRate, hwRate);
    
    int maxFrames = mRecordingStream->getFramesPerBurst() * 4; // safe margin
    if (maxFrames < 2048) maxFrames = 2048;
    
    mInputBuffer.resize(maxFrames);
    mAiBuffer.resize(maxFrames);
    mOutputFifo.clear();

    return true;
}

void AudioEngine::closeStreams() {
    if (mPlaybackStream) {
        mPlaybackStream->requestStop();
        mPlaybackStream->close();
        mPlaybackStream.reset();
    }
    if (mRecordingStream) {
        mRecordingStream->requestStop();
        mRecordingStream->close();
        mRecordingStream.reset();
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) {
    auto start_time = std::chrono::high_resolution_clock::now();
    float *outputBuffer = static_cast<float *>(audioData);

    if (mRecordingStream) {
        if (mInputBuffer.size() < numFrames) {
            mInputBuffer.resize(numFrames * 2);
        }
        
        oboe::ResultWithValue<int32_t> result = mRecordingStream->read(mInputBuffer.data(), numFrames, 0);
        int32_t framesRead = result.value() > 0 ? result.value() : 0;
        
        // Zero-fill if underrun
        for (int i = framesRead; i < numFrames; ++i) {
            mInputBuffer[i] = 0.0f;
        }

        int hwRate = mRecordingStream->getSampleRate();
        int aiRate = 16000;

        // 1. Downsample to 16kHz
        int aiMaxFrames = (numFrames * aiRate / hwRate) + 10;
        if (mAiBuffer.size() < aiMaxFrames) {
            mAiBuffer.resize(aiMaxFrames);
        }
        
        int aiFrames = mDownsampler->resample(mInputBuffer.data(), numFrames, mAiBuffer.data());
        
        // 2. --- NEURAL NETWORK PROCESSING at 16kHz ---
        int hopSize = mEnhancer ? mEnhancer->getHopLength() : 128;
        if (mHopBuffer.size() < hopSize) mHopBuffer.resize(hopSize, 0.0f);
        if (mHopOutBuffer.size() < hopSize) mHopOutBuffer.resize(hopSize, 0.0f);
        
        std::vector<float> processedAi;
        processedAi.reserve(aiFrames + hopSize);

        // We need a proper FIFO for ML input if aiFrames is not a multiple of hopSize.
        // For simplicity, we will assume mHopBuffer acts as a persistent FIFO across callbacks.
        // Let's add mAiFifo to class if needed, but since it's just processing whatever is available:
        static std::vector<float> sAiFifo; // simple static for now, should be member
        sAiFifo.insert(sAiFifo.end(), mAiBuffer.begin(), mAiBuffer.begin() + aiFrames);
        
        while (sAiFifo.size() >= hopSize) {
            if (mEnhancer) {
                mEnhancer->processHop(sAiFifo.data(), mHopOutBuffer.data());
            } else {
                std::copy(sAiFifo.begin(), sAiFifo.begin() + hopSize, mHopOutBuffer.begin());
            }
            processedAi.insert(processedAi.end(), mHopOutBuffer.begin(), mHopOutBuffer.begin() + hopSize);
            sAiFifo.erase(sAiFifo.begin(), sAiFifo.begin() + hopSize);
        }
        
        // 3. Upsample back to hardware rate
        int outMax = (processedAi.size() * hwRate / aiRate) + 10;
        std::vector<float> tempOut(outMax);
        int outFrames = mUpsampler->resample(processedAi.data(), processedAi.size(), tempOut.data());
        
        mOutputFifo.insert(mOutputFifo.end(), tempOut.begin(), tempOut.begin() + outFrames);
        
        int framesToCopy = std::min((int)numFrames, (int)mOutputFifo.size());
        for (int i = 0; i < framesToCopy; ++i) {
            outputBuffer[i] = mOutputFifo[i];
        }
        
        mOutputFifo.erase(mOutputFifo.begin(), mOutputFifo.begin() + framesToCopy);
        
        for (int i = framesToCopy; i < numFrames; ++i) {
            outputBuffer[i] = 0.0f;
        }
    } else {
        for (int i = 0; i < numFrames; ++i) {
            outputBuffer[i] = 0.0f;
        }
    }

    auto end_time = std::chrono::high_resolution_clock::now();
    std::chrono::duration<double, std::milli> latency = end_time - start_time;
    mLastProcessingLatencyMs.store(latency.count());

    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) {
    LOGE("Error was %s", oboe::convertToText(error));
}