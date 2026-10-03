package com.wennuanbiji

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream

object PdfStorage {
    private val annotationWriteMutex = Mutex()
    private val pageCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Deprecated("Use importDocument so PDF and Word files share one import path")
    fun importPdf(context: Context, uri: Uri): PdfDocumentInfo = importDocument(context, uri)

    fun importDocument(context: Context, uri: Uri): PdfDocumentInfo {
        val displayName = queryDisplayName(context, uri)
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        val extension = displayName.substringAfterLast('.', "").lowercase().ifBlank {
            when {
                mimeType.contains("wordprocessingml") -> "docx"
                mimeType == "application/msword" -> "doc"
                else -> "pdf"
            }
        }
        val safeBaseName = displayName
            .substringBeforeLast('.', missingDelimiterValue = displayName)
            .ifBlank { "document" }
            .replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5._-]"), "_")
        val folder = File(context.filesDir, "pdfs").apply { mkdirs() }
        val target = File(folder, "$safeBaseName-${System.currentTimeMillis()}.pdf")
        val source = File(context.cacheDir, "import-${System.currentTimeMillis()}.$extension")

        val inputStream = requireNotNull(context.contentResolver.openInputStream(uri)) {
            "无法读取文档文件"
        }
        inputStream.use { input ->
            FileOutputStream(source).use { output -> input.copyTo(output) }
        }

        try {
            when (extension) {
                "pdf" -> source.copyTo(target, overwrite = true)
                "doc", "docx", "docm", "dotx" -> WordToPdfConverter.convert(source, target)
                else -> error("暂不支持 .$extension 文件，请先转换为 PDF 或 DOCX")
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        } finally {
            source.delete()
        }

        return readInfo(target).copy(displayName = safeBaseName)
    }

    fun readInfo(file: File): PdfDocumentInfo {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(descriptor)
        return try {
            PdfDocumentInfo(file, file.nameWithoutExtension, renderer.pageCount)
        } finally {
            renderer.close()
            descriptor.close()
        }
    }

    fun listDocuments(context: Context): List<PdfDocumentInfo> {
        val folder = File(context.filesDir, "pdfs")
        return folder.listFiles { file ->
            file.isFile && file.extension.equals("pdf", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { file -> runCatching { readInfo(file) }.getOrNull() }
            ?: emptyList()
    }

    fun renderPage(file: File, pageIndex: Int, targetWidth: Int = 1600): Bitmap {
        val cacheKey = "${file.absolutePath}:${file.lastModified()}:$pageIndex:$targetWidth"
        synchronized(pageCache) {
            pageCache.get(cacheKey)?.let { return it }
        }
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(descriptor)
        return try {
            val page = renderer.openPage(pageIndex)
            try {
                val targetHeight = (targetWidth * page.height.toFloat() / page.width.toFloat())
                    .toInt()
                    .coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                synchronized(pageCache) { pageCache.put(cacheKey, bitmap) }
                bitmap
            } finally {
                page.close()
            }
        } finally {
            renderer.close()
            descriptor.close()
        }
    }

    fun annotationFile(document: PdfDocumentInfo): File =
        File(document.file.parentFile, "${document.file.nameWithoutExtension}.warmnotes.json")

    fun loadAnnotations(document: PdfDocumentInfo): List<PdfAnnotation> {
        val file = annotationFile(document)
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<PdfAnnotation>>(file.readText()) }
            .getOrDefault(emptyList())
    }

    suspend fun saveAnnotations(document: PdfDocumentInfo, annotations: List<PdfAnnotation>) {
        annotationWriteMutex.withLock {
            val target = annotationFile(document)
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.writeText(json.encodeToString(annotations))
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return "document.pdf"
    }
}
