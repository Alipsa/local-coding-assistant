package se.alipsa.lca.shell

import com.embabel.agent.api.common.Ai
import com.embabel.agent.domain.io.UserInput
import com.embabel.common.ai.model.LlmOptions
import groovy.transform.CompileStatic
import org.springframework.shell.standard.ShellComponent
import org.springframework.shell.standard.ShellMethod
import org.springframework.shell.standard.ShellOption
import se.alipsa.lca.agent.CodingAssistantAgent
import se.alipsa.lca.agent.CodingAssistantAgent.CodeSnippet
import se.alipsa.lca.agent.PersonaMode
import se.alipsa.lca.shell.SessionState.SessionSettings
import se.alipsa.lca.tools.WebSearchTool

import java.io.BufferedReader
import java.io.InputStreamReader

@ShellComponent
@CompileStatic
class ShellCommands {

  private final CodingAssistantAgent codingAssistantAgent
  private final Ai ai
  private final SessionState sessionState
  private final EditorLauncher editorLauncher

  ShellCommands(
    CodingAssistantAgent codingAssistantAgent,
    Ai ai,
    SessionState sessionState,
    EditorLauncher editorLauncher
  ) {
    this.codingAssistantAgent = codingAssistantAgent
    this.ai = ai
    this.sessionState = sessionState
    this.editorLauncher = editorLauncher
  }

  @ShellMethod(key = ["chat", "/chat"], value = "Send a prompt to the coding assistant.")
  String chat(
    @ShellOption(help = "Prompt text; multiline supported by quoting or paste mode") String prompt,
    @ShellOption(defaultValue = "default", help = "Session id for persisting options") String session,
    @ShellOption(defaultValue = "CODER", help = "Persona mode: CODER, ARCHITECT, REVIEWER") PersonaMode persona,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override model for this session") String model,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override craft temperature") Double temperature,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override review temperature") Double reviewTemperature,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override max tokens") Integer maxTokens,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Additional system prompt guidance") String systemPrompt
  ) {
    SessionSettings settings = sessionState.update(
      session,
      model,
      temperature,
      reviewTemperature,
      maxTokens,
      systemPrompt
    )
    LlmOptions options = sessionState.craftOptions(settings)
    String system = sessionState.systemPrompt(settings)
    CodeSnippet snippet = codingAssistantAgent.craftCode(
      new UserInput(prompt),
      ai,
      persona,
      options,
      system
    )
    sessionState.appendHistory(session, "User: ${prompt}", "Assistant: ${snippet.text}")
    snippet.text
  }

  @ShellMethod(key = ["review", "/review"], value = "Ask the assistant to review code.")
  String review(
    @ShellOption(help = "Code to review") String code,
    @ShellOption(help = "Review context or request") String prompt,
    @ShellOption(defaultValue = "default", help = "Session id") String session,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override model") String model,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override review temperature") Double reviewTemperature,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override max tokens") Integer maxTokens,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Additional system prompt guidance") String systemPrompt
  ) {
    SessionSettings settings = sessionState.update(session, model, null, reviewTemperature, maxTokens, systemPrompt)
    LlmOptions reviewOptions = sessionState.reviewOptions(settings)
    String system = sessionState.systemPrompt(settings)
    def result = codingAssistantAgent.reviewCode(
      new UserInput(prompt),
      new CodeSnippet(code),
      ai,
      reviewOptions,
      system
    )
    sessionState.appendHistory(session, "User review request: ${prompt}", "Review: ${result.review}")
    result.review
  }

  @ShellMethod(key = ["search", "/search"], value = "Run web search through the agent tool.")
  String search(
    @ShellOption(help = "Query to search") String query,
    @ShellOption(defaultValue = "5", help = "Number of results to show") int limit
  ) {
    codingAssistantAgent.search(query).stream()
      .limit(limit)
      .map { WebSearchTool.SearchResult result ->
        "${result.title} - ${result.url}\n${result.snippet}"
      }
      .toList()
      .join("\n\n")
  }

  @ShellMethod(key = ["edit", "/edit"], value = "Open default editor to draft a prompt, optionally send to assistant.")
  String edit(
    @ShellOption(defaultValue = "", help = "Seed text to prefill in editor") String seed,
    @ShellOption(defaultValue = "false", help = "Send the edited text to /chat when done") boolean send,
    @ShellOption(defaultValue = "default", help = "Session id") String session,
    @ShellOption(defaultValue = "CODER", help = "Persona mode when sending") PersonaMode persona
  ) {
    String content = editorLauncher.edit(seed)
    if (!send) {
      return content
    }
    chat(content, session, persona, null, null, null, null, null)
  }

  @ShellMethod(key = ["paste", "/paste"], value = "Enter paste mode; end input with a line containing only /end.")
  String paste(
    @ShellOption(
      defaultValue = ShellOption.NULL,
      help = "Prefilled content; if omitted, read from stdin"
    ) String content,
    @ShellOption(defaultValue = "/end", help = "Line that terminates paste mode") String endMarker,
    @ShellOption(defaultValue = "false", help = "Send pasted content to /chat") boolean send,
    @ShellOption(defaultValue = "default", help = "Session id") String session,
    @ShellOption(defaultValue = "CODER", help = "Persona for sending") PersonaMode persona
  ) {
    String body = content
    if (body == null) {
      body = readFromStdIn(endMarker)
    }
    if (!send) {
      return body
    }
    chat(body, session, persona, null, null, null, null, null)
  }

  private static final int MAX_PASTE_CHARS = 500_000

  private static String readFromStdIn(String endMarker) {
    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))
    println "Paste your content. End with a line containing only '${endMarker}'."
    StringBuilder builder = new StringBuilder()
    String line
    while ((line = reader.readLine()) != null) {
      if (line == endMarker) {
        break
      }
      if (builder.length() + line.length() + System.lineSeparator().length() > MAX_PASTE_CHARS) {
        throw new IllegalArgumentException("Paste content exceeds ${MAX_PASTE_CHARS} characters.")
      }
      builder.append(line).append(System.lineSeparator())
    }
    builder.toString().stripTrailing()
  }
}
