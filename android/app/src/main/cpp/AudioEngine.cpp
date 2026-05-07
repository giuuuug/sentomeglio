#include "AudioEngine.h"
#include <algorithm>
#include <android/log.h>
#include <chrono>
#include <cstring>
#include <sys/resource.h>

#define TAG "AudioEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

AudioEngine::AudioEngine() {}
AudioEngine::~AudioEngine() { stop(); }

// ── Public API ────────────────────────────────────────────────────────────────

bool AudioEngine::start(int inputDeviceId, int outputDeviceId,
                        const std::string &modelPath, int nFft, int hopLength,
                        int winLength)
{
    mEnhancer = std::make_unique<StreamingOnnxEnhancer>(
        modelPath, mAiRate, nFft, hopLength, winLength);

    if (!openStreams(inputDeviceId, outputDeviceId, hopLength))
    {
        mEnhancer.reset();
        return false;
    }

    mEnhancer->prewarm();

    // Start inference thread before streams so it's already running when the
    // first hops arrive from the callback.
    mInferenceRunning.store(true, std::memory_order_relaxed);
    mInferenceThread = std::thread(&AudioEngine::inferenceLoop, this);

    oboe::Result result = mRecordingStream->requestStart();
    if (result != oboe::Result::OK)
    {
        LOGE("Failed to start recording stream: %s", oboe::convertToText(result));
        mInferenceRunning.store(false, std::memory_order_relaxed);
        mInferenceThread.join();
        closeStreams();
        mEnhancer.reset();
        return false;
    }

    result = mPlaybackStream->requestStart();
    if (result != oboe::Result::OK)
    {
        LOGE("Failed to start playback stream: %s", oboe::convertToText(result));
        mInferenceRunning.store(false, std::memory_order_relaxed);
        mInferenceThread.join();
        closeStreams();
        mEnhancer.reset();
        return false;
    }

    LOGD("Audio started. HW=%d Hz, AI=%d Hz, hop=%d, burst=%d",
         mHwRate, mAiRate, hopLength,
         mPlaybackStream ? mPlaybackStream->getFramesPerBurst() : -1);
    return true;
}

void AudioEngine::stop()
{
    // Stop streams
    closeStreams();

    // Signal inference thread to exit and wait for it.
    mInferenceRunning.store(false, std::memory_order_relaxed);
    if (mInferenceThread.joinable())
    {
        mInferenceThread.join();
    }

    mEnhancer.reset();
}

void AudioEngine::getSpectrograms(std::vector<float> &noisyDb, std::vector<float> &denDb)
{
    if (mEnhancer)
    {
        mEnhancer->getMagnitudesDb(noisyDb, denDb);
    }
}

double AudioEngine::getInferenceLatencyMs() const
{
    return mEnhancer ? mEnhancer->getInferenceLatencyMs() : 0.0;
}

double AudioEngine::getDspLatencyMs() const
{
    return mEnhancer ? mEnhancer->getDspLatencyMs() : 0.0;
}

double AudioEngine::getHwLatencyMs() const
{
    return getInputLatencyMs() + getOutputLatencyMs();
}

double AudioEngine::getInputLatencyMs() const
{
    if (!mRecordingStream)
        return 0.0;
    auto r = mRecordingStream->calculateLatencyMillis();
    return r ? r.value() : 0.0;
}

double AudioEngine::getOutputLatencyMs() const
{
    if (!mPlaybackStream)
        return 0.0;
    auto r = mPlaybackStream->calculateLatencyMillis();
    return r ? r.value() : 0.0;
}

int32_t AudioEngine::getXRunCount() const
{
    int32_t total = 0;
    if (mRecordingStream)
    {
        auto r = mRecordingStream->getXRunCount();
        if (r)
            total += r.value();
    }
    if (mPlaybackStream)
    {
        auto r = mPlaybackStream->getXRunCount();
        if (r)
            total += r.value();
    }
    return total;
}

int32_t AudioEngine::getBufferSizeFrames() const
{
    return mPlaybackStream ? mPlaybackStream->getBufferSizeInFrames() : 0;
}

int32_t AudioEngine::getBurstSizeFrames() const
{
    return mPlaybackStream ? mPlaybackStream->getFramesPerBurst() : 0;
}

int32_t AudioEngine::getSampleRateHz() const
{
    return mPlaybackStream ? mPlaybackStream->getSampleRate() : 0;
}

int32_t AudioEngine::getSharingMode() const
{
    if (!mPlaybackStream)
        return -1;
    return mPlaybackStream->getSharingMode() == oboe::SharingMode::Exclusive ? 0 : 1;
}

// ── Stream management ─────────────────────────────────────────────────────────

