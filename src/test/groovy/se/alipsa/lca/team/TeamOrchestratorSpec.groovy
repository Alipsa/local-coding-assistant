package se.alipsa.lca.team

import se.alipsa.lca.tools.FileEditingTool
import spock.lang.Specification

class TeamOrchestratorSpec extends Specification {

  DispatcherAgent dispatcher = Mock()
  ArchitectAgent architect = Mock()
  EngineerAgent engineer = Mock()
  TeamReviewerAgent reviewer = Mock()
  TeamSettings settings = new TeamSettings(
    true, "model", "model", "model", "model", 0.1d, 0.3d, 0.2d, 0.1d, 30L, 300L, 600L, 300L, true
  )
  FileEditingTool fileEditingTool = Mock()
  TeamOrchestrator orchestrator

  def setup() {
    orchestrator = new TeamOrchestrator(dispatcher, architect, engineer, reviewer, settings, fileEditingTool)
  }

  def "isEnabled delegates to settings"() {
    expect:
    orchestrator.isEnabled()
  }

  def "isEnabled returns false when disabled"() {
    given:
    TeamSettings disabled = new TeamSettings(
      false, "model", "model", "model", "model", 0.1d, 0.3d, 0.2d, 0.1d, 30L, 300L, 600L, 300L, true
    )
    TeamOrchestrator disabledOrch = new TeamOrchestrator(
      dispatcher, architect, engineer, reviewer, disabled, fileEditingTool
    )

    expect:
    !disabledOrch.isEnabled()
  }

  def "simple task bypasses architect and calls engineer directly"() {
    given:
    dispatcher.classify(_) >> new DispatcherAgent.DispatchResult(false, "Simple")
    engineer.executeStep(_) >> new EngineerStepResult(1, true, "OK", "response", ["file.groovy"], null)

    when:
    TeamOrchestrator.TeamResult result = orchestrator.execute("add a logger", null)

    then:
    result.success
    result.plan == null // no architect plan for simple tasks
    result.stepResults.size() == 1
    0 * architect.plan(_) // architect never called
    0 * reviewer.review(_) // QA is scoped to the complex path only
  }

  def "complex task calls architect then engineer"() {
    given:
    dispatcher.classify(_) >> new DispatcherAgent.DispatchResult(true, "Complex keyword")
    architect.plan(_) >> new ArchitectPlan(
      "Refactor plan",
      [
        new PlanStep(1, "Step 1", "File1.groovy", StepAction.MODIFY, [], [], "Done"),
        new PlanStep(2, "Step 2", "File2.groovy", StepAction.CREATE, [], [1], "Done")
      ],
      [],
      ["Risk 1"],
      "Reasoning"
    )
    engineer.executeStep(_) >>> [
      new EngineerStepResult(1, true, "OK", "r1", ["File1.groovy"], null),
      new EngineerStepResult(2, true, "OK", "r2", ["File2.groovy"], null)
    ]
    reviewer.review(_) >> new TeamReviewResult("No issues found.", false)

    when:
    TeamOrchestrator.TeamResult result = orchestrator.execute("refactor everything", null)

    then:
    result.success
    result.plan != null
    result.plan.summary == "Refactor plan"
    result.stepResults.size() == 2
    result.qaReview == "No issues found."
  }

  def "step failure halts execution"() {
    given:
    dispatcher.classify(_) >> new DispatcherAgent.DispatchResult(true, "Complex")
    architect.plan(_) >> new ArchitectPlan(
      "Plan",
      [
        new PlanStep(1, "Step 1", null, StepAction.MODIFY, [], [], ""),
        new PlanStep(2, "Step 2", null, StepAction.MODIFY, [], [1], ""),
        new PlanStep(3, "Step 3", null, StepAction.MODIFY, [], [2], "")
      ],
      [], [], ""
    )
    engineer.executeStep(_) >>> [
      new EngineerStepResult(1, true, "OK", "r1", [], null),
      new EngineerStepResult(2, false, null, "r2", [], "Grounding check failed"),
    ]

    when:
    TeamOrchestrator.TeamResult result = orchestrator.execute("refactor all", null)

    then:
    !result.success
    result.stepResults.size() == 2 // step 3 never executed
    result.summary.contains("Failed at step 2")
    0 * reviewer.review(_) // failure short-circuits before QA
  }

  def "empty plan produces successful result"() {
    given:
    dispatcher.classify(_) >> new DispatcherAgent.DispatchResult(true, "Complex")
    architect.plan(_) >> new ArchitectPlan("Empty plan", [], [], [], "")
    reviewer.review(_) >> new TeamReviewResult("No issues found.", false)

    when:
    TeamOrchestrator.TeamResult result = orchestrator.execute("refactor something", null)

    then:
    result.success
    result.stepResults.isEmpty()
  }

  def "simple task failure is reported"() {
    given:
    dispatcher.classify(_) >> new DispatcherAgent.DispatchResult(false, "Simple")
    engineer.executeStep(_) >> new EngineerStepResult(1, false, null, null, [], "LLM error")

    when:
    TeamOrchestrator.TeamResult result = orchestrator.execute("add a method", null)

    then:
    !result.success
    result.summary == "Simple task failed"
  }

