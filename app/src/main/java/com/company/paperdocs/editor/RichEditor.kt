package com.company.paperdocs.editor

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.InputFilter
import android.text.Spanned
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.company.paperdocs.data.RichContent

class DocumentEditText(context: Context) : EditText(context) {
    var selectionChanged: (() -> Unit)? = null
    init {
        background = null
        setTextColor(Color.rgb(32, 33, 36))
        setHintTextColor(Color.rgb(154, 160, 166))
        textSize = 16f
        setLineSpacing(8f, 1.1f)
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setHorizontallyScrolling(false)
        setPadding(0, 0, 0, 0)
        hint = "Start writing…"
        contentDescription = "Document body"
        filters = arrayOf(InputFilter.LengthFilter(1_000_000))
        minHeight = (750 * resources.displayMetrics.density).toInt()
    }
    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        selectionChanged?.invoke()
    }
}

class RichEditorController(initial: RichContent, private val onChange: (RichContent) -> Unit) {
    var content by mutableStateOf(initial)
        private set
    var selectionStart by mutableIntStateOf(0)
        private set
    var selectionEnd by mutableIntStateOf(0)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    private var view: DocumentEditText? = null
    private var suppress = false
    private var lastEditAt = 0L
    private val undo = ArrayDeque<RichContent>()
    private val redo = ArrayDeque<RichContent>()
    private var watcher: TextWatcher? = null
    private var formatTick by mutableIntStateOf(0)

