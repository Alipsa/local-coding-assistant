package se.alipsa.lca.shell

import com.embabel.agent.api.common.Ai
import com.embabel.agent.domain.io.UserInput
import com.embabel.common.ai.model.LlmOptions
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.shell.standard.ShellComponent
import org.springframework.shell.standard.ShellMethod
import org.springframework.shell.standard.ShellOption
import se.alipsa.lca.agent.CodingAssistantAgent
import se.alipsa.lca.agent.CodingAssistantAgent.CodeSnippet
import se.alipsa.lca.agent.PersonaMode
import se.alipsa.lca.review.ReviewFinding
import se.alipsa.lca.review.ReviewParser
import se.alipsa.lca.review.ReviewSeverity
import se.alipsa.lca.review.ReviewSummary
import se.alipsa.lca.shell.SessionState.SessionSettings
import se.alipsa.lca.tools.CodeSearchTool
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.GitTool
import se.alipsa.lca.tools.ContextPacker
import se.alipsa.lca.tools.ContextBudgetManager
import se.alipsa.lca.tools.PackedContext
import se.alipsa.lca.tools.CommandRunner
import se.alipsa.lca.tools.TokenEstimator
import se.alipsa.lca.tools.WebSearchTool
import se.alipsa.lca.tools.ModelRegistry

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@ShellComponent
@CompileStatic
class ShellCommands {

  private static final Logger log = LoggerFactory.getLogger(ShellCommands)
  private final CodingAssistantAgent codingAssistantAgent
  private final Ai ai
  private final SessionState sessionState
  private final EditorLauncher editorLauncher
  private final FileEditingTool fileEditingTool
  private final GitTool gitTool
  private final CodeSearchTool codeSearchTool
  private final ContextPacker contextPacker
  private final ContextBudgetManager contextBudgetManager
  private final CommandRunner commandRunner
  private final ModelRegistry modelRegistry
  private final Path reviewLogPath
  private volatile boolean applyAllConfirmed = false

  ShellCommands(
    CodingAssistantAgent codingAssistantAgent,
    Ai ai,
    SessionState sessionState,
    EditorLauncher editorLauncher,
    FileEditingTool fileEditingTool,
    CodeSearchTool codeSearchTool,
    ContextPacker contextPacker,
    ContextBudgetManager contextBudgetManager,
    CommandRunner commandRunner,
    ModelRegistry modelRegistry,
    String reviewLogPath
  ) {
    this(
      codingAssistantAgent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      new GitTool(resolveProjectRoot(fileEditingTool)),
      codeSearchTool,
      contextPacker,
      contextBudgetManager,
      commandRunner != null ? commandRunner : new CommandRunner(resolveProjectRoot(fileEditingTool)),
      modelRegistry != null ? modelRegistry : new ModelRegistry(),
      reviewLogPath
    )
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
    ModelRegistry modelRegistry,
    @Value('${review.log.path:.lca/reviews.log}') String reviewLogPath
  ) {
    this.codingAssistantAgent = codingAssistantAgent
    this.ai = ai
    this.sessionState = sessionState
    this.editorLauncher = editorLauncher
    this.fileEditingTool = fileEditingTool
    this.gitTool = gitTool
    this.codeSearchTool = codeSearchTool
    this.contextPacker = contextPacker
    this.contextBudgetManager = contextBudgetManager
    this.commandRunner = commandRunner != null ? commandRunner : new CommandRunner(resolveProjectRoot(fileEditingTool))
    this.modelRegistry = modelRegistry != null ? modelRegistry : new ModelRegistry()
    this.reviewLogPath = Paths.get(reviewLogPath).toAbsolutePath()
  }

  ShellCommands(
    CodingAssistantAgent codingAssistantAgent,
    Ai ai,
    SessionState sessionState,
    EditorLauncher editorLauncher,
    FileEditingTool fileEditingTool,
    GitTool gitTool,
    CommandRunner commandRunner,
    ModelRegistry modelRegistry,
    String reviewLogPath
  ) {
    this(
      codingAssistantAgent,
      ai,
      sessionState,
      editorLauncher,
      fileEditingTool,
      gitTool,
      new CodeSearchTool(),
      new ContextPacker(),
      new ContextBudgetManager(12000, 0, new TokenEstimator(), 2, -1),
      commandRunner != null ? commandRunner : new CommandRunner(resolveProjectRoot(fileEditingTool)),
      modelRegistry != null ? modelRegistry : new ModelRegistry(),
      reviewLogPath
    )
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
    CodeSnippet snippet = codingAssistantAgent.craftCode(
      new UserInput(prompt),
      ai,
      persona,
      options,
      system
    )
    if (snippet == null) {
      return "No response generated."
    }
    sessionState.appendHistory(session, "User: ${prompt}", "Assistant: ${snippet.text}")
    if (fallbackNote != null) {
      return fallbackNote + "\n" + snippet.text
    }
    snippet.text
  }

