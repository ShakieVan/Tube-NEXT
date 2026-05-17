package de.shakie.tubenext.tabs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

class TabPreviewStore(context: Context) {
    private val previewDir = File(context.filesDir, PREVIEW_DIR).apply { mkdirs() }

    fun save(tabId: String, bitmap: Bitmap) {
        val file = fileFor(tabId)
        runCatching {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        }
    }

    fun load(tabId: String): Bitmap? {
        val file = fileFor(tabId)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    fun delete(tabId: String) {
        fileFor(tabId).delete()
    }

    fun prune(validTabIds: Set<String>) {
        previewDir.listFiles()?.forEach { file ->
            val name = file.name
            if (!name.endsWith(FILE_SUFFIX)) return@forEach
            val id = name.removeSuffix(FILE_SUFFIX)
            if (id !in validTabIds) {
                file.delete()
            }
        }
    }

    private fun fileFor(tabId: String): File {
        return File(previewDir, tabId + FILE_SUFFIX)
    }

    companion object {
        private const val PREVIEW_DIR = "tab_previews"
        private const val FILE_SUFFIX = ".jpg"
        private const val JPEG_QUALITY = 72
    }
}
