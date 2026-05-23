package com.sentomeglio.app

object NativeBridge {

    init {
        System.loadLibrary("onnxruntime")
        System.loadLibrary("app")
    }

    external fun startAudioEngine(
        inputId: Int, outputId: Int, modelPath: String,
        nFft: Int, hopLength: Int, winLength: Int
    ): Boolean

    external fun stopAudioEngine()
    external fun getInferenceLatencyMs(): Double
    external fun getDspLatencyMs(): Double
    external fun getHwLatencyMs(): Double
    external fun getInputLatencyMs(): Double
    external fun getOutputLatencyMs(): Double
    external fun getXRunCount(): Int
    external fun getBufferSizeFrames(): Int
    external fun getBurstSizeFrames(): Int
    external fun getSampleRateHz(): Int
    external fun getSharingMode(): Int          // 0 = Exclusive, 1 = Shared
    external fun getSpectrograms(noisyDb: FloatArray, denDb: FloatArray)
    external fun setRecording(enabled: Boolean, noisyPath: String, denoisedPath: String)
}
