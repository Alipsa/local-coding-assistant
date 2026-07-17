package se.alipsa.lca.gui

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import groovy.transform.PackageScope

import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingWorker
import java.awt.Component
import java.awt.Dimension
import java.util.concurrent.atomic.AtomicInteger

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
  // Guards against a slower earlier refreshAsync() tick overwriting a fresher one's result —
  // mirrors HeaderBar.populateBranches()'s generation counter for the same race.
  private final AtomicInteger refreshGeneration = new AtomicInteger(0)

  FooterBar(SystemMetrics systemMetrics, ContextEstimator contextEstimator, String sessionId) {
    this.systemMetrics = systemMetrics
    this.contextEstimator = contextEstimator
    this.sessionId = sessionId ?: "default"
    setLayout(new BoxLayout(this, BoxLayout.X_AXIS))
    setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8))
    contextBar.setStringPainted(true)
    memoryBar.setStringPainted(true)
    contextBar.setString("n/a")
    memoryBar.setString("n/a")
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
    // Never fetch synchronously here: on a cache miss, contextEstimator.usedPercent() chains
    // into a real HTTP call to Ollama (default 4s timeout), which would block the EDT — and the
    // very first refresh, right after construction, is a guaranteed cache miss.
    refreshAsync()
  }

  /**
   * Computes the metrics off the EDT (context estimation and, on the memory side, a possible
   * {@code vm_stat}/{@code /proc} subprocess spawn) and applies the result on the EDT once done —
   * so the periodic footer timer never blocks UI event handling. Mirrors the pattern
   * {@link HeaderBar#populateBranches} uses for git listing.
   */
  final void refreshAsync() {
    int generation = beginRefreshGeneration()
    new SwingWorker<FooterSnapshot, Void>() {
      @Override
      protected FooterSnapshot doInBackground() {
        collectSnapshot()
      }

      @Override
      protected void done() {
        if (!isCurrentRefreshGeneration(generation)) {
          return
        }
        try {
          apply(get())
        } catch (Exception ignored) {
          contextBar.setString("n/a")
          memoryBar.setString("n/a")
        }
      }
    }.execute()
  }

  /** Starts a new refresh generation, superseding any still-running earlier one. */
  @PackageScope
  int beginRefreshGeneration() {
    refreshGeneration.incrementAndGet()
  }

  /** Whether {@code generation} is still the most recently started refresh call. */
  @PackageScope
  boolean isCurrentRefreshGeneration(int generation) {
    generation == refreshGeneration.get()
  }

  private FooterSnapshot collectSnapshot() {
    Integer contextPercent = null
    try {
      contextPercent = contextEstimator.usedPercent(sessionId)
    } catch (Exception ignored) {
      // contextPercent stays null; apply() renders "n/a" for it.
    }
    Integer memoryPercent = null
    String memorySummary = null
    try {
      memoryPercent = systemMetrics.usedMemoryPercent()
      memorySummary = systemMetrics.memorySummary()
    } catch (Exception ignored) {
      // memoryPercent/memorySummary stay null; apply() renders "n/a" for them.
    }
    new FooterSnapshot(contextPercent, memoryPercent, memorySummary)
  }

  private void apply(FooterSnapshot snapshot) {
    if (snapshot.contextPercent != null) {
      contextBar.setValue(snapshot.contextPercent)
      contextBar.setString("${snapshot.contextPercent}%")
    } else {
      contextBar.setString("n/a")
    }
    if (snapshot.memoryPercent != null) {
      memoryBar.setValue(snapshot.memoryPercent)
      memoryBar.setString(snapshot.memorySummary)
    } else {
      memoryBar.setString("n/a")
    }
  }

  private static Component separator() {
    new JLabel("     |     ")
  }

  /** Immutable snapshot collected off the EDT and applied on it; a null field means "failed". */
  @Canonical
  @CompileStatic
  private static class FooterSnapshot {
    Integer contextPercent
    Integer memoryPercent
    String memorySummary
  }
}
