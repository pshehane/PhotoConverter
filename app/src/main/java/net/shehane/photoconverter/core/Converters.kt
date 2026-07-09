package net.shehane.photoconverter.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.heifwriter.HeifWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

data class ConvertOutcome(val warnings: List<FileIssue>, val outputVideoLength: Long)

/** HEIF→JPEG and JPEG→HEIF conversion with metadata preservation. */
class Converters(private val context: Context) {

    private fun decodeSoftware(bytes: ByteArray): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    /**
     * HEIF → JPEG. Gainmaps ride along automatically: Bitmap.compress writes an
     * Ultra HDR (JPEG_R) file when the bitmap carries a gainmap. EXIF is spliced in
     * as a fresh APP1; motion videos are re-appended with rebuilt XMP.
     */
    fun heifToJpeg(bytes: ByteArray, info: ImageInfo, out: File): ConvertOutcome {
        val warnings = ArrayList<FileIssue>()
        var outputVideoLength = 0L
        val bmp = decodeSoftware(bytes)
        try {
            if (info.hasGainmap && !bmp.hasGainmap()) {
                warnings += FileIssue(info.displayName, "gainmap present in source but lost during decode")
            }
            val baos = ByteArrayOutputStream(bytes.size)
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, baos)) {
                throw IOException("JPEG encode failed")
            }
            var jpeg = baos.toByteArray()

            if (info.hasGainmap && bmp.hasGainmap()) {
                // sanity: did the encoder actually write UltraHDR?
                val checkGm = try {
                    val b2 = decodeSoftware(jpeg)
                    val has = b2.hasGainmap()
                    b2.recycle()
                    has
                } catch (e: Exception) { false }
                if (!checkGm) warnings += FileIssue(info.displayName, "encoder did not write Ultra HDR gainmap")
            }

            Exif.buildTiff(context, bytes, bmp.width, bmp.height)?.let { tiff ->
                try {
                    jpeg = Jpeg.insertSegment(jpeg, Jpeg.exifApp1(tiff), isExif = true)
                } catch (e: Exception) {
                    warnings += FileIssue(info.displayName, "EXIF not copied: ${e.message}")
                }
            } ?: run {
                warnings += FileIssue(info.displayName, "no EXIF found in source")
            }

            if (info.isMotionPhoto && info.videoOffset > 0) {
                val video = bytes.copyOfRange(info.videoOffset.toInt(), bytes.size)
                val ts = Xmp.presentationTimestampUs(Xmp.extract(bytes))
                jpeg = Jpeg.removeXmp(jpeg)
                jpeg = Jpeg.insertSegment(
                    jpeg,
                    Jpeg.xmpApp1(Xmp.motionPhoto("image/jpeg", video.size.toLong(), ts)),
                    isExif = false,
                )
                out.writeBytes(jpeg + video)
                outputVideoLength = video.size.toLong()
            } else {
                if (info.isMotionPhoto) {
                    warnings += FileIssue(info.displayName, "motion photo flagged but video not located; video dropped")
                }
                out.writeBytes(jpeg)
            }
            return ConvertOutcome(warnings, outputVideoLength)
        } finally {
            bmp.recycle()
        }
    }

    /**
     * JPEG → HEIF via HeifWriter (HEVC), then EXIF/XMP injected directly into the
     * ISOBMFF meta box, and any motion video re-appended.
     */
    fun jpegToHeif(bytes: ByteArray, info: ImageInfo, out: File): ConvertOutcome {
        val warnings = ArrayList<FileIssue>()
        var outputVideoLength = 0L
        val bmp = decodeSoftware(bytes)
        try {
            if (bmp.hasGainmap()) {
                warnings += FileIssue(
                    info.displayName,
                    "Ultra HDR gainmap cannot be encoded into HEIF with public Android APIs; output is SDR base image",
                )
            }
            val writer = HeifWriter.Builder(out.absolutePath, bmp.width, bmp.height, HeifWriter.INPUT_MODE_BITMAP)
                .setQuality(90)
                .setMaxImages(1)
                .build()
            try {
                writer.start()
                writer.addBitmap(bmp)
                writer.stop(0)
            } finally {
                writer.close()
            }

            var heif = out.readBytes()
            if (heif.isEmpty()) throw IOException("HeifWriter produced empty file")

            val exifPayload = Exif.buildTiff(context, bytes, bmp.width, bmp.height)?.let { Exif.heifExifPayload(it) }
            if (exifPayload == null) warnings += FileIssue(info.displayName, "no EXIF found in source")

            val srcXmp = Xmp.extract(bytes)
            val motion = info.isMotionPhoto && info.videoOffset > 0
            val xmpPacket: String? = when {
                motion -> Xmp.motionPhoto(
                    "image/heic",
                    (bytes.size - info.videoOffset),
                    Xmp.presentationTimestampUs(srcXmp),
                )
                srcXmp != null && !srcXmp.contains("Container:Directory") -> srcXmp
                srcXmp != null -> {
                    warnings += FileIssue(info.displayName, "source XMP contained a container directory; not copied verbatim")
                    null
                }
                else -> null
            }

            if (exifPayload != null || xmpPacket != null) {
                try {
                    heif = Isobmff.injectMetadata(heif, exifPayload, xmpPacket?.toByteArray(Charsets.UTF_8))
                } catch (e: Exception) {
                    warnings += FileIssue(info.displayName, "metadata injection failed: ${e.message}")
                }
            }

            if (motion) {
                val video = bytes.copyOfRange(info.videoOffset.toInt(), bytes.size)
                out.writeBytes(heif + Isobmff.mpvdBox(video))
                outputVideoLength = video.size.toLong()
            } else {
                if (info.isMotionPhoto) {
                    warnings += FileIssue(info.displayName, "motion photo flagged but video not located; video dropped")
                }
                out.writeBytes(heif)
            }
            return ConvertOutcome(warnings, outputVideoLength)
        } finally {
            bmp.recycle()
        }
    }
}
