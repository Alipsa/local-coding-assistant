package se.alipsa.lca.team

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.LlmOptions
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile

import java.time.Duration

@Agent(name = "lca-team-dispatcher", description = "Classify a coding task as simple or complex")
@Profile("!test")
@CompileStatic
class DispatcherAgent {

  private static final Logger log = LoggerFactory.getLogger(DispatcherAgent)

  private final Ai ai
  private final TeamSettings settings

  DispatcherAgent(Ai ai, TeamSettings settings) {
    this.ai = ai
    this.settings = settings
  }

  @AchievesGoal(description = "Classify a coding task as simple or complex")
  @Action(canRerun = true, trigger = DispatchRequest)
  DispatchResult classify(DispatchRequest request) {
    String prompt = request?.prompt
    if (prompt == null || prompt.trim().isEmpty()) {
      return new DispatchResult(false, "Empty or null prompt")
    }

    String classificationPrompt = buildClassificationPrompt(prompt)

    LlmOptions options = LlmOptions.withModel(settings.dispatcherModel)
      .withTemperature(settings.dispatcherTemperature)
      .withTimeout(Duration.ofSeconds(settings.dispatcherTimeoutSeconds))

    try {
      String response = ai.withLlm(options).generateText(classificationPrompt)
      parseResponse(response)
    } catch (Exception e) {
      log.warn("Dispatcher LLM call failed, defaulting to complex", e)
      new DispatchResult(true, "LLM error: ${e.message}".toString())
    }
  }

  private String buildClassificationPrompt(String prompt) {
    """\
Classify the following coding task as either SIMPLE or COMPLEX.

A SIMPLE task:
- Affects a single file or a small, localised change
- Has a clear, straightforward implementation
- Examples: adding a method, fixing a typo, renaming a variable

A COMPLEX task:
- Requires changes across multiple files or components
- Involves refactoring, redesigning, or architectural changes
- Needs careful planning or coordination between parts

Respond with exactly one word — SIMPLE or COMPLEX — followed by a colon and a brief reason.
Example responses:
SIMPLE: straightforward single-file change
COMPLEX: multi-file refactoring with dependency updates

=== TASK ===
${prompt}""".toString()
  }

  DispatchResult parseResponse(String response) {
    if (response == null || response.trim().isEmpty()) {
      log.warn("Empty response from dispatcher LLM, defaulting to complex")
      return new DispatchResult(true, "Empty LLM response")
    }

    String upper = response.trim().toUpperCase(Locale.ROOT)

    if (upper.startsWith("SIMPLE")) {
      String reason = extractReason(response, "SIMPLE")
      return new DispatchResult(false, reason)
    }

    if (upper.startsWith("COMPLEX")) {
      String reason = extractReason(response, "COMPLEX")
      return new DispatchResult(true, reason)
    }

    // Check anywhere in the response
    if (upper.contains("SIMPLE") && !upper.contains("COMPLEX")) {
      return new DispatchResult(false, response.trim())
    }

    if (upper.contains("COMPLEX")) {
      return new DispatchResult(true, response.trim())
    }

    log.warn("Unparseable dispatcher response, defaulting to complex: {}", response)
    new DispatchResult(true, "Unparseable response: ${response.trim()}".toString())
  }

  private String extractReason(String response, String label) {
    String trimmed = response.trim()
    int colonIdx = trimmed.indexOf(':')
    if (colonIdx >= 0 && colonIdx < trimmed.length() - 1) {
      return trimmed.substring(colonIdx + 1).trim()
    }
    label.toLowerCase(Locale.ROOT)
  }

  @Canonical
  @CompileStatic
  static class DispatchResult {
    boolean complex
    String reason
  }
}
