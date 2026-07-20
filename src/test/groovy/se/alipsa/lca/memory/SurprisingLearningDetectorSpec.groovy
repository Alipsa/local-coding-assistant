package se.alipsa.lca.memory

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.PromptRunner
import com.embabel.common.ai.model.ByNameModelSelectionCriteria
import com.embabel.common.ai.model.LlmOptions
import spock.lang.Specification
import spock.lang.Unroll

class SurprisingLearningDetectorSpec extends Specification {

  MemoryStore memoryStore = Mock(MemoryStore)
  MemorySettings settings = new MemorySettings()
  ProjectScopeResolver projectScopeResolver = Mock(ProjectScopeResolver) {
    currentProjectId() >> "proj-1"
  }

  private SurprisingLearningDetector detectorWithFallback(String fallbackModel = "fallback-model") {
    new SurprisingLearningDetector(memoryStore, settings, projectScopeResolver, fallbackModel)
  }

  def "does nothing when memory is disabled"() {
    given:
    settings.enabled = false
    SurprisingLearningDetector detector = detectorWithFallback()
    Ai ai = Mock(Ai)

    when:
    detector.maybeRemember("actually, that's wrong", "I don't know", "session-1", ai)

    then:
    0 * ai._
    0 * memoryStore.remember(_, _, _)
  }

  def "does nothing when surprising-learning detection is disabled"() {
    given:
    settings.surprisingLearningDetectionEnabled = false
    SurprisingLearningDetector detector = detectorWithFallback()
    Ai ai = Mock(Ai)

    when:
    detector.maybeRemember("actually, that's wrong", "I don't know", "session-1", ai)

    then:
    0 * ai._
    0 * memoryStore.remember(_, _, _)
  }

  def "never calls the LLM when there is no heuristic match"() {
    given:
    SurprisingLearningDetector detector = detectorWithFallback()
    Ai ai = Mock(Ai)

    when:
    detector.maybeRemember("what does this method do", "It parses the input and returns a result.", "session-1", ai)

    then:
    0 * ai._
    0 * memoryStore.remember(_, _, _)
  }

  @Unroll
  def "heuristic-matching reply/input triggers the classifier: #description"() {
    given:
    SurprisingLearningDetector detector = detectorWithFallback()
    Ai ai = Mock(Ai)
    PromptRunner runner = Mock(PromptRunner)
    def verdict = new SurprisingLearningDetector.SurpriseVerdict(false, null)

    when:
    detector.maybeRemember(userInput, assistantReply, "session-1", ai)

    then:
    1 * ai.withLlm(_ as LlmOptions) >> runner
    1 * runner.createObject(_ as String, SurprisingLearningDetector.SurpriseVerdict) >> verdict

    where:
    description                | userInput                 | assistantReply
    "hedging reply"            | "what year is it"         | "I don't know the current year."
    "alternate hedging phrase" | "who owns this repo"      | "I'm not sure who owns it."
    "correction marker"        | "actually, that's wrong"  | "Understood, thanks for the correction."
  }

  def "surprising=false does not store a memory"() {
    given:
    SurprisingLearningDetector detector = detectorWithFallback()
    Ai ai = Mock(Ai)
    PromptRunner runner = Mock(PromptRunner)
    ai.withLlm(_ as LlmOptions) >> runner
    runner.createObject(_ as String, SurprisingLearningDetector.SurpriseVerdict) >>
      new SurprisingLearningDetector.SurpriseVerdict(false, null)

    when:
    detector.maybeRemember("actually, that's wrong", "noted", "session-1", ai)

    then:
    0 * memoryStore.remember(_, _, _)
  }

  def "surprising=true with a fact stores it, always project-scoped"() {
    given:
    SurprisingLearningDetector detector = detectorWithFallback()
    Ai ai = Mock(Ai)
    PromptRunner runner = Mock(PromptRunner)
    ai.withLlm(_ as LlmOptions) >> runner
    runner.createObject(_ as String, SurprisingLearningDetector.SurpriseVerdict) >>
      new SurprisingLearningDetector.SurpriseVerdict(true, "the build uses Groovy 5.0.3")

    when:
    detector.maybeRemember("actually, that's wrong", "noted", "session-1", ai)

    then:
    1 * memoryStore.remember("the build uses Groovy 5.0.3", "session-1", "proj-1")
  }

  def "surprising=true with a blank fact does not store a memory"() {
    given:
    SurprisingLearningDetector detector = detectorWithFallback()
    Ai ai = Mock(Ai)
    PromptRunner runner = Mock(PromptRunner)
    ai.withLlm(_ as LlmOptions) >> runner
    runner.createObject(_ as String, SurprisingLearningDetector.SurpriseVerdict) >>
      new SurprisingLearningDetector.SurpriseVerdict(true, "   ")

    when:
    detector.maybeRemember("actually, that's wrong", "noted", "session-1", ai)

    then:
    0 * memoryStore.remember(_, _, _)
  }

  def "uses surprisingLearningModel override instead of the fallback model when set"() {
    given:
    settings.surprisingLearningModel = "override-model"
    SurprisingLearningDetector detector = detectorWithFallback("fallback-model")
    Ai ai = Mock(Ai)
    PromptRunner runner = Mock(PromptRunner)

    when:
    detector.maybeRemember("actually, that's wrong", "noted", "session-1", ai)

    then:
    1 * ai.withLlm({ LlmOptions options ->
      (options.modelSelectionCriteria as ByNameModelSelectionCriteria).name == "override-model"
    }) >> runner
    1 * runner.createObject(_, SurprisingLearningDetector.SurpriseVerdict) >>
      new SurprisingLearningDetector.SurpriseVerdict(false, null)
  }

  def "does nothing when no model is configured or available as a fallback"() {
    given:
    SurprisingLearningDetector detector = detectorWithFallback(null)
    Ai ai = Mock(Ai)

    when:
    detector.maybeRemember("actually, that's wrong", "noted", "session-1", ai)

    then:
    0 * ai._
    0 * memoryStore.remember(_, _, _)
  }
}
