package com.flark.stemwerk

import java.io.OutputStream

interface OutputSink {
    fun open(name: String, mimeType: String = "audio/wav"): OutputStream
}
