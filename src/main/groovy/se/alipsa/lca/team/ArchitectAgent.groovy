package se.alipsa.lca.team

import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.LlmOptions
import groovy.json.JsonException
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import se.alipsa.lca.agent.Personas
import se.alipsa.lca.tools.ImplementContextPacker

import java.time.Duration
import java.util.regex.Matcher
import java.util.regex.Pattern

@Component
@CompileStatic
class ArchitectAgent {

  private static final Logger log = LoggerFactory.getLogger(ArchitectAgent)
  private static final long ARCHITECT_TIMEOUT_SECONDS = 300L
  private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile(
    "```(?:json)?\\s*\\n?(\\{.*?})\\s*\\n?```",
    Pattern.DOTALL
  )

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

  ArchitectPlan plan(String prompt, String sessionSystemPrompt) {
    String contextBlock = ""
    if (contextPacker != null) {
      try {
        ImplementContextPacker.PrePackedContext packed = contextPacker.buildContext(prompt)
        contextBlock = packed.contextBlock ?: ""
      } catch (Exception e) {
        log.warn("Failed to pack context for architect", e)
      }
    }

    String systemPrompt = buildSystemPrompt(sessionSystemPrompt)
    String userPrompt = buildUserPrompt(prompt, contextBlock)

    LlmOptions options = LlmOptions.withModel(settings.architectModel)
      .withTemperature(0.3d)
      .withTimeout(Duration.ofSeconds(ARCHITECT_TIMEOUT_SECONDS))

    try {
      String response = ai.withLlm(options)
        .withPromptContributor(Personas.ARCHITECT)
        .generateText(userPrompt)

      parseResponse(response)
    } catch (Exception e) {
      log.error("Architect agent failed", e)
      createFallbackPlan(prompt, e.message)
    }
  }

  private String buildSystemPrompt(String sessionSystemPrompt) {
    StringBuilder sb = new StringBuilder()
    sb.append("You are a Software Architect. You create implementation plans but NEVER write code.\n")
    sb.append("Analyse the task, break it into ordered steps, identify risks, and respond with valid JSON.\n")
    if (sessionSystemPrompt != null && !sessionSystemPrompt.trim().isEmpty()) {
      sb.append("\nAdditional context:\n")
      sb.append(sessionSystemPrompt)
    }
    sb.toString()
  }

  private String buildUserPrompt(String prompt, String contextBlock) {
    StringBuilder sb = new StringBuilder()
    if (!contextBlock.isEmpty()) {
      sb.append("=== PROJECT CONTEXT ===\n")
      sb.append(contextBlock)
      sb.append("\n\n")
    }
    sb.append("=== TASK ===\n")
    sb.append(prompt)
    sb.append("\n\n")
    sb.append("=== RESPONSE FORMAT ===\n")
    sb.append("Respond with a single JSON object (no markdown fences) matching this schema:\n")
    sb.append("{\n")
    sb.append("  \"summary\": \"Brief summary of the implementation plan\",\n")
    sb.append("  \"steps\": [\n")
    sb.append("    {\n")
    sb.append("      \"order\": 1,\n")
    sb.append("      \"description\": \"What to do in this step\",\n")
    sb.append("      \"targetFile\": \"path/to/file or null\",\n")
    sb.append("      \"action\": \"CREATE|MODIFY|DELETE|RUN_COMMAND\",\n")
    sb.append("      \"contextFiles\": [\"files to read for context\"],\n")
    sb.append("      \"dependsOn\": [step orders that must complete before this step starts],\n")
    sb.append("      \"acceptanceCriteria\": \"How to verify this step succeeded\"\n")
    sb.append("    }\n")
    sb.append("  ],\n")
    sb.append("  \"readOnlyContext\": [\"files that should be read but not modified\"],\n")
    sb.append("  \"risks\": [\"potential risks or issues\"],\n")
    sb.append("  \"reasoning\": \"Why this approach was chosen\"\n")
    sb.append("}\n\n")
    sb.append("=== PARALLELISM RULES ===\n")
    sb.append("Steps with disjoint targetFile values can run in parallel (leave dependsOn empty).\n")
    sb.append("Steps that modify the SAME targetFile MUST have a dependency chain ")
    sb.append("(e.g. step 3 depends on step 1 if both target the same file).\n")
    sb.append("Reading the same file in contextFiles is safe and needs no dependency.\n")
    sb.append("When in doubt, add a dependency — correctness over speed.\n")
    sb.toString()
  }

  ArchitectPlan parseResponse(String response) {
    if (response == null || response.trim().isEmpty()) {
      return createFallbackPlan("Unknown task", "Empty response from architect")
    }

    String json = response.trim()

    // Try to extract JSON from markdown code fences
    Matcher matcher = JSON_BLOCK_PATTERN.matcher(json)
    if (matcher.find()) {
      json = matcher.group(1).trim()
    }

    // Strip leading/trailing non-JSON text
    int braceStart = json.indexOf('{')
    int braceEnd = json.lastIndexOf('}')
    if (braceStart >= 0 && braceEnd > braceStart) {
      json = json.substring(braceStart, braceEnd + 1)
    }

    try {
      return ArchitectPlan.fromJson(json)
    } catch (JsonException | IllegalArgumentException e) {
      log.warn("Failed to parse architect JSON response, creating fallback plan: {}", e.message)
      return createFallbackPlan(response, "JSON parsing failed: ${e.message}")
    }
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
