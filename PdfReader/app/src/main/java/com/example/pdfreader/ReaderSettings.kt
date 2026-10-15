package com.example.pdfreader

import android.content.Context
import com.example.pdfreader.core.SpreadMode
import com.example.pdfreader.core.ViewMode

/** Reader preferences that persist across launches. */
class ReaderSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var viewMode: ViewMode
        get() = enumValue(prefs.getString(KEY_VIEW_MODE, null), ViewMode.CONTINUOUS)
        set(value) = prefs.edit().putString(KEY_VIEW_MODE, value.name).apply()

    var spreadMode: SpreadMode
        get() = enumValue(prefs.getString(KEY_SPREAD_MODE, null), SpreadMode.NONE)
        set(value) = prefs.edit().putString(KEY_SPREAD_MODE, value.name).apply()

    var volumeKeysTurnPages: Boolean
        get() = prefs.getBoolean(KEY_VOLUME_KEYS, false)
        set(value) = prefs.edit().putBoolean(KEY_VOLUME_KEYS, value).apply()

    /** The fit mode applied when a document opens, remembered per view mode. */
    fun fitMode(viewMode: ViewMode): FitMode {
        val default = if (viewMode == ViewMode.SINGLE_PAGE) FitMode.FIT_PAGE else FitMode.FIT_WIDTH
        return enumValue(prefs.getString(KEY_FIT_MODE + viewMode.name, null), default)
    }

    fun setFitMode(viewMode: ViewMode, mode: FitMode) =
        prefs.edit().putString(KEY_FIT_MODE + viewMode.name, mode.name).apply()

    var darkPages: Boolean
        get() = prefs.getBoolean(KEY_DARK_PAGES, false)
        set(value) = prefs.edit().putBoolean(KEY_DARK_PAGES, value).apply()

    private inline fun <reified T : Enum<T>> enumValue(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    companion object {
        const val PREFS_NAME = "settings"
        private const val KEY_VIEW_MODE = "view_mode"
        private const val KEY_SPREAD_MODE = "spread_mode"
        private const val KEY_VOLUME_KEYS = "volume_keys"
        private const val KEY_DARK_PAGES = "dark_pages"
        private const val KEY_FIT_MODE = "fit_mode_"
    }
}