  // --- QA loop tests ---

  def "QA flags a high-severity finding, engineer runs one fix-up pass, then succeeds"() {
    given:
    dispatcher.classify(_) >> new DispatcherAgent.DispatchResult(true, "Complex")
    architect.plan(_) >> new ArchitectPlan(
      "Plan", [new PlanStep(1, "Step 1", "A.groovy", StepAction.MODIFY, [], [], "")], [], [], ""
    )
    engineer.executeStep(_) >>> [
      new EngineerStepResult(1, true, "OK", "r1", ["A.groovy"], null),
      new EngineerStepResult(2, true, "Fixed it", "r2", ["A.groovy"], null)
    ]
    reviewer.review(_) >>> [
      new TeamReviewResult("- [High] A.groovy:1 - missing null check", true),
      new TeamReviewResult("No further issues.", false)
    ]

    when:
    TeamOrchestrator.TeamResult result = orchestrator.execute("do something risky", null)

    then:
    result.success
    result.stepResults.size() == 2 // original step + one fix-up step
    result.qaReview == "No further issues." // proves the reviewer ran a second time, post-fix-up
  }

  def "QA finds nothing, no fix-up pass runs"() {
    given:
    dispatcher.classify(_) >> new DispatcherAgent.DispatchResult(true, "Complex")
    architect.plan(_) >> new ArchitectPlan(
      "Plan", [new PlanStep(1, "Step 1", "A.groovy", StepAction.MODIFY, [], [], "")], [], [], ""
    )
    engineer.executeStep(_) >> new EngineerStepResult(1, true, "OK", "r1", ["A.groovy"], null)
    reviewer.review(_) >> new TeamReviewResult("No issues found.", false)

    when:
    TeamOrchestrator.TeamResult result = orchestrator.execute("do something safe", null)

    then:
    result.success
    result.stepResults.size() == 1 // no fix-up step added
  }

  def "QA-flagged fix-up failure is reported as a failure"() {
    given:
    dispatcher.classify(_) >> new DispatcherAgent.DispatchResult(true, "Complex")
    architect.plan(_) >> new ArchitectPlan(
      "Plan", [new PlanStep(1, "Step 1", "A.groovy", StepAction.MODIFY, [], [], "")], [], [], ""
    )
    engineer.executeStep(_) >>> [
      new EngineerStepResult(1, true, "OK", "r1", ["A.groovy"], null),
      new EngineerStepResult(2, false, null, null, [], "Could not apply fix")
    ]
    reviewer.review(_) >> new TeamReviewResult("- [High] A.groovy:1 - missing null check", true)

    when:
    TeamOrchestrator.TeamResult result = orchestrator.execute("do something risky", null)

    then:
    !result.success
    result.summary.contains("QA-flagged fix-up failed")
  }

  // --- Wave computation tests ---

  def "wave computation with dependencies produces correct waves"() {
    given:
    List<PlanStep> steps = [
      new PlanStep(1, "Step 1", "A.groovy", StepAction.MODIFY, [], [], ""),
      new PlanStep(2, "Step 2", "B.groovy", StepAction.MODIFY, [], [], ""),
      new PlanStep(3, "Step 3", "C.groovy", StepAction.MODIFY, [], [1], "")
    ]

    when:
    List<TeamOrchestrator.ExecutionWave> waves = orchestrator.computeExecutionWaves(steps)

    then:
    waves.size() == 2
    waves[0].waveNumber == 1
    waves[0].steps*.order == [1, 2]
    waves[1].waveNumber == 2
    waves[1].steps*.order == [3]
  }

  def "single wave when no dependencies"() {
    given:
    List<PlanStep> steps = [
      new PlanStep(1, "Step 1", "A.groovy", StepAction.MODIFY, [], [], ""),
      new PlanStep(2, "Step 2", "B.groovy", StepAction.CREATE, [], [], ""),
      new PlanStep(3, "Step 3", "C.groovy", StepAction.MODIFY, [], [], "")
    ]

    when:
    List<TeamOrchestrator.ExecutionWave> waves = orchestrator.computeExecutionWaves(steps)

    then:
    waves.size() == 1
    waves[0].steps*.order == [1, 2, 3]
  }

  def "fully sequential chain produces one wave per step"() {
    given:
    List<PlanStep> steps = [
      new PlanStep(1, "Step 1", "A.groovy", StepAction.MODIFY, [], [], ""),
      new PlanStep(2, "Step 2", "A.groovy", StepAction.MODIFY, [], [1], ""),
      new PlanStep(3, "Step 3", "A.groovy", StepAction.MODIFY, [], [2], "")
    ]

    when:
    List<TeamOrchestrator.ExecutionWave> waves = orchestrator.computeExecutionWaves(steps)

    then:
    waves.size() == 3
    waves[0].steps*.order == [1]
    waves[1].steps*.order == [2]
    waves[2].steps*.order == [3]
  }

