package se.alipsa.lca.gui

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import se.alipsa.lca.shell.SessionState
import se.alipsa.lca.tools.ModelRegistry
import se.alipsa.lca.tools.TokenEstimator

import java.util.concurrent.atomic.AtomicReference

/**
 * Best-effort estimate of how much of the model's context window the current conversation
 * occupies, for the footer gauge. Tokens are approximated from the session transcript with
 * {@link TokenEstimator}; the window size is taken from Ollama via
 * {@link ModelRegistry#contextLength} and falls back to {@code lca.gui.default-context-window}.
 *
 * <p>The resolved window is cached per model so the 2-second footer refresh does not query
 * Ollama repeatedly.
 */
@Component
@CompileStatic
class ContextEstimator {

  private static final String DEFAULT_CONTEXT_WINDOW = "131072"

  private final SessionState sessionState
  private final TokenEstimator tokenEstimator
  private final ModelRegistry modelRegistry
  private final int defaultContextWindow

  // A single AtomicReference, not two independent volatile fields, so a model+window pair is
  // always read/written atomically — two volatiles would let concurrent SwingWorkers interleave
  // writes for different models into a torn pair (e.g. model from B, window from A).
  private final AtomicReference<CachedWindow> cache = new AtomicReference<>()

  ContextEstimator(
    SessionState sessionState,
    TokenEstimator tokenEstimator,
    ModelRegistry modelRegistry,
    @Value('${lca.gui.default-context-window:' + DEFAULT_CONTEXT_WINDOW + '}') int defaultContextWindow
  ) {
    this.sessionState = sessionState
    this.tokenEstimator = tokenEstimator
    this.modelRegistry = modelRegistry
    this.defaultContextWindow = defaultContextWindow > 0 ? defaultContextWindow : Integer.parseInt(DEFAULT_CONTEXT_WINDOW)
  }

  int estimatedTokens(String sessionId) {
    int total = 0
    for (String entry : sessionState.history(sessionId)) {
      total += tokenEstimator.estimate(entry)
    }
    total
  }

  int contextWindow(String sessionId) {
    String model = sessionState.defaultModel
    CachedWindow snapshot = cache.get()
    if (model != null && snapshot != null && model == snapshot.model && snapshot.window > 0) {
      return snapshot.window
    }
    Integer reported = modelRegistry.contextLength(model)
    if (reported != null && reported > 0) {
      cache.set(new CachedWindow(model, reported))
      return reported
    }
    // Ollama did not report a size (unreachable, or the model is not pulled yet). Use the fallback
    // WITHOUT caching it, so a later refresh re-queries and picks up the real size once available.
    defaultContextWindow
  }

  int usedPercent(String sessionId) {
    int window = contextWindow(sessionId)
    if (window <= 0) {
      return 0
    }
    (int) Math.min(100L, Math.round((estimatedTokens(sessionId) * 100.0d) / window))
  }

  @Canonical
  @CompileStatic
  private static class CachedWindow {
    String model
    int window
  }
}
