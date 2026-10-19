package com.company.paperdocs.io

import android.graphics.Color
import android.util.Log
import android.util.Xml
import com.company.paperdocs.data.RichContent
import com.company.paperdocs.data.TextMark
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Word (.docx) reading and writing.
 *
 * Round-trips paragraph styles (Title, Heading 1–3), bulleted and numbered lists, tables,
 * hyperlinks, alignment, and character formatting (bold, italic, underline, strikethrough, size,
 * font, colour, highlight). Untrusted archives are bounded and XML is never allowed to resolve
 * external entities.
 */
object DocxCodec {
    private const val NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val MAX_TEXT = 1_000_000
    private const val TAG = "DocxCodec"
    private const val BULLET_ID = 1
    private const val NUMBER_ID = 2

    private val listPrefix = Regex("^\\s*(?:• |☐ |☑ |\\d+[.)] )")
    private val headingSizes = mapOf("title" to 32, "heading1" to 28, "heading2" to 24, "heading3" to 20)

    // ------------------------------------------------------------------ writing

    fun write(content: RichContent, output: OutputStream) {
        val links = content.marks.filter { it.kind == "link" && it.value.isNotBlank() }
        val document = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<w:document xmlns:w=\"$NS\" xmlns:r=\"$NS_R\"><w:body>")
            writeContent(content, this, links.map { it.value }.distinct())
            append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
            append("<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr></w:body></w:document>")
        }
        val relationships = if (links.isEmpty()) emptyMap() else links.map { it.value }.distinct().withIndex().associate { (index, url) -> "rIdLink$index" to url }

        val zip = ZipOutputStream(output)
        fun entry(name: String, value: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(value.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        entry("[Content_Types].xml", contentTypes(links.isNotEmpty()))
        entry("_rels/.rels", packageRelationships())
        entry("word/_rels/document.xml.rels", documentRelationships(relationships))
        entry("word/styles.xml", STYLES_XML)
        if (links.isNotEmpty()) entry("word/numbering.xml", NUMBERING_XML)
        entry("word/document.xml", document)
        zip.finish()
        zip.flush()
    }

    /** Writes paragraphs, converting list-marker paragraphs into real Word numbering. */
    private fun writeContent(content: RichContent, out: StringBuilder, linkTargets: List<String>) {
        DocumentFiles.paragraphRanges(content.text, 0, content.text.length).forEach { range ->
            val start = range.first
            val end = range.last + 1
            val raw = content.text.substring(start, end)
            val prefix = listPrefix.find(raw)
            val bodyStart = if (prefix != null && !isHeading(content, start, end)) start + prefix.value.length else start
            val numbered = prefix?.value?.trimStart()?.firstOrNull()?.isDigit() == true

            out.append("<w:p><w:pPr>")
            val heading = headingSize(content, start, end)
            when {
                prefix != null && !isHeading(content, start, end) ->
                    out.append("<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"${if (numbered) NUMBER_ID else BULLET_ID}\"/></w:numPr>")
                heading != null -> out.append("<w:pStyle w:val=\"${styleName(heading)}\"/>")
            }
            val alignment = content.marks.lastOrNull { it.kind == "align" && it.start <= start && it.end >= end }?.value ?: "left"
            if (alignment != "left") out.append("<w:jc w:val=\"$alignment\"/>")
            out.append("</w:pPr>")

            DocumentFiles.runsIn(content, bodyStart, end) { run, marks ->
                val link = marks.firstOrNull { it.kind == "link" }
                if (link != null) {
                    val id = "rIdLink${linkTargets.indexOf(link.value)}"
                    out.append("<w:hyperlink r:id=\"$id\">")
                }
                out.append("<w:r><w:rPr>")
                marks.forEach { mark ->
                    when (mark.kind) {
                        "bold" -> out.append("<w:b/>")
                        "italic" -> out.append("<w:i/>")
                        "underline" -> out.append("<w:u w:val=\"single\"/>")
                        "strike" -> out.append("<w:strike/>")
                        "size" -> mark.value.toIntOrNull()?.let { out.append("<w:sz w:val=\"${it.coerceIn(8, 72) * 2}\"/>") }
                        "font" -> out.append("<w:rFonts w:ascii=\"${esc(mark.value)}\" w:hAnsi=\"${esc(mark.value)}\"/>")
                        "color" -> out.append("<w:color w:val=\"${hex(mark.value)}\"/>")
                        "highlight" -> out.append("<w:shd w:val=\"clear\" w:fill=\"${hex(mark.value)}\"/>")
                        "link" -> out.append("<w:rStyle w:val=\"Hyperlink\"/><w:color w:val=\"1155CC\"/><w:u w:val=\"single\"/>")
                    }
                }
                out.append("</w:rPr><w:t xml:space=\"preserve\">${esc(run)}</w:t></w:r>")
                if (link != null) out.append("</w:hyperlink>")
            }
            out.append("</w:p>")
        }
    }

    private fun isHeading(content: RichContent, start: Int, end: Int) = headingSize(content, start, end) != null
    private fun headingSize(content: RichContent, start: Int, end: Int): Int? =
        content.marks.firstOrNull { it.kind == "size" && it.start <= start && it.end >= end }
            ?.value?.toIntOrNull()?.takeIf { it >= 20 }

    private fun styleName(size: Int) = when {
        size >= 30 -> "Title"
        size >= 26 -> "Heading1"
        size >= 22 -> "Heading2"
        else -> "Heading3"
    }

    // ------------------------------------------------------------- package parts

    private fun contentTypes(withNumbering: Boolean) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>")
        append("<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>")
        if (withNumbering) append("<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>")
        append("</Types>")
    }

    private fun packageRelationships() =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"$NS_R/officeDocument\" Target=\"word/document.xml\"/></Relationships>"

    private fun documentRelationships(links: Map<String, String>) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        append("<Relationship Id=\"rIdStyles\" Type=\"$NS_R/styles\" Target=\"styles.xml\"/>")
        if (links.isNotEmpty()) {
            append("<Relationship Id=\"rIdNumbering\" Type=\"$NS_R/numbering\" Target=\"numbering.xml\"/>")
            links.forEach { (id, url) ->
                append("<Relationship Id=\"$id\" Type=\"$NS_R/hyperlink\" Target=\"${esc(url)}\" TargetMode=\"External\"/>")
            }
        }
        append("</Relationships>")
    }

    private val STYLES_XML = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:styles xmlns:w=\"$NS\">")
        listOf("Title" to 32, "Heading1" to 28, "Heading2" to 24, "Heading3" to 20).forEach { (name, size) ->
            append("<w:style w:type=\"paragraph\" w:styleId=\"$name\"><w:name w:val=\"$name\"/>")
            append("<w:rPr><w:b/><w:sz w:val=\"${size * 2}\"/></w:rPr></w:style>")
        }
        append("<w:style w:type=\"character\" w:styleId=\"Hyperlink\"><w:name w:val=\"Hyperlink\"/>")
        append("<w:rPr><w:color w:val=\"1155CC\"/><w:u w:val=\"single\"/></w:rPr></w:style>")
        append("</w:styles>")
    }

    private val NUMBERING_XML =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:numbering xmlns:w=\"$NS\">" +
            "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"•\"/></w:lvl></w:abstractNum>" +
            "<w:abstractNum w:abstractNumId=\"1\"><w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/></w:lvl></w:abstractNum>" +
            "<w:num w:numId=\"$BULLET_ID\"><w:abstractNumId w:val=\"0\"/></w:num>" +
            "<w:num w:numId=\"$NUMBER_ID\"><w:abstractNumId w:val=\"1\"/></w:num>" +
            "</w:numbering>"

    // ------------------------------------------------------------------ reading

    fun read(input: InputStream): RichContent {
        val members = HashMap<String, ByteArray>()
        val zip = ZipInputStream(input)
        var scanned = 0
        var budget = 32 * 1024 * 1024
        while (true) {
            val entry = zip.nextEntry ?: break
            require(++scanned <= 2000) { "Too many entries in this Word document" }
            val name = entry.name
            if (!name.endsWith(".xml") && !name.endsWith(".rels")) { zip.closeEntry(); continue }
            val bytes = DocumentFiles.readBounded(zip, minOf(8 * 1024 * 1024, budget))
            budget -= bytes.size
            require(budget > 0) { "This Word document is too large" }
            members.putIfAbsent(name, bytes)
            if (members.size >= 12) break
        }
        val document = members["word/document.xml"] ?: error("This is not a supported Word document")
        return Reader(
            links = relationships(members["word/_rels/document.xml.rels"]),
            styleSizes = styleSizes(members["word/styles.xml"]),
            listStyles = listStyles(members["word/numbering.xml"]),
        ).parse(document)
    }

    /** Maps relationship ids to hyperlink targets. */
    private fun relationships(bytes: ByteArray?): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (bytes == null) return map
        walk(bytes) { parser, event ->
            if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = plainAttr(parser, "Id")
                val target = plainAttr(parser, "Target")
                if (id != null && target != null && target.startsWith("http")) map[id] = target
            }
        }
        return map
    }

    /** Reads an attribute that carries no namespace prefix, tolerating either empty form. */
    private fun plainAttr(parser: XmlPullParser, name: String): String? =
        parser.getAttributeValue(null, name) ?: parser.getAttributeValue("", name)

    /** Maps style ids to point sizes, so documents that style headings by name are understood. */
    private fun styleSizes(bytes: ByteArray?): Map<String, Int> {
        val sizes = mutableMapOf<String, Int>()
        if (bytes == null) return sizes
        var current: String? = null
        walk(bytes) { parser, event ->
            when {
                event == XmlPullParser.START_TAG && parser.name == "style" ->
                    current = parser.getAttributeValue(NS, "styleId") ?: parser.getAttributeValue(null, "styleId")
                event == XmlPullParser.START_TAG && parser.name == "sz" -> {
                    val style = current
                    val half = (parser.getAttributeValue(NS, "val") ?: parser.getAttributeValue(null, "val"))?.toIntOrNull()
                    if (style != null && half != null) sizes[style] = (half / 2).coerceIn(8, 72)
                }
                event == XmlPullParser.END_TAG && parser.name == "style" -> current = null
            }
        }
        return sizes
    }

    /** Maps numbering definition ids to true when they render as a numbered list. */
    private fun listStyles(bytes: ByteArray?): Map<String, Boolean> {
        val abstract = mutableMapOf<String, Boolean>()
        val instances = mutableMapOf<String, String>()
        if (bytes == null) return emptyMap()
        var currentAbstract: String? = null
        var currentInstance: String? = null
        var fmt = "bullet"
        walk(bytes) { parser, event ->
            when {
                event == XmlPullParser.START_TAG && parser.name == "abstractNum" ->
                    currentAbstract = parser.getAttributeValue(NS, "abstractNumId")
                event == XmlPullParser.END_TAG && parser.name == "abstractNum" -> {
                    currentAbstract?.let { abstract[it] = fmt != "bullet" }
                    currentAbstract = null
                }
                event == XmlPullParser.START_TAG && parser.name == "num" ->
                    currentInstance = parser.getAttributeValue(NS, "numId")
                event == XmlPullParser.END_TAG && parser.name == "num" -> {
                    currentInstance = null
                }
                event == XmlPullParser.START_TAG && parser.name == "abstractNumId" && currentInstance != null -> {
                    val ref = parser.getAttributeValue(NS, "val") ?: parser.getAttributeValue(null, "val")
                    if (ref != null) instances[currentInstance!!] = ref
                }
                event == XmlPullParser.START_TAG && parser.name == "numFmt" ->
                    fmt = parser.getAttributeValue(NS, "val") ?: parser.getAttributeValue(null, "val") ?: "bullet"
            }
        }
        return instances.mapNotNull { (id, ref) -> abstract[ref]?.let { id to it } }.toMap()
    }

    /**
     * Parses an auxiliary package part. A malformed optional part (styles, numbering,
     * relationships) must not fail the whole import, but it is reported rather than swallowed.
     */
    private inline fun walk(bytes: ByteArray, block: (XmlPullParser, Int) -> Unit) {
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                require(event != XmlPullParser.DOCDECL) { "XML document type declarations are not supported" }
                block(parser, event)
                event = parser.nextToken()
            }
        } catch (error: XmlPullParserException) {
            Log.w(TAG, "Ignoring an unreadable Word part", error)
        }
    }

    /** Rebuilds text and marks from WordprocessingML. */
    private class Reader(
        private val links: Map<String, String>,
        private val styleSizes: Map<String, Int>,
        private val listStyles: Map<String, Boolean>,
    ) {
        private val text = StringBuilder()
        private val marks = mutableListOf<TextMark>()
        private val runMarks = mutableMapOf<String, String>()
        private val paragraphMarks = mutableMapOf<String, String>()

        private var runStart = 0
        private var paragraphStart = 0
        private var alignment = "left"
        private var numbered: Boolean? = null
        private var counter = 0
        private var inText = false
        private var inRunProperties = false
        private var inTable = false
        // A hyperlink wraps its runs, so its target is tracked separately from run formatting.
        private var linkTarget: String? = null
        private var cellStarts = mutableListOf<Int>()
        private var rowStarts = mutableListOf<Int>()
        private var tables = mutableListOf<TableCodec.Block.Table>()

        fun parse(document: ByteArray): RichContent {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(ByteArrayInputStream(document), "UTF-8")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                require(event != XmlPullParser.DOCDECL) { "XML document type declarations are not supported" }
                when (event) {
                    XmlPullParser.START_TAG -> start(parser)
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> if (inText) text.append(parser.text)
                    XmlPullParser.ENTITY_REF -> {
                        require(parser.text != null) { "Unresolved XML entity" }
                        if (inText) text.append(parser.text)
                    }
                    XmlPullParser.END_TAG -> end(parser.name)
                }
                require(text.length <= MAX_TEXT) { "This Word document is too large" }
                event = parser.nextToken()
            }
            flushTable()
            if (text.isNotEmpty() && text.last() == '\n') text.deleteCharAt(text.length - 1)
            return RichContent(text.toString(), (marks + tables.flatMap { tableMarks(it) }).sortedWith(compareBy({ it.start }, { it.end }, { it.kind }, { it.value })))
        }

        private fun attr(parser: XmlPullParser, name: String) =
            parser.getAttributeValue(NS, name) ?: parser.getAttributeValue(null, name).orEmpty()

        /** `w:hyperlink` references its target through the relationships namespace. */
        private fun relationshipAttr(parser: XmlPullParser) =
            parser.getAttributeValue(NS_R, "id") ?: parser.getAttributeValue(null, "id")

        private fun start(parser: XmlPullParser) {
            when (parser.name) {
                "p" -> { paragraphStart = text.length; alignment = "left"; numbered = null; paragraphMarks.clear() }
                "tbl" -> { inTable = true; rowStarts = mutableListOf(); cellStarts = mutableListOf() }
                "tr" -> { rowStarts += text.length; cellStarts += text.length }
                "tc" -> cellStarts += text.length
                "r" -> { runStart = text.length; runMarks.clear() }
                "rPr" -> inRunProperties = true
                "t" -> inText = true
                "tab" -> if (!inTable) text.append('\t')
                "br", "cr" -> if (!inTable) text.append('\n')
                "jc" -> alignment = attr(parser, "val").let { if (it == "both") "center" else if (it in setOf("center", "right")) it else "left" }
                "pStyle" -> {
                    val name = attr(parser, "val").lowercase(Locale.ROOT)
                    val size = styleSizes[name] ?: headingSizes[name]
                    if (size != null) paragraphMarks["size"] = size.toString()
                }
                "numId" -> listStyles[attr(parser, "val")]?.let { numbered = it }
                "hyperlink" -> {
                    linkTarget = links[relationshipAttr(parser)]
                }
                "b", "i", "u", "strike" -> if (inRunProperties && attr(parser, "val") !in setOf("false", "0", "none")) {
                    runMarks[when (parser.name) { "b" -> "bold"; "i" -> "italic"; "u" -> "underline"; else -> "strike" }] = ""
                }
                "sz" -> if (inRunProperties) attr(parser, "val").toIntOrNull()?.let { runMarks["size"] = (it / 2).coerceIn(8, 72).toString() }
                "color", "shd" -> if (inRunProperties) {
                    val value = attr(parser, if (parser.name == "color") "val" else "fill")
                    if (value.matches(Regex("[0-9a-fA-F]{6}"))) {
                        runMarks[if (parser.name == "color") "color" else "highlight"] = (0xFF000000.toInt() or value.toInt(16)).toString()
                    }
                }
                "rFonts" -> if (inRunProperties) runMarks["font"] = attr(parser, "ascii").ifEmpty { "sans-serif" }
            }
        }

        private fun end(name: String) {
            when (name) {
                "t" -> inText = false
                "rPr" -> inRunProperties = false
                "r" -> {
                    if (text.length > runStart) {
                        runMarks.forEach { (kind, value) -> marks += TextMark(kind, runStart, text.length, value) }
                        linkTarget?.let { marks += TextMark("link", runStart, text.length, it) }
                    }
                    linkTarget = null
                }
                "hyperlink" -> linkTarget = null
                "tc" -> if (inTable) text.append('\t')
                "tr" -> if (inTable) text.append('\n')
                "p" -> if (!inTable) finishParagraph()
                "tbl" -> flushTable()
            }
        }

        private fun finishParagraph() {
            val end = text.length
            val numberedList = numbered
            if (end > paragraphStart) {
                if (alignment != "left") marks += TextMark("align", paragraphStart, end, alignment)
                val heading = paragraphMarks["size"]?.toIntOrNull()?.takeIf { it >= 20 }
                if (heading != null) {
                    marks += TextMark("size", paragraphStart, end, heading.toString())
                    marks += TextMark("bold", paragraphStart, end)
                }
                if (numberedList != null) {
                    counter = if (numberedList) counter + 1 else 0
                    val marker = if (numberedList) "$counter. " else "• "
                    text.insert(paragraphStart, marker)
                    shift(paragraphStart, marker.length)
                }
            }
            paragraphMarks.clear()
            text.append('\n')
        }

        /** Reintroduces the list markers Word stores as numbering, not characters. */
        private fun shift(at: Int, delta: Int) {
            if (delta == 0) return
            marks.indices.forEach { index ->
                val mark = marks[index]
                val start = if (mark.start >= at) mark.start + delta else mark.start
                val end = if (mark.end >= at) mark.end + delta else mark.end
                marks[index] = mark.copy(start = start, end = end)
            }
        }

        private fun flushTable() {
            if (!inTable) return
            inTable = false
            if (cellStarts.isEmpty()) return
            val start = cellStarts.first()
            val end = (text.length - 1).coerceAtLeast(start)
            tables += TableCodec.Block.Table(start, end, rowStarts, cellStarts)
        }

        private fun tableMarks(table: TableCodec.Block.Table): List<TextMark> =
            TableCodec.marksFor(text.toString(), table.start)
                .filter { it.start >= table.start && it.end <= table.end.coerceAtMost(text.length) }
                .map { mark -> mark.copy(end = mark.end.coerceAtMost(table.end.coerceAtMost(text.length))) }
    }

    private fun esc(value: String) = DocumentFiles.escapeXml(value)
    private fun hex(value: String) = "%06X".format((value.toIntOrNull() ?: Color.BLACK) and 0xFFFFFF)
}