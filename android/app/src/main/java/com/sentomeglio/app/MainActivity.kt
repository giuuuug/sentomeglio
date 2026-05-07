package com.sentomeglio.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = AppPreferences(this)
        val target = if (prefs.devMode) DevScreenActivity::class.java else DailyScreenActivity::class.java
        startActivity(Intent(this, target))
        finish()
    }
}
