package com.androidcast

import android.content.Context
import java.io.File

/**
 * The folder of background images/videos. Items play in filename order, so
 * prefix names with 01_, 02_, ... to control the order.
 *
 * Location: /sdcard/Android/data/com.androidcast/files/backgrounds
 * (you can also `adb push` files straight into it).
 */
class MediaLibrary(context: Context) {

    enum class Kind { IMAGE, VIDEO }

    val dir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "backgrounds")
        .apply { mkdirs() }

    fun items(): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && kindOf(it) != null }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    fun find(name: String): File? = items().firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Turns a client-supplied name into a safe filename inside [dir]. */
    fun safeFile(name: String): File {
        val clean = name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim().trimStart('.')
        require(clean.isNotEmpty()) { "invalid file name" }
        val file = File(dir, clean)
        requireNotNull(kindOf(file)) { "unsupported file type (use ${EXTENSIONS.keys.joinToString()})" }
        return file
    }

    /** Temporary file used while an upload/download is in progress (hidden from [items]). */
    fun partFile(target: File): File = File(dir, ".${target.name}.part")

    companion object {
        private val EXTENSIONS = mapOf(
            "jpg" to Kind.IMAGE, "jpeg" to Kind.IMAGE, "png" to Kind.IMAGE,
            "webp" to Kind.IMAGE, "bmp" to Kind.IMAGE, "gif" to Kind.IMAGE,
            "mp4" to Kind.VIDEO, "m4v" to Kind.VIDEO, "mkv" to Kind.VIDEO,
            "webm" to Kind.VIDEO, "3gp" to Kind.VIDEO, "mov" to Kind.VIDEO,
        )

        fun kindOf(file: File): Kind? = EXTENSIONS[file.extension.lowercase()]
    }
}
