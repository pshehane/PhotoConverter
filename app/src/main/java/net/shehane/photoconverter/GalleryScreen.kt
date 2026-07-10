package net.shehane.photoconverter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import net.shehane.photoconverter.core.ConversionOutput
import net.shehane.photoconverter.core.ConversionResult
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * One side of a comparison pair. Exactly one of [uri]/[filePath] is set.
 * videoLength > 0 marks a motion photo whose mp4 occupies the trailing
 * [videoLength] bytes starting at [videoOffset].
 */
data class MediaSpec(
    val uri: Uri? = null,
    val filePath: String? = null,
    val videoOffset: Long = 0,
    val videoLength: Long = 0,
) {
    val isMotion get() = videoLength > 0
}

@Composable
fun GalleryScreen(result: ConversionResult, onBack: () -> Unit) {
    var zoomed by remember { mutableStateOf<ConversionOutput?>(null) }
    val current = zoomed
    if (current != null) {
        FullscreenCompare(current) { zoomed = null }
        return
    }
    BackHandler(onBack = onBack)
    val pairs = result.outputs.filter { it.source != null }
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${result.label} — comparison", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onBack) { Text("Back") }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("Original", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text("Converted", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(pairs, key = { it.outputPath }) { out ->
                ComparisonRow(out) { zoomed = out }
            }
        }
    }
}

/** "HEIC → JPG" plus preservation badges — no long filenames. */
private fun pairLabel(out: ConversionOutput): String {
    val src = out.source!!
    val srcExt = src.displayName.substringAfterLast('.', "?").uppercase()
    val outExt = out.outputPath.substringAfterLast('.', "?").uppercase()
    return buildString {
        append(srcExt).append("  →  ").append(outExt)
        if (src.hasGainmap) {
            append(if (outExt == "JPG") "   [UltraHDR kept]" else "   [UltraHDR dropped]")
        }
        if (src.isMotionPhoto) {
            append(if (out.outputVideoLength > 0) "   [Motion kept]" else "   [Motion dropped]")
        }
    }
}

@Composable
private fun ComparisonRow(out: ConversionOutput, onZoom: () -> Unit) {
    val src = out.source!!
    val aspect = if (src.width > 0 && src.height > 0) src.width.toFloat() / src.height else 1f
    val outFile = File(out.outputPath)
    val sourceSpec = remember(out.outputPath) {
        MediaSpec(uri = src.uri, videoOffset = src.videoOffset, videoLength = src.videoLength)
    }
    val convertedSpec = remember(out.outputPath) {
        MediaSpec(
            filePath = out.outputPath,
            videoOffset = if (out.outputVideoLength > 0) outFile.length() - out.outputVideoLength else 0,
            videoLength = out.outputVideoLength,
        )
    }
    Column(Modifier.clickable { onZoom() }) {
        Text(pairLabel(out), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MediaCell(sourceSpec, Modifier.weight(1f).aspectRatio(aspect))
            MediaCell(convertedSpec, Modifier.weight(1f).aspectRatio(aspect))
        }
    }
}

/**
 * Full-screen original/converted pair. One gesture surface drives a shared
 * zoom/pan state applied to both panes, so they stay locked together.
 * Pinch to zoom, drag to pan, double-tap to toggle 2.5x / reset.
 */
@Composable
private fun FullscreenCompare(out: ConversionOutput, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val src = out.source!!
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 12f)
                    offset = if (scale > 1f) offset + pan else Offset.Zero
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            },
    ) {
        Row(Modifier.fillMaxSize()) {
            ZoomPane(
                MediaSpec(uri = src.uri),
                scale, offset,
                Modifier.weight(1f).fillMaxHeight(),
            )
            ZoomPane(
                MediaSpec(filePath = out.outputPath),
                scale, offset,
                Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Text(
            pairLabel(out),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(8.dp)
                .background(Color(0xA0000000))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        TextButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding(),
        ) {
            Text("✕", color = Color.White, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun ZoomPane(spec: MediaSpec, scale: Float, offset: Offset, modifier: Modifier) {
    val context = LocalContext.current
    var bmp by remember(spec) { mutableStateOf<Bitmap?>(null) }
    // higher-resolution decode than the grid cells, so zooming shows real detail
    LaunchedEffect(spec) { bmp = decodeForDisplay(context, spec, longEdgeTarget = 4096) }
    Box(modifier.clipToBounds()) {
        AndroidView(
            factory = {
                ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            },
            update = { it.setImageBitmap(bmp) },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

@Composable
private fun MediaCell(spec: MediaSpec, modifier: Modifier) {
    if (spec.isMotion) MotionCell(spec, modifier) else StillCell(spec, modifier)
}

/**
 * Decodes for display. Hardware allocation (the default) keeps the gainmap and
 * color space on the bitmap so HWUI renders Ultra HDR / P3 correctly — the
 * activity window is put in COLOR_MODE_HDR by MainActivity.
 */
private suspend fun decodeForDisplay(
    context: Context,
    spec: MediaSpec,
    longEdgeTarget: Int = 1600,
): Bitmap? =
    withContext(Dispatchers.IO) {
        try {
            val source = when {
                spec.filePath != null -> ImageDecoder.createSource(File(spec.filePath))
                spec.uri != null -> ImageDecoder.createSource(context.contentResolver, spec.uri)
                else -> return@withContext null
            }
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longEdge = maxOf(info.size.width, info.size.height)
                if (longEdge > longEdgeTarget) {
                    decoder.setTargetSampleSize((longEdge + longEdgeTarget - 1) / longEdgeTarget)
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

@Composable
private fun StillCell(spec: MediaSpec, modifier: Modifier) {
    val context = LocalContext.current
    var bmp by remember(spec) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(spec) { bmp = decodeForDisplay(context, spec) }
    AndroidView(
        factory = {
            ImageView(it).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(0xFF1A1A1A.toInt())
            }
        },
        update = { it.setImageBitmap(bmp) },
        modifier = modifier,
    )
}

/** TextureView (video) with the photo overlaid between plays. */
private class MotionViews(context: Context) : FrameLayout(context) {
    val texture = TextureView(context)
    val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    init {
        setBackgroundColor(0xFF1A1A1A.toInt())
        val lp = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(texture, lp)
        addView(image, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
}

@Composable
private fun MotionCell(spec: MediaSpec, modifier: Modifier) {
    val context = LocalContext.current
    var bmp by remember(spec) { mutableStateOf<Bitmap?>(null) }
    var videoVisible by remember(spec) { mutableStateOf(false) }
    var surface by remember(spec) { mutableStateOf<Surface?>(null) }
    val views = remember(spec) { MotionViews(context) }

    DisposableEffect(spec) {
        views.texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                surface?.release()
                surface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        onDispose {
            surface?.release()
            surface = null
        }
    }

    LaunchedEffect(spec) { bmp = decodeForDisplay(context, spec) }

    // play video → show photo → wait 5 s → repeat
    LaunchedEffect(spec, surface) {
        val surf = surface ?: return@LaunchedEffect
        while (isActive) {
            val ok = playVideoOnce(context, spec, surf, views) { videoVisible = it }
            if (!ok) break // unplayable; stay on the photo
            delay(5_000)
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { views },
            update = {
                it.image.setImageBitmap(bmp)
                it.image.visibility = if (videoVisible) android.view.View.GONE else android.view.View.VISIBLE
            },
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            "▶",
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(Color(0x80000000)),
        )
    }
}

/** Plays the embedded mp4 once; returns false if the video can't be played. */
private suspend fun playVideoOnce(
    context: Context,
    spec: MediaSpec,
    surface: Surface,
    views: MotionViews,
    setVideoVisible: (Boolean) -> Unit,
): Boolean {
    val mp = MediaPlayer()
    return try {
        when {
            spec.filePath != null -> FileInputStream(File(spec.filePath)).use { fis ->
                mp.setDataSource(fis.fd, spec.videoOffset, spec.videoLength)
            }
            spec.uri != null -> context.contentResolver.openAssetFileDescriptor(spec.uri, "r")!!.use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset + spec.videoOffset, spec.videoLength)
            }
            else -> return false
        }
        mp.setSurface(surface)
        mp.setOnVideoSizeChangedListener { _, vw, vh -> applyFitTransform(views.texture, vw, vh) }
        suspendCancellableCoroutine { cont ->
            mp.setOnPreparedListener {
                setVideoVisible(true)
                it.start()
            }
            mp.setOnCompletionListener { if (cont.isActive) cont.resume(true, null) }
            mp.setOnErrorListener { _, _, _ ->
                if (cont.isActive) cont.resume(false, null)
                true
            }
            cont.invokeOnCancellation { }
            mp.prepareAsync()
        }
    } catch (t: Throwable) {
        false
    } finally {
        setVideoVisible(false)
        try { mp.release() } catch (_: Throwable) {}
    }
}

/** Center-inside fit; TextureView otherwise stretches the video to its bounds. */
private fun applyFitTransform(tv: TextureView, videoW: Int, videoH: Int) {
    val vw = tv.width.toFloat()
    val vh = tv.height.toFloat()
    if (vw <= 0 || vh <= 0 || videoW <= 0 || videoH <= 0) return
    val scale = minOf(vw / videoW, vh / videoH)
    val m = Matrix()
    m.setScale(videoW * scale / vw, videoH * scale / vh)
    m.postTranslate((vw - videoW * scale) / 2f, (vh - videoH * scale) / 2f)
    tv.setTransform(m)
}
