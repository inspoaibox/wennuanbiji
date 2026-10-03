package com.wennuanbiji

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val SUPPORTED_DOCUMENT_TYPES = arrayOf(
    "application/pdf",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-word.document.macroEnabled.12",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.template"
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WarmNotesTheme {
                WarmNotesApp(initialUri = intent?.data)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WarmNotesApp(initialUri: Uri? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var document by remember { mutableStateOf<PdfDocumentInfo?>(null) }
    var annotations by remember { mutableStateOf<List<PdfAnnotation>>(emptyList()) }
    var undoStack by remember { mutableStateOf<List<List<PdfAnnotation>>>(emptyList()) }
    var redoStack by remember { mutableStateOf<List<List<PdfAnnotation>>>(emptyList()) }
    var recentDocuments by remember { mutableStateOf<List<PdfDocumentInfo>>(emptyList()) }
    var currentPage by remember { mutableStateOf(0) }
    var selectedTool by remember { mutableStateOf(AnnotationTool.PEN) }
    var selectedAnnotationId by remember { mutableStateOf<String?>(null) }
    var pendingTextPoint by remember { mutableStateOf<PdfPoint?>(null) }
    var editingAnnotationId by remember { mutableStateOf<String?>(null) }
    var textInput by remember { mutableStateOf("") }
    var pageInput by remember { mutableStateOf("1") }

    fun showMessage(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun commitAnnotations(next: List<PdfAnnotation>) {
        undoStack = undoStack + listOf(annotations)
        annotations = next
        redoStack = emptyList()
    }

    fun undo() {
        val previous = undoStack.lastOrNull() ?: return
        redoStack = redoStack + listOf(annotations)
        annotations = previous
        undoStack = undoStack.dropLast(1)
    }

    fun redo() {
        val next = redoStack.lastOrNull() ?: return
        undoStack = undoStack + listOf(annotations)
        annotations = next
        redoStack = redoStack.dropLast(1)
    }

    val importLauncher = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val importedWithAnnotations = withContext(Dispatchers.IO) {
                    val imported = PdfStorage.importDocument(context, uri)
                    imported to PdfStorage.loadAnnotations(imported)
                }
                val imported = importedWithAnnotations.first
                val loadedAnnotations = importedWithAnnotations.second
                document = imported
                annotations = loadedAnnotations
                selectedAnnotationId = null
                editingAnnotationId = null
                undoStack = emptyList()
                redoStack = emptyList()
                recentDocuments = withContext(Dispatchers.IO) {
                    PdfStorage.listDocuments(context)
                }
                currentPage = 0
                pageInput = "1"
            }.onFailure {
                showMessage("导入失败：${it.message ?: "文件无法读取"}")
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(CreateDocument("application/pdf")) { uri ->
        val currentDocument = document ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                PdfExporter.export(context, currentDocument, annotations, uri)
            }.onSuccess {
                showMessage("已导出带标注的 PDF")
            }.onFailure {
                showMessage("导出失败：${it.message ?: "无法写入文件"}")
            }
        }
    }

    LaunchedEffect(Unit) {
        recentDocuments = withContext(Dispatchers.IO) {
            PdfStorage.listDocuments(context)
        }
    }

    LaunchedEffect(initialUri) {
        if (initialUri != null && document == null) {
            runCatching {
                val importedWithAnnotations = withContext(Dispatchers.IO) {
                    val imported = PdfStorage.importDocument(context, initialUri)
                    imported to PdfStorage.loadAnnotations(imported)
                }
                val imported = importedWithAnnotations.first
                val loadedAnnotations = importedWithAnnotations.second
                document = imported
                annotations = loadedAnnotations
                selectedAnnotationId = null
                editingAnnotationId = null
                undoStack = emptyList()
                redoStack = emptyList()
                recentDocuments = withContext(Dispatchers.IO) {
                    PdfStorage.listDocuments(context)
                }
            }.onFailure {
                showMessage("打开失败：${it.message ?: "文件无法读取"}")
            }
        }
    }

    LaunchedEffect(document, annotations) {
        val currentDocument = document ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            PdfStorage.saveAnnotations(currentDocument, annotations)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = document?.displayName ?: "温暖笔记",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    TextButton(onClick = { importLauncher.launch(SUPPORTED_DOCUMENT_TYPES) }) {
                        Text("导入文档")
                    }
                    if (document != null) {
                        if (selectedAnnotationId != null) {
                            TextButton(onClick = {
                                commitAnnotations(annotations.filterNot { it.id == selectedAnnotationId })
                                selectedAnnotationId = null
                            }) { Text("删除标注") }
                        }
                        TextButton(enabled = undoStack.isNotEmpty(), onClick = ::undo) {
                            Text("撤销")
                        }
                        TextButton(enabled = redoStack.isNotEmpty(), onClick = ::redo) {
                            Text("重做")
                        }
                        TextButton(onClick = {
                            val name = "${document!!.displayName}-annotated.pdf"
                            exportLauncher.launch(name)
                        }) {
                            Text("导出")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (document == null) {
            HomeContent(
                modifier = Modifier.padding(padding),
                recentDocuments = recentDocuments,
                onImport = { importLauncher.launch(SUPPORTED_DOCUMENT_TYPES) },
                onOpenRecent = { selected ->
                    scope.launch {
                        val loadedAnnotations = withContext(Dispatchers.IO) {
                            PdfStorage.loadAnnotations(selected)
                        }
                        document = selected
                        annotations = loadedAnnotations
                        selectedAnnotationId = null
                        editingAnnotationId = null
                        undoStack = emptyList()
                        redoStack = emptyList()
                        currentPage = 0
                        pageInput = "1"
                    }
                }
            )
        } else {
            val currentDocument = document!!
            EditorContent(
                modifier = Modifier.padding(padding),
                document = currentDocument,
                annotations = annotations,
                currentPage = currentPage,
                pageInput = pageInput,
                selectedTool = selectedTool,
                selectedAnnotationId = selectedAnnotationId,
                onToolSelected = { selectedTool = it },
                onAnnotationSelected = { selectedAnnotationId = it },
                onPageInputChanged = { pageInput = it },
                onPageSelected = { page ->
                    currentPage = page.coerceIn(0, currentDocument.pageCount - 1)
                    pageInput = (currentPage + 1).toString()
                },
                onPageRequested = {
                    val requested = pageInput.toIntOrNull()?.minus(1) ?: currentPage
                    currentPage = requested.coerceIn(0, currentDocument.pageCount - 1)
                    pageInput = (currentPage + 1).toString()
                },
                onPreviousPage = {
                    currentPage = (currentPage - 1).coerceAtLeast(0)
                    pageInput = (currentPage + 1).toString()
                },
                onNextPage = {
                    currentPage = (currentPage + 1).coerceAtMost(currentDocument.pageCount - 1)
                    pageInput = (currentPage + 1).toString()
                },
                onAddStroke = { kind, points ->
                    if (selectedTool == AnnotationTool.ERASER) {
                        commitAnnotations(annotations.filterNot { annotation ->
                            if (annotation.page != currentPage) return@filterNot false
                            if (annotation.kind == AnnotationKind.TEXT) {
                                points.any { point ->
                                    kotlin.math.abs(point.x - annotation.x) < 0.06f &&
                                        kotlin.math.abs(point.y - annotation.y) < 0.06f
                                }
                            } else {
                                annotation.points.any { annotationPoint ->
                                    points.any { eraserPoint ->
                                        kotlin.math.abs(annotationPoint.x - eraserPoint.x) < 0.04f &&
                                            kotlin.math.abs(annotationPoint.y - eraserPoint.y) < 0.04f
                                    }
                                }
                            }
                        })
                    } else {
                        val annotationKind = when (kind) {
                            AnnotationTool.HIGHLIGHT -> AnnotationKind.HIGHLIGHT
                            AnnotationTool.UNDERLINE -> AnnotationKind.UNDERLINE
                            else -> AnnotationKind.INK
                        }
                        commitAnnotations(annotations + PdfAnnotation(
                            id = UUID.randomUUID().toString(),
                            page = currentPage,
                            kind = annotationKind,
                            points = points,
                            color = when (annotationKind) {
                                AnnotationKind.HIGHLIGHT -> 0xFFFFD54F
                                AnnotationKind.UNDERLINE -> 0xFFEF7D62
                                else -> 0xFFEF5B51
                            },
                            strokeWidth = when (annotationKind) {
                                AnnotationKind.HIGHLIGHT -> 0.012f
                                AnnotationKind.UNDERLINE -> 0.006f
                                else -> 0.006f
                            }
                        ))
                    }
                },
                onTextRequested = { point ->
                    editingAnnotationId = null
                    pendingTextPoint = point
                    textInput = ""
                },
                onEditText = { annotation ->
                    editingAnnotationId = annotation.id
                    pendingTextPoint = PdfPoint(annotation.x, annotation.y)
                    textInput = annotation.text
                },
                onMoveAnnotation = { id, deltaX, deltaY ->
                    val next = annotations.map { annotation ->
                        if (annotation.id != id) annotation
                        else if (annotation.kind == AnnotationKind.TEXT) {
                            annotation.copy(
                                x = (annotation.x + deltaX).coerceIn(0f, 1f),
                                y = (annotation.y + deltaY).coerceIn(0f, 1f)
                            )
                        } else {
                            annotation.copy(
                                points = annotation.points.map { point ->
                                    PdfPoint(
                                        x = (point.x + deltaX).coerceIn(0f, 1f),
                                        y = (point.y + deltaY).coerceIn(0f, 1f)
                                    )
                                }
                            )
                        }
                    }
                    commitAnnotations(next)
                }
            )
        }
    }

    if (pendingTextPoint != null) {
        AlertDialog(
            onDismissRequest = {
                editingAnnotationId = null
                pendingTextPoint = null
            },
            title = { Text(if (editingAnnotationId == null) "添加文字注释" else "修改文字注释") },
            text = {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入你的笔记") },
                    minLines = 3
                )
            },
            confirmButton = {
                TextButton(
                    enabled = textInput.isNotBlank(),
                    onClick = {
                        val point = pendingTextPoint ?: return@TextButton
                        val editingId = editingAnnotationId
                        if (editingId == null) {
                            commitAnnotations(annotations + PdfAnnotation(
                                id = UUID.randomUUID().toString(),
                                page = currentPage,
                                kind = AnnotationKind.TEXT,
                                text = textInput.trim(),
                                x = point.x,
                                y = point.y,
                                color = 0xFF7C5146
                            ))
                        } else {
                            commitAnnotations(annotations.map { annotation ->
                                if (annotation.id == editingId) annotation.copy(text = textInput.trim()) else annotation
                            })
                        }
                        editingAnnotationId = null
                        pendingTextPoint = null
                    }
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = {
                    editingAnnotationId = null
                    pendingTextPoint = null
                }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun HomeContent(
    modifier: Modifier = Modifier,
    recentDocuments: List<PdfDocumentInfo>,
    onImport: () -> Unit,
    onOpenRecent: (PdfDocumentInfo) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(112.dp),
            shape = RoundedCornerShape(32.dp),
            color = Color.Transparent
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "温暖笔记 Logo",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
        Spacer(Modifier.height(22.dp))
        Text("温暖笔记", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "导入 PDF 或 Word，在页面上留下你的想法。",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onImport, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)) {
            Text("选择 PDF 文件")
        }
        if (recentDocuments.isNotEmpty()) {
            Spacer(Modifier.height(32.dp))
            Text("最近打开", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            recentDocuments.take(5).forEach { recent ->
                OutlinedButton(
                    onClick = { onOpenRecent(recent) },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    Text(recent.displayName, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun EditorContent(
    modifier: Modifier,
    document: PdfDocumentInfo,
    annotations: List<PdfAnnotation>,
    currentPage: Int,
    pageInput: String,
    selectedTool: AnnotationTool,
    selectedAnnotationId: String?,
    onToolSelected: (AnnotationTool) -> Unit,
    onAnnotationSelected: (String?) -> Unit,
    onPageInputChanged: (String) -> Unit,
    onPageSelected: (Int) -> Unit,
    onPageRequested: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onAddStroke: (AnnotationTool, List<PdfPoint>) -> Unit,
    onTextRequested: (PdfPoint) -> Unit,
    onEditText: (PdfAnnotation) -> Unit,
    onMoveAnnotation: (String, Float, Float) -> Unit
) {
    Column(modifier = modifier.fillMaxSize()) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items((0 until document.pageCount).toList()) { index ->
                PdfThumbnail(
                    document = document,
                    pageIndex = index,
                    selected = index == currentPage,
                    onClick = { onPageSelected(index) }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnnotationTool.values().forEach { tool ->
                FilterChip(
                    selected = tool == selectedTool,
                    onClick = { onToolSelected(tool) },
                    label = { Text(tool.label) }
                )
            }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            OutlinedButton(onClick = onPreviousPage, enabled = currentPage > 0) { Text("上一页") }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = pageInput,
                onValueChange = onPageInputChanged,
                modifier = Modifier.width(76.dp),
                singleLine = true
            )
            Text(" / ${document.pageCount}", modifier = Modifier.padding(horizontal = 6.dp))
            Button(onClick = onPageRequested) { Text("跳转") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = onNextPage,
                enabled = currentPage < document.pageCount - 1
            ) { Text("下一页") }
        }
        HorizontalDivider()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .background(Color(0xFFF1EEE9))
                .padding(12.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            PdfPageView(
                document = document,
                pageIndex = currentPage,
                annotations = annotations,
                selectedTool = selectedTool,
                selectedAnnotationId = selectedAnnotationId,
                onAddStroke = onAddStroke,
                onTextRequested = onTextRequested,
                onEditText = onEditText,
                onAnnotationSelected = onAnnotationSelected,
                onMoveAnnotation = onMoveAnnotation
            )
        }
    }
}

@Composable
private fun PdfThumbnail(
    document: PdfDocumentInfo,
    pageIndex: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    var bitmap by remember(document.file, pageIndex) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(document.file, pageIndex) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { PdfStorage.renderPage(document.file, pageIndex, targetWidth = 180) }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .width(68.dp)
            .height(98.dp)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFFD6D0C8),
                shape = RoundedCornerShape(5.dp)
            )
            .clickable(onClick = onClick)
            .background(Color.White, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "第 ${pageIndex + 1} 页缩略图",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp),
                contentScale = ContentScale.Fit
            )
        } ?: Text("${pageIndex + 1}", fontSize = 12.sp)
        Text(
            text = "${pageIndex + 1}",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(Color.White.copy(alpha = 0.85f))
                .padding(horizontal = 3.dp),
            fontSize = 10.sp
        )
    }
}

@Composable
private fun PdfPageView(
    document: PdfDocumentInfo,
    pageIndex: Int,
    annotations: List<PdfAnnotation>,
    selectedTool: AnnotationTool,
    selectedAnnotationId: String?,
    onAddStroke: (AnnotationTool, List<PdfPoint>) -> Unit,
    onTextRequested: (PdfPoint) -> Unit,
    onEditText: (PdfAnnotation) -> Unit,
    onAnnotationSelected: (String?) -> Unit,
    onMoveAnnotation: (String, Float, Float) -> Unit
) {
    var bitmap by remember(document.file, pageIndex) { mutableStateOf<Bitmap?>(null) }
    var draftPoints by remember(selectedTool, pageIndex) { mutableStateOf<List<PdfPoint>>(emptyList()) }
    var zoom by remember(document.file, pageIndex) { mutableStateOf(1f) }
    var panOffset by remember(document.file, pageIndex) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        zoom = (zoom * zoomChange).coerceIn(1f, 4f)
        panOffset += panChange
    }

    LaunchedEffect(document.file, pageIndex) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { PdfStorage.renderPage(document.file, pageIndex) }.getOrNull()
        }
    }

    val pageBitmap = bitmap
    if (pageBitmap == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("正在加载第 ${pageIndex + 1} 页…")
        }
        return
    }

    val ratio = pageBitmap.width.toFloat() / pageBitmap.height.toFloat()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = widthPx / ratio
        val pageAnnotations = annotations.filter { it.page == pageIndex }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .clipToBounds()
                .transformable(
                    state = transformState,
                    enabled = selectedTool == AnnotationTool.SELECT
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = panOffset.x
                        translationY = panOffset.y
                    }
            ) {
            Image(
                bitmap = pageBitmap.asImageBitmap(),
                contentDescription = "PDF 第 ${pageIndex + 1} 页",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                pageAnnotations.forEach { drawPdfAnnotation(it, size.width, size.height) }
                pageAnnotations.find { it.id == selectedAnnotationId }?.let {
                    drawPdfSelection(it, size.width, size.height)
                }
                if (draftPoints.size > 1) {
                    drawPdfAnnotation(
                        PdfAnnotation(
                            id = "draft",
                            page = pageIndex,
                            kind = when (selectedTool) {
                                AnnotationTool.HIGHLIGHT -> AnnotationKind.HIGHLIGHT
                                AnnotationTool.UNDERLINE -> AnnotationKind.UNDERLINE
                                else -> AnnotationKind.INK
                            },
                            points = draftPoints,
                            color = if (selectedTool == AnnotationTool.HIGHLIGHT) 0xFFFFD54F else 0xFFEF5B51,
                            strokeWidth = if (selectedTool == AnnotationTool.HIGHLIGHT) 0.012f else 0.006f
                        ),
                        size.width,
                        size.height
                    )
                }
            }

            pageAnnotations.filter { it.kind == AnnotationKind.TEXT }.forEach { note ->
                Text(
                    text = note.text,
                    color = Color(note.color),
                    fontSize = 16.sp,
                    modifier = Modifier
                        .padding(start = with(density) { (note.x * widthPx).toDp() }, top = with(density) { (note.y * heightPx - 20f).coerceAtLeast(0f).toDp() })
                        .background(Color(0xFFFFF7D6).copy(alpha = 0.88f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }

            val gestureModifier = Modifier.pointerInput(selectedTool, pageIndex, annotations) {
                if (selectedTool == AnnotationTool.TEXT) {
                    detectTapGestures { offset ->
                        val point = PdfPoint(
                            x = (offset.x / size.width).coerceIn(0f, 1f),
                            y = (offset.y / size.height).coerceIn(0f, 1f)
                        )
                        val hit = hitTestAnnotation(pageAnnotations, point)
                        if (hit?.kind == AnnotationKind.TEXT) onEditText(hit) else onTextRequested(point)
                    }
                } else if (selectedTool == AnnotationTool.SELECT) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = PdfPoint(
                            x = (down.position.x / size.width).coerceIn(0f, 1f),
                            y = (down.position.y / size.height).coerceIn(0f, 1f)
                        )
                        val selected = hitTestAnnotation(pageAnnotations, start)
                        var lastPosition = down.position
                        var moved = false
                        onAnnotationSelected(selected?.id)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (change.changedToUp()) {
                                if (selected != null && moved) {
                                    val end = PdfPoint(
                                        x = (change.position.x / size.width).coerceIn(0f, 1f),
                                        y = (change.position.y / size.height).coerceIn(0f, 1f)
                                    )
                                    onMoveAnnotation(
                                        selected.id,
                                        end.x - start.x,
                                        end.y - start.y
                                    )
                                }
                                break
                            }
                            if (!change.pressed) break
                            if (change.position != lastPosition) {
                                moved = true
                                lastPosition = change.position
                                change.consume()
                            }
                        }
                    }
                } else {
                    detectDragGestures(
                        onDragStart = { offset ->
                            draftPoints = listOf(
                                PdfPoint(
                                    x = (offset.x / size.width).coerceIn(0f, 1f),
                                    y = (offset.y / size.height).coerceIn(0f, 1f)
                                )
                            )
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            draftPoints = draftPoints + PdfPoint(
                                x = (change.position.x / size.width).coerceIn(0f, 1f),
                                y = (change.position.y / size.height).coerceIn(0f, 1f)
                            )
                        },
                        onDragEnd = {
                            if (draftPoints.size > 1) onAddStroke(selectedTool, draftPoints)
                            draftPoints = emptyList()
                        },
                        onDragCancel = { draftPoints = emptyList() }
                    )
                }
            }
            Box(modifier = gestureModifier.fillMaxSize())
            }
        }
    }
}

