package net.shehane.photoconverter.core

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import java.io.IOException

/**
 * AV1 still-frame encoder via MediaCodec, with grid tiling for images that exceed
 * the encoder's frame-size capability (e.g. c2.android.av1.encoder caps at 1920²,
 * while photos are 12–200 MP).
 *
 * The platform MediaMuxer (and therefore androidx AvifWriter) cannot mux AV1 image
 * items on production devices — MPEG4Writer validates image tracks as HEVC and
 * rejects the av1C config. So we produce raw OBUs + codec config here and build the
 * AVIF container in [Isobmff.buildAvif] / [Isobmff.buildAvifGrid].
 */
object AvifCodec {

    private const val MIME = "video/av01"

    data class Encoded(
        val av1c: ByteArray,
        val tiles: List<ByteArray>,
        val tileWidth: Int,
        val tileHeight: Int,
        val rows: Int,
        val cols: Int,
    ) {
        fun isSingle(width: Int, height: Int) =
            rows == 1 && cols == 1 && tileWidth == width && tileHeight == height
    }

    private fun findEncoder(): MediaCodecInfo? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }
        }

    fun encode(bmp: Bitmap, quality: Int): Encoded {
        val info = findEncoder() ?: throw IOException("no AV1 encoder on this device")
        val caps = info.getCapabilitiesForType(MIME)
        val video = caps.videoCapabilities

        val align = maxOf(video.widthAlignment, video.heightAlignment, 2)
        val cap = minOf(video.supportedWidths.upper, video.supportedHeights.upper)

        val tileW: Int
        val tileH: Int
        if (bmp.width <= cap && bmp.height <= cap &&
            bmp.width % align == 0 && bmp.height % align == 0 &&
            video.isSizeSupported(bmp.width, bmp.height)
        ) {
            tileW = bmp.width
            tileH = bmp.height
        } else {
            // MIAF wants grid tiles ≥64 and codecs want alignment; 64 covers both
            val t = (minOf(1920, cap) / 64) * 64
            if (t < 64) throw IOException("AV1 encoder ${info.name} too small for tiling (cap ${cap}px)")
            tileW = t
            tileH = t
        }
        val cols = (bmp.width + tileW - 1) / tileW
        val rows = (bmp.height + tileH - 1) / tileH

        val format = MediaFormat.createVideoFormat(MIME, tileW, tileH).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
            val bits = (tileW.toLong() * tileH * 12 * quality / 900)
                .coerceIn(500_000, Int.MAX_VALUE.toLong())
            setInteger(MediaFormat.KEY_BIT_RATE, bits.toInt())
            if (caps.encoderCapabilities.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ)
            ) {
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
                )
                setInteger(MediaFormat.KEY_QUALITY, quality)
            }
        }

        val codec = MediaCodec.createByCodecName(info.name)
        var csd: ByteArray? = null
        val frames = ArrayList<ByteArray>(rows * cols)
        var outFormat: MediaFormat? = null
        var eos = false

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            try {
                val bufInfo = MediaCodec.BufferInfo()

                fun drain(timeoutUs: Long): Boolean { // true when EOS reached
                    while (true) {
                        when (val idx = codec.dequeueOutputBuffer(bufInfo, timeoutUs)) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                            MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                            MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                            else -> if (idx >= 0) {
                                val buf = codec.getOutputBuffer(idx)!!
                                val arr = ByteArray(bufInfo.size)
                                buf.position(bufInfo.offset)
                                buf.get(arr)
                                if (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    csd = arr
                                } else if (arr.isNotEmpty()) {
                                    frames += stripTemporalDelimiter(arr)
                                }
                                val done = bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                codec.releaseOutputBuffer(idx, false)
                                if (done) return true
                            }
                        }
                    }
                }

                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val canvas = surface.lockCanvas(null)
                        try {
                            canvas.drawColor(Color.BLACK)
                            val sx = c * tileW
                            val sy = r * tileH
                            val sw = minOf(tileW, bmp.width - sx)
                            val sh = minOf(tileH, bmp.height - sy)
                            canvas.drawBitmap(
                                bmp,
                                Rect(sx, sy, sx + sw, sy + sh),
                                Rect(0, 0, sw, sh),
                                null,
                            )
                        } finally {
                            surface.unlockCanvasAndPost(canvas)
                        }
                        drain(0) // keep the output queue moving while feeding
                    }
                }
                codec.signalEndOfInputStream()

                var spins = 0
                while (!eos) {
                    eos = drain(100_000)
                    if (!eos && ++spins > 3600) throw IOException("AV1 encoder stalled (>6 min)")
                }
            } finally {
                surface.release()
            }
            codec.stop()
        } finally {
            codec.release()
        }

        if (csd == null) {
            csd = outFormat?.getByteBuffer("csd-0")?.let { bb ->
                ByteArray(bb.remaining()).also { bb.get(it) }
            }
        }
        val av1c = csd ?: throw IOException("AV1 encoder produced no codec config (csd-0)")
        if (frames.size != rows * cols) {
            throw IOException("AV1 encoder produced ${frames.size} frames for ${rows * cols} tiles")
        }
        return Encoded(av1c, frames, tileW, tileH, rows, cols)
    }

    /** AV1-ISOBMFF item data must not start with a temporal delimiter OBU. */
    private fun stripTemporalDelimiter(d: ByteArray): ByteArray {
        if (d.isEmpty()) return d
        val header = d[0].toInt() and 0xFF
        val type = (header shr 3) and 0x0F
        if (type != 2) return d // not a temporal delimiter
        var i = 1
        if (header and 0x04 != 0) i++ // extension flag
        if (header and 0x02 != 0) {
            var size = 0L
            var shift = 0
            while (i < d.size) {
                val b = d[i].toInt() and 0xFF
                size = size or ((b and 0x7F).toLong() shl shift)
                i++
                shift += 7
                if (b and 0x80 == 0) break
            }
            i += size.toInt()
        }
        return if (i in 1 until d.size) d.copyOfRange(i, d.size) else d
    }
}
