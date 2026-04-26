package com.sentomeglio.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
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
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.sentomeglio.app.databinding.LayoutDailyBinding

class DailyScreenManager(
    private val binding: LayoutDailyBinding,
    private val onStartRecording: (inputId: Int, outputId: Int) -> Boolean,
    private val onStopRecording: () -> Unit,
    private val onSettingsRequested: () -> Unit
) {

    data class AudioDeviceItem(val name: String, val id: Int, val type: Int) {
        override fun toString() = name
    }

    enum class State { IDLE, RECORDING }

    private val context: Context get() = binding.root.context
    private var state = State.IDLE
    private var elapsedSeconds = 0
    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    private var pulseAnimator: AnimatorSet? = null
    private var blinkAnim: AlphaAnimation? = null

    private val inputDevices = mutableListOf<AudioDeviceItem>()
    private val outputDevices = mutableListOf<AudioDeviceItem>()
    private var scoReceiver: BroadcastReceiver? = null
    private var scoTimeoutRunnable: Runnable? = null
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL

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

    // ── Dynamic device detection ─────────────────────────────────────────────

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            handler.post { refreshDeviceLists() }
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            handler.post {
                refreshDeviceLists()
                // If we are recording and a selected device was removed, stop
                if (state == State.RECORDING) {
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val currentInputId = (binding.inputSpinner.selectedItem as? AudioDeviceItem)?.id
                    val currentOutputId = (binding.outputSpinner.selectedItem as? AudioDeviceItem)?.id
                    if (currentInputId != null && inputDevices.none { it.id == currentInputId } ||
                        currentOutputId != null && outputDevices.none { it.id == currentOutputId }) {
                        stopRecording()
                        Toast.makeText(context, "Dispositivo audio disconnesso, registrazione fermata", Toast.LENGTH_SHORT).show()
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
                    // Small delay to let the system register the audio device
                    handler.postDelayed({ refreshDeviceLists() }, 500)
                }
            }
        }
    }

    init {
        populateDeviceLists()
        registerDeviceCallbacks()
        setupButton()
        updateUi()
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

    // ── Device selection ─────────────────────────────────────────────────────

    private fun populateDeviceLists() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        inputDevices.clear()
        outputDevices.clear()

        for (device in audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (device.type !in SUPPORTED_INPUT_TYPES) continue
            inputDevices.add(AudioDeviceItem(deviceLabel(device), device.id, device.type))
        }

        for (device in audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.type !in SUPPORTED_OUTPUT_TYPES) continue
            outputDevices.add(AudioDeviceItem(deviceLabel(device), device.id, device.type))
        }

        val inAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, inputDevices)
        inAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.inputSpinner.adapter = inAdapter

        val outAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, outputDevices)
        outAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.outputSpinner.adapter = outAdapter
    }

    private fun refreshDeviceLists() {
        // Save current selections
        val prevInputId = (binding.inputSpinner.selectedItem as? AudioDeviceItem)?.id
        val prevOutputId = (binding.outputSpinner.selectedItem as? AudioDeviceItem)?.id

        populateDeviceLists()

        // Restore previous selections if they still exist
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
    }

    private fun deviceLabel(device: AudioDeviceInfo): String {
        val typeName = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC      -> "Microfono"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER  -> "Altoparlante"
            AudioDeviceInfo.TYPE_WIRED_HEADSET    -> "Auricolari cablati"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Cuffie cablate"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO    -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP   -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_BLE_HEADSET      -> "BLE Headset"
            AudioDeviceInfo.TYPE_BLE_SPEAKER      -> "BLE Speaker"
            AudioDeviceInfo.TYPE_USB_DEVICE       -> "USB"
            AudioDeviceInfo.TYPE_USB_HEADSET      -> "Cuffie USB"
            else                                  -> "Dispositivo ${device.id}"
        }
        val productName = device.productName.toString().takeIf { it.isNotBlank() && it != "null" }
        return if (productName != null) "$productName ($typeName)" else typeName
    }

    private fun setupButton() {
        binding.recButton.setOnClickListener {
            when (state) {
                State.IDLE -> startRecording()
                State.RECORDING -> stopRecording()
            }
        }
        // Place mic icon programmatically so it's always centred
        val micIcon = ContextCompat.getDrawable(binding.root.context, R.drawable.ic_mic)
        binding.recButton.setCompoundDrawablesWithIntrinsicBounds(null, micIcon, null, null)
        binding.recButton.text = "REC"
        binding.recButton.setTextColor(ContextCompat.getColor(binding.root.context, R.color.colorPrimary))
    }

    private fun startRecording() {
        val inputItem = binding.inputSpinner.selectedItem as? AudioDeviceItem ?: return
        val outputItem = binding.outputSpinner.selectedItem as? AudioDeviceItem ?: return
        val needsSco = inputItem.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                       outputItem.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        if (needsSco) {
            activateScoAndStart(inputItem, outputItem)
        } else {
            doStart(inputItem, outputItem)
        }
    }

    private fun doStart(inputItem: AudioDeviceItem, outputItem: AudioDeviceItem) {
        val ok = onStartRecording(inputItem.id, outputItem.id)
        if (!ok) return
        AudioService.show(context)
        state = State.RECORDING
        setSpinnersEnabled(false)
        updateUi()
        startTimer()
        startPulse()
        binding.waveformView.setActive(true)
    }

    private fun activateScoAndStart(inputItem: AudioDeviceItem, outputItem: AudioDeviceItem) {
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
                        doStart(inputItem, outputItem)
                    }
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        cancelScoTimeout()
                        cleanupScoReceiver()
                        audioManager.mode = previousAudioMode
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
            Toast.makeText(context, "Timeout connessione Bluetooth SCO", Toast.LENGTH_SHORT).show()
        }
        handler.postDelayed(scoTimeoutRunnable!!, SCO_TIMEOUT_MS)

        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()
    }

    private fun stopRecording() {
        onStopRecording()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        cleanupSco()
        // Restore audio mode
        audioManager.mode = previousAudioMode
        state = State.IDLE
        setSpinnersEnabled(true)
        stopTimer()
        stopPulse()
        binding.waveformView.setActive(false)
        updateUi()
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

    private fun setSpinnersEnabled(enabled: Boolean) {
        binding.inputSpinner.isEnabled = enabled
        binding.outputSpinner.isEnabled = enabled
    }

    private fun updateUi() {
        val primary = ContextCompat.getColor(binding.root.context, R.color.colorPrimary)
        val error = ContextCompat.getColor(binding.root.context, R.color.colorError)

        when (state) {
            State.IDLE -> {
                binding.recButton.backgroundTintList = null
                binding.recButton.setBackgroundResource(R.drawable.bg_rec_idle)
                val micIcon = ContextCompat.getDrawable(binding.root.context, R.drawable.ic_mic)
                binding.recButton.setCompoundDrawablesWithIntrinsicBounds(null, micIcon, null, null)
                binding.recButton.text = "REC"
                binding.recButton.setTextColor(primary)
                binding.timerRow.visibility = View.INVISIBLE
                binding.statusHint.text = "Tocca per iniziare la registrazione"
                binding.statusHint.alpha = 0.7f
                stopBlink()
            }
            State.RECORDING -> {
                binding.recButton.setBackgroundResource(R.drawable.bg_rec_recording)
                val stopIcon = ContextCompat.getDrawable(binding.root.context, R.drawable.ic_stop)
                binding.recButton.setCompoundDrawablesWithIntrinsicBounds(null, stopIcon, null, null)
                binding.recButton.text = "STOP"
                binding.recButton.setTextColor(error)
                binding.timerRow.visibility = View.VISIBLE
                binding.statusHint.text = "Speech enhancement attivo…"
                binding.statusHint.alpha = 1f
                startBlink()
            }
        }
    }

    // ── Timer ────────────────────────────────────────────────────────────────

    private fun startTimer() {
        elapsedSeconds = 0
        updateTimerDisplay()
        timerRunnable = object : Runnable {
            override fun run() {
                elapsedSeconds++
                updateTimerDisplay()
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(timerRunnable!!, 1000)
    }

    private fun stopTimer() {
        timerRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable = null
        elapsedSeconds = 0
        updateTimerDisplay()
    }

    private fun updateTimerDisplay() {
        val m = elapsedSeconds / 60
        val s = elapsedSeconds % 60
        binding.timerText.text = "%02d:%02d".format(m, s)
    }

    // ── Pulse animation ──────────────────────────────────────────────────────

    private fun startPulse() {
        pulseAnimator?.cancel()
        val ring = binding.pulseRing
        val scaleX = ObjectAnimator.ofFloat(ring, View.SCALE_X, 1f, 1.6f)
        val scaleY = ObjectAnimator.ofFloat(ring, View.SCALE_Y, 1f, 1.6f)
        val alpha = ObjectAnimator.ofFloat(ring, View.ALPHA, 0.35f, 0f)
        pulseAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 1400
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    ring.scaleX = 1f; ring.scaleY = 1f; ring.alpha = 0f
                    if (state == State.RECORDING) start()
                }
            })
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.pulseRing.alpha = 0f
        binding.pulseRing.scaleX = 1f
        binding.pulseRing.scaleY = 1f
    }

    // ── Blink dot ────────────────────────────────────────────────────────────

    private fun startBlink() {
        blinkAnim = AlphaAnimation(1f, 0f).apply {
            duration = 500
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }
        binding.recDot.startAnimation(blinkAnim)
    }

    private fun stopBlink() {
        blinkAnim?.cancel()
        binding.recDot.clearAnimation()
        binding.recDot.alpha = 1f
    }

    fun retryStartRecording() {
        if (state == State.IDLE) startRecording()
    }

    fun onStart() {
        registerDeviceCallbacks()
    }

    fun onStop() {
        unregisterDeviceCallbacks()
    }

    fun syncToIdle() {
        if (state != State.RECORDING) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        cleanupSco()
        audioManager.mode = previousAudioMode
        state = State.IDLE
        setSpinnersEnabled(true)
        stopTimer()
        stopPulse()
        binding.waveformView.setActive(false)
        updateUi()
    }
}
