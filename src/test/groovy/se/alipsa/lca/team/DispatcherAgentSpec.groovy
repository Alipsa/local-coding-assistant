package se.alipsa.lca.team

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.PromptRunner
import spock.lang.Specification

class DispatcherAgentSpec extends Specification {

  Ai ai = Mock()
  PromptRunner promptRunner = Mock()
  TeamSettings settings = new TeamSettings(false, "model", "model", "model", 0.1d, true)

  def "null prompt is classified as simple without LLM call"() {
    given:
    DispatcherAgent dispatcher = new DispatcherAgent(ai, settings)

    when:
    DispatcherAgent.DispatchResult result = dispatcher.classify(null)

    then:
    !result.complex
    0 * ai._
  }

  def "empty prompt is classified as simple without LLM call"() {
    given:
    DispatcherAgent dispatcher = new DispatcherAgent(ai, settings)

    when:
    DispatcherAgent.DispatchResult result = dispatcher.classify("")

    then:
    !result.complex
    0 * ai._
  }

  def "blank prompt is classified as simple without LLM call"() {
    given:
    DispatcherAgent dispatcher = new DispatcherAgent(ai, settings)

    when:
    DispatcherAgent.DispatchResult result = dispatcher.classify("   ")

    then:
    !result.complex
    0 * ai._
  }

  def "LLM returning SIMPLE classifies as simple"() {
    given:
    ai.withLlm(_) >> promptRunner
    promptRunner.generateText(_) >> "SIMPLE: straightforward single-file change"
    DispatcherAgent dispatcher = new DispatcherAgent(ai, settings)

    when:
    DispatcherAgent.DispatchResult result = dispatcher.classify("add a logger")

    then:
    !result.complex
    result.reason == "straightforward single-file change"
  }

  def "LLM returning COMPLEX classifies as complex"() {
    given:
    ai.withLlm(_) >> promptRunner
    promptRunner.generateText(_) >> "COMPLEX: multi-file refactoring needed"
    DispatcherAgent dispatcher = new DispatcherAgent(ai, settings)

    when:
    DispatcherAgent.DispatchResult result = dispatcher.classify("refactor authentication")

    then:
    result.complex
    result.reason == "multi-file refactoring needed"
  }

  def "unparseable LLM response defaults to complex"() {
    given:
    ai.withLlm(_) >> promptRunner
    promptRunner.generateText(_) >> "I'm not sure what you mean"
    DispatcherAgent dispatcher = new DispatcherAgent(ai, settings)

    when:
    DispatcherAgent.DispatchResult result = dispatcher.classify("do something")

    then:
    result.complex
    result.reason.contains("Unparseable")
  }

  def "LLM exception defaults to complex"() {
    given:
    ai.withLlm(_) >> { throw new RuntimeException("LLM unavailable") }
    DispatcherAgent dispatcher = new DispatcherAgent(ai, settings)

    when:
    DispatcherAgent.DispatchResult result = dispatcher.classify("add a method")

    then:
    result.complex
    result.reason.contains("LLM error")
  }

  def "empty LLM response defaults to complex"() {
    given:
    ai.withLlm(_) >> promptRunner
    promptRunner.generateText(_) >> ""
    DispatcherAgent dispatcher = new DispatcherAgent(ai, settings)

    when:
    DispatcherAgent.DispatchResult result = dispatcher.classify("something")

    then:
    result.complex
  }

  def "parseResponse finds SIMPLE anywhere in response"() {
    given:
    DispatcherAgent dispatcher = new DispatcherAgent(ai, settings)

    when:
    DispatcherAgent.DispatchResult result = dispatcher.parseResponse("This is a SIMPLE task")

    then:
    !result.complex
  }

  def "parseResponse finds COMPLEX anywhere in response"() {
    given:
    DispatcherAgent dispatcher = new DispatcherAgent(ai, settings)

    when:
    DispatcherAgent.DispatchResult result = dispatcher.parseResponse("This looks COMPLEX to me")

    then:
    result.complex
  }
}
