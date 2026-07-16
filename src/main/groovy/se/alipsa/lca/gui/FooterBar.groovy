package se.alipsa.lca.gui

import groovy.transform.CompileStatic

import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import java.awt.Component
import java.awt.Dimension

/**
 * The bottom metrics strip. Context-window usage and host RAM are live (best-effort);
 * autocompact and GPU memory are shown as {@code n/a} until backing features exist
 * (see {@code docs/gui.md}).
 */
@CompileStatic
class FooterBar extends JPanel {

  private final SystemMetrics systemMetrics
  private final ContextEstimator contextEstimator
  private final String sessionId

  private final JProgressBar contextBar = new JProgressBar(0, 100)
  private final JProgressBar memoryBar = new JProgressBar(0, 100)
  private final JLabel autoCompactLabel = new JLabel("Autocompact: n/a")
  private final JLabel gpuLabel = new JLabel("GPU memory: n/a")

  FooterBar(SystemMetrics systemMetrics, ContextEstimator contextEstimator, String sessionId) {
    this.systemMetrics = systemMetrics
    this.contextEstimator = contextEstimator
    this.sessionId = sessionId ?: "default"
    setLayout(new BoxLayout(this, BoxLayout.X_AXIS))
    setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8))
    contextBar.setStringPainted(true)
    memoryBar.setStringPainted(true)
    contextBar.setMaximumSize(new Dimension(160, 18))
    memoryBar.setMaximumSize(new Dimension(200, 18))
    add(new JLabel("Context: "))
    add(contextBar)
    add(separator())
    add(autoCompactLabel)
    add(separator())
    add(new JLabel("Main memory: "))
    add(memoryBar)
    add(separator())
    add(gpuLabel)
    refresh()
  }

  final void refresh() {
    try {
      int ctx = contextEstimator.usedPercent(sessionId)
      contextBar.setValue(ctx)
      contextBar.setString("${ctx}%")
    } catch (Exception ignored) {
      contextBar.setString("n/a")
    }
    try {
      memoryBar.setValue(systemMetrics.usedMemoryPercent())
      memoryBar.setString(systemMetrics.memorySummary())
    } catch (Exception ignored) {
      memoryBar.setString("n/a")
    }
  }

  private static Component separator() {
    new JLabel("     |     ")
  }
}
