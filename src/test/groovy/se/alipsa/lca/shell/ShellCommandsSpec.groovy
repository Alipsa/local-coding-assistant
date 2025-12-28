package se.alipsa.lca.shell

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.spi.ContextRepository
import com.embabel.chat.AssistantMessage
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import se.alipsa.lca.agent.ChatRequest
import se.alipsa.lca.agent.CodingAssistantAgent
import se.alipsa.lca.agent.PersonaMode
import se.alipsa.lca.agent.ReviewRequest
import se.alipsa.lca.agent.ReviewResponse
import se.alipsa.lca.intent.IntentRoutingSettings
import se.alipsa.lca.intent.IntentRoutingState
import se.alipsa.lca.review.ReviewSeverity
import se.alipsa.lca.tools.AgentsMdProvider
import se.alipsa.lca.tools.GitTool
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.CommandRunner
import se.alipsa.lca.tools.CommandPolicy
import se.alipsa.lca.tools.WebSearchTool
import se.alipsa.lca.tools.CodeSearchTool
import se.alipsa.lca.tools.ContextPacker
import se.alipsa.lca.tools.ContextBudgetManager
import se.alipsa.lca.tools.LocalOnlyState
import se.alipsa.lca.tools.ModelRegistry
import se.alipsa.lca.tools.TreeTool
import se.alipsa.lca.tools.TokenEstimator
import se.alipsa.lca.tools.SastTool
import spock.lang.Specification
import spock.lang.TempDir

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

