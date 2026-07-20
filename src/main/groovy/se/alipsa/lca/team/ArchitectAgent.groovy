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
import se.alipsa.lca.tools.ImplementContextPacker

import java.time.Duration

@Agent(name = "lca-team-architect", description = "Plan the implementation of a coding task")
@Profile("!test")
@CompileStatic
class ArchitectAgent {

  private static final Logger log = LoggerFactory.getLogger(ArchitectAgent)

  private final Ai ai
  private final TeamSettings settings
  private final ImplementContextPacker contextPacker

  ArchitectAgent(
    Ai ai,
    TeamSettings settings,
    @org.springframework.beans.factory.annotation.Autowired(required = false) ImplementContextPacker contextPacker
  ) {
    this.ai = ai
    this.settings = settings
    this.contextPacker = contextPacker
  }

  @AchievesGoal(description = "Produce a repository-aware implementation plan for a coding task")
  @Action(canRerun = true, trigger = PlanRequest)
  ArchitectPlan plan(PlanRequest request) {
    String prompt = request?.prompt
    String contextBlock = ""
    if (contextPacker != null) {
      try {
        ImplementContextPacker.PrePackedContext packed = contextPacker.buildContext(prompt)
        contextBlock = packed.contextBlock ?: ""
      } catch (Exception e) {
        log.warn("Failed to pack context for architect", e)
      }
    }

    String userPrompt = buildUserPrompt(prompt, contextBlock, request?.sessionSystemPrompt)

    LlmOptions options = LlmOptions.withModel(settings.architectModel)
      .withTemperature(settings.architectTemperature)
      .withTimeout(Duration.ofSeconds(settings.architectTimeoutSeconds))

    try {
      ai.withLlm(options)
        .withPromptContributor(Personas.ARCHITECT)
        .createObject(userPrompt, ArchitectPlan)
    } catch (Exception e) {
      log.error("Architect agent failed", e)
      createFallbackPlan(prompt, e.message)
    }
  }

  private String buildUserPrompt(String prompt, String contextBlock, String sessionSystemPrompt) {
    StringBuilder sb = new StringBuilder()
    sb.append("You are a Software Architect. You create implementation plans but NEVER write code.\n")
    sb.append("Follow the project's own conventions (check AGENTS.md if present); ")
    sb.append("never invent package names, frameworks, or project structure not evidenced in the context below.\n\n")
    sb.append("=== CROSS-FILE PLANNING RULES ===\n")
    sb.append("If a step changes a shared interface, method signature, or public contract, ")
    sb.append("add a step for every caller/implementer affected — do not leave dependents unplanned.\n")
    sb.append("If a step changes a database column, schema, or on-disk format, ")
    sb.append("add a step for every reader/writer of that data.\n")
    sb.append("acceptanceCriteria must describe how a Spock test would verify the step ")
    sb.append("(what it asserts), not just restate the step's description.\n\n")
    if (sessionSystemPrompt != null && !sessionSystemPrompt.trim().isEmpty()) {
      sb.append("=== ADDITIONAL CONTEXT ===\n")
      sb.append(sessionSystemPrompt)
      sb.append("\n\n")
    }
    if (!contextBlock.isEmpty()) {
      sb.append("=== PROJECT CONTEXT ===\n")
      sb.append(contextBlock)
      sb.append("\n\n")
    }
    sb.append("=== TASK ===\n")
    sb.append(prompt)
    sb.append("\n\n")
    sb.append("=== PARALLELISM RULES ===\n")
    sb.append("Steps with disjoint targetFile values can run in parallel (leave dependsOn empty).\n")
    sb.append("Steps that modify the SAME targetFile MUST have a dependency chain ")
    sb.append("(e.g. step 3 depends on step 1 if both target the same file).\n")
    sb.append("Reading the same file in contextFiles is safe and needs no dependency.\n")
    sb.append("When in doubt, add a dependency — correctness over speed.\n")
    sb.toString()
  }

  private ArchitectPlan createFallbackPlan(String description, String reason) {
    PlanStep step = new PlanStep(
      1,
      description,
      null,
      StepAction.MODIFY,
      [],
      [],
      "Task completed successfully"
    )
    new ArchitectPlan(
      "Fallback single-step plan (${reason})".toString(),
      [step],
      [],
      ["Architect could not produce structured plan: ${reason}".toString()],
      reason
    )
  }
}
