package se.alipsa.lca.shell

import com.embabel.agent.api.common.Ai
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Conversation
import com.embabel.chat.Message
import com.embabel.chat.support.InMemoryConversation
import com.embabel.common.ai.model.LlmOptions
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import se.alipsa.lca.gui.ContextEstimator

/**
 * Summarizes and trims a session's conversation once it grows large, so {@code ChatAgent} keeps
 * sending a bounded message list to the LLM instead of the entire, ever-growing history. Reuses
 * {@link ContextEstimator#usedPercent} (already the GUI footer's "how full is context" number) as
 * the single source of truth for both the auto-compact trigger and the manual {@code /compact}
 * command's notion of "how full", so the two never disagree.
 */
@Component
@CompileStatic
class ContextCompactor {

  private final SessionState sessionState
  private final ContextEstimator contextEstimator
  private final Ai ai
  private final boolean autocompactEnabled
  private final int thresholdPercent
  private final int keepRecentMessages
  // Optional override for the summarization call; falls back to the session's own craft model
  // when unset, so compaction doesn't silently switch models on the user without configuration.
  private final String compactionModel
  // The summarization call always gets its own budget, independent of the session's chat
  // max-tokens override — otherwise a small --max-tokens set for chat replies would silently
  // truncate the compaction summary too.
  private final int compactionMaxTokens

  ContextCompactor(
    SessionState sessionState,
    ContextEstimator contextEstimator,
    Ai ai,
    @Value('${assistant.context.autocompact-enabled:true}') boolean autocompactEnabled,
    @Value('${assistant.context.autocompact-threshold-percent:80}') int thresholdPercent,
    @Value('${assistant.context.autocompact-keep-recent:6}') int keepRecentMessages,
    @Value('${assistant.context.compaction-model:}') String compactionModel,
    @Value('${assistant.context.compaction-max-tokens:1024}') int compactionMaxTokens
  ) {
    this.sessionState = sessionState
    this.contextEstimator = contextEstimator
    this.ai = ai
    this.autocompactEnabled = autocompactEnabled
    this.thresholdPercent = thresholdPercent > 0 ? thresholdPercent : 80
    this.keepRecentMessages = keepRecentMessages > 0 ? keepRecentMessages : 6
    this.compactionModel = (compactionModel != null && compactionModel.trim()) ? compactionModel.trim() : null
    this.compactionMaxTokens = compactionMaxTokens > 0 ? compactionMaxTokens : 1024
  }

  boolean isAutocompactEnabled() {
    autocompactEnabled
  }

  /** Whether the session is currently over the auto-compact trigger threshold. */
  boolean shouldAutoCompact(String sessionId) {
    autocompactEnabled && contextEstimator.usedPercent(sessionId) >= thresholdPercent
  }

  /** Progress toward the auto-compact threshold, 0-100 (100 meaning "would trigger now"). */
  int autocompactProgressPercent(String sessionId) {
    if (thresholdPercent <= 0) {
      return 0
    }
    (int) Math.min(100L, Math.round((contextEstimator.usedPercent(sessionId) * 100.0d) / thresholdPercent))
  }

  /**
   * Summarizes the older portion of the session's conversation via the LLM and replaces it with
   * one summary message plus the most recent {@code keepRecentMessages} messages verbatim. Also
   * rewrites the parallel flat-string {@link SessionState#history} to cover the summary AND those
   * kept messages, so {@link ContextEstimator}'s percent reflects what is actually still sent to
   * the LLM every subsequent turn, not just the summary's size.
   */
  CompactionResult compact(String sessionId) {
    Conversation conversation = sessionState.getOrCreateConversation(sessionId)
    List<Message> messages = conversation.messages
    int before = messages.size()
    if (before <= keepRecentMessages) {
      return new CompactionResult(false, before, before, null)
    }
    List<Message> toSummarize = messages.subList(0, before - keepRecentMessages)
    List<Message> toKeep = messages.subList(before - keepRecentMessages, before)
    String transcript = toSummarize.collect { Message m -> "${m.role.displayName}: ${m.content}" }.join("\n")
    LlmOptions options = resolveOptions(sessionId)
    String summary = ai.withLlm(options).generateText(buildSummaryPrompt(transcript))
    if (summary == null || summary.trim().isEmpty()) {
      return new CompactionResult(false, before, before, null)
    }
    String trimmedSummary = summary.trim()
    AssistantMessage summaryMessage = new AssistantMessage(
      "Summary of earlier conversation (compacted to save context space):\n${trimmedSummary}")
    List<Message> newMessages = new ArrayList<>()
    newMessages.add(summaryMessage)
    newMessages.addAll(toKeep)
    Conversation compacted = new InMemoryConversation(newMessages, conversation.id, false, conversation.assetTracker)
    sessionState.replaceConversation(sessionId, compacted)
    List<String> newHistory = new ArrayList<>()
    newHistory.add("Compacted summary: ${trimmedSummary}".toString())
    newHistory.addAll(toKeep.collect { Message m -> "${m.role.displayName}: ${m.content}".toString() })
    sessionState.replaceHistory(sessionId, newHistory)
    new CompactionResult(true, before, newMessages.size(), trimmedSummary)
  }

  /**
   * LlmOptions for the summarization call: the configured override model if set, otherwise the
   * session's own craft model. Mirrors {@code SessionState.buildOptions}'s defensive
   * {@code setModel} workaround — {@code LlmOptions.withModel(...)} only sets
   * {@code modelSelectionCriteria}, not the plain {@code model} field some callers read directly.
   * {@code maxTokens} is always overridden to {@link #compactionMaxTokens}, deliberately NOT
   * inherited from the session's craft options — a small {@code --max-tokens} set for chat
   * replies must not also cap (and silently truncate) the compaction summary.
   */
  private LlmOptions resolveOptions(String sessionId) {
    LlmOptions options
    if (compactionModel == null) {
      options = sessionState.craftOptions(sessionState.getOrCreate(sessionId))
    } else {
      options = LlmOptions.withModel(compactionModel)
      if (options.getModel() == null) {
        options.setModel(compactionModel)
      }
    }
    options.withTemperature(0.2d).withMaxTokens(compactionMaxTokens)
  }

  private static String buildSummaryPrompt(String transcript) {
    """
Summarize the following conversation between a user and an AI coding assistant.
Preserve: key decisions made, file paths touched, unresolved questions, and the current task state.
Be concise (a short paragraph or a few bullet points) — this replaces the raw messages below to save
context space, so it must retain everything a continuation of the conversation would need.

Conversation:
${transcript}
""".stripIndent().trim()
  }

  @Canonical
  @CompileStatic
  static class CompactionResult {
    boolean compacted
    int messagesBefore
    int messagesAfter
    String summary
  }
}
