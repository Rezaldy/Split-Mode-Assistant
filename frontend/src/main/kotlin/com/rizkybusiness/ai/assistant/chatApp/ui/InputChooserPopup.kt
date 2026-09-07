package com.rizkybusiness.ai.assistant.chatApp.ui

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Point
import javax.swing.JComponent
import javax.swing.ListSelectionModel

private const val MAX_VISIBLE_ROWS = 10

/**
 * A chooser anchored ABOVE [anchor] that never takes focus: the anchor keeps typing focus and
 * forwards Up/Down/Enter/Escape via [moveSelection], [chooseSelected], [hide]. Items are shown
 * best-match-last so the closest match sits nearest the input.
 */
class InputChooserPopup<T>(
    private val anchor: JComponent,
    private val title: String,
    private val renderText: (T) -> String,
    private val onChosen: (T) -> Unit,
) {
    private val model = CollectionListModel<T>()
    private val list = JBList(model).apply {
        cellRenderer = SimpleListCellRenderer.create { label, value, _ -> label.text = renderText(value) }
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }
    private var popup: JBPopup? = null

    val isShowing: Boolean get() = popup?.let { it.isVisible && !it.isDisposed } == true

    fun update(resultsBestFirst: List<T>) {
        if (resultsBestFirst.isEmpty()) {
            hide()
            return
        }

        val reversed = resultsBestFirst.asReversed()
        model.replaceAll(reversed)
        val lastIndex = reversed.size - 1
        list.selectedIndex = lastIndex
        list.ensureIndexIsVisible(lastIndex)
        list.visibleRowCount = reversed.size.coerceAtMost(MAX_VISIBLE_ROWS)

        val current = popup
        if (current == null || !isShowing) {
            popup = JBPopupFactory.getInstance()
                .createListPopupBuilder(list)
                .setTitle(title)
                .setRequestFocus(false)
                .setMovable(false)
                .setResizable(false)
                .setCancelOnClickOutside(true)
                .setItemChosenCallback { chosen ->
                    hide()
                    onChosen(chosen)
                }
                .createPopup()
                .also { it.show(RelativePoint(anchor, Point(0, 0))) }
            reposition()
        } else {
            current.pack(true, true)
            reposition()
        }
    }

    fun moveSelection(delta: Int) {
        val size = model.size
        if (size == 0) return
        val next = (list.selectedIndex + delta).coerceIn(0, size - 1)
        list.selectedIndex = next
        list.ensureIndexIsVisible(next)
    }

    fun chooseSelected(): Boolean {
        if (!isShowing) return false
        val value = list.selectedValue ?: return false
        hide()
        onChosen(value)
        return true
    }

    fun hide() {
        popup?.cancel()
        popup = null
    }

    private fun reposition() {
        val current = popup ?: return
        if (!anchor.isShowing) return

        val initialSize = current.size ?: current.content.preferredSize
        val width = initialSize.width.coerceAtLeast(anchor.width)
        current.setSize(Dimension(width, initialSize.height))

        val finalSize = current.size ?: initialSize
        val anchorScreen = anchor.locationOnScreen
        current.setLocation(Point(anchorScreen.x, anchorScreen.y - finalSize.height - JBUI.scale(4)))
    }
}
