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
import se.alipsa.lca.tools.CommandRunner
import se.alipsa.lca.tools.CommandPolicy
import se.alipsa.lca.tools.WebSearchTool
import se.alipsa.lca.tools.CodeSearchTool
import se.alipsa.lca.tools.ContextPacker
import se.alipsa.lca.tools.ContextBudgetManager
import se.alipsa.lca.tools.ModelRegistry
import se.alipsa.lca.tools.TreeTool
import se.alipsa.lca.tools.TokenEstimator
import se.alipsa.lca.tools.SastTool
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class ShellCommandsSpec extends Specification {

  SessionState sessionState = new SessionState("default-model", 0.7d, 0.35d, 0, "", true, false, "fallback-model")
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
  CommandRunner commandRunner = Stub() {
    run(_, _, _) >> new CommandRunner.CommandResult(true, false, 0, "", false, null)
  }
  CommandPolicy commandPolicy = new CommandPolicy("", "")
  ModelRegistry modelRegistry = Stub() {
    listModels() >> ["default-model", "fallback-model", "custom-model"]
    isModelAvailable(_) >> true
    checkHealth() >> new ModelRegistry.Health(true, "ok")
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
      commandRunner,
      commandPolicy,
      modelRegistry,
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
    agent.reviewCode(_, _, ai, _, _, _) >> reviewed
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
      false,
      false,
      false
    )

    then:
    response.contains("=== Review ===")
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
      "system",
      _
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
    out.contains("=== Web Search ===")
    out.contains("Results: 2")
    out.contains("1. T1 - http://example.com")
    out.contains("S1")
  }

  def "search rejects blank query"() {
    when:
    commands.search("  ", 5, "default", "duckduckgo", 15000L, true, null)

    then:
    thrown(IllegalArgumentException)
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
    output.contains("=== Edit Preview ===")
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
    message.contains("=== Edit Result ===")
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
    agent.reviewCode(_, _, ai, _, _, _) >> reviewed
    fileEditingTool.readFile(_) >> "content"

    when:
    commands.review("", "log it", "default", null, null, null, null, ["src/App.groovy"], false, ReviewSeverity.LOW, false, true, false, false)

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
      commandRunner,
      modelRegistry,
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
    result.contains("=== Edit Preview ===")
    result.contains("Dry run")
  }

  def "reviewlog filters by severity and path"() {
    given:
    def reviewed = new CodingAssistantAgent.ReviewedCodeSnippet(
      new CodeSnippet("code"),
      "Findings:\n- [High] src/App.groovy:1 - issue\n- [Low] other - ignore\nTests:\n- test",
      Personas.REVIEWER
    )
    agent.reviewCode(_, _, ai, _, _, _) >> reviewed
    fileEditingTool.readFile(_) >> "content"
    commands.review("", "log it", "default", null, null, null, null, ["src/App.groovy"], false, ReviewSeverity.LOW, false, true, false, false)

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
    agent.reviewCode(_, _, ai, _, _, _) >> reviewed
    fileEditingTool.readFile(_) >> "content"
    def instants = [java.time.Instant.parse("2025-01-01T00:00:00Z"), java.time.Instant.parse("2025-01-01T00:00:10Z")].iterator()
    ShellCommands clocked = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      gitTool,
      commandRunner,
      modelRegistry,
      tempDir.resolve("reviews.log").toString()
    ) {
      @Override
      protected java.time.Instant nowInstant() {
        instants.hasNext() ? instants.next() : java.time.Instant.now()
      }
    }
    clocked.review("", "entry1", "default", null, null, null, null, ["src/App.groovy"], false, ReviewSeverity.LOW, false, true, false, false)
    clocked.review("", "entry2", "default", null, null, null, null, ["src/App.groovy"], false, ReviewSeverity.LOW, false, true, false, false)

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
    CommandRunner runTool = Stub()
    ShellCommands commitCommands = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      repoGit,
      runTool,
      modelRegistry,
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
    def message = commitCommands.commitSuggest("default", null, 0.5d, 512, "focus on bugfix", true, false)

    then:
    message.contains("Subject")
  }

  def "commitSuggest blocks when secrets are detected"() {
    given:
    def stagedDiff = new GitTool.GitResult(true, true, 0, "AKIA1234567890ABCDEF\n", "")
    GitTool repoGit = Mock()
    _ * repoGit.isGitRepo() >> true
    _ * repoGit.hasStagedChanges() >> true
    _ * repoGit.stagedDiff() >> stagedDiff
    ShellCommands commitCommands = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      repoGit,
      commandRunner,
      modelRegistry,
      tempDir.resolve("commit.log").toString()
    )

    when:
    def message = commitCommands.commitSuggest("default", null, null, null, null, true, false)

    then:
    message.contains("Potential secrets detected")
    0 * ai.withLlm(_)
  }

  def "commitSuggest proceeds when allowSecrets is enabled"() {
    given:
    def stagedDiff = new GitTool.GitResult(true, true, 0, "AKIA1234567890ABCDEF\n", "")
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
      commandRunner,
      modelRegistry,
      tempDir.resolve("commit.log").toString()
    )
    ai.withLlm(_ as LlmOptions) >> { LlmOptions opts -> promptRunner }
    promptRunner.generateText(_ as String) >> { String prompt ->
      assert prompt.contains("User acknowledged potential secrets in staged diff.")
      "Subject: Allowed"
    }

    when:
    def message = commitCommands.commitSuggest("default", null, null, null, null, true, true)

    then:
    message.contains("Subject: Allowed")
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
      commandRunner,
      modelRegistry,
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

  def "gitStatus formats output"() {
    given:
    GitTool repoGit = Stub(GitTool) {
      status(false) >> new GitTool.GitResult(true, true, 0, "clean", "")
    }
    ShellCommands cmds = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      repoGit,
      commandRunner,
      modelRegistry,
      tempDir.resolve("status.log").toString()
    )

    when:
    def out = cmds.gitStatus(false)

    then:
    out.contains("clean")
  }

  def "buildSastBlock sanitizes findings"() {
    given:
    SastTool sastTool = Stub() {
      run(_ as List<String>) >> new SastTool.SastResult(
        true,
        true,
        null,
        [new SastTool.SastFinding("HIGH", "src/main/java/Auth.java", 42, "ghp_1234567890abcdef1234567890abcdef1234")]
      )
    }
    ShellCommands cmds = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      gitTool,
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator()),
      commandRunner,
      commandPolicy,
      modelRegistry,
      tempDir.resolve("sast.log").toString(),
      null,
      sastTool
    )
    def method = ShellCommands.getDeclaredMethod("buildSastBlock", boolean, List)
    method.accessible = true

    when:
    String block = method.invoke(cmds, true, ["src"])

    then:
    block.contains("SAST:")
    !block.contains("ghp_1234567890abcdef")
    block.contains("REDACTED")
  }

  def "gitDiff uses git tool output"() {
    given:
    GitTool repoGit = Stub(GitTool) {
      diff(true, ["src/App.groovy"], 2, false) >> new GitTool.GitResult(true, true, 0, "diff output", "")
    }
    ShellCommands cmds = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      repoGit,
      commandRunner,
      modelRegistry,
      tempDir.resolve("diff.log").toString()
    )

    when:
    def out = cmds.gitDiff(true, 2, ["src/App.groovy"], false)

    then:
    out.contains("diff output")
  }

  def "gitApply runs confirmation and apply"() {
    given:
    GitTool repoGit = Mock() {
      1 * applyPatch("patch", false, true) >> new GitTool.GitResult(true, true, 0, "ok", "")
      1 * applyPatch("patch", false, false) >> new GitTool.GitResult(true, true, 0, "applied", "")
      _ * isGitRepo() >> true
    }
    ShellCommands cmds = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      repoGit,
      commandRunner,
      modelRegistry,
      tempDir.resolve("apply.log").toString()
    ) {
      @Override
      protected ConfirmChoice confirmAction(String prompt) {
        ConfirmChoice.YES
      }

      @Override
      protected void warnDirtyWorkspace() {
        // no-op for tests
      }
    }

    when:
    def out = cmds.gitApply("patch", null, false, true, true)

    then:
    out.contains("applied")
  }

  def "gitPush requires confirmation"() {
    given:
    GitTool repoGit = Stub(GitTool) {
      isGitRepo() >> true
      push(false) >> new GitTool.GitResult(true, true, 0, "pushed", "")
    }
    ShellCommands cmds = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      repoGit,
      commandRunner,
      modelRegistry,
      tempDir.resolve("push.log").toString()
    ) {
      @Override
      protected ConfirmChoice confirmAction(String prompt) {
        ConfirmChoice.YES
      }
    }

    when:
    def out = cmds.gitPush(false, true)

    then:
    out.contains("pushed")
  }

  def "runCommand confirms agent requests and logs output"() {
    given:
    CommandRunner runner = Mock() {
      1 * run("echo hi", 5000L, 2000) >> new CommandRunner.CommandResult(
        true,
        false,
        0,
        "[OUT] hi",
        false,
        tempDir.resolve("run.log")
      )
    }
    List<String> prompts = []
    ShellCommands runCommands = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      gitTool,
      runner,
      modelRegistry,
      tempDir.resolve("runReviews.log").toString()
    ) {
      @Override
      protected ConfirmChoice confirmAction(String prompt) {
        prompts.add(prompt)
        ConfirmChoice.YES
      }
    }

    when:
    def output = runCommands.runCommand("echo hi", 5000L, 2000, "sess", true, true)

    then:
    prompts.first().startsWith("> Agent wants to run: 'echo hi'")
    output.contains("Exit: 0")
    output.contains("Log:")
    sessionState.history("sess").any { it.contains("Exit 0") }
  }

  def "runCommand reports timeout and truncation"() {
    given:
    CommandRunner runner = Stub() {
      run("long task", 10L, 5) >> new CommandRunner.CommandResult(false, true, -1, "data", true, null)
    }
    ShellCommands runCommands = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      gitTool,
      runner,
      modelRegistry,
      tempDir.resolve("run2.log").toString()
    )

    when:
    def output = runCommands.runCommand("long task", 10L, 5, "default", false, false)

    then:
    output.contains("timed out")
    output.contains("Output truncated")
  }

  def "model command falls back when requested model missing"() {
    given:
    ModelRegistry fallbackRegistry = Stub() {
      listModels() >> ["fallback-model"]
      checkHealth() >> new ModelRegistry.Health(true, "ok")
    }
    ShellCommands cmds = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      gitTool,
      commandRunner,
      fallbackRegistry,
      tempDir.resolve("model.log").toString()
    )

    when:
    def out = cmds.model("missing-model", "s1", true)

    then:
    out.contains("fallback from missing-model")
    sessionState.getOrCreate("s1").model == "fallback-model"
  }

  def "health command reports unreachable state"() {
    given:
    ModelRegistry downRegistry = Stub() {
      checkHealth() >> new ModelRegistry.Health(false, "connection refused")
      listModels() >> List.of()
      getBaseUrl() >> "http://localhost:11434"
    }
    ShellCommands cmds = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      gitTool,
      commandRunner,
      downRegistry,
      tempDir.resolve("health.log").toString()
    )

    expect:
    cmds.health().contains("unreachable")
  }

  def "tree formats repository output"() {
    given:
    TreeTool treeTool = Stub() {
      buildTree(3, false, 100) >> new TreeTool.TreeResult(
        true,
        true,
        false,
        2,
        ".\n  src/\n    App.groovy",
        null
      )
    }
    ShellCommands treeCommands = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      gitTool,
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator()),
      commandRunner,
      commandPolicy,
      modelRegistry,
      tempDir.resolve("tree.log").toString(),
      treeTool,
      null
    )

    when:
    def out = treeCommands.tree(3, false, 100)

    then:
    out.contains("=== Repository Tree ===")
    out.contains("src/")
  }
}
