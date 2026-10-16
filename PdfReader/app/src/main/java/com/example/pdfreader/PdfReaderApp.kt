package com.example.pdfreader

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class PdfReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply the saved light/dark choice before any screen is shown.
        AppCompatDelegate.setDefaultNightMode(ReaderSettings(this).theme.nightMode)
        // Material You colors from the wallpaper (Android 12+), except with the sepia theme.
        com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(
            this,
            com.google.android.material.color.DynamicColorsOptions.Builder()
                .setPrecondition { _, _ -> ReaderSettings(this).theme != AppTheme.SEPIA }
                .build(),
        )
    }
}
