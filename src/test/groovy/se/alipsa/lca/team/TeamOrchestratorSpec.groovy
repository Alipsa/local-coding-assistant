package se.alipsa.lca.team

import se.alipsa.lca.tools.FileEditingTool
import spock.lang.Specification

class TeamOrchestratorSpec extends Specification {

  DispatcherAgent dispatcher = Mock()
  ArchitectAgent architect = Mock()
  EngineerAgent engineer = Mock()
  TeamSettings settings = new TeamSettings(true, "model", "model", "model", 0.1d, true)
  FileEditingTool fileEditingTool = Mock()
  TeamOrchestrator orchestrator

  def setup() {
    orchestrator = new TeamOrchestrator(dispatcher, architect, engineer, settings, fileEditingTool)
  }

  def "isEnabled delegates to settings"() {
    expect:
    orchestrator.isEnabled()
  }

  def "isEnabled returns false when disabled"() {
    given:
    TeamSettings disabled = new TeamSettings(false, "model", "model", "model", 0.1d, true)
    TeamOrchestrator disabledOrch = new TeamOrchestrator(dispatcher, architect, engineer, disabled, fileEditingTool)

    expect:
    !disabledOrch.isEnabled()
  }

  def "simple task bypasses architect and calls engineer directly"() {
    given:
    dispatcher.classify(_) >> new DispatcherAgent.DispatchResult(false, "Simple")
    engineer.executeStep(_, _, _, _) >> new EngineerStepResult(1, true, "OK", "response", ["file.groovy"], null)

    when:
    TeamOrchestrator.TeamResult result = orchestrator.execute("add a logger", null)

    then:
    result.success
    result.plan == null // no architect plan for simple tasks
    result.stepResults.size() == 1
    0 * architect.plan(_, _) // architect never called
  }

  def "complex task calls architect then engineer"() {
    given:
    dispatcher.classify(_) >> new DispatcherAgent.DispatchResult(true, "Complex keyword")
    architect.plan(_, _) >> new ArchitectPlan(
      "Refactor plan",
      [
        new PlanStep(1, "Step 1", "File1.groovy", StepAction.MODIFY, [], [], "Done"),
        new PlanStep(2, "Step 2", "File2.groovy", StepAction.CREATE, [], [1], "Done")
      ],
      [],
      ["Risk 1"],
      "Reasoning"
    )
    engineer.executeStep(_, _, _, _) >>> [
      new EngineerStepResult(1, true, "OK", "r1", ["File1.groovy"], null),
      new EngineerStepResult(2, true, "OK", "r2", ["File2.groovy"], null)
    ]

    when:
    TeamOrchestrator.TeamResult result = orchestrator.execute("refactor everything", null)

    then:
    result.success
    result.plan != null
    result.plan.summary == "Refactor plan"
    result.stepResults.size() == 2
  }

  def "step failure halts execution"() {
    given:
    dispatcher.classify(_) >> new DispatcherAgent.DispatchResult(true, "Complex")
    architect.plan(_, _) >> new ArchitectPlan(
      "Plan",
      [
        new PlanStep(1, "Step 1", null, StepAction.MODIFY, [], [], ""),
        new PlanStep(2, "Step 2", null, StepAction.MODIFY, [], [], ""),
        new PlanStep(3, "Step 3", null, StepAction.MODIFY, [], [], "")
      ],
      [], [], ""
    )
    engineer.executeStep(_, _, _, _) >>> [
      new EngineerStepResult(1, true, "OK", "r1", [], null),
      new EngineerStepResult(2, false, null, "r2", [], "Grounding check failed"),
    ]

    when:
    TeamOrchestrator.TeamResult result = orchestrator.execute("refactor all", null)

    then:
    !result.success
    result.stepResults.size() == 2 // step 3 never executed
    result.summary.contains("Failed at step 2")
  }

  def "empty plan produces successful result"() {
    given:
    dispatcher.classify(_) >> new DispatcherAgent.DispatchResult(true, "Complex")
    architect.plan(_, _) >> new ArchitectPlan("Empty plan", [], [], [], "")

    when:
    TeamOrchestrator.TeamResult result = orchestrator.execute("refactor something", null)

    then:
    result.success
    result.stepResults.isEmpty()
  }

  def "simple task failure is reported"() {
    given:
    dispatcher.classify(_) >> new DispatcherAgent.DispatchResult(false, "Simple")
    engineer.executeStep(_, _, _, _) >> new EngineerStepResult(1, false, null, null, [], "LLM error")

    when:
    TeamOrchestrator.TeamResult result = orchestrator.execute("add a method", null)

    then:
    !result.success
    result.summary == "Simple task failed"
  }
}
