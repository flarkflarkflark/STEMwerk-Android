package com.flark.stemwerk

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream

class SafOutputSink(
    private val ctx: Context,
    private val treeUri: Uri,
) : OutputSink {

    private val root: DocumentFile = DocumentFile.fromTreeUri(ctx, treeUri)
        ?: throw IllegalArgumentException("Invalid output folder")

    override fun open(name: String, mimeType: String): OutputStream {
        // Replace if exists
        root.findFile(name)?.delete()
        val doc = root.createFile(mimeType, name)
            ?: throw RuntimeException("Unable to create $name in output folder")
        return ctx.contentResolver.openOutputStream(doc.uri, "w")
            ?: throw RuntimeException("Unable to open output stream for $name")
    }
}
