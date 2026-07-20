package se.alipsa.lca.team

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import se.alipsa.lca.tools.FileEditingTool

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

@Component
@CompileStatic
class TeamOrchestrator {

  private static final Logger log = LoggerFactory.getLogger(TeamOrchestrator)
  private static final int CONTEXT_FILE_MAX_CHARS = 3000

  private final DispatcherAgent dispatcher
  private final ArchitectAgent architect
  private final EngineerAgent engineer
  private final TeamReviewerAgent reviewer
  private final TeamSettings settings
  private final FileEditingTool fileEditingTool

  TeamOrchestrator(
    DispatcherAgent dispatcher,
    ArchitectAgent architect,
    EngineerAgent engineer,
    TeamReviewerAgent reviewer,
    TeamSettings settings,
    FileEditingTool fileEditingTool
  ) {
    this.dispatcher = dispatcher
    this.architect = architect
    this.engineer = engineer
    this.reviewer = reviewer
    this.settings = settings
    this.fileEditingTool = fileEditingTool
  }

  boolean isEnabled() {
    settings.enabled
  }

  TeamResult execute(String prompt, String sessionSystemPrompt) {
    // Classify complexity
    DispatcherAgent.DispatchResult dispatch = dispatcher.classify(new DispatchRequest(prompt))
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

    EngineerStepResult result = engineer.executeStep(new EngineerStepRequest(step, syntheticPlan, [], context))
    List<EngineerStepResult> results = [result]

    String fullOutput = formatOutput(null, results, false, null)
    new TeamResult(result.success, result.success ? "Simple task completed" : "Simple task failed",
      null, results, fullOutput, null)
  }