bool AudioEngine::openStreams(int inputDeviceId, int outputDeviceId, int hopLength)
{
    mHopLength = hopLength;

    // Request Exclusive mode for minimum mixer overhead on built-in devices.
    // Oboe automatically falls back to Shared for Bluetooth SCO/A2DP.
    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(1)
        ->setDeviceId(inputDeviceId)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);

    oboe::Result result = inBuilder.openStream(mRecordingStream);
    if (result != oboe::Result::OK)
    {
        LOGE("Failed to open recording stream: %s", oboe::convertToText(result));
        return false;
    }

    mHwRate = mRecordingStream->getSampleRate();
    LOGD("Recording: rate=%d burst=%d sharing=%d perf=%d",
         mHwRate,
         mRecordingStream->getFramesPerBurst(),
         (int)mRecordingStream->getSharingMode(),
         (int)mRecordingStream->getPerformanceMode());

    oboe::AudioStreamBuilder outBuilder;
    outBuilder.setDirection(oboe::Direction::Output)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(1)
        ->setSampleRate(mHwRate)
        ->setDeviceId(outputDeviceId)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    result = outBuilder.openStream(mPlaybackStream);
    if (result != oboe::Result::OK)
    {
        LOGE("Failed to open playback stream: %s", oboe::convertToText(result));
        mRecordingStream->close();
        mRecordingStream.reset();
        return false;
    }

    int32_t burst = mPlaybackStream->getFramesPerBurst();
    mPlaybackStream->setBufferSizeInFrames(burst);

    LOGD("Playback: rate=%d burst=%d bufSize=%d sharing=%d perf=%d",
         mPlaybackStream->getSampleRate(), burst,
         mPlaybackStream->getBufferSizeInFrames(),
         (int)mPlaybackStream->getSharingMode(),
         (int)mPlaybackStream->getPerformanceMode());

    // Resamplers
    if (mHwRate != mAiRate)
    {
        mDownsampler = std::make_unique<SimpleResampler>(mHwRate, mAiRate);
        mUpsampler = std::make_unique<SimpleResampler>(mAiRate, mHwRate);
    }
    else
    {
        mDownsampler.reset();
        mUpsampler.reset();
    }

    // Callback scratch buffers (sized for worst-case burst)
    int maxHwFrames = std::max(mRecordingStream->getFramesPerBurst(), burst) * 4;
    if (maxHwFrames < 4096)
        maxHwFrames = 4096;
    mMicrophoneBuffer.resize(maxHwFrames);

    int maxAiFrames = (maxHwFrames * mAiRate / mHwRate) + 16;
    if (maxAiFrames < 4096)
        maxAiFrames = 4096;
    mDownsampledBuffer.resize(maxAiFrames);

    // AI input FIFO: callback writes, inference thread reads
    mInferenceInputFifoCapacity = maxAiFrames * 8;
    mInferenceInputFifo.assign(mInferenceInputFifoCapacity, 0.0f);
    mInferenceInputFifoReadIdx = 0;
    mInferenceInputFifoWriteIdx = 0;
    mInferenceInputFifoCount.store(0, std::memory_order_relaxed);

    // Output FIFO: inference thread writes, callback reads
    int hopHwFrames = (hopLength * mHwRate / mAiRate) + 4;
    mInferenceOutputFifoCapacity = hopHwFrames * 16;
    if (mInferenceOutputFifoCapacity < 4096)
        mInferenceOutputFifoCapacity = 4096;
    mInferenceOutputFifo.assign(mInferenceOutputFifoCapacity, 0.0f);
    mInferenceOutputFifoReadIdx = 0;
    mInferenceOutputFifoWriteIdx = 0;

    // Pre-fill with 1 hop of silence: minimum cushion against the first-hop
    // jitter while keeping algorithmic latency low. The ONNX model is also
    // pre-warmed in start() before streams begin so the first real Run() does
    // not pay model-load cost.
    int preFill = std::min(hopHwFrames, mInferenceOutputFifoCapacity);
    mInferenceOutputFifoWriteIdx = preFill;
    mInferenceOutputFifoCount.store(preFill, std::memory_order_relaxed);

    // Inference-thread scratch buffers (exact sizes, no resizing at runtime)
    mModelInputBuffer.assign(hopLength, 0.0f);
    mModelOutputBuffer.assign(hopLength, 0.0f);
    int maxUpsampled = (hopLength * mHwRate / mAiRate) + 4;
    mUpsampledBuffer.resize(maxUpsampled);

    return true;
}

void AudioEngine::closeStreams()
{
    if (mPlaybackStream)
    {
        mPlaybackStream->requestStop();
        mPlaybackStream->close();
        mPlaybackStream.reset();
    }
    if (mRecordingStream)
    {
        mRecordingStream->requestStop();
        mRecordingStream->close();
        mRecordingStream.reset();
    }
}

// ── SPSC FIFO helpers ─────────────────────────────────────────────────────────
//
// Output FIFO: inference thread = producer, callback = consumer.
// The acquire/release pairing on mInferenceOutputFifoCount ensures written data is visible
// to the consumer before the count increment is published.

