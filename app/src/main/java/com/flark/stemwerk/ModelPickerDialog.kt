package com.flark.stemwerk

import android.app.AlertDialog
import android.content.Context

object ModelPickerDialog {
    fun show(ctx: Context, onChosen: (String) -> Unit) {
        val ids = arrayOf(ModelManager.FOUR_STEMS, ModelManager.TWO_STEMS)
        AlertDialog.Builder(ctx).setTitle("Pick model")
            .setItems(arrayOf("4 stems — vocals, drums, bass, other", "2 stems — vocals / instrumental")) { _, i ->
                onChosen(ids[i])
            }.show()
    }
}