  private TeamResult executeComplex(String prompt, String sessionSystemPrompt) {
    // Architect phase
    println("Architect is analysing the task...")
    ArchitectPlan plan = architect.plan(new PlanRequest(prompt, sessionSystemPrompt))

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
        String fullOutput = formatOutput(plan, [], true, null)
        return new TeamResult(false, "Plan rejected by user", plan, [], fullOutput, null)
      }
    }

    // Compute execution waves from dependency graph
    List<PlanStep> sortedSteps = plan.steps.sort { PlanStep a, PlanStep b -> a.order <=> b.order }
    List<ExecutionWave> waves
    try {
      waves = computeExecutionWaves(sortedSteps)
    } catch (IllegalStateException e) {
      String failOutput = formatOutput(plan, [], false, null)
      return new TeamResult(false, e.message, plan, [], failOutput, null)
    }

    // Print execution plan with wave info
    println("\nExecution plan: ${waves.size()} wave(s)")
    for (ExecutionWave wave : waves) {
      String stepDescs = wave.steps.collect { PlanStep s ->
        String deps = (s.dependsOn != null && !s.dependsOn.isEmpty()) ? " (depends on ${s.dependsOn})" : ""
        "step ${s.order}${deps}"
      }.join(", ")
      println("  Wave ${wave.waveNumber}: ${stepDescs}")
    }

    // Engineer phase — execute waves
    List<EngineerStepResult> stepResults = []
    int totalSteps = sortedSteps.size()

    for (ExecutionWave wave : waves) {
      // Check for file collisions within this wave
      List<FileCollision> collisions = detectFileCollisions(wave)
      if (!collisions.isEmpty()) {
        String collisionMsg = collisions.collect { FileCollision c ->
          "${c.filePath} (steps ${c.conflictingStepOrders})"
        }.join(", ")
        String failOutput = formatOutput(plan, stepResults, false, null)
        return new TeamResult(false,
          "File collision in wave ${wave.waveNumber}: ${collisionMsg}".toString(),
          plan, stepResults, failOutput, null)
      }

      // Execute wave
      if (wave.steps.size() == 1) {
        PlanStep step = wave.steps.first()
        println("\nEngineer executing step ${step.order}/${totalSteps}: ${step.description}")
      } else {
        String stepNums = wave.steps.collect { PlanStep s -> s.order.toString() }.join(", ")
        println("\nEngineer executing wave ${wave.waveNumber} (steps ${stepNums}) in parallel")
      }

      List<EngineerStepResult> waveResults = executeWave(wave, plan, stepResults)
      stepResults.addAll(waveResults)

      // Check for failures
      List<EngineerStepResult> failures = waveResults.findAll { EngineerStepResult r -> !r.success }
      if (!failures.isEmpty()) {
        for (EngineerStepResult fail : failures) {
          println("Step ${fail.stepOrder} FAILED: ${fail.errorMessage}")
        }
        String failOutput = formatOutput(plan, stepResults, false, null)
        int succeeded = stepResults.count { EngineerStepResult r -> r.success } as int
        int failedStep = failures.first().stepOrder
        return new TeamResult(false,
          "Failed at step ${failedStep}/${totalSteps} (${succeeded} steps succeeded)".toString(),
          plan, stepResults, failOutput, null)
      }

      for (EngineerStepResult r : waveResults) {
        println("Step ${r.stepOrder} completed successfully")
      }
    }

    // QA phase — review the accumulated diff; one bounded fix-up pass if High severity is flagged
    println("\nQA reviewer is checking the implementation...")
    TeamReviewResult qa = runQaReview(plan, stepResults, sessionSystemPrompt)
    if (qa.hasHighSeverityFinding) {
      println("QA flagged high-severity findings; running one fix-up pass...")
      List<String> modifiedFiles = new ArrayList<>(new LinkedHashSet<>(
        stepResults.collectMany { EngineerStepResult r -> r.filesModified ?: [] }
      ))
      PlanStep fixStep = new PlanStep(
        totalSteps + 1,
        "Address the following QA review findings:\n${qa.review}".toString(),
        null, StepAction.MODIFY, modifiedFiles, [],
        "All High severity QA findings from the review below are resolved"
      )
      String fixContext = readContextForStep(fixStep)
      EngineerStepResult fixResult = engineer.executeStep(
        new EngineerStepRequest(fixStep, plan, stepResults, fixContext)
      )
      stepResults.add(fixResult)
      if (!fixResult.success) {
        String failOutput = formatOutput(plan, stepResults, false, qa.review)
        return new TeamResult(false,
          "QA-flagged fix-up failed: ${fixResult.errorMessage}".toString(),
          plan, stepResults, failOutput, qa.review)
      }
      println("Fix-up applied; re-reviewing once more (no further retries)...")
      qa = runQaReview(plan, stepResults, sessionSystemPrompt)
    }

    String fullOutput = formatOutput(plan, stepResults, false, qa.review)
    new TeamResult(true,
      "All ${totalSteps} steps completed successfully in ${waves.size()} wave(s)".toString(),
      plan, stepResults, fullOutput, qa.review)
  }

  private TeamReviewResult runQaReview(ArchitectPlan plan, List<EngineerStepResult> stepResults, String sessionSystemPrompt) {
    String diffText = stepResults.collect { EngineerStepResult r ->
      "Step ${r.stepOrder} (${r.success ? "SUCCESS" : "FAILED"}, modified: ${r.filesModified.join(', ')}):\n${r.toolResults ?: r.llmResponse ?: ''}"
    }.join("\n\n")
    reviewer.review(new TeamReviewRequest(plan.summary, diffText, sessionSystemPrompt))
  }

  List<ExecutionWave> computeExecutionWaves(List<PlanStep> steps) {
    if (steps.isEmpty()) {
      return []
    }

    Map<Integer, PlanStep> stepsByOrder = new LinkedHashMap<>()
    Map<Integer, List<Integer>> dependencies = new LinkedHashMap<>()
    for (PlanStep step : steps) {
      stepsByOrder.put(step.order, step)
      dependencies.put(step.order, step.dependsOn != null ? new ArrayList<>(step.dependsOn) : [])
    }

    Set<Integer> completed = new LinkedHashSet<>()
    Set<Integer> remaining = new LinkedHashSet<>(stepsByOrder.keySet())
    List<ExecutionWave> waves = []
    int waveNumber = 1

    while (!remaining.isEmpty()) {
      List<PlanStep> ready = []
      for (int order : remaining) {
        List<Integer> deps = dependencies.get(order)
        if (deps.every { int d -> completed.contains(d) }) {
          ready.add(stepsByOrder.get(order))
        }
      }

      if (ready.isEmpty()) {
        throw new IllegalStateException(
          "Circular dependency detected among steps: ${remaining}".toString()
        )
      }

      waves.add(new ExecutionWave(waveNumber, ready))
      for (PlanStep step : ready) {
        completed.add(step.order)
        remaining.remove(step.order)
      }
      waveNumber++
    }

    waves
  }

  List<FileCollision> detectFileCollisions(ExecutionWave wave) {
    Set<StepAction> fileActions = EnumSet.of(StepAction.MODIFY, StepAction.CREATE, StepAction.DELETE)
    Map<String, List<Integer>> fileToSteps = new LinkedHashMap<>()

    for (PlanStep step : wave.steps) {
      if (step.targetFile != null && fileActions.contains(step.action)) {
        fileToSteps.computeIfAbsent(step.targetFile) { new ArrayList<>() }.add(step.order)
      }
    }

    List<FileCollision> collisions = []
    for (Map.Entry<String, List<Integer>> entry : fileToSteps.entrySet()) {
      if (entry.value.size() > 1) {
        collisions.add(new FileCollision(entry.key, entry.value))
      }
    }
    collisions
  }

  private List<EngineerStepResult> executeWave(
    ExecutionWave wave,
    ArchitectPlan plan,
    List<EngineerStepResult> priorResults
  ) {
    if (wave.steps.size() == 1) {
      PlanStep step = wave.steps.first()
      String context = readContextForStep(step)
      EngineerStepResult result = engineer.executeStep(new EngineerStepRequest(step, plan, priorResults, context))
      return [result]
    }

    ConcurrentHashMap<Integer, EngineerStepResult> resultMap = new ConcurrentHashMap<>()
    AtomicBoolean failed = new AtomicBoolean(false)
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()

    try {
      List<Future<EngineerStepResult>> futures = []
      for (PlanStep step : wave.steps) {
        final PlanStep capturedStep = step
        Future<EngineerStepResult> future = executor.submit(new Callable<EngineerStepResult>() {
          @Override
          EngineerStepResult call() {
            try {
              if (failed.get()) {
                EngineerStepResult skipped = new EngineerStepResult(
                  capturedStep.order, false, null, null, [],
                  "Skipped due to failure in parallel step"
                )
                resultMap.put(capturedStep.order, skipped)
                return skipped
              }
              String context = readContextForStep(capturedStep)
              EngineerStepResult result = engineer.executeStep(
                new EngineerStepRequest(capturedStep, plan, priorResults, context)
              )
              if (!result.success) {
                failed.set(true)
              }
              resultMap.put(capturedStep.order, result)
              result
            } catch (Exception e) {
              log.error("Step {} threw exception during execution", capturedStep.order, e)
              EngineerStepResult errResult = new EngineerStepResult(
                capturedStep.order, false, null, null, [],
                "Exception: ${e.message}".toString()
              )
              resultMap.put(capturedStep.order, errResult)
              failed.set(true)
              errResult
            }
          }
        })
        futures.add(future)
      }

      for (Future<EngineerStepResult> future : futures) {
        try {
          future.get()
        } catch (ExecutionException e) {
          log.error("Step execution threw exception", e.cause)
        }
      }
    } finally {
      executor.shutdown()
    }

    wave.steps.collect { PlanStep s ->
      resultMap.getOrDefault(s.order, new EngineerStepResult(
        s.order, false, null, null, [], "Step did not produce a result"
      ))
    }.sort { EngineerStepResult a, EngineerStepResult b -> a.stepOrder <=> b.stepOrder }
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

  private String formatOutput(ArchitectPlan plan, List<EngineerStepResult> results, boolean rejected, String qaReview) {
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

    if (qaReview != null && !qaReview.trim().isEmpty()) {
      sb.append("\n=== QA Review ===\n")
      sb.append(qaReview)
      sb.append("\n")
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
    String qaReview
  }

  @Canonical
  @CompileStatic
  static class ExecutionWave {
    int waveNumber
    List<PlanStep> steps
  }

  @Canonical
  @CompileStatic
  static class FileCollision {
    String filePath
    List<Integer> conflictingStepOrders
  }
}
