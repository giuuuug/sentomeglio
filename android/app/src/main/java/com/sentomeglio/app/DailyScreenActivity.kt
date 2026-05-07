package com.sentomeglio.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.Spinner
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.sentomeglio.app.databinding.ActivityDailyBinding
import java.io.File
import java.io.FileOutputStream

class DailyScreenActivity : BaseAudioActivity() {

    private lateinit var binding: ActivityDailyBinding

    override val inputSpinner: Spinner get() = binding.content.inputSpinner
    override val outputSpinner: Spinner get() = binding.content.outputSpinner

    private enum class State { IDLE, RECORDING }

    private var state = State.IDLE
    private var elapsedSeconds = 0
    private var timerRunnable: Runnable? = null
    private var pulseAnimator: AnimatorSet? = null
    private var blinkAnim: AlphaAnimation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDailyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar(binding.toolbar)
        populateDeviceLists()
        setupButton()
        updateUi()
    }

    override fun onStart() {
        super.onStart()
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (AppPreferences(this).devMode) {
            if (state == State.RECORDING) stopRecording()
            startActivity(Intent(this, DevScreenActivity::class.java))
            finish()
            return
        }
        if (!AudioService.isRunning) syncToIdle()
    }

    override fun isAudioActive() = state == State.RECORDING

    override fun onActiveDeviceDisconnected() {
        stopRecording()
        Toast.makeText(this, "Dispositivo audio disconnesso, registrazione fermata", Toast.LENGTH_SHORT).show()
    }

    override fun onScoConnected(inputItem: AudioDeviceItem, outputItem: AudioDeviceItem) {
        doStartEngine(inputItem, outputItem)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (state == State.IDLE) startRecording()
            } else {
                Toast.makeText(this, "Permessi necessari per microfono/notifiche", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Button ───────────────────────────────────────────────────────────────

    private fun setupButton() {
        binding.content.recButton.setOnClickListener {
            when (state) {
                State.IDLE -> startRecording()
                State.RECORDING -> stopRecording()
            }
        }
        val micIcon = ContextCompat.getDrawable(this, R.drawable.ic_mic)
        binding.content.recButton.setCompoundDrawablesWithIntrinsicBounds(null, micIcon, null, null)
        binding.content.recButton.text = "REC"
        binding.content.recButton.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary))
    }

    // ── Recording control ────────────────────────────────────────────────────

    private fun startRecording() {
        val inputItem = binding.content.inputSpinner.selectedItem as? AudioDeviceItem ?: return
        val outputItem = binding.content.outputSpinner.selectedItem as? AudioDeviceItem ?: return
        val needsSco = inputItem.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                       outputItem.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        if (needsSco) activateScoAndStart(inputItem, outputItem) else doStartEngine(inputItem, outputItem)
    }

    private fun doStartEngine(inputItem: AudioDeviceItem, outputItem: AudioDeviceItem) {
        val modelPath = loadDefaultModel() ?: run {
            Toast.makeText(this, "Nessun modello ONNX trovato", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = NativeBridge.startAudioEngine(
            inputId = inputItem.id, outputId = outputItem.id, modelPath = modelPath,
            nFft = 512, hopLength = 128, winLength = 320
        )
        if (!ok) return
        AudioService.show(this)
        state = State.RECORDING
        setSpinnersEnabled(false)
        updateUi()
        startTimer()
        startPulse()
    }

    private fun stopRecording() {
        NativeBridge.stopAudioEngine()
        AudioService.dismiss(this)
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        cleanupSco()
        am.mode = previousAudioMode
        state = State.IDLE
        setSpinnersEnabled(true)
        stopTimer()
        stopPulse()
        updateUi()
    }

    private fun syncToIdle() {
        if (state != State.RECORDING) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        cleanupSco()
        am.mode = previousAudioMode
        state = State.IDLE
        setSpinnersEnabled(true)
        stopTimer()
        stopPulse()
        updateUi()
    }

    private fun setSpinnersEnabled(enabled: Boolean) {
        binding.content.inputSpinner.isEnabled = enabled
        binding.content.outputSpinner.isEnabled = enabled
    }

    private fun loadDefaultModel(): String? {
        val modelName = assets.list("")?.find { it == "High.onnx" } ?: return null
        val outFile = File(cacheDir, modelName)
        return try {
            assets.open(modelName).use { input -> FileOutputStream(outFile).use { input.copyTo(it) } }
            outFile.absolutePath
        } catch (_: Exception) { null }
    }

    // ── UI ───────────────────────────────────────────────────────────────────

    private fun updateUi() {
        val primary = ContextCompat.getColor(this, R.color.colorPrimary)
        val error = ContextCompat.getColor(this, R.color.colorError)
        when (state) {
            State.IDLE -> {
                binding.content.recButton.backgroundTintList = null
                binding.content.recButton.setBackgroundResource(R.drawable.bg_rec_idle)
                binding.content.recButton.setCompoundDrawablesWithIntrinsicBounds(
                    null, ContextCompat.getDrawable(this, R.drawable.ic_mic), null, null)
                binding.content.recButton.text = "REC"
                binding.content.recButton.setTextColor(primary)
                binding.content.timerRow.visibility = View.INVISIBLE
                binding.content.statusHint.text = "Tocca per iniziare la registrazione"
                binding.content.statusHint.alpha = 0.7f
                stopBlink()
            }
            State.RECORDING -> {
                binding.content.recButton.setBackgroundResource(R.drawable.bg_rec_recording)
                binding.content.recButton.setCompoundDrawablesWithIntrinsicBounds(
                    null, ContextCompat.getDrawable(this, R.drawable.ic_stop), null, null)
                binding.content.recButton.text = "STOP"
                binding.content.recButton.setTextColor(error)
                binding.content.timerRow.visibility = View.VISIBLE
                binding.content.statusHint.text = "Speech enhancement attivo…"
                binding.content.statusHint.alpha = 1f
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
        binding.content.timerText.text = "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
    }

    // ── Pulse animation ──────────────────────────────────────────────────────

    private fun startPulse() {
        pulseAnimator?.cancel()
        val ring = binding.content.pulseRing
        pulseAnimator = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(ring, View.SCALE_X, 1f, 1.6f),
                ObjectAnimator.ofFloat(ring, View.SCALE_Y, 1f, 1.6f),
                ObjectAnimator.ofFloat(ring, View.ALPHA, 0.35f, 0f)
            )
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
        binding.content.pulseRing.apply { alpha = 0f; scaleX = 1f; scaleY = 1f }
    }

    // ── Blink ────────────────────────────────────────────────────────────────

    private fun startBlink() {
        blinkAnim = AlphaAnimation(1f, 0f).apply {
            duration = 500
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }
        binding.content.recDot.startAnimation(blinkAnim)
    }

    private fun stopBlink() {
        blinkAnim?.cancel()
        binding.content.recDot.clearAnimation()
        binding.content.recDot.alpha = 1f
    }
}
