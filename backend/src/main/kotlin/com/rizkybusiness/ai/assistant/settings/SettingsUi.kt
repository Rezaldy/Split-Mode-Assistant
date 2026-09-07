package com.rizkybusiness.ai.assistant.settings

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI

/** Small shared building blocks used across the settings sub-pages. */
internal object SettingsUi {

    /** A small grey helper label placed under a field to explain it. */
    fun hintLabel(text: String) = JBLabel(text).apply {
        font = JBFont.small()
        foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
    }
}
