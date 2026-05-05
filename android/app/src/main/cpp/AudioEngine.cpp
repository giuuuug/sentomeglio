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
    double inputLatMs = 0.0, outputLatMs = 0.0;
    if (mRecordingStream)
    {
        auto r = mRecordingStream->calculateLatencyMillis();
        if (r)
            inputLatMs = r.value();
    }
    if (mPlaybackStream)
    {
        auto r = mPlaybackStream->calculateLatencyMillis();
        if (r)
            outputLatMs = r.value();
    }
    return inputLatMs + outputLatMs;
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

    // Tighten buffer to a single burst (minimum for LowLatency Exclusive).
    // If we observe XRuns in the field, the driver itself will not allow
    // setting below the supported floor — setBufferSizeInFrames clamps.
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
    mInputBuffer.resize(maxHwFrames);

    int maxAiFrames = (maxHwFrames * mAiRate / mHwRate) + 16;
    if (maxAiFrames < 4096)
        maxAiFrames = 4096;
    mAiBuffer.resize(maxAiFrames);

    // AI input FIFO — callback writes, inference thread reads
    mAiFifoCapacity = maxAiFrames * 8;
    mAiFifo.assign(mAiFifoCapacity, 0.0f);
    mAiFifoReadIdx = 0;
    mAiFifoWriteIdx = 0;
    mAiFifoCount.store(0, std::memory_order_relaxed);

    // Output FIFO — inference thread writes, callback reads
    // Size: 16 hops at HW rate is plenty of buffer headroom.
    int hopHwFrames = (hopLength * mHwRate / mAiRate) + 4;
    mFifoCapacity = hopHwFrames * 16;
    if (mFifoCapacity < 4096)
        mFifoCapacity = 4096;
    mOutputFifo.assign(mFifoCapacity, 0.0f);
    mFifoReadIdx = 0;
    mFifoWriteIdx = 0;

    // Pre-fill with 1 hop of silence — minimum cushion against the first-hop
    // jitter while keeping algorithmic latency low. The ONNX model is also
    // pre-warmed in start() before streams begin so the first real Run() does
    // not pay model-load cost.
    int preFill = std::min(hopHwFrames, mFifoCapacity);
    mFifoWriteIdx = preFill;
    mFifoCount.store(preFill, std::memory_order_relaxed);

    // Inference-thread scratch buffers (exact sizes, no resizing at runtime)
    mHopBuffer.assign(hopLength, 0.0f);
    mHopOutBuffer.assign(hopLength, 0.0f);
    int maxUpsampled = (hopLength * mHwRate / mAiRate) + 4;
    mTempOut.resize(maxUpsampled);

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
// The acquire/release pairing on mFifoCount ensures written data is visible
// to the consumer before the count increment is published.

void AudioEngine::outFifoWrite(const float *data, int count)
{
    int space = mFifoCapacity - mFifoCount.load(std::memory_order_acquire);
    int n = std::min(count, space);
    for (int i = 0; i < n; ++i)
    {
        mOutputFifo[mFifoWriteIdx] = data[i];
        if (++mFifoWriteIdx == mFifoCapacity)
            mFifoWriteIdx = 0;
    }
    if (n > 0)
        mFifoCount.fetch_add(n, std::memory_order_release);
}

int AudioEngine::outFifoRead(float *data, int count)
{
    int avail = mFifoCount.load(std::memory_order_acquire);
    int n = std::min(count, avail);
    for (int i = 0; i < n; ++i)
    {
        data[i] = mOutputFifo[mFifoReadIdx];
        if (++mFifoReadIdx == mFifoCapacity)
            mFifoReadIdx = 0;
    }
    if (n > 0)
        mFifoCount.fetch_sub(n, std::memory_order_release);
    return n;
}

void AudioEngine::aiFifoWrite(const float *data, int count)
{
    int space = mAiFifoCapacity - mAiFifoCount.load(std::memory_order_acquire);
    int n = std::min(count, space);
    for (int i = 0; i < n; ++i)
    {
        mAiFifo[mAiFifoWriteIdx] = data[i];
        if (++mAiFifoWriteIdx == mAiFifoCapacity)
            mAiFifoWriteIdx = 0;
    }
    if (n > 0)
        mAiFifoCount.fetch_add(n, std::memory_order_release);
}

int AudioEngine::aiFifoRead(float *data, int count)
{
    int avail = mAiFifoCount.load(std::memory_order_acquire);
    int n = std::min(count, avail);
    for (int i = 0; i < n; ++i)
    {
        data[i] = mAiFifo[mAiFifoReadIdx];
        if (++mAiFifoReadIdx == mAiFifoCapacity)
            mAiFifoReadIdx = 0;
    }
    if (n > 0)
        mAiFifoCount.fetch_sub(n, std::memory_order_release);
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
        if (mAiFifoCount.load(std::memory_order_acquire) < mHopLength)
        {
            std::this_thread::sleep_for(std::chrono::microseconds(500));
            continue;
        }

        aiFifoRead(mHopBuffer.data(), mHopLength);

        if (mEnhancer)
        {
            mEnhancer->processHop(mHopBuffer.data(), mHopOutBuffer.data());
        }
        else
        {
            std::memcpy(mHopOutBuffer.data(), mHopBuffer.data(), mHopLength * sizeof(float));
        }

        // Upsample back to HW rate if needed, then push to output FIFO.
        if (mUpsampler)
        {
            int n = mUpsampler->resample(mHopOutBuffer.data(), mHopLength, mTempOut.data());
            outFifoWrite(mTempOut.data(), n);
        }
        else
        {
            outFifoWrite(mHopOutBuffer.data(), mHopLength);
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
    auto res = mRecordingStream->read(mInputBuffer.data(), numFrames, 0);
    int32_t framesRead = (res && res.value() > 0) ? res.value() : 0;
    if (framesRead < numFrames)
    {
        std::memset(mInputBuffer.data() + framesRead, 0,
                    (numFrames - framesRead) * sizeof(float));
    }

    // Downsample to AI rate (if needed) and push to AI FIFO.
    if (mDownsampler)
    {
        int n = mDownsampler->resample(mInputBuffer.data(), numFrames, mAiBuffer.data());
        aiFifoWrite(mAiBuffer.data(), n);
    }
    else
    {
        aiFifoWrite(mInputBuffer.data(), numFrames);
    }

    // Read processed audio from output FIFO.
    int got = outFifoRead(out, numFrames);
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
