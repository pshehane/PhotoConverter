package com.pshehane.heifconverter.core

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File

/**
 * Runs the full analyze → sort → HEIF→JPEG → JPEG→HEIF pipeline.
 * Shared by the Compose UI and the adb CLI service.
 */
class Pipeline(
    private val context: Context,
    private val onLog: (String) -> Unit = {},
    private val onProgress: (phase: String, done: Int, total: Int) -> Unit = { _, _, _ -> },
) {
    val store = TempStore(context)
    private val converters = Converters(context)

    // ---------- input ----------

    fun readBytes(uri: Uri): ByteArray {
        // prefer unredacted originals so GPS EXIF survives (needs ACCESS_MEDIA_LOCATION)
        val original = try { MediaStore.setRequireOriginal(uri) } catch (e: Exception) { uri }
        return try {
            context.contentResolver.openInputStream(original)!!.readBytes()
        } catch (e: Exception) {
            context.contentResolver.openInputStream(uri)!!.readBytes()
        }
    }

    fun displayName(uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment ?: "unknown"
    }

    /** Most recent [count] images from MediaStore (what the photo picker shows first). */
    fun firstGalleryImages(count: Int): List<Uri> {
        val uris = ArrayList<Uri>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext() && uris.size < count) {
                uris += Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
            }
        }
        return uris
    }

    // ---------- phases ----------

    fun analyze(uris: List<Uri>): AnalysisResult {
        val t0 = System.nanoTime()
        val images = ArrayList<ImageInfo>()
        uris.forEachIndexed { i, uri ->
            onProgress("Analyzing", i, uris.size)
            val name = displayName(uri)
            val info = try {
                Analyzer.analyze(uri, name, readBytes(uri))
            } catch (t: Throwable) {
                ImageInfo(uri = uri, displayName = name, sizeBytes = 0, error = t.message ?: t.javaClass.simpleName)
            }
            images += info
            onLog("[analyze ${i + 1}/${uris.size}] ${info.summaryLine()}")
            onProgress("Analyzing", i + 1, uris.size)
        }
        val totalMs = (System.nanoTime() - t0) / 1_000_000
        return AnalysisResult(images, PhaseStats(images.size, totalMs))
    }

    fun convertHeifToJpeg(images: List<ImageInfo>): ConversionResult =
        convert("HEIF to JPEG", images, store.toJpegDir, "jpg") { bytes, info, out ->
            converters.heifToJpeg(bytes, info, out)
        }

    fun convertJpegToHeif(images: List<ImageInfo>): ConversionResult =
        convert("JPEG to HEIF", images, store.toHeifDir, "heic") { bytes, info, out ->
            converters.jpegToHeif(bytes, info, out)
        }

    private fun convert(
        label: String,
        images: List<ImageInfo>,
        dir: File,
        ext: String,
        op: (ByteArray, ImageInfo, File) -> ConvertOutcome,
    ): ConversionResult {
        store.prepare()
        val errors = ArrayList<FileIssue>()
        val warnings = ArrayList<FileIssue>()
        val outputs = ArrayList<ConversionOutput>()
        val t0 = System.nanoTime()
        images.forEachIndexed { i, info ->
            onProgress(label, i, images.size)
            val it0 = System.nanoTime()
            val outFile = File(dir, "%02d_%s.%s".format(i, info.displayName.substringBeforeLast('.').sanitized(), ext))
            try {
                val bytes = readBytes(info.uri)
                val outcome = op(bytes, info, outFile)
                warnings += outcome.warnings
                val ms = (System.nanoTime() - it0) / 1_000_000
                outputs += ConversionOutput(
                    info.displayName, outFile.absolutePath, ms,
                    source = info, outputVideoLength = outcome.outputVideoLength,
                )
                onLog("[$label ${i + 1}/${images.size}] ${info.displayName} -> ${outFile.name} (${ms}ms)")
            } catch (t: Throwable) {
                outFile.delete()
                val msg = t.message ?: t.javaClass.simpleName
                errors += FileIssue(info.displayName, msg)
                onLog("[$label ${i + 1}/${images.size}] ${info.displayName} ERROR: $msg")
            }
            onProgress(label, i + 1, images.size)
        }
        val totalMs = (System.nanoTime() - t0) / 1_000_000
        return ConversionResult(
            label = label,
            stats = PhaseStats(images.size, totalMs),
            succeeded = outputs.size,
            errors = errors,
            warnings = warnings,
            outputs = outputs,
        )
    }

    private fun String.sanitized(): String = replace(Regex("""[^A-Za-z0-9._-]"""), "_")

    // ---------- report ----------

    fun writeReport(analysis: AnalysisResult?, h2j: ConversionResult?, j2h: ConversionResult?) {
        val json = JSONObject()
            .put("generatedAt", System.currentTimeMillis())
            .put("analysis", analysis?.toJson() ?: JSONObject.NULL)
            .put("heifToJpeg", h2j?.toJson() ?: JSONObject.NULL)
            .put("jpegToHeif", j2h?.toJson() ?: JSONObject.NULL)
            .put("outputRoot", store.root.absolutePath)
        store.reportFile.writeText(json.toString(2))
        onLog("report written: ${store.reportFile.absolutePath}")
    }
}
