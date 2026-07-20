package se.alipsa.lca.gui

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import se.alipsa.lca.shell.ContextCompactor
import se.alipsa.lca.tools.ModelRegistry

import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingWorker
import java.awt.Component
import java.awt.Dimension
import java.util.concurrent.atomic.AtomicInteger

/**
 * The bottom metrics strip. Context-window usage, host RAM, and autocompact progress are all live
 * (best-effort). "Main memory" and "GPU memory" source from local host stats ({@link SystemMetrics})
 * only when Ollama is local — when {@code spring.ai.ollama.base-url} points at a remote server,
 * local host stats wouldn't represent the machine actually running inference, so both switch to
 * what Ollama itself reports about its currently loaded models ({@link ModelRegistry#loadedModels}),
 * or {@code n/a} when Ollama doesn't report anything useful. "Autocompact" shows progress toward
 * {@link ContextCompactor}'s auto-compact trigger threshold, or "disabled" when auto-compact is
 * turned off via config.
 */
@CompileStatic
class FooterBar extends JPanel {

  private static final int SEGMENT_GAP = 12

  private final SystemMetrics systemMetrics
  private final ContextEstimator contextEstimator
  private final ModelRegistry modelRegistry
  private final ContextCompactor contextCompactor
  private final String sessionId

  private final JProgressBar contextBar = new JProgressBar(0, 100)
  private final JProgressBar memoryBar = new JProgressBar(0, 100)
  private final JLabel autoCompactLabel = new JLabel("Autocompact: n/a")
  private final JLabel gpuLabel = new JLabel("GPU memory: n/a")
  // Guards against a slower earlier refreshAsync() tick overwriting a fresher one's result —
  // mirrors HeaderBar.populateBranches()'s generation counter for the same race.
  private final AtomicInteger refreshGeneration = new AtomicInteger(0)

  FooterBar(SystemMetrics systemMetrics, ContextEstimator contextEstimator, ModelRegistry modelRegistry,
            ContextCompactor contextCompactor, String sessionId) {
    this.systemMetrics = systemMetrics
    this.contextEstimator = contextEstimator
    this.modelRegistry = modelRegistry
    this.contextCompactor = contextCompactor
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
    add(spacer())
    add(autoCompactLabel)
    add(spacer())
    add(new JLabel("Main memory: "))
    add(memoryBar)
    add(spacer())
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
    // remote is I/O-free (fixed at ModelRegistry construction), so it's resolved first: local
    // host memory is only sampled when it would actually be shown — memoryDisplayFor() discards
    // it entirely when remote, so sampling it unconditionally on every 2s footer tick would be
    // wasted vm_stat/proc work in the (now more common) remote-Ollama case.
    boolean remote = false
    try {
      remote = modelRegistry != null && modelRegistry.isRemote()
    } catch (Exception ignored) {
      // remote stays false: isRemote() is I/O-free and shouldn't throw; degrade toward today's
      // local-stats display if it somehow does.
    }
    Integer memoryPercent = null
    String memorySummary = null
    if (!remote) {
      try {
        memoryPercent = systemMetrics.usedMemoryPercent()
        memorySummary = systemMetrics.memorySummary()
      } catch (Exception ignored) {
        // memoryPercent/memorySummary stay null; apply() renders "n/a" for them.
      }
    }
    // A SEPARATE try/catch from isRemote() above, deliberately: a loadedModels() failure while
    // remote is true must degrade to "n/a" (via memoryDisplayFor), never fall back to local
    // stats — showing local numbers for a remote Ollama server is the exact bug being fixed, and
    // merging these into one catch would silently reintroduce it on a transient connectivity blip.
    List<ModelRegistry.LoadedModel> loaded = List.of()
    try {
      loaded = modelRegistry != null ? modelRegistry.loadedModels() : List.of()
    } catch (Exception ignored) {
      // loaded stays empty: memoryDisplayFor renders "n/a" when remote, never local stats.
    }
    boolean autocompactEnabled = false
    Integer autocompactProgress = null
    boolean autocompactCheckFailed = false
    try {
      autocompactEnabled = contextCompactor != null && contextCompactor.isAutocompactEnabled()
      if (autocompactEnabled) {
        autocompactProgress = contextCompactor.autocompactProgressPercent(sessionId)
      }
    } catch (Exception ignored) {
      // A thrown check is not the same as the user having turned autocompact off via config, so
      // it's tracked separately — autocompactLabelFor() renders "n/a" for this, never "disabled".
      autocompactCheckFailed = true
    }
    new FooterSnapshot(contextPercent, memoryPercent, memorySummary, remote, loaded,
      autocompactEnabled, autocompactProgress, autocompactCheckFailed)
  }

