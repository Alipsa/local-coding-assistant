package se.alipsa.lca.gui

import se.alipsa.lca.shell.ContextCompactor
import se.alipsa.lca.tools.ModelRegistry
import spock.lang.Specification
import spock.lang.Unroll
import spock.util.concurrent.PollingConditions

import javax.swing.JLabel
import javax.swing.JProgressBar

class FooterBarSpec extends Specification {

  SystemMetrics systemMetrics = Mock()
  ContextEstimator contextEstimator = Mock()
  ModelRegistry modelRegistry = Mock()
  // Unstubbed isAutocompactEnabled() returns false by default (Mock sensible-default for
  // boolean), so existing tests that don't care about autocompact need no explicit interaction.
  ContextCompactor contextCompactor = Mock()
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

  private static String autocompactText(FooterBar footer) {
    ((JLabel) footer.components.find {
      it instanceof JLabel && ((JLabel) it).text?.startsWith("Autocompact:")
    })?.text
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
    FooterBar footer = new FooterBar(systemMetrics, contextEstimator, modelRegistry, contextCompactor, "default")

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
    FooterBar footer = new FooterBar(systemMetrics, contextEstimator, modelRegistry, contextCompactor, "default")
    footer.refreshAsync()

    then:
    conditions.eventually {
      contextText(footer) == "n/a"
      memoryText(footer) == "2 / 8 Gb"
    }
  }

  def "a fresh refreshAsync call supersedes the generation an earlier, still-running one captured"() {
    given:
    FooterBar footer = new FooterBar(systemMetrics, contextEstimator, modelRegistry, contextCompactor, "default")

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

  def "the footer bar no longer shows pipe separators between segments"() {
    given:
    FooterBar footer = new FooterBar(systemMetrics, contextEstimator, modelRegistry, contextCompactor, "default")

    expect:
    footer.components.findAll { it instanceof JLabel }.every { !((JLabel) it).text?.contains("|") }
  }

  @Unroll
  def "memoryDisplayFor(remote=#remote, localPercent=#localPercent) -> (#expectedPercent, '#expectedText')"() {
    expect:
    FooterBar.MemoryDisplay display = FooterBar.memoryDisplayFor(remote, localPercent, localSummary, loaded)
    display.percent == expectedPercent
    display.text == expectedText

    where:
    remote | localPercent | localSummary | loaded                                                       || expectedPercent | expectedText
    false  | 55           | "4 / 8 Gb"   | []                                                           || 55              | "4 / 8 Gb"
    false  | null         | null         | []                                                           || null            | "n/a"
    true   | null         | null         | [new ModelRegistry.LoadedModel("m1", 5_000_000_000L, 0L)]   || null            | "5 Gb (Ollama)"
    true   | null         | null         | []                                                           || null            | "n/a"
    true   | null         | null         | [new ModelRegistry.LoadedModel("m1", 0L, 0L)]               || null            | "n/a"
  }

  @Unroll
  def "gpuLabelFor(#loaded) == '#expected'"() {
    expect:
    FooterBar.gpuLabelFor(loaded) == expected

    where:
    loaded                                                                       | expected
    []                                                                            | "GPU memory: n/a"
    [new ModelRegistry.LoadedModel("m1", 5_000_000_000L, 0L)]                    | "GPU memory: n/a"
    [new ModelRegistry.LoadedModel("m1", 5_000_000_000L, 3_000_000_000L)]        | "GPU memory: 3 Gb"
  }

  def "a remote Ollama with no loaded models shows n/a instead of local host stats"() {
    given:
    systemMetrics.usedMemoryPercent() >> 55
    systemMetrics.memorySummary() >> "4 / 8 Gb"
    modelRegistry.isRemote() >> true
    modelRegistry.loadedModels() >> []

    when:
    FooterBar footer = new FooterBar(systemMetrics, contextEstimator, modelRegistry, contextCompactor, "default")
    footer.refreshAsync()

    then:
    conditions.eventually {
      memoryText(footer) == "n/a"
      footer.components.find { it instanceof JLabel && ((JLabel) it).text == "GPU memory: n/a" } != null
    }
  }

  def "a remote Ollama with a loaded model shows its reported memory, not local host stats"() {
    given:
    systemMetrics.usedMemoryPercent() >> 55
    systemMetrics.memorySummary() >> "4 / 8 Gb"
    modelRegistry.isRemote() >> true
    modelRegistry.loadedModels() >> [new ModelRegistry.LoadedModel("m1", 5_000_000_000L, 3_000_000_000L)]

    when:
    FooterBar footer = new FooterBar(systemMetrics, contextEstimator, modelRegistry, contextCompactor, "default")
    footer.refreshAsync()

    then:
    conditions.eventually {
      memoryText(footer)?.contains("(Ollama)")
      footer.components.find { it instanceof JLabel && ((JLabel) it).text == "GPU memory: 3 Gb" } != null
    }
  }

  def "a local Ollama keeps showing local host stats unchanged"() {
    given:
    systemMetrics.usedMemoryPercent() >> 55
    systemMetrics.memorySummary() >> "4 / 8 Gb"
    modelRegistry.isRemote() >> false
    modelRegistry.loadedModels() >> []

    when:
    FooterBar footer = new FooterBar(systemMetrics, contextEstimator, modelRegistry, contextCompactor, "default")
    footer.refreshAsync()

    then:
    conditions.eventually {
      memoryText(footer) == "4 / 8 Gb"
    }
  }

  @Unroll
  def "autocompactLabelFor(enabled=#enabled, progress=#progress) == '#expected'"() {
    expect:
    FooterBar.autocompactLabelFor(enabled, progress) == expected

    where:
    enabled | progress || expected
    false   | null     || "Autocompact: disabled"
    false   | 42       || "Autocompact: disabled"
    true    | null     || "Autocompact: n/a"
    true    | 42       || "Autocompact: 42%"
    true    | 100      || "Autocompact: 100%"
  }

  def "the footer shows autocompact progress when enabled"() {
    given:
    contextCompactor.isAutocompactEnabled() >> true
    contextCompactor.autocompactProgressPercent("default") >> 55

    when:
    FooterBar footer = new FooterBar(systemMetrics, contextEstimator, modelRegistry, contextCompactor, "default")
    footer.refreshAsync()

    then:
    conditions.eventually {
      autocompactText(footer) == "Autocompact: 55%"
    }
  }

  def "the footer shows autocompact as disabled when turned off via config"() {
    given:
    contextCompactor.isAutocompactEnabled() >> false

    when:
    FooterBar footer = new FooterBar(systemMetrics, contextEstimator, modelRegistry, contextCompactor, "default")
    footer.refreshAsync()

    then:
    conditions.eventually {
      autocompactText(footer) == "Autocompact: disabled"
    }
    0 * contextCompactor.autocompactProgressPercent(_)
  }

  def "a remote Ollama never samples local host memory, since it would be discarded anyway"() {
    given:
    modelRegistry.isRemote() >> true
    modelRegistry.loadedModels() >> [new ModelRegistry.LoadedModel("m1", 5_000_000_000L, 0L)]

    when:
    FooterBar footer = new FooterBar(systemMetrics, contextEstimator, modelRegistry, contextCompactor, "default")
    footer.refreshAsync()

    then:
    conditions.eventually {
      memoryText(footer)?.contains("(Ollama)")
    }
    0 * systemMetrics.usedMemoryPercent()
    0 * systemMetrics.memorySummary()
  }
}
