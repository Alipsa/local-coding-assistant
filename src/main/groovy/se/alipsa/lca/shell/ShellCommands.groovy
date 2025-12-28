package se.alipsa.lca.shell

import com.embabel.agent.api.common.Ai
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.spi.ContextRepository
import com.embabel.agent.spi.support.SimpleContext
import com.embabel.chat.AssistantMessage
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.SpringBootVersion
import org.springframework.shell.ExitRequest
import org.springframework.shell.standard.ShellComponent
import org.springframework.shell.standard.ShellMethod
import org.springframework.shell.standard.ShellOption
import se.alipsa.lca.agent.CodingAssistantAgent
import se.alipsa.lca.agent.ChatRequest
import se.alipsa.lca.agent.PersonaMode
import se.alipsa.lca.agent.ReviewRequest
import se.alipsa.lca.agent.ReviewResponse
import se.alipsa.lca.review.ReviewFinding
import se.alipsa.lca.review.ReviewParser
import se.alipsa.lca.review.ReviewSeverity
import se.alipsa.lca.review.ReviewSummary
import se.alipsa.lca.intent.IntentRoutingSettings
import se.alipsa.lca.intent.IntentRoutingState
import se.alipsa.lca.shell.SessionState.SessionSettings
import se.alipsa.lca.tools.CodeSearchTool
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.GitTool
import se.alipsa.lca.tools.ContextPacker
import se.alipsa.lca.tools.ContextBudgetManager
import se.alipsa.lca.tools.PackedContext
import se.alipsa.lca.tools.CommandRunner
import se.alipsa.lca.tools.CommandPolicy
import se.alipsa.lca.tools.TokenEstimator
import se.alipsa.lca.tools.WebSearchTool
import se.alipsa.lca.tools.ModelRegistry
import se.alipsa.lca.tools.TreeTool
import se.alipsa.lca.tools.SecretScanner
import se.alipsa.lca.tools.SastTool
import se.alipsa.lca.tools.LogSanitizer

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import java.util.Locale

@ShellComponent("lcaShellCommands")
@CompileStatic
class ShellCommands {

  private static final Logger log = LoggerFactory.getLogger(ShellCommands)
  private static final String CHAT_AGENT_NAME = "lca-chat"
  private static final String REVIEW_AGENT_NAME = "lca-review"
  private static final String DEFAULT_SESSION = "default"
  private static final List<String> PLAN_COMMANDS = List.of(
    "/chat",
    "/plan",
    "/review",
    "/edit",
    "/apply",
    "/run",
    "/gitapply",
    "/git-push",
    "/search"
  )
  private static final String PLAN_RESPONSE_FORMAT = """
Respond with a numbered list only.
Each step must start with a command from the allow-list and include a short explanation.
Format: 1. /review src/main/groovy - Review code quality.
Do not execute any commands.
""".stripIndent().trim()
  private static final Set<String> INTENT_ON_VALUES = Set.of("on", "enable", "enabled", "true", "yes", "y")
  private static final Set<String> INTENT_OFF_VALUES = Set.of("off", "disable", "disabled", "false", "no", "n")
  private static final Set<String> INTENT_DEFAULT_VALUES = Set.of("default", "reset", "auto")
  private static final Set<String> WEB_SEARCH_DISABLED_VALUES = Set.of("disabled", "disable", "off", "false", "no", "n")
  private static final Set<String> WEB_SEARCH_DEFAULT_VALUES = Set.of("default", "reset", "auto")
  private static final int WEB_SEARCH_SUMMARY_LIMIT = 3
  private static final int WEB_SEARCH_SUMMARY_MAX_CHARS = 1200
  private static final long DIRECT_SHELL_TIMEOUT_MILLIS = 60000L
  private static final int DIRECT_SHELL_MAX_OUTPUT_CHARS = 8000
  private static final int DIRECT_SHELL_SUMMARY_MAX_CHARS = 400
  private static final int DIRECT_SHELL_CONVERSATION_MAX_CHARS = 2000
  private final CodingAssistantAgent codingAssistantAgent
  private final Ai ai
  private final AgentPlatform agentPlatform
  private final ContextRepository contextRepository
  private final SessionState sessionState
  private final EditorLauncher editorLauncher
  private final FileEditingTool fileEditingTool
  private final GitTool gitTool
  private final CodeSearchTool codeSearchTool
  private final ContextPacker contextPacker
  private final ContextBudgetManager contextBudgetManager
  private final CommandRunner commandRunner
  private final CommandPolicy commandPolicy
  private final ModelRegistry modelRegistry
  private final ShellSettings shellSettings
  private final IntentRoutingState intentRoutingState
  private final IntentRoutingSettings intentRoutingSettings
  private final TreeTool treeTool
  private final SecretScanner secretScanner
  private final SastTool sastTool
  private final Path reviewLogPath
  private volatile boolean applyAllConfirmed = false
  private volatile boolean batchMode = false
  private volatile boolean assumeYes = false

  @ShellMethod(key = ["/exit", "/quit"], value = "Exit the shell.")
  ExitRequest exitShell() {
    new ExitRequest(0)
  }

  @ShellMethod(key = ["/version"], value = "Show the running application version.")
  String version() {
    List<String> lines = new ArrayList<String>()
    lines.add("lca version: ${resolveVersion()}".toString())
    lines.add("Embabel version: ${resolveEmbabelVersion()}".toString())
    lines.add("Spring-boot version: ${resolveSpringBootVersion()}".toString())
    List<String> modelInfo = resolveModelVersionInfo()
    if (!modelInfo.isEmpty()) {
      lines.addAll(modelInfo)
    }
    lines.join("\n")
  }

  @ShellMethod(key = ["/config"], value = "View or update shell settings.")
  String config(
    @ShellOption(
      defaultValue = ShellOption.NULL,
      help = "Enable or disable auto-paste detection (true/false)"
    ) Boolean autoPaste,
    @ShellOption(
      defaultValue = ShellOption.NULL,
      value = "local-only",
      help = "Enable or disable local-only mode for this session (true/false)"
    ) Boolean localOnly,
    @ShellOption(
      defaultValue = ShellOption.NULL,
      value = "web-search",
      help = "Set web search for this session (htmlunit/jsoup/disabled/default)"
    ) String webSearch,
    @ShellOption(
      defaultValue = ShellOption.NULL,
      value = "web-search-fetcher",
      help = "Set web search fetcher for this session (htmlunit/jsoup/default)"
    ) String webSearchFetcher,
    @ShellOption(
      defaultValue = ShellOption.NULL,
      value = "web-search-fallback",
      help = "Set fallback web search fetcher (htmlunit/jsoup/none/default)"
    ) String webSearchFallback,
    @ShellOption(
      defaultValue = ShellOption.NULL,
      value = "intent",
      help = "Enable or disable intent routing for this session (enabled/disabled/default)"
    ) String intent
  ) {
    if (autoPaste != null) {
      shellSettings.setAutoPasteEnabled(autoPaste)
    }
    if (localOnly != null) {
      sessionState.setLocalOnlyOverride(DEFAULT_SESSION, localOnly)
    }
    if (webSearch != null) {
      applyWebSearchMode(DEFAULT_SESSION, webSearch)
    } else {
      if (webSearchFetcher != null) {
        sessionState.setWebSearchFetcherOverride(DEFAULT_SESSION, webSearchFetcher)
      }
      if (webSearchFallback != null) {
        sessionState.setWebSearchFallbackFetcherOverride(DEFAULT_SESSION, webSearchFallback)
      }
    }
    if (intent != null) {
      applyIntentMode(intent)
    }
    String autoPasteState = shellSettings.isAutoPasteEnabled() ? "enabled" : "disabled"
    String localOnlyState = sessionState.isLocalOnly(DEFAULT_SESSION) ? "enabled" : "disabled"
    String webSearchState = formatWebSearchState(DEFAULT_SESSION)
    String intentState = formatIntentState()
    formatSection(
      "Configuration",
      "Auto-paste: ${autoPasteState}\n" +
        "Local-only: ${localOnlyState}\n" +
        "web-search: ${webSearchState}\n" +
        "Intent routing: ${intentState}"
    )
  }

  @ShellMethod(key = ["/help"], value = "Show available slash commands.")
  String help() {
    Map<String, String> commands = new LinkedHashMap<>()
    commands.put("/!", "Execute a shell command directly (alias: /sh).")
    commands.put("/apply", "Apply a unified diff patch with confirmation.")
    commands.put("/applyBlocks", "Apply Search-and-Replace blocks to a file.")
    commands.put("/chat", "Send a prompt to the coding assistant.")
    commands.put("/codesearch", "Search repository files with ripgrep.")
    commands.put("/commit-suggest", "Draft a commit message from staged changes.")
    commands.put("/config", "View or update shell settings.")
    commands.put("/context", "Show a snippet for targeted edits.")
    commands.put("/diff", "Show git diff with optional staging.")
    commands.put("/edit", "Open editor to draft a prompt.")
    commands.put("/exit", "Exit the shell (alias: /quit).")
    commands.put("/gitapply", "Apply a patch using git apply (alias: /git-apply).")
    commands.put("/git-push", "Push the current branch with confirmation.")
    commands.put("/health", "Check connectivity to Ollama.")
    commands.put("/help", "Show available slash commands.")
    commands.put("/intent-debug", "Toggle intent routing debug output.")
    commands.put("/model", "List or set the active session model.")
    commands.put("/paste", "Enter paste mode; optionally send to /chat.")
    commands.put("/plan", "Create a step-by-step plan using CLI commands.")
    commands.put("/review", "Ask the assistant to review code.")
    commands.put("/reviewlog", "Show recent reviews from the log.")
    commands.put("/revert", "Restore a file using the most recent patch backup.")
    commands.put("/route", "Preview intent routing output.")
    commands.put("/run", "Execute a project command with timeout and logging.")
    commands.put("/search", "Run web search through the agent tool.")
    commands.put("/stage", "Stage files or hunks with confirmation.")
    commands.put("/status", "Show git status for the current repository.")
    commands.put("/tree", "Show repository tree.")
    commands.put("/version", "Show the running application version.")
    List<String> keys = new ArrayList<>(commands.keySet())
    keys.sort { String left, String right -> left <=> right }
    String commandLines = keys.collect { String key ->
      "- ${key}: ${commands.get(key)}"
    }.join("\n")
    String configLines = [
      "- auto-paste: true|false",
      "- intent: enabled|disabled|default",
      "- local-only: true|false",
      "- web-search: htmlunit|jsoup|disabled|default"
    ].join("\n")
    formatSection("Help", "Commands:\n${commandLines}\n\nConfig options (/config):\n${configLines}")
  }

