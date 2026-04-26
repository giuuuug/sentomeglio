package com.sentomeglio.app

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
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
        private val SUPPORTED_INPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET
        )
        private val SUPPORTED_OUTPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER
        )
        private const val SCO_TIMEOUT_MS = 8000L
    }

    private val context: Context get() = binding.root.context
    private val handler = Handler(Looper.getMainLooper())

    private val inputDevices = mutableListOf<AudioDeviceItem>()
    private val outputDevices = mutableListOf<AudioDeviceItem>()
    private val onnxModels = mutableListOf<String>()
    private var scoReceiver: BroadcastReceiver? = null
    private var scoTimeoutRunnable: Runnable? = null
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL

    var isPlaying = false
        private set

    private var currentModelPath = ""
    private var currentNFft = 512
    private var currentHopLength = 128

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
                val frameBudgetMs = currentHopLength * 1000.0 / 16000.0
                val rtf = (dspMs + inferMs) / frameBudgetMs
                binding.metricsText.text = String.format("RTF: %.3f", rtf)
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

    // ── Dynamic device detection ─────────────────────────────────────────────

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            handler.post { refreshDeviceLists() }
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            handler.post {
                refreshDeviceLists()
                if (isPlaying) {
                    val currentInputId = (binding.inputSpinner.selectedItem as? AudioDeviceItem)?.id
                    val currentOutputId = (binding.outputSpinner.selectedItem as? AudioDeviceItem)?.id
                    if (currentInputId != null && inputDevices.none { it.id == currentInputId } ||
                        currentOutputId != null && outputDevices.none { it.id == currentOutputId }) {
                        stopAudio()
                        log("WARN: dispositivo disconnesso, engine fermato")
                        Toast.makeText(context, "Dispositivo audio disconnesso", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED" -> {
                    handler.postDelayed({ refreshDeviceLists() }, 500)
                }
            }
        }
    }

    init {
        binding.consoleScroll.setOnTouchListener { v, _ ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            false
        }
        populateDeviceLists()
        registerDeviceCallbacks()
        populateModelSpinner()
        setupRecButton()
    }

    private fun registerDeviceCallbacks() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)

        val btFilter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
        }
        context.registerReceiver(bluetoothReceiver, btFilter)
    }

    private fun unregisterDeviceCallbacks() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        try { context.unregisterReceiver(bluetoothReceiver) } catch (_: Exception) {}
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

        for (device in audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (device.type !in SUPPORTED_INPUT_TYPES) continue
            val typeStr = deviceTypeStr(device.type)
            val name = device.productName.toString().takeIf { it.isNotBlank() && it != "null" } ?: typeStr
            inputDevices.add(AudioDeviceItem("$name ($typeStr)", device.id, device.type))
        }

        for (device in audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.type !in SUPPORTED_OUTPUT_TYPES) continue
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

    private fun refreshDeviceLists() {
        val prevInputId = (binding.inputSpinner.selectedItem as? AudioDeviceItem)?.id
        val prevOutputId = (binding.outputSpinner.selectedItem as? AudioDeviceItem)?.id

        populateDeviceLists()

        prevInputId?.let { id ->
            inputDevices.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let {
                binding.inputSpinner.setSelection(it)
            }
        }
        prevOutputId?.let { id ->
            outputDevices.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let {
                binding.outputSpinner.setSelection(it)
            }
        }

        log("Dispositivi aggiornati: ${inputDevices.size} in / ${outputDevices.size} out")
    }

    private fun deviceTypeStr(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC      -> "Built-in Mic"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER  -> "Built-in Speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET    -> "Wired Headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO    -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP   -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLE_HEADSET      -> "BLE Headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER      -> "BLE Speaker"
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
            currentHopLength = hopLength
            binding.specIn.init(currentNFft)
            binding.specDen.init(currentNFft)

            log("─── Avvio audio")
            log("STFT: n_fft=$nFft  hop=$hopLength  win=$winLength")
            log("Input : ${inputItem.name}")
            log("Output: ${outputItem.name}")

            val needsSco = inputItem.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                           outputItem.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            if (needsSco) {
                activateScoAndStart(inputItem, outputItem, nFft, hopLength, winLength)
            } else {
                doStartEngine(inputItem, outputItem, nFft, hopLength, winLength)
            }
        } catch (e: NumberFormatException) {
            Toast.makeText(context, "Valori STFT devono essere numeri interi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun activateScoAndStart(inputItem: AudioDeviceItem, outputItem: AudioDeviceItem, nFft: Int, hopLength: Int, winLength: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Save current mode and switch to COMMUNICATION — required for SCO routing
        previousAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        cleanupSco()

        scoReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val scoState = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                when (scoState) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        cancelScoTimeout()
                        cleanupScoReceiver()
                        log("SCO connesso")
                        doStartEngine(inputItem, outputItem, nFft, hopLength, winLength)
                    }
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        cancelScoTimeout()
                        cleanupScoReceiver()
                        audioManager.mode = previousAudioMode
                        log("ERRORE: connessione SCO fallita")
                        Toast.makeText(ctx, "Connessione Bluetooth SCO fallita", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        context.registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))

        // Timeout in case SCO never connects
        scoTimeoutRunnable = Runnable {
            cleanupSco()
            audioManager.mode = previousAudioMode
            log("ERRORE: timeout connessione SCO")
            Toast.makeText(context, "Timeout connessione Bluetooth SCO", Toast.LENGTH_SHORT).show()
        }
        handler.postDelayed(scoTimeoutRunnable!!, SCO_TIMEOUT_MS)

        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()
        log("Attivazione SCO in corso...")
    }

    private fun doStartEngine(inputItem: AudioDeviceItem, outputItem: AudioDeviceItem, nFft: Int, hopLength: Int, winLength: Int) {
        val ok = NativeBridge.startAudioEngine(
            inputItem.id, outputItem.id, currentModelPath, nFft, hopLength, winLength
        )
        if (ok) {
            AudioService.show(context)
            isPlaying = true
            setControlsEnabled(false)
            binding.recButton.setBackgroundResource(R.drawable.bg_rec_dev_recording)
            binding.recButton.text = "STOP"
            binding.recButton.setTextColor(ContextCompat.getColor(context, R.color.colorError))
            binding.statusText.text = "Audio Engine Running"
            handler.post(uiUpdater)
            log("Engine avviato")
        } else {
            // If SCO was activated, restore audio mode
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = previousAudioMode
            log("ERRORE: engine non avviato")
            Toast.makeText(context, "Failed to start audio engine", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopAudio() {
        if (!isPlaying) return
        NativeBridge.stopAudioEngine()
        AudioService.dismiss(context)
        isPlaying = false
        handler.removeCallbacks(uiUpdater)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        cleanupSco()
        audioManager.mode = previousAudioMode
        setControlsEnabled(true)
        binding.recButton.setBackgroundResource(R.drawable.bg_rec_dev_idle)
        binding.recButton.text = "REC"
        binding.recButton.setTextColor(ContextCompat.getColor(context, R.color.colorPrimary))
        binding.statusText.text = "Ready"
        binding.latencyText.text = "HW: N/A  |  DSP: N/A  |  Infer: N/A"
        binding.metricsText.text = "RTF: N/A"
        log("Engine fermato")
    }

    private fun cleanupSco() {
        cancelScoTimeout()
        cleanupScoReceiver()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        @Suppress("DEPRECATION")
        audioManager.stopBluetoothSco()
    }

    private fun cleanupScoReceiver() {
        scoReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
            scoReceiver = null
        }
    }

    private fun cancelScoTimeout() {
        scoTimeoutRunnable?.let { handler.removeCallbacks(it) }
        scoTimeoutRunnable = null
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

    fun onStart() {
        registerDeviceCallbacks()
    }

    fun onStop() {
        unregisterDeviceCallbacks()
    }

    fun syncToIdle() {
        if (!isPlaying) return
        isPlaying = false
        handler.removeCallbacks(uiUpdater)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        cleanupSco()
        audioManager.mode = previousAudioMode
        setControlsEnabled(true)
        binding.recButton.setBackgroundResource(R.drawable.bg_rec_dev_idle)
        binding.recButton.text = "REC"
        binding.recButton.setTextColor(ContextCompat.getColor(context, R.color.colorPrimary))
        binding.statusText.text = "Ready"
        binding.latencyText.text = "HW: N/A  |  DSP: N/A  |  Infer: N/A"
        binding.metricsText.text = "RTF: N/A"
    }
}
