package se.alipsa.lca.shell

import com.embabel.agent.api.common.Ai
import com.embabel.agent.domain.io.UserInput
import com.embabel.common.ai.model.LlmOptions
import se.alipsa.lca.agent.CodingAssistantAgent
import se.alipsa.lca.agent.CodingAssistantAgent.CodeSnippet
import se.alipsa.lca.agent.PersonaMode
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.WebSearchTool
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class ShellCommandsSpec extends Specification {

  SessionState sessionState = new SessionState("default-model", 0.7d, 0.35d, 0, "")
  CodingAssistantAgent agent = Mock()
  Ai ai = Mock()
  FileEditingTool fileEditingTool = Stub()
  EditorLauncher editorLauncher = Stub() {
    edit(_) >> "edited text"
  }
  @TempDir
  Path tempDir
  ShellCommands commands

  def setup() {
    commands = new ShellCommands(agent, ai, sessionState, editorLauncher, fileEditingTool, tempDir.resolve("reviews.log").toString())
  }

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
    def reviewed = new CodingAssistantAgent.ReviewedCodeSnippet(
      new CodeSnippet("code"),
      "Findings:\n- [High] src/App.groovy:10 - bug\nTests:\n- test it",
      null
    )
    agent.reviewCode(_, _, ai, _, "system") >> reviewed
    fileEditingTool.readFile(_) >> "content"

    when:
    def response = commands.review(
      "println 'hi'",
      "check safety",
      "default",
      null,
      0.2d,
      1024,
      "system",
      ["src/App.groovy"],
      false,
      ReviewSeverity.LOW,
      true,
      false
    )

    then:
    response.contains("[High] src/App.groovy:10 - bug")
    !response.contains("\u001B[")
    response.contains("Tests:")
    1 * agent.reviewCode(
      { UserInput ui -> ui.getContent() == "check safety" },
      { CodeSnippet snippet -> snippet.text.contains("println 'hi'") && snippet.text.contains("src/App.groovy") },
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

  def "search formats results"() {
    given:
    def results = [
      new WebSearchTool.SearchResult("T1", "http://example.com", "S1"),
      new WebSearchTool.SearchResult("T2", "http://example.org", "S2")
    ]
    agent.search(_) >> results

    when:
    def out = commands.search("query", 1)

    then:
    out.contains("T1 - http://example.com")
    out.contains("S1")
  }

  def "edit returns edited text when send is false"() {
    when:
    def text = commands.edit("seed", false, "default", PersonaMode.CODER)

    then:
    text == "edited text"
    0 * agent._
  }

  def "applyPatch runs a dry-run when requested"() {
    given:
    fileEditingTool.applyPatch(_, true) >> new FileEditingTool.PatchResult(
      false,
      true,
      false,
      [
        new FileEditingTool.FilePatchResult(
          "f.txt",
          false,
          false,
          false,
          false,
          true,
          null,
          "ok",
          "preview"
        )
      ],
      List.of()
    )

    when:
    def output = commands.applyPatch("patch body", null, true, true)

    then:
    output.contains("Dry run")
    output.contains("f.txt")
    output.contains("preview")
  }

  def "revert reports backup path"() {
    given:
    fileEditingTool.revertLatestBackup("f.txt", false) >> new FileEditingTool.EditResult(
      true,
      false,
      "backups/f.txt.1.bak",
      "Restored f.txt",
      "f.txt"
    )

    when:
    def message = commands.revert("f.txt", false)

    then:
    message.contains("Restored f.txt")
    message.contains("backups/f.txt.1.bak")
  }

  def "context uses symbol lookup when provided"() {
    given:
    fileEditingTool.contextBySymbol("src/App.groovy", "symbol", 2) >> new FileEditingTool.TargetedEditContext(
      "src/App.groovy",
      5,
      5,
      20,
      "   5 | line"
    )

    when:
    def result = commands.context("src/App.groovy", null, null, "symbol", 2)

    then:
    result.contains("src/App.groovy:5-5")
    result.contains("line")
  }

  def "review writes to log path"() {
    given:
    def reviewed = new CodingAssistantAgent.ReviewedCodeSnippet(
      new CodeSnippet("code"),
      "Findings:\n- [Low] general - note\nTests:\n- test",
      null
    )
    agent.reviewCode(_, _, ai, _, _) >> reviewed
    fileEditingTool.readFile(_) >> "content"

    when:
    commands.review("", "log it", "default", null, null, null, null, ["src/App.groovy"], false, ReviewSeverity.LOW, false, true)

    then:
    Files.exists(tempDir.resolve("reviews.log"))
  }

  def "applyPatch honors confirm all choice"() {
    given:
    def patchResult = new FileEditingTool.PatchResult(true, false, false, List.of(), List.of())
    fileEditingTool.applyPatch(_, true) >> patchResult
    fileEditingTool.applyPatch(_, false) >> patchResult
    int confirmations = 0
    ShellCommands confirming = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      tempDir.resolve("other.log").toString()
    ) {
      @Override
      protected ConfirmChoice confirmAction(String prompt) {
        confirmations++
        ConfirmChoice.ALL
      }
    }

    when:
    confirming.applyPatch("patch body", null, false, true)
    confirmations = 0
    confirming.applyPatch("second patch", null, false, true)

    then:
    confirmations == 0
    3 * fileEditingTool.applyPatch(_, _)
  }

  def "applyBlocks runs dry-run when requested"() {
    given:
    fileEditingTool.applySearchReplaceBlocks(_, _, true) >> new FileEditingTool.SearchReplaceResult(
      false,
      true,
      false,
      [
        new FileEditingTool.BlockResult(0, false, "Replaced", "updated")
      ],
      null,
      List.of()
    )

    when:
    def result = commands.applyBlocks("file.txt", "blocks", null, true, true)

    then:
    result.contains("Dry run")
    1 * fileEditingTool.applySearchReplaceBlocks("file.txt", "blocks", true)
  }

  def "reviewlog filters by severity and path"() {
    given:
    def reviewed = new CodingAssistantAgent.ReviewedCodeSnippet(
      new CodeSnippet("code"),
      "Findings:\n- [High] src/App.groovy:1 - issue\n- [Low] other - ignore\nTests:\n- test",
      null
    )
    agent.reviewCode(_, _, ai, _, _) >> reviewed
    fileEditingTool.readFile(_) >> "content"
    commands.review("", "log it", "default", null, null, null, null, ["src/App.groovy"], false, ReviewSeverity.LOW, false, true)

    when:
    def out = commands.reviewLog(ReviewSeverity.HIGH, "src/App.groovy", 5, 1, null, true)

    then:
    out.contains("High")
    !out.contains("Low")
  }

  def "reviewlog respects pagination and since"() {
    given:
    def reviewed = new CodingAssistantAgent.ReviewedCodeSnippet(
      new CodeSnippet("code"),
      "Findings:\n- [High] src/App.groovy:1 - issue\nTests:\n- test",
      null
    )
    agent.reviewCode(_, _, ai, _, _) >> reviewed
    fileEditingTool.readFile(_) >> "content"
    commands.review("", "entry1", "default", null, null, null, null, ["src/App.groovy"], false, ReviewSeverity.LOW, false, true)
    Thread.sleep(5)
    commands.review("", "entry2", "default", null, null, null, null, ["src/App.groovy"], false, ReviewSeverity.LOW, false, true)
    String timestamp = java.time.Instant.now().minusSeconds(1).toString()

    when:
    def out = commands.reviewLog(ReviewSeverity.LOW, null, 1, 2, timestamp, true)

    then:
    out.contains("entry2")
    !out.contains("entry1")
  }
}
