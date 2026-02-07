package com.flark.stemwerk

import android.content.Context

/**
 * Placeholder for Demucs inference.
 * Next step: load TorchScript Lite module and run chunked inference.
 */
class DemucsSeparator(private val ctx: Context) {
    fun isAvailable(): Boolean = true
}
