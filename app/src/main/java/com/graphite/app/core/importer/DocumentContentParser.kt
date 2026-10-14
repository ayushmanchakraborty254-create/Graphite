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
                if (entry.name == "word/document.xml") {
                    val xmlContent = zip.readBytes().toString(Charsets.UTF_8)
                    paragraphs.addAll(extractDocxParagraphs(xmlContent))
                    break
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
            // Fallback if ZIP parsing encounters non-standard structure
        }
        val fallbackText = parsePlainText(bytes, baseTitle)
        return fallbackText.copy(title = baseTitle)
    }

    private fun extractDocxParagraphs(xmlContent: String): List<String> {
        val paragraphs = mutableListOf<String>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(xmlContent.reader())

            var eventType = parser.eventType
            val currentParagraph = StringBuilder()
            var inParagraph = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name?.substringAfterLast(':') ?: ""
                        if (name == "p") {
                            inParagraph = true
                            currentParagraph.clear()
                        } else if (name == "t" && inParagraph) {
                            val text = parser.nextText()
                            if (!text.isNullOrEmpty()) {
                                currentParagraph.append(text)
                            }
                        } else if (name == "tab" && inParagraph) {
                            currentParagraph.append("\t")
                        } else if (name == "br" && inParagraph) {
                            currentParagraph.append("\n")
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name?.substringAfterLast(':') ?: ""
                        if (name == "p") {
                            val text = currentParagraph.toString().trim()
                            if (text.isNotEmpty()) {
                                paragraphs.add(text)
                            }
                            inParagraph = false
                            currentParagraph.clear()
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            // Robust regex fallback if XML parser fails on malformed input
            val pRegex = Regex("<w:p[ >](.*?)</w:p>", RegexOption.DOT_MATCHES_ALL)
            val tRegex = Regex("<w:t[ >](.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
            for (pMatch in pRegex.findAll(xmlContent)) {
                val pText = StringBuilder()
                for (tMatch in tRegex.findAll(pMatch.value)) {
                    pText.append(tMatch.groupValues[1])
                }
                val clean = pText.toString().trim()
                if (clean.isNotEmpty()) {
                    paragraphs.add(clean)
                }
            }
        }
        return paragraphs
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
                val name = entry.name
                val match = Regex("ppt/slides/slide(\\d+)\\.xml").matchEntire(name)
                if (match != null) {
                    val slideNum = match.groupValues[1].toIntOrNull() ?: 1
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
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(xmlContent.reader())

            var eventType = parser.eventType
            val currentPara = StringBuilder()
            var inPara = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name?.substringAfterLast(':') ?: ""
                        if (name == "p") {
                            inPara = true
                            currentPara.clear()
                        } else if (name == "t" && inPara) {
                            val t = parser.nextText()
                            if (!t.isNullOrEmpty()) {
                                currentPara.append(t)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name?.substringAfterLast(':') ?: ""
                        if (name == "p") {
                            val line = currentPara.toString().trim()
                            if (line.isNotEmpty()) {
                                texts.add(line)
                            }
                            inPara = false
                            currentPara.clear()
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            val tRegex = Regex("<a:t>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL)
            for (match in tRegex.findAll(xmlContent)) {
                val clean = match.groupValues[1].trim()
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
