package com.rizkybusiness.ai.assistant.chatApp.ui.markdown

/**
 * Pragmatic markdown subset for chat rendering: fenced code blocks (with language tag),
 * inline code, bold, italic, links, hash headers and dash/star/numbered lists. Pure Kotlin on
 * purpose — unit-testable, and all HTML escaping lives here in one place.
 *
 * Streaming-friendly: an unclosed fence at the end of the text is treated as a code
 * block in progress, so highlighting appears while the model is still typing it.
 */
object MarkdownBlocks {

    sealed interface Block {
        /** Inline-converted HTML (already escaped), without surrounding html/body tags. */
        data class Paragraph(val html: String) : Block

        data class Code(val language: String?, val text: String) : Block

        /** A complete `<table>…</table>` fragment (cells already inline-converted). */
        data class Table(val html: String) : Block
    }

    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraphLines = mutableListOf<String>()
        val tableLines = mutableListOf<String>()
        val codeLines = mutableListOf<String>()
        var inCode = false
        var codeLanguage: String? = null

        fun flushParagraph() {
            if (paragraphLines.any { it.isNotBlank() }) {
                blocks += Block.Paragraph(paragraphLines.joinToString("<br>") { lineToHtml(it) })
            }
            paragraphLines.clear()
        }

        fun flushTable() {
            if (tableLines.isEmpty()) return
            if (tableLines.size >= 2 && isSeparatorRow(tableLines[1])) {
                blocks += Block.Table(buildTableHtml(
                    header = splitCells(tableLines[0]),
                    rows = tableLines.drop(2).map { splitCells(it) },
                ))
            } else {
                // Not (yet) a valid table — e.g. header row still streaming: plain text.
                blocks += Block.Paragraph(tableLines.joinToString("<br>") { lineToHtml(it) })
            }
            tableLines.clear()
        }

        fun flushCode() {
            blocks += Block.Code(codeLanguage, codeLines.joinToString("\n"))
            codeLines.clear()
            codeLanguage = null
        }

        for (line in markdown.lines()) {
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```")) {
                if (inCode) {
                    flushCode()
                    inCode = false
                } else {
                    flushParagraph()
                    flushTable()
                    inCode = true
                    codeLanguage = trimmed.removePrefix("```").trim().takeIf { it.isNotBlank() }
                }
                continue
            }
            when {
                inCode -> codeLines += line
                trimmed.startsWith("|") -> {
                    flushParagraph()
                    tableLines += line
                }
                else -> {
                    flushTable()
                    paragraphLines += line
                }
            }
        }
        // Unclosed fence while streaming: show what we have as code.
        if (inCode) flushCode() else {
            flushTable()
            flushParagraph()
        }
        return blocks
    }

    private fun isSeparatorRow(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith("|") || '-' !in trimmed) return false
        return trimmed.all { it == '|' || it == '-' || it == ':' || it == ' ' }
    }

    private fun splitCells(line: String): List<String> = line.trim()
        .removePrefix("|")
        .removeSuffix("|")
        .split("|")
        .map { inlineToHtml(it.trim()) }

    private fun buildTableHtml(header: List<String>, rows: List<List<String>>): String {
        val html = StringBuilder("<table border=\"1\" cellspacing=\"0\" cellpadding=\"4\">")
        html.append("<tr>")
        header.forEach { html.append("<th align=\"left\">").append(it).append("</th>") }
        html.append("</tr>")
        for (row in rows) {
            html.append("<tr>")
            row.forEach { html.append("<td>").append(it).append("</td>") }
            html.append("</tr>")
        }
        return html.append("</table>").toString()
    }

    /** One markdown source line → escaped HTML with inline styling applied. */
    fun lineToHtml(line: String): String {
        val headerMatch = Regex("^(#{1,3})\\s+(.*)").find(line.trimStart())
        if (headerMatch != null) {
            return "<b>" + inlineToHtml(headerMatch.groupValues[2]) + "</b>"
        }
        val listMatch = Regex("^(\\s*)[-*]\\s+(.*)").find(line)
        if (listMatch != null) {
            return "&nbsp;&nbsp;•&nbsp;" + inlineToHtml(listMatch.groupValues[2])
        }
        return inlineToHtml(line)
    }

    /**
     * Inline conversion. Backtick spans are honored first so nothing inside them gets
     * styled; an unbalanced trailing backtick (mid-stream) renders literally.
     */
    fun inlineToHtml(text: String): String {
        val parts = text.split('`')
        val result = StringBuilder()
        for ((index, part) in parts.withIndex()) {
            // Odd indexes sit between backticks; the last one is unclosed when the split
            // produced an even part count (mid-stream) and then renders literally.
            val closedCodeSpan = index % 2 == 1 && !(index == parts.lastIndex && parts.size % 2 == 0)
            when {
                closedCodeSpan -> result.append("<code>").append(escape(part)).append("</code>")
                index % 2 == 1 -> result.append(styleText(escape("`$part")))
                else -> result.append(styleText(escape(part)))
            }
        }
        return result.toString()
    }

    private fun styleText(escaped: String): String {
        var html = escaped
        html = Regex("\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)")
            .replace(html) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
        html = Regex("\\*\\*(.+?)\\*\\*").replace(html) { "<b>${it.groupValues[1]}</b>" }
        html = Regex("(?<!\\*)\\*([^*]+)\\*(?!\\*)").replace(html) { "<i>${it.groupValues[1]}</i>" }
        return html
    }

    fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
