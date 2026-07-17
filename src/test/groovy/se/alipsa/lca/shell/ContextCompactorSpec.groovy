package se.alipsa.lca.shell

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.PromptRunner
import com.embabel.chat.Conversation
import com.embabel.chat.UserMessage
import com.embabel.chat.AssistantMessage
import com.embabel.chat.support.InMemoryConversation
import com.embabel.common.ai.model.LlmOptions
import se.alipsa.lca.gui.ContextEstimator
import se.alipsa.lca.tools.AgentsMdProvider
import se.alipsa.lca.tools.LocalOnlyState
import spock.lang.Specification

class ContextCompactorSpec extends Specification {

  AgentsMdProvider agentsMdProvider = Stub() {
    appendToSystemPrompt(_) >> { String base -> base }
  }
  SessionState sessionState = new SessionState(
    "default-model",
    0.7d,
    0.35d,
    0,
    "",
    true,
    "htmlunit",
    "jsoup",
    600L,
    "fallback",
    300000L,
    agentsMdProvider,
    new LocalOnlyState(false)
  )
  ContextEstimator contextEstimator = Mock()
  Ai ai = Mock()
  PromptRunner promptRunner = Mock()

  private ContextCompactor compactorWith(boolean enabled = true, int threshold = 80, int keepRecent = 6,
                                          String compactionModel = null) {
    new ContextCompactor(sessionState, contextEstimator, ai, enabled, threshold, keepRecent, compactionModel)
  }

  private static void seedMessages(SessionState state, String sessionId, int count) {
    Conversation conversation = state.getOrCreateConversation(sessionId)
    count.times { int i -> conversation.addMessage(new UserMessage("message ${i}")) }
  }

  def "compact is a no-op when the conversation is at or below the keep-recent floor"() {
    given:
    seedMessages(sessionState, "s1", 5)
    ContextCompactor compactor = compactorWith(true, 80, 6)

    when:
    def result = compactor.compact("s1")

    then:
    !result.compacted
    result.messagesBefore == 5
    result.messagesAfter == 5
    result.summary == null
    0 * ai.withLlm(_)
  }

  def "compact summarizes the older portion and keeps the recent tail verbatim"() {
    given:
    seedMessages(sessionState, "s1", 10)
    ai.withLlm(_ as LlmOptions) >> promptRunner
    promptRunner.generateText(_ as String) >> { String prompt ->
      assert prompt.contains("message 0")
      assert prompt.contains("message 3")
      "a concise summary"
    }
    ContextCompactor compactor = compactorWith(true, 80, 6)

    when:
    def result = compactor.compact("s1")

    then:
    result.compacted
    result.messagesBefore == 10
    result.messagesAfter == 7 // 1 summary message + 6 kept
    result.summary == "a concise summary"

    and: "the session's conversation is actually replaced"
    def newConversation = sessionState.getOrCreateConversation("s1")
    newConversation.messages.size() == 7
    newConversation.messages[0] instanceof AssistantMessage
    newConversation.messages[0].content.contains("a concise summary")
    newConversation.messages[1].content == "message 4"
    newConversation.messages[6].content == "message 9"

    and: "the flat-string history is replaced with a single compacted entry"
    sessionState.history("s1") == ["Compacted summary: a concise summary"]
  }

  def "compact does not mutate anything when the LLM returns a blank summary"() {
    given:
    seedMessages(sessionState, "s1", 10)
    ai.withLlm(_ as LlmOptions) >> promptRunner
    promptRunner.generateText(_ as String) >> "   "
    ContextCompactor compactor = compactorWith(true, 80, 6)
    def before = sessionState.getOrCreateConversation("s1")

    when:
    def result = compactor.compact("s1")

    then:
    !result.compacted
    result.messagesBefore == 10
    result.messagesAfter == 10
    sessionState.getOrCreateConversation("s1").is(before)
  }

  def "compact uses an explicit compaction-model override when configured"() {
    given:
    seedMessages(sessionState, "s1", 10)
    ai.withLlm(_ as LlmOptions) >> { LlmOptions opts ->
      assert opts.model == "small-model"
      promptRunner
    }
    promptRunner.generateText(_ as String) >> "summary"
    ContextCompactor compactor = compactorWith(true, 80, 6, "small-model")

    expect:
    compactor.compact("s1").compacted
  }

  def "shouldAutoCompact respects the enabled flag and threshold"() {
    given:
    contextEstimator.usedPercent("s1") >> usedPercent
    ContextCompactor compactor = compactorWith(enabled, 80, 6)

    expect:
    compactor.shouldAutoCompact("s1") == expected

    where:
    enabled | usedPercent || expected
    true    | 90          || true
    true    | 80          || true
    true    | 79          || false
    false   | 90          || false
  }

  def "autocompactProgressPercent is used-percent relative to the threshold, clamped at 100"() {
    given:
    contextEstimator.usedPercent("s1") >> usedPercent
    ContextCompactor compactor = compactorWith(true, 50, 6)

    expect:
    compactor.autocompactProgressPercent("s1") == expected

    where:
    usedPercent || expected
    0           || 0
    25          || 50
    50          || 100
    100         || 100
  }

  def "isAutocompactEnabled reflects the configured flag"() {
    expect:
    compactorWith(true).isAutocompactEnabled()
    !compactorWith(false).isAutocompactEnabled()
  }
}
