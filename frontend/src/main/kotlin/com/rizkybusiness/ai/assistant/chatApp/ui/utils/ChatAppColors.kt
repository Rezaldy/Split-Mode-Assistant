package com.rizkybusiness.ai.assistant.chatApp.ui.utils

import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import javax.swing.UIManager

object ChatAppColors {
    object Panel {
        val background: Color
            get() = UIManager.getColor("Panel.background") ?: JBColor.PanelBackground
    }

    object Text {
        val disabled: Color
            get() = JBUI.CurrentTheme.Label.disabledForeground()

        val normal: Color
            get() = UIManager.getColor("Label.foreground") ?: JBColor.foreground()

        val timestamp: Color = JBColor.namedColor("CodeAssistant.Text.timestamp", JBColor(Gray._192, Gray._160))

        val authorName: Color =
            JBColor.namedColor("CodeAssistant.Text.authorName", JBColor(Color(219, 224, 235), Color(180, 200, 220)))
    }

    object MessageBubble {
        val myBackground: Color
            get() = JBColor.namedColor(
                "CodeAssistant.MessageBubble.myBackground",
                JBColor(Color(227, 242, 253), Color(37, 55, 70))
            )

        val othersBackground: Color
            get() = JBColor.namedColor(
                "CodeAssistant.MessageBubble.othersBackground",
                JBColor(Gray._245, Color(50, 50, 52))
            )

        val myBackgroundBorder: Color
            get() = JBColor.namedColor(
                "CodeAssistant.MessageBubble.myBorder",
                JBColor(Color(144, 202, 249), Color(66, 165, 245))
            )

        val othersBackgroundBorder: Color
            get() = JBColor.namedColor(
                "CodeAssistant.MessageBubble.othersBorder",
                JBColor(Gray._189, Gray._97)
            )

        val mySearchHighlightedBackground: Color
            get() = JBColor.namedColor(
                "CodeAssistant.MessageBubble.mySearchBackground",
                JBColor(Color(179, 229, 252), Color(58, 96, 115))
            )

        val othersSearchHighlightedBackground: Color
            get() = JBColor.namedColor(
                "CodeAssistant.MessageBubble.othersSearchBackground",
                JBColor(Gray._224, Color(70, 73, 75))
            )

        val searchHighlightedBackgroundBorder: Color = JBColor.namedColor(
            "CodeAssistant.MessageBubble.searchBorder",
            JBColor(Color(66, 165, 245), Color(100, 181, 246))
        )

        val matchingMyBorder: Color
            get() = JBColor.namedColor(
                "CodeAssistant.MessageBubble.matchingMyBorder",
                JBColor(Color(144, 202, 249), Color(79, 195, 247))
            )

        val matchingOthersBorder: Color
            get() = JBColor.namedColor(
                "CodeAssistant.MessageBubble.matchingOthersBorder",
                JBColor(Gray._158, Gray._117)
            )

        val errorBackground: Color
            get() = JBColor.namedColor(
                "CodeAssistant.MessageBubble.errorBackground",
                JBColor(Color(253, 236, 234), Color(66, 43, 43))
            )

        val errorBorder: Color
            get() = JBColor.namedColor(
                "CodeAssistant.MessageBubble.errorBorder",
                JBColor(Color(239, 154, 154), Color(180, 90, 90))
            )
    }

    object Prompt {
        val border: Color = JBColor.border()
    }

    object IndexStatus {
        val healthy: Color =
            JBColor.namedColor("CodeAssistant.IndexStatus.healthy", JBColor(Color(0x2E7D32), Color(0x499C54)))

        val unsynced: Color =
            JBColor.namedColor("CodeAssistant.IndexStatus.unsynced", JBColor(Color(0xF9A825), Color(0xD6AE58)))

        val error: Color =
            JBColor.namedColor("CodeAssistant.IndexStatus.error", JBColor(Color(0xC62828), Color(0xCF5B56)))

        val unknown: Color =
            JBColor.namedColor("CodeAssistant.IndexStatus.unknown", JBColor.GRAY)
    }

    object Markdown {
        val codeBackground: Color =
            JBColor.namedColor("CodeAssistant.Markdown.codeBackground", JBColor(0xEBEBEB, 0x2B2D30))
    }
}
