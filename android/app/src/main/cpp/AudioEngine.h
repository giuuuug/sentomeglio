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
    void getSpectrograms(std::vector<float> &noisyDb, std::vector<float> &denDb);

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream,
                                          void *audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) override;

private:
    std::shared_ptr<oboe::AudioStream> mRecordingStream;
    std::shared_ptr<oboe::AudioStream> mPlaybackStream;

    std::unique_ptr<SimpleResampler> mDownsampler;
    std::unique_ptr<SimpleResampler> mUpsampler;

    // Callback-only scratch buffers (never accessed from inference thread)
    std::vector<float> mInputBuffer;
    std::vector<float> mAiBuffer;

    // ── Output FIFO (inference thread → audio callback) — SPSC ──────────────
    // Producer: inference thread writes mFifoWriteIdx, calls outFifoWrite()
    // Consumer: audio callback reads mFifoReadIdx,   calls outFifoRead()
    std::vector<float> mOutputFifo;
    int mFifoReadIdx = 0;           // owned by audio callback
    int mFifoWriteIdx = 0;          // owned by inference thread
    std::atomic<int> mFifoCount{0}; // shared: release/acquire ordering
    int mFifoCapacity = 0;

    // ── AI input FIFO (audio callback → inference thread) — SPSC ────────────
    // Producer: audio callback writes mAiFifoWriteIdx, calls aiFifoWrite()
    // Consumer: inference thread reads mAiFifoReadIdx,  calls aiFifoRead()
    std::vector<float> mAiFifo;
    int mAiFifoReadIdx = 0;  // owned by inference thread
    int mAiFifoWriteIdx = 0; // owned by audio callback
    std::atomic<int> mAiFifoCount{0};
    int mAiFifoCapacity = 0;

    // Inference-thread-only scratch buffers
    std::vector<float> mHopBuffer;
    std::vector<float> mHopOutBuffer;
    std::vector<float> mTempOut;

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

    // Output FIFO helpers (inference = producer, callback = consumer)
    void outFifoWrite(const float *data, int count);
    int outFifoRead(float *data, int count);

    // AI FIFO helpers (callback = producer, inference = consumer)
    void aiFifoWrite(const float *data, int count);
    int aiFifoRead(float *data, int count);
};

#endif // AUDIOENGINE_H
