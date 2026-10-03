package com.wennuanbiji

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import kotlin.math.max

object PdfExporter {
    suspend fun export(
        context: Context,
        document: PdfDocumentInfo,
        annotations: List<PdfAnnotation>,
        destination: android.net.Uri
    ) = withContext(Dispatchers.IO) {
        val descriptor = ParcelFileDescriptor.open(
            document.file,
            ParcelFileDescriptor.MODE_READ_ONLY
        )
        val renderer = PdfRenderer(descriptor)
        val output = PdfDocument()

        try {
            for (pageIndex in 0 until renderer.pageCount) {
                val sourcePage = renderer.openPage(pageIndex)
                val pageWidth = 1440
                val pageHeight = max(
                    1,
                    (pageWidth * sourcePage.height.toFloat() / sourcePage.width.toFloat()).toInt()
                )
                val bitmap = Bitmap.createBitmap(
                    pageWidth,
                    pageHeight,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.eraseColor(Color.WHITE)
                sourcePage.render(
                    bitmap,
                    null,
                    null,
                    PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                )
                sourcePage.close()

                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex).create()
                val outputPage = output.startPage(pageInfo)
                outputPage.canvas.drawBitmap(bitmap, null, Rect(0, 0, pageWidth, pageHeight), null)
                annotations.filter { it.page == pageIndex }.forEach {
                    drawAnnotation(outputPage.canvas, it, pageWidth.toFloat(), pageHeight.toFloat())
                }
                output.finishPage(outputPage)
                bitmap.recycle()
            }

            context.contentResolver.openOutputStream(destination).use { stream ->
                requireNotNull(stream) { "无法打开导出位置" }
                output.writeTo(stream)
            }
        } finally {
            output.close()
            renderer.close()
            descriptor.close()
        }
    }

    private fun drawAnnotation(canvas: Canvas, annotation: PdfAnnotation, width: Float, height: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = annotation.color.toInt()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            style = Paint.Style.STROKE
            strokeWidth = annotation.strokeWidth * minOf(width, height)
        }

        when (annotation.kind) {
            AnnotationKind.TEXT -> {
                paint.style = Paint.Style.FILL
                paint.textSize = max(24f, width * 0.028f)
                canvas.drawText(annotation.text, annotation.x * width, annotation.y * height, paint)
            }
            AnnotationKind.INK,
            AnnotationKind.HIGHLIGHT,
            AnnotationKind.UNDERLINE -> {
                if (annotation.kind == AnnotationKind.HIGHLIGHT) {
                    paint.alpha = 90
                    paint.strokeWidth = max(20f, annotation.strokeWidth * minOf(width, height) * 3f)
                }
                if (annotation.kind == AnnotationKind.UNDERLINE && annotation.points.size >= 2) {
                    val first = annotation.points.first()
                    val last = annotation.points.last()
                    canvas.drawLine(
                        first.x * width,
                        first.y * height,
                        last.x * width,
                        last.y * height,
                        paint
                    )
                    return
                }
                val path = Path()
                annotation.points.forEachIndexed { index, point ->
                    val x = point.x * width
                    val y = point.y * height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                if (annotation.points.isNotEmpty()) canvas.drawPath(path, paint)
            }
        }
    }
}
