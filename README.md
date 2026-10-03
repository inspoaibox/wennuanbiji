# 温暖笔记

温暖笔记是一个开源 Android 文档阅读与标注应用。当前版本支持本地 PDF、DOCX、DOC、DOCM 和 DOTX 导入；Word 文件会在设备上转换成内部分页 PDF，然后使用统一的阅读和标注流程。

## 打开项目

使用 Android Studio 打开本目录，等待 Gradle 同步后运行 `app` 配置即可。工程使用：

- Kotlin 2.0.21
- Jetpack Compose
- Android Gradle Plugin 8.7.3
- `minSdk 26`，`targetSdk 35`
- Android 系统 `PdfRenderer`，不需要额外的闭源 PDF SDK
- Apache POI 5.5.1，用于离线提取 Word 文字、表格和段落图片

项目包含 Gradle Wrapper。Android Studio 安装 SDK Platform 35 和 Android SDK Build-Tools 后，可以使用 `gradlew.bat assembleDebug` 构建。

## 使用方式

1. 点击“导入文档”，从系统文件选择器选择 PDF 或 Word 文件。
2. 在工具栏切换手写、荧光笔、下划线、文字或橡皮擦。
3. 编辑内容会自动保存在应用私有目录中，标注以 `.warmnotes.json` 保存。
4. 点击“导出”，选择位置生成一个带标注的 PDF。

也可以在文件管理器中选择 PDF 或 Word 文件，使用“温暖笔记”打开。

## 目录

- `app/src/main/java/com/wennuanbiji/`：Android 应用代码
- `logo.svg` / `logo.png`：根目录品牌 Logo，PNG 版本用于应用图标，含完整安全边距
- `REQUIREMENTS.md`：产品需求和开源项目评估

## 后续方向

PDF 和 Word 导入后的导出都会生成带标注的扁平化 PDF。Word 转换会保留常规段落、表格和段落内图片；复杂的分页、页眉页脚、文本框、浮动对象和部分特殊字体会被规范化。

全文搜索、OCR、原生可编辑 PDF Annotation、Word 原格式回写和云同步暂列入下一阶段。
