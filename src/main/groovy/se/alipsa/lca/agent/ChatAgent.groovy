package se.alipsa.lca.agent

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.common.Ai
import com.embabel.agent.prompt.persona.RoleGoalBackstory
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Conversation
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile

import java.util.Objects

@Agent(name = "lca-chat", description = "Conversation-based coding assistant")
@Profile("!test")
@CompileStatic
class ChatAgent {

  private final int snippetWordCount
  private static final String DEFAULT_RESPONSE_FORMAT = """
Respond with:
Plan:
- short bullet list of steps rooted in the repository
Implementation:
```groovy
// target-file-or-class
// implementation
```
Notes:
- risks, required configuration, and follow-up tasks
""".stripIndent().trim()

  ChatAgent(@Value('${snippetWordCount:200}') int snippetWordCount) {
    this.snippetWordCount = snippetWordCount
  }

  @AchievesGoal(description = "Respond to a user message in an ongoing coding conversation")
  @Action(canRerun = true, trigger = UserMessage)
  AssistantMessage respond(Conversation conversation, UserMessage userMessage, ChatRequest request, Ai ai) {
    Objects.requireNonNull(conversation, "conversation must not be null")
    Objects.requireNonNull(userMessage, "userMessage must not be null")
    Objects.requireNonNull(request, "request must not be null")
    Objects.requireNonNull(ai, "ai must not be null")
    if (!conversation.messages.contains(userMessage)) {
      conversation.addMessage(userMessage)
    }
    PersonaTemplate template = personaTemplate(request.persona)
    String systemPrompt = buildSystemPrompt(template, request)
    LlmOptions options = request.options ?: LlmOptions.withDefaultLlm()
    AssistantMessage reply = ai
      .withLlm(options)
      .withPromptContributor(template.persona)
      .withSystemPrompt(systemPrompt)
      .respond(conversation.messages)
    conversation.addMessage(reply)
    reply
  }

  private String buildSystemPrompt(PersonaTemplate template, ChatRequest request) {
    String extraSystem = request?.systemPrompt?.trim()
    String responseFormat = request?.responseFormat?.trim()
    String formatBlock = responseFormat ?: DEFAULT_RESPONSE_FORMAT
    """
You are a repository-aware Groovy/Spring Boot coding assistant for a local-only CLI project.
Follow these rules:
- Indent with 2 spaces and keep lines under 120 characters.
- Use Groovy 5.0.3 with @CompileStatic when possible; avoid deprecated APIs.
- Map changes to existing files when possible and mention target file paths.
- Prefer Search and Replace Blocks for multi-file updates; avoid TODO placeholders.
- Include imports, validation, and error handling; favour testable designs and mention Spock coverage ideas.
${template.instructions}
${extraSystem ? "Additional system guidance: ${extraSystem}\n" : ""}
Keep narrative text under ${snippetWordCount} words; code may exceed that to stay correct.
${formatBlock}
""".stripIndent().trim()
  }

  private PersonaTemplate personaTemplate(PersonaMode mode) {
    switch (mode) {
      case PersonaMode.ARCHITECT:
        return new PersonaTemplate(
          Personas.ARCHITECT,
          "Architect Mode: Describe reasoning and trade-offs before code; include architecture notes."
        )
      case PersonaMode.REVIEWER:
        return new PersonaTemplate(
          Personas.REVIEWER,
          "Reviewer Mode: Be critical and flag security issues, unsafe IO, missing validation, " +
            "and deployment risks."
        )
      case PersonaMode.CODER:
      default:
        return new PersonaTemplate(
          Personas.CODER,
          "Coder Mode: Keep narration minimal; prefer code blocks and concise repository-scoped steps."
        )
    }
  }

  @Canonical
  @CompileStatic
  static class PersonaTemplate {
    RoleGoalBackstory persona
    String instructions
  }
}
