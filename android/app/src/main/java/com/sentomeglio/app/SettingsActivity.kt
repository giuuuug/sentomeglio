package com.sentomeglio.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sentomeglio.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        prefs = AppPreferences(this)

        binding.devModeSwitch.isChecked = prefs.devMode
        binding.devModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.devMode = isChecked
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