  @ShellMethod(key = ["review", "/review"], value = "Ask the assistant to review code.")
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
    @ShellOption(defaultValue = "true", help = "Persist review summary to log file") boolean logReview
  ) {
    ReviewSeverity severityThreshold = minSeverity ?: ReviewSeverity.LOW
    String health = ensureOllamaHealth()
    if (health != null) {
      return health
    }
    ModelResolution resolution = resolveModel(model)
    SessionSettings settings = sessionState.update(session, resolution.chosen, null, reviewTemperature, maxTokens, systemPrompt, null)
    LlmOptions reviewOptions = sessionState.reviewOptions(settings)
    String system = sessionState.systemPrompt(settings)
    String reviewPayload = buildReviewPayload(code, paths, staged)
    def result = codingAssistantAgent.reviewCode(
      new UserInput(prompt),
      new CodeSnippet(reviewPayload),
      ai,
      reviewOptions,
      system
    )
    ReviewSummary summary = ReviewParser.parse(result.review)
    String rendered = renderReview(summary, severityThreshold, !noColor)
    if (resolution.fallbackUsed) {
      rendered = "Note: using fallback model ${resolution.chosen}.\n" + rendered
    }
    sessionState.appendHistory(session, "User review request: ${prompt}", "Review: ${rendered}")
    if (logReview) {
      writeReviewLog(prompt, summary, paths, staged, severityThreshold)
    }
    rendered
  }

  @ShellMethod(key = ["reviewlog", "/reviewlog"], value = "Show recent reviews from the log with filters.")
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

  @ShellMethod(key = ["search", "/search"], value = "Run web search through the agent tool.")
  String search(
    @ShellOption(help = "Query to search") String query,
    @ShellOption(defaultValue = "5", help = "Number of results to show") int limit,
    @ShellOption(defaultValue = "default", help = "Session id for caching and configuration") String session,
    @ShellOption(defaultValue = "duckduckgo", help = "Search provider") String provider,
    @ShellOption(defaultValue = "15000", help = "Timeout in milliseconds") long timeoutMillis,
    @ShellOption(defaultValue = "true", help = "Run browser in headless mode") boolean headless,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override web search enablement (true/false)") Boolean enableWebSearch
  ) {
    boolean defaultEnabled = sessionState.isWebSearchEnabled(session)
    Boolean overrideEnabled = enableWebSearch
    boolean allowed = overrideEnabled != null ? overrideEnabled : defaultEnabled
    if (!allowed) {
      return "Web search is disabled for this session. " +
        "Enable globally in application.properties with assistant.web-search.enabled=true, " +
        "or enable for this request by passing --enable-web-search true."
    }
    WebSearchTool.SearchOptions options = WebSearchTool.withDefaults(
      new WebSearchTool.SearchOptions(
        provider: WebSearchTool.providerFrom(provider),
        limit: limit,
        headless: headless,
        timeoutMillis: timeoutMillis,
        sessionId: session,
        webSearchEnabled: overrideEnabled
      ),
      defaultEnabled
    )
    List<WebSearchTool.SearchResult> results
    try {
      results = codingAssistantAgent.search(query, options)
    } catch (Exception e) {
      results = []
      return "Web search unavailable: ${e.message ?: e.class.simpleName}"
    }
    if (results == null || results.isEmpty()) {
      return "No web results."
    }
    results.stream()
      .map { WebSearchTool.SearchResult result ->
        String title = result.title ?: "(no title)"
        String url = result.url ?: "(no url)"
        String snippet = result.snippet ?: ""
        "${title} - ${url}\n${snippet}"
      }
      .toList()
      .join("\n\n")
  }

  @ShellMethod(
    key = ["codesearch", "/codesearch"],
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
    List<CodeSearchTool.SearchHit> hits = codeSearchTool.search(query, paths, context, limit)
    if (hits.isEmpty()) {
      return "No matches found."
    }
    if (pack) {
      PackedContext packed = contextPacker.pack(hits, maxChars)
      int tokens = maxTokens > 0 ? maxTokens : contextBudgetManager.maxTokens
      int chars = maxChars > 0 ? maxChars : contextBudgetManager.maxChars
      def budgeted = contextBudgetManager.applyBudget(packed.text, packed.included, chars, tokens)
      String summary = "Packed ${budgeted.included.size()} matches" + ((packed.truncated || budgeted.truncated) ? " (truncated)" : "")
      return summary + "\n" + budgeted.text
    }
    hits.collect { CodeSearchTool.SearchHit hit ->
      "${hit.path}:${hit.line}:${hit.column}\n${hit.snippet}"
    }.join("\n\n")
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

  @ShellMethod(key = ["status", "/status"], value = "Show git status for the current repository.")
  String gitStatus(
    @ShellOption(defaultValue = "false", help = "Use short porcelain output") boolean shortFormat
  ) {
    formatGitResult("Status", gitTool.status(shortFormat))
  }

  @ShellMethod(key = ["diff", "/diff"], value = "Show git diff with optional staging and path filters.")
  String gitDiff(
    @ShellOption(defaultValue = "false", help = "Use staged diff (--cached)") boolean staged,
    @ShellOption(defaultValue = "3", help = "Number of context lines") int context,
    @ShellOption(defaultValue = ShellOption.NULL, arity = -1, help = "Paths to include") List<String> paths,
    @ShellOption(defaultValue = "false", help = "Show stats instead of full patch") boolean stat
  ) {
    GitTool.GitResult result = gitTool.diff(staged, paths, context, stat)
    formatGitResult("Diff", result)
  }

  @ShellMethod(
    key = ["gitapply", "/gitapply", "git-apply", "/git-apply"],
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

  @ShellMethod(key = ["stage", "/stage"], value = "Stage files or specific hunks with confirmation.")
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
    key = ["commit-suggest", "/commit-suggest"],
    value = "Draft an imperative commit message from staged changes."
  )
  String commitSuggest(
    @ShellOption(defaultValue = "default", help = "Session id for options") String session,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override model") String model,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override temperature") Double temperature,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Override max tokens") Integer maxTokens,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Optional guidance for the commit message") String hint
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
    String health = ensureOllamaHealth()
    if (health != null) {
      return health
    }
    ModelResolution resolution = resolveModel(model)
    SessionSettings settings = sessionState.update(session, resolution.chosen, temperature, null, maxTokens, null, null)
    LlmOptions options = sessionState.craftOptions(settings)
    String prompt = buildCommitPrompt(diff.output ?: "", hint, sessionState.systemPrompt(settings))
    String message = ai.withLlm(options).generateText(prompt)
    if (resolution.fallbackUsed) {
      message = "Note: using fallback model ${resolution.chosen}.\n" + (message ?: "")
    }
    sessionState.appendHistory(session, "Commit suggest request", message)
    message?.trim() ?: "No commit message generated."
  }

  @ShellMethod(key = ["git-push", "/git-push"], value = "Push the current branch with confirmation.")
  String gitPush(
    @ShellOption(defaultValue = "false", help = "Use --force-with-lease") boolean force
  ) {
    if (!gitTool.isGitRepo()) {
      return "Not a git repository."
    }
    ConfirmChoice choice = confirmAction("Run git push${force ? ' --force-with-lease' : ''}?")
    if (choice != ConfirmChoice.YES && choice != ConfirmChoice.ALL) {
      return "Push canceled."
    }
    GitTool.GitResult result = gitTool.push(force)
    formatGitResult("Push", result)
  }

  @ShellMethod(
    key = ["model", "/model"],
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
    key = ["health", "/health"],
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
    key = ["run", "/run"],
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
    String trimmed = command != null ? command.trim() : ""
    if (!trimmed) {
      throw new IllegalArgumentException("Command must not be empty.")
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
    key = ["apply", "/apply"],
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
    if (dryRun) {
      return formatPatchResult(fileEditingTool.applyPatch(body, true))
    }
    boolean shouldConfirm = confirm && !applyAllConfirmed
    if (shouldConfirm) {
      FileEditingTool.PatchResult preview = fileEditingTool.applyPatch(body, true)
      String previewText = formatPatchResult(preview)
      if (preview.hasConflicts) {
        return previewText
      }
      println(previewText)
      ConfirmChoice choice = confirmAction("Apply patch to ${preview.fileResults.size()} file(s)?")
      if (choice == ConfirmChoice.NO) {
        return "Patch application canceled."
      }
      if (choice == ConfirmChoice.ALL) {
        applyAllConfirmed = true
      }
    }
    warnDirtyWorkspace()
    FileEditingTool.PatchResult result = fileEditingTool.applyPatch(body, false)
    formatPatchResult(result)
  }

  @ShellMethod(
    key = ["applyBlocks", "/applyBlocks"],
    value = "Apply Search-and-Replace blocks to a file (<<<<SEARCH ... ==== ... >>>>)."
  )
  String applyBlocks(
    @ShellOption(help = "Target file path relative to project root") String filePath,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Blocks text; ignored when blocks-file is set") String blocks,
    @ShellOption(defaultValue = ShellOption.NULL, help = "File containing blocks") String blocksFile,
    @ShellOption(defaultValue = "true", help = "Preview changes without writing") boolean dryRun,
    @ShellOption(defaultValue = "true", help = "Ask for confirmation before writing changes") boolean confirm
  ) {
    String body = resolvePatchBody(blocks, blocksFile)
    if (dryRun) {
      return formatSearchReplaceResult(fileEditingTool.applySearchReplaceBlocks(filePath, body, true))
    }
    boolean shouldConfirm = confirm && !applyAllConfirmed
    if (shouldConfirm) {
      FileEditingTool.SearchReplaceResult preview = fileEditingTool.applySearchReplaceBlocks(filePath, body, true)
      String previewText = formatSearchReplaceResult(preview)
      if (preview.hasConflicts) {
        return previewText
      }
      println(previewText)
      ConfirmChoice choice = confirmAction("Apply blocks to ${filePath}?")
      if (choice == ConfirmChoice.NO) {
        return "Block application canceled."
      }
      if (choice == ConfirmChoice.ALL) {
        applyAllConfirmed = true
      }
    }
    warnDirtyWorkspace()
    FileEditingTool.SearchReplaceResult result = fileEditingTool.applySearchReplaceBlocks(filePath, body, false)
    formatSearchReplaceResult(result)
  }

  @ShellMethod(
    key = ["revert", "/revert"],
    value = "Restore a file using the most recent patch backup."
  )
  String revert(
    @ShellOption(help = "File path relative to project root") String filePath,
    @ShellOption(defaultValue = "false", help = "Preview the restore without writing") boolean dryRun
  ) {
    formatEditResult(fileEditingTool.revertLatestBackup(filePath, dryRun))
  }

  @ShellMethod(
    key = ["context", "/context"],
    value = "Show a snippet for targeted edits by line range or symbol."
  )
  String context(
    @ShellOption(help = "File path relative to project root") String filePath,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Start line (1-based) when using ranges") Integer start,
    @ShellOption(defaultValue = ShellOption.NULL, help = "End line (1-based) when using ranges") Integer end,
    @ShellOption(defaultValue = ShellOption.NULL, help = "Symbol to locate instead of line numbers") String symbol,
    @ShellOption(defaultValue = "2", help = "Padding lines around the selection") int padding
  ) {
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
      List<ReviewFinding> filtered = summary.findings.findAll { it.severity.ordinal() <= minSeverity.ordinal() }
      String entry = """
=== Review ===
Prompt: ${prompt}
Paths: ${paths ? paths.join(", ") : "none"}
Staged: ${staged}
Timestamp: ${nowInstant()}
Findings: ${filtered.size()} (min ${minSeverity})
Tests: ${summary.tests.size()}
---
${renderReview(summary, minSeverity, false)}
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

  private String ensureOllamaHealth() {
    ModelRegistry.Health health = modelRegistry.checkHealth()
    if (!health.reachable) {
      return "Ollama unreachable at ${modelRegistry.getBaseUrl()}: ${health.message}"
    }
    null
  }

  protected ModelResolution resolveModel(String requested) {
    resolveModel(requested, modelRegistry.listModels())
  }

  protected ModelResolution resolveModel(String requested, List<String> available) {
    String desired = requested != null ? requested : sessionState.getDefaultModel()
    boolean canCheck = available != null && !available.isEmpty()
    String matchedDesired = canCheck ? available.find { it.equalsIgnoreCase(desired) } : desired
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

  private static String buildCommitPrompt(String diff, String hint, String systemPrompt) {
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
