package se.alipsa.lca.gui

import se.alipsa.lca.shell.SessionState
import se.alipsa.lca.tools.ModelRegistry
import se.alipsa.lca.tools.TokenEstimator
import spock.lang.Specification

class ContextEstimatorSpec extends Specification {

  SessionState sessionState = Mock()
  TokenEstimator tokenEstimator = Mock()
  ModelRegistry modelRegistry = Mock()
  ContextEstimator estimator = new ContextEstimator(sessionState, tokenEstimator, modelRegistry, 98304)

  def "a reported context length is used and cached (queried once)"() {
    given:
    sessionState.defaultModel >> "m"

    when:
    int first = estimator.contextWindow("s")
    int second = estimator.contextWindow("s")

    then:
    1 * modelRegistry.contextLength("m") >> 131072
    first == 131072
    second == 131072
  }

  def "a fallback is NOT cached, so a later call re-queries and picks up the recovered size"() {
    given:
    sessionState.defaultModel >> "m"

    when:
    int first = estimator.contextWindow("s")
    int second = estimator.contextWindow("s")

    then:
    // First query misses (Ollama unreachable / model not pulled), second recovers.
    2 * modelRegistry.contextLength("m") >>> [null, 131072]
    first == 98304      // the fallback, uncached
    second == 131072    // re-queried once Ollama recovered
  }

  def "usedPercent is tokens over window, capped at 100"() {
    given:
    sessionState.defaultModel >> "m"
    modelRegistry.contextLength("m") >> 1000
    sessionState.history("s") >> ["aaa", "bbb"]
    tokenEstimator.estimate(_ as String) >> 300

    expect:
    // 600 tokens / 1000 window = 60%
    estimator.usedPercent("s") == 60
  }
}
