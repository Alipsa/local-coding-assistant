package se.alipsa.lca.shell

import com.embabel.agent.api.common.Ai
import com.embabel.agent.domain.io.UserInput
import com.embabel.common.ai.model.LlmOptions
import se.alipsa.lca.agent.CodingAssistantAgent
import se.alipsa.lca.agent.CodingAssistantAgent.CodeSnippet
import se.alipsa.lca.agent.PersonaMode
import spock.lang.Specification

class ShellCommandsSpec extends Specification {

  SessionState sessionState = new SessionState("default-model", 0.6d, 0.3d, 0, "")
  CodingAssistantAgent agent = Mock()
  Ai ai = Mock()
  EditorLauncher editorLauncher = Stub() {
    edit(_) >> "edited text"
  }
  ShellCommands commands = new ShellCommands(agent, ai, sessionState, editorLauncher)

  def "chat uses persona mode and session overrides"() {
    given:
    CodeSnippet snippet = new CodeSnippet("result")
    agent.craftCode(_, ai, PersonaMode.ARCHITECT, _, "extra system") >> snippet

    when:
    def response = commands.chat(
      "prompt text",
      "s1",
      PersonaMode.ARCHITECT,
      "custom-model",
      0.9d,
      0.4d,
      2048,
      "extra system"
    )

    then:
    response == "result"
    1 * agent.craftCode(
      { UserInput ui -> ui.getContent() == "prompt text" },
      ai,
      PersonaMode.ARCHITECT,
      { LlmOptions opts ->
        opts.getModel() == "custom-model" &&
        opts.getTemperature() == 0.9d &&
        opts.getMaxTokens() == 2048
      },
      "extra system"
    )
  }

  def "review uses review options and system prompt override"() {
    given:
    def reviewed = new CodingAssistantAgent.ReviewedCodeSnippet(new CodeSnippet("code"), "review", null)
    agent.reviewCode(_, _, ai, _, "system") >> reviewed

    when:
    def response = commands.review("println 'hi'", "check safety", "default", null, 0.2d, 1024, "system")

    then:
    response == "review"
    1 * agent.reviewCode(
      { UserInput ui -> ui.getContent() == "check safety" },
      { CodeSnippet code -> code.text == "println 'hi'" },
      ai,
      { LlmOptions opts ->
        opts.getModel() == "default-model" &&
        opts.getTemperature() == 0.2d &&
        opts.getMaxTokens() == 1024
      },
      "system"
    )
  }

  def "paste can forward content to chat"() {
    given:
    CodeSnippet snippet = new CodeSnippet("assistant response")
    agent.craftCode(_, ai, PersonaMode.CODER, _, "") >> snippet

    when:
    def result = commands.paste("multi\nline", "/end", true, "default", PersonaMode.CODER)

    then:
    result == "assistant response"
    1 * agent.craftCode(
      { UserInput ui -> ui.getContent() == "multi\nline" },
      ai,
      PersonaMode.CODER,
      _ as LlmOptions,
      ""
    )
  }

  def "edit returns edited text when send is false"() {
    when:
    def text = commands.edit("seed", false, "default", PersonaMode.CODER)

    then:
    text == "edited text"
    0 * agent._
  }
}
