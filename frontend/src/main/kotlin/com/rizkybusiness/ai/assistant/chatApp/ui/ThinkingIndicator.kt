package com.rizkybusiness.ai.assistant.chatApp.ui

import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBLabel
import com.intellij.util.animation.Animation
import com.intellij.util.animation.Easing
import com.intellij.util.animation.JBAnimator
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.rizkybusiness.ai.assistant.ChatMessage
import com.rizkybusiness.ai.assistant.ModularPluginFrontendBundle
import com.rizkybusiness.ai.assistant.chatApp.ui.utils.ChatAppColors
import com.rizkybusiness.ai.assistant.chatApp.ui.utils.ChatUIConstants
import java.awt.AlphaComposite
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JPanel
import javax.swing.Timer

@Suppress("UnstableApiUsage")
class ThinkingIndicator(private var phase: ChatMessage.GenerationPhase) : JPanel(), Disposable {
    private val animator = JBAnimator(this).apply {
        isCyclic = true
        period = ChatUIConstants.ThinkingIndicator.ANIMATION_PERIOD_MS
    }

    private val dots = List(ChatUIConstants.ThinkingIndicator.DOT_COUNT) { PulsingDot() }

    private val phaseLabel = JBLabel().apply {
        font = JBFont.small()
        foreground = ChatAppColors.Text.disabled
        border = JBUI.Borders.emptyLeft(ChatUIConstants.Spacing.SMALL)
    }

    /** Wall-clock time the current WAITING phase began, for the elapsed-seconds label. */
    private var waitingStartedAtMs: Long = 0L

    private val waitingTimer = Timer(WAITING_TICK_MS) { updatePhaseLabel() }.apply {
        isRepeats = true
    }

    init {
        setupAppearance()
        dots.forEach { add(it) }
        add(phaseLabel)
        applyPhase()
    }

    private fun setupAppearance() {
        layout = FlowLayout(FlowLayout.LEFT, 0, 0)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        border = JBUI.Borders.empty(ChatUIConstants.ThinkingIndicator.PADDING)
    }

    override fun addNotify() {
        super.addNotify()
        start()
    }

    override fun removeNotify() {
        animator.stop()
        waitingTimer.stop()
        super.removeNotify()
    }

    override fun dispose() {
        animator.stop()
        waitingTimer.stop()
    }

    fun setPhase(phase: ChatMessage.GenerationPhase) {
        if (this.phase == phase) return
        this.phase = phase
        applyPhase()
    }

    private fun applyPhase() {
        waitingTimer.stop()
        if (phase == ChatMessage.GenerationPhase.WAITING) {
            waitingStartedAtMs = System.currentTimeMillis()
            waitingTimer.start()
        }
        updatePhaseLabel()
    }

    private fun updatePhaseLabel() {
        phaseLabel.text = when (phase) {
            ChatMessage.GenerationPhase.NONE -> ""
            ChatMessage.GenerationPhase.PREPARING -> ModularPluginFrontendBundle.message("chat.phase.preparing")
            ChatMessage.GenerationPhase.WAITING -> waitingLabelText()
            ChatMessage.GenerationPhase.THINKING -> ModularPluginFrontendBundle.message("chat.phase.thinking")
            ChatMessage.GenerationPhase.WRITING -> ModularPluginFrontendBundle.message("chat.phase.writing")
        }
    }

    private fun waitingLabelText(): String {
        val elapsedSeconds = (System.currentTimeMillis() - waitingStartedAtMs) / 1000
        return if (elapsedSeconds >= WAITING_ELAPSED_THRESHOLD_S) {
            ModularPluginFrontendBundle.message("chat.phase.waiting.elapsed", elapsedSeconds)
        } else {
            ModularPluginFrontendBundle.message("chat.phase.waiting")
        }
    }

    private fun start() {
        val cfg = ChatUIConstants.ThinkingIndicator
        val animations = dots.mapIndexed { index, dot ->
            Animation { fraction ->
                dot.alpha = cfg.MIN_ALPHA + (cfg.MAX_ALPHA - cfg.MIN_ALPHA) * pingPong(fraction)
                dot.repaint()
            }.apply {
                delay = index * cfg.STAGGER_MS
                duration = cfg.CYCLE_MS
                easing = Easing.LINEAR
            }
        }
        animator.animate(animations)
    }

    private fun pingPong(fraction: Double): Double =
        if (fraction <= 0.5) fraction * 2.0 else (1.0 - fraction) * 2.0

    private class PulsingDot : JPanel() {
        var alpha: Double = ChatUIConstants.ThinkingIndicator.MIN_ALPHA
            set(value) {
                field = value.coerceIn(0.0, 1.0)
            }

        init {
            isOpaque = false
            val size = JBUI.scale(ChatUIConstants.ThinkingIndicator.DOT_DIAMETER)
            val spacing = JBUI.scale(ChatUIConstants.ThinkingIndicator.DOT_SPACING)
            preferredSize = Dimension(size + spacing, size)
        }

        override fun paintComponent(g: Graphics) {
            val g2d = g.create() as Graphics2D
            try {
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha.toFloat())
                g2d.color = ChatAppColors.Text.normal
                val size = JBUI.scale(ChatUIConstants.ThinkingIndicator.DOT_DIAMETER)
                val y = (height - size) / 2
                g2d.fillOval(0, y, size, size)
            } finally {
                g2d.dispose()
            }
        }
    }

    companion object {
        private const val WAITING_TICK_MS = 1000
        private const val WAITING_ELAPSED_THRESHOLD_S = 3
    }
}
