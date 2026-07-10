package net.shehane.photoconverter.core

import android.content.Context
import java.io.File

/** Output locations for converted temp files and the CLI/UI report. */
class TempStore(context: Context) {
    private val base: File = context.getExternalFilesDir(null) ?: context.filesDir
    val root = File(base, "converted")
    val toJpegDir = File(root, "toJpeg")
    val toHeifDir = File(root, "toHeif")
    val heifToAvifDir = File(root, "heifToAvif")
    val jpegToAvifDir = File(root, "jpegToAvif")
    val reportFile = File(base, "report.json")

    fun prepare() {
        toJpegDir.mkdirs()
        toHeifDir.mkdirs()
        heifToAvifDir.mkdirs()
        jpegToAvifDir.mkdirs()
    }

    /** Deletes all converted temp files and the report. Returns (files, bytes) removed. */
    fun cleanup(): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        root.walkBottomUp().forEach { f ->
            if (f.isFile) { files++; bytes += f.length() }
            f.delete()
        }
        if (reportFile.exists()) { files++; bytes += reportFile.length(); reportFile.delete() }
        return files to bytes
    }
}
