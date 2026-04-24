package com.sentomeglio.app

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("sentomeglio_prefs", Context.MODE_PRIVATE)

    var devMode: Boolean
        get() = prefs.getBoolean(KEY_DEV_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DEV_MODE, value).apply()

    companion object {
        private const val KEY_DEV_MODE = "dev_mode"
    }
}
