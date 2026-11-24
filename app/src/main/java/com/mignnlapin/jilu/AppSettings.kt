package com.mignnlapin.jilu

import android.content.Context

object AppSettings {
    private const val PREFS_NAME = "jilu_settings"
    private const val KEY_AMAP_API_KEY = "amap_api_key"
    private const val KEY_LAST_TRACK_PATH = "last_track_path"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAmapApiKey(context: Context): String =
        prefs(context).getString(KEY_AMAP_API_KEY, "").orEmpty()

    fun setAmapApiKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_AMAP_API_KEY, value).apply()
    }

    fun getLastTrackPath(context: Context): String? =
        prefs(context).getString(KEY_LAST_TRACK_PATH, null)

    fun setLastTrackPath(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LAST_TRACK_PATH, value).apply()
    }
}
