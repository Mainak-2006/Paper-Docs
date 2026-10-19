package com.company.paperdocs

import com.company.paperdocs.data.RichContent
import com.company.paperdocs.data.TextMark
import org.junit.Assert.*
import org.junit.Test

class ContentTest {
    @Test fun wordCountHandlesPunctuationAndUnicode() {
        assertEquals(6, RichContent("Hello, world! It's a café. 你好").wordCount)
        assertEquals(0, RichContent(" \n\t—!!").wordCount)
    }

    @Test fun invalidPersistedRangesAreDropped() {
        val content = RichContent("abc", listOf(TextMark("bold", -1, 2), TextMark("italic", 0, 10), TextMark("unknown", 0, 2), TextMark("underline", 0, 3)))
        assertEquals(listOf(TextMark("underline", 0, 3)), content.validated().marks)
    }

    @Test fun hyperlinkMarksAreAcceptedAsKnownKinds() {
        val content = RichContent("see docs", listOf(TextMark("link", 4, 8, "https://example.com")))
        assertEquals(listOf(TextMark("link", 4, 8, "https://example.com")), content.validated().marks)
    }
}