#ifndef AUDIOENGINE_H
#define AUDIOENGINE_H

#include "SimpleResampler.h"
#include "StreamingOnnxEnhancer.h"
#include <atomic>
#include <memory>
#include <oboe/Oboe.h>
#include <thread>
#include <vector>

class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback
{
public:
    AudioEngine();
    ~AudioEngine();

    bool start(int inputDeviceId, int outputDeviceId,
               const std::string &modelPath, int nFft, int hopLength, int winLength);
    void stop();

    double getInferenceLatencyMs() const;
    double getDspLatencyMs() const;
    double getHwLatencyMs() const;
    double getInputLatencyMs() const;
    double getOutputLatencyMs() const;
    int32_t getXRunCount() const;
    int32_t getBufferSizeFrames() const;
    int32_t getBurstSizeFrames() const;
    int32_t getSampleRateHz() const;
    int32_t getSharingMode() const; // 0 = Exclusive, 1 = Shared
    void getSpectrograms(std::vector<float> &noisyDb, std::vector<float> &denDb);

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream,
                                          void *audioData, int32_t numFrames) override;
    void onErrorBeforeClose(oboe::AudioStream *audioStream, oboe::Result error) override;
    void onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) override;

private:
    // Single-producer / single-consumer lock-free ring buffer of floats.
    // The acquire/release pairing on `count` ensures data written by the
    // producer is visible to the consumer before the count increment is
    // published. read/write indices are each owned by exactly one thread.
    struct RingFifo
    {
        std::vector<float> buffer;
        int readIdx = 0;  // owned by consumer
        int writeIdx = 0; // owned by producer
        std::atomic<int> count{0};
        int capacity = 0;

        void reset(int cap, int initialFill = 0)
        {
            capacity = cap;
            buffer.assign(cap, 0.0f);
            readIdx = 0;
            writeIdx = initialFill;
            count.store(initialFill, std::memory_order_relaxed);
        }

        void write(const float *data, int n)
        {
            int space = capacity - count.load(std::memory_order_acquire);
            n = std::min(n, space);
            for (int i = 0; i < n; ++i)
            {
                buffer[writeIdx] = data[i];
                if (++writeIdx == capacity)
                    writeIdx = 0;
            }
            if (n > 0)
                count.fetch_add(n, std::memory_order_release);
        }

        int read(float *data, int n)
        {
            int avail = count.load(std::memory_order_acquire);
            n = std::min(n, avail);
            for (int i = 0; i < n; ++i)
            {
                data[i] = buffer[readIdx];
                if (++readIdx == capacity)
                    readIdx = 0;
            }
            if (n > 0)
                count.fetch_sub(n, std::memory_order_release);
            return n;
        }
    };

    std::shared_ptr<oboe::AudioStream> mRecordingStream;
    std::shared_ptr<oboe::AudioStream> mPlaybackStream;

    std::unique_ptr<SimpleResampler> mDownsampler;
    std::unique_ptr<SimpleResampler> mUpsampler;

    // Callback-only scratch buffers (never accessed from inference thread)
    std::vector<float> mMicrophoneBuffer;
    std::vector<float> mDownsampledBuffer;

    // Output FIFO: inference thread = producer, audio callback = consumer.
    RingFifo mOutputFifo;

    // AI input FIFO: audio callback = producer, inference thread = consumer.
    RingFifo mInputFifo;

    // Inference-thread-only scratch buffers
    std::vector<float> mModelInputBuffer;
    std::vector<float> mModelOutputBuffer;
    std::vector<float> mUpsampledBuffer;

    std::unique_ptr<StreamingOnnxEnhancer> mEnhancer;

    int mHwRate = 0;
    int mAiRate = 16000;
    int mHopLength = 128;

    // Inference worker thread
    std::thread mInferenceThread;
    std::atomic<bool> mInferenceRunning{false};
    void inferenceLoop();

    bool openStreams(int inputDeviceId, int outputDeviceId, int hopLength);
    void closeStreams();

    // ── Recording ─────────────────────────────────────────────────────────────
public:
    void setRecording(bool enabled, const std::string &noisyPath, const std::string &denoisedPath);

private:
    std::atomic<bool> mRecordingEnabled{false};
    std::string mNoisyWavPath;
    std::string mDenoisedWavPath;

    // Recording FIFOs (producer → recorder thread consumer):
    //   mRecordNoisyFifo:    audio callback → recorder thread
    //   mRecordDenoisedFifo: inference thread → recorder thread
    RingFifo mRecordNoisyFifo;
    RingFifo mRecordDenoisedFifo;

    // Recorder thread
    std::thread mRecorderThread;
    std::atomic<bool> mRecorderRunning{false};
    void recorderLoop();
};

#endif // AUDIOENGINE_H
