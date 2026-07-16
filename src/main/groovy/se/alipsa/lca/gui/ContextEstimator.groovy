package se.alipsa.lca.gui

import groovy.transform.CompileStatic
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import se.alipsa.lca.shell.SessionState
import se.alipsa.lca.tools.ModelRegistry
import se.alipsa.lca.tools.TokenEstimator

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

  private final SessionState sessionState
  private final TokenEstimator tokenEstimator
  private final ModelRegistry modelRegistry
  private final int defaultContextWindow

  private volatile String cachedModel
  private volatile int cachedWindow

  ContextEstimator(
    SessionState sessionState,
    TokenEstimator tokenEstimator,
    ModelRegistry modelRegistry,
    @Value('${lca.gui.default-context-window:98304}') int defaultContextWindow
  ) {
    this.sessionState = sessionState
    this.tokenEstimator = tokenEstimator
    this.modelRegistry = modelRegistry
    this.defaultContextWindow = defaultContextWindow > 0 ? defaultContextWindow : 98304
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
    if (model != null && model == cachedModel && cachedWindow > 0) {
      return cachedWindow
    }
    Integer reported = modelRegistry.contextLength(model)
    int window = (reported != null && reported > 0) ? reported : defaultContextWindow
    cachedModel = model
    cachedWindow = window
    window
  }

  int usedPercent(String sessionId) {
    int window = contextWindow(sessionId)
    if (window <= 0) {
      return 0
    }
    (int) Math.min(100L, Math.round((estimatedTokens(sessionId) * 100.0d) / window))
  }
}
