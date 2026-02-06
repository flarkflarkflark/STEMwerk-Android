package com.flark.stemwerk

import android.app.AlertDialog
import android.content.Context

/** Minimal picker; replace with real model selection UI if needed. */
object ModelPickerDialog {
    fun show(ctx: Context, onChosen: (String) -> Unit) {
        val items = arrayOf("models-v0.1")
        AlertDialog.Builder(ctx)
            .setTitle("Pick model")
            .setItems(items) { _, which ->
                onChosen(items[which])
            }
            .show()
    }
}
