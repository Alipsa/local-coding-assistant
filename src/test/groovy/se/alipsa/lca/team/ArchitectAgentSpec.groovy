package se.alipsa.lca.team

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.PromptRunner
import groovy.json.JsonBuilder
import spock.lang.Specification

class ArchitectAgentSpec extends Specification {

  Ai ai = Mock()
  PromptRunner promptRunner = Mock()
  TeamSettings settings = new TeamSettings(false, "test-model", "test-model", "test-model", 0.1d, true)

  def "plan parses valid JSON response"() {
    given:
    String jsonResponse = new JsonBuilder([
      summary: "Add logging",
      steps: [[order: 1, description: "Add logger", action: "MODIFY", targetFile: "Foo.groovy"]],
      risks: ["None"],
      reasoning: "Simple change"
    ]).toString()

    ai.withLlm(_) >> promptRunner
    promptRunner.withPromptContributor(_) >> promptRunner
    promptRunner.generateText(_) >> jsonResponse

    ArchitectAgent agent = new ArchitectAgent(ai, settings, null)

    when:
    ArchitectPlan plan = agent.plan("add logging", null)

    then:
    plan.summary == "Add logging"
    plan.steps.size() == 1
    plan.steps[0].description == "Add logger"
  }

  def "plan handles JSON wrapped in markdown code fence"() {
    given:
    String wrappedResponse = '```json\n{"summary":"Test","steps":[],"risks":[],"reasoning":""}\n```'

    ArchitectAgent agent = new ArchitectAgent(ai, settings, null)

    when:
    ArchitectPlan plan = agent.parseResponse(wrappedResponse)

    then:
    plan.summary == "Test"
    plan.steps.isEmpty()
  }

  def "plan creates fallback for invalid JSON"() {
    given:
    ArchitectAgent agent = new ArchitectAgent(ai, settings, null)

    when:
    ArchitectPlan plan = agent.parseResponse("This is not JSON at all, just plain text.")

    then:
    plan.steps.size() == 1
    plan.summary.contains("Fallback")
  }

  def "plan creates fallback for empty response"() {
    given:
    ArchitectAgent agent = new ArchitectAgent(ai, settings, null)

    when:
    ArchitectPlan plan = agent.parseResponse("")

    then:
    plan.steps.size() == 1
    plan.summary.contains("Fallback")
  }

  def "plan creates fallback on exception"() {
    given:
    ai.withLlm(_) >> { throw new RuntimeException("LLM unavailable") }
    ArchitectAgent agent = new ArchitectAgent(ai, settings, null)

    when:
    ArchitectPlan plan = agent.plan("some task", null)

    then:
    plan.steps.size() == 1
    plan.summary.contains("Fallback")
    plan.risks.any { it.contains("LLM unavailable") }
  }

  def "parseResponse handles JSON with surrounding text"() {
    given:
    String response = 'Here is my plan:\n{"summary":"Plan","steps":[],"risks":[]}\nDone.'
    ArchitectAgent agent = new ArchitectAgent(ai, settings, null)

    when:
    ArchitectPlan plan = agent.parseResponse(response)

    then:
    plan.summary == "Plan"
  }
}
