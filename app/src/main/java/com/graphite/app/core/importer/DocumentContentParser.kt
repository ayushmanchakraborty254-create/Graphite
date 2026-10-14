package com.graphite.app.core.importer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.core.content.FileProvider
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

data class SlideContent(
    val slideNumber: Int,
    val title: String,
    val bullets: List<String>,
    val fullText: String
)

data class ParsedDocument(
    val title: String,
    val pageOrSlideCount: Int,
    val extractedText: String,
    val paragraphs: List<String> = emptyList(),
    val slides: List<SlideContent> = emptyList()
)

object DocumentContentParser {

    /**
     * Parses document bytes according to file extension or MIME type,
     * extracting structured text, paragraphs, slides, and page/slide counts.
     */
    fun parse(bytes: ByteArray, fileName: String, mimeType: String? = null): ParsedDocument {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val baseTitle = if (fileName.contains('.')) fileName.substringBeforeLast('.') else fileName

        return when {
            ext in listOf("docx", "doc") || mimeType?.contains("wordprocessingml") == true -> {
                parseDocx(bytes, baseTitle)
            }
            ext in listOf("pptx", "ppt") || mimeType?.contains("presentationml") == true -> {
                parsePptx(bytes, baseTitle)
            }
            ext in listOf("xlsx", "xls") || mimeType?.contains("spreadsheetml") == true -> {
                parseXlsx(bytes, baseTitle)
            }
            ext == "pdf" || mimeType?.contains("pdf") == true -> {
                parsePdf(bytes, baseTitle)
            }
            else -> {
                parsePlainText(bytes, baseTitle)
            }
        }
    }

    /**
     * Parses a Microsoft Word (.docx) package, extracting paragraphs and headings from word/document.xml.
     */
    fun parseDocx(bytes: ByteArray, baseTitle: String): ParsedDocument {
        try {
            val paragraphs = mutableListOf<String>()
            val zip = ZipInputStream(ByteArrayInputStream(bytes))
            var entry = zip.nextEntry
            while (entry != null) {
                val cleanName = entry.name.replace('\\', '/')
                if (cleanName.endsWith("word/document.xml", ignoreCase = true) ||
                    cleanName.endsWith("word/document2.xml", ignoreCase = true)
                ) {
                    val xmlContent = zip.readBytes().toString(Charsets.UTF_8)
                    paragraphs.addAll(extractDocxParagraphs(xmlContent))
                }
                entry = zip.nextEntry
            }
            zip.close()

            if (paragraphs.isNotEmpty()) {
                val fullText = paragraphs.joinToString("\n\n")
                val wordCount = fullText.split(Regex("\\s+")).filter { it.isNotBlank() }.size
                val estimatedPages = maxOf(1, (wordCount / 250))
                return ParsedDocument(
                    title = baseTitle,
                    pageOrSlideCount = estimatedPages,
                    extractedText = fullText,
                    paragraphs = paragraphs
                )
            }
        } catch (_: Exception) {
            // Fallback to binary extraction or plain text
        }

        // Handle binary legacy .doc format (Word 97-2003)
        val binaryLines = extractBinaryDocText(bytes)
        if (binaryLines.isNotEmpty()) {
            val fullText = binaryLines.joinToString("\n\n")
            return ParsedDocument(
                title = baseTitle,
                pageOrSlideCount = maxOf(1, binaryLines.size / 20),
                extractedText = fullText,
                paragraphs = binaryLines
            )
        }

        val fallbackText = parsePlainText(bytes, baseTitle)
        return fallbackText.copy(title = baseTitle)
    }

    private fun extractDocxParagraphs(xmlContent: String): List<String> {
        val paragraphs = mutableListOf<String>()

        // Match all paragraph elements <w:p>...</w:p> regardless of namespace prefix or attributes
        val pPattern = Regex("<(?:[a-zA-Z0-9_-]+:)?p(?:\\s[^>]*)?>(.*?)</(?:[a-zA-Z0-9_-]+:)?p>", RegexOption.DOT_MATCHES_ALL)
        val tPattern = Regex("<(?:[a-zA-Z0-9_-]+:)?t(?:\\s[^>]*)?>(.*?)</(?:[a-zA-Z0-9_-]+:)?t>", RegexOption.DOT_MATCHES_ALL)

        for (pMatch in pPattern.findAll(xmlContent)) {
            val pBody = pMatch.groupValues[1]
            val paraBuilder = StringBuilder()
            for (tMatch in tPattern.findAll(pBody)) {
                val text = tMatch.groupValues[1]
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                paraBuilder.append(text)
            }
            val cleanPara = paraBuilder.toString().trim()
            if (cleanPara.isNotEmpty()) {
                paragraphs.add(cleanPara)
            }
        }

        if (paragraphs.isNotEmpty()) return paragraphs

        // Fallback: extract all text runs <w:t> directly
        val allTMatches = tPattern.findAll(xmlContent).map { match ->
            match.groupValues[1]
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
        }.filter { it.isNotBlank() }.toList()

        return allTMatches
    }

