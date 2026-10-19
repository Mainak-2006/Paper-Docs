package com.company.paperdocs.editor

import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.*
import com.company.paperdocs.data.RichContent
import com.company.paperdocs.data.TextMark
import org.json.JSONArray
import org.json.JSONObject

object RichTextCodec {
    fun toJson(content: RichContent): String = JSONObject().apply {
        put("version", 1)
        put("text", content.text)
        put("marks", JSONArray().apply {
            content.validated().marks.forEach { mark -> put(JSONObject().apply {
                put("kind", mark.kind); put("start", mark.start); put("end", mark.end); put("value", mark.value)
            }) }
        })
    }.toString()

    fun fromJson(json: String): RichContent {
        val data = JSONObject(json)
        require(data.getInt("version") == 1) { "Unsupported document version" }
        val marks = data.getJSONArray("marks")
        return RichContent(data.getString("text"), List(marks.length()) { i ->
            val item = marks.getJSONObject(i)
            TextMark(item.getString("kind"), item.getInt("start"), item.getInt("end"), item.optString("value"))
        }).validated()
    }

    fun span(kind: String, value: String): Any? = when (kind) {
        "bold" -> StyleSpan(Typeface.BOLD)
        "italic" -> StyleSpan(Typeface.ITALIC)
        "underline" -> UnderlineSpan()
        "strike" -> StrikethroughSpan()
        "size" -> AbsoluteSizeSpan((value.toIntOrNull() ?: 16).coerceIn(8, 72), true)
        "font" -> TypefaceSpan(value)
        "color" -> ForegroundColorSpan(value.toIntOrNull() ?: android.graphics.Color.BLACK)
        "highlight" -> BackgroundColorSpan(value.toIntOrNull() ?: android.graphics.Color.YELLOW)
        "link" -> URLSpan(value)
        "align" -> AlignmentSpan.Standard(when (value) {
            "center" -> Layout.Alignment.ALIGN_CENTER
            "right" -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        })
        else -> null
    }

    fun kind(span: Any): String? = when (span) {
        is StyleSpan -> if (span.style == Typeface.BOLD) "bold" else if (span.style == Typeface.ITALIC) "italic" else null
        is UnderlineSpan -> "underline"
        is StrikethroughSpan -> "strike"
        is AbsoluteSizeSpan -> "size"
        is TypefaceSpan -> "font"
        is ForegroundColorSpan -> "color"
        is BackgroundColorSpan -> "highlight"
        is URLSpan -> "link"
        is AlignmentSpan -> "align"
        else -> null
    }

    fun toSpannable(content: RichContent): SpannableStringBuilder = SpannableStringBuilder(content.text).apply {
        content.validated().marks.forEach { mark -> span(mark.kind, mark.value)?.let {
            setSpan(it, mark.start, mark.end, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        } }
    }

    fun fromSpanned(text: Spanned): RichContent {
        val marks = buildList {
            text.getSpans(0, text.length, Any::class.java).forEach { span ->
                val start = text.getSpanStart(span).coerceAtLeast(0)
                val end = text.getSpanEnd(span).coerceAtMost(text.length)
                if (start >= end) return@forEach
                if (span is StyleSpan && span.style == Typeface.BOLD_ITALIC) {
                    add(TextMark("bold", start, end)); add(TextMark("italic", start, end))
                    return@forEach
                }
                val kind = kind(span) ?: return@forEach
                val value = when (span) {
                    is AbsoluteSizeSpan -> span.size.toString()
                    is TypefaceSpan -> span.family.orEmpty()
                    is ForegroundColorSpan -> span.foregroundColor.toString()
                    is BackgroundColorSpan -> span.backgroundColor.toString()
                    is URLSpan -> span.url
                    is AlignmentSpan -> when (span.alignment) {
                        Layout.Alignment.ALIGN_CENTER -> "center"
                        Layout.Alignment.ALIGN_OPPOSITE -> "right"
                        else -> "left"
                    }
                    else -> ""
                }
                add(TextMark(kind, start, end, value))
            }
        }
        return RichContent(text.toString(), marks.sortedWith(compareBy({ it.start }, { it.end }, { it.kind }, { it.value })))
    }

    /** Remove only the selected part of a style, preserving both outside fragments. */
    fun clearRange(text: Spannable, start: Int, end: Int, kind: String?) {
        text.getSpans(start, end, Any::class.java).forEach { existing ->
            val existingKind = kind(existing) ?: return@forEach
            if (kind != null && kind != existingKind) return@forEach
            val a = text.getSpanStart(existing)
            val b = text.getSpanEnd(existing)
            if (start != end && (a >= end || b <= start)) return@forEach
            val value = fromSpanned(SpannableStringBuilder("x").apply { setSpan(existing, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }).marks.firstOrNull()?.value.orEmpty()
            text.removeSpan(existing)
            if (a < start) span(existingKind, value)?.let { text.setSpan(it, a, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (b > end) span(existingKind, value)?.let { text.setSpan(it, end, b, Spanned.SPAN_EXCLUSIVE_INCLUSIVE) }
        }
    }
}
