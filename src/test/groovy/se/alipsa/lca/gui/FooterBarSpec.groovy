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

  def "refreshAsync computes metrics off the EDT and applies them once done"() {
    given:
    // The constructor itself calls refresh() synchronously once; give it a distinct value from
    // the one refreshAsync() should apply, so the assertion can only pass via the async path.
    int contextCalls = 0
    contextEstimator.usedPercent("default") >> { contextCalls++ == 0 ? 0 : 42 }
    int memoryCalls = 0
    systemMetrics.usedMemoryPercent() >> { memoryCalls == 0 ? 0 : 55 }
    systemMetrics.memorySummary() >> { memoryCalls++ == 0 ? "0 / 8 Gb" : "4 / 8 Gb" }

    when:
    FooterBar footer = new FooterBar(systemMetrics, contextEstimator, "default")
    footer.refreshAsync()

    then:
    conditions.eventually {
      contextText(footer) == "42%"
      memoryText(footer) == "4 / 8 Gb"
    }
  }

  def "a failure in one metric on refreshAsync falls back to n/a without affecting the other"() {
    given:
    // First (constructor) call succeeds so the failure below is attributable to refreshAsync().
    int contextCalls = 0
    contextEstimator.usedPercent("default") >> {
      if (contextCalls++ == 0) {
        return 10
      }
      throw new RuntimeException("boom")
    }
    int memoryCalls = 0
    systemMetrics.usedMemoryPercent() >> { memoryCalls == 0 ? 1 : 20 }
    systemMetrics.memorySummary() >> { memoryCalls++ == 0 ? "1 / 8 Gb" : "2 / 8 Gb" }

    when:
    FooterBar footer = new FooterBar(systemMetrics, contextEstimator, "default")
    footer.refreshAsync()

    then:
    conditions.eventually {
      contextText(footer) == "n/a"
      memoryText(footer) == "2 / 8 Gb"
    }
  }
}