void AudioEngine::outputFifoWrite(const float *data, int count)
{
    int space = mInferenceOutputFifoCapacity - mInferenceOutputFifoCount.load(std::memory_order_acquire);
    int n = std::min(count, space);
    for (int i = 0; i < n; ++i)
    {
        mInferenceOutputFifo[mInferenceOutputFifoWriteIdx] = data[i];
        if (++mInferenceOutputFifoWriteIdx == mInferenceOutputFifoCapacity)
            mInferenceOutputFifoWriteIdx = 0;
    }
    if (n > 0)
        mInferenceOutputFifoCount.fetch_add(n, std::memory_order_release);
}

int AudioEngine::outputFifoRead(float *data, int count)
{
    int avail = mInferenceOutputFifoCount.load(std::memory_order_acquire);
    int n = std::min(count, avail);
    for (int i = 0; i < n; ++i)
    {
        data[i] = mInferenceOutputFifo[mInferenceOutputFifoReadIdx];
        if (++mInferenceOutputFifoReadIdx == mInferenceOutputFifoCapacity)
            mInferenceOutputFifoReadIdx = 0;
    }
    if (n > 0)
        mInferenceOutputFifoCount.fetch_sub(n, std::memory_order_release);
    return n;
}

void AudioEngine::inputFifoWrite(const float *data, int count)
{
    int space = mInferenceInputFifoCapacity - mInferenceInputFifoCount.load(std::memory_order_acquire);
    int n = std::min(count, space);
    for (int i = 0; i < n; ++i)
    {
        mInferenceInputFifo[mInferenceInputFifoWriteIdx] = data[i];
        if (++mInferenceInputFifoWriteIdx == mInferenceInputFifoCapacity)
            mInferenceInputFifoWriteIdx = 0;
    }
    if (n > 0)
        mInferenceInputFifoCount.fetch_add(n, std::memory_order_release);
}

int AudioEngine::inputFifoRead(float *data, int count)
{
    int avail = mInferenceInputFifoCount.load(std::memory_order_acquire);
    int n = std::min(count, avail);
    for (int i = 0; i < n; ++i)
    {
        data[i] = mInferenceInputFifo[mInferenceInputFifoReadIdx];
        if (++mInferenceInputFifoReadIdx == mInferenceInputFifoCapacity)
            mInferenceInputFifoReadIdx = 0;
    }
    if (n > 0)
        mInferenceInputFifoCount.fetch_sub(n, std::memory_order_release);
    return n;
}

// ── Inference thread ──────────────────────────────────────────────────────────

void AudioEngine::inferenceLoop()
{
    // Raise thread priority slightly (best-effort; won't fail if denied).
    setpriority(PRIO_PROCESS, 0, -8);

    while (mInferenceRunning.load(std::memory_order_relaxed))
    {
        // Wait until a full hop is available.
        if (mInferenceInputFifoCount.load(std::memory_order_acquire) < mHopLength)
        {
            std::this_thread::sleep_for(std::chrono::microseconds(500));
            continue;
        }

        inputFifoRead(mModelInputBuffer.data(), mHopLength);

        if (mEnhancer)
        {
            mEnhancer->processHop(mModelInputBuffer.data(), mModelOutputBuffer.data());
        }
        else
        {
            std::memcpy(mModelOutputBuffer.data(), mModelInputBuffer.data(), mHopLength * sizeof(float));
        }

        // Upsample back to HW rate if needed, then push to output FIFO.
        if (mUpsampler)
        {
            int n = mUpsampler->resample(mModelOutputBuffer.data(), mHopLength, mUpsampledBuffer.data());
            outputFifoWrite(mUpsampledBuffer.data(), n);
        }
        else
        {
            outputFifoWrite(mModelOutputBuffer.data(), mHopLength);
        }
    }
}

// ── Audio callback ────────────────────────────────────────────────────────────
// This runs on the real-time audio thread. It must never block or allocate.

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream * /*stream*/,
                                                   void *audioData,
                                                   int32_t numFrames)
{
    float *out = static_cast<float *>(audioData);

    if (!mRecordingStream)
    {
        std::memset(out, 0, numFrames * sizeof(float));
        return oboe::DataCallbackResult::Continue;
    }

    // Read from microphone.
    auto res = mRecordingStream->read(mMicrophoneBuffer.data(), numFrames, 0);
    int32_t framesRead = (res && res.value() > 0) ? res.value() : 0;
    if (framesRead < numFrames)
    {
        std::memset(mMicrophoneBuffer.data() + framesRead, 0,
                    (numFrames - framesRead) * sizeof(float));
    }

    // Downsample to AI rate (if needed) and push to AI FIFO.
    if (mDownsampler)
    {
        int n = mDownsampler->resample(mMicrophoneBuffer.data(), numFrames, mDownsampledBuffer.data());
        inputFifoWrite(mDownsampledBuffer.data(), n);
    }
    else
    {
        inputFifoWrite(mMicrophoneBuffer.data(), numFrames);
    }

    // Read processed audio from output FIFO.
    int got = outputFifoRead(out, numFrames);
    if (got < numFrames)
    {
        // Output FIFO underrun — zero-fill to avoid noise.
        std::memset(out + got, 0, (numFrames - got) * sizeof(float));
    }

    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result error)
{
    LOGE("Stream error: %s", oboe::convertToText(error));
}
