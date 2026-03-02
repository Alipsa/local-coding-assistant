package se.alipsa.lca.team

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.PromptRunner
import se.alipsa.lca.tools.CommandRunner
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.ToolCallParser
import se.alipsa.lca.validation.ImplementationGroundingCheck
import se.alipsa.lca.validation.ToolCallValidator
import spock.lang.Specification

class EngineerAgentSpec extends Specification {

  Ai ai = Mock()
  PromptRunner promptRunner = Mock()
  TeamSettings settings = new TeamSettings(false, "test-model", "test-model", "test-model", 0.1d, true)
  ToolCallParser toolCallParser = new ToolCallParser()
  FileEditingTool fileEditingTool = Mock()
  CommandRunner commandRunner = Mock()

  def "executeStep returns success for tool-call response"() {
    given:
    String response = 'writeFile("src/main/groovy/Foo.groovy", "class Foo {}")'
    ai.withLlm(_) >> promptRunner
    promptRunner.withPromptContributor(_) >> promptRunner
    promptRunner.generateText(_) >> response
    fileEditingTool.writeFile("src/main/groovy/Foo.groovy", "class Foo {}") >> "OK"

    EngineerAgent engineer = new EngineerAgent(ai, settings, toolCallParser, fileEditingTool, commandRunner, null, null)
    PlanStep step = new PlanStep(1, "Create Foo", "src/main/groovy/Foo.groovy", StepAction.CREATE, [], [], "File exists")
    ArchitectPlan plan = new ArchitectPlan("Test plan", [step], [], [], "")

    when:
    EngineerStepResult result = engineer.executeStep(step, plan, [], "")

    then:
    result.success
    result.stepOrder == 1
    result.filesModified == ["src/main/groovy/Foo.groovy"]
  }

  def "executeStep returns success for empty tool calls"() {
    given:
    String response = "I have reviewed the code and no changes are needed."
    ai.withLlm(_) >> promptRunner
    promptRunner.withPromptContributor(_) >> promptRunner
    promptRunner.generateText(_) >> response

    EngineerAgent engineer = new EngineerAgent(ai, settings, toolCallParser, fileEditingTool, commandRunner, null, null)
    PlanStep step = new PlanStep(1, "Review", null, StepAction.MODIFY, [], [], "")
    ArchitectPlan plan = new ArchitectPlan("Test", [step], [], [], "")

    when:
    EngineerStepResult result = engineer.executeStep(step, plan, [], "")

    then:
    result.success
    result.toolResults == null
  }

  def "executeStep returns failure on empty response"() {
    given:
    ai.withLlm(_) >> promptRunner
    promptRunner.withPromptContributor(_) >> promptRunner
    promptRunner.generateText(_) >> ""

    EngineerAgent engineer = new EngineerAgent(ai, settings, toolCallParser, fileEditingTool, commandRunner, null, null)
    PlanStep step = new PlanStep(1, "Do something", null, StepAction.MODIFY, [], [], "")
    ArchitectPlan plan = new ArchitectPlan("Test", [step], [], [], "")

    when:
    EngineerStepResult result = engineer.executeStep(step, plan, [], "")

    then:
    !result.success
    result.errorMessage.contains("Empty response")
  }

  def "executeStep returns failure on exception"() {
    given:
    ai.withLlm(_) >> { throw new RuntimeException("Connection refused") }

    EngineerAgent engineer = new EngineerAgent(ai, settings, toolCallParser, fileEditingTool, commandRunner, null, null)
    PlanStep step = new PlanStep(1, "Step", null, StepAction.MODIFY, [], [], "")
    ArchitectPlan plan = new ArchitectPlan("Test", [step], [], [], "")

    when:
    EngineerStepResult result = engineer.executeStep(step, plan, [], "")

    then:
    !result.success
    result.errorMessage.contains("Connection refused")
  }

  def "executeStep blocks on grounding check failure"() {
    given:
    String response = 'writeFile("com/example/Bad.groovy", "package com.example")'
    ai.withLlm(_) >> promptRunner
    promptRunner.withPromptContributor(_) >> promptRunner
    promptRunner.generateText(_) >> response

    ImplementationGroundingCheck groundingCheck = Mock()
    groundingCheck.check(_, _) >> new ImplementationGroundingCheck.GroundingResult(
      ImplementationGroundingCheck.GroundingLevel.UNGROUNDED,
      ["Uses com.example package", "No existing files referenced", "All new files"]
    )

    EngineerAgent engineer = new EngineerAgent(ai, settings, toolCallParser, fileEditingTool,
      commandRunner, groundingCheck, null)
    PlanStep step = new PlanStep(1, "Step", null, StepAction.CREATE, [], [], "")
    ArchitectPlan plan = new ArchitectPlan("Test", [step], [], [], "")

    when:
    EngineerStepResult result = engineer.executeStep(step, plan, [], "")

    then:
    !result.success
    result.errorMessage.contains("Grounding check failed")
  }

  def "executeStep blocks on tool call validation failure"() {
    given:
    String response = 'writeFile("deeply/nested/fake/path/File.groovy", "content")'
    ai.withLlm(_) >> promptRunner
    promptRunner.withPromptContributor(_) >> promptRunner
    promptRunner.generateText(_) >> response

    ToolCallValidator validator = Mock()
    validator.validate(_) >> new ToolCallValidator.ToolCallValidationResult(
      [new ToolCallValidator.BlockedCall(
        new ToolCallParser.ToolCall("writeFile", ["deeply/nested/fake/path/File.groovy", "content"]),
        "Path requires creating 4 missing directory levels"
      )],
      [],
      []
    )

    EngineerAgent engineer = new EngineerAgent(ai, settings, toolCallParser, fileEditingTool,
      commandRunner, null, validator)
    PlanStep step = new PlanStep(1, "Step", null, StepAction.CREATE, [], [], "")
    ArchitectPlan plan = new ArchitectPlan("Test", [step], [], [], "")

    when:
    EngineerStepResult result = engineer.executeStep(step, plan, [], "")

    then:
    !result.success
    result.errorMessage.contains("Tool call validation failed")
  }
}