  ShellCommands(
    CodingAssistantAgent codingAssistantAgent,
    Ai ai,
    SessionState sessionState,
    EditorLauncher editorLauncher,
    FileEditingTool fileEditingTool,
    GitTool gitTool,
    CodeSearchTool codeSearchTool,
    ContextPacker contextPacker,
    ContextBudgetManager contextBudgetManager,
    CommandRunner commandRunner,
    CommandPolicy commandPolicy,
    ModelRegistry modelRegistry,
    AgentPlatform agentPlatform,
    ContextRepository contextRepository,
    @Value('${review.log.path:.lca/reviews.log}') String reviewLogPath,
    TreeTool treeTool,
    SastTool sastTool,
    ShellSettings shellSettings,
    IntentRoutingState intentRoutingState,
    IntentRoutingSettings intentRoutingSettings
  ) {
    this.codingAssistantAgent = codingAssistantAgent
    this.ai = ai
    this.agentPlatform = agentPlatform
    this.sessionState = sessionState
    this.editorLauncher = editorLauncher
    this.fileEditingTool = fileEditingTool
    Path root = resolveProjectRoot(fileEditingTool)
    this.gitTool = gitTool != null ? gitTool : new GitTool(root)
    this.codeSearchTool = codeSearchTool != null ? codeSearchTool : new CodeSearchTool()
    this.contextPacker = contextPacker != null ? contextPacker : new ContextPacker()
    this.contextBudgetManager = contextBudgetManager != null
      ? contextBudgetManager
      : new ContextBudgetManager(12000, 0, new TokenEstimator(), 2, -1)
    this.commandRunner = commandRunner != null ? commandRunner : new CommandRunner(root)
    this.commandPolicy = commandPolicy != null ? commandPolicy : new CommandPolicy("", "")
    this.modelRegistry = modelRegistry
    this.shellSettings = shellSettings
    this.intentRoutingState = intentRoutingState
    this.intentRoutingSettings = intentRoutingSettings
    this.contextRepository = contextRepository
    this.treeTool = treeTool != null ? treeTool : new TreeTool(root, this.gitTool)
    this.secretScanner = new SecretScanner()
    this.sastTool = sastTool != null ? sastTool : new SastTool(this.commandRunner, this.commandPolicy, "", 60000L, 8000)
    String reviewPath = reviewLogPath != null && reviewLogPath.trim() ? reviewLogPath : ".lca/reviews.log"
    this.reviewLogPath = Paths.get(reviewPath).toAbsolutePath()
  }

