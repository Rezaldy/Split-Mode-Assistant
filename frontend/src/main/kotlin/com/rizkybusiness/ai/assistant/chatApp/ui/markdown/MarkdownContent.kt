package com.rizkybusiness.ai.assistant.chatApp.ui.markdown

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorUtil
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.event.HyperlinkEvent

/**
 * Renders chat markdown: paragraphs as themed HTML, fenced code blocks as read-only IDE
 * editor fields — real syntax highlighting from the current color scheme when the IDE
 * knows the language, plain monospace otherwise (multi-IDE safe by construction).
 *
 * Streaming-aware: consecutive updates diff the block list and only touch what changed —
 * during a stream that is almost always just the last block, so no flicker and no
 * per-token editor churn.
 */
class MarkdownContent(private val project: Project? = null) : JPanel() {

    private var wrapWidthPx: Int = JBUI.scale(360)
    private var blocks: List<MarkdownBlocks.Block> = emptyList()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
    }

    fun setWrapWidth(px: Int) {
        if (px == wrapWidthPx) return
        wrapWidthPx = px
        components.forEach { (it as? BlockView)?.setWrapWidth(px) }
        revalidate()
        repaint()
    }

    fun setMarkdown(text: String) {
        val parsed = try {
            MarkdownBlocks.parse(text)
        } catch (e: Exception) {
            listOf(MarkdownBlocks.Block.Paragraph(MarkdownBlocks.escape(text)))
        }

        var reusable = 0
        while (reusable < minOf(parsed.size, blocks.size)) {
            val old = blocks[reusable]
            val new = parsed[reusable]
            val sameShape = when {
                old is MarkdownBlocks.Block.Paragraph && new is MarkdownBlocks.Block.Paragraph -> true
                old is MarkdownBlocks.Block.Table && new is MarkdownBlocks.Block.Table -> true
                old is MarkdownBlocks.Block.Code && new is MarkdownBlocks.Block.Code ->
                    old.language == new.language
                else -> false
            }
            if (!sameShape) break
            reusable++
        }

        for (i in 0 until reusable) {
            (getComponent(i) as BlockView).update(parsed[i])
        }
        while (componentCount > reusable) {
            remove(componentCount - 1)
        }
        for (i in reusable until parsed.size) {
            add(createView(parsed[i]) as Component)
        }
        blocks = parsed
        revalidate()
        repaint()
    }

    private fun createView(block: MarkdownBlocks.Block): BlockView = when (block) {
        is MarkdownBlocks.Block.Paragraph -> ParagraphView(block, wrapWidthPx)
        is MarkdownBlocks.Block.Table -> ParagraphView(block, wrapWidthPx)
        is MarkdownBlocks.Block.Code -> CodeView(block, wrapWidthPx, project)
    }
}

private interface BlockView {
    fun update(block: MarkdownBlocks.Block)
    fun setWrapWidth(px: Int)
}

private class ParagraphView(block: MarkdownBlocks.Block, private var wrapPx: Int) :
    JEditorPane(), BlockView {

    private var currentHtml = ""

    init {
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        isEditable = false
        isFocusable = false
        isOpaque = false
        border = JBUI.Borders.emptyBottom(4)
        alignmentX = LEFT_ALIGNMENT
        addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                event.url?.let { BrowserUtil.browse(it) }
            }
        }
        update(block)
    }

    override fun update(block: MarkdownBlocks.Block) {
        val html = when (block) {
            is MarkdownBlocks.Block.Paragraph -> block.html
            is MarkdownBlocks.Block.Table -> block.html
            is MarkdownBlocks.Block.Code -> return
        }
        if (html == currentHtml) return
        currentHtml = html
        text = wrapHtml(html)
    }

    private fun wrapHtml(bodyHtml: String): String {
        val base = JBFont.regular()
        val codeBackground = ColorUtil.toHtmlColor(JBColor(0xEBEBEB, 0x2B2D30))
        val foreground = ColorUtil.toHtmlColor(UIUtil.getLabelForeground())
        val linkColor = ColorUtil.toHtmlColor(JBUI.CurrentTheme.Link.Foreground.ENABLED)
        return "<html><head><style>" +
            "body { font-family: '${base.family}'; font-size: ${base.size}pt; color: $foreground; margin: 0; }" +
            "code { background-color: $codeBackground; font-family: '${editorFontName()}'; }" +
            "a { color: $linkColor; }" +
            "</style></head><body>$bodyHtml</body></html>"
    }

    override fun setWrapWidth(px: Int) {
        wrapPx = px
    }

    override fun getPreferredSize(): Dimension {
        size = Dimension(wrapPx, Short.MAX_VALUE.toInt())
        return Dimension(wrapPx, super.getPreferredSize().height)
    }

    override fun getMaximumSize(): Dimension = preferredSize
}

private class CodeView(
    block: MarkdownBlocks.Block.Code,
    private var wrapPx: Int,
    project: Project?,
) : JPanel(), BlockView {

    private var currentText = block.text
    private val field = run {
        val fileType = fileTypeFor(block.language)
        // EditorTextField only installs syntax highlighting when it has a Project —
        // a null project renders plain grey text. Belt and suspenders: pass the
        // project AND set the highlighter explicitly for the current scheme.
        EditorTextField(
            EditorFactory.getInstance().createDocument(block.text),
            project,
            fileType,
            /* isViewer = */ true,
            /* oneLineMode = */ false,
        ).apply {
            setFontInheritedFromLAF(false)
            addSettingsProvider { editor ->
                // Code keeps its real line structure: no soft wraps, scroll horizontally.
                editor.settings.isUseSoftWraps = false
                editor.setHorizontalScrollbarVisible(true)
                editor.settings.additionalLinesCount = 0
                editor.settings.isLineNumbersShown = false
                editor.setBorder(JBUI.Borders.empty(4))
                editor.highlighter = EditorHighlighterFactory.getInstance()
                    .createEditorHighlighter(project, fileType)
            }
        }
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.emptyBottom(6)
        add(field)
    }

    override fun update(block: MarkdownBlocks.Block) {
        val code = (block as MarkdownBlocks.Block.Code).text
        if (code == currentText) return
        currentText = code
        field.text = code
    }

    override fun setWrapWidth(px: Int) {
        wrapPx = px
        revalidate()
    }

    override fun getPreferredSize(): Dimension {
        val height = field.preferredSize.height + insets.top + insets.bottom
        return Dimension(wrapPx, height)
    }

    override fun getMaximumSize(): Dimension = preferredSize
}

/** IDE-known language → its file type for highlighting; anything unknown → plain text. */
private fun fileTypeFor(language: String?): FileType {
    if (language.isNullOrBlank()) return PlainTextFileType.INSTANCE
    val extension = LANGUAGE_TAG_TO_EXTENSION[language.lowercase()] ?: language.lowercase()
    val fileType = FileTypeManager.getInstance().getFileTypeByExtension(extension)
    return if (fileType is UnknownFileType) PlainTextFileType.INSTANCE else fileType
}

private fun editorFontName(): String =
    EditorColorsManager.getInstance().globalScheme.editorFontName

private val LANGUAGE_TAG_TO_EXTENSION = mapOf(
    "kotlin" to "kt",
    "python" to "py",
    "javascript" to "js",
    "typescript" to "ts",
    "shell" to "sh",
    "bash" to "sh",
    "yaml" to "yml",
    "markdown" to "md",
    "rust" to "rs",
    "csharp" to "cs",
    "c++" to "cpp",
)
