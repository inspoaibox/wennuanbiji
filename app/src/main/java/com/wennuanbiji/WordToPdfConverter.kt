package com.wennuanbiji

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.xwpf.usermodel.IBodyElement
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Converts Word files into a paged PDF snapshot so the existing annotation
 * engine can treat PDF and Word documents uniformly.
 *
 * This is deliberately an offline, dependency-free rendering path after POI
 * extraction. Paragraphs, tables, and embedded paragraph images are preserved
 * in a readable layout; advanced Word layout features are normalized.
 */
object WordToPdfConverter {
    private const val PAGE_WIDTH = 1440
    private const val PAGE_HEIGHT = 2036
    private const val MARGIN = 110f
    private const val BODY_WIDTH = PAGE_WIDTH - (MARGIN * 2)

    private sealed interface Block {
        data class Text(val value: String) : Block
        data class Image(val bytes: ByteArray) : Block
    }

    fun convert(source: File, destination: File) {
        val blocks = when (source.extension.lowercase()) {
            "docx", "docm", "dotx" -> readDocx(source)
            "doc" -> readDoc(source)
            else -> error("不支持的 Word 格式：${source.extension}")
        }
        renderBlocks(blocks, destination)
    }

    private fun readDocx(source: File): List<Block> {
        val blocks = mutableListOf<Block>()
        FileInputStream(source).use { input ->
            XWPFDocument(input).use { document ->
                document.bodyElements.forEach { element: IBodyElement ->
                    when (element) {
                        is XWPFParagraph -> addParagraph(blocks, element)
                        is XWPFTable -> addTable(blocks, element)
                    }
                }
            }
        }
        return blocks.ifEmpty { listOf(Block.Text("（Word 文档没有可显示的文字内容）")) }
    }

    private fun addParagraph(blocks: MutableList<Block>, paragraph: XWPFParagraph) {
        paragraph.text.trim().takeIf { it.isNotEmpty() }?.let { blocks += Block.Text(it) }
        paragraph.runs.forEach { run ->
            run.embeddedPictures.forEach { picture ->
                picture.pictureData?.data?.let { bytes -> blocks += Block.Image(bytes) }
            }
        }
    }

    private fun addTable(blocks: MutableList<Block>, table: XWPFTable) {
        table.rows.forEach { row ->
            val line = row.tableCells.joinToString("    ") { cell ->
                cell.text.replace(Regex("\\s+"), " ").trim()
            }.trim()
            if (line.isNotEmpty()) blocks += Block.Text(line)
        }
        blocks += Block.Text("")
    }

    private fun readDoc(source: File): List<Block> {
        val blocks = mutableListOf<Block>()
        FileInputStream(source).use { input ->
            val document = HWPFDocument(input)
            val extractor = WordExtractor(document)
            try {
                extractor.paragraphText.forEach { paragraph ->
                    paragraph.trim().takeIf { it.isNotEmpty() }?.let { blocks += Block.Text(it) }
                }
            } finally {
                extractor.close()
                document.close()
            }
        }
        return blocks.ifEmpty { listOf(Block.Text("（Word 文档没有可显示的文字内容）")) }
    }

    private fun renderBlocks(blocks: List<Block>, destination: File) {
        val pdf = PdfDocument()
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 39, 36)
            textSize = 30f
            typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL)
        }
        val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        var pageNumber = 0
        var page = pdf.startPage(pageInfo(pageNumber))
        var cursorY = MARGIN

        fun finishPage() {
            pdf.finishPage(page)
        }

        fun startNewPage() {
            finishPage()
            pageNumber += 1
            page = pdf.startPage(pageInfo(pageNumber))
            cursorY = MARGIN
        }

        blocks.forEach { block ->
            when (block) {
                is Block.Text -> {
                    val value = block.value.ifBlank { " " }
                    val layout = StaticLayout.Builder
                        .obtain(value, 0, value.length, textPaint, BODY_WIDTH.toInt())
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setIncludePad(true)
                        .setLineSpacing(5f, 1.12f)
                        .build()
                    if (cursorY + layout.height > PAGE_HEIGHT - MARGIN && cursorY > MARGIN) {
                        startNewPage()
                    }
                    page.canvas.save()
                    page.canvas.translate(MARGIN, cursorY)
                    layout.draw(page.canvas)
                    page.canvas.restore()
                    cursorY += layout.height + 20f
                }
                is Block.Image -> {
                    val bitmap = BitmapFactory.decodeByteArray(block.bytes, 0, block.bytes.size)
                        ?: return@forEach
                    val scale = minOf(
                        BODY_WIDTH / bitmap.width.toFloat(),
                        (PAGE_HEIGHT - 2 * MARGIN) / bitmap.height.toFloat(),
                        1f
                    )
                    val width = bitmap.width * scale
                    val height = bitmap.height * scale
                    if (cursorY + height > PAGE_HEIGHT - MARGIN && cursorY > MARGIN) {
                        startNewPage()
                    }
                    page.canvas.drawBitmap(
                        bitmap,
                        null,
                        RectF(MARGIN, cursorY, MARGIN + width, cursorY + height),
                        imagePaint
                    )
                    cursorY += height + 24f
                    bitmap.recycle()
                }
            }
        }
        finishPage()

        FileOutputStream(destination).use { output -> pdf.writeTo(output) }
        pdf.close()
    }

    private fun pageInfo(pageNumber: Int): PdfDocument.PageInfo =
        PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
}
