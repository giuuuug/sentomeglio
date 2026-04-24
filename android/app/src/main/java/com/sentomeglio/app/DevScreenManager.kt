package com.sentomeglio.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.sentomeglio.app.databinding.LayoutDevBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class DevScreenManager(
    private val binding: LayoutDevBinding,
    private val onSettingsRequested: () -> Unit
) {

    data class AudioDeviceItem(val name: String, val id: Int, val type: Int) {
        override fun toString() = name
    }

    companion object {
        private val LOW_LATENCY_INPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )
        private val LOW_LATENCY_OUTPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )
    }

    private val context: Context get() = binding.root.context
    private val handler = Handler(Looper.getMainLooper())

    private val inputDevices = mutableListOf<AudioDeviceItem>()
    private val outputDevices = mutableListOf<AudioDeviceItem>()
    private val onnxModels = mutableListOf<String>()
    private var scoReceiver: BroadcastReceiver? = null

    var isPlaying = false
        private set

    private var currentModelPath = ""
    private var currentNFft = 512

    private val consoleLines = ArrayDeque<String>()
    private val maxConsoleLines = 150
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val uiUpdater = object : Runnable {
        override fun run() {
            if (isPlaying) {
                val hwMs = NativeBridge.getHwLatencyMs()
                val inferMs = NativeBridge.getInferenceLatencyMs()
                val dspMs = NativeBridge.getDspLatencyMs()
                binding.latencyText.text = String.format(
                    "HW: %.1f ms  |  DSP: %.2f ms  |  Infer: %.2f ms",
                    hwMs, dspMs, inferMs
                )
                val numFreqs = currentNFft / 2 + 1
                val noisyArray = FloatArray(numFreqs)
                val denArray = FloatArray(numFreqs)
                NativeBridge.getSpectrograms(noisyArray, denArray)
                binding.specIn.updateSpectrogram(noisyArray)
                binding.specDen.updateSpectrogram(denArray)
                handler.postDelayed(this, 100)
            }
        }
    }

    init {
        binding.consoleScroll.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        populateDeviceLists()
        populateModelSpinner()
        setupRecButton()
    }

    // ── Model spinner ────────────────────────────────────────────────────────

    private fun populateModelSpinner() {
        val assets = context.assets
        val models = assets.list("")?.filter { it.endsWith(".onnx") } ?: emptyList()
        onnxModels.clear()
        onnxModels.addAll(models)

        if (onnxModels.isEmpty()) {
            log("WARNING: nessun file .onnx trovato negli assets")
            return
        }

        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, onnxModels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.modelSpinner.adapter = adapter

        binding.modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                loadModel(onnxModels[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Pre-select DNS4.onnx if present, otherwise first model
        val defaultIndex = onnxModels.indexOfFirst { it == "DNS4.onnx" }.takeIf { it >= 0 } ?: 0
        binding.modelSpinner.setSelection(defaultIndex)
    }

    private fun loadModel(modelName: String) {
        val outFile = File(context.cacheDir, modelName)
        try {
            context.assets.open(modelName).use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            currentModelPath = outFile.absolutePath
            val flashBytes = outFile.length()
            val ramEstBytes = flashBytes * 2L
            log("─── Modello: $modelName")
            log("Flash : ${"%.2f".format(flashBytes / 1_048_576.0)} MB")
            log("RAM   : ~${"%.2f".format(ramEstBytes / 1_048_576.0)} MB")
        } catch (e: Exception) {
            log("ERRORE caricamento modello: ${e.message}")
        }
    }

    // ── Audio devices ────────────────────────────────────────────────────────

    private fun populateDeviceLists() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        inputDevices.clear()
        outputDevices.clear()

        val seenInputTypes = mutableSetOf<Int>()
        for (device in audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (device.type !in LOW_LATENCY_INPUT_TYPES) continue
            if (!seenInputTypes.add(device.type)) continue
            val typeStr = deviceTypeStr(device.type)
            val name = device.productName.toString().takeIf { it.isNotBlank() && it != "null" } ?: typeStr
            inputDevices.add(AudioDeviceItem("$name ($typeStr)", device.id, device.type))
        }

        val seenOutputTypes = mutableSetOf<Int>()
        for (device in audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.type !in LOW_LATENCY_OUTPUT_TYPES) continue
            if (!seenOutputTypes.add(device.type)) continue
            val typeStr = deviceTypeStr(device.type)
            val name = device.productName.toString().takeIf { it.isNotBlank() && it != "null" } ?: typeStr
            outputDevices.add(AudioDeviceItem("$name ($typeStr)", device.id, device.type))
        }

        val inAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, inputDevices)
        inAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.inputSpinner.adapter = inAdapter

        val outAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, outputDevices)
        outAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.outputSpinner.adapter = outAdapter
    }

    private fun deviceTypeStr(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC      -> "Built-in Mic"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER  -> "Built-in Speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET    -> "Wired Headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO    -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_USB_DEVICE       -> "USB Device"
        AudioDeviceInfo.TYPE_USB_HEADSET      -> "USB Headset"
        else                                  -> "Type $type"
    }

    // ── REC button ───────────────────────────────────────────────────────────

    private fun setupRecButton() {
        binding.recButton.setOnClickListener {
            if (isPlaying) stopAudio() else startAudio()
        }
    }

    fun startAudio() {
        val inputItem = binding.inputSpinner.selectedItem as? AudioDeviceItem ?: return
        val outputItem = binding.outputSpinner.selectedItem as? AudioDeviceItem ?: return
        try {
            val nFft = binding.nFftInput.text.toString().toInt()
            val hopLength = binding.hopLengthInput.text.toString().toInt()
            val winLength = binding.winLengthInput.text.toString().toInt()
            if (nFft < winLength || hopLength > winLength) {
                Toast.makeText(context, "Parametri STFT non validi", Toast.LENGTH_LONG).show()
                return
            }
            currentNFft = nFft
            binding.specIn.init(currentNFft)
            binding.specDen.init(currentNFft)

            log("─── Avvio audio")
            log("STFT: n_fft=$nFft  hop=$hopLength  win=$winLength")
            log("Input : ${inputItem.name}")
            log("Output: ${outputItem.name}")

            val needsSco = inputItem.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                           outputItem.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            if (needsSco) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                scoReceiver?.let { context.unregisterReceiver(it) }
                scoReceiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val scoState = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                        when (scoState) {
                            AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                                ctx.unregisterReceiver(this)
                                scoReceiver = null
                                log("SCO connesso")
                                doStartEngine(inputItem, outputItem, nFft, hopLength, winLength)
                            }
                            AudioManager.SCO_AUDIO_STATE_ERROR -> {
                                ctx.unregisterReceiver(this)
                                scoReceiver = null
                                log("ERRORE: connessione SCO fallita")
                                Toast.makeText(ctx, "Connessione Bluetooth SCO fallita", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                context.registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
                @Suppress("DEPRECATION")
                audioManager.startBluetoothSco()
                log("Attivazione SCO in corso...")
            } else {
                doStartEngine(inputItem, outputItem, nFft, hopLength, winLength)
            }
        } catch (e: NumberFormatException) {
            Toast.makeText(context, "Valori STFT devono essere numeri interi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun doStartEngine(inputItem: AudioDeviceItem, outputItem: AudioDeviceItem, nFft: Int, hopLength: Int, winLength: Int) {
        val ok = NativeBridge.startAudioEngine(
            inputItem.id, outputItem.id, currentModelPath, nFft, hopLength, winLength
        )
        if (ok) {
            isPlaying = true
            setControlsEnabled(false)
            binding.recButton.setBackgroundResource(R.drawable.bg_rec_dev_recording)
            binding.recButton.text = "STOP"
            binding.recButton.setTextColor(ContextCompat.getColor(context, R.color.colorError))
            binding.statusText.text = "Audio Engine Running"
            handler.post(uiUpdater)
            log("Engine avviato")
        } else {
            log("ERRORE: engine non avviato")
            Toast.makeText(context, "Failed to start audio engine", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopAudio() {
        if (!isPlaying) return
        NativeBridge.stopAudioEngine()
        isPlaying = false
        handler.removeCallbacks(uiUpdater)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        scoReceiver?.let { context.unregisterReceiver(it); scoReceiver = null }
        @Suppress("DEPRECATION")
        audioManager.stopBluetoothSco()
        setControlsEnabled(true)
        binding.recButton.setBackgroundResource(R.drawable.bg_rec_dev_idle)
        binding.recButton.text = "REC"
        binding.recButton.setTextColor(ContextCompat.getColor(context, R.color.colorPrimary))
        binding.statusText.text = "Ready"
        binding.latencyText.text = "HW: N/A  |  DSP: N/A  |  Infer: N/A"
        log("Engine fermato")
    }

    private fun setControlsEnabled(enabled: Boolean) {
        binding.modelSpinner.isEnabled = enabled
        binding.inputSpinner.isEnabled = enabled
        binding.outputSpinner.isEnabled = enabled
        binding.nFftInput.isEnabled = enabled
        binding.hopLengthInput.isEnabled = enabled
        binding.winLengthInput.isEnabled = enabled
    }

    // ── Console ──────────────────────────────────────────────────────────────

    fun log(msg: String) {
        val ts = timeFormatter.format(Date())
        val line = "[$ts] $msg"
        handler.post {
            consoleLines.addLast(line)
            while (consoleLines.size > maxConsoleLines) consoleLines.removeFirst()
            binding.consoleText.text = consoleLines.joinToString("\n")
            binding.consoleScroll.post {
                binding.consoleScroll.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    fun onStop() {
        if (isPlaying) stopAudio()
    }
}
