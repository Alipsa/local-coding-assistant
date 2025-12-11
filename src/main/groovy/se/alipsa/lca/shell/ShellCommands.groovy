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
import se.alipsa.lca.tools.FileEditingTool
import se.alipsa.lca.tools.WebSearchTool

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

@ShellComponent
@CompileStatic
class ShellCommands {

  private final CodingAssistantAgent codingAssistantAgent
  private final Ai ai
  private final SessionState sessionState
  private final EditorLauncher editorLauncher
  private final FileEditingTool fileEditingTool
  private volatile boolean applyAllConfirmed = false

  ShellCommands(
    CodingAssistantAgent codingAssistantAgent,
    Ai ai,
    SessionState sessionState,
    EditorLauncher editorLauncher,
    FileEditingTool fileEditingTool
  ) {
    this.codingAssistantAgent = codingAssistantAgent
    this.ai = ai
    this.sessionState = sessionState
    this.editorLauncher = editorLauncher
    this.fileEditingTool = fileEditingTool
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
    FileEditingTool.PatchResult result = fileEditingTool.applyPatch(body, false)
    formatPatchResult(result)
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
}
