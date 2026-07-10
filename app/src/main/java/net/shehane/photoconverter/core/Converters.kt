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

/** ISOBMFF encode targets. */
enum class IsobmffTarget(val mime: String, val codecName: String) {
    HEIC("image/heic", "HEVC"),
    AVIF("image/avif", "AV1"),
}

/** HEIF→JPEG and JPEG→HEIF conversion with metadata preservation. */
class Converters(private val context: Context) {

    private fun decodeSoftware(bytes: ByteArray): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    /** Heavily sampled decode: enough to check gainmap presence, tiny footprint. */
    private fun sampledGainmapCheck(bytes: ByteArray): Boolean = try {
        val b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(8)
        }
        val has = b.hasGainmap()
        b.recycle()
        has
    } catch (e: Exception) {
        false
    }

    /**
     * HEIF → JPEG. Gainmaps ride along automatically: Bitmap.compress writes an
     * Ultra HDR (JPEG_R) file when the bitmap carries a gainmap. EXIF is spliced in
     * as a fresh APP1; motion videos are re-appended with rebuilt XMP.
     */
    fun heifToJpeg(bytes: ByteArray, info: ImageInfo, out: File): ConvertOutcome {
        val warnings = ArrayList<FileIssue>()
        var outputVideoLength = 0L
        // The full-resolution bitmap can be enormous (a 200 MP HEIF is ~800 MB);
        // hold it only for the compress call and recycle before any further work.
        val bmp = decodeSoftware(bytes)
        val width = bmp.width
        val height = bmp.height
        val hadGainmap: Boolean
        var jpeg: ByteArray
        try {
            hadGainmap = bmp.hasGainmap()
            if (info.hasGainmap && !hadGainmap) {
                warnings += FileIssue(info.displayName, "gainmap present in source but lost during decode")
            }
            val baos = ByteArrayOutputStream(bytes.size)
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, baos)) {
                throw IOException("JPEG encode failed")
            }
            jpeg = baos.toByteArray()
        } finally {
            bmp.recycle()
        }

        if (info.hasGainmap && hadGainmap) {
            // sanity: did the encoder actually write UltraHDR? (sampled decode —
            // gainmap presence survives sampling, footprint stays small)
            if (!sampledGainmapCheck(jpeg)) {
                warnings += FileIssue(info.displayName, "encoder did not write Ultra HDR gainmap")
            }
        }

        Exif.buildTiff(context, bytes, width, height)?.let { tiff ->
            try {
                jpeg = Jpeg.insertSegment(jpeg, Jpeg.exifApp1(tiff), isExif = true)
            } catch (e: Exception) {
                warnings += FileIssue(info.displayName, "EXIF not copied: ${e.message}")
            }
        } ?: run {
            warnings += FileIssue(info.displayName, "no EXIF found in source")
        }

        if (info.isMotionPhoto && info.videoOffset > 0) {
            val ts = Xmp.presentationTimestampUs(Xmp.extract(bytes))
            val videoLength = bytes.size - info.videoOffset
            jpeg = Jpeg.removeXmp(jpeg)
            jpeg = Jpeg.insertSegment(
                jpeg,
                Jpeg.xmpApp1(Xmp.motionPhoto("image/jpeg", videoLength, ts)),
                isExif = false,
            )
            // stream: avoids concatenating jpeg+video into yet another array
            out.outputStream().use { os ->
                os.write(jpeg)
                os.write(bytes, info.videoOffset.toInt(), videoLength.toInt())
            }
            outputVideoLength = videoLength
        } else {
            if (info.isMotionPhoto) {
                warnings += FileIssue(info.displayName, "motion photo flagged but video not located; video dropped")
            }
            out.writeBytes(jpeg)
        }
        return ConvertOutcome(warnings, outputVideoLength)
    }

    /** JPEG/HEIF → HEIC via HeifWriter (HEVC). */
    fun toHeif(bytes: ByteArray, info: ImageInfo, out: File): ConvertOutcome =
        toIsobmff(bytes, info, out, IsobmffTarget.HEIC)

    /** JPEG/HEIF → AVIF via AvifWriter (AV1). */
    fun toAvif(bytes: ByteArray, info: ImageInfo, out: File): ConvertOutcome =
        toIsobmff(bytes, info, out, IsobmffTarget.AVIF)

    /**
     * Encode into an ISOBMFF still (HEIC or AVIF), then inject EXIF/XMP directly
     * into the meta box and re-append any motion video in an mpvd box.
     */
    private fun toIsobmff(bytes: ByteArray, info: ImageInfo, out: File, target: IsobmffTarget): ConvertOutcome {
        val warnings = ArrayList<FileIssue>()
        var outputVideoLength = 0L
        // hold the full-resolution bitmap only for the encode, then release it
        val bmp = decodeSoftware(bytes)
        val width = bmp.width
        val height = bmp.height
        try {
            if (bmp.hasGainmap()) {
                warnings += FileIssue(
                    info.displayName,
                    "Ultra HDR gainmap cannot be encoded into ${target.name} with public Android APIs; output is SDR base image",
                )
            }
            when (target) {
                IsobmffTarget.HEIC -> {
                    val writer = HeifWriter.Builder(out.absolutePath, width, height, HeifWriter.INPUT_MODE_BITMAP)
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
                }
                IsobmffTarget.AVIF -> {
                    // MediaMuxer can't write AV1 image items on production devices,
                    // so encode via MediaCodec and build the container ourselves
                    if (bmp.colorSpace?.isSrgb == false) {
                        warnings += FileIssue(
                            info.displayName,
                            "wide-gamut source (${bmp.colorSpace?.name}) may be clamped to sRGB by the AV1 encoder surface",
                        )
                    }
                    val enc = AvifCodec.encode(bmp, 90)
                    val avif = if (enc.isSingle(width, height)) {
                        Isobmff.buildAvif(width, height, enc.av1c, enc.tiles[0])
                    } else {
                        Isobmff.buildAvifGrid(
                            width, height,
                            enc.tileWidth, enc.tileHeight, enc.rows, enc.cols,
                            enc.av1c, enc.tiles,
                        )
                    }
                    out.writeBytes(avif)
                }
            }
        } finally {
            bmp.recycle()
        }

        var heif = out.readBytes()
        if (heif.isEmpty()) throw IOException("${target.name} writer produced empty file")

        val exifPayload = Exif.buildTiff(context, bytes, width, height)?.let { Exif.heifExifPayload(it) }
        if (exifPayload == null) warnings += FileIssue(info.displayName, "no EXIF found in source")

        val srcXmp = Xmp.extract(bytes)
        val motion = info.isMotionPhoto && info.videoOffset > 0
        val xmpPacket: String? = when {
            motion -> Xmp.motionPhoto(
                target.mime,
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
            val videoLength = bytes.size - info.videoOffset
            // stream: mpvd box header + video range from the source buffer
            out.outputStream().use { os ->
                os.write(heif)
                os.write(Isobmff.mpvdHeader(videoLength))
                os.write(bytes, info.videoOffset.toInt(), videoLength.toInt())
            }
            outputVideoLength = videoLength
        } else {
            if (info.isMotionPhoto) {
                warnings += FileIssue(info.displayName, "motion photo flagged but video not located; video dropped")
            }
            out.writeBytes(heif)
        }
        return ConvertOutcome(warnings, outputVideoLength)
    }
}