    private fun extractBinaryDocText(bytes: ByteArray): List<String> {
        val textBuilder = StringBuilder()
        var i = 0
        while (i < bytes.size - 1) {
            val b1 = bytes[i]
            val b2 = bytes[i + 1]
            if (b2 == 0.toByte() && b1 in 32..126) {
                textBuilder.append(b1.toInt().toChar())
                i += 2
            } else if (b1 in 32..126) {
                textBuilder.append(b1.toInt().toChar())
                i++
            } else {
                if (textBuilder.isNotEmpty() && textBuilder.last() != '\n') {
                    textBuilder.append('\n')
                }
                i++
            }
        }
        return textBuilder.split('\n')
            .map { it.trim() }
            .filter { it.length > 3 && !it.startsWith("WordDocument") && !it.startsWith("Root Entry") && !it.startsWith("Data") }
    }

    /**
     * Parses a PowerPoint (.pptx) presentation, extracting individual slide titles and bullets.
     */
    fun parsePptx(bytes: ByteArray, baseTitle: String): ParsedDocument {
        try {
            val slideEntries = mutableMapOf<Int, String>()
            val zip = ZipInputStream(ByteArrayInputStream(bytes))
            var entry = zip.nextEntry
            while (entry != null) {
                val cleanName = entry.name.replace('\\', '/')
                val match = Regex(".*ppt/slides/slide(\\d+)\\.xml", RegexOption.IGNORE_CASE).matchEntire(cleanName)
                if (match != null) {
                    val slideNum = match.groupValues[1].toIntOrNull() ?: (slideEntries.size + 1)
                    slideEntries[slideNum] = zip.readBytes().toString(Charsets.UTF_8)
                }
                entry = zip.nextEntry
            }
            zip.close()

            if (slideEntries.isNotEmpty()) {
                val slides = mutableListOf<SlideContent>()
                val sortedKeys = slideEntries.keys.sorted()

                for (num in sortedKeys) {
                    val xml = slideEntries[num] ?: continue
                    val slideTexts = extractPptxTexts(xml)
                    val title = slideTexts.firstOrNull() ?: "Slide $num"
                    val bullets = if (slideTexts.size > 1) slideTexts.drop(1) else emptyList()
                    val fullText = buildString {
                        appendLine("Slide $num: $title")
                        bullets.forEach { appendLine("• $it") }
                    }.trim()
                    slides.add(SlideContent(slideNumber = num, title = title, bullets = bullets, fullText = fullText))
                }

                val fullPresentationText = slides.joinToString("\n\n") { it.fullText }
                return ParsedDocument(
                    title = baseTitle,
                    pageOrSlideCount = slides.size,
                    extractedText = fullPresentationText,
                    slides = slides
                )
            }
        } catch (_: Exception) {
            // Fallback
        }
        return parsePlainText(bytes, baseTitle).copy(title = baseTitle)
    }

    private fun extractPptxTexts(xmlContent: String): List<String> {
        val texts = mutableListOf<String>()
        val pPattern = Regex("<(?:[a-zA-Z0-9_-]+:)?p(?:\\s[^>]*)?>(.*?)</(?:[a-zA-Z0-9_-]+:)?p>", RegexOption.DOT_MATCHES_ALL)
        val tPattern = Regex("<(?:[a-zA-Z0-9_-]+:)?t(?:\\s[^>]*)?>(.*?)</(?:[a-zA-Z0-9_-]+:)?t>", RegexOption.DOT_MATCHES_ALL)

        for (pMatch in pPattern.findAll(xmlContent)) {
            val pBody = pMatch.groupValues[1]
            val builder = StringBuilder()
            for (tMatch in tPattern.findAll(pBody)) {
                val text = tMatch.groupValues[1]
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                builder.append(text)
            }
            val clean = builder.toString().trim()
            if (clean.isNotEmpty()) {
                texts.add(clean)
            }
        }

        if (texts.isEmpty()) {
            for (tMatch in tPattern.findAll(xmlContent)) {
                val clean = tMatch.groupValues[1]
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                    .trim()
                if (clean.isNotEmpty()) texts.add(clean)
            }
        }

        return texts
    }