  @ShellMethod(key = ["/chat"], value = "Send a prompt to the coding assistant.")
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
    requireNonBlank(prompt, "prompt")
    String health = ensureOllamaHealth()
    if (health != null) {
      return health
    }
    ModelResolution resolution = resolveModel(model)
    SessionSettings settings = sessionState.update(
      session,
      resolution.chosen,
      temperature,
      reviewTemperature,
      maxTokens,
      systemPrompt,
      null
    )
    LlmOptions options = sessionState.craftOptions(settings)
    String fallbackNote = resolution.fallbackUsed ? "Note: using fallback model ${resolution.chosen}." : null
    String system = sessionState.systemPrompt(settings)
    Agent agent = resolveAgent(CHAT_AGENT_NAME)
    if (agent == null) {
      return "Chat agent unavailable; ensure Embabel agents are enabled."
    }
    def conversation = sessionState.getOrCreateConversation(session)
    String planPrompt = buildPlanPrompt(session, prompt)
    UserMessage userMessage = new UserMessage(planPrompt)
    conversation.addMessage(userMessage)
    ChatRequest request = new ChatRequest(persona, options, system, null)
    AssistantMessage reply = runAgent(agent, session, AssistantMessage, conversation, request, userMessage)
    String replyText = reply?.textContent
    if (replyText == null || replyText.trim().isEmpty()) {
      return "No response generated."
    }
    sessionState.appendHistory(session, "User: ${prompt}", "Assistant: ${replyText}")
    if (fallbackNote != null) {
      return fallbackNote + "\n" + replyText
    }
    replyText
  }

  @ShellMethod(key = ["/plan"], value = "Create a step-by-step plan using CLI commands.")
  String plan(
    @ShellOption(help = "Planning request; multiline supported by quoting or paste mode") String prompt,
    @ShellOption(defaultValue = "default", help = "Session id for persisting options") String session,
    @ShellOption(defaultValue = "ARCHITECT", help = "Persona mode: CODER, ARCHITECT, REVIEWER") PersonaMode persona,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override model for this session") String model,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override craft temperature") Double temperature,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override review temperature") Double reviewTemperature,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override max tokens") Integer maxTokens,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Additional system prompt guidance") String systemPrompt
  ) {
    requireNonBlank(prompt, "prompt")
    String health = ensureOllamaHealth()
    if (health != null) {
      return health
    }
    ModelResolution resolution = resolveModel(model)
    SessionSettings settings = sessionState.update(
      session,
      resolution.chosen,
      temperature,
      reviewTemperature,
      maxTokens,
      systemPrompt,
      null
    )
    LlmOptions options = sessionState.craftOptions(settings)
    String fallbackNote = resolution.fallbackUsed ? "Note: using fallback model ${resolution.chosen}." : null
    String baseSystem = sessionState.systemPrompt(settings)
    String planSystem = buildPlanSystemPrompt(baseSystem)
    Agent agent = resolveAgent(CHAT_AGENT_NAME)
    if (agent == null) {
      return "Chat agent unavailable; ensure Embabel agents are enabled."
    }
    def conversation = sessionState.getOrCreateConversation(session)
    String planPrompt = buildPlanPrompt(session, prompt)
    UserMessage userMessage = new UserMessage(planPrompt)
    conversation.addMessage(userMessage)
    ChatRequest request = new ChatRequest(persona, options, planSystem, PLAN_RESPONSE_FORMAT)
    AssistantMessage reply = runAgent(agent, session, AssistantMessage, conversation, request, userMessage)
    String replyText = reply?.textContent
    if (replyText == null || replyText.trim().isEmpty()) {
      return "No response generated."
    }
    sessionState.appendHistory(session, "User: ${prompt}", "Assistant (plan): ${replyText}")
    if (fallbackNote != null) {
      return fallbackNote + "\n" + replyText
    }
    replyText
  }

  @ShellMethod(key = ["/review"], value = "Ask the assistant to review code.")
  String review(
    @ShellOption(defaultValue = "", help = "Code to review; optional when providing paths or staged diff") String code,
    @ShellOption(help = "Review context or request") String prompt,
    @ShellOption(defaultValue = "default", help = "Session id") String session,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override model") String model,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override review temperature") Double reviewTemperature,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override max tokens") Integer maxTokens,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Additional system prompt guidance") String systemPrompt,
    @ShellOption(
      defaultValue = ShellOption.NULL,
      arity = -1,
      help = "File paths to include in the review context"
    ) List<String> paths,
    @ShellOption(defaultValue = "false", help = "Include staged git diff") boolean staged,
    @ShellOption(defaultValue = "LOW", help = "Minimum severity to display/log: LOW, MEDIUM, HIGH") ReviewSeverity minSeverity,
    @ShellOption(defaultValue = "false", help = "Disable ANSI colors in output") boolean noColor,
    @ShellOption(defaultValue = "true", help = "Persist review summary to log file") boolean logReview,
    @ShellOption(defaultValue = "false", help = "Focus on security risks in the review") boolean security,
    @ShellOption(defaultValue = "false", help = "Run optional SAST scan before review") boolean sast
  ) {
    requireNonBlank(prompt, "prompt")
    ReviewSeverity severityThreshold = minSeverity ?: ReviewSeverity.LOW
    String health = ensureOllamaHealth()
    if (health != null) {
      return health
    }
    printProgressStart("Review")
    ModelResolution resolution = resolveModel(model)
    SessionSettings settings = sessionState.update(session, resolution.chosen, null, reviewTemperature, maxTokens, systemPrompt, null)
    LlmOptions reviewOptions = sessionState.reviewOptions(settings)
    String system = sessionState.systemPrompt(settings)
    String reviewPayload = buildReviewPayload(code, paths, staged)
    Agent agent = resolveAgent(REVIEW_AGENT_NAME)
    if (agent == null) {
      printProgressDone("Review")
      return "Review agent unavailable; ensure Embabel agents are enabled."
    }
    ReviewRequest request = new ReviewRequest(prompt, reviewPayload, reviewOptions, system, security)
    ReviewResponse response = runAgent(agent, session, ReviewResponse, request)
    printProgressDone("Review")
    String reviewText = response?.review
    if (reviewText == null || reviewText.trim().isEmpty()) {
      return "No review response generated."
    }
    ReviewSummary summary = ReviewParser.parse(reviewText)
    String rendered = renderReview(summary, severityThreshold, !noColor)
    String sastBlock = buildSastBlock(sast, paths)
    if (resolution.fallbackUsed) {
      rendered = "Note: using fallback model ${resolution.chosen}.\n" + rendered
    }
    String output = formatSection("Review", rendered + sastBlock)
    sessionState.appendHistory(session, "User review request: ${prompt}", "Review: ${output}")
    if (logReview) {
      writeReviewLog(prompt, summary, paths, staged, severityThreshold)
    }
    output
  }

  @ShellMethod(key = ["/reviewlog"], value = "Show recent reviews from the log with filters.")
  String reviewLog(
    @ShellOption(defaultValue = "LOW", help = "Minimum severity to show") ReviewSeverity minSeverity,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Path substring filter") String pathFilter,
    @ShellOption(defaultValue = "5", help = "Maximum entries to show") int limit,
    @ShellOption(defaultValue = "1", help = "Page number (1-based)") int page,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Show entries since ISO timestamp, e.g. 2025-02-12T10:00:00Z") String since,
    @ShellOption(defaultValue = "false", help = "Disable ANSI colors") boolean noColor
  ) {
    if (!Files.exists(reviewLogPath)) {
      return "No reviews logged yet."
    }
    requireMin(limit, 1, "limit")
    requireMin(page, 1, "page")
    Instant sinceInstant = parseInstant(since)
    List<LogEntry> entries = loadLogEntries(pathFilter, minSeverity, sinceInstant)
    if (entries.isEmpty()) {
      return "No matching review entries."
    }
    int pageSize = Math.max(1, limit)
    int startIndex = Math.max(0, (Math.max(1, page) - 1) * pageSize)
    if (startIndex >= entries.size()) {
      startIndex = Math.max(0, entries.size() - pageSize)
    }
    entries = entries.subList(startIndex, Math.min(entries.size(), startIndex + pageSize))
    entries.collect { LogEntry entry ->
      StringBuilder builder = new StringBuilder()
      builder.append("Prompt: ").append(entry.prompt).append("\n")
      builder.append("Paths: ").append(entry.paths ?: "none").append("\n")
      builder.append("Staged: ").append(entry.staged).append("\n")
      if (entry.timestamp != null) {
        builder.append("Timestamp: ").append(entry.timestamp).append("\n")
      }
      builder.append(renderReview(entry.summary, minSeverity, !noColor))
      builder.toString()
    }.join("\n\n---\n\n")
  }

  @ShellMethod(key = ["/search"], value = "Run web search through the agent tool.")
  String search(
    @ShellOption(help = "Query to search") String query,
    @ShellOption(defaultValue = "5", help = "Number of results to show") int limit,
    @ShellOption(defaultValue = "default", help = "Session id for caching and configuration") String session,
    @ShellOption(defaultValue = "duckduckgo", help = "Search provider") String provider,
    @ShellOption(defaultValue = "15000", help = "Timeout in milliseconds") long timeoutMillis,
    @ShellOption(defaultValue = "true", help = "Run browser in headless mode") boolean headless,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override web search enablement (true/false)") Boolean enableWebSearch
  ) {
    requireNonBlank(query, "query")
    requireMin(limit, 1, "limit")
    requireMin(timeoutMillis, 1, "timeoutMillis")
    String sessionId = session != null && session.trim() ? session.trim() : DEFAULT_SESSION
    Boolean overrideEnabled = enableWebSearch
    boolean desired = overrideEnabled != null ? overrideEnabled : sessionState.isWebSearchDesired(sessionId)
    if (!desired) {
      return "Web search is disabled for this session. " +
        "Enable globally in application.properties with assistant.web-search.enabled=true, " +
        "or enable for this request by passing --enable-web-search true."
    }
    if (sessionState.isLocalOnly(sessionId)) {
      if (!promptDisableLocalOnly(sessionId)) {
        return "Web search is disabled in local-only mode. Set assistant.local-only=false to enable."
      }
    }
    boolean defaultEnabled = sessionState.isWebSearchEnabled(sessionId)
    printProgressStart("Web search")
    WebSearchTool.SearchOptions options = WebSearchTool.withDefaults(
      new WebSearchTool.SearchOptions(
        provider: WebSearchTool.providerFrom(provider),
        limit: limit,
        headless: headless,
        timeoutMillis: timeoutMillis,
        sessionId: sessionId,
        webSearchEnabled: overrideEnabled,
        fetcherName: sessionState.getWebSearchFetcher(sessionId),
        fallbackFetcherName: sessionState.getWebSearchFallbackFetcher(sessionId)
      ),
      defaultEnabled
    )
    List<WebSearchTool.SearchResult> results
    try {
      results = codingAssistantAgent.search(query, options)
    } catch (Exception e) {
      results = []
      printProgressDone("Web search")
      return "Web search unavailable: ${e.message ?: e.class.simpleName}"
    }
    printProgressDone("Web search")
    if (results == null || results.isEmpty()) {
      recordWebSearchSummary(sessionId, query, results)
      return formatSection("Web Search", "No web results.")
    }
    recordWebSearchSummary(sessionId, query, results)
    String body = results.withIndex().collect { WebSearchTool.SearchResult result, int idx ->
      String title = result.title ?: "(no title)"
      String url = result.url ?: "(no url)"
      String snippet = result.snippet ?: ""
      "${idx + 1}. ${title} - ${url}\n${snippet}"
    }.join("\n\n")
    formatSection("Web Search", "Results: ${results.size()}\n${body}")
  }

  @ShellMethod(
    key = ["/codesearch"],
    value = "Search repository files with ripgrep and build context."
  )
  String codeSearch(
    @ShellOption(help = "Pattern to search for") String query,
    @ShellOption(defaultValue = ShellOption.NULL, arity = -1, help = "Paths or globs to search") List<String> paths,
    @ShellOption(defaultValue = "2", help = "Context lines around matches") int context,
    @ShellOption(defaultValue = "20", help = "Maximum matches to return") int limit,
    @ShellOption(defaultValue = "false", help = "Pack results into a single context blob") boolean pack,
    @ShellOption(defaultValue = "8000", help = "Max chars when packing") int maxChars,
    @ShellOption(defaultValue = "0", help = "Max tokens when packing (0 uses default)") int maxTokens
  ) {
    requireNonBlank(query, "query")
    requireMin(context, 0, "context")
    requireMin(limit, 1, "limit")
    requireMin(maxChars, 0, "maxChars")
    requireMin(maxTokens, 0, "maxTokens")
    printProgressStart("Repository search")
    List<CodeSearchTool.SearchHit> hits = codeSearchTool.search(query, paths, context, limit)
    printProgressDone("Repository search")
    if (hits.isEmpty()) {
      return formatSection("Code Search", "No matches found.")
    }
    if (pack) {
      PackedContext packed = contextPacker.pack(hits, maxChars)
      int tokens = maxTokens > 0 ? maxTokens : contextBudgetManager.maxTokens
      int chars = maxChars > 0 ? maxChars : contextBudgetManager.maxChars
      def budgeted = contextBudgetManager.applyBudget(packed.text, packed.included, chars, tokens)
      String summary = "Packed ${budgeted.included.size()} matches" +
        ((packed.truncated || budgeted.truncated) ? " (truncated)" : "")
      return formatSection("Code Search", summary + "\n" + budgeted.text)
    }
    String body = hits.collect { CodeSearchTool.SearchHit hit ->
      "${hit.path}:${hit.line}:${hit.column}\n${hit.snippet}"
    }.join("\n\n")
    formatSection("Code Search", "Matches: ${hits.size()}\n${body}")
  }

  @ShellMethod(key = ["/edit"], value = "Open default editor to draft a prompt, optionally send to assistant.")
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

  @ShellMethod(key = ["/paste"], value = "Enter paste mode; end input with a line containing only /end.")
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

  @ShellMethod(key = ["/status"], value = "Show git status for the current repository.")
  String gitStatus(
    @ShellOption(defaultValue = "false", help = "Use short porcelain output") boolean shortFormat
  ) {
    formatGitResult("Status", gitTool.status(shortFormat))
  }

  @ShellMethod(key = ["/diff"], value = "Show git diff with optional staging and path filters.")
  String gitDiff(
    @ShellOption(defaultValue = "false", help = "Use staged diff (--cached)") boolean staged,
    @ShellOption(defaultValue = "3", help = "Number of context lines") int context,
    @ShellOption(defaultValue = ShellOption.NULL, arity = -1, help = "Paths to include") List<String> paths,
    @ShellOption(defaultValue = "false", help = "Show stats instead of full patch") boolean stat
  ) {
    requireMin(context, 0, "context")
    GitTool.GitResult result = gitTool.diff(staged, paths, context, stat)
    formatGitResult("Diff", result)
  }

  @ShellMethod(
    key = ["/gitapply", "/git-apply"],
    value = "Apply a patch using git apply (optionally to index) with confirmation."
  )
  String gitApply(
    @ShellOption(
      defaultValue = ShellOption.NULL,
      help = "Patch text; ignored when patch-file is provided"
    ) String patch,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Patch file relative to project root") String patchFile,
    @ShellOption(defaultValue = "false", help = "Apply to index (--cached)") boolean cached,
    @ShellOption(defaultValue = "true", help = "Run git apply --check before writing") boolean check,
    @ShellOption(defaultValue = "true", help = "Ask for confirmation before applying") boolean confirm
  ) {
    String body = resolvePatchBody(patch, patchFile)
    GitTool.GitResult preview = null
    if (check) {
      preview = gitTool.applyPatch(body, cached, true)
      if (!preview.repoPresent) {
        return formatGitResult("Git apply", preview)
      }
      if (!preview.success) {
        return formatGitResult("Git apply check failed", preview)
      }
      println(formatGitResult("Git apply preview", preview))
    } else if (!gitTool.isGitRepo()) {
      return "Not a git repository."
    }
    boolean shouldConfirm = confirm && !applyAllConfirmed
    if (shouldConfirm) {
      ConfirmChoice choice = confirmAction("Apply patch with git apply${cached ? ' --cached' : ''}?")
      if (choice == ConfirmChoice.NO) {
        return "Git apply canceled."
      }
      if (choice == ConfirmChoice.ALL) {
        applyAllConfirmed = true
      }
    }
    warnDirtyWorkspace()
    GitTool.GitResult applied = gitTool.applyPatch(body, cached, false)
    formatGitResult("Git apply", applied)
  }

  @ShellMethod(key = ["/stage"], value = "Stage files or specific hunks with confirmation.")
  String stage(
    @ShellOption(defaultValue = ShellOption.NULL, arity = -1, help = "File paths to stage") List<String> paths,
    @ShellOption(defaultValue = ShellOption.NULL, help = "File to stage hunks from") String file,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Comma-separated hunk numbers to stage") String hunks,
    @ShellOption(defaultValue = "true", help = "Ask for confirmation before staging") boolean confirm
  ) {
    boolean hunkMode = hunks != null && hunks.trim()
    if (!hunkMode && (paths == null || paths.isEmpty())) {
      throw new IllegalArgumentException("Provide file paths or hunk numbers to stage.")
    }
    boolean shouldConfirm = confirm && !applyAllConfirmed
    if (shouldConfirm) {
      String targetLabel = hunkMode ? "hunks from ${file}" : "${paths.size()} file(s)"
      ConfirmChoice choice = confirmAction("Stage ${targetLabel}?")
      if (choice == ConfirmChoice.NO) {
        return "Staging canceled."
      }
      if (choice == ConfirmChoice.ALL) {
        applyAllConfirmed = true
      }
    }
    GitTool.GitResult result
    if (hunkMode) {
      if (file == null || file.trim().isEmpty()) {
        throw new IllegalArgumentException("Specify --file when staging hunks.")
      }
      List<Integer> indexes = parseHunkIndexes(hunks)
      result = gitTool.stageHunks(file, indexes)
    } else {
      result = gitTool.stageFiles(paths)
    }
    formatGitResult("Stage", result)
  }

  @ShellMethod(
    key = ["/commit-suggest"],
    value = "Draft an imperative commit message from staged changes."
  )
  String commitSuggest(
    @ShellOption(defaultValue = "default", help = "Session id for options") String session,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override model") String model,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override temperature") Double temperature,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override max tokens") Integer maxTokens,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Optional guidance for the commit message") String hint,
    @ShellOption(defaultValue = "true", help = "Scan staged diff for secrets") boolean secretScan,
    @ShellOption(defaultValue = "false", help = "Allow commit suggestion even if secrets are detected") boolean allowSecrets
  ) {
    if (!gitTool.isGitRepo()) {
      return "Not a git repository; cannot suggest a commit message."
    }
    if (!gitTool.hasStagedChanges()) {
      return "No staged changes found. Stage files or hunks first."
    }
    GitTool.GitResult diff = gitTool.stagedDiff()
    if (!diff.success) {
      return formatGitResult("Staged diff", diff)
    }
    List<SecretScanner.SecretFinding> findings = secretScan ? secretScanner.scan(diff.output ?: "") : List.of()
    if (!findings.isEmpty() && !allowSecrets) {
      String details = findings.collect { finding ->
        "- ${finding.label} at line ${finding.line}: ${finding.maskedValue}"
      }.join("\n")
      return "Potential secrets detected in staged changes.\n${details}\n" +
        "Remove secrets or re-run with --allow-secrets true to proceed."
    }
    String health = ensureOllamaHealth()
    if (health != null) {
      return health
    }
    ModelResolution resolution = resolveModel(model)
    SessionSettings settings = sessionState.update(session, resolution.chosen, temperature, null, maxTokens, null, null)
    LlmOptions options = sessionState.craftOptions(settings)
    String secretNote = !findings.isEmpty() ? "User acknowledged potential secrets in staged diff." : null
    String prompt = buildCommitPrompt(diff.output ?: "", hint, sessionState.systemPrompt(settings), secretNote)
    String message = ai.withLlm(options).generateText(prompt)
    if (resolution.fallbackUsed) {
      message = "Note: using fallback model ${resolution.chosen}.\n" + (message ?: "")
    }
    sessionState.appendHistory(session, "Commit suggest request", message)
    message?.trim() ?: "No commit message generated."
  }

  @ShellMethod(key = ["/git-push"], value = "Push the current branch with confirmation.")
  String gitPush(
    @ShellOption(defaultValue = "false", help = "Use --force-with-lease") boolean force,
    @ShellOption(defaultValue = "true", help = "Ask for confirmation before pushing") boolean confirm
  ) {
    if (!gitTool.isGitRepo()) {
      return "Not a git repository."
    }
    if (confirm && !applyAllConfirmed) {
      ConfirmChoice choice = confirmAction("Run git push${force ? ' --force-with-lease' : ''}?")
      if (choice != ConfirmChoice.YES && choice != ConfirmChoice.ALL) {
        return "Push canceled."
      }
      if (choice == ConfirmChoice.ALL) {
        applyAllConfirmed = true
      }
    }
    GitTool.GitResult result = gitTool.push(force)
    formatGitResult("Push", result)
  }

  @ShellMethod(
    key = ["/model"],
    value = "List available Ollama models and switch the active session model."
  )
  String model(
    @ShellOption(defaultValue = ShellOption.NULL, help = "Model name to set for the session") String set,
    @ShellOption(defaultValue = "default", help = "Session id") String session,
    @ShellOption(defaultValue = "false", help = "List available models") boolean list
  ) {
    ModelRegistry.Health health = modelRegistry.checkHealth()
    List<String> available = health.reachable ? modelRegistry.listModels() : List.of()
    StringBuilder builder = new StringBuilder()
    if (!health.reachable) {
      builder.append("Ollama unreachable at ").append(modelRegistry.getBaseUrl())
        .append(": ").append(health.message).append("\n")
    }
    if (list || set == null) {
      builder.append("Default: ").append(sessionState.getDefaultModel() ?: "unspecified")
        .append("\nFallback: ").append(sessionState.getFallbackModel() ?: "none")
        .append("\nSession override: ").append(sessionState.getOrCreate(session).getModel() ?: "none")
        .append("\nAvailable: ")
      if (available.isEmpty()) {
        builder.append("unavailable (check Ollama)")
      } else {
        builder.append(String.join(", ", available))
      }
      if (set == null) {
        return builder.toString().stripTrailing()
      }
      builder.append("\n")
    }
    ModelResolution resolution = resolveModel(set, available)
    sessionState.update(session, resolution.chosen, null, null, null, null, null)
    builder.append("Session ").append(session).append(" model set to ").append(resolution.chosen)
    if (resolution.fallbackUsed) {
      builder.append(" (fallback from ").append(resolution.requested).append(")")
    }
    builder.toString().stripTrailing()
  }

  @ShellMethod(
    key = ["/health"],
    value = "Check connectivity to Ollama base URL."
  )
  String health() {
    ModelRegistry.Health health = modelRegistry.checkHealth()
    if (health.reachable) {
      return "Ollama reachable at ${modelRegistry.getBaseUrl()}: ${health.message}"
    }
    "Ollama unreachable at ${modelRegistry.getBaseUrl()}: ${health.message}"
  }

  @ShellMethod(
    key = ["/!", "/sh"],
    value = "Execute a shell command directly with streaming output."
  )
  String shellCommand(
    @ShellOption(help = "Command to execute (runs via bash -lc)") String command,
    @ShellOption(defaultValue = DEFAULT_SESSION, help = "Session id for history logging") String session
  ) {
    // Intentionally no confirmation prompt to mirror a direct shell mode; rely on CommandPolicy for guardrails.
    requireNonBlank(command, "command")
    String trimmed = command.trim()
    CommandPolicy.Decision decision = commandPolicy.evaluate(trimmed)
    if (!decision.allowed) {
      return decision.message ?: "Command blocked by policy."
    }
    CommandRunner.OutputListener listener = { String stream, String line ->
      if ("ERR" == stream) {
        System.err.println(line)
        System.err.flush()
      } else {
        println(line)
      }
    } as CommandRunner.OutputListener
    CommandRunner.CommandResult result = commandRunner.runStreaming(
      trimmed,
      DIRECT_SHELL_TIMEOUT_MILLIS,
      DIRECT_SHELL_MAX_OUTPUT_CHARS,
      listener
    )
    String summary = summarizeOutput(result?.output, 5, DIRECT_SHELL_SUMMARY_MAX_CHARS)
    sessionState.appendHistory(
      session,
      "Shell command: ${trimmed}",
      "Exit ${result?.timedOut ? 'timeout' : result?.exitCode}; ${summary}"
    )
    appendShellCommandToConversation(session, trimmed, result)
    formatDirectShellResult(trimmed, result)
  }

  @ShellMethod(
    key = ["/run"],
    value = "Execute a project command with timeout, truncation, and logging."
  )
  String runCommand(
    @ShellOption(help = "Command to execute (runs via bash -lc)") String command,
    @ShellOption(defaultValue = "60000", help = "Timeout in milliseconds") long timeoutMillis,
    @ShellOption(defaultValue = "8000", help = "Maximum output characters to display") int maxOutputChars,
    @ShellOption(defaultValue = "default", help = "Session id for history logging") String session,
    @ShellOption(defaultValue = "true", help = "Ask for confirmation before running") boolean confirm,
    @ShellOption(defaultValue = "false", help = "Set when the request originates from the agent") boolean agentRequested
  ) {
    String trimmed = requireNonBlank(command, "command").trim()
    CommandPolicy.Decision decision = commandPolicy.evaluate(trimmed)
    if (!decision.allowed) {
      return decision.message ?: "Command blocked by policy."
    }
    boolean shouldConfirm = (agentRequested || confirm) && !applyAllConfirmed
    if (shouldConfirm) {
      String prompt = agentRequested
        ? "> Agent wants to run: '${trimmed}'. Allow?"
        : "Run command '${trimmed}'?"
      ConfirmChoice choice = confirmAction(prompt)
      if (choice == ConfirmChoice.NO) {
        return "Command canceled."
      }
      if (choice == ConfirmChoice.ALL) {
        applyAllConfirmed = true
      }
    }
    CommandRunner.CommandResult result = commandRunner.run(trimmed, timeoutMillis, maxOutputChars)
    String summary = summarizeOutput(result?.output, 5, Math.min(400, Math.max(1, maxOutputChars)))
    sessionState.appendHistory(
      session,
      "Run command: ${trimmed}",
      "Exit ${result?.timedOut ? 'timeout' : result?.exitCode}; ${summary}"
    )
    formatRunResult(trimmed, result, timeoutMillis, maxOutputChars)
  }

  @ShellMethod(
    key = ["/apply"],
    value = "Apply a unified diff patch with optional dry-run, confirmation, and backups."
  )
  String applyPatch(
    @ShellOption(
      defaultValue = ShellOption.NULL,
      help = "Unified diff patch text; ignored when patch-file is provided"
    ) String patch,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Patch file relative to project root") String patchFile,
    @ShellOption(defaultValue = "true", help = "Preview changes without writing them") boolean dryRun,
    @ShellOption(defaultValue = "true", help = "Ask for confirmation before writing changes") boolean confirm
  ) {
    String body = resolvePatchBody(patch, patchFile)
    String title = dryRun ? "Edit Preview" : "Edit Result"
    if (dryRun) {
      printProgressStart("Edit preview")
      String output = formatPatchResult(fileEditingTool.applyPatch(body, true))
      printProgressDone("Edit preview")
      return formatSection(title, output)
    }
    boolean shouldConfirm = confirm && !applyAllConfirmed
    if (shouldConfirm) {
      printProgressStart("Edit preview")
      FileEditingTool.PatchResult preview = fileEditingTool.applyPatch(body, true)
      String previewText = formatPatchResult(preview)
      if (preview.hasConflicts) {
        printProgressDone("Edit preview")
        return formatSection("Edit Preview", previewText)
      }
      printProgressDone("Edit preview")
      println(previewText)
      ConfirmChoice choice = confirmAction("Apply patch to ${preview.fileResults.size()} file(s)?")
      if (choice == ConfirmChoice.NO) {
        return formatSection("Edit Result", "Patch application canceled.")
      }
      if (choice == ConfirmChoice.ALL) {
        applyAllConfirmed = true
      }
    }
    warnDirtyWorkspace()
    printProgressStart("Edit apply")
    FileEditingTool.PatchResult result = fileEditingTool.applyPatch(body, false)
    printProgressDone("Edit apply")
    formatSection(title, formatPatchResult(result))
  }

  @ShellMethod(
    key = ["/applyBlocks"],
    value = "Apply Search-and-Replace blocks to a file (<<<<SEARCH ... ==== ... >>>>)."
  )
  String applyBlocks(
    @ShellOption(help = "Target file path relative to project root") String filePath,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Blocks text; ignored when blocks-file is set") String blocks,
    @ShellOption(defaultValue = ShellOption.NULL, help = "File containing blocks") String blocksFile,
    @ShellOption(defaultValue = "true", help = "Preview changes without writing") boolean dryRun,
    @ShellOption(defaultValue = "true", help = "Ask for confirmation before writing changes") boolean confirm
  ) {
    requireNonBlank(filePath, "filePath")
    String body = resolvePatchBody(blocks, blocksFile)
    String title = dryRun ? "Edit Preview" : "Edit Result"
    if (dryRun) {
      printProgressStart("Edit preview")
      String output = formatSearchReplaceResult(fileEditingTool.applySearchReplaceBlocks(filePath, body, true))
      printProgressDone("Edit preview")
      return formatSection(title, output)
    }
    boolean shouldConfirm = confirm && !applyAllConfirmed
    if (shouldConfirm) {
      printProgressStart("Edit preview")
      FileEditingTool.SearchReplaceResult preview = fileEditingTool.applySearchReplaceBlocks(filePath, body, true)
      String previewText = formatSearchReplaceResult(preview)
      if (preview.hasConflicts) {
        printProgressDone("Edit preview")
        return formatSection("Edit Preview", previewText)
      }
      printProgressDone("Edit preview")
      println(previewText)
      ConfirmChoice choice = confirmAction("Apply blocks to ${filePath}?")
      if (choice == ConfirmChoice.NO) {
        return formatSection("Edit Result", "Block application canceled.")
      }
      if (choice == ConfirmChoice.ALL) {
        applyAllConfirmed = true
      }
    }
    warnDirtyWorkspace()
    printProgressStart("Edit apply")
    FileEditingTool.SearchReplaceResult result = fileEditingTool.applySearchReplaceBlocks(filePath, body, false)
    printProgressDone("Edit apply")
    formatSection(title, formatSearchReplaceResult(result))
  }

  @ShellMethod(
    key = ["/revert"],
    value = "Restore a file using the most recent patch backup."
  )
  String revert(
    @ShellOption(help = "File path relative to project root") String filePath,
    @ShellOption(defaultValue = "false", help = "Preview the restore without writing") boolean dryRun
  ) {
    revert(filePath, dryRun, true)
  }

  String revert(String filePath, boolean dryRun, boolean confirm) {
    requireNonBlank(filePath, "filePath")
    if (!dryRun && !confirm) {
      return "Revert canceled: confirmation required."
    }
    printProgressStart("Edit revert")
    String output = formatEditResult(fileEditingTool.revertLatestBackup(filePath, dryRun))
    printProgressDone("Edit revert")
    formatSection("Edit Result", output)
  }

  @ShellMethod(
    key = ["/context"],
    value = "Show a snippet for targeted edits by line range or symbol."
  )
  String context(
    @ShellOption(help = "File path relative to project root") String filePath,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Start line (1-based) when using ranges") Integer start,
    @ShellOption(defaultValue = ShellOption.NULL, help = "End line (1-based) when using ranges") Integer end,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Symbol to locate instead of line numbers") String symbol,
    @ShellOption(defaultValue = "2", help = "Padding lines around the selection") int padding
  ) {
    requireNonBlank(filePath, "filePath")
    requireMin(padding, 0, "padding")
    FileEditingTool.TargetedEditContext ctx
    if (symbol != null && symbol.trim()) {
      ctx = fileEditingTool.contextBySymbol(filePath, symbol, padding)
    } else {
      if (start == null || end == null) {
        throw new IllegalArgumentException("Provide start and end when symbol is not set.")
      }
      ctx = fileEditingTool.contextByRange(filePath, start, end, padding)
    }
    "Context ${ctx.filePath}:${ctx.startLine}-${ctx.endLine} of ${ctx.totalLines}\n${ctx.snippet}"
  }

  @ShellMethod(
    key = ["/tree"],
    value = "Show repository tree (respects .gitignore when available)."
  )
  String tree(
    @ShellOption(defaultValue = "4", help = "Max depth (-1 for unlimited)") int depth,
    @ShellOption(defaultValue = "false", help = "Show directories only") boolean dirsOnly,
    @ShellOption(defaultValue = "2000", help = "Maximum entries to render (0 for unlimited)") int maxEntries
  ) {
    requireMin(depth, -1, "depth")
    requireMin(maxEntries, 0, "maxEntries")
    printProgressStart("Repository tree")
    TreeTool.TreeResult result = treeTool.buildTree(depth, dirsOnly, maxEntries)
    printProgressDone("Repository tree")
    if (!result.repoPresent) {
      return "Not a git repository."
    }
    if (!result.success) {
      return result.message ?: "Unable to build repository tree."
    }
    String body = result.treeText ?: ""
    if (result.truncated) {
      body = body + "\n... (truncated; increase --max-entries or --depth)"
    }
    formatSection("Repository Tree", body)
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

  private String buildReviewPayload(String code, List<String> paths, boolean staged) {
    StringBuilder builder = new StringBuilder()
    if (code != null && code.trim()) {
      builder.append("User-provided code:\n```\n").append(code.trim()).append("\n```\n\n")
    }
    if (paths != null) {
      paths.each { String path ->
        if (path == null || path.trim().isEmpty()) {
          return
        }
        try {
          String content = fileEditingTool.readFile(path.trim())
          builder.append("File: ").append(path.trim()).append("\n```\n")
            .append(content).append("\n```\n\n")
        } catch (IllegalArgumentException ex) {
          builder.append("File: ").append(path.trim()).append(" (error: ").append(ex.message).append(")\n\n")
        }
      }
    }
    if (staged) {
      String diff = stagedDiff()
      if (diff) {
        builder.append("Staged diff:\n```\n").append(diff).append("\n```\n")
      }
    }
    String payload = builder.toString().trim()
    payload ? payload : "No additional context provided."
  }

  private String stagedDiff() {
    GitTool.GitResult diff = gitTool.stagedDiff()
    if (!diff.repoPresent || !diff.success) {
      return ""
    }
    diff.output ?: ""
  }

  static String renderReview(ReviewSummary summary, ReviewSeverity minSeverity, boolean colorize) {
    StringBuilder builder = new StringBuilder("Findings:")
    List<ReviewFinding> filtered = summary.findings.findAll { it.severity.ordinal() <= minSeverity.ordinal() }
    if (filtered.isEmpty()) {
      builder.append("\n- None")
    } else {
      List<ReviewFinding> sorted = new ArrayList<>(filtered)
      sorted.sort { ReviewFinding a, ReviewFinding b -> b.severity.ordinal() <=> a.severity.ordinal() }
      sorted.each { ReviewFinding finding ->
        String location = finding.file ?: "general"
        if (finding.line != null) {
          location += ":${finding.line}"
        }
        String severity = capitalize(finding.severity.name().toLowerCase())
        builder.append("\n- [").append(colorize ? colorSeverity(severity) : severity)
          .append("] ").append(location).append(" - ").append(finding.comment)
      }
    }
    builder.append("\nTests:")
    if (summary.tests.isEmpty()) {
      builder.append("\n- None provided")
    } else {
      summary.tests.each { builder.append("\n- ").append(it) }
    }
    builder.toString().stripTrailing()
  }

  private static String capitalize(String value) {
    if (value == null || value.isEmpty()) {
      return value
    }
    if (value.length() == 1) {
      return value.toUpperCase()
    }
    return value.substring(0, 1).toUpperCase() + value.substring(1)
  }

  private static String colorSeverity(String severity) {
    switch (severity.toUpperCase()) {
      case "HIGH":
        return "\u001B[31m${severity}\u001B[0m"
      case "MEDIUM":
        return "\u001B[33m${severity}\u001B[0m"
      default:
        return severity
    }
  }

  private List<LogEntry> loadLogEntries(String pathFilter, ReviewSeverity minSeverity, Instant since) {
    String content = Files.readString(reviewLogPath)
    if (!content.trim()) {
      return List.of()
    }
    List<LogEntry> entries = new ArrayList<>()
    content.split("=== Review ===").each { String raw ->
      String trimmed = raw.trim()
      if (!trimmed) {
        return
      }
      String prompt = extractField(trimmed, "Prompt:")
      String paths = extractField(trimmed, "Paths:")
      String staged = extractField(trimmed, "Staged:")
      String timestamp = extractField(trimmed, "Timestamp:")
      String body = extractBody(trimmed)
      ReviewSummary summary = ReviewParser.parse(body)
      List<ReviewFinding> filtered = summary.findings.findAll { it.severity.ordinal() <= minSeverity.ordinal() }
      if (filtered.isEmpty()) {
        return
      }
      Instant parsedTs = null
      if (timestamp) {
        try {
          parsedTs = OffsetDateTime.parse(timestamp).toInstant()
        } catch (DateTimeParseException ignored) {
          parsedTs = null
        }
      }
      if (since != null && parsedTs != null && parsedTs.isBefore(since)) {
        return
      }
      if (pathFilter != null && pathFilter.trim()) {
        String filter = pathFilter.trim()
        if (paths == null || !paths.contains(filter)) {
          return
        }
      }
      entries.add(new LogEntry(prompt, paths, Boolean.parseBoolean(staged), parsedTs, new ReviewSummary(filtered, summary.tests, summary.raw)))
    }
    entries.sort { LogEntry a, LogEntry b ->
      if (a.timestamp == null && b.timestamp == null) return 0
      if (a.timestamp == null) return 1
      if (b.timestamp == null) return -1
      return b.timestamp <=> a.timestamp
    }
    entries
  }

  private static String extractField(String entry, String label) {
    entry.readLines().find { it.startsWith(label) }?.substring(label.length())?.trim()
  }

  private static String extractBody(String entry) {
    int idx = entry.indexOf("---")
    if (idx < 0) {
      return entry
    }
    int second = entry.indexOf("---", idx + 3)
    if (second < 0) {
      return entry.substring(idx + 3).trim()
    }
    entry.substring(idx + 3, second).trim()
  }

  private static Instant parseInstant(String value) {
    if (value == null || value.trim().isEmpty()) {
      return null
    }
    try {
      return OffsetDateTime.parse(value.trim()).toInstant()
    } catch (DateTimeParseException ignored) {
      throw new IllegalArgumentException("Invalid timestamp format; use ISO-8601 like 2025-02-12T10:00:00Z")
    }
  }

  private static String requireNonBlank(String value, String field) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException("${field} must not be blank")
    }
    value
  }

  private static void requireMin(long value, long min, String field) {
    if (value < min) {
      throw new IllegalArgumentException("${field} must be >= ${min}")
    }
  }

  @CompileStatic
  protected Instant nowInstant() {
    Instant.now()
  }

  @Canonical
  @CompileStatic
  private static class LogEntry {
    String prompt
    String paths
    boolean staged
    Instant timestamp
    ReviewSummary summary
  }

  private void writeReviewLog(
    String prompt,
    ReviewSummary summary,
    List<String> paths,
    boolean staged,
    ReviewSeverity minSeverity
  ) {
    try {
      Path parent = reviewLogPath.getParent()
      if (parent != null) {
        Files.createDirectories(parent)
      }
      String sanitizedPrompt = LogSanitizer.sanitize(prompt)
      List<ReviewFinding> filtered = summary.findings.findAll { it.severity.ordinal() <= minSeverity.ordinal() }
      String rendered = LogSanitizer.sanitize(renderReview(summary, minSeverity, false))
      String entry = """
=== Review ===
Prompt: ${sanitizedPrompt}
Paths: ${paths ? paths.join(", ") : "none"}
Staged: ${staged}
Timestamp: ${nowInstant()}
Findings: ${filtered.size()} (min ${minSeverity})
Tests: ${summary.tests.size()}
---
${rendered}
---
"""
      Files.writeString(
        reviewLogPath,
        entry,
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND
      )
    } catch (IOException e) {
      log.warn("Failed to write review log to {}", reviewLogPath, e)
    }
  }

  private String resolvePatchBody(String patch, String patchFile) {
    if (patchFile != null && patchFile.trim()) {
      Path candidate = fileEditingTool.projectRoot.resolve(patchFile).normalize()
      try {
        Path realRoot = fileEditingTool.projectRoot.toRealPath()
        Path realCandidate = candidate.toRealPath()
        if (!realCandidate.startsWith(realRoot)) {
          throw new IllegalArgumentException("Patch file must be inside the project root")
        }
        return Files.readString(realCandidate)
      } catch (IOException e) {
        throw new IllegalArgumentException("Patch file not found or unreadable: $patchFile", e)
      }
    }
    if (patch == null || patch.trim().isEmpty()) {
      throw new IllegalArgumentException("Provide either patch text or a patch file path")
    }
    patch
  }

  private static String formatPatchResult(FileEditingTool.PatchResult result) {
    if (result == null) {
      return "No patch result."
    }
    StringBuilder builder = new StringBuilder()
    builder.append(result.dryRun ? "Dry run" : "Patch apply")
    builder.append(result.hasConflicts ? " (conflicts detected)" : " completed")
    if (!result.messages.isEmpty()) {
      builder.append("\n").append(String.join("\n", result.messages))
    }
    result.fileResults.each { FileEditingTool.FilePatchResult file ->
      builder.append("\n- ").append(file.filePath).append(": ")
      if (file.conflicted) {
        builder.append("CONFLICT ")
      }
      builder.append(file.message)
      if (file.backupPath) {
        builder.append(" [backup: ").append(file.backupPath).append("]")
      }
      if (file.preview && file.dryRun && !file.conflicted) {
        builder.append("\n  preview: ").append(file.preview)
      }
    }
    builder.toString().stripTrailing()
  }

  private static String formatEditResult(FileEditingTool.EditResult result) {
    StringBuilder builder = new StringBuilder(result.message)
    if (result.backupPath) {
      builder.append(" (backup: ").append(result.backupPath).append(")")
    }
    builder.toString()
  }

  private static String formatSearchReplaceResult(FileEditingTool.SearchReplaceResult result) {
    if (result == null) {
      return "No block result."
    }
    StringBuilder builder = new StringBuilder()
    builder.append(result.dryRun ? "Dry run" : "Applied blocks")
    builder.append(result.hasConflicts ? " (conflicts detected)" : "")
    result.blocks.each { FileEditingTool.BlockResult block ->
      builder.append("\n- block ").append(block.index).append(": ").append(block.message)
    }
    result.messages.each { builder.append("\n").append(it) }
    if (result.backupPath) {
      builder.append("\nBackup: ").append(result.backupPath)
    }
    builder.toString().stripTrailing()
  }

  private String formatGitResult(String label, GitTool.GitResult result) {
    if (result == null) {
      return "${label} returned no result."
    }
    if (!result.repoPresent) {
      return "Not a git repository."
    }
    StringBuilder builder = new StringBuilder(label)
    builder.append(result.success ? " succeeded" : " failed (exit ${result.exitCode})")
    if (result.output != null && result.output.trim()) {
      builder.append("\n").append(result.output.trim())
    }
    if (result.error != null && result.error.trim()) {
      builder.append("\n").append(result.error.trim())
    }
    builder.toString().stripTrailing()
  }

  private static String formatSection(String title, String body) {
    String content = body ?: ""
    "=== ${title} ===\n${content}".stripTrailing()
  }

  private void recordWebSearchSummary(String sessionId, String query, List<WebSearchTool.SearchResult> results) {
    String summary = WebSearchTool.summariseResults(query, results, WEB_SEARCH_SUMMARY_LIMIT, WEB_SEARCH_SUMMARY_MAX_CHARS)
    SessionState.ToolSummary toolSummary = new SessionState.ToolSummary("web-search", summary, Instant.now())
    sessionState.storeToolSummary(sessionId, toolSummary)
  }

  private static String formatFetcherLabel(String value) {
    if (value == null) {
      return "disabled"
    }
    String trimmed = value.trim()
    if (!trimmed || trimmed.equalsIgnoreCase("none")) {
      return "disabled"
    }
    trimmed.toLowerCase(Locale.ROOT)
  }

  private void applyWebSearchMode(String sessionId, String mode) {
    String trimmed = mode != null ? mode.trim() : ""
    if (!trimmed) {
      return
    }
    String normalised = trimmed.toLowerCase(Locale.UK)
    if (WEB_SEARCH_DISABLED_VALUES.contains(normalised)) {
      sessionState.setWebSearchEnabledOverride(sessionId, false)
      sessionState.setWebSearchFetcherOverride(sessionId, "none")
      sessionState.setWebSearchFallbackFetcherOverride(sessionId, "none")
      return
    }
    if (WEB_SEARCH_DEFAULT_VALUES.contains(normalised)) {
      sessionState.setWebSearchEnabledOverride(sessionId, null)
      sessionState.setWebSearchFetcherOverride(sessionId, "default")
      sessionState.setWebSearchFallbackFetcherOverride(sessionId, "default")
      return
    }
    String primary = normaliseWebSearchFetcher(normalised)
    if (primary == null) {
      throw new IllegalArgumentException(
        "Unknown web-search mode '${mode}'. Use htmlunit, jsoup, disabled, or default."
      )
    }
    String fallback = primary == "htmlunit" ? "jsoup" : "htmlunit"
    sessionState.setWebSearchEnabledOverride(sessionId, true)
    sessionState.setWebSearchFetcherOverride(sessionId, primary)
    sessionState.setWebSearchFallbackFetcherOverride(sessionId, fallback)
  }

  private static String normaliseWebSearchFetcher(String value) {
    if (value == null) {
      return null
    }
    String trimmed = value.trim()
    if (!trimmed) {
      return null
    }
    String normalised = trimmed.toLowerCase(Locale.UK)
    if (normalised == "jdoup") {
      normalised = "jsoup"
    }
    if (normalised == "htmlunit" || normalised == "jsoup") {
      return normalised
    }
    null
  }

  private String formatWebSearchState(String sessionId) {
    if (!sessionState.isWebSearchDesired(sessionId)) {
      return "disabled"
    }
    String primary = formatFetcherLabel(sessionState.getWebSearchFetcher(sessionId))
    if (primary == "disabled") {
      return "disabled"
    }
    String fallback = formatFetcherLabel(sessionState.getWebSearchFallbackFetcher(sessionId))
    if (fallback == "disabled") {
      return "${primary} (fallback disabled)"
    }
    "${primary} (fallback ${fallback})"
  }

  private void applyIntentMode(String mode) {
    if (intentRoutingState == null) {
      return
    }
    String trimmed = mode != null ? mode.trim() : ""
    if (!trimmed) {
      return
    }
    String normalised = trimmed.toLowerCase(Locale.UK)
    if (INTENT_ON_VALUES.contains(normalised)) {
      intentRoutingState.setEnabledOverride(true)
      return
    }
    if (INTENT_OFF_VALUES.contains(normalised)) {
      intentRoutingState.setEnabledOverride(false)
      return
    }
    if (INTENT_DEFAULT_VALUES.contains(normalised)) {
      intentRoutingState.clearEnabledOverride()
      return
    }
    throw new IllegalArgumentException("Unknown intent mode '${mode}'. Use enabled, disabled, or default.")
  }

  private String formatIntentState() {
    if (intentRoutingState == null) {
      return "unavailable"
    }
    intentRoutingState.isEnabled(intentRoutingSettings) ? "enabled" : "disabled"
  }

  private String buildPlanPrompt(String sessionId, String prompt) {
    String base = prompt != null ? prompt.trim() : ""
    SessionState.ToolSummary summary = sessionState.getRecentToolSummary(sessionId)
    if (summary == null || summary.summary == null || summary.summary.trim().isEmpty()) {
      return base
    }
    String summaryText = summary.summary.trim()
    if (base.contains(summaryText)) {
      return base
    }
    StringBuilder builder = new StringBuilder()
    if (base) {
      builder.append(base).append("\n\n")
    }
    builder.append("Recent investigation context:\n").append(summaryText)
    builder.toString().stripTrailing()
  }

  private String buildPlanSystemPrompt(String baseSystemPrompt) {
    StringBuilder builder = new StringBuilder()
    if (baseSystemPrompt != null && baseSystemPrompt.trim()) {
      builder.append(baseSystemPrompt.trim()).append("\n\n")
    }
    builder.append("You are a planning assistant for the local coding CLI.\n")
    builder.append("Available commands: ").append(PLAN_COMMANDS.join(", ")).append(".\n")
    builder.append("Propose a sequence of steps that matches the user's request.\n")
    builder.append("Do not execute any commands.")
    builder.toString()
  }

  private String buildSastBlock(boolean enabled, List<String> paths) {
    if (!enabled) {
      return ""
    }
    SastTool.SastResult result = sastTool.run(paths)
    if (!result.ran) {
      return "\n\nSAST:\n- " + (result.message ?: "SAST not run.")
    }
    if (result.findings == null || result.findings.isEmpty()) {
      return "\n\nSAST:\n- No findings"
    }
    String details = result.findings.collect { SastTool.SastFinding finding ->
      String location = finding.path ?: "unknown"
      if (finding.line != null) {
        location += ":" + finding.line
      }
      String sanitizedLocation = LogSanitizer.sanitize(location)
      String sanitizedRule = LogSanitizer.sanitize(finding.rule ?: "rule")
      String sanitizedSeverity = LogSanitizer.sanitize(finding.severity ?: "UNKNOWN")
      "- [${sanitizedSeverity}] ${sanitizedLocation} - ${sanitizedRule}"
    }.join("\n")
    "\n\nSAST:\n" + details
  }

  private static void printProgressStart(String label) {
    println("${label}...")
  }

  private static void printProgressDone(String label) {
    println("${label} done.")
  }

  private static String formatRunResult(
    String command,
    CommandRunner.CommandResult result,
    long timeoutMillis,
    int maxOutputChars
  ) {
    if (result == null) {
      return "No command result."
    }
    StringBuilder builder = new StringBuilder()
    builder.append("Command: ").append(command)
    builder.append("\nExit: ")
    if (result.timedOut) {
      builder.append("timed out after ").append(timeoutMillis).append(" ms")
    } else {
      builder.append(result.exitCode)
    }
    builder.append(result.success ? " (success)" : " (failed)")
    if (result.truncated) {
      builder.append("\nOutput truncated to ").append(maxOutputChars).append(" characters.")
    }
    if (result.output != null && result.output.trim()) {
      builder.append("\nOutput:\n").append(result.output.trim())
    }
    if (result.logPath != null) {
      builder.append("\nLog: ").append(result.logPath)
    }
    builder.toString().stripTrailing()
  }

  private static String formatDirectShellResult(String command, CommandRunner.CommandResult result) {
    if (result == null) {
      return "No command result."
    }
    StringBuilder builder = new StringBuilder()
    builder.append("Command: ").append(command)
    builder.append("\nExit: ")
    if (result.timedOut) {
      builder.append("timed out after ").append(DIRECT_SHELL_TIMEOUT_MILLIS).append(" ms")
    } else {
      builder.append(result.exitCode)
    }
    builder.append(result.success ? " (success)" : " (failed)")
    if (result.truncated) {
      builder.append("\nOutput truncated to ").append(DIRECT_SHELL_MAX_OUTPUT_CHARS).append(" characters.")
    }
    builder.append("\nOutput: streamed to console.")
    if (result.logPath != null) {
      builder.append("\nLog: ").append(result.logPath)
    }
    builder.toString().stripTrailing()
  }

  private static String summarizeOutput(String output, int maxLines, int maxChars) {
    if (output == null || output.trim().isEmpty()) {
      return "no output"
    }
    String trimmed = output.stripTrailing()
    String[] lines = trimmed.split("\\R")
    int linesToTake = Math.min(Math.max(1, maxLines), lines.length)
    String summary = String.join("\n", lines[0..<linesToTake])
    if (summary.length() > maxChars) {
      summary = summary.substring(0, Math.max(1, maxChars))
    }
    boolean shortened = lines.length > linesToTake || trimmed.length() > summary.length()
    shortened ? "${summary} ..." : summary
  }

  private void appendShellCommandToConversation(
    String sessionId,
    String command,
    CommandRunner.CommandResult result
  ) {
    String sessionKey = sessionId ?: DEFAULT_SESSION
    def conversation = sessionState.getOrCreateConversation(sessionKey)
    StringBuilder builder = new StringBuilder()
    builder.append("Shell command executed:\n")
    builder.append('$ ').append(command)
    builder.append("\nExit: ")
    if (result == null) {
      builder.append("unknown")
      builder.append("\nOutput: no output")
      conversation.addMessage(new UserMessage(builder.toString()))
      return
    }
    if (result.timedOut) {
      builder.append("timed out after ").append(DIRECT_SHELL_TIMEOUT_MILLIS).append(" ms")
    } else {
      builder.append(result.exitCode)
    }
    builder.append(result.success ? " (success)" : " (failed)")
    if (result.truncated) {
      builder.append("\nOutput truncated to ").append(DIRECT_SHELL_MAX_OUTPUT_CHARS).append(" characters.")
    }
    String outputSummary = summarizeOutput(result.output, 20, DIRECT_SHELL_CONVERSATION_MAX_CHARS)
    builder.append("\nOutput:\n").append(outputSummary)
    conversation.addMessage(new UserMessage(builder.toString()))
  }

  private Agent resolveAgent(String name) {
    if (agentPlatform == null) {
      return null
    }
    List<Agent> agents = agentPlatform.agents()
    if (agents == null || agents.isEmpty()) {
      return null
    }
    agents.find { Agent agent -> agent?.name == name }
  }

  private <T> T runAgent(Agent agent, String sessionId, Class<T> resultType, Object... inputs) {
    ProcessOptions options = ProcessOptions.DEFAULT
    if (sessionId != null && sessionId.trim()) {
      ensureContextExists(sessionId)
      options = options.withContextId(sessionId)
    }
    AgentProcess process = agentPlatform.createAgentProcessFrom(agent, options, inputs)
    process.run()
    process.resultOfType(resultType)
  }

  private void ensureContextExists(String sessionId) {
    if (contextRepository == null) {
      return
    }
    String contextId = sessionId != null ? sessionId.trim() : ""
    if (!contextId) {
      return
    }
    try {
      def existing = contextRepository.findById(contextId)
      if (existing == null) {
        contextRepository.save(new SimpleContext(contextId))
      }
    } catch (Exception e) {
      log.debug("Failed to ensure context ${contextId}: ${e.message}", e)
    }
  }

  private String ensureOllamaHealth() {
    ModelRegistry.Health health = modelRegistry.checkHealth()
    if (!health.reachable) {
      return "Ollama unreachable at ${modelRegistry.getBaseUrl()}: ${health.message}"
    }
    null
  }

  protected String resolveVersion() {
    String version = readBuildInfoVersion()
    if (version != null && version.trim()) {
      return version.trim()
    }
    version = getClass().getPackage()?.getImplementationVersion()
    if (version != null && version.trim()) {
      return version.trim()
    }
    String pomVersion = readPomVersion()
    pomVersion != null && pomVersion.trim() ? pomVersion.trim() : "unknown"
  }

  protected String resolveEmbabelVersion() {
    List<String> candidates = [
      "com.embabel.agent:embabel-agent-api",
      "com.embabel.agent:embabel-agent-platform-autoconfigure",
      "com.embabel.agent:embabel-agent-starter"
    ]
    String version = resolveLibraryVersion(candidates)
    version != null && version.trim() ? version.trim() : "unknown"
  }

  protected String resolveSpringBootVersion() {
    String version = SpringBootVersion.getVersion()
    if (version != null && version.trim()) {
      return version.trim()
    }
    String fromPom = resolveLibraryVersion(["org.springframework.boot:spring-boot"])
    fromPom != null && fromPom.trim() ? fromPom.trim() : "unknown"
  }

  protected List<String> resolveModelVersionInfo() {
    String model = sessionState.getDefaultModel()
    String fallback = sessionState.getFallbackModel()
    if (model == null && fallback == null) {
      return List.of()
    }
    List<String> lines = new ArrayList<String>()
    if (fallback != null && fallback.trim()) {
      lines.add("Models: ${model ?: "unknown"} (fallback: ${fallback})".toString())
    } else {
      lines.add("Models: ${model ?: "unknown"}".toString())
    }
    lines
  }

  private String resolveLibraryVersion(List<String> coordinates) {
    for (String coordinate : coordinates) {
      String resolved = resolveLibraryVersion(coordinate)
      if (resolved != null) {
        return resolved
      }
    }
    null
  }

  private String resolveLibraryVersion(String coordinate) {
    if (coordinate == null || !coordinate.contains(":")) {
      return null
    }
    String[] parts = coordinate.split(":", 2)
    if (parts.length != 2) {
      return null
    }
    readPomVersion(parts[0], parts[1])
  }

  private String readBuildInfoVersion() {
    String path = "META-INF/build-info.properties"
    InputStream stream = ShellCommands.classLoader.getResourceAsStream(path)
    if (stream == null) {
      return null
    }
    Properties props = new Properties()
    try {
      props.load(stream)
    } catch (IOException e) {
      log.debug("Failed to read build-info.properties for version", e)
      return null
    } finally {
      try {
        stream.close()
      } catch (IOException ignored) {
        // Ignore close errors for version lookup.
      }
    }
    props.getProperty("build.version")
  }

  private String readPomVersion() {
    readPomVersion("se.alipsa.lca", "local-coding-assistant")
  }

  private String readPomVersion(String groupId, String artifactId) {
    if (groupId == null || artifactId == null) {
      return null
    }
    String path = "META-INF/maven/${groupId}/${artifactId}/pom.properties"
    InputStream stream = ShellCommands.classLoader.getResourceAsStream(path)
    if (stream == null) {
      return null
    }
    Properties props = new Properties()
    try {
      props.load(stream)
    } catch (IOException e) {
      log.debug("Failed to read pom.properties for version", e)
      return null
    } finally {
      try {
        stream.close()
      } catch (IOException ignored) {
        // Ignore close errors for version lookup.
      }
    }
    props.getProperty("version")
  }

  protected ModelResolution resolveModel(String requested) {
    resolveModel(requested, modelRegistry.listModels())
  }

  protected ModelResolution resolveModel(String requested, List<String> available) {
    String desired = requested != null ? requested : sessionState.getDefaultModel()
    boolean canCheck = available != null && !available.isEmpty()
    String matchedDesired = (canCheck && desired != null) ? available.find { it != null && it.equalsIgnoreCase(desired) } : desired
    boolean desiredOk = !canCheck || matchedDesired != null
    if (desiredOk) {
      return new ModelResolution(matchedDesired ?: desired, false, requested ?: desired)
    }
    String fallback = sessionState.getFallbackModel()
    String matchedFallback = (fallback != null && canCheck) ? available.find { it.equalsIgnoreCase(fallback) } : fallback
    boolean fallbackOk = fallback != null && (!canCheck || matchedFallback != null)
    if (fallbackOk) {
      return new ModelResolution(matchedFallback ?: fallback, true, desired)
    }
    new ModelResolution(desired, false, desired)
  }

  private static List<Integer> parseHunkIndexes(String hunks) {
    if (hunks == null || !hunks.trim()) {
      return List.of()
    }
    List<Integer> indexes = new ArrayList<>()
    hunks.split(",").each { String raw ->
      String value = raw.trim()
      if (!value) {
        return
      }
      try {
        int parsed = Integer.parseInt(value)
        if (parsed <= 0) {
          throw new IllegalArgumentException("Hunk indexes must be 1-based; got ${parsed}")
        }
        indexes.add(parsed)
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Invalid hunk index: ${value}", e)
      }
    }
    if (indexes.isEmpty()) {
      throw new IllegalArgumentException("No valid hunk indexes parsed from '${hunks}'.")
    }
    indexes
  }

  private static String buildCommitPrompt(String diff, String hint, String systemPrompt, String secretNote) {
    StringBuilder builder = new StringBuilder()
    builder.append(
      "You are crafting a git commit message for staged changes in the current project.\n"
    )
    builder.append(
      "Write a concise, imperative subject line (<= 72 characters) and optional bullet body.\n"
    )
    builder.append("Use American English and mention tests if added or missing.\n")
    if (systemPrompt != null && systemPrompt.trim()) {
      builder.append("System guidance: ").append(systemPrompt.trim()).append("\n")
    }
    if (secretNote != null && secretNote.trim()) {
      builder.append("Security note: ").append(secretNote.trim()).append("\n")
    }
    if (hint != null && hint.trim()) {
      builder.append("User notes: ").append(hint.trim()).append("\n")
    }
    builder.append("\nStaged diff:\n").append(diff)
    builder.append("\n\nRespond with:\nSubject: <one line>\nBody:\n- <bullet lines or 'none'>")
    builder.toString()
  }

  private void warnDirtyWorkspace() {
    if (!gitTool.isGitRepo()) {
      return
    }
    if (gitTool.isDirty()) {
      // Direct stdout to ensure the user sees the safety notice even when logs are redirected.
      println("Warning: Uncommitted changes detected. Consider committing before applying patches.")
    }
  }

  private static Path resolveProjectRoot(FileEditingTool fileEditingTool) {
    Path root = fileEditingTool != null ? fileEditingTool.getProjectRoot() : null
    root != null ? root : Paths.get(".").toAbsolutePath().normalize()
  }

  @CompileStatic
  protected ConfirmChoice confirmAction(String prompt) {
    if (batchMode) {
      if (assumeYes) {
        return ConfirmChoice.ALL
      }
      throw new IllegalStateException(
        "Confirmation required in batch mode. Re-run with --yes to auto-confirm."
      )
    }
    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))
    print("${prompt.trim()} [y/N/a]: ")
    String response = reader.readLine()
    String normalized = response != null ? response.trim().toLowerCase() : ""
    if ("a" == normalized) {
      return ConfirmChoice.ALL
    }
    if ("y" == normalized) {
      return ConfirmChoice.YES
    }
    ConfirmChoice.NO
  }

  private boolean promptDisableLocalOnly(String sessionId) {
    if (batchMode) {
      return false
    }
    print("Web search is disabled in local-only mode. Would you like to temporarily disable local-only mode for this session? (y/n): ")
    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))
    String response = reader.readLine()
    String normalised = response != null ? response.trim().toLowerCase(Locale.UK) : ""
    if (normalised == "y" || normalised == "yes") {
      sessionState.setLocalOnlyOverride(sessionId, false)
      println("Local-only mode disabled for this session, searching the web...")
      return true
    }
    false
  }

  @PackageScope
  void configureBatchMode(boolean enabled, boolean assumeYes) {
    this.batchMode = enabled
    this.assumeYes = assumeYes
    if (assumeYes) {
      this.applyAllConfirmed = true
    }
  }

  @CompileStatic
  protected static enum ConfirmChoice {
    YES,
    NO,
    ALL
  }

  @Canonical
  @CompileStatic
  protected static class ModelResolution {
    String chosen
    boolean fallbackUsed
    String requested
  }
}
