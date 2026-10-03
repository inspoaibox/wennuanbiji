package com.wennuanbiji

import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class PdfPoint(
    val x: Float,
    val y: Float
)

@Serializable
enum class AnnotationKind {
    INK,
    HIGHLIGHT,
    UNDERLINE,
    TEXT
}

@Serializable
data class PdfAnnotation(
    val id: String,
    val page: Int,
    val kind: AnnotationKind,
    val points: List<PdfPoint> = emptyList(),
    val text: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val color: Long = 0xFFEF7D62,
    val strokeWidth: Float = 0.008f
)

data class PdfDocumentInfo(
    val file: File,
    val displayName: String,
    val pageCount: Int
)

enum class AnnotationTool(val label: String) {
    SELECT("选择"),
    PEN("手写"),
    HIGHLIGHT("荧光笔"),
    UNDERLINE("下划线"),
    TEXT("文字"),
    ERASER("橡皮擦")
}
