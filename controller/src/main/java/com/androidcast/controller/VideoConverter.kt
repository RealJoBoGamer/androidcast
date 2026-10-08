package com.androidcast.controller

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.Effect
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Old Fire TV sticks only play H.264 video up to 1080p at about 30 fps. Phones usually
 * record HEVC, 4K, 60 fps and/or HDR, so videos are checked before upload and converted
 * on the phone when needed.
 */
class VideoConverter(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    data class Info(val mime: String, val width: Int, val height: Int, val fps: Float, val hdr: Boolean) {
        /** Human-readable reasons the stick can't play this, empty if it's fine. */
        val problems: List<String>
            get() = buildList {
                if (mime != MimeTypes.VIDEO_H264) add(codecName(mime))
                if (max(width, height) > MAX_LONG || min(width, height) > MAX_SHORT) add("${width}×$height")
                if (fps > MAX_FPS + 1) add("${fps.roundToInt()} fps")
                if (hdr) add("HDR")
            }
    }

    /** Reads the first video track's format, or null if it can't be read. */
    fun probe(uri: Uri): Info? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, uri, null)
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }.firstNotNullOfOrNull { f ->
                val mime = f.getString(MediaFormat.KEY_MIME)?.takeIf { it.startsWith("video/") } ?: return@firstNotNullOfOrNull null
                var w = f.getInteger(MediaFormat.KEY_WIDTH)
                var h = f.getInteger(MediaFormat.KEY_HEIGHT)
                val rotation = if (Build.VERSION.SDK_INT >= 23 && f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
                if (rotation == 90 || rotation == 270) w = h.also { h = w }
                val fps = when {
                    !f.containsKey(MediaFormat.KEY_FRAME_RATE) -> 0f
                    else -> try { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() } catch (e: ClassCastException) { f.getFloat(MediaFormat.KEY_FRAME_RATE) }
                }
                val hdr = Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_COLOR_TRANSFER) &&
                    f.getInteger(MediaFormat.KEY_COLOR_TRANSFER).let {
                        it == MediaFormat.COLOR_TRANSFER_ST2084 || it == MediaFormat.COLOR_TRANSFER_HLG
                    }
                Info(mime, w, h, fps, hdr)
            }
        } catch (e: Exception) {
            null
        } finally {
            ex.release()
        }
    }

    /**
     * Converts to H.264 MP4, at most 1080p / 30 fps / SDR. Blocks until done, so call it
     * from a background thread. Returns null on success, or an error message.
     */
    fun convert(uri: Uri, info: Info, output: File, bitrate: Int, progress: (percent: Int) -> Unit): String? {
        val scale = min(1f, min(MAX_LONG.toFloat() / max(info.width, info.height), MAX_SHORT.toFloat() / min(info.width, info.height)))
        val outW = even(info.width * scale)
        val outH = even(info.height * scale)
        val videoEffects = buildList<Effect> {
            if (info.fps > MAX_FPS + 1) add(FrameDropEffect.createDefaultFrameDropEffect(MAX_FPS.toFloat()))
            add(Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT))
        }

        val done = CountDownLatch(1)
        var error: String? = null
        var transformer: Transformer? = null
        output.delete()

        // Transformer must be created and driven from a thread with a Looper.
        main.post {
            try {
                val encoders = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                    .setEnableFallback(true)
                    .build()
                val t = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoders)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) = done.countDown()
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            error = exportException.message ?: "conversion failed"
                            done.countDown()
                        }
                    })
                    .build()
                val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                    .setEffects(Effects(emptyList<AudioProcessor>(), videoEffects))
                    .build()
                val composition = Composition.Builder(EditedMediaItemSequence(item))
                    .apply {
                        // HDR -> normal colours (needs Android 10+ for the OpenGL tone mapper).
                        if (info.hdr && Build.VERSION.SDK_INT >= 29) {
                            setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                        }
                    }
                    .build()
                transformer = t
                t.start(composition, output.absolutePath)
            } catch (e: Exception) {
                error = e.message ?: "conversion failed"
                done.countDown()
            }
        }

        val holder = ProgressHolder()
        while (!done.await(500, TimeUnit.MILLISECONDS)) {
            main.post {
                val t = transformer ?: return@post
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress)
            }
        }
        if (error != null) output.delete()
        return error
    }

    companion object {
        const val MAX_LONG = 1920
        const val MAX_SHORT = 1080
        const val MAX_FPS = 30

        private fun even(v: Float) = max(2, (v.roundToInt() / 2) * 2)

        fun codecName(mime: String) = when (mime) {
            MimeTypes.VIDEO_H265 -> "H.265/HEVC"
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_VP9 -> "VP9"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
            else -> mime.removePrefix("video/").uppercase()
        }
    }
}
