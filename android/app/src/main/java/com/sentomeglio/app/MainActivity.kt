package com.sentomeglio.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.sentomeglio.app.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences

    private lateinit var dailyManager: DailyScreenManager
    private lateinit var devManager: DevScreenManager

    private val PERMISSIONS_REQUEST_CODE = 123
    private lateinit var toolbarDevBadge: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        val titleView = layoutInflater.inflate(R.layout.layout_toolbar_title, binding.toolbar, false)
        binding.toolbar.addView(titleView)
        toolbarDevBadge = titleView.findViewById(R.id.toolbarDevBadge)

        prefs = AppPreferences(this)

        dailyManager = DailyScreenManager(
            binding = binding.screenDaily,
            onStartRecording = { inputId, outputId -> startDailyAudio(inputId, outputId) },
            onStopRecording = {
                NativeBridge.stopAudioEngine()
                AudioService.dismiss(this)
            },
            onSettingsRequested = { openSettings() }
        )

        devManager = DevScreenManager(
            binding = binding.screenDev,
            onSettingsRequested = { openSettings() }
        )

        applyScreenMode()
    }

    override fun onStart() {
        super.onStart()
        dailyManager.onStart()
        devManager.onStart()
    }

    override fun onResume() {
        super.onResume()
        applyScreenMode()
        // If the service was stopped from the notification while the app was away,
        // sync manager UI back to idle without touching the engine.
        if (!AudioService.isRunning) {
            dailyManager.syncToIdle()
            devManager.syncToIdle()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            openSettings()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun applyScreenMode() {
        if (prefs.devMode) {
            binding.screenDaily.root.visibility = View.GONE
            binding.screenDev.root.visibility = View.VISIBLE
            toolbarDevBadge.visibility = View.VISIBLE
        } else {
            binding.screenDev.root.visibility = View.GONE
            binding.screenDaily.root.visibility = View.VISIBLE
            toolbarDevBadge.visibility = View.GONE
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ── Daily audio ──────────────────────────────────────────────────────────

    private fun startDailyAudio(inputId: Int, outputId: Int): Boolean {
        if (!checkPermissions()) return false
        val modelPath = loadDefaultModel() ?: run {
            Toast.makeText(this, "Nessun modello ONNX trovato", Toast.LENGTH_SHORT).show()
            return false
        }
        return NativeBridge.startAudioEngine(
            inputId = inputId, outputId = outputId,
            modelPath = modelPath,
            nFft = 512, hopLength = 128, winLength = 320
        )
    }

    private fun loadDefaultModel(): String? {
        val modelName = assets.list("")?.firstOrNull { it.endsWith(".onnx") } ?: return null
        val outFile = File(cacheDir, modelName)
        return try {
            assets.open(modelName).use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            outFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    // ── Permissions ──────────────────────────────────────────────────────────

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSIONS_REQUEST_CODE)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                dailyManager.retryStartRecording()
            } else {
                Toast.makeText(this, "Permesso microfono necessario", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        dailyManager.onStop()
        devManager.onStop()
        // Do NOT stop audio here — the foreground service keeps it running in background.
    }
}
