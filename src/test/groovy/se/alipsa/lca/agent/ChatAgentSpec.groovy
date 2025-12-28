package se.alipsa.lca.agent

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.PromptRunner
import com.embabel.chat.AssistantMessage
import com.embabel.chat.UserMessage
import com.embabel.chat.support.InMemoryConversation
import com.embabel.common.ai.model.LlmOptions
import spock.lang.Specification

class ChatAgentSpec extends Specification {

  def "respond appends assistant reply to conversation"() {
    given:
    PromptRunner runner = Mock()
    Ai ai = Mock()
    ai.withLlm(_ as LlmOptions) >> runner
    runner.withPromptContributor(_) >> runner
    runner.respond(_ as List) >> new AssistantMessage("ok")
    ChatAgent agent = new ChatAgent(200)
    def conversation = new InMemoryConversation()
    def userMessage = new UserMessage("Hello")
    conversation.addMessage(userMessage)
    ChatRequest request = new ChatRequest(PersonaMode.CODER, LlmOptions.withModel("m"), "extra")

    when:
    def reply = agent.respond(conversation, userMessage, request, ai)

    then:
    reply.textContent == "ok"
    conversation.messages.any { it instanceof AssistantMessage && it.textContent == "ok" }
    1 * runner.withSystemPrompt({ String prompt -> prompt.contains("Additional system guidance: extra") }) >> runner
  }
}
