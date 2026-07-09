package com.pshehane.heifconverter.core

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

enum class Container { JPEG, HEIF, AVIF, PNG, WEBP, GIF, OTHER }

data class ImageInfo(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val container: Container = Container.OTHER,
    val codec: String = "?",
    val width: Int = 0,
    val height: Int = 0,
    val bitDepth: Int = 8,
    val colorSpace: String = "?",
    val hasGainmap: Boolean = false,
    val isMotionPhoto: Boolean = false,
    /** absolute file offset where the appended motion video begins, -1 if none */
    val videoOffset: Long = -1,
    val videoLength: Long = 0,
    val analysisMs: Long = 0,
    val error: String? = null,
) {
    val isJpeg get() = container == Container.JPEG && error == null
    val isHeif get() = (container == Container.HEIF || container == Container.AVIF) && error == null

    fun summaryLine(): String = buildString {
        append(displayName).append("  ")
        append(container.name).append('/').append(codec).append("  ")
        append(width).append('x').append(height)
        append("  ").append(bitDepth).append("-bit")
        append("  ").append(colorSpace)
        if (hasGainmap) append("  [UltraHDR]")
        if (isMotionPhoto) append("  [Motion ${videoLength}B]")
        error?.let { append("  ERROR: ").append(it) }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("name", displayName)
        .put("uri", uri.toString())
        .put("sizeBytes", sizeBytes)
        .put("container", container.name)
        .put("codec", codec)
        .put("width", width)
        .put("height", height)
        .put("bitDepth", bitDepth)
        .put("colorSpace", colorSpace)
        .put("hasGainmap", hasGainmap)
        .put("isMotionPhoto", isMotionPhoto)
        .put("videoOffset", videoOffset)
        .put("videoLength", videoLength)
        .put("analysisMs", analysisMs)
        .put("error", error ?: JSONObject.NULL)
}

data class PhaseStats(val count: Int, val totalMs: Long) {
    val avgMs: Long get() = if (count > 0) totalMs / count else 0
    fun toJson(): JSONObject =
        JSONObject().put("count", count).put("totalMs", totalMs).put("avgMs", avgMs)
}

data class FileIssue(val name: String, val message: String) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("message", message)
}

data class AnalysisResult(val images: List<ImageInfo>, val stats: PhaseStats) {
    val jpegs get() = images.filter { it.isJpeg }
    val heifs get() = images.filter { it.isHeif }
    val others get() = images.filter { !it.isJpeg && !it.isHeif }

    fun toJson(): JSONObject = JSONObject()
        .put("stats", stats.toJson())
        .put("jpegCount", jpegs.size)
        .put("heifCount", heifs.size)
        .put("otherCount", others.size)
        .put("images", JSONArray(images.map { it.toJson() }))
}

data class ConversionOutput(
    val sourceName: String,
    val outputPath: String,
    val durationMs: Long,
    /** analysis info of the source image, for side-by-side display */
    val source: ImageInfo? = null,
    /** bytes of motion video at the end of the output file, 0 if none */
    val outputVideoLength: Long = 0,
)

data class ConversionResult(
    val label: String,
    val stats: PhaseStats,
    val succeeded: Int,
    val errors: List<FileIssue>,
    val warnings: List<FileIssue>,
    val outputs: List<ConversionOutput>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("label", label)
        .put("stats", stats.toJson())
        .put("succeeded", succeeded)
        .put("errorCount", errors.size)
        .put("errors", JSONArray(errors.map { it.toJson() }))
        .put("warningCount", warnings.size)
        .put("warnings", JSONArray(warnings.map { it.toJson() }))
        .put("outputs", JSONArray(outputs.map {
            JSONObject().put("source", it.sourceName).put("output", it.outputPath)
                .put("ms", it.durationMs).put("videoLength", it.outputVideoLength)
        }))
}
