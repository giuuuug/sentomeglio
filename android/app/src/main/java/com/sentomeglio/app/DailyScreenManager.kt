package com.sentomeglio.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    init {
        populateDeviceLists()
        setupButton()
        updateUi()
    }

    // ── Device selection ─────────────────────────────────────────────────────

    private fun populateDeviceLists() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        inputDevices.clear()
        outputDevices.clear()

        val seenInputTypes = mutableSetOf<Int>()
        for (device in audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (device.type !in LOW_LATENCY_INPUT_TYPES) continue
            if (!seenInputTypes.add(device.type)) continue
            inputDevices.add(AudioDeviceItem(deviceLabel(device), device.id, device.type))
        }

        val seenOutputTypes = mutableSetOf<Int>()
        for (device in audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.type !in LOW_LATENCY_OUTPUT_TYPES) continue
            if (!seenOutputTypes.add(device.type)) continue
            outputDevices.add(AudioDeviceItem(deviceLabel(device), device.id, device.type))
        }

        val inAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, inputDevices)
        inAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.inputSpinner.adapter = inAdapter

        val outAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, outputDevices)
        outAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.outputSpinner.adapter = outAdapter
    }

    private fun deviceLabel(device: AudioDeviceInfo): String {
        val typeName = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC      -> "Microfono"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER  -> "Altoparlante"
            AudioDeviceInfo.TYPE_WIRED_HEADSET    -> "Auricolari cablati"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Cuffie cablate"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO    -> "Bluetooth SCO"
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
        state = State.RECORDING
        setSpinnersEnabled(false)
        updateUi()
        startTimer()
        startPulse()
        binding.waveformView.setActive(true)
    }

    private fun activateScoAndStart(inputItem: AudioDeviceItem, outputItem: AudioDeviceItem) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        scoReceiver?.let { context.unregisterReceiver(it) }
        scoReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val scoState = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                when (scoState) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        ctx.unregisterReceiver(this)
                        scoReceiver = null
                        doStart(inputItem, outputItem)
                    }
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        ctx.unregisterReceiver(this)
                        scoReceiver = null
                        Toast.makeText(ctx, "Connessione Bluetooth SCO fallita", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        context.registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()
    }

    private fun stopRecording() {
        onStopRecording()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        scoReceiver?.let { context.unregisterReceiver(it); scoReceiver = null }
        @Suppress("DEPRECATION")
        audioManager.stopBluetoothSco()
        state = State.IDLE
        setSpinnersEnabled(true)
        stopTimer()
        stopPulse()
        binding.waveformView.setActive(false)
        updateUi()
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

    fun onStop() {
        if (state == State.RECORDING) stopRecording()
    }
}