  def "circular dependency detection throws exception"() {
    given:
    List<PlanStep> steps = [
      new PlanStep(1, "Step 1", "A.groovy", StepAction.MODIFY, [], [2], ""),
      new PlanStep(2, "Step 2", "B.groovy", StepAction.MODIFY, [], [1], "")
    ]

    when:
    orchestrator.computeExecutionWaves(steps)

    then:
    IllegalStateException e = thrown()
    e.message.contains("Circular dependency")
  }

  // --- File collision tests ---

  def "file collision detected when two steps target same file in one wave"() {
    given:
    TeamOrchestrator.ExecutionWave wave = new TeamOrchestrator.ExecutionWave(1, [
      new PlanStep(1, "Step 1", "Same.groovy", StepAction.MODIFY, [], [], ""),
      new PlanStep(2, "Step 2", "Same.groovy", StepAction.MODIFY, [], [], "")
    ])

    when:
    List<TeamOrchestrator.FileCollision> collisions = orchestrator.detectFileCollisions(wave)

    then:
    collisions.size() == 1
    collisions[0].filePath == "Same.groovy"
    collisions[0].conflictingStepOrders == [1, 2]
  }

  def "no collision for different target files"() {
    given:
    TeamOrchestrator.ExecutionWave wave = new TeamOrchestrator.ExecutionWave(1, [
      new PlanStep(1, "Step 1", "A.groovy", StepAction.MODIFY, [], [], ""),
      new PlanStep(2, "Step 2", "B.groovy", StepAction.CREATE, [], [], "")
    ])

    when:
    List<TeamOrchestrator.FileCollision> collisions = orchestrator.detectFileCollisions(wave)

    then:
    collisions.isEmpty()
  }

  def "collision aborts before engineer runs"() {
    given:
    dispatcher.classify(_) >> new DispatcherAgent.DispatchResult(true, "Complex")
    architect.plan(_) >> new ArchitectPlan(
      "Plan",
      [
        new PlanStep(1, "Step 1", "Same.groovy", StepAction.MODIFY, [], [], ""),
        new PlanStep(2, "Step 2", "Same.groovy", StepAction.MODIFY, [], [], "")
      ],
      [], [], ""
    )

    when:
    TeamOrchestrator.TeamResult result = orchestrator.execute("do something", null)

    then:
    !result.success
    result.summary.contains("File collision")
    0 * engineer.executeStep(_)
  }

  def "parallel steps all succeed"() {
    given:
    // Use Stubs for parallel tests — Spock mocks are not thread-safe
    EngineerAgent stubEngineer = Stub() {
      executeStep(_) >> { args ->
        EngineerStepRequest request = args[0] as EngineerStepRequest
        PlanStep s = request.step
        new EngineerStepResult(s.order, true, "OK", "r${s.order}".toString(), [s.targetFile], null)
      }
    }
    TeamOrchestrator parallelOrch = new TeamOrchestrator(
      dispatcher, architect, stubEngineer, reviewer, settings, fileEditingTool
    )
    dispatcher.classify(_) >> new DispatcherAgent.DispatchResult(true, "Complex")
    architect.plan(_) >> new ArchitectPlan(
      "Plan",
      [
        new PlanStep(1, "Step 1", "A.groovy", StepAction.MODIFY, [], [], ""),
        new PlanStep(2, "Step 2", "B.groovy", StepAction.CREATE, [], [], ""),
        new PlanStep(3, "Step 3", "C.groovy", StepAction.MODIFY, [], [], "")
      ],
      [], [], ""
    )
    reviewer.review(_) >> new TeamReviewResult("No issues found.", false)

    when:
    TeamOrchestrator.TeamResult result = parallelOrch.execute("do three things", null)

    then:
    result.success
    result.stepResults.size() == 3
    result.summary.contains("wave")
  }

  def "wave failure halts subsequent waves"() {
    given:
    // Use Stubs for parallel tests — Spock mocks are not thread-safe
    EngineerAgent stubEngineer = Stub() {
      executeStep(_) >> { args ->
        EngineerStepRequest request = args[0] as EngineerStepRequest
        PlanStep s = request.step
        if (s.order == 2) {
          return new EngineerStepResult(s.order, false, null, "r2", [], "Failed")
        }
        new EngineerStepResult(s.order, true, "OK", "r${s.order}".toString(), [s.targetFile], null)
      }
    }
    TeamOrchestrator waveOrch = new TeamOrchestrator(
      dispatcher, architect, stubEngineer, reviewer, settings, fileEditingTool
    )
    dispatcher.classify(_) >> new DispatcherAgent.DispatchResult(true, "Complex")
    architect.plan(_) >> new ArchitectPlan(
      "Plan",
      [
        new PlanStep(1, "Step 1", "A.groovy", StepAction.MODIFY, [], [], ""),
        new PlanStep(2, "Step 2", "B.groovy", StepAction.MODIFY, [], [1], ""),
        new PlanStep(3, "Step 3", "C.groovy", StepAction.MODIFY, [], [1], "")
      ],
      [], [], ""
    )

    when:
    TeamOrchestrator.TeamResult result = waveOrch.execute("multi wave task", null)

    then:
    !result.success
    // Wave 1 has step 1 (succeeds), wave 2 has steps 2,3 (step 2 fails)
    result.summary.contains("Failed at step")
  }
}
