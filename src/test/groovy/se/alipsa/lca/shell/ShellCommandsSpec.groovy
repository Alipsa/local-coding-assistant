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
import se.alipsa.lca.agent.ChatResponse
import se.alipsa.lca.agent.CodingAssistantAgent
import se.alipsa.lca.agent.PersonaMode
import se.alipsa.lca.agent.ReviewRequest
import se.alipsa.lca.agent.ReviewResponse
import se.alipsa.lca.intent.IntentRoutingSettings
import se.alipsa.lca.intent.IntentRoutingState
import se.alipsa.lca.review.ReviewSeverity
import se.alipsa.lca.review.ReviewSummary
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
import se.alipsa.lca.validation.ImplementationGroundingCheck
import spock.lang.Requires
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
    300000L,
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
  // Unstubbed shouldAutoCompact(_) returns false by default (Spock Stub sensible-default for
  // boolean), so tests that don't care about auto-compact need no explicit interaction here.
  ContextCompactor contextCompactor = Stub()
  Agent chatAgent = new Agent("lca-chat", "local", "1.0.0", "Chat agent", Set.of(), List.of(), Set.of())
  Agent reviewAgent = new Agent("lca-review", "local", "1.0.0", "Review agent", Set.of(), List.of(), Set.of())
  AgentProcess chatProcess = Mock()
  AgentProcess reviewProcess = Mock()
  @TempDir
  Path tempDir
  ShellCommands commands
  // Backed by a real CommandRunner (runs actual `bash -lc`), used by the streaming
  // shellCommandCaptured tests below; guarded with @Requires since it needs a real shell.
  ShellCommands shellCommands

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
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
    )
    shellCommands = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      Mock(se.alipsa.lca.tools.ToolCallParser),
      gitTool,
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      new se.alipsa.lca.tools.CommandRunner(tempDir),
      commandPolicy,
      modelRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("reviews-streaming.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
    )
  }

  def "chat uses persona mode and session overrides"() {
    given:
    ChatRequest captured = null
    chatProcess.resultOfType(ChatResponse) >> new ChatResponse(new AssistantMessage("result"), null)
    agentPlatform.createAgentProcessFrom(chatAgent, _ as ProcessOptions, _ as Object[]) >> {
      Agent agentArg, ProcessOptions options, Object[] inputs ->
        captured = inputs.find { it instanceof ChatRequest } as ChatRequest
        chatProcess
    }

    when:
    def response = commands.chat(
      ["prompt text"] as String[],
      "s1",
      PersonaMode.ARCHITECT,
      "custom-model",
      0.9d,
      0.4d,
      2048,
      "extra system",
      false,
      false
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

  def "chat appends an auto-compact note when the session crosses the threshold"() {
    given:
    chatProcess.resultOfType(ChatResponse) >> new ChatResponse(new AssistantMessage("result"), null)
    agentPlatform.createAgentProcessFrom(chatAgent, _ as ProcessOptions, _ as Object[]) >> chatProcess
    contextCompactor.shouldAutoCompact("s1") >> true
    contextCompactor.compact("s1") >> new ContextCompactor.CompactionResult(true, 20, 7, "summary")

    when:
    def response = commands.chat(
      ["hi"] as String[], "s1", PersonaMode.CODER, null, null, null, null, null, false, false)

    then:
    response.contains("result")
    response.contains("(Context automatically compacted: 20 messages -> 7.)")
  }

  def "chat notes when over threshold but too few messages exist yet to compact"() {
    given: "shouldAutoCompact fires on token usage, but compact refuses below its keep-recent floor"
    chatProcess.resultOfType(ChatResponse) >> new ChatResponse(new AssistantMessage("result"), null)
    agentPlatform.createAgentProcessFrom(chatAgent, _ as ProcessOptions, _ as Object[]) >> chatProcess
    contextCompactor.shouldAutoCompact("s1") >> true
    contextCompactor.compact("s1") >> new ContextCompactor.CompactionResult(false, 3, 3, null)

    when:
    def response = commands.chat(
      ["hi"] as String[], "s1", PersonaMode.CODER, null, null, null, null, null, false, false)

    then:
    response.contains("result")
    response.contains("Context usage is high, but there isn't enough conversation history yet to compact")
    response.contains("only 3 message(s) recorded")
  }

  def "compact reports nothing to compact when below the keep-recent floor"() {
    given:
    contextCompactor.compact("s1") >> new ContextCompactor.CompactionResult(false, 3, 3, null)

    expect:
    commands.compact("s1") ==
      "Nothing to compact: conversation has 3 message(s), at or below the keep-recent floor."
  }

  def "compact reports the before/after message counts on success"() {
    given:
    contextCompactor.compact("s1") >> new ContextCompactor.CompactionResult(true, 20, 7, "summary text")

    expect:
    commands.compact("s1") == "Compacted conversation: 20 messages -> 7 (1 summary + 6 recent). " +
      "Context usage will reflect this on the next turn."
  }

  def "plan uses planning format and architect persona"() {
    given:
    ChatRequest captured = null
    chatProcess.resultOfType(ChatResponse) >> new ChatResponse(new AssistantMessage("plan output"), null)
    agentPlatform.createAgentProcessFrom(chatAgent, _ as ProcessOptions, _ as Object[]) >> {
      Agent agentArg, ProcessOptions options, Object[] inputs ->
        captured = inputs.find { it instanceof ChatRequest } as ChatRequest
        chatProcess
    }

    when:
    def response = commands.plan(
      ["Create a plan"] as String[],
      "s2",
      PersonaMode.ARCHITECT,
      null,
      null,
      null,
      null,
      null,
      false
    )

    then:
    response.contains("plan output")
    response.contains("What would you like to do next?")
    response.contains("/implement")
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
    chatProcess.resultOfType(ChatResponse) >> new ChatResponse(new AssistantMessage("plan output"), null)
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
      ["Based on your investigation, suggest a plan."] as String[],
      "s2",
      PersonaMode.ARCHITECT,
      null,
      null,
      null,
      null,
      null,
      false
    )

    then:
    1 * agent.search("out of memory", { WebSearchTool.SearchOptions opts ->
      opts.sessionId == "s2" &&
      opts.fetcherName == "htmlunit" &&
      opts.fallbackFetcherName == "jsoup"
    }) >> results
    response.contains("plan output")
    response.contains("What would you like to do next?")
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
    // 10 lines so the cited "src/App.groovy:10" finding is in range, not [UNVERIFIED].
    fileEditingTool.readFile(_) >> (1..10).collect { "line${it}" }.join("\n")

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
      false,
      false,
      (Integer) null
    )

    then:
    response.contains("=== Review ===")
    response.contains("[High] src/App.groovy:10")
    response.contains("    - bug")
    !response.contains("\u001B[")
    response.contains("## Tests")
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
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
    )
    chatProcess.resultOfType(ChatResponse) >> new ChatResponse(new AssistantMessage("context response"), null)
    platform.createAgentProcessFrom(chatAgent, _ as ProcessOptions, _ as Object[]) >> chatProcess

    when:
    def response = withRepo.chat(["Hello"] as String[], "context-session", PersonaMode.CODER, null, null, null, null, null, false, false)

    then:
    response == "context response"
    1 * repo.findById("context-session") >> null
    1 * repo.createWithId("context-session")
  }

  def "paste can forward content to chat"() {
    given:
    chatProcess.resultOfType(ChatResponse) >> new ChatResponse(new AssistantMessage("assistant response"), null)
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
      300000L,
      agentsMdProvider,
      new LocalOnlyState(true)
    )
    ShellCommands localCommands = new ShellCommands(
      agent,
      ai,
      localState,
      editorLauncher,
      fileEditingTool,
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
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
    commands.review("", "log it", "default", null, null, null, null, ["src/App.groovy"], false, ReviewSeverity.LOW, false, true, false, false, false, (Integer) null)

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
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
    ) {
      @Override
      protected ConfirmationChoice confirmAction(String prompt) {
        confirmations++
        ConfirmationChoice.ALL
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
    commands.review("", "log it", "default", null, null, null, null, ["src/App.groovy"], false, ReviewSeverity.LOW, false, true, false, false, false, (Integer) null)

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
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
    ) {
      @Override
      protected java.time.Instant nowInstant() {
        instants.hasNext() ? instants.next() : java.time.Instant.now()
      }
    }
    clocked.review("", "entry1", "default", null, null, null, null, ["src/App.groovy"], false, ReviewSeverity.LOW, false, true, false, false, false, (Integer) null)
    clocked.review("", "entry2", "default", null, null, null, null, ["src/App.groovy"], false, ReviewSeverity.LOW, false, true, false, false, false, (Integer) null)

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
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
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
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
    ) {
      @Override
      protected ConfirmationChoice confirmAction(String prompt) {
        ConfirmationChoice.NO
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
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
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
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
    ) {
      ConfirmationChoice exposeConfirmAction(String prompt) {
        super.confirmAction(prompt)
      }
    }
    staging.configureBatchMode(true, true)

    when:
    def choice = staging.exposeConfirmAction("Stage files?")
    def result = staging.stage(["file.txt"], null, null, true)

    then:
    choice == ConfirmationChoice.ALL
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
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
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
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
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
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
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
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
    ) {
      @Override
      protected ConfirmationChoice confirmAction(String prompt) {
        ConfirmationChoice.YES
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
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
    ) {
      @Override
      protected ConfirmationChoice confirmAction(String prompt) {
        ConfirmationChoice.YES
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
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
    ) {
      @Override
      protected ConfirmationChoice confirmAction(String prompt) {
        prompts.add(prompt)
        ConfirmationChoice.YES
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
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
    )

    when:
    def output = runCommands.runCommand("long task", 10L, 5, "default", false, false)

    then:
    output.contains("timed out")
    output.contains("Output truncated")
  }

  def "shellCommand streams output and updates conversation history"() {
    given:
    boolean listenerCalled = false
    CommandRunner runner = Mock() {
      1 * runStreaming("echo hi", 60000L, 8000, _ as CommandRunner.OutputListener) >> {
        String cmd, long timeout, int maxChars, CommandRunner.OutputListener listener ->
          listener.onLine("OUT", "hi")
          listenerCalled = true
          new CommandRunner.CommandResult(true, false, 0, "[OUT] hi", false, tempDir.resolve("run.log"))
      }
    }
    ShellCommands shellCommands = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
    )

    when:
    String output = shellCommands.shellCommand("echo hi", "shell-session")

    then:
    output.contains("Exit: 0")
    listenerCalled
    sessionState.history("shell-session").contains("Shell command: echo hi")
    sessionState.history("shell-session").any { it.contains("Exit 0; [OUT] hi") }
    sessionState.getOrCreateConversation("shell-session").messages.any {
      it.textContent?.contains("Shell command executed")
    }
  }

  def "shellCommand returns policy block message"() {
    given:
    CommandRunner runner = Mock()
    CommandPolicy policy = new CommandPolicy("", "echo*")
    ShellCommands shellCommands = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      Mock(se.alipsa.lca.tools.ToolCallParser),
      gitTool,
      Stub(CodeSearchTool),
      new ContextPacker(),
      new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      runner,
      policy,
      modelRegistry,
      agentPlatform,
      contextRepository,
      tempDir.resolve("shell.log").toString(),
      null,
      null,
      shellSettings,
      intentRoutingState,
      intentRoutingSettings
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
    )

    when:
    String output = shellCommands.shellCommand("echo hi", "shell-session")

    then:
    output == "Command blocked by denylist: echo*"
    0 * runner._
  }

  def "shellCommand reports timeout and truncation"() {
    given:
    CommandRunner runner = Stub() {
      runStreaming("long task", 60000L, 8000, _ as CommandRunner.OutputListener) >>
        new CommandRunner.CommandResult(false, true, -1, "data", true, null)
    }
    ShellCommands shellCommands = new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
    )

    when:
    String output = shellCommands.shellCommand("long task", "shell-session")

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
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
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
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
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
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
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
    output.contains("Multi-line input:")
    output.contains("/paste ... /end")
    output.contains("^^^ ... ^^^")
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
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
    )

    when:
    def out = treeCommands.tree(3, false, 100)

    then:
    out.contains("=== Repository Tree ===")
    out.contains("src/")
  }

  def "buildPrReviewPayload keeps files that fit within budget"() {
    given:
    String diff = "diff content"
    GitTool.GitResult diffResult = new GitTool.GitResult(true, true, 0, diff, "")
    GitTool prGit = Stub(GitTool) {
      prChangedFiles(1) >> new GitTool.GitResult(true, true, 0, "small.groovy\nlarge.groovy", "")
      prHeadCommit(1) >> new GitTool.GitResult(true, true, 0, "abc123", "")
      showFileAtCommit("abc123", "small.groovy") >> new GitTool.GitResult(true, true, 0, "small content", "")
      showFileAtCommit("abc123", "large.groovy") >> new GitTool.GitResult(true, true, 0, "x" * 90000, "")
    }
    FileEditingTool prFileEditing = Stub(FileEditingTool)
    ShellCommands shellCommands = new ShellCommands(
      agent, ai, sessionState, editorLauncher, prFileEditing,
      Mock(se.alipsa.lca.tools.ToolCallParser), prGit, Stub(CodeSearchTool),
      new ContextPacker(), new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner, commandPolicy, modelRegistry, agentPlatform, contextRepository,
      tempDir.resolve("pr-budget.log").toString(), null, null, shellSettings,
      intentRoutingState, intentRoutingSettings,
      Mock(se.alipsa.lca.validation.RequestValidator), Mock(se.alipsa.lca.validation.ClarificationDialog),
      null, null, null, null, contextCompactor, 80000, 30000, null
    )

    when:
    ShellCommands.ReviewPayload payload = shellCommands.buildPrReviewPayload(1, diffResult)

    then: "the git-show path is exercised, not an accidental fallback to the local tree"
    payload.text.contains("File: small.groovy")
    payload.text.contains("small content")
    !payload.text.contains("File: large.groovy")
    payload.text.contains("PR diff:")
    payload.text.contains("diff content")
    payload.fileLineCounts == ["small.groovy": 1]
    payload.changedFiles == ["small.groovy", "large.groovy"]
  }

  def "buildPrReviewPayload falls back to a local read and marks it approximate when prHeadCommit fails"() {
    given:
    String diff = "diff content"
    GitTool.GitResult diffResult = new GitTool.GitResult(true, true, 0, diff, "")
    GitTool prGit = Stub(GitTool) {
      prChangedFiles(2) >> new GitTool.GitResult(true, true, 0, "small.groovy", "")
      prHeadCommit(2) >> new GitTool.GitResult(false, true, 1, "", "no gh")
    }
    FileEditingTool prFileEditing = Stub(FileEditingTool) {
      readFile("small.groovy") >> "local content"
    }
    ShellCommands shellCommands = new ShellCommands(
      agent, ai, sessionState, editorLauncher, prFileEditing,
      Mock(se.alipsa.lca.tools.ToolCallParser), prGit, Stub(CodeSearchTool),
      new ContextPacker(), new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner, commandPolicy, modelRegistry, agentPlatform, contextRepository,
      tempDir.resolve("pr-fallback.log").toString(), null, null, shellSettings,
      intentRoutingState, intentRoutingSettings,
      Mock(se.alipsa.lca.validation.RequestValidator), Mock(se.alipsa.lca.validation.ClarificationDialog),
      null, null, null, null, contextCompactor, 80000, 30000, null
    )

    when:
    ShellCommands.ReviewPayload payload = shellCommands.buildPrReviewPayload(2, diffResult)

    then:
    payload.text.contains("File: small.groovy")
    payload.text.contains("local content")
    payload.text.contains("(approximate — local copy, not verified against PR head)")
    payload.fileLineCounts == ["small.groovy": 1]
  }

  def "buildPrReviewPayload fetches the PR ref once and retries showFileAtCommit on first failure"() {
    given:
    String diff = "diff content"
    GitTool.GitResult diffResult = new GitTool.GitResult(true, true, 0, diff, "")
    GitTool prGit = Mock(GitTool)
    prGit.prChangedFiles(3) >> new GitTool.GitResult(true, true, 0, "a.groovy\nb.groovy", "")
    prGit.prHeadCommit(3) >> new GitTool.GitResult(true, true, 0, "sha1", "")
    prGit.showFileAtCommit("sha1", "a.groovy") >>> [
      new GitTool.GitResult(false, true, 1, "", "not found"),
      new GitTool.GitResult(true, true, 0, "a content", "")
    ]
    prGit.showFileAtCommit("sha1", "b.groovy") >> new GitTool.GitResult(true, true, 0, "b content", "")
    FileEditingTool prFileEditing = Stub(FileEditingTool)
    ShellCommands shellCommands = new ShellCommands(
      agent, ai, sessionState, editorLauncher, prFileEditing,
      Mock(se.alipsa.lca.tools.ToolCallParser), prGit, Stub(CodeSearchTool),
      new ContextPacker(), new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner, commandPolicy, modelRegistry, agentPlatform, contextRepository,
      tempDir.resolve("pr-retry.log").toString(), null, null, shellSettings,
      intentRoutingState, intentRoutingSettings,
      Mock(se.alipsa.lca.validation.RequestValidator), Mock(se.alipsa.lca.validation.ClarificationDialog),
      null, null, null, null, contextCompactor, 80000, 30000, null
    )

    when:
    ShellCommands.ReviewPayload payload = shellCommands.buildPrReviewPayload(3, diffResult)

    then:
    1 * prGit.fetchPullRequestRef(3) >> new GitTool.GitResult(true, true, 0, "", "")
    payload.text.contains("a content")
    payload.text.contains("b content")
    !payload.text.contains("approximate")
  }

  def "renderReview shows raw response when no structured findings parsed"() {
    given:
    String rawText = "This PR looks good. No issues found. The changes correctly replace CompanySettingsService with CompanyService."
    ReviewSummary summary = new ReviewSummary([], [], rawText)

    when:
    String rendered = ShellCommands.renderReview(summary, ReviewSeverity.LOW, false)

    then:
    rendered.contains(rawText)
    !rendered.contains("## Findings\n\n- None")
  }

  def "shellHeader renders the command with a prompt prefix"() {
    expect:
    ShellCommands.shellHeader("git status") == '$ git status'
  }

  def "shellFooter renders exit status and truncation"() {
    given:
    def ok = new se.alipsa.lca.tools.CommandRunner.CommandResult(
      exitCode: 0, success: true, timedOut: false, truncated: false)
    def failTrunc = new se.alipsa.lca.tools.CommandRunner.CommandResult(
      exitCode: 2, success: false, timedOut: false, truncated: true)
    def timedOut = new se.alipsa.lca.tools.CommandRunner.CommandResult(
      exitCode: -1, success: false, timedOut: true, truncated: false)

    expect:
    ShellCommands.shellFooter(ok) == '[exit 0]'
    ShellCommands.shellFooter(failTrunc) == '[exit 2 — failed] (output truncated)'
    ShellCommands.shellFooter(timedOut) == '[exit timeout — failed]'
  }

  @Requires({ os.macOs || os.linux })
  def "shellCommandCaptured streams each line to the consumer and returns only the footer"() {
    given:
    List<String> lines = []

    when:
    String footer = shellCommands.shellCommandCaptured("printf 'a\\nb\\nc\\n'", "default",
      { String line -> lines << line } as java.util.function.Consumer)

    then:
    lines == ["a", "b", "c"]
    footer.startsWith("[exit 0]")
    !footer.contains("\$ printf")   // body/header are NOT in the streamed return value
  }

  @Requires({ os.macOs || os.linux })
  def "non-streaming shellCommandCaptured still includes header, body and footer"() {
    when:
    String captured = shellCommands.shellCommandCaptured("printf 'x\\n'", "default")

    then:
    captured.contains('$ printf')
    captured.contains("x")
    captured.contains("[exit 0]")
  }

  @Requires({ os.macOs || os.linux })
  def "streams lines from both stdout and stderr to the consumer"() {
    given:
    List<String> lines = Collections.synchronizedList([] as List<String>)

    when:
    // o1/o2 on stdout, e1/e2 on stderr — read by two separate threads inside CommandRunner.
    shellCommands.shellCommandCaptured("printf 'o1\\no2\\n'; printf 'e1\\ne2\\n' 1>&2", "default",
      { String line -> lines << line } as java.util.function.Consumer)

    then:
    // Every line from each stream is delivered (set membership); per-stream order is preserved;
    // cross-stream interleaving order is unspecified, so it is NOT asserted.
    lines.toSet() == ["o1", "o2", "e1", "e2"].toSet()
    lines.indexOf("o1") < lines.indexOf("o2")
    lines.indexOf("e1") < lines.indexOf("e2")
  }

  @Requires({ os.macOs || os.linux })
  def "non-streaming captured buffer is clamped mid-line to the char cap"() {
    when:
    // A single newline-free line far larger than the 8000-char cap; the old length-checked
    // append would have forwarded the whole line, overshooting the cap ~2.5x.
    String captured = shellCommands.shellCommandCaptured(
      "awk 'BEGIN{for(i=0;i<20000;i++)printf \"A\"}'", "default")

    then:
    // header ("$ awk ...\n") + body (<= 8000) + footer ("[exit 0]"); comfortably under 2x the cap.
    captured.length() <= 8000 + 200
  }

  def "a target-less follow-up reuses the cached non-PR target and includes previousFindings"() {
    given:
    reviewProcess.resultOfType(ReviewResponse) >>> [
      new ReviewResponse("Findings:\n- [High] src/App.groovy:10 - first round bug\nTests:\n- test it"),
      new ReviewResponse("Findings:\n- [Low] src/App.groovy:10 - confirmed fixed\nTests:\n- test it")
    ]
    List<ReviewRequest> capturedRequests = []
    agentPlatform.createAgentProcessFrom(reviewAgent, _ as ProcessOptions, _ as Object[]) >> {
      Agent agentArg, ProcessOptions options, Object[] inputs ->
        capturedRequests << (inputs.find { it instanceof ReviewRequest } as ReviewRequest)
        reviewProcess
    }
    fileEditingTool.readFile(_) >> "content"

    when: "the first review names a real target"
    commands.review(
      "", "review this file", "s-cache", null, null, null, null,
      ["src/App.groovy"], false, ReviewSeverity.LOW, true, false, false, false, false, (Integer) null
    )

    and: "no target named at all — code defaults to '', never null, proving the cache branch is reachable"
    commands.review(
      "", "verify these findings", "s-cache", null, null, null, null,
      null, false, ReviewSeverity.LOW, true, false, false, false, false, (Integer) null
    )

    then:
    capturedRequests.size() == 2
    capturedRequests[1].previousFindings.contains("first round bug")
    capturedRequests[1].payload.contains("content")
  }

  def "a target-less follow-up with no prior cache returns the fail-fast message without invoking the agent"() {
    when:
    def response = commands.review(
      "", "verify these findings", "s-empty", null, null, null, null,
      null, false, ReviewSeverity.LOW, true, false, false, false, false, (Integer) null
    )

    then:
    response == "No prior review to verify, and no file/PR specified — what would you like me to review?"
    0 * agentPlatform.createAgentProcessFrom(_, _, _)
  }

  def "a target-less follow-up reusing a cached PR target is treated as a PR review, not a non-PR review"() {
    given: "the headline regression this round found"
    GitTool prGit = Mock(GitTool)
    prGit.prDiff(52) >>> [
      new GitTool.GitResult(true, true, 0, "diff round 1", ""),
      new GitTool.GitResult(true, true, 0, "diff round 2", "")
    ]
    prGit.prChangedFiles(52) >> new GitTool.GitResult(true, true, 0, "Foo.groovy\nNewFile.groovy", "")
    prGit.prHeadCommit(52) >> new GitTool.GitResult(true, true, 0, "sha1", "")
    prGit.showFileAtCommit("sha1", "Foo.groovy") >> new GitTool.GitResult(true, true, 0, "class Foo {}", "")
    prGit.showFileAtCommit("sha1", "NewFile.groovy") >> new GitTool.GitResult(true, true, 0, "class NewFile {}", "")
    List<Set<String>> capturedKnownPaths = []
    ImplementationGroundingCheck groundingCheck = Stub(ImplementationGroundingCheck) {
      checkFileReferences(_, _) >> { List<String> cited, Set<String> known ->
        capturedKnownPaths << known
        new ImplementationGroundingCheck.GroundingResult(ImplementationGroundingCheck.GroundingLevel.GROUNDED, [])
      }
    }
    ShellCommands prCommands = new ShellCommands(
      agent, ai, sessionState, editorLauncher, fileEditingTool,
      Mock(se.alipsa.lca.tools.ToolCallParser), prGit, Stub(CodeSearchTool),
      new ContextPacker(), new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner, commandPolicy, modelRegistry, agentPlatform, contextRepository,
      tempDir.resolve("pr-cache.log").toString(), null, null, shellSettings,
      intentRoutingState, intentRoutingSettings,
      Mock(se.alipsa.lca.validation.RequestValidator), Mock(se.alipsa.lca.validation.ClarificationDialog),
      null, groundingCheck, null, null, contextCompactor, 80000, 30000, null
    )
    reviewProcess.resultOfType(ReviewResponse) >>> [
      new ReviewResponse("Findings:\n- [High] Foo.groovy:1 - round 1 bug\nTests:\n- test it"),
      new ReviewResponse("Findings:\n- [Low] Foo.groovy:1 - confirmed fixed\nTests:\n- test it"),
      new ReviewResponse("Findings:\n- [Low] Foo.groovy:1 - still fine\nTests:\n- test it")
    ]
    List<ReviewRequest> capturedRequests = []
    agentPlatform.createAgentProcessFrom(reviewAgent, _ as ProcessOptions, _ as Object[]) >> {
      Agent agentArg, ProcessOptions options, Object[] inputs ->
        capturedRequests << (inputs.find { it instanceof ReviewRequest } as ReviewRequest)
        reviewProcess
    }

    when: "the first review explicitly targets PR 52"
    prCommands.review(
      "", "review this PR", "s-pr", null, null, null, null,
      null, false, ReviewSeverity.LOW, true, false, false, false, false, 52
    )

    and: "a target-less follow-up, with no --pr of its own, asks to verify"
    prCommands.review(
      "", "verify these findings", "s-pr", null, null, null, null,
      null, false, ReviewSeverity.LOW, true, false, false, false, false, (Integer) null
    )

    then: "the reused cache is treated as a full PR review — right prompt shape, real changedFiles for grounding"
    capturedRequests.size() == 2
    capturedRequests[1].prReview
    capturedRequests[1].previousFindings.contains("round 1 bug")
    capturedKnownPaths[1].contains("Foo.groovy") || capturedKnownPaths[1].contains("NewFile.groovy")

    when: "a third target-less follow-up verifies round 2, not round 1"
    prCommands.review(
      "", "verify again", "s-pr", null, null, null, null,
      null, false, ReviewSeverity.LOW, true, false, false, false, false, (Integer) null
    )

    then: "the cache was overwritten after round 2, so round 3 verifies round 2's findings"
    capturedRequests.size() == 3
    capturedRequests[2].prReview
    capturedRequests[2].previousFindings.contains("confirmed fixed")
    !capturedRequests[2].previousFindings.contains("round 1 bug")
  }

  def "a --staged-only review skips the cache, so a later target-less follow-up fails fast instead of NPEing"() {
    given:
    reviewProcess.resultOfType(ReviewResponse) >> new ReviewResponse("Findings:\n- [Low] general - nit\nTests:\n- test")
    agentPlatform.createAgentProcessFrom(reviewAgent, _ as ProcessOptions, _ as Object[]) >> reviewProcess
    GitTool stagedGit = Stub(GitTool) {
      stagedDiff() >> new GitTool.GitResult(true, true, 0, "some staged diff", "")
    }
    ShellCommands stagedCommands = new ShellCommands(
      agent, ai, sessionState, editorLauncher, fileEditingTool,
      Mock(se.alipsa.lca.tools.ToolCallParser), stagedGit, Stub(CodeSearchTool),
      new ContextPacker(), new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner, commandPolicy, modelRegistry, agentPlatform, contextRepository,
      tempDir.resolve("staged-no-cache.log").toString(), null, null, shellSettings,
      intentRoutingState, intentRoutingSettings,
      Mock(se.alipsa.lca.validation.RequestValidator), Mock(se.alipsa.lca.validation.ClarificationDialog),
      null, null, null, null, contextCompactor, 80000, 30000, null
    )

    when: "a --staged review runs with a real staged diff"
    stagedCommands.review(
      "", "review my staged changes", "s-staged", null, null, null, null,
      null, true, ReviewSeverity.LOW, true, false, false, false, false, (Integer) null
    )

    and: "a target-less follow-up asks to verify"
    def response = stagedCommands.review(
      "", "verify these findings", "s-staged", null, null, null, null,
      null, false, ReviewSeverity.LOW, true, false, false, false, false, (Integer) null
    )

    then: "no NPE, and the fail-fast message fires, because --staged reviews are never cached"
    response == "No prior review to verify, and no file/PR specified — what would you like me to review?"
  }

  def "review --staged against a clean index reports nothing staged, without a prior cache"() {
    when:
    def response = commands.review(
      "", "review my staged changes", "s-clean-a", null, null, null, null,
      null, true, ReviewSeverity.LOW, true, false, false, false, false, (Integer) null
    )

    then: "gitTool.stagedDiff() is stubbed to fail in the shared fixture, i.e. a clean-index diff"
    response == "Nothing staged to review — git add your changes first, or drop --staged."
    0 * agentPlatform.createAgentProcessFrom(_, _, _)
  }

  def "review --staged against a clean index reports nothing staged even with a prior cached PR review"() {
    given:
    GitTool prGit = Mock(GitTool)
    prGit.prDiff(52) >> new GitTool.GitResult(true, true, 0, "diff content", "")
    prGit.prChangedFiles(52) >> new GitTool.GitResult(true, true, 0, "Foo.groovy", "")
    prGit.prHeadCommit(52) >> new GitTool.GitResult(true, true, 0, "sha1", "")
    prGit.showFileAtCommit("sha1", "Foo.groovy") >> new GitTool.GitResult(true, true, 0, "class Foo {}", "")
    prGit.stagedDiff() >> new GitTool.GitResult(false, true, 1, "", "")
    ShellCommands prCommands = new ShellCommands(
      agent, ai, sessionState, editorLauncher, fileEditingTool,
      Mock(se.alipsa.lca.tools.ToolCallParser), prGit, Stub(CodeSearchTool),
      new ContextPacker(), new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner, commandPolicy, modelRegistry, agentPlatform, contextRepository,
      tempDir.resolve("staged-clean-with-cache.log").toString(), null, null, shellSettings,
      intentRoutingState, intentRoutingSettings,
      Mock(se.alipsa.lca.validation.RequestValidator), Mock(se.alipsa.lca.validation.ClarificationDialog),
      null, null, null, null, contextCompactor, 80000, 30000, null
    )
    reviewProcess.resultOfType(ReviewResponse) >> new ReviewResponse(
      "Findings:\n- [High] Foo.groovy:1 - bug\nTests:\n- test"
    )
    agentPlatform.createAgentProcessFrom(reviewAgent, _ as ProcessOptions, _ as Object[]) >> reviewProcess

    when: "a PR review is cached first"
    prCommands.review(
      "", "review PR 52", "s-clean-b", null, null, null, null,
      null, false, ReviewSeverity.LOW, true, false, false, false, false, 52
    )

    and: "then a --staged review runs against a clean index"
    def response = prCommands.review(
      "", "review my staged changes", "s-clean-b", null, null, null, null,
      null, true, ReviewSeverity.LOW, true, false, false, false, false, (Integer) null
    )

    then: "reports nothing staged, does not silently substitute the cached PR #52 review"
    response == "Nothing staged to review — git add your changes first, or drop --staged."
  }

  def "review --code with only whitespace reports no code provided, even with a prior cache"() {
    given:
    reviewProcess.resultOfType(ReviewResponse) >> new ReviewResponse(
      "Findings:\n- [Low] Foo.groovy:1 - nit\nTests:\n- test"
    )
    agentPlatform.createAgentProcessFrom(reviewAgent, _ as ProcessOptions, _ as Object[]) >> reviewProcess
    fileEditingTool.readFile(_) >> "content"

    when: "a real path-based review is cached first"
    commands.review(
      "", "review this file", "s-whitespace", null, null, null, null,
      ["src/App.groovy"], false, ReviewSeverity.LOW, true, false, false, false, false, (Integer) null
    )

    and: "then --code is passed but is whitespace-only"
    def response = commands.review(
      "   ", "verify these findings", "s-whitespace", null, null, null, null,
      null, false, ReviewSeverity.LOW, true, false, false, false, false, (Integer) null
    )

    then:
    response == "No code provided to review — pass --code with content, or drop --code."
  }

  def "reviewing a directory target populates fileLineCounts, so an out-of-range line gets flagged UNVERIFIED"() {
    given:
    Path dir = tempDir.resolve("reviewdir")
    Files.createDirectories(dir)
    Files.writeString(dir.resolve("Sample.groovy"), "class Sample {\n  void x() {}\n}\n")
    reviewProcess.resultOfType(ReviewResponse) >> new ReviewResponse(
      "Findings:\n- [High] Sample.groovy:999 - out of range\nTests:\n- test it"
    )
    agentPlatform.createAgentProcessFrom(reviewAgent, _ as ProcessOptions, _ as Object[]) >> reviewProcess

    when:
    def response = commands.review(
      "", "review this directory", "s-dir", null, null, null, null,
      [dir.toString()], false, ReviewSeverity.LOW, true, false, false, false, false, (Integer) null
    )

    then:
    response.contains("[UNVERIFIED]")
  }

  def "the grounding warning appears in review's returned string, not only in stdout"() {
    given:
    ImplementationGroundingCheck stubbedGroundingCheck = Stub(ImplementationGroundingCheck) {
      checkFileReferences(_, _) >> new ImplementationGroundingCheck.GroundingResult(
        ImplementationGroundingCheck.GroundingLevel.UNCERTAIN,
        ["Referenced file(s) not found in the project: Fake.groovy"]
      )
    }
    ShellCommands groundedCommands = new ShellCommands(
      agent, ai, sessionState, editorLauncher, fileEditingTool,
      Mock(se.alipsa.lca.tools.ToolCallParser), gitTool, Stub(CodeSearchTool),
      new ContextPacker(), new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner, commandPolicy, modelRegistry, agentPlatform, contextRepository,
      tempDir.resolve("grounding-visible.log").toString(), null, null, shellSettings,
      intentRoutingState, intentRoutingSettings,
      Mock(se.alipsa.lca.validation.RequestValidator), Mock(se.alipsa.lca.validation.ClarificationDialog),
      null, stubbedGroundingCheck, null, null, contextCompactor, 80000, 30000, null
    )
    reviewProcess.resultOfType(ReviewResponse) >> new ReviewResponse(
      "Findings:\n- [High] Fake.groovy:1 - a fabricated citation\nTests:\n- test it"
    )
    agentPlatform.createAgentProcessFrom(reviewAgent, _ as ProcessOptions, _ as Object[]) >> reviewProcess
    fileEditingTool.readFile(_) >> "content"

    when:
    def response = groundedCommands.review(
      "", "review this file", "s-grounding", null, null, null, null,
      ["src/App.groovy"], false, ReviewSeverity.LOW, true, false, false, false, false, (Integer) null
    )

    then:
    response.contains("Fake.groovy")
    response.contains("Referenced file(s) not found in the project")
  }

  def "reviewing a directory cites its parent-relative label, and grounding does not flag it as missing"() {
    given: "a real (project-root-relative) grounding check, distinct from the temp dir being reviewed"
    ImplementationGroundingCheck realGroundingCheck = new ImplementationGroundingCheck()
    ShellCommands groundedCommands = new ShellCommands(
      agent, ai, sessionState, editorLauncher, fileEditingTool,
      Mock(se.alipsa.lca.tools.ToolCallParser), gitTool, Stub(CodeSearchTool),
      new ContextPacker(), new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner, commandPolicy, modelRegistry, agentPlatform, contextRepository,
      tempDir.resolve("grounding-dir-review.log").toString(), null, null, shellSettings,
      intentRoutingState, intentRoutingSettings,
      Mock(se.alipsa.lca.validation.RequestValidator), Mock(se.alipsa.lca.validation.ClarificationDialog),
      null, realGroundingCheck, null, null, contextCompactor, 80000, 30000, null
    )
    Path dir = tempDir.resolve("reviewdir")
    Files.createDirectories(dir)
    Files.writeString(dir.resolve("Sample.groovy"), "class Sample {\n  void x() {}\n}\n")
    // appendDirectoryContents labels files relative to the reviewed dir's parent (tempDir here),
    // so the model is instructed to cite it back as "reviewdir/Sample.groovy" — a path that does
    // not exist under the real project root the grounding check resolves against.
    reviewProcess.resultOfType(ReviewResponse) >> new ReviewResponse(
      "Findings:\n- [Low] reviewdir/Sample.groovy:1 - looks fine\nTests:\n- test it"
    )
    agentPlatform.createAgentProcessFrom(reviewAgent, _ as ProcessOptions, _ as Object[]) >> reviewProcess

    when:
    def response = groundedCommands.review(
      "", "review this directory", "s-dir-grounding", null, null, null, null,
      [dir.toString()], false, ReviewSeverity.LOW, true, false, false, false, false, (Integer) null
    )

    then:
    !response.contains("not found in the project")
  }

  def "a Tests: section naming a not-yet-created file does not trigger a grounding warning"() {
    given:
    ImplementationGroundingCheck realGroundingCheck = new ImplementationGroundingCheck(tempDir)
    ShellCommands groundedCommands = new ShellCommands(
      agent, ai, sessionState, editorLauncher, fileEditingTool,
      Mock(se.alipsa.lca.tools.ToolCallParser), gitTool, Stub(CodeSearchTool),
      new ContextPacker(), new ContextBudgetManager(10000, 0, new TokenEstimator(), 2, -1),
      commandRunner, commandPolicy, modelRegistry, agentPlatform, contextRepository,
      tempDir.resolve("grounding-tests-section.log").toString(), null, null, shellSettings,
      intentRoutingState, intentRoutingSettings,
      Mock(se.alipsa.lca.validation.RequestValidator), Mock(se.alipsa.lca.validation.ClarificationDialog),
      null, realGroundingCheck, null, null, contextCompactor, 80000, 30000, null
    )
    reviewProcess.resultOfType(ReviewResponse) >> new ReviewResponse(
      "Findings:\n- [Low] general - looks fine\n" +
      "Tests:\n- add ReviewLineNumberVerifierSpec.groovy covering the new verifier"
    )
    agentPlatform.createAgentProcessFrom(reviewAgent, _ as ProcessOptions, _ as Object[]) >> reviewProcess
    fileEditingTool.readFile(_) >> "content"

    when:
    def response = groundedCommands.review(
      "", "review this file", "s-tests-section", null, null, null, null,
      ["src/App.groovy"], false, ReviewSeverity.LOW, true, false, false, false, false, (Integer) null
    )

    then: "the Tests: section citation never enters summary.findings, so it never reaches checkFileReferences"
    !response.contains("not found in the project")
  }

  private ShellCommands commitCommandsFor(GitTool repoGit) {
    new ShellCommands(
      agent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      Mock(se.alipsa.lca.tools.ToolCallParser),
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
      ,
      Mock(se.alipsa.lca.validation.RequestValidator),
      Mock(se.alipsa.lca.validation.ClarificationDialog),
      null,
      null,
      null,
      null,
      contextCompactor,
      80000,
      30000,
      null
    )
  }
}