private fun hitTestAnnotation(
    annotations: List<PdfAnnotation>,
    point: PdfPoint
): PdfAnnotation? = annotations.asReversed().firstOrNull { annotation ->
    if (annotation.kind == AnnotationKind.TEXT) {
        kotlin.math.abs(annotation.x - point.x) < 0.18f &&
            kotlin.math.abs(annotation.y - point.y) < 0.08f
    } else {
        annotation.points.any {
            kotlin.math.abs(it.x - point.x) < 0.04f &&
                kotlin.math.abs(it.y - point.y) < 0.04f
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPdfAnnotation(
    annotation: PdfAnnotation,
    width: Float,
    height: Float
) {
    if (annotation.kind == AnnotationKind.TEXT || annotation.points.isEmpty()) return
    val path = Path()
    annotation.points.forEachIndexed { index, point ->
        val offset = Offset(point.x * width, point.y * height)
        if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
    }
    val color = Color(annotation.color).copy(
        alpha = if (annotation.kind == AnnotationKind.HIGHLIGHT) 0.32f else 1f
    )
    val stroke = if (annotation.kind == AnnotationKind.HIGHLIGHT) {
        annotation.strokeWidth * minOf(width, height) * 3f
    } else {
        annotation.strokeWidth * minOf(width, height)
    }
    if (annotation.kind == AnnotationKind.UNDERLINE && annotation.points.size >= 2) {
        val first = annotation.points.first()
        val last = annotation.points.last()
        drawLine(
            color = color,
            start = Offset(first.x * width, first.y * height),
            end = Offset(last.x * width, last.y * height),
            strokeWidth = stroke
        )
    } else {
        drawPath(path, color = color, style = Stroke(width = stroke))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPdfSelection(
    annotation: PdfAnnotation,
    width: Float,
    height: Float
) {
    val color = Color(0xFFB94E3B)
    if (annotation.kind == AnnotationKind.TEXT) {
        drawRect(
            color = color,
            topLeft = Offset(annotation.x * width, (annotation.y - 0.05f).coerceAtLeast(0f) * height),
            size = androidx.compose.ui.geometry.Size(
                width = (annotation.text.length.coerceAtLeast(4) * 0.025f * width).coerceAtMost(width * 0.9f),
                height = 0.08f * height
            ),
            style = Stroke(width = 2f)
        )
        return
    }
    if (annotation.points.isEmpty()) return
    val minX = annotation.points.minOf { it.x } * width
    val maxX = annotation.points.maxOf { it.x } * width
    val minY = annotation.points.minOf { it.y } * height
    val maxY = annotation.points.maxOf { it.y } * height
    drawRect(
        color = color,
        topLeft = Offset(minX - 8f, minY - 8f),
        size = androidx.compose.ui.geometry.Size(
            width = (maxX - minX + 16f).coerceAtLeast(12f),
            height = (maxY - minY + 16f).coerceAtLeast(12f)
        ),
        style = Stroke(width = 2f)
    )
}