    fun attach(editor: DocumentEditText) {
        view = editor
        suppress = true
        editor.setText(RichTextCodec.toSpannable(content))
        editor.setSelection(selectionStart.coerceIn(0, content.text.length), selectionEnd.coerceIn(0, content.text.length))
        suppress = false
        editor.selectionChanged = {
            selectionStart = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
            selectionEnd = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
            formatTick++
        }
        watcher = object : TextWatcher {
            private var newline = -1
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                newline = if (!suppress && before == 0 && count == 1 && s?.get(start) == '\n') start else -1
            }
            override fun afterTextChanged(s: Editable?) {
                if (!suppress && s != null) {
                    val at = newline
                    newline = -1
                    if (at >= 0) {
                        val lineStart = s.lastIndexOf('\n', at - 1) + 1
                        val previous = s.substring(lineStart, at)
                        val prefix = Regex("^(• |☐ |☑ |(\\d+)\\. )").find(previous)
                        if (prefix != null) {
                            suppress = true
                            if (previous == prefix.value) s.delete(lineStart, at)
                            else {
                                val next = prefix.groupValues[2].toIntOrNull()?.let { "${it + 1}. " } ?: if (prefix.value == "☑ ") "☐ " else prefix.value
                                s.insert(at + 1, next)
                            }
                            suppress = false
                        }
                    }
                    changed(groupTyping = true)
                }
            }
        }.also(editor::addTextChangedListener)
    }

    fun detach() {
        watcher?.let { view?.removeTextChangedListener(it) }
        view?.selectionChanged = null
        view = null
    }

    private fun changed(groupTyping: Boolean = false) {
        val next = view?.text?.let(RichTextCodec::fromSpanned) ?: return
        if (content == next) return
        val now = System.currentTimeMillis()
        if (!groupTyping || now - lastEditAt > 700 || undo.isEmpty()) {
            undo.addLast(content)
            if (undo.size > 100) undo.removeFirst()
        }
        lastEditAt = if (groupTyping) now else 0
        redo.clear()
        content = next
        updateHistory()
        onChange(next)
    }

    private fun updateHistory() { canUndo = undo.isNotEmpty(); canRedo = redo.isNotEmpty() }

    private fun applyContent(next: RichContent) {
        val position = selectionStart.coerceAtMost(next.text.length)
        content = next
        suppress = true
        view?.setText(RichTextCodec.toSpannable(next))
        view?.setSelection(position)
        suppress = false
        lastEditAt = 0
        updateHistory()
        onChange(next)
    }

    fun undo() { if (undo.isNotEmpty()) { redo.addLast(content); applyContent(undo.removeLast()) } }
    fun redo() { if (redo.isNotEmpty()) { undo.addLast(content); applyContent(redo.removeLast()) } }
    fun restore(next: RichContent) { undo.addLast(content); redo.clear(); applyContent(next) }

    fun active(kind: String): Boolean {
        @Suppress("UNUSED_VARIABLE") val tick = formatTick
        if (selectionStart == selectionEnd) {
            val text = view?.text ?: return false
            return text.getSpans(selectionStart, selectionEnd, Any::class.java).any { RichTextCodec.kind(it) == kind }
        }
        return content.marks.any { it.kind == kind && it.start <= selectionStart && it.end >= selectionEnd }
    }

    fun format(kind: String, value: String = "", toggle: Boolean = true) {
        val text = view?.text ?: return
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(start, text.length)
        val remove = toggle && active(kind)
        RichTextCodec.clearRange(text, start, end, kind)
        if (!remove) RichTextCodec.span(kind, value)?.let {
            text.setSpan(it, start, end, if (start == end) Spanned.SPAN_INCLUSIVE_INCLUSIVE else Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        }
        changed()
        formatTick++
        view?.invalidate()
    }

    fun heading(size: Int) = paragraphMutation { text, start, end ->
        RichTextCodec.clearRange(text, start, end, "size")
        RichTextCodec.clearRange(text, start, end, "bold")
        RichTextCodec.span("size", size.toString())?.let { text.setSpan(it, start, end, Spanned.SPAN_INCLUSIVE_INCLUSIVE) }
        if (size > 16) RichTextCodec.span("bold", "")?.let { text.setSpan(it, start, end, Spanned.SPAN_INCLUSIVE_INCLUSIVE) }
    }

    fun align(alignment: String) = paragraphMutation { text, start, end ->
        RichTextCodec.clearRange(text, start, end, "align")
        RichTextCodec.span("align", alignment)?.let { text.setSpan(it, start, end, Spanned.SPAN_INCLUSIVE_INCLUSIVE) }
    }

    private fun paragraphMutation(action: (Editable, Int, Int) -> Unit) {
        val text = view?.text ?: return
        val start = text.lastIndexOf('\n', (selectionStart - 1).coerceAtLeast(-1)) + 1
        val end = text.indexOf('\n', selectionEnd.coerceAtMost(text.length)).let { if (it == -1) text.length else it + 1 }
        suppress = true
        action(text, start, end)
        suppress = false
        changed()
        view?.invalidate()
    }

    fun list(numbered: Boolean = false, checklist: Boolean = false) = paragraphMutation { text, start, end ->
        val lines = text.substring(start, end).split('\n')
        val prefix = Regex("^(?:• |☐ |☑ |\\d+\\. )")
        val offsets = mutableListOf<Pair<Int, String>>()
        var offset = start
        lines.forEachIndexed { index, line ->
            if (offset < end || start == end) {
                offsets += offset to (if (numbered) "${index + 1}. " else if (checklist) "☐ " else "• ")
            }
            offset += line.length + 1
        }
        val remove = lines.filter { it.isNotEmpty() }.let { it.isNotEmpty() && it.all { line -> prefix.containsMatchIn(line) } }
        offsets.asReversed().forEach { (position, marker) ->
            val match = prefix.find(text.substring(position))
            text.replace(position, position + (match?.value?.length ?: 0), if (remove) "" else marker)
        }
    }

    fun clearFormatting() {
        val text = view?.text ?: return
        RichTextCodec.clearRange(text, selectionStart, selectionEnd, null)
        changed(); formatTick++; view?.invalidate()
    }

    fun matches(query: String, matchCase: Boolean = false): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val matches = mutableListOf<IntRange>()
        var offset = 0
        while (offset <= content.text.length - query.length) {
            val found = content.text.indexOf(query, offset, !matchCase)
            if (found == -1) break
            matches += found until found + query.length
            offset = found + query.length
        }
        return matches
    }

    fun select(range: IntRange) {
        view?.setSelection(range.first, range.last + 1)
        view?.bringPointIntoView(range.first)
    }

    fun replace(query: String, replacement: String, all: Boolean, matchCase: Boolean = false): Int {
        val ranges = matches(query, matchCase)
        val selected = if (all) ranges else listOfNotNull(ranges.firstOrNull { it.first >= selectionStart } ?: ranges.firstOrNull())
        suppress = true
        selected.asReversed().forEach { view?.text?.replace(it.first, it.last + 1, replacement) }
        suppress = false
        changed()
        return selected.size
    }

    fun selectedText(): String = content.text.substring(minOf(selectionStart, selectionEnd).coerceIn(0, content.text.length), maxOf(selectionStart, selectionEnd).coerceIn(0, content.text.length))
}
