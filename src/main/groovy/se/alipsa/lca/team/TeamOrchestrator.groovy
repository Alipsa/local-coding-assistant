package se.alipsa.lca.team

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import se.alipsa.lca.tools.FileEditingTool

@Component
@CompileStatic
class TeamOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(TeamOrchestrator)
  private static final int CONTEXT_FILE_MAX_CHARS = 3000

  private final DispatcherAgent dispatcher
  private final ArchitectAgent architect
  private final EngineerAgent engineer
  private final TeamSettings settings
  private final FileEditingTool fileEditingTool

  TeamOrchestrator(
    DispatcherAgent dispatcher,
    ArchitectAgent architect,
    EngineerAgent engineer,
    TeamSettings settings,
    FileEditingTool fileEditingTool
  ) {
    this.dispatcher = dispatcher
    this.architect = architect
    this.engineer = engineer
    this.settings = settings
    this.fileEditingTool = fileEditingTool
  }

  boolean isEnabled() {
    settings.enabled
  }

  TeamResult execute(String prompt, String sessionSystemPrompt) {
    // Classify complexity
    DispatcherAgent.DispatchResult dispatch = dispatcher.classify(prompt)
    log.info("Dispatcher classified task as {}: {}", dispatch.complex ? "complex" : "simple", dispatch.reason)

    if (!dispatch.complex) {
      return executeSimple(prompt, sessionSystemPrompt)
    }
    return executeComplex(prompt, sessionSystemPrompt)
  }

  private TeamResult executeSimple(String prompt, String sessionSystemPrompt) {
    // Create synthetic single-step plan
    PlanStep step = new PlanStep(1, prompt, null, StepAction.MODIFY, [], [], "Task completed successfully")
    ArchitectPlan syntheticPlan = new ArchitectPlan(
      "Direct execution (simple task)",
      [step],
      [],
      [],
      "Task classified as simple — bypassing Architect"
    )

    // Build context from any referenced files
    String context = readContextForStep(step)

    EngineerStepResult result = engineer.executeStep(step, syntheticPlan, [], context)
    List<EngineerStepResult> results = [result]

    String fullOutput = formatOutput(null, results, false)
    new TeamResult(result.success, result.success ? "Simple task completed" : "Simple task failed",
      null, results, fullOutput)
  }

  private TeamResult executeComplex(String prompt, String sessionSystemPrompt) {
    // Architect phase
    println("Architect is analysing the task...")
    ArchitectPlan plan = architect.plan(prompt, sessionSystemPrompt)

    // Print plan summary
    println("\n=== Architect Plan ===")
    println("Summary: ${plan.summary}")
    if (!plan.risks.isEmpty()) {
      println("Risks: ${plan.risks.join('; ')}")
    }
    println("Steps (${plan.steps.size()}):")
    for (PlanStep step : plan.steps) {
      String target = step.targetFile != null ? " -> ${step.targetFile}" : ""
      println("  ${step.order}. [${step.action}] ${step.description}${target}")
    }

    // Check auto-execute
    if (!settings.autoExecute) {
      println("\nProceed with this plan? (y/n): ")
      BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))
      String answer = reader.readLine()?.trim()?.toLowerCase(Locale.ROOT)
      if (answer != "y" && answer != "yes") {
        String fullOutput = formatOutput(plan, [], true)
        return new TeamResult(false, "Plan rejected by user", plan, [], fullOutput)
      }
    }

    // Engineer phase — execute steps serially
    List<PlanStep> sortedSteps = plan.steps.sort { PlanStep a, PlanStep b -> a.order <=> b.order }
    List<EngineerStepResult> stepResults = []

    for (PlanStep step : sortedSteps) {
      println("\nEngineer executing step ${step.order}/${sortedSteps.size()}: ${step.description}")

      String context = readContextForStep(step)
      EngineerStepResult result = engineer.executeStep(step, plan, stepResults, context)
      stepResults.add(result)

      if (!result.success) {
        println("Step ${step.order} FAILED: ${result.errorMessage}")
        String failOutput = formatOutput(plan, stepResults, false)
        int succeeded = stepResults.count { EngineerStepResult r -> r.success } as int
        return new TeamResult(false,
          "Failed at step ${step.order}/${sortedSteps.size()} (${succeeded} steps succeeded)".toString(),
          plan, stepResults, failOutput)
      }
      println("Step ${step.order} completed successfully")
    }

    String fullOutput = formatOutput(plan, stepResults, false)
    new TeamResult(true, "All ${sortedSteps.size()} steps completed successfully".toString(),
      plan, stepResults, fullOutput)
  }

  private String readContextForStep(PlanStep step) {
    StringBuilder context = new StringBuilder()

    // Read target file if action is MODIFY
    if (step.action == StepAction.MODIFY && step.targetFile != null) {
      String content = safeReadFile(step.targetFile)
      if (content != null) {
        context.append("--- ${step.targetFile} (target) ---\n")
        context.append(truncate(content, CONTEXT_FILE_MAX_CHARS))
        context.append("\n\n")
      }
    }

    // Read context files
    if (step.contextFiles != null) {
      for (String file : step.contextFiles) {
        String content = safeReadFile(file)
        if (content != null) {
          context.append("--- ${file} ---\n")
          context.append(truncate(content, CONTEXT_FILE_MAX_CHARS))
          context.append("\n\n")
        }
      }
    }

    context.toString()
  }

  private String safeReadFile(String filePath) {
    try {
      String content = fileEditingTool.readFile(filePath)
      return content
    } catch (Exception e) {
      log.debug("Could not read file {}: {}", filePath, e.message)
      return null
    }
  }

  private static String truncate(String text, int maxChars) {
    if (text.length() <= maxChars) {
      return text
    }
    text.substring(0, maxChars) + "\n... (truncated)"
  }

  private String formatOutput(ArchitectPlan plan, List<EngineerStepResult> results, boolean rejected) {
    StringBuilder sb = new StringBuilder()

    if (plan != null) {
      sb.append("=== Architect Plan ===\n")
      sb.append("Summary: ${plan.summary}\n")
      if (!plan.risks.isEmpty()) {
        sb.append("Risks: ${plan.risks.join('; ')}\n")
      }
      sb.append("\n")
    }

    if (rejected) {
      sb.append("Plan rejected by user.\n")
      return sb.toString()
    }

    if (!results.isEmpty()) {
      sb.append("=== Execution Results ===\n")
      for (EngineerStepResult result : results) {
        String status = result.success ? "SUCCESS" : "FAILED"
        sb.append("Step ${result.stepOrder}: ${status}")
        if (!result.filesModified.isEmpty()) {
          sb.append(" (modified: ${result.filesModified.join(', ')})")
        }
        if (result.errorMessage != null) {
          sb.append(" - ${result.errorMessage}")
        }
        sb.append("\n")
        if (result.toolResults != null) {
          sb.append(result.toolResults)
          sb.append("\n")
        }
      }
    }

    int succeeded = results.count { EngineerStepResult r -> r.success } as int
    int total = results.size()
    sb.append("\nCompleted: ${succeeded}/${total} steps")
    sb.toString()
  }

  @Canonical
  @CompileStatic
  static class TeamResult {
    boolean success
    String summary
    ArchitectPlan plan
    List<EngineerStepResult> stepResults
    String fullOutput
  }
}
