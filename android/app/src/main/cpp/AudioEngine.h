#ifndef AUDIOENGINE_H
#define AUDIOENGINE_H

#include <oboe/Oboe.h>
#include <atomic>
#include <memory>
#include <vector>
#include "SimpleResampler.h"
#include "StreamingOnnxEnhancer.h"

class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    AudioEngine();
    ~AudioEngine();

    bool start(int inputDeviceId, int outputDeviceId, const std::string& modelPath, int nFft, int hopLength, int winLength);
    void stop();
    double getInferenceLatencyMs() const;
    double getDspLatencyMs() const;
    double getHwLatencyMs() const;
    void getSpectrograms(std::vector<float>& noisyDb, std::vector<float>& denDb);

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) override;

    // oboe::AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) override;

private:
    std::shared_ptr<oboe::AudioStream> mRecordingStream;
    std::shared_ptr<oboe::AudioStream> mPlaybackStream;

    std::atomic<double> mLastProcessingLatencyMs{0.0};
    
    std::unique_ptr<SimpleResampler> mDownsampler;
    std::unique_ptr<SimpleResampler> mUpsampler;
    std::vector<float> mInputBuffer;
    std::vector<float> mAiBuffer;
    std::vector<float> mOutputFifo;

    std::unique_ptr<StreamingOnnxEnhancer> mEnhancer;
    std::vector<float> mHopBuffer;
    std::vector<float> mHopOutBuffer;

    bool openStreams(int inputDeviceId, int outputDeviceId);
    void closeStreams();
};

#endif // AUDIOENGINE_H