  private void apply(FooterSnapshot snapshot) {
    if (snapshot.contextPercent != null) {
      contextBar.setValue(snapshot.contextPercent)
      contextBar.setString("${snapshot.contextPercent}%")
    } else {
      contextBar.setString("n/a")
    }
    MemoryDisplay memoryDisplay = memoryDisplayFor(
      snapshot.remote, snapshot.memoryPercent, snapshot.memorySummary, snapshot.loadedModels)
    memoryBar.setValue(memoryDisplay.percent != null ? memoryDisplay.percent : 0)
    memoryBar.setString(memoryDisplay.text)
    memoryBar.setToolTipText(memoryDisplay.tooltip)
    gpuLabel.setText(gpuLabelFor(snapshot.loadedModels))
    autoCompactLabel.setText(autocompactLabelFor(
      snapshot.autocompactEnabled, snapshot.autocompactProgress, snapshot.autocompactCheckFailed))
  }

  /**
   * The "Main memory" bar's percent/text/tooltip. Local host stats are only representative of
   * the machine actually running inference when Ollama is local; when remote, source memory
   * from what Ollama itself reports about its currently loaded models instead — an absolute byte
   * count (or {@code n/a}) rather than a percent, since {@code /api/ps} reports what's loaded,
   * never the remote host's total memory, so any derived percentage would be exactly as invented
   * as the local numbers this is meant to replace. Pure/static so it's unit-testable without
   * Swing or HTTP mocking, mirroring {@code HeaderBar}'s {@code branchItemsFor}/{@code
   * pullRequestLabelFor} helpers.
   */
  static MemoryDisplay memoryDisplayFor(boolean remote, Integer localPercent, String localSummary,
                                         List<ModelRegistry.LoadedModel> loaded) {
    if (!remote) {
      return localPercent != null
        ? new MemoryDisplay(localPercent, localSummary, "This machine's memory")
        : new MemoryDisplay(null, "n/a", "This machine's memory")
    }
    long totalBytes = 0L
    for (ModelRegistry.LoadedModel model : (loaded ?: List.<ModelRegistry.LoadedModel> of())) {
      totalBytes += model.size
    }
    totalBytes > 0L
      ? new MemoryDisplay(null, "${SystemMetrics.formatGb(totalBytes)} Gb (Ollama)".toString(),
          "Reported by Ollama for its currently loaded models (remote host)")
      : new MemoryDisplay(null, "n/a", "Ollama did not report any loaded models")
  }

  /**
   * GPU memory reported by Ollama for its currently loaded models — works whether Ollama is
   * local or remote, since it's always the Ollama server's own report of VRAM usage, not a
   * host-level GPU query (which {@link SystemMetrics} notes has no portable source).
   */
  static String gpuLabelFor(List<ModelRegistry.LoadedModel> loaded) {
    long vramBytes = 0L
    for (ModelRegistry.LoadedModel model : (loaded ?: List.<ModelRegistry.LoadedModel> of())) {
      vramBytes += model.sizeVram
    }
    vramBytes > 0L
      ? "GPU memory: ${SystemMetrics.formatGb(vramBytes)} Gb".toString()
      : "GPU memory: n/a"
  }

  /**
   * The "Autocompact" label's text: "disabled" only when the config flag itself is off; "n/a"
   * when the enabled/progress check threw (a transient failure, not a deliberate config choice —
   * conflating the two would misreport a bug as a setting); otherwise a percent when known, or
   * "n/a" when enabled but the progress estimate itself failed. Pure/static, mirroring
   * {@code gpuLabelFor}/{@code memoryDisplayFor}.
   */
  static String autocompactLabelFor(boolean enabled, Integer progress, boolean checkFailed) {
    if (checkFailed) {
      return "Autocompact: n/a"
    }
    if (!enabled) {
      return "Autocompact: disabled"
    }
    progress != null ? "Autocompact: ${progress}%".toString() : "Autocompact: n/a"
  }

  private static Component spacer() {
    Box.createHorizontalStrut(SEGMENT_GAP)
  }

  /** Immutable snapshot collected off the EDT and applied on it; a null field means "failed". */
  @Canonical
  @CompileStatic
  private static class FooterSnapshot {
    Integer contextPercent
    Integer memoryPercent
    String memorySummary
    boolean remote
    List<ModelRegistry.LoadedModel> loadedModels
    boolean autocompactEnabled
    Integer autocompactProgress
    boolean autocompactCheckFailed
  }

  /** The "Main memory" bar's resolved display: a null {@code percent} paints the bar empty. */
  @Canonical
  @CompileStatic
  static class MemoryDisplay {
    Integer percent
    String text
    String tooltip
  }
}
