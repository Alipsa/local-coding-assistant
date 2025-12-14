package se.alipsa.lca.shell

import com.embabel.agent.api.common.Ai
import com.embabel.agent.domain.io.UserInput
import com.embabel.common.ai.model.LlmOptions
import se.alipsa.lca.agent.CodingAssistantAgent
import se.alipsa.lca.agent.CodingAssistantAgent.CodeSnippet
import se.alipsa.lca.agent.PersonaMode
import se.alipsa.lca.agent.Personas
import se.alipsa.lca.review.ReviewSeverity
import se.alipsa.lca.tools.GitTool
import com.embabel.agent.api.common.PromptRunner
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.WebSearchTool
import se.alipsa.lca.tools.CodeSearchTool
import se.alipsa.lca.tools.ContextPacker
import se.alipsa.lca.tools.ContextBudgetManager
import se.alipsa.lca.tools.TokenEstimator
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class ShellCommandsSpec extends Specification {

  SessionState sessionState = new SessionState("default-model", 0.7d, 0.35d, 0, "", true)
  CodingAssistantAgent agent = Mock()
  Ai ai = Mock()
  FileEditingTool fileEditingTool = Mock()
  GitTool gitTool = Stub() {
    isGitRepo() >> false
    stagedDiff() >> new GitTool.GitResult(false, false, 1, "", "")
    isDirty() >> false
    hasStagedChanges() >> false
  }
  EditorLauncher editorLauncher = Stub() {
    edit(_) >> "edited text"
  }
  @TempDir
  Path tempDir
  ShellCommands commands

  def setup() {
    commands = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      gitTool,
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator()),
      tempDir.resolve("reviews.log").toString()
    )
  }

  def "chat uses persona mode and session overrides"() {
    given:
    CodeSnippet snippet = new CodeSnippet("result")
    agent.craftCode(_, ai, PersonaMode.ARCHITECT, _, _) >> snippet

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
    ) >> snippet
  }

  def "review uses review options and system prompt override"() {
    given:
    def reviewed = new CodingAssistantAgent.ReviewedCodeSnippet(
      new CodeSnippet("code"),
      "Findings:\n- [High] src/App.groovy:10 - bug\nTests:\n- test it",
      Personas.REVIEWER
    )
    agent.reviewCode(_, _, ai, _, _) >> reviewed
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
    ) >> reviewed
  }

  def "paste can forward content to chat"() {
    given:
    CodeSnippet snippet = new CodeSnippet("assistant response")
    agent.craftCode(_, ai, PersonaMode.CODER, _, _) >> snippet

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
    ) >> snippet
  }

  def "search formats results"() {
    given:
    def results = [
      new WebSearchTool.SearchResult("T1", "http://example.com", "S1"),
      new WebSearchTool.SearchResult("T2", "http://example.org", "S2")
    ]

    when:
    def out = commands.search("query", 1, "default", "duckduckgo", 15000L, true, null)

    then:
    1 * agent.search("query", { WebSearchTool.SearchOptions opts ->
      opts.limit == 1 &&
      opts.provider == WebSearchTool.SearchProvider.DUCKDUCKGO &&
      opts.webSearchEnabled
    }) >> results
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
      Personas.REVIEWER
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
    int confirmations = 0
    ShellCommands confirming = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      gitTool,
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
    3 * fileEditingTool.applyPatch(_, _) >> patchResult
  }

  def "applyBlocks runs dry-run when requested"() {
    when:
    def result = commands.applyBlocks("file.txt", "blocks", null, true, true)

    then:
    1 * fileEditingTool.applySearchReplaceBlocks("file.txt", "blocks", true) >> new FileEditingTool.SearchReplaceResult(
      false,
      true,
      false,
      [
        new FileEditingTool.BlockResult(0, false, "Replaced", "updated")
      ],
      null,
      List.of()
    )
    result.contains("Dry run")
  }

  def "reviewlog filters by severity and path"() {
    given:
    def reviewed = new CodingAssistantAgent.ReviewedCodeSnippet(
      new CodeSnippet("code"),
      "Findings:\n- [High] src/App.groovy:1 - issue\n- [Low] other - ignore\nTests:\n- test",
      Personas.REVIEWER
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
      Personas.REVIEWER
    )
    agent.reviewCode(_, _, ai, _, _) >> reviewed
    fileEditingTool.readFile(_) >> "content"
    def instants = [java.time.Instant.parse("2025-01-01T00:00:00Z"), java.time.Instant.parse("2025-01-01T00:00:10Z")].iterator()
    ShellCommands clocked = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      gitTool,
      tempDir.resolve("reviews.log").toString()
    ) {
      @Override
      protected java.time.Instant nowInstant() {
        instants.hasNext() ? instants.next() : java.time.Instant.now()
      }
    }
    clocked.review("", "entry1", "default", null, null, null, null, ["src/App.groovy"], false, ReviewSeverity.LOW, false, true)
    clocked.review("", "entry2", "default", null, null, null, null, ["src/App.groovy"], false, ReviewSeverity.LOW, false, true)

    when:
    def out = clocked.reviewLog(ReviewSeverity.LOW, null, 1, 2, "2025-01-01T00:00:05Z", true)

    then:
    out.contains("entry2")
    !out.contains("entry1")
  }

  def "commitSuggest uses staged diff and llm options"() {
    given:
    def stagedDiff = new GitTool.GitResult(true, true, 0, "diff --git a/f b/f\n+change", "")
    GitTool repoGit = Mock()
    _ * repoGit.isGitRepo() >> true
    _ * repoGit.hasStagedChanges() >> true
    _ * repoGit.stagedDiff() >> stagedDiff
    PromptRunner promptRunner = Mock()
    ShellCommands commitCommands = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      repoGit,
      tempDir.resolve("commit.log").toString()
    )
    ai.withLlm(_ as LlmOptions) >> { LlmOptions opts ->
      assert opts.model == "default-model"
      assert opts.temperature == 0.5d
      assert opts.maxTokens == 512
      promptRunner
    }
    promptRunner.generateText(_ as String) >> { String prompt ->
      assert prompt.contains("diff --git")
      assert prompt.contains("focus on bugfix")
      "Subject: Demo\nBody:\n- Added"
    }

    when:
    def message = commitCommands.commitSuggest("default", null, 0.5d, 512, "focus on bugfix")

    then:
    message.contains("Subject")
  }

  def "stage cancels when user declines confirmation"() {
    given:
    GitTool repoGit = Mock()
    ShellCommands staging = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      repoGit,
      tempDir.resolve("stage.log").toString()
    ) {
      @Override
      protected ConfirmChoice confirmAction(String prompt) {
        ConfirmChoice.NO
      }
    }

    when:
    def result = staging.stage(["file.txt"], null, null, true)

    then:
    result == "Staging canceled."
    0 * repoGit.stageFiles(_)
  }
}
