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
    int32_t getSharingMode() const;   // 0 = Exclusive, 1 = Shared
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
    std::vector<float> mMicrophoneBuffer;
    std::vector<float> mDownsampledBuffer;

    // Output FIFO (inference thread → audio callback)
    // Producer: inference thread writes mInferenceOutputFifoWriteIdx, calls outputFifoWrite()
    // Consumer: audio callback reads mInferenceOutputFifoReadIdx, calls outputFifoRead()
    std::vector<float> mInferenceOutputFifo;
    int mInferenceOutputFifoReadIdx = 0;           // owned by audio callback
    int mInferenceOutputFifoWriteIdx = 0;          // owned by inference thread
    std::atomic<int> mInferenceOutputFifoCount{0}; // shared: release/acquire ordering
    int mInferenceOutputFifoCapacity = 0;

    // AI input FIFO (audio callback → inference thread)
    // Producer: audio callback writes mInferenceInputFifoWriteIdx, calls inputFifoWrite()
    // Consumer: inference thread reads mInferenceInputFifoReadIdx, calls inputFifoRead()
    std::vector<float> mInferenceInputFifo;
    int mInferenceInputFifoReadIdx = 0;  // owned by inference thread
    int mInferenceInputFifoWriteIdx = 0; // owned by audio callback
    std::atomic<int> mInferenceInputFifoCount{0};
    int mInferenceInputFifoCapacity = 0;

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

    // Output FIFO helpers (inference = producer, callback = consumer)
    void outputFifoWrite(const float *data, int count);
    int outputFifoRead(float *data, int count);

    // AI FIFO helpers (callback = producer, inference = consumer)
    void inputFifoWrite(const float *data, int count);
    int inputFifoRead(float *data, int count);

    // ── Recording ─────────────────────────────────────────────────────────────
public:
    void setRecording(bool enabled, const std::string &noisyPath, const std::string &denoisedPath);

private:
    std::atomic<bool> mRecordingEnabled{false};
    std::string mNoisyWavPath;
    std::string mDenoisedWavPath;

    // SPSC FIFOs: audio callback → recorder thread (noisy)
    //             inference thread → recorder thread (denoised)
    std::vector<float> mRecordNoisyFifo;
    int mRecordNoisyReadIdx = 0;
    int mRecordNoisyWriteIdx = 0;
    std::atomic<int> mRecordNoisyCount{0};
    int mRecordNoisyCapacity = 0;

    std::vector<float> mRecordDenoisedFifo;
    int mRecordDenoisedReadIdx = 0;
    int mRecordDenoisedWriteIdx = 0;
    std::atomic<int> mRecordDenoisedCount{0};
    int mRecordDenoisedCapacity = 0;

    // Recorder thread
    std::thread mRecorderThread;
    std::atomic<bool> mRecorderRunning{false};
    void recorderLoop();

    // Recording FIFO helpers
    void recNoisyWrite(const float *data, int n);
    void recDenoisedWrite(const float *data, int n);
    int  recNoisyRead(float *buf, int n);
    int  recDenoisedRead(float *buf, int n);
};

#endif // AUDIOENGINE_H
