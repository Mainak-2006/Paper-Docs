package com.company.paperdocs.io

import com.company.paperdocs.data.RichContent
import com.company.paperdocs.data.TextMark

/**
 * Table support for Word documents.
 *
 * A table is reconstructed as tab separated cells, one row per line, which is how it then appears
 * in the plain-text editor. Cell and row boundaries are kept as `table` marks so a later DOCX
 * export can rebuild a real grid instead of flattened text.
 */
object TableCodec {
    /** Either a run of ordinary text or one table occupying [start, end). */
    sealed interface Block {
        val start: Int
        val end: Int

        data class Text(override val start: Int, override val end: Int) : Block
        data class Table(override val start: Int, override val end: Int, val rowStarts: List<Int>, val cellStarts: List<Int>) : Block
    }

    /** Builds a table block from recorded row and cell offsets. */
    fun tableFrom(end: Int, cellStarts: List<Int>): Block.Table {
        val start = cellStarts.first()
        val textEnd = (end - 1).coerceAtLeast(start)
        return Block.Table(start, textEnd, rowStarts = cellStarts, cellStarts = cellStarts)
    }

    /** Splits content into alternating text and table blocks, ordered by position. */
    fun blocks(content: RichContent): List<Block> {
        val tables = content.marks.filter { it.kind == "table" }
        if (tables.isEmpty()) return listOf(Block.Text(0, content.text.length))
        val merged = tables.groupBy { it.start }.map { (start, group) ->
            Block.Table(start, group.maxOf { it.end }, group.map { it.start }.sorted(), group.map { it.start }.sorted())
        }.sortedBy { it.start }
        val blocks = mutableListOf<Block>()
        var cursor = 0
        merged.forEach { table ->
            if (table.start > cursor) blocks += Block.Text(cursor, table.start)
            blocks += table
            cursor = table.end
        }
        if (cursor < content.text.length) blocks += Block.Text(cursor, content.text.length)
        return blocks
    }

    /** Writes a real Word table for [block]; [renderText] emits anything outside tables. */
    fun writeTable(content: RichContent, block: Block.Table, renderText: (RichContent, Int, Int, StringBuilder) -> Unit, out: StringBuilder) {
        val rows = block.rowStarts
        if (rows.isEmpty()) {
            renderText(content, block.start, block.end, out)
            return
        }
        out.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr>")
        rows.forEachIndexed { index, rowStart ->
            val rowEnd = rows.getOrNull(index + 1) ?: block.end
            out.append("<w:tr>")
            block.cellStarts.filter { it >= rowStart && it < rowEnd }.forEach { cellStart ->
                val nextCell = block.cellStarts.filter { it > cellStart }.minOrNull() ?: rowEnd
                val cellEnd = nextCell.coerceAtMost(rowEnd)
                out.append("<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/></w:tcPr>")
                renderText(content, cellStart, cellEnd.coerceAtLeast(cellStart), out)
                out.append("</w:tc>")
            }
            out.append("</w:tr>")
        }
        out.append("</w:tbl>")
    }

    /** Marks every tab and newline inside a reconstructed table so it round-trips as a grid. */
    fun marksFor(text: String, start: Int): List<TextMark> {
        val marks = mutableListOf<TextMark>()
        text.forEachIndexed { index, char ->
            if (char == '\t' || char == '\n') marks += TextMark("table", start + index, start + index + 1)
        }
        return marks
    }
}