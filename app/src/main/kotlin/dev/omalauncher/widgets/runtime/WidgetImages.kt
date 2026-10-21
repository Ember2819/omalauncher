package dev.omalauncher.widgets.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.asImageBitmap
import dev.omalauncher.ui.decodeSampled
import dev.omalauncher.widgets.lang.Global
import dev.omalauncher.widgets.lang.Program
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A widget's images by src: decoded off the main thread at widget size, a
 * bounded number kept, and checked again when the guest may have rewritten them.
 */
internal class WidgetImages(
    private val program: Program,
    private val services: Services,
    private val report: (String) -> Unit,
) {
    /** Where decodes run; set when the widget starts. */
    var scope: CoroutineScope? = null

    /** Decoded images by src, filled in off the main thread; see [image]. */
    private val images = mutableStateMapOf<String, WidgetImage?>()

    /** Modification times the images were decoded at; written by the decoding thread. */
    private val stamps = ConcurrentHashMap<String, Long>()

    /** Srcs asked for so far; main thread only. */
    private val requests = HashSet<String>()

    /**
     * The image for [src], or null until it's decoded, which happens off the
     * main thread and at most at widget size. A guest file is checked again
     * (a stat; decoded only if it changed) whenever a source updates, so a
     * file a command rewrites (a cover, a chart) shows its new content. An
     * https URL comes through the launcher's image cache, and one that failed
     * is tried again then too. Safe to call while drawing: it only reads state.
     */
    fun image(src: String): WidgetImage? {
        // The launcher already holds every app's icon, as snapshot state: reading it redraws once it loads.
        if (src.startsWith("app:")) return services.appIcon(src.removePrefix("app:"))?.let { WidgetImage.Raster(it) }
        if (src.startsWith("icon:")) return services.launcherIcon(src.removePrefix("icon:"))?.let { WidgetImage.Raster(it) }
        // Album art changes with every track: Android holds the current one. Only its wrapper is
        // kept, so asking again for the same art gives the same image and redraws nothing.
        if (src.startsWith("media:")) {
            val art = services.androidImage(src) ?: return null
            mediaArt?.takeIf { it.first === art }?.let { return it.second }
            return WidgetImage.Raster(art.asImageBitmap()).also { mediaArt = art to it }
        }
        if (src !in requests) {
            // Not from inside a draw: that would write state while drawing.
            scope?.launch {
                if (src in requests) return@launch
                // A src that changes (a cover per track) mustn't keep every image it ever showed: at most
                // MAX_IMAGES bitmaps and MAX_VECTORS SVGs, which are small whatever size they're drawn at.
                val vector = isVector(src)
                val vectors = requests.count(::isVector)
                if (if (vector) vectors >= MAX_VECTORS else requests.size - vectors >= MAX_IMAGES) forget { isVector(it) == vector }
                requests.add(src)
                load(src)
            }
        }
        return images[src]
    }

    /** Whether [src] is an SVG: decoded as one, or, until it's loaded, named like one. */
    private fun isVector(src: String) = images[src]?.let { it is WidgetImage.Vector } ?: src.substringBefore('?').endsWith(".svg", ignoreCase = true)

    private fun forget(which: (String) -> Boolean) {
        val gone = requests.filter(which)
        requests.removeAll(gone.toSet())
        for (src in gone) {
            images.remove(src)
            stamps.remove(src)
        }
    }

    /** The album art last asked for, and its image; main thread only. */
    private var mediaArt: Pair<Bitmap, WidgetImage.Raster>? = null

    /** When guest image files were last checked for changes, in elapsed milliseconds. */
    private var checked = 0L

    /** Srcs being loaded now; main thread only. A recheck while one loads skips it rather than decoding it twice. */
    private val loading = HashSet<String>()

    private fun load(src: String) {
        val scope = scope ?: return
        scope.launch {
            if (!loading.add(src)) return@launch
            try {
                loadNow(src)
            } finally {
                loading.remove(src)
            }
        }
    }

    private suspend fun loadNow(src: String) {
        val remote = src.startsWith("https://")
        // The cache keeps a downloaded image fresh; one that loaded stays for as long as the widget runs.
        if (remote && images[src] != null) return
        val decoded =
            withContext(Dispatchers.IO) {
                // Resolved as the guest sees it: a stat per part of the path, so off the main thread too.
                val file = if (src.startsWith("asset:") || remote) null else services.guestFile(src)
                val stamp = file?.lastModified() ?: 0L
                if (!remote && stamps[src] == stamp) return@withContext null
                stamps[src] = stamp
                Result.success(runCatching { decode(src, file) }.getOrNull())
            } ?: return
        val image = decoded.getOrNull()
        if (image == null) report(if (remote) "image '$src' couldn't be downloaded, or isn't an image" else "image '$src' couldn't be loaded")
        images[src] = image
    }

    /** [src]'s image, from its asset, the web or a guest [file]; off the main thread. */
    private suspend fun decode(
        src: String,
        file: File?,
    ): WidgetImage? {
        val bytes =
            when {
                file != null -> {
                    if (file.length() >= MAX_FILE_BYTES) return null
                    val head = ByteArray(64)
                    val n = file.inputStream().use { it.read(head) }
                    if (n <= 0 || !isSvg(head.copyOf(n))) return raster(decodeSampled(file, MAX_IMAGE_SIDE / 2))
                    file.readBytes()
                }
                src.startsWith("https://") -> if (program.level < 8) return null else services.remoteFile(src)?.readBytes() ?: return null
                else -> (program.globals.firstOrNull { it.name == src.removePrefix("asset:") } as? Global.Asset)?.bytes ?: return null
            }
        if (isSvg(bytes)) return if (bytes.size <= MAX_SVG_BYTES) decodeSvg(bytes)?.let { WidgetImage.Vector(it) } else null
        return raster(if (src.startsWith("https://")) decodeSampled(bytes, MAX_IMAGE_SIDE / 2) else BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    // A small PNG can hold a huge flat image; drawing one over Android's limit throws on every frame.
    private fun raster(bitmap: Bitmap?) = bitmap?.takeIf { it.byteCount <= MAX_IMAGE_BYTES }?.let { WidgetImage.Raster(it.asImageBitmap()) }

    /**
     * After a source of [kind] updated. Guest image files are checked again (a stat each) after
     * guest commands, which may have rewritten them; after a clock tick or Android data, only
     * every few seconds. A web image that couldn't be downloaded is asked for again then too.
     */
    fun recheck(kind: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        // A stream can print many lines a second: its images are checked at most once a second.
        val due = now - checked >= if (kind == "cmd" || kind == "file") 0 else if (kind == "stream") STREAM_RECHECK_MS else RECHECK_MS
        if (due) {
            checked = now
            for (src in requests) if (!src.startsWith("asset:")) load(src)
        }
    }

    private companion object {
        /** How often guest image files are checked for changes when no guest command ran. */
        private const val RECHECK_MS = 5_000L

        /** How often guest image files are checked while a stream source prints. */
        private const val STREAM_RECHECK_MS = 1_000L

        /** Distinct bitmaps kept per widget. */
        private const val MAX_IMAGES = 32

        /** Distinct SVGs kept per widget; each is drawing commands, a few KB. */
        private const val MAX_VECTORS = 128

        /** SVG files larger than this aren't drawn. */
        private const val MAX_SVG_BYTES = 1 shl 20

        /** Guest image files this size or larger aren't read. */
        private const val MAX_FILE_BYTES = 8 shl 20

        /** Images are decoded no larger than needed for a tile this big, in pixels. */
        private const val MAX_IMAGE_SIDE = 1080

        /** Decoded images larger than this are dropped; Android refuses to draw past 100 MB. */
        private const val MAX_IMAGE_BYTES = 32 shl 20
    }
}
