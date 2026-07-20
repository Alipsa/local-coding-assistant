package se.alipsa.lca.team

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.PromptRunner
import spock.lang.Specification

class ArchitectAgentSpec extends Specification {

  Ai ai = Mock()
  PromptRunner promptRunner = Mock()
  TeamSettings settings = new TeamSettings(
    false, "test-model", "test-model", "test-model", "test-model",
    0.1d, 0.3d, 0.2d, 0.1d, 30L, 300L, 600L, 300L, true
  )

  def "plan returns the structured plan produced by createObject"() {
    given:
    ArchitectPlan expected = new ArchitectPlan(
      "Add logging",
      [new PlanStep(1, "Add logger", "Foo.groovy", StepAction.MODIFY, [], [], "")],
      [], ["None"], "Simple change"
    )
    ai.withLlm(_) >> promptRunner
    promptRunner.withPromptContributor(_) >> promptRunner
    promptRunner.createObject(_ as String, ArchitectPlan) >> expected

    ArchitectAgent agent = new ArchitectAgent(ai, settings, null)

    when:
    ArchitectPlan plan = agent.plan(new PlanRequest("add logging", null))

    then:
    plan.summary == "Add logging"
    plan.steps.size() == 1
    plan.steps[0].description == "Add logger"
  }

  def "plan creates fallback on exception"() {
    given:
    ai.withLlm(_) >> { throw new RuntimeException("LLM unavailable") }
    ArchitectAgent agent = new ArchitectAgent(ai, settings, null)

    when:
    ArchitectPlan plan = agent.plan(new PlanRequest("some task", null))

    then:
    plan.steps.size() == 1
    plan.summary.contains("Fallback")
    plan.risks.any { it.contains("LLM unavailable") }
  }

  def "plan folds the session system prompt into the prompt sent to the LLM"() {
    given:
    String capturedPrompt = null
    ArchitectPlan expected = new ArchitectPlan("Plan", [], [], [], "")
    ai.withLlm(_) >> promptRunner
    promptRunner.withPromptContributor(_) >> promptRunner
    promptRunner.createObject(_ as String, ArchitectPlan) >> { String prompt, Class type ->
      capturedPrompt = prompt
      expected
    }

    ArchitectAgent agent = new ArchitectAgent(ai, settings, null)

    when:
    agent.plan(new PlanRequest("do the thing", "Extra session guidance"))

    then:
    capturedPrompt.contains("Extra session guidance")
    capturedPrompt.contains("do the thing")
  }
}
