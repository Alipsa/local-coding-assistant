package se.alipsa.lca.memory

import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.LlmOptions
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Detects "surprising learnings" - the model not knowing something, or a fact contradicting
 * what it would normally assume from training data - and stores them as memories.
 *
 * Heuristically gated: a cheap local keyword scan runs first; the extra LLM classification
 * call only fires when the scan finds a signal worth checking, so this doesn't cost a full
 * LLM round-trip on every single turn.
 */
@Component
@CompileStatic
class SurprisingLearningDetector {

  private static final List<String> HEDGING_PHRASES = [
    "i don't know", "i do not know", "i'm not sure", "i am not sure",
    "i don't have information", "i do not have information",
    "i don't recall", "i do not recall",
  ].asImmutable()

  private static final List<String> CORRECTION_MARKERS = [
    "actually,", "actually ", "that's wrong", "that is wrong", "no, ", "correction:", "to be clear,",
  ].asImmutable()

  private final MemoryStore memoryStore
  private final MemorySettings settings
  private final ProjectScopeResolver projectScopeResolver
  private final String fallbackModel

  SurprisingLearningDetector(
    MemoryStore memoryStore,
    MemorySettings settings,
    ProjectScopeResolver projectScopeResolver,
    @Value('${assistant.llm.fallback-model:${embabel.models.llms.cheapest:}}') String fallbackModel
  ) {
    this.memoryStore = memoryStore
    this.settings = settings
    this.projectScopeResolver = projectScopeResolver
    this.fallbackModel = (fallbackModel != null && fallbackModel.trim()) ? fallbackModel.trim() : null
  }

  /**
   * Automatic memories are always project-scoped, never global - only the explicit
   * rememberFact() tool exposes the global option, since deciding a fact applies everywhere
   * is a judgment call for the user to make, not an automatic classifier.
   */
  void maybeRemember(String userInput, String assistantReply, String sessionId, Ai ai) {
    if (!settings.enabled || !settings.surprisingLearningDetectionEnabled) {
      return
    }
    if (!heuristicMatch(userInput, assistantReply)) {
      return
    }
    String model = settings.surprisingLearningModel?.trim() ?: fallbackModel
    if (!model) {
      return
    }
    LlmOptions options = LlmOptions.withModel(model)
    SurpriseVerdict verdict = ai.withLlm(options)
      .createObject(buildClassificationPrompt(userInput, assistantReply), SurpriseVerdict)
    if (verdict?.surprising && verdict.fact?.trim()) {
      memoryStore.remember(verdict.fact.trim(), sessionId, projectScopeResolver.currentProjectId())
    }
  }

  private static boolean heuristicMatch(String userInput, String assistantReply) {
    String reply = (assistantReply ?: "").toLowerCase()
    String input = (userInput ?: "").toLowerCase()
    HEDGING_PHRASES.any { String phrase -> reply.contains(phrase) } ||
      CORRECTION_MARKERS.any { String marker -> input.contains(marker) }
  }

  private static String buildClassificationPrompt(String userInput, String assistantReply) {
    """
Below is one turn of a conversation between a user and a coding assistant.
Determine whether the assistant's reply reveals a "surprising learning" - something the
assistant did not already know, or a fact that contradicts what it would normally assume
from its training data (e.g. the user corrected it, or it admitted not knowing something
that turned out to have a concrete answer in this turn).

User: ${userInput}
Assistant: ${assistantReply}

If there is a surprising learning, set surprising=true and fact to a single concise
sentence stating the fact to remember. Otherwise set surprising=false and leave fact blank.
""".stripIndent().trim()
  }

  @Canonical
  @CompileStatic
  static class SurpriseVerdict {
    boolean surprising
    String fact
  }
}
