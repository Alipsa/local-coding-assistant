package se.alipsa.lca.gui

import se.alipsa.lca.shell.SessionState
import se.alipsa.lca.tools.ModelRegistry
import se.alipsa.lca.tools.TokenEstimator
import spock.lang.Specification

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

  def "concurrent contextWindow calls for different models never observe a torn (model, window) pair"() {
    given:
    // Binds each caller's thread to a fixed model, standing in for two SwingWorker ticks that
    // land while the session's default model differs between them.
    ThreadLocal<String> threadModel = new ThreadLocal<>()
    sessionState.defaultModel >> { threadModel.get() }
    modelRegistry.contextLength("modelA") >> 1111
    modelRegistry.contextLength("modelB") >> 2222
    List<Integer> observedForA = new CopyOnWriteArrayList<>()
    List<Integer> observedForB = new CopyOnWriteArrayList<>()
    CountDownLatch start = new CountDownLatch(1)
    int iterations = 500

    Thread threadA = Thread.start {
      threadModel.set("modelA")
      start.await()
      iterations.times { observedForA << estimator.contextWindow("s") }
    }
    Thread threadB = Thread.start {
      threadModel.set("modelB")
      start.await()
      iterations.times { observedForB << estimator.contextWindow("s") }
    }

    when:
    start.countDown()
    threadA.join(5000)
    threadB.join(5000)

    then:
    // A torn read would surface here as an observed 2222 for modelA (or vice versa) — impossible
    // once the cache is a single AtomicReference<CachedWindow> written/read as one unit.
    observedForA.every { it == 1111 }
    observedForB.every { it == 2222 }
  }
}