    /**
     * Parses an Excel spreadsheet (.xlsx), extracting row and cell data.
     */
    fun parseXlsx(bytes: ByteArray, baseTitle: String): ParsedDocument {
        try {
            val sharedStrings = mutableListOf<String>()
            var sheetXml: String? = null

            val zip = ZipInputStream(ByteArrayInputStream(bytes))
            var entry = zip.nextEntry
            while (entry != null) {
                when (entry.name) {
                    "xl/sharedStrings.xml" -> {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        val tRegex = Regex("<t.*?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                        for (m in tRegex.findAll(xml)) {
                            sharedStrings.add(m.groupValues[1])
                        }
                    }
                    "xl/worksheets/sheet1.xml" -> {
                        sheetXml = zip.readBytes().toString(Charsets.UTF_8)
                    }
                }
                entry = zip.nextEntry
            }
            zip.close()

            if (sheetXml != null) {
                val rows = mutableListOf<String>()
                val rowRegex = Regex("<row.*?>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
                val cRegex = Regex("<c.*?t=\"(.*?)\".*?<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
                val vRegex = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)

                for (rowMatch in rowRegex.findAll(sheetXml)) {
                    val cells = mutableListOf<String>()
                    for (cMatch in cRegex.findAll(rowMatch.value)) {
                        val type = cMatch.groupValues[1]
                        val rawVal = cMatch.groupValues[2]
                        if (type == "s") {
                            val idx = rawVal.toIntOrNull() ?: 0
                            cells.add(sharedStrings.getOrElse(idx) { rawVal })
                        } else {
                            cells.add(rawVal)
                        }
                    }
                    if (cells.isEmpty()) {
                        for (vMatch in vRegex.findAll(rowMatch.value)) {
                            cells.add(vMatch.groupValues[1])
                        }
                    }
                    if (cells.isNotEmpty()) {
                        rows.add(cells.joinToString("  |  "))
                    }
                }

                if (rows.isNotEmpty()) {
                    val formatted = rows.joinToString("\n")
                    return ParsedDocument(
                        title = baseTitle,
                        pageOrSlideCount = maxOf(1, rows.size / 30),
                        extractedText = formatted,
                        paragraphs = rows
                    )
                }
            }
        } catch (_: Exception) {}
        return parsePlainText(bytes, baseTitle).copy(title = baseTitle)
    }

    private fun parsePdf(bytes: ByteArray, baseTitle: String): ParsedDocument {
        var pageCount = 1
        try {
            val temp = File.createTempFile("preview_", ".pdf")
            temp.writeBytes(bytes)
            val pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            pageCount = renderer.pageCount
            renderer.close()
            pfd.close()
            temp.delete()
        } catch (_: Exception) {}

        return ParsedDocument(
            title = baseTitle,
            pageOrSlideCount = pageCount,
            extractedText = "PDF Document: $baseTitle\nTotal Pages: $pageCount"
        )
    }

    private fun parsePlainText(bytes: ByteArray, baseTitle: String): ParsedDocument {
        val text = try {
            bytes.toString(Charsets.UTF_8).ifBlank { "Document Content ($baseTitle)" }
        } catch (_: Exception) {
            "Document: $baseTitle"
        }
        val lines = text.lines()
        val count = maxOf(1, lines.size / 40)
        return ParsedDocument(
            title = baseTitle,
            pageOrSlideCount = count,
            extractedText = text,
            paragraphs = lines
        )
    }

    /**
     * Opens a document with an external application (Microsoft Office, Google Docs/Slides, WPS, etc.)
     * via Android FileProvider.
     */
    fun openWithExternalApp(context: Context, file: File, mimeType: String? = null) {
        try {
            if (!file.exists()) {
                Toast.makeText(context, "File does not exist on device", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val resolvedMime = mimeType ?: when (file.extension.lowercase()) {
                "pdf" -> "application/pdf"
                "docx", "doc" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "pptx", "ppt" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                "xlsx", "xls" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                else -> "*/*"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open with..."))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app available on device to open ${file.extension.uppercase()} files.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
