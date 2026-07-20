package se.alipsa.lca.team

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.LlmOptions
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import se.alipsa.lca.agent.Personas
import se.alipsa.lca.tools.CommandRunner
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.ToolCallParser
import se.alipsa.lca.validation.ImplementationGroundingCheck
import se.alipsa.lca.validation.ToolCallValidator

import java.time.Duration

@Agent(name = "lca-team-engineer", description = "Implement a single plan step by editing files")
@Profile("!test")
@CompileStatic
class EngineerAgent {

  private static final Logger log = LoggerFactory.getLogger(EngineerAgent)

  private final Ai ai
  private final TeamSettings settings
  private final ToolCallParser toolCallParser
  private final FileEditingTool fileEditingTool
  private final CommandRunner commandRunner
  private final ImplementationGroundingCheck groundingCheck
  private final ToolCallValidator toolCallValidator

  EngineerAgent(
    Ai ai,
    TeamSettings settings,
    ToolCallParser toolCallParser,
    FileEditingTool fileEditingTool,
    @org.springframework.beans.factory.annotation.Autowired(required = false) CommandRunner commandRunner,
    @org.springframework.beans.factory.annotation.Autowired(required = false) ImplementationGroundingCheck groundingCheck,
    @org.springframework.beans.factory.annotation.Autowired(required = false) ToolCallValidator toolCallValidator
  ) {
    this.ai = ai
    this.settings = settings
    this.toolCallParser = toolCallParser
    this.fileEditingTool = fileEditingTool
    this.commandRunner = commandRunner
    this.groundingCheck = groundingCheck
    this.toolCallValidator = toolCallValidator
  }

  @AchievesGoal(description = "Implement a single plan step using file editing tools")
  @Action(canRerun = true, trigger = EngineerStepRequest)
  EngineerStepResult executeStep(EngineerStepRequest request) {
    PlanStep step = request.step
    ArchitectPlan plan = request.plan
    List<EngineerStepResult> priorResults = request.priorResults
    String contextContent = request.contextContent
    try {
      String userPrompt = buildUserPrompt(step, plan, priorResults, contextContent)

      LlmOptions options = LlmOptions.withModel(settings.engineerModel)
        .withTemperature(settings.engineerTemperature)
        .withTimeout(Duration.ofSeconds(settings.engineerTimeoutSeconds))

      String response = ai.withLlm(options)
        .withPromptContributor(Personas.CODER)
        .generateText(userPrompt)

      if (response == null || response.trim().isEmpty()) {
        return new EngineerStepResult(step.order, false, null, null, [], "Empty response from engineer")
      }

      // Parse tool calls
      List<ToolCallParser.ToolCall> toolCalls = toolCallParser.parseToolCalls(response)

      if (toolCalls.isEmpty()) {
        return new EngineerStepResult(step.order, true, null, response, [], null)
      }

      // Run grounding check
      if (groundingCheck != null) {
        def grounding = groundingCheck.check(response, toolCalls)
        if (grounding.shouldBlock()) {
          String issues = grounding.issues.join("; ")
          return new EngineerStepResult(step.order, false, null, response, [],
            "Grounding check failed: ${issues}".toString())
        }
      }

      // Run tool call validation
      if (toolCallValidator != null) {
        def validation = toolCallValidator.validate(toolCalls)
        if (!validation.safeToExecute) {
          String blocked = validation.blocked.collect { ToolCallValidator.BlockedCall bc -> bc.reason }.join("; ")
          return new EngineerStepResult(step.order, false, null, response, [],
            "Tool call validation failed: ${blocked}".toString())
        }
        toolCalls = validation.safe
      }

      // Execute tool calls
      List<String> filesModified = []
      for (ToolCallParser.ToolCall tc : toolCalls) {
        if (tc.toolName == "writeFile" || tc.toolName == "replace") {
          filesModified.add(tc.arguments[0])
        }
      }

      String toolResults = toolCallParser.executeToolCalls(toolCalls, fileEditingTool, commandRunner)
      new EngineerStepResult(step.order, true, toolResults, response, filesModified, null)

    } catch (Exception e) {
      log.error("Engineer step {} failed", step.order, e)
      new EngineerStepResult(step.order, false, null, null, [], "Exception: ${e.message}".toString())
    }
  }

  private String buildUserPrompt(
    PlanStep step,
    ArchitectPlan plan,
    List<EngineerStepResult> priorResults,
    String contextContent
  ) {
    StringBuilder sb = new StringBuilder()

    sb.append("You are a file editing assistant for an existing project. Implement the given step precisely.\n\n")
    sb.append("Use these exact tool call formats:\n")
    sb.append("- writeFile(\"file-path\", \"content\") - create or overwrite a file\n")
    sb.append("- replace(\"file-path\", \"old-text\", \"new-text\") - modify existing file\n")
    sb.append("- deleteFile(\"file-path\") - delete a file\n")
    sb.append("- runCommand(\"command\") - execute a shell command (e.g., chmod, mkdir, mv, cp)\n\n")
    sb.append("RULES:\n")
    sb.append("- NEVER invent package names, project structures, or frameworks not in the provided context\n")
    sb.append("- NEVER use com.example unless the project actually uses it\n")
    sb.append("- Follow the project's coding standards and conventions (check AGENTS.md if present)\n")
    sb.append("- ALWAYS prefer modifying existing files over creating new ones\n")
    sb.append("- ALL new files must use the same package structure found in the project\n")
    sb.append("- Include necessary imports, validation, and error handling; favor testable designs\n")
    sb.append("- Match the language, style, and patterns used in the existing codebase\n")
    sb.append("- Use actual tool calls, not code blocks\n")
    sb.append("- Focus only on the current step — do not implement other steps\n\n")

    sb.append("=== PLAN SUMMARY ===\n")
    sb.append(plan.summary)
    sb.append("\n\n")

    // Include prior step results summary
    if (priorResults != null && !priorResults.isEmpty()) {
      sb.append("=== PRIOR STEPS COMPLETED ===\n")
      for (EngineerStepResult prior : priorResults) {
        String status = prior.success ? "SUCCESS" : "FAILED"
        sb.append("Step ${prior.stepOrder}: ${status}")
        if (!prior.filesModified.isEmpty()) {
          sb.append(" (modified: ${prior.filesModified.join(', ')})")
        }
        sb.append("\n")
      }
      sb.append("\n")
    }

    // Include context
    if (contextContent != null && !contextContent.trim().isEmpty()) {
      sb.append("=== CONTEXT ===\n")
      sb.append(contextContent)
      sb.append("\n\n")
    }

    sb.append("=== CURRENT STEP (${step.order}) ===\n")
    sb.append("Description: ${step.description}\n")
    if (step.targetFile != null) {
      sb.append("Target file: ${step.targetFile}\n")
    }
    sb.append("Action: ${step.action}\n")
    if (step.acceptanceCriteria != null && !step.acceptanceCriteria.isEmpty()) {
      sb.append("Acceptance criteria (your changes MUST satisfy this before you finish): ")
      sb.append(step.acceptanceCriteria)
      sb.append("\n")
    }

    sb.toString()
  }
}
