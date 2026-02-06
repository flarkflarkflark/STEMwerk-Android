package com.flark.stemwerk

import java.io.File
import java.io.OutputStream

class FileOutputSink(private val outDir: File) : OutputSink {
    override fun open(name: String, mimeType: String): OutputStream {
        val f = File(outDir, name)
        return f.outputStream()
    }
}
