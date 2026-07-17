package se.alipsa.lca.gui

import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import javax.swing.JProgressBar

class FooterBarSpec extends Specification {

  SystemMetrics systemMetrics = Mock()
  ContextEstimator contextEstimator = Mock()
  PollingConditions conditions = new PollingConditions(timeout: 2)

  private static String contextText(FooterBar footer) {
    progressBars(footer)[0].string
  }

  private static String memoryText(FooterBar footer) {
    progressBars(footer)[1].string
  }

  private static List<JProgressBar> progressBars(FooterBar footer) {
    footer.components.findAll { it instanceof JProgressBar } as List<JProgressBar>
  }

  def "construction fetches a slow metric off the calling thread, then the result arrives asynchronously"() {
    given:
    // Stands in for ModelRegistry.contextLength()'s real HTTP call on a cache miss (up to a 4s
    // timeout) — the exact call the constructor used to make synchronously before this fix.
    // Wall-clock thresholds are unreliable here (Swing/SwingWorker's one-time JVM warm-up cost
    // can itself run into the hundreds of ms), so assert the non-blocking property directly:
    // the slow call must not run on the thread that invoked the constructor.
    Thread callingThread = Thread.currentThread()
    Thread stubThread = null
    contextEstimator.usedPercent("default") >> {
      stubThread = Thread.currentThread()
      Thread.sleep(300)
      42
    }
    systemMetrics.usedMemoryPercent() >> 55
    systemMetrics.memorySummary() >> "4 / 8 Gb"

    when:
    FooterBar footer = new FooterBar(systemMetrics, contextEstimator, "default")

    then: "the slow metric is fetched off the calling thread, not blocking construction"
    conditions.eventually {
      stubThread != null
    }
    stubThread != callingThread

    and: "the slow metric still eventually arrives, applied asynchronously"
    conditions.eventually {
      contextText(footer) == "42%"
      memoryText(footer) == "4 / 8 Gb"
    }
  }

  def "a failure in one metric on refreshAsync falls back to n/a without affecting the other"() {
    given:
    contextEstimator.usedPercent("default") >> { throw new RuntimeException("boom") }
    systemMetrics.usedMemoryPercent() >> 20
    systemMetrics.memorySummary() >> "2 / 8 Gb"

    when:
    FooterBar footer = new FooterBar(systemMetrics, contextEstimator, "default")
    footer.refreshAsync()

    then:
    conditions.eventually {
      contextText(footer) == "n/a"
      memoryText(footer) == "2 / 8 Gb"
    }
  }

  def "a fresh refreshAsync call supersedes the generation an earlier, still-running one captured"() {
    given:
    FooterBar footer = new FooterBar(systemMetrics, contextEstimator, "default")

    when: "an earlier tick's worker captures its generation before a second tick starts"
    int firstGeneration = footer.beginRefreshGeneration()

    then:
    footer.isCurrentRefreshGeneration(firstGeneration)

    when: "a second, later tick starts before the first worker finishes"
    int secondGeneration = footer.beginRefreshGeneration()

    then: "the first worker's captured generation is now stale and must not apply its result"
    !footer.isCurrentRefreshGeneration(firstGeneration)
    footer.isCurrentRefreshGeneration(secondGeneration)
  }
}
