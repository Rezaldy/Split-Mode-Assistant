package com.rizkybusiness.ai.assistant.chatApp.ui.markdown

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * Markdown for chat rendering. Fenced code blocks are split out by our own line scanner
 * (streaming-proven: an unclosed fence renders as a code block in progress); everything
 * between fences goes through the platform-bundled intellij-markdown library with the
 * GFM flavour — full CommonMark + tables, strikethrough, task lists, nested structures.
 * No hand-rolled subset, no extra dependency.
 */
object MarkdownBlocks {

    sealed interface Block {
        /** Rendered HTML fragment (fully escaped by the library). */
        data class Paragraph(val html: String) : Block

        data class Code(val language: String?, val text: String) : Block
    }

    private val flavour = GFMFlavourDescriptor()

    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val textLines = mutableListOf<String>()
        val codeLines = mutableListOf<String>()
        var inCode = false
        var codeLanguage: String? = null

        fun flushText() {
            val segment = textLines.joinToString("\n")
            textLines.clear()
            if (segment.isBlank()) return
            blocks += Block.Paragraph(renderGfm(segment))
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
                    flushText()
                    inCode = true
                    codeLanguage = trimmed.removePrefix("```").trim().takeIf { it.isNotBlank() }
                }
                continue
            }
            if (inCode) codeLines += line else textLines += line
        }
        // Unclosed fence while streaming: show what we have as code.
        if (inCode) flushCode() else flushText()
        return blocks
    }

    /** CommonMark+GFM → HTML, post-processed for Swing's HTMLEditorKit quirks. */
    fun renderGfm(segment: String): String {
        // CommonMark passes raw HTML through; neutralize tag-like '<' up front so model
        // output can never inject markup (the parser preserves the entity as literal text).
        val safe = segment.replace(Regex("<(?=[A-Za-z/!?])"), "&lt;")
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(safe)
        var html = HtmlGenerator(safe, tree, flavour).generateHtml()
        html = html.removePrefix("<body>").removeSuffix("</body>")
        // Swing's HTML support needs explicit table borders (CSS border-collapse is out).
        html = html.replace("<table>", "<table border=\"1\" cellspacing=\"0\" cellpadding=\"4\">")
        // Task-list checkboxes would render as live form widgets; show glyphs instead.
        html = html.replace(Regex("<input[^>]*checked[^>]*>"), "☑ ")
        html = html.replace(Regex("<input[^>]*>"), "☐ ")
        // Swing fetches <img> sources on the EDT (remote, blocking) — placeholder instead.
        html = html.replace(Regex("<img[^>]*>"), "[image]")
        return html
    }

    fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
