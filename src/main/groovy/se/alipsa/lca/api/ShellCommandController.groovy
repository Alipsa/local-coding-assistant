package se.alipsa.lca.api

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import se.alipsa.lca.agent.PersonaMode
import se.alipsa.lca.review.ReviewSeverity
import se.alipsa.lca.shell.ShellCommands

@RestController
@RequestMapping("/api/cli")
@CompileStatic
class ShellCommandController {

  private final ShellCommands shellCommands

  ShellCommandController(ShellCommands shellCommands) {
    this.shellCommands = shellCommands
  }

  @PostMapping(path = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
  String chat(@RequestBody ChatRequest request) {
    String prompt = requireValue(request.prompt, "prompt")
    String session = request.session ?: "default"
    PersonaMode persona = request.persona ?: PersonaMode.CODER
    shellCommands.chat(
      prompt,
      session,
      persona,
      request.model,
      request.temperature,
      request.reviewTemperature,
      request.maxTokens,
      request.systemPrompt
    )
  }

  @PostMapping(path = "/review", consumes = MediaType.APPLICATION_JSON_VALUE)
  String review(@RequestBody ReviewRequest request) {
    String prompt = requireValue(request.prompt, "prompt")
    String session = request.session ?: "default"
    ReviewSeverity severity = request.minSeverity ?: ReviewSeverity.LOW
    boolean staged = request.staged != null ? request.staged : false
    boolean noColor = request.noColor != null ? request.noColor : false
    boolean logReview = request.logReview != null ? request.logReview : true
    List<String> paths = request.paths != null ? request.paths : List.<String>of()
    shellCommands.review(
      request.code ?: "",
      prompt,
      session,
      request.model,
      request.reviewTemperature,
      request.maxTokens,
      request.systemPrompt,
      paths,
      staged,
      severity,
      noColor,
      logReview
    )
  }

  @GetMapping("/reviewlog")
  String reviewLog(
    @RequestParam(name = "minSeverity", defaultValue = "LOW") ReviewSeverity minSeverity,
    @RequestParam(name = "pathFilter", required = false) String pathFilter,
    @RequestParam(name = "limit", defaultValue = "5") int limit,
    @RequestParam(name = "page", defaultValue = "1") int page,
    @RequestParam(name = "since", required = false) String since,
    @RequestParam(name = "noColor", defaultValue = "false") boolean noColor
  ) {
    shellCommands.reviewLog(minSeverity, pathFilter, limit, page, since, noColor)
  }

  @GetMapping("/search")
  String search(
    @RequestParam(name = "query") String query,
    @RequestParam(name = "limit", defaultValue = "5") int limit,
    @RequestParam(name = "session", defaultValue = "default") String session,
    @RequestParam(name = "provider", defaultValue = "duckduckgo") String provider,
    @RequestParam(name = "timeoutMillis", defaultValue = "15000") long timeoutMillis,
    @RequestParam(name = "headless", defaultValue = "true") boolean headless,
    @RequestParam(name = "enableWebSearch", required = false) Boolean enableWebSearch
  ) {
    shellCommands.search(query, limit, session, provider, timeoutMillis, headless, enableWebSearch)
  }

  @GetMapping("/codesearch")
  String codeSearch(
    @RequestParam(name = "query") String query,
    @RequestParam(name = "paths", required = false) List<String> paths,
    @RequestParam(name = "context", defaultValue = "2") int context,
    @RequestParam(name = "limit", defaultValue = "20") int limit,
    @RequestParam(name = "pack", defaultValue = "false") boolean pack,
    @RequestParam(name = "maxChars", defaultValue = "8000") int maxChars,
    @RequestParam(name = "maxTokens", defaultValue = "0") int maxTokens
  ) {
    shellCommands.codeSearch(query, paths, context, limit, pack, maxChars, maxTokens)
  }

  @PostMapping(path = "/edit", consumes = MediaType.APPLICATION_JSON_VALUE)
  String edit(@RequestBody EditRequest request) {
    String seed = request.seed ?: ""
    boolean send = request.send != null ? request.send : false
    String session = request.session ?: "default"
    PersonaMode persona = request.persona ?: PersonaMode.CODER
    if (send) {
      return shellCommands.chat(seed, session, persona, null, null, null, null, null)
    }
    seed
  }

  @PostMapping(path = "/paste", consumes = MediaType.APPLICATION_JSON_VALUE)
  String paste(@RequestBody PasteRequest request) {
    String content = requireValue(request.content, "content")
    String endMarker = request.endMarker ?: "/end"
    boolean send = request.send != null ? request.send : false
    String session = request.session ?: "default"
    PersonaMode persona = request.persona ?: PersonaMode.CODER
    shellCommands.paste(content, endMarker, send, session, persona)
  }

  @GetMapping("/status")
  String status(@RequestParam(name = "shortFormat", defaultValue = "false") boolean shortFormat) {
    shellCommands.gitStatus(shortFormat)
  }

  @GetMapping("/diff")
  String diff(
    @RequestParam(name = "staged", defaultValue = "false") boolean staged,
    @RequestParam(name = "context", defaultValue = "3") int context,
    @RequestParam(name = "paths", required = false) List<String> paths,
    @RequestParam(name = "stat", defaultValue = "false") boolean stat
  ) {
    shellCommands.gitDiff(staged, context, paths, stat)
  }

  @PostMapping(path = "/gitapply", consumes = MediaType.APPLICATION_JSON_VALUE)
  String gitApply(@RequestBody GitApplyRequest request) {
    boolean cached = request.cached != null ? request.cached : false
    boolean check = request.check != null ? request.check : true
    boolean confirm = request.confirm != null ? request.confirm : false
    shellCommands.gitApply(request.patch, request.patchFile, cached, check, confirm)
  }

  @PostMapping(path = "/stage", consumes = MediaType.APPLICATION_JSON_VALUE)
  String stage(@RequestBody StageRequest request) {
    boolean confirm = request.confirm != null ? request.confirm : false
    shellCommands.stage(request.paths, request.file, request.hunks, confirm)
  }

  @PostMapping(path = "/commit-suggest", consumes = MediaType.APPLICATION_JSON_VALUE)
  String commitSuggest(@RequestBody CommitSuggestRequest request) {
    String session = request.session ?: "default"
    shellCommands.commitSuggest(session, request.model, request.temperature, request.maxTokens, request.hint)
  }

  @PostMapping(path = "/git-push", consumes = MediaType.APPLICATION_JSON_VALUE)
  String gitPush(@RequestBody GitPushRequest request) {
    boolean force = request.force != null ? request.force : false
    boolean confirm = request.confirm != null ? request.confirm : false
    shellCommands.gitPush(force, confirm)
  }

  @PostMapping(path = "/model", consumes = MediaType.APPLICATION_JSON_VALUE)
  String model(@RequestBody ModelRequest request) {
    String session = request.session ?: "default"
    boolean list = request.list != null ? request.list : false
    shellCommands.model(request.set, session, list)
  }

  @GetMapping("/health")
  String health() {
    shellCommands.health()
  }

  @PostMapping(path = "/run", consumes = MediaType.APPLICATION_JSON_VALUE)
  String run(@RequestBody RunRequest request) {
    String command = requireValue(request.command, "command")
    String session = request.session ?: "default"
    long timeoutMillis = request.timeoutMillis != null ? request.timeoutMillis : 60000L
    int maxOutputChars = request.maxOutputChars != null ? request.maxOutputChars : 8000
    boolean confirm = request.confirm != null ? request.confirm : false
    boolean agentRequested = request.agentRequested != null ? request.agentRequested : false
    shellCommands.runCommand(command, timeoutMillis, maxOutputChars, session, confirm, agentRequested)
  }

  @PostMapping(path = "/apply", consumes = MediaType.APPLICATION_JSON_VALUE)
  String apply(@RequestBody ApplyPatchRequest request) {
    boolean dryRun = request.dryRun != null ? request.dryRun : true
    boolean confirm = request.confirm != null ? request.confirm : false
    shellCommands.applyPatch(request.patch, request.patchFile, dryRun, confirm)
  }

  @PostMapping(path = "/apply-blocks", consumes = MediaType.APPLICATION_JSON_VALUE)
  String applyBlocks(@RequestBody ApplyBlocksRequest request) {
    String filePath = requireValue(request.filePath, "filePath")
    boolean dryRun = request.dryRun != null ? request.dryRun : true
    boolean confirm = request.confirm != null ? request.confirm : false
    shellCommands.applyBlocks(filePath, request.blocks, request.blocksFile, dryRun, confirm)
  }

  @PostMapping(path = "/revert", consumes = MediaType.APPLICATION_JSON_VALUE)
  String revert(@RequestBody RevertRequest request) {
    String filePath = requireValue(request.filePath, "filePath")
    boolean dryRun = request.dryRun != null ? request.dryRun : false
    shellCommands.revert(filePath, dryRun)
  }

  @PostMapping(path = "/context", consumes = MediaType.APPLICATION_JSON_VALUE)
  String context(@RequestBody ContextRequest request) {
    String filePath = requireValue(request.filePath, "filePath")
    Integer start = request.start
    Integer end = request.end
    String symbol = request.symbol
    int padding = request.padding != null ? request.padding : 2
    shellCommands.context(filePath, start, end, symbol, padding)
  }

  @GetMapping("/tree")
  String tree(
    @RequestParam(name = "depth", defaultValue = "4") int depth,
    @RequestParam(name = "dirsOnly", defaultValue = "false") boolean dirsOnly,
    @RequestParam(name = "maxEntries", defaultValue = "2000") int maxEntries
  ) {
    shellCommands.tree(depth, dirsOnly, maxEntries)
  }

  private static String requireValue(String value, String field) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException("Missing required field: ${field}")
    }
    value
  }

  @Canonical
  @CompileStatic
  static class ChatRequest {
    String prompt
    String session
    PersonaMode persona
    String model
    Double temperature
    Double reviewTemperature
    Integer maxTokens
    String systemPrompt
  }

  @Canonical
  @CompileStatic
  static class ReviewRequest {
    String code
    String prompt
    String session
    String model
    Double reviewTemperature
    Integer maxTokens
    String systemPrompt
    List<String> paths
    Boolean staged
    ReviewSeverity minSeverity
    Boolean noColor
    Boolean logReview
  }

  @Canonical
  @CompileStatic
  static class EditRequest {
    String seed
    Boolean send
    String session
    PersonaMode persona
  }

  @Canonical
  @CompileStatic
  static class PasteRequest {
    String content
    String endMarker
    Boolean send
    String session
    PersonaMode persona
  }

  @Canonical
  @CompileStatic
  static class GitApplyRequest {
    String patch
    String patchFile
    Boolean cached
    Boolean check
    Boolean confirm
  }

  @Canonical
  @CompileStatic
  static class StageRequest {
    List<String> paths
    String file
    String hunks
    Boolean confirm
  }

  @Canonical
  @CompileStatic
  static class CommitSuggestRequest {
    String session
    String model
    Double temperature
    Integer maxTokens
    String hint
  }

  @Canonical
  @CompileStatic
  static class GitPushRequest {
    Boolean force
    Boolean confirm
  }

  @Canonical
  @CompileStatic
  static class ModelRequest {
    String set
    String session
    Boolean list
  }

  @Canonical
  @CompileStatic
  static class RunRequest {
    String command
    Long timeoutMillis
    Integer maxOutputChars
    String session
    Boolean confirm
    Boolean agentRequested
  }

  @Canonical
  @CompileStatic
  static class ApplyPatchRequest {
    String patch
    String patchFile
    Boolean dryRun
    Boolean confirm
  }

  @Canonical
  @CompileStatic
  static class ApplyBlocksRequest {
    String filePath
    String blocks
    String blocksFile
    Boolean dryRun
    Boolean confirm
  }

  @Canonical
  @CompileStatic
  static class RevertRequest {
    String filePath
    Boolean dryRun
  }

  @Canonical
  @CompileStatic
  static class ContextRequest {
    String filePath
    Integer start
    Integer end
    String symbol
    Integer padding
  }
}
