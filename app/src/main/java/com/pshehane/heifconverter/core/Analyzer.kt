package com.pshehane.heifconverter.core

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.nio.ByteBuffer

/** Identifies container, codec, resolution, bit depth, gainmap and motion-photo data. */
object Analyzer {

    fun analyze(uri: Uri, displayName: String, bytes: ByteArray): ImageInfo {
        val start = System.nanoTime()
        var info = ImageInfo(uri = uri, displayName = displayName, sizeBytes = bytes.size.toLong())
        try {
            info = info.copy(container = sniffContainer(bytes))
            info = info.copy(codec = detectCodec(bytes, info.container))

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth > 0) info = info.copy(width = opts.outWidth, height = opts.outHeight)

            info = info.copy(bitDepth = detectBitDepth(bytes, info.container))

            // sampled decode: gainmap + colorspace (cheap relative to full decode)
            try {
                val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                val bmp = ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSampleSize(8)
                }
                info = info.copy(
                    hasGainmap = bmp.hasGainmap(),
                    colorSpace = bmp.colorSpace?.name ?: "?",
                )
                bmp.recycle()
            } catch (e: Exception) {
                info = info.copy(error = "decode: ${e.message}")
            }

            val xmp = Xmp.extract(bytes)
            if (Xmp.isMotionPhoto(xmp)) {
                val (offset, length) = locateMotionVideo(bytes, xmp)
                info = info.copy(isMotionPhoto = true, videoOffset = offset, videoLength = length)
            }
        } catch (e: Exception) {
            info = info.copy(error = e.message ?: e.javaClass.simpleName)
        }
        return info.copy(analysisMs = (System.nanoTime() - start) / 1_000_000)
    }

    fun sniffContainer(b: ByteArray): Container {
        if (b.size < 12) return Container.OTHER
        if (Jpeg.isJpeg(b)) return Container.JPEG
        if (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte()) return Container.PNG
        if (String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP") return Container.WEBP
        if (String(b, 0, 3, Charsets.ISO_8859_1) == "GIF") return Container.GIF
        if (String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp") {
            val brands = ftypBrands(b)
            return when {
                brands.any { it.startsWith("avi") } -> Container.AVIF
                else -> Container.HEIF
            }
        }
        return Container.OTHER
    }

    private fun ftypBrands(b: ByteArray): List<String> {
        val size = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        val end = minOf(size, b.size)
        val brands = ArrayList<String>()
        brands += String(b, 8, 4, Charsets.ISO_8859_1) // major
        var o = 16 // skip minor version
        while (o + 4 <= end) {
            brands += String(b, o, 4, Charsets.ISO_8859_1)
            o += 4
        }
        return brands
    }

    private fun detectCodec(b: ByteArray, container: Container): String = when (container) {
        Container.JPEG -> "JPEG"
        Container.PNG -> "PNG"
        Container.WEBP -> "VP8"
        Container.GIF -> "GIF"
        Container.AVIF -> "AV1"
        Container.HEIF -> {
            // the meta box (with infe codec item types) may sit after a large mdat
            val meta = try {
                Isobmff.parseBoxes(b, 0, b.size).firstOrNull { it.type == "meta" }
            } catch (e: Exception) {
                null
            }
            val region = if (meta != null) {
                String(b, meta.start, meta.end - meta.start, Charsets.ISO_8859_1)
            } else {
                String(b, 0, minOf(b.size, 1024 * 1024), Charsets.ISO_8859_1)
            }
            when {
                region.contains("hvc1") || region.contains("hev1") -> "HEVC"
                region.contains("av01") -> "AV1"
                else -> "HEVC?"
            }
        }
        Container.OTHER -> "?"
    }

    /** Reads bits_per_channel from the first 'pixi' property if present (HEIF), else 8. */
    private fun detectBitDepth(b: ByteArray, container: Container): Int {
        if (container != Container.HEIF && container != Container.AVIF) return 8
        val limit = minOf(b.size - 10, 256 * 1024)
        var i = 4
        while (i < limit) {
            if (b[i] == 'p'.code.toByte() && b[i + 1] == 'i'.code.toByte() &&
                b[i + 2] == 'x'.code.toByte() && b[i + 3] == 'i'.code.toByte()
            ) {
                val numChannels = b[i + 8].toInt() and 0xFF
                if (numChannels in 1..4) {
                    val depth = b[i + 9].toInt() and 0xFF
                    if (depth in 1..16) return depth
                }
            }
            i++
        }
        return 8
    }

    /**
     * Locates the appended motion-photo mp4. Prefers the XMP-declared length
     * (video occupies the last N bytes); falls back to scanning for the last
     * plausible 'ftyp' signature.
     */
    fun locateMotionVideo(bytes: ByteArray, xmp: String?): Pair<Long, Long> {
        val declared = Xmp.motionVideoLength(xmp)
        if (declared in 17..bytes.size.toLong()) {
            val offset = bytes.size - declared
            if (isFtypAt(bytes, offset.toInt())) return offset to declared
        }
        // fallback: last 'ftyp' whose box size is plausible
        var best = -1
        var i = bytes.size - 12
        val floor = 16
        while (i > floor) {
            if (bytes[i + 4] == 'f'.code.toByte() && bytes[i + 5] == 't'.code.toByte() &&
                bytes[i + 6] == 'y'.code.toByte() && bytes[i + 7] == 'p'.code.toByte() &&
                isFtypAt(bytes, i)
            ) { best = i; break }
            i--
        }
        return if (best > 0) best.toLong() to (bytes.size - best).toLong() else -1L to 0L
    }

    private fun isFtypAt(b: ByteArray, o: Int): Boolean {
        if (o < 0 || o + 12 > b.size) return false
        if (!(b[o + 4] == 'f'.code.toByte() && b[o + 5] == 't'.code.toByte() &&
                b[o + 6] == 'y'.code.toByte() && b[o + 7] == 'p'.code.toByte())) return false
        val size = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
        if (size < 12 || size > 1024) return false
        // brand must be printable ascii
        for (j in o + 8 until o + 12) {
            val c = b[j].toInt() and 0xFF
            if (c < 0x20 || c > 0x7E) return false
        }
        return true
    }
}
