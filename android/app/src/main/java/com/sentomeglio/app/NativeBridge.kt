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
    external fun getSpectrograms(noisyDb: FloatArray, denDb: FloatArray)
}