class ShellCommandsSpec extends Specification {

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
    "fallback-model",
    agentsMdProvider,
    new LocalOnlyState(false)
  )
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
  IntentRoutingState intentRoutingState = new IntentRoutingState()
  IntentRoutingSettings intentRoutingSettings = new IntentRoutingSettings(true, "/edit")
  AgentPlatform agentPlatform = Mock()
  ContextRepository contextRepository = Stub()
  ShellSettings shellSettings = new ShellSettings(true)
  Agent chatAgent = new Agent("lca-chat", "local", "1.0.0", "Chat agent", Set.of(), List.of(), Set.of())
  Agent reviewAgent = new Agent("lca-review", "local", "1.0.0", "Review agent", Set.of(), List.of(), Set.of())
  AgentProcess chatProcess = Mock()
  AgentProcess reviewProcess = Mock()
  @TempDir
  Path tempDir
  ShellCommands commands

  def setup() {
    agentPlatform.agents() >> [chatAgent, reviewAgent]
    chatProcess.run() >> chatProcess
    reviewProcess.run() >> reviewProcess
    commands = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      gitTool,
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner,
      commandPolicy,
      modelRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("reviews.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
    )
  }

  def "chat uses persona mode and session overrides"() {
    given:
    ChatRequest captured = null
    chatProcess.resultOfType(AssistantMessage) >> new AssistantMessage("result")
    agentPlatform.createAgentProcessFrom(chatAgent, _ as ProcessOptions, _ as Object[]) >> {
      Agent agentArg, ProcessOptions options, Object[] inputs ->
        captured = inputs.find { it instanceof ChatRequest } as ChatRequest
        chatProcess
    }

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
    captured != null
    captured.persona == PersonaMode.ARCHITECT
    captured.options.model == "custom-model"
    captured.options.temperature == 0.9d
    captured.options.maxTokens == 2048
    captured.systemPrompt == "extra system"
  }

  def "plan uses planning format and architect persona"() {
    given:
    ChatRequest captured = null
    chatProcess.resultOfType(AssistantMessage) >> new AssistantMessage("plan output")
    agentPlatform.createAgentProcessFrom(chatAgent, _ as ProcessOptions, _ as Object[]) >> {
      Agent agentArg, ProcessOptions options, Object[] inputs ->
        captured = inputs.find { it instanceof ChatRequest } as ChatRequest
        chatProcess
    }

    when:
    def response = commands.plan(
      "Create a plan",
      "s2",
      PersonaMode.ARCHITECT,
      null,
      null,
      null,
      null,
      null
    )

    then:
    response == "plan output"
    captured != null
    captured.persona == PersonaMode.ARCHITECT
    captured.responseFormat != null
    captured.responseFormat.contains("numbered list")
    captured.systemPrompt.contains("Available commands:")
  }

  def "plan includes recent web search summary in prompt"() {
    given:
    ChatRequest captured = null
    UserMessage capturedUserMessage = null
    chatProcess.resultOfType(AssistantMessage) >> new AssistantMessage("plan output")
    agentPlatform.createAgentProcessFrom(chatAgent, _ as ProcessOptions, _ as Object[]) >> {
      Agent agentArg, ProcessOptions options, Object[] inputs ->
        captured = inputs.find { it instanceof ChatRequest } as ChatRequest
        capturedUserMessage = inputs.find { it instanceof UserMessage } as UserMessage
        chatProcess
    }
    def results = [
      new WebSearchTool.SearchResult("Result 1", "http://example.com", "Snippet 1")
    ]

    when:
    commands.search("out of memory", 2, "s2", "duckduckgo", 15000L, true, null)
    def response = commands.plan(
      "Based on your investigation, suggest a plan.",
      "s2",
      PersonaMode.ARCHITECT,
      null,
      null,
      null,
      null,
      null
    )

    then:
    1 * agent.search("out of memory", { WebSearchTool.SearchOptions opts ->
      opts.sessionId == "s2" &&
      opts.fetcherName == "htmlunit" &&
      opts.fallbackFetcherName == "jsoup"
    }) >> results
    response == "plan output"
    captured != null
    capturedUserMessage != null
    capturedUserMessage.textContent.contains("Recent investigation context:")
    capturedUserMessage.textContent.contains("Web search results for \"out of memory\"")
  }

  def "review uses review options and system prompt override"() {
    given:
    ReviewRequest captured = null
    reviewProcess.resultOfType(ReviewResponse) >> new ReviewResponse(
      "Findings:\n- [High] src/App.groovy:10 - bug\nTests:\n- test it"
    )
    agentPlatform.createAgentProcessFrom(reviewAgent, _ as ProcessOptions, _ as Object[]) >> {
      Agent agentArg, ProcessOptions options, Object[] inputs ->
        captured = inputs.find { it instanceof ReviewRequest } as ReviewRequest
        reviewProcess
    }
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
    captured != null
    captured.prompt == "check safety"
    captured.payload.contains("println 'hi'")
    captured.payload.contains("src/App.groovy")
    captured.options.model == "default-model"
    captured.options.temperature == 0.2d
    captured.options.maxTokens == 1024
    captured.systemPrompt == "system"
  }

  def "chat ensures a context exists when session id is provided"() {
    given:
    ContextRepository repo = Mock()
    AgentPlatform platform = agentPlatform
    ShellCommands withRepo = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      gitTool,
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner,
      commandPolicy,
      modelRegistry,
      platform,
      repo,
      tempDir.resolve("reviews-context.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
    )
    chatProcess.resultOfType(AssistantMessage) >> new AssistantMessage("context response")
    platform.createAgentProcessFrom(chatAgent, _ as ProcessOptions, _ as Object[]) >> chatProcess

    when:
    def response = withRepo.chat("Hello", "context-session", PersonaMode.CODER, null, null, null, null, null)

    then:
    response == "context response"
    1 * repo.findById("context-session") >> null
    1 * repo.save({ it instanceof com.embabel.agent.spi.support.SimpleContext && it.id == "context-session" })
  }

  def "paste can forward content to chat"() {
    given:
    chatProcess.resultOfType(AssistantMessage) >> new AssistantMessage("assistant response")
    agentPlatform.createAgentProcessFrom(chatAgent, _ as ProcessOptions, _ as Object[]) >> chatProcess

    when:
    def result = commands.paste("multi\nline", "/end", true, "default", PersonaMode.CODER)

    then:
    result == "assistant response"
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
      opts.webSearchEnabled &&
      opts.fetcherName == "htmlunit" &&
      opts.fallbackFetcherName == "jsoup"
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

  def "search can prompt to disable local-only mode"() {
    given:
    SessionState localState = new SessionState(
      "default-model",
      0.7d,
      0.35d,
      0,
      "",
      true,
      "htmlunit",
      "jsoup",
      600L,
      "fallback-model",
      agentsMdProvider,
      new LocalOnlyState(true)
    )
    ShellCommands localCommands = new ShellCommands(
      agent,
      ai,
      localState,
      editorLauncher,
      fileEditingTool,
      gitTool,
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner,
      commandPolicy,
      modelRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("reviews-local.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
    )
    def results = [new WebSearchTool.SearchResult("T1", "http://example.com", "S1")]
    InputStream originalIn = System.in
    System.in = new ByteArrayInputStream("y\n".bytes)

    when:
    def out = localCommands.search("query", 1, "default", "duckduckgo", 15000L, true, null)

    then:
    1 * agent.search("query", { WebSearchTool.SearchOptions opts ->
      opts.limit == 1 &&
      opts.provider == WebSearchTool.SearchProvider.DUCKDUCKGO &&
      opts.webSearchEnabled &&
      opts.fetcherName == "htmlunit" &&
      opts.fallbackFetcherName == "jsoup"
    }) >> results
    out.contains("=== Web Search ===")
    !localState.isLocalOnly("default")

    cleanup:
    System.in = originalIn
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
    reviewProcess.resultOfType(ReviewResponse) >> new ReviewResponse(
      "Findings:\n- [Low] general - note\nTests:\n- test"
    )
    agentPlatform.createAgentProcessFrom(reviewAgent, _ as ProcessOptions, _ as Object[]) >> reviewProcess
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
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner,
      commandPolicy,
      modelRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("other.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
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
    reviewProcess.resultOfType(ReviewResponse) >> new ReviewResponse(
      "Findings:\n- [High] src/App.groovy:1 - issue\n- [Low] other - ignore\nTests:\n- test"
    )
    agentPlatform.createAgentProcessFrom(reviewAgent, _ as ProcessOptions, _ as Object[]) >> reviewProcess
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
    reviewProcess.resultOfType(ReviewResponse) >> new ReviewResponse(
      "Findings:\n- [High] src/App.groovy:1 - issue\nTests:\n- test"
    )
    agentPlatform.createAgentProcessFrom(reviewAgent, _ as ProcessOptions, _ as Object[]) >> reviewProcess
    fileEditingTool.readFile(_) >> "content"
    def instants = [java.time.Instant.parse("2025-01-01T00:00:00Z"), java.time.Instant.parse("2025-01-01T00:00:10Z")].iterator()
    ShellCommands clocked = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      gitTool,
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner,
      commandPolicy,
      modelRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("reviews.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
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
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      runTool,
      commandPolicy,
      modelRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("commit.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
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
    ShellCommands commitCommands = commitCommandsFor(repoGit)

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
    ShellCommands commitCommands = commitCommandsFor(repoGit)
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
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner,
      commandPolicy,
      modelRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("stage.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
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

  def "batch mode throws when confirmation is required"() {
    given:
    GitTool repoGit = Mock()
    ShellCommands staging = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      repoGit,
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner,
      commandPolicy,
      modelRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("stage.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
    )
    staging.configureBatchMode(true, false)

    when:
    staging.stage(["file.txt"], null, null, true)

    then:
    IllegalStateException ex = thrown()
    ex.message.contains("Confirmation required in batch mode")
    0 * repoGit.stageFiles(_)
  }

  def "batch mode assumes yes for confirmations"() {
    given:
    GitTool repoGit = Mock()
    ShellCommands staging = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      repoGit,
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner,
      commandPolicy,
      modelRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("stage.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
    ) {
      ConfirmChoice exposeConfirmAction(String prompt) {
        super.confirmAction(prompt)
      }
    }
    staging.configureBatchMode(true, true)

    when:
    def choice = staging.exposeConfirmAction("Stage files?")
    def result = staging.stage(["file.txt"], null, null, true)

    then:
    choice == ShellCommands.ConfirmChoice.ALL
    1 * repoGit.stageFiles(["file.txt"]) >> new GitTool.GitResult(true, true, 0, "ok", "")
    result.contains("Stage succeeded")
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
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner,
      commandPolicy,
      modelRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("status.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
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
        [new SastTool.SastFinding(
          "HIGH-ghp_1234567890abcdef1234567890abcdef1234",
          "src/main/java/Auth.java",
          42,
          "ghp_1234567890abcdef1234567890abcdef1234"
        )]
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
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner,
      commandPolicy,
      modelRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("sast.log").toString(),
      null,
      sastTool,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
    )
    def method = ShellCommands.getDeclaredMethod("buildSastBlock", boolean, List)
    method.accessible = true

    when:
    String block = method.invoke(cmds, true, ["src"])

    then:
    block.contains("SAST:")
    !block.contains("ghp_1234567890abcdef")
    block.contains("[HIGH-REDACTED]")
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
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner,
      commandPolicy,
      modelRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("diff.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
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
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner,
      commandPolicy,
      modelRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("apply.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
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
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner,
      commandPolicy,
      modelRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("push.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
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
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      runner,
      commandPolicy,
      modelRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("runReviews.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
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
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      runner,
      commandPolicy,
      modelRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("run2.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
    )

    when:
    def output = runCommands.runCommand("long task", 10L, 5, "default", false, false)

    then:
    output.contains("timed out")
    output.contains("Output truncated")
  }

  def "shellCommand streams output and updates conversation history"() {
    given:
    CommandRunner runner = Mock() {
      1 * runStreaming("echo hi", 60000L, 8000, _ as CommandRunner.OutputListener) >> {
        new CommandRunner.CommandResult(true, false, 0, "[OUT] hi", false, tempDir.resolve("run.log"))
      }
    }
    ShellCommands shellCommands = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      gitTool,
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      runner,
      commandPolicy,
      modelRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("shell.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
    )

    when:
    String output = shellCommands.shellCommand("echo hi", "shell-session")

    then:
    output.contains("Exit: 0")
    sessionState.history("shell-session").any { it.contains("Shell command: echo hi") }
    sessionState.getOrCreateConversation("shell-session").messages.any {
      it.textContent?.contains("Shell command executed")
    }
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
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner,
      commandPolicy,
      fallbackRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("model.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
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
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner,
      commandPolicy,
      downRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("health.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
    )

    expect:
    cmds.health().contains("unreachable")
  }

  def "version returns resolved version"() {
    given:
    ShellCommands versioned = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      gitTool,
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner,
      commandPolicy,
      modelRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("version.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
    )

    expect:
    versioned.version().contains("lca version: 0.0-test")
    versioned.version().contains("Embabel version:")
    versioned.version().contains("Spring-boot version:")
    versioned.version().contains("Models: default-model (fallback: fallback-model)")
  }

  def "config reports and updates auto-paste"() {
    when:
    def initial = commands.config(null, null, null, null, null, null)

    then:
    initial.contains("=== Configuration ===")
    initial.contains("Auto-paste: enabled")
    initial.contains("Local-only: disabled")
    initial.contains("web-search: htmlunit (fallback jsoup)")
    initial.contains("Intent routing: enabled")

    when:
    def disabled = commands.config(false, null, null, null, null, null)

    then:
    disabled.contains("Auto-paste: disabled")

    when:
    def enabled = commands.config(true, null, null, null, null, null)

    then:
    enabled.contains("Auto-paste: enabled")
  }

  def "config updates local-only for the session"() {
    when:
    def enabled = commands.config(null, true, null, null, null, null)

    then:
    enabled.contains("Local-only: enabled")

    when:
    def disabled = commands.config(null, false, null, null, null, null)

    then:
    disabled.contains("Local-only: disabled")
  }

  def "config updates web search fetchers for the session"() {
    when:
    def updated = commands.config(null, null, "jsoup", null, null, null)

    then:
    updated.contains("web-search: jsoup (fallback htmlunit)")
  }

  def "config disables web search for the session"() {
    when:
    def updated = commands.config(null, null, "disabled", null, null, null)

    then:
    updated.contains("web-search: disabled")
    !sessionState.isWebSearchDesired("default")
  }

  def "config updates intent routing for the session"() {
    when:
    def disabled = commands.config(null, null, null, null, null, "disabled")

    then:
    disabled.contains("Intent routing: disabled")
    intentRoutingState.enabledOverride == Boolean.FALSE

    when:
    def enabled = commands.config(null, null, null, null, null, "enabled")

    then:
    enabled.contains("Intent routing: enabled")
    intentRoutingState.enabledOverride == Boolean.TRUE

    when:
    def reset = commands.config(null, null, null, null, null, "default")

    then:
    reset.contains("Intent routing: enabled")
    intentRoutingState.enabledOverride == null
  }

  def "help lists commands alphabetically and includes config options"() {
    when:
    String output = commands.help()
    List<String> commandLines = output.readLines().findAll { String line ->
      line.startsWith("- /")
    }
    List<String> listed = commandLines.collect { String line ->
      line.split(":")[0].substring(2)
    }
    List<String> sorted = new ArrayList<>(listed)
    sorted.sort()

    then:
    output.contains("=== Help ===")
    output.contains("Config options (/config):")
    output.contains("- intent: enabled|disabled|default")
    output.contains("- web-search: htmlunit|jsoup|disabled|default")
    listed == sorted
    !output.contains("/clear")
    !output.contains("/history")
    !output.contains("/stacktrace")
    !output.contains("/script")
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
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner,
      commandPolicy,
      modelRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("tree.log").toString(),
      treeTool,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
    )

    when:
    def out = treeCommands.tree(3, false, 100)

    then:
    out.contains("=== Repository Tree ===")
    out.contains("src/")
  }

  private ShellCommands commitCommandsFor(GitTool repoGit) {
    new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      repoGit,
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner,
      commandPolicy,
      modelRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("commit.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
    )
  }
}
