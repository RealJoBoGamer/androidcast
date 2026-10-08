package com.androidcast

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/** Describes a video file's format, to explain why it won't play (e.g. "H.265/HEVC · 3840×2160 · 60 fps"). */
object VideoProbe {
    fun describe(file: File): String? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }.firstNotNullOfOrNull { f ->
                val mime = f.getString(MediaFormat.KEY_MIME)?.takeIf { it.startsWith("video/") } ?: return@firstNotNullOfOrNull null
                val parts = mutableListOf(codecName(mime))
                if (f.containsKey(MediaFormat.KEY_WIDTH)) {
                    parts += "${f.getInteger(MediaFormat.KEY_WIDTH)}×${f.getInteger(MediaFormat.KEY_HEIGHT)}"
                }
                if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    val fps = try { f.getInteger(MediaFormat.KEY_FRAME_RATE) } catch (e: ClassCastException) { f.getFloat(MediaFormat.KEY_FRAME_RATE).toInt() }
                    parts += "$fps fps"
                }
                parts.joinToString(" · ")
            }
        } catch (e: Exception) {
            null
        } finally {
            ex.release()
        }
    }

    private fun codecName(mime: String) = when (mime) {
        "video/hevc" -> "H.265/HEVC"
        "video/avc" -> "H.264"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/av01" -> "AV1"
        "video/dolby-vision" -> "Dolby Vision"
        else -> mime.removePrefix("video/").uppercase()
    }
}
