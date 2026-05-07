package com.sentomeglio.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar

abstract class BaseAudioActivity : AppCompatActivity() {

    data class AudioDeviceItem(val name: String, val id: Int, val type: Int) {
        override fun toString() = name
    }

    companion object {
        val SUPPORTED_INPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET
        )
        val SUPPORTED_OUTPUT_TYPES = setOf(
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
        const val SCO_TIMEOUT_MS = 8000L
        const val PERMISSIONS_REQUEST_CODE = 123
    }

    protected val handler = Handler(Looper.getMainLooper())
    protected val inputDevices = mutableListOf<AudioDeviceItem>()
    protected val outputDevices = mutableListOf<AudioDeviceItem>()

    private var scoReceiver: BroadcastReceiver? = null
    private var scoTimeoutRunnable: Runnable? = null
    protected var previousAudioMode: Int = AudioManager.MODE_NORMAL

    protected abstract val inputSpinner: Spinner
    protected abstract val outputSpinner: Spinner

    /** Returns true while audio engine is actively running. */
    protected abstract fun isAudioActive(): Boolean

    /** Called when a device used in the active session is disconnected. */
    protected abstract fun onActiveDeviceDisconnected()

    /** Called when SCO channel is ready; subclass starts the audio engine. */
    protected abstract fun onScoConnected(inputItem: AudioDeviceItem, outputItem: AudioDeviceItem)

    // ── Toolbar ──────────────────────────────────────────────────────────────

    protected fun setupToolbar(toolbar: MaterialToolbar, showDevBadge: Boolean = false) {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        val titleView = layoutInflater.inflate(R.layout.layout_toolbar_title, toolbar, false)
        toolbar.addView(titleView)
        titleView.findViewById<TextView>(R.id.toolbarDevBadge).visibility =
            if (showDevBadge) View.VISIBLE else View.GONE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) { openSettings(); return true }
        return super.onOptionsItemSelected(item)
    }

    // ── Device labels ────────────────────────────────────────────────────────

    protected open fun deviceLabel(device: AudioDeviceInfo): String {
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

    // ── Device callbacks ─────────────────────────────────────────────────────

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            handler.post { populateDeviceLists() }
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            handler.post {
                val prevInputId = (inputSpinner.selectedItem as? AudioDeviceItem)?.id
                val prevOutputId = (outputSpinner.selectedItem as? AudioDeviceItem)?.id
                populateDeviceLists()
                if (isAudioActive()) {
                    val inputGone = prevInputId != null && inputDevices.none { it.id == prevInputId }
                    val outputGone = prevOutputId != null && outputDevices.none { it.id == prevOutputId }
                    if (inputGone || outputGone) onActiveDeviceDisconnected()
                }
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED" ->
                    handler.postDelayed({ populateDeviceLists() }, 500)
            }
        }
    }

    private fun registerDeviceCallbacks() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(audioDeviceCallback, handler)
        val btFilter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
        }
        registerReceiver(bluetoothReceiver, btFilter)
    }

    private fun unregisterDeviceCallbacks() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.unregisterAudioDeviceCallback(audioDeviceCallback)
        try { unregisterReceiver(bluetoothReceiver) } catch (_: Exception) {}
    }

    // ── Device list population ───────────────────────────────────────────────

    protected fun populateDeviceLists() {
        val prevInputId = (inputSpinner.selectedItem as? AudioDeviceItem)?.id
        val prevOutputId = (outputSpinner.selectedItem as? AudioDeviceItem)?.id

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        inputDevices.clear()
        outputDevices.clear()
        for (d in am.getDevices(AudioManager.GET_DEVICES_INPUTS))
            if (d.type in SUPPORTED_INPUT_TYPES) inputDevices.add(AudioDeviceItem(deviceLabel(d), d.id, d.type))
        for (d in am.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if (d.type in SUPPORTED_OUTPUT_TYPES) outputDevices.add(AudioDeviceItem(deviceLabel(d), d.id, d.type))

        inputSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, inputDevices)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        outputSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, outputDevices)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        prevInputId?.let { id ->
            inputDevices.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { inputSpinner.setSelection(it) }
        }
        prevOutputId?.let { id ->
            outputDevices.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { outputSpinner.setSelection(it) }
        }
    }

    // ── SCO management ───────────────────────────────────────────────────────

    protected fun activateScoAndStart(inputItem: AudioDeviceItem, outputItem: AudioDeviceItem) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        previousAudioMode = am.mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        cleanupSco()
        scoReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val scoState = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                when (scoState) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        cancelScoTimeout(); cleanupScoReceiver()
                        onScoConnected(inputItem, outputItem)
                    }
                    AudioManager.SCO_AUDIO_STATE_ERROR -> {
                        cancelScoTimeout(); cleanupScoReceiver()
                        am.mode = previousAudioMode
                        Toast.makeText(ctx, "Connessione Bluetooth SCO fallita", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        scoTimeoutRunnable = Runnable {
            cleanupSco()
            am.mode = previousAudioMode
            Toast.makeText(this, "Timeout connessione Bluetooth SCO", Toast.LENGTH_SHORT).show()
        }
        handler.postDelayed(scoTimeoutRunnable!!, SCO_TIMEOUT_MS)
        @Suppress("DEPRECATION")
        am.startBluetoothSco()
    }

    protected fun cleanupSco() {
        cancelScoTimeout()
        cleanupScoReceiver()
        @Suppress("DEPRECATION")
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).stopBluetoothSco()
    }

    private fun cleanupScoReceiver() {
        scoReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {}; scoReceiver = null }
    }

    private fun cancelScoTimeout() {
        scoTimeoutRunnable?.let { handler.removeCallbacks(it) }
        scoTimeoutRunnable = null
    }

    // ── Permissions ──────────────────────────────────────────────────────────

    fun checkPermissions(): Boolean {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSIONS_REQUEST_CODE)
            return false
        }
        return true
    }

    fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    override fun onStart() {
        super.onStart()
        registerDeviceCallbacks()
    }

    override fun onStop() {
        super.onStop()
        unregisterDeviceCallbacks()
    }
}
