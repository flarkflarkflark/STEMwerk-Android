package com.flark.stemwerk

import android.app.AlertDialog
import android.content.Context

object BackendPickerDialog {
    private val ids = arrayOf("auto", "cpu", "nnapi")
    private val labels = arrayOf(
        "Auto — NNAPI hardware, then CPU",
        "CPU — ARM64",
        "NNAPI — device hardware",
    )

    fun show(ctx: Context, selected: String, onChosen: (String) -> Unit) {
        val checked = ids.indexOf(selected).coerceAtLeast(0)
        AlertDialog.Builder(ctx)
            .setTitle("Inference route")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                onChosen(ids[which])
                dialog.dismiss()
            }
            .show()
    }

    fun label(id: String): String = labels[ids.indexOf(id).coerceAtLeast(0)]
}
