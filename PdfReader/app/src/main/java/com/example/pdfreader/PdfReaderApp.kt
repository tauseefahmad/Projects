package com.example.pdfreader

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class PdfReaderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply the saved light/dark choice before any screen is shown.
        AppCompatDelegate.setDefaultNightMode(ReaderSettings(this).theme.nightMode)
    }
}
