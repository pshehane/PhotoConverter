package com.pshehane.heifconverter.core

import android.content.Context
import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Modifier

/**
 * Copies EXIF from a source image (JPEG or HEIF — ExifInterface reads both) into a
 * standalone TIFF blob by round-tripping through a tiny in-cache JPEG. The TIFF bytes
 * can then be spliced into a JPEG APP1 or a HEIF Exif item without ever calling
 * saveAttributes() on the real output file (which could disturb trailing data such as
 * Ultra HDR gainmap images or appended motion videos).
 */
object Exif {

    private val ALL_TAGS: List<String> by lazy {
        ExifInterface::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.name.startsWith("TAG_") && it.type == String::class.java }
            .mapNotNull { it.get(null) as? String }
            .distinct()
    }

    private val SKIP_TAGS = setOf(
        ExifInterface.TAG_ORIENTATION,          // decode is upright; forced to 1 below
        ExifInterface.TAG_XMP,                  // XMP handled separately
        ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH,
        ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
        ExifInterface.TAG_THUMBNAIL_ORIENTATION,
        ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT,
        ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
        ExifInterface.TAG_STRIP_OFFSETS,
        ExifInterface.TAG_STRIP_BYTE_COUNTS,
        ExifInterface.TAG_COMPRESSION,
        ExifInterface.TAG_BITS_PER_SAMPLE,
    )

    /**
     * Returns TIFF bytes carrying the source's EXIF with orientation reset to 1 and
     * pixel dimensions set to [outWidth]x[outHeight], or null if the source had no EXIF.
     *
     * Preferred path: lift the raw TIFF straight out of the source container (JPEG APP1
     * or HEIF/AVIF Exif item), splice it into a tiny carrier JPEG, and let ExifInterface
     * normalize orientation/dimensions. Falls back to attribute-by-attribute copy for
     * containers we can't lift TIFF from (e.g. PNG eXIf).
     */
    fun buildTiff(context: Context, sourceBytes: ByteArray, outWidth: Int, outHeight: Int): ByteArray? {
        val rawTiff: ByteArray? = try {
            when {
                Jpeg.isJpeg(sourceBytes) -> Jpeg.extractExifTiff(sourceBytes)
                Isobmff.isHeif(sourceBytes) -> Isobmff.extractExifTiff(sourceBytes)
                else -> null
            }
        } catch (e: Exception) {
            null
        }

        val tmp = File.createTempFile("exifcarrier", ".jpg", context.cacheDir)
        try {
            val tinyJpeg = run {
                val baos = java.io.ByteArrayOutputStream()
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.JPEG, 90, baos)
                baos.toByteArray()
            }

            if (rawTiff != null) {
                tmp.writeBytes(Jpeg.insertSegment(tinyJpeg, Jpeg.exifApp1(rawTiff), isExif = true))
            } else {
                // fallback: attribute copy via reflection over all known tags
                val src = try {
                    ExifInterface(ByteArrayInputStream(sourceBytes))
                } catch (e: Exception) {
                    return null
                }
                tmp.writeBytes(tinyJpeg)
                val dst = ExifInterface(tmp.absolutePath)
                var copied = 0
                for (tag in ALL_TAGS) {
                    if (tag in SKIP_TAGS) continue
                    try {
                        src.getAttribute(tag)?.let { v ->
                            dst.setAttribute(tag, v)
                            copied++
                        }
                    } catch (_: Exception) {
                        // some tags reject string round-trips; skip them
                    }
                }
                if (copied == 0) return null
                dst.saveAttributes()
            }

            val dst = ExifInterface(tmp.absolutePath)
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, "1")
            dst.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, outWidth.toString())
            dst.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, outHeight.toString())
            dst.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, outWidth.toString())
            dst.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, outHeight.toString())
            dst.saveAttributes()
            return Jpeg.extractExifTiff(tmp.readBytes())
        } catch (e: Exception) {
            return null
        } finally {
            tmp.delete()
        }
    }

    /** HEIF Exif item payload: u32 tiff-header offset + "Exif\0\0" + TIFF. */
    fun heifExifPayload(tiff: ByteArray): ByteArray {
        val out = ByteArray(4 + 6 + tiff.size)
        out[3] = 6 // exif_tiff_header_offset (big-endian 6): skip "Exif\0\0"
        Jpeg.EXIF_HEADER.copyInto(out, 4)
        tiff.copyInto(out, 10)
        return out
    }
}
