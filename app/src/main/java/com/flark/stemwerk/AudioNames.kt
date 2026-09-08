package com.flark.stemwerk

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object AudioNames {
    fun display(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "audio"

    fun folder(index: Int, name: String): String {
        val base = name.substringBeforeLast('.', name)
            .replace(Regex("[^\\p{L}\\p{N} _-]"), "_").take(80).ifBlank { "audio" }
        return (index + 1).toString().padStart(3, '0') + "_" + base
    }
}
