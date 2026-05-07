package com.sentomeglio.app

import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.sentomeglio.app.databinding.ActivityDevBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class DevScreenActivity : BaseAudioActivity() {

    private lateinit var binding: ActivityDevBinding

    override val inputSpinner: Spinner get() = binding.content.inputSpinner
    override val outputSpinner: Spinner get() = binding.content.outputSpinner

    private val onnxModels = mutableListOf<String>()
    private var isPlaying = false
    private var currentModelPath = ""
    private var currentNFft = 512
    private var currentHopLength = 128
    private var currentWinLength = 320
    private var pendingNFft = 512
    private var pendingHopLength = 128
    private var pendingWinLength = 320

    private val defaultNFft = 512
    private val defaultHopLength = 128
    private val defaultWinLength = 320

    private val consoleLines = ArrayDeque<String>()
    private val maxConsoleLines = 150
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val uiUpdater = object : Runnable {
        override fun run() {
            if (isPlaying) {
                val hwMs = NativeBridge.getHwLatencyMs()
                val inferMs = NativeBridge.getInferenceLatencyMs()
                val dspMs = NativeBridge.getDspLatencyMs()
                binding.content.latencyText.text = String.format(
                    "HW: %.1f ms  |  DSP: %.2f ms  |  Infer: %.2f ms", hwMs, dspMs, inferMs
                )
                val frameBudgetMs = currentHopLength * 1000.0 / 16000.0
                binding.content.metricsText.text = String.format("RTF: %.3f", (dspMs + inferMs) / frameBudgetMs)

                // Oboe stream diagnostics
                val hwInMs = NativeBridge.getInputLatencyMs()
                val hwOutMs = NativeBridge.getOutputLatencyMs()
                binding.content.hwSplitText.text = String.format(
                    "HW In: %.1f ms  |  HW Out: %.1f ms", hwInMs, hwOutMs
                )
                val rate = NativeBridge.getSampleRateHz()
                val rateF = if (rate > 0) rate.toDouble() else 1.0
                val bufFrames = NativeBridge.getBufferSizeFrames()
                val burstFrames = NativeBridge.getBurstSizeFrames()
                val bufMs = bufFrames * 1000.0 / rateF
                val burstMs = burstFrames * 1000.0 / rateF
                binding.content.bufferText.text = String.format(
                    "Buffer: %d fr (%.1f ms)  |  Burst: %d fr (%.1f ms)",
                    bufFrames, bufMs, burstFrames, burstMs
                )
                val xruns = NativeBridge.getXRunCount()
                val sharing = when (NativeBridge.getSharingMode()) {
                    0 -> "Exclusive"
                    1 -> "Shared"
                    else -> "N/A"
                }
                binding.content.streamInfoText.text = String.format(
                    "XRun: %d  |  Rate: %d Hz  |  %s", xruns, rate, sharing
                )

                val nFreqs = currentNFft / 2 + 1
                val noisyArray = FloatArray(nFreqs)
                val denArray = FloatArray(nFreqs)
                NativeBridge.getSpectrograms(noisyArray, denArray)
                binding.content.specIn.updateSpectrogram(noisyArray)
                binding.content.specDen.updateSpectrogram(denArray)
                handler.postDelayed(this, 100)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDevBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, showDevBadge = true)
        binding.content.consoleScroll.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true); false
        }
        populateDeviceLists()
        populateModelSpinner()
        setupRecButton()
    }

    override fun onStart() {
        super.onStart()
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (!AppPreferences(this).devMode) {
            if (isPlaying) stopRecording()
            startActivity(Intent(this, DailyScreenActivity::class.java))
            finish()
            return
        }
        if (!AudioService.isRunning) syncToIdle()
    }

    override fun isAudioActive() = isPlaying

    override fun onActiveDeviceDisconnected() {
        stopRecording()
        log("WARN: dispositivo disconnesso, engine fermato")
        Toast.makeText(this, "Dispositivo audio disconnesso", Toast.LENGTH_SHORT).show()
    }

    override fun onScoConnected(inputItem: AudioDeviceItem, outputItem: AudioDeviceItem) {
        log("SCO connesso")
        doStartEngine(inputItem, outputItem, pendingNFft, pendingHopLength, pendingWinLength)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (!isPlaying) startRecording()
            } else {
                Toast.makeText(this, "Permessi necessari per microfono/notifiche", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Model spinner ────────────────────────────────────────────────────────

    private fun populateModelSpinner() {
        val models = assets.list("")?.filter { it.endsWith(".onnx") } ?: emptyList()
        onnxModels.clear()
        onnxModels.addAll(models)
        if (onnxModels.isEmpty()) { log("WARNING: nessun file .onnx trovato negli assets"); return }

        binding.content.modelSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_item, onnxModels)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.content.modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = loadModel(onnxModels[pos])
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        val default = onnxModels.indexOfFirst { it == "DNS4.onnx" }.takeIf { it >= 0 } ?: 0
        binding.content.modelSpinner.setSelection(default)
    }

    private fun loadModel(modelName: String) {
        val outFile = File(cacheDir, modelName)
        try {
            assets.open(modelName).use { input -> FileOutputStream(outFile).use { input.copyTo(it) } }
            currentModelPath = outFile.absolutePath
            val bytes = outFile.length()
            log("─── Modello: $modelName")
            log("Flash : ${"%.2f".format(bytes / 1_048_576.0)} MB")
            log("RAM   : ~${"%.2f".format(bytes * 2L / 1_048_576.0)} MB")
        } catch (e: Exception) {
            log("ERRORE caricamento modello: ${e.message}")
        }
    }

    // ── Audio control ────────────────────────────────────────────────────────

    private fun setupRecButton() {
        binding.content.recButton.setOnClickListener { if (isPlaying) stopRecording() else startRecording() }
    }

    private fun startRecording() {
        if (!checkPermissions()) { log("INFO: permessi mancanti, richiesta in corso"); return }
        val inputItem = binding.content.inputSpinner.selectedItem as? AudioDeviceItem ?: return
        val outputItem = binding.content.outputSpinner.selectedItem as? AudioDeviceItem ?: return
        try {
            val nFft = binding.content.nFftInput.text.toString().toIntOrNull() ?: defaultNFft
            val hopLength = binding.content.hopLengthInput.text.toString().toIntOrNull() ?: defaultHopLength
            val winLength = binding.content.winLengthInput.text.toString().toIntOrNull() ?: defaultWinLength
            if (nFft < winLength || hopLength > winLength) {
                Toast.makeText(this, "Parametri STFT non validi", Toast.LENGTH_LONG).show()
                return
            }
            currentNFft = nFft; currentHopLength = hopLength; currentWinLength = winLength
            binding.content.specIn.init(currentNFft)
            binding.content.specDen.init(currentNFft)
            log("─── Avvio audio")
            log("STFT: n_fft=$nFft  hop=$hopLength  win=$winLength")
            log("Input : ${inputItem.name}")
            log("Output: ${outputItem.name}")

            val needsSco = inputItem.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                           outputItem.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            if (needsSco) {
                pendingNFft = nFft; pendingHopLength = hopLength; pendingWinLength = winLength
                log("Attivazione SCO in corso...")
                activateScoAndStart(inputItem, outputItem)
            } else {
                doStartEngine(inputItem, outputItem, nFft, hopLength, winLength)
            }
        } catch (_: NumberFormatException) {
            Toast.makeText(this, "Valori STFT non validi, uso default", Toast.LENGTH_SHORT).show()
            binding.content.nFftInput.setText(defaultNFft.toString())
            binding.content.hopLengthInput.setText(defaultHopLength.toString())
            binding.content.winLengthInput.setText(defaultWinLength.toString())
        }
    }

    private fun doStartEngine(
        inputItem: AudioDeviceItem, outputItem: AudioDeviceItem,
        nFft: Int, hopLength: Int, winLength: Int
    ) {
        val ok = NativeBridge.startAudioEngine(inputItem.id, outputItem.id, currentModelPath, nFft, hopLength, winLength)
        if (ok) {
            AudioService.show(this)
            isPlaying = true
            setControlsEnabled(false)
            binding.content.recButton.setBackgroundResource(R.drawable.bg_rec_dev_recording)
            binding.content.recButton.text = "STOP"
            binding.content.recButton.setTextColor(ContextCompat.getColor(this, R.color.colorError))
            binding.content.statusText.text = "Audio Engine Running"
            handler.post(uiUpdater)
            log("Engine avviato")
        } else {
            (getSystemService(AUDIO_SERVICE) as AudioManager).mode = previousAudioMode
            log("ERRORE: engine non avviato")
            Toast.makeText(this, "Failed to start audio engine", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        if (!isPlaying) return
        NativeBridge.stopAudioEngine()
        AudioService.dismiss(this)
        isPlaying = false
        handler.removeCallbacks(uiUpdater)
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        cleanupSco()
        am.mode = previousAudioMode
        resetUi()
        log("Engine fermato")
    }

    private fun syncToIdle() {
        if (!isPlaying) return
        isPlaying = false
        handler.removeCallbacks(uiUpdater)
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        cleanupSco()
        am.mode = previousAudioMode
        resetUi()
    }

    private fun resetUi() {
        setControlsEnabled(true)
        binding.content.recButton.setBackgroundResource(R.drawable.bg_rec_dev_idle)
        binding.content.recButton.text = "REC"
        binding.content.recButton.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary))
        binding.content.statusText.text = "Ready"
        binding.content.latencyText.text = "HW: N/A  |  DSP: N/A  |  Infer: N/A"
        binding.content.metricsText.text = "RTF: N/A"
        binding.content.hwSplitText.text = "HW In: N/A  |  HW Out: N/A"
        binding.content.bufferText.text = "Buffer: N/A  |  Burst: N/A"
        binding.content.streamInfoText.text = "XRun: N/A  |  Rate: N/A  |  Sharing: N/A"
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.content.modelSpinner.isEnabled = enabled
        binding.content.inputSpinner.isEnabled = enabled
        binding.content.outputSpinner.isEnabled = enabled
        binding.content.nFftInput.isEnabled = enabled
        binding.content.hopLengthInput.isEnabled = enabled
        binding.content.winLengthInput.isEnabled = enabled
    }

    // ── Console ──────────────────────────────────────────────────────────────

    fun log(msg: String) {
        val line = "[${timeFormatter.format(Date())}] $msg"
        handler.post {
            consoleLines.addLast(line)
            while (consoleLines.size > maxConsoleLines) consoleLines.removeFirst()
            binding.content.consoleText.text = consoleLines.joinToString("\n")
            binding.content.consoleScroll.post {
                binding.content.consoleScroll.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }
}
