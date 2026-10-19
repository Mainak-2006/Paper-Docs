package com.company.paperdocs.io

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Xml
import androidx.core.graphics.withClip
import com.company.paperdocs.data.Document
import com.company.paperdocs.data.RichContent
import com.company.paperdocs.data.TextMark
import com.company.paperdocs.editor.RichTextCodec
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class ExportFormat(val label: String, val extension: String, val mime: String, val description: String) {
    PDF("PDF document", "pdf", "application/pdf", "Ready to read, print, or send"),
    DOCX("Word document", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Headings, lists, tables, links, and formatting"),
    HTML("Web document", "html", "text/html", "Formatted text for the web"),
    TXT("Plain text", "txt", "text/plain", "Just your words, without formatting"),
}

object DocumentFiles {
    fun filename(title: String, format: ExportFormat): String = title.replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_").trim().take(100).ifEmpty { "Document" } + "." + format.extension

    fun write(document: Document, format: ExportFormat, output: OutputStream) {
        when (format) {
            ExportFormat.TXT -> output.write(document.content.text.toByteArray(Charsets.UTF_8))
            ExportFormat.HTML -> output.write(html(document).toByteArray(Charsets.UTF_8))
            ExportFormat.DOCX -> DocxCodec.write(document.content, output)
            ExportFormat.PDF -> writePdf(document, output)
        }
    }

    /** Decodes already-downloaded bytes using the same rules as the file picker import. */
    fun decode(bytes: ByteArray, name: String, mime: String): RichContent {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when {
            extension == "docx" || mime == ExportFormat.DOCX.mime -> DocxCodec.read(ByteArrayInputStream(bytes))
            extension in setOf("html", "htm") || mime == ExportFormat.HTML.mime ->
                RichTextCodec.fromSpanned(Html.fromHtml(bytes.toString(Charsets.UTF_8), Html.FROM_HTML_MODE_COMPACT))
            extension == "txt" || mime == ExportFormat.TXT.mime -> {
                require(bytes.none { it == 0.toByte() }) { "Please choose a UTF-8 text file" }
                RichContent(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
            }
            else -> error("Unsupported file type")
        }.also { require(it.text.length <= 1_000_000) { "This document exceeds the 1 million character limit" } }
    }

    fun import(context: Context, uri: Uri): Pair<String, RichContent> {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Imported document"
        val mime = context.contentResolver.getType(uri).orEmpty()
        val bytes = context.contentResolver.openInputStream(uri)?.use { readBounded(it, 16 * 1024 * 1024) } ?: error("Could not open the selected file")
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val content = when {
            extension == "docx" || mime == ExportFormat.DOCX.mime -> DocxCodec.read(ByteArrayInputStream(bytes))
            extension in setOf("html", "htm") || mime == "text/html" -> RichTextCodec.fromSpanned(Html.fromHtml(bytes.toString(Charsets.UTF_8), Html.FROM_HTML_MODE_COMPACT))
            extension == "txt" || mime == "text/plain" -> {
                require(bytes.none { it == 0.toByte() }) { "Please choose a UTF-8 text file" }
                RichContent(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
            }
            else -> error("Choose a .txt, .html, or .docx file")
        }
        require(content.text.length <= 1_000_000) { "This document exceeds the 1 million character import limit" }
        return name.substringBeforeLast('.', name).ifBlank { "Imported document" } to content
    }

    private fun escape(value: String): String = escapeXml(value)

    /** Escapes text for XML/HTML output, dropping control characters that cannot be represented. */
    internal fun escapeXml(value: String): String = buildString {
        value.forEach { when (it) {
            '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
            '"' -> append("&quot;"); '\'' -> append("&apos;")
            else -> if (it == '\n' || it == '\r' || it == '\t' || it.code >= 32) append(it)
        } }
    }

    /** Line ranges between [from] and [to], each ending just before its newline, as `start until end`. */
    internal fun paragraphRanges(text: String, from: Int, to: Int): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var start = from
        for (index in from until to) {
            if (text[index] == '\n') { ranges += start until index; start = index + 1 }
        }
        ranges += start until to
        return ranges
    }

    internal fun runsIn(content: RichContent, start: Int, end: Int, block: (String, List<TextMark>) -> Unit) {
        runs(content, start, end, block)
    }

    internal fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            require(output.size() + read <= limit) { "The selected file is too large" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun color(value: String): String = "%06X".format((value.toIntOrNull() ?: Color.BLACK) and 0xFFFFFF)
    private fun paragraphs(text: String): List<IntRange> = paragraphRanges(text, 0, text.length)
    private fun runs(content: RichContent, start: Int, end: Int, block: (String, List<TextMark>) -> Unit) {
        val boundaries = sortedSetOf(start, end)
        content.marks.filter { it.start < end && it.end > start }.forEach { boundaries += it.start.coerceIn(start, end); boundaries += it.end.coerceIn(start, end) }
        boundaries.toList().zipWithNext().forEach { (a, b) -> block(content.text.substring(a, b), content.marks.filter { it.start <= a && it.end >= b }) }
    }

    fun html(document: Document): String = buildString {
        append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>${escape(document.title)}</title>")
        append("<style>body{max-width:760px;margin:48px auto;padding:0 24px;font:16px/1.65 sans-serif;color:#202124}p{white-space:pre-wrap;min-height:1em;margin:0}</style></head><body>")
        paragraphs(document.content.text).forEach { range ->
            val end = range.last + 1
            val alignment = document.content.marks.lastOrNull { it.kind == "align" && it.start <= range.first && it.end >= end }?.value ?: "left"
            append("<p style=\"text-align:${if (alignment in setOf("left", "center", "right")) alignment else "left"}\">")
            runs(document.content, range.first, end) { text, marks ->
                val link = marks.firstOrNull { it.kind == "link" }
                if (link != null) {
                    append("<a href=\"${escape(link.value)}\">")
                }
                val css = marks.mapNotNull { mark -> when (mark.kind) {
                    "bold" -> "font-weight:bold"
                    "italic" -> "font-style:italic"
                    "size" -> "font-size:${(mark.value.toIntOrNull() ?: 16).coerceIn(8, 72)}px"
                    "font" -> "font-family:${escape(mark.value)}"
                    "color" -> "color:#${color(mark.value)}"
                    "highlight" -> "background-color:#${color(mark.value)}"
                    "link" -> "color:#1155cc"
                    else -> null
                } }.toMutableList()
                val decoration = buildList { if (marks.any { it.kind == "underline" }) add("underline"); if (marks.any { it.kind == "strike" }) add("line-through"); if (link != null) add("underline") }
                if (decoration.isNotEmpty()) css += "text-decoration:${decoration.joinToString(" ")}"
                append("<span style=\"${css.joinToString(";")}\">${escape(text)}</span>")
                if (link != null) {
                    append("</a>")
                }
            }
            if (range.isEmpty()) append("<br>")
            append("</p>\n")
        }
        append("</body></html>")
    }


    private fun writePdf(document: Document, output: OutputStream) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 16f; color = Color.rgb(32, 33, 36); density = 1f }
        val content = RichTextCodec.toSpannable(document.content)
        val layout = StaticLayout.Builder.obtain(content, 0, content.length, paint, 483)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f, 1.1f).setIncludePad(false).build()
        val pdf = PdfDocument()
        try {
            var startLine = 0
            var pageNumber = 1
            do {
                val top = layout.getLineTop(startLine)
                var endLine = startLine + 1
                while (endLine < layout.lineCount && layout.getLineBottom(endLine) - top <= 710) endLine++
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
                val canvas = page.canvas
                canvas.withClip(56f, 56f, 539f, 56f + layout.getLineBottom(endLine - 1) - top) {
                    translate(56f, 56f - top)
                    layout.draw(this)
                }
                val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 9f }
                canvas.drawText("$pageNumber", 295f, 808f, footer)
                pdf.finishPage(page)
                startLine = endLine; pageNumber++
            } while (startLine < layout.lineCount)
            pdf.writeTo(output)
        } finally { pdf.close() }
    }
}
