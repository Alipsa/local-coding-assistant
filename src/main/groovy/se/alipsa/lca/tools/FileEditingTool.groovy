package se.alipsa.lca.tools

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.ArrayList
import java.util.Arrays
import java.util.LinkedHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern

@Component
@CompileStatic
class FileEditingTool {

  private static final Logger log = LoggerFactory.getLogger(FileEditingTool)
  private static final Pattern HUNK_HEADER = Pattern.compile('^@@ -(\\d+),?(\\d*) \\+(\\d+),?(\\d*) @@')
  private static final DateTimeFormatter BACKUP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
  private static final String BACKUP_ROOT = ".lca/backups"
  private static final int PREVIEW_LIMIT = 800

  private final Path projectRoot
  private final Path realProjectRoot
  private final ExclusionPolicy exclusionPolicy

  FileEditingTool() {
    this(Paths.get(".").toAbsolutePath())
  }

  FileEditingTool(Path projectRoot) {
    this.projectRoot = projectRoot.toAbsolutePath().normalize()
    try {
      this.realProjectRoot = this.projectRoot.toRealPath()
    } catch (IOException e) {
      throw new IllegalStateException("Failed to resolve project root path", e)
    }
    this.exclusionPolicy = new ExclusionPolicy(this.projectRoot)
  }

  Path getProjectRoot() {
    projectRoot
  }

  String writeFile(String filePath, String content) {
    try {
      Path path = resolvePath(filePath)
      if (path.getParent() != null) {
        Files.createDirectories(path.getParent())
      }
      Files.writeString(path, content)
      return "Successfully wrote to $filePath"
    } catch (IOException e) {
      log.error("Error writing to file {}", filePath, e)
      return "Error writing to file: ${e.message}"
    }
  }

  String readFile(String filePath) {
    try {
      Path path = resolvePath(filePath)
      if (!Files.exists(path)) {
        throw new IllegalArgumentException("File $filePath does not exist")
      }
      return Files.readString(path)
    } catch (IOException e) {
      log.error("Error reading file {}", filePath, e)
      throw new IllegalArgumentException("Error reading file: ${e.message}", e)
    }
  }

  SearchReplaceResult applySearchReplaceBlocks(String filePath, String blocksText, boolean dryRun) {
    if (blocksText == null || blocksText.trim().isEmpty()) {
      return new SearchReplaceResult(false, dryRun, true, List.of(), null, List.of("No blocks provided"))
    }
    Path target = resolvePath(filePath)
    if (!Files.exists(target)) {
      return new SearchReplaceResult(
        false,
        dryRun,
        true,
        List.of(),
        null,
        List.of("File ${filePath} does not exist".toString())
      )
    }
    String original = Files.readString(target)
    boolean hadTrailingNewline = hasTrailingNewline(original)
    List<SearchReplaceBlock> blocks = parseBlocks(blocksText)
    if (blocks.isEmpty()) {
      return new SearchReplaceResult(false, dryRun, true, List.of(), null, List.of("No valid blocks found"))
    }
    List<BlockResult> blockResults = new ArrayList<>()
    String working = original
    boolean conflict = false
    for (int i = 0; i < blocks.size(); i++) {
      SearchReplaceBlock block = blocks.get(i)
      BlockResult result = applySingleBlock(working, block, i)
      blockResults.add(result)
      if (result.conflicted) {
        conflict = true
      } else {
        working = result.updatedText
      }
    }
    if (!dryRun && conflict) {
      return new SearchReplaceResult(false, false, true, blockResults, null, List.of("Conflicts detected; no changes applied."))
    }
    if (!dryRun) {
      Path backup = createBackup(target, original)
      writeLines(target, splitToLines(working), resolveLineSeparator(original), hadTrailingNewline)
      return new SearchReplaceResult(true, false, conflict, blockResults, projectRoot.relativize(backup).toString(), List.of())
    }
    new SearchReplaceResult(false, true, conflict, blockResults, null, List.of())
  }

  String replace(String filePath, String oldString, String newString) {
    try {
      Path path = resolvePath(filePath)
      if (!Files.exists(path)) {
        throw new IllegalArgumentException("File $filePath does not exist")
      }
      String content = Files.readString(path)
      String newContent = content.replace(oldString, newString)
      Files.writeString(path, newContent)
      return "Successfully replaced content in $filePath"
    } catch (IOException e) {
      log.error("Error replacing content in file {}", filePath, e)
      return "Error replacing content in file: ${e.message}"
    }
  }

  String deleteFile(String filePath) {
    try {
      Path path = resolvePath(filePath)
      Files.deleteIfExists(path)
      return "Successfully deleted $filePath"
    } catch (IOException e) {
      log.error("Error deleting file {}", filePath, e)
      return "Error deleting file: ${e.message}"
    }
  }

  PatchResult applyPatch(String patchText, boolean dryRun) {
    if (patchText == null || patchText.trim().isEmpty()) {
      return new PatchResult(false, dryRun, true, List.of(), List.of("No patch content provided."))
    }
    List<PatchFile> patchFiles = parseUnifiedDiff(patchText)
    if (patchFiles.isEmpty()) {
      return new PatchResult(false, dryRun, true, List.of(), List.of("No file sections found in patch content."))
    }
    List<FilePatchResult> allResults = new ArrayList<>()
    Map<Path, FilePatchResult> resultsByPath = new LinkedHashMap<>()
    List<FilePatchComputation> computations = new ArrayList<>()
    boolean hasConflicts = false

    for (PatchFile patchFile : patchFiles) {
      try {
        FilePatchComputation computation = computeApplication(patchFile)
        computations.add(computation)
        FilePatchResult fileResult = buildResultFromComputation(computation, dryRun)
        allResults.add(fileResult)
        resultsByPath.put(computation.targetPath, fileResult)
        if (computation.conflicted) {
          hasConflicts = true
        }
      } catch (Exception e) {
        hasConflicts = true
        String label = patchFile.newPath != null ? patchFile.newPath : patchFile.oldPath
        String pathLabel = label ?: "unknown"
        FilePatchResult failed = new FilePatchResult(
          pathLabel,
          false,
          true,
          "/dev/null" == patchFile.newPath,
          "/dev/null" == patchFile.oldPath,
          dryRun,
          null,
          "Failed to prepare patch: ${e.message}",
          ""
        )
        allResults.add(failed)
        log.warn("Failed to prepare patch for {}", pathLabel, e)
      }
    }
    if (!dryRun && hasConflicts) {
      return new PatchResult(
        false,
        false,
        true,
        allResults.isEmpty() ? new ArrayList<>(resultsByPath.values()) : allResults,
        List.of("Conflicts detected; patch was not applied.")
      )
    }
    boolean writeFailed = false
    if (!dryRun) {
      writeFailed = applyComputedChanges(computations, resultsByPath)
    }
    boolean finalConflicts = hasConflicts || writeFailed
    boolean applied = !dryRun && !finalConflicts
    List<FilePatchResult> results = allResults.isEmpty() ? new ArrayList<>(resultsByPath.values()) : allResults
    List<String> messages = new ArrayList<>()
    if (hasConflicts) {
      messages.add("Conflicts detected; patch not applied.")
    }
    if (writeFailed) {
      messages.add("Failed to write one or more files; see messages for details.")
    }
    new PatchResult(applied, dryRun, finalConflicts, results, messages)
  }

  TargetedEditContext contextByRange(String filePath, int startLine, int endLine, int padding) {
    if (startLine <= 0 || endLine <= 0 || endLine < startLine) {
      throw new IllegalArgumentException("Invalid start/end lines for context request")
    }
    Path path = resolvePath(filePath)
    if (!Files.exists(path)) {
      throw new IllegalArgumentException("File $filePath does not exist")
    }
    List<String> lines = readLines(path)
    int totalLines = lines.size()
    int paddingLines = Math.max(0, padding)
    int start = Math.max(1, startLine - paddingLines)
    int end = Math.min(totalLines, endLine + paddingLines)
    String snippet = formatSnippet(lines, start, end)
    new TargetedEditContext(filePath, start, end, totalLines, snippet)
  }

  TargetedEditContext contextBySymbol(String filePath, String symbol, int padding) {
    if (symbol == null || symbol.trim().isEmpty()) {
      throw new IllegalArgumentException("Symbol must be provided")
    }
    Path path = resolvePath(filePath)
    if (!Files.exists(path)) {
      throw new IllegalArgumentException("File $filePath does not exist")
    }
    List<String> lines = readLines(path)
    Pattern pattern = Pattern.compile("\\b${Pattern.quote(symbol)}\\b")
    int index = -1
    for (int i = 0; i < lines.size(); i++) {
      if (pattern.matcher(lines.get(i)).find()) {
        index = i
        break
      }
    }
    if (index < 0) {
      throw new IllegalArgumentException("Symbol '$symbol' not found in $filePath")
    }
    int line = index + 1
    contextByRange(filePath, line, line, padding)
  }

  EditResult replaceRange(String filePath, int startLine, int endLine, String newContent, boolean dryRun) {
    if (startLine <= 0 || endLine < startLine) {
      throw new IllegalArgumentException("Invalid line range for replacement")
    }
    Path path = resolvePath(filePath)
    if (!Files.exists(path)) {
      throw new IllegalArgumentException("File $filePath does not exist")
    }
    List<String> lines = readLines(path)
    if (endLine > lines.size()) {
      throw new IllegalArgumentException("End line $endLine exceeds file length ${lines.size()}")
    }
    int zeroBasedStart = startLine - 1
    int zeroBasedEnd = endLine - 1
    List<String> replacementLines = splitToLines(newContent)
    List<String> updated = new ArrayList<>(lines.subList(0, zeroBasedStart))
    updated.addAll(replacementLines)
    if (zeroBasedEnd + 1 < lines.size()) {
      updated.addAll(lines.subList(zeroBasedEnd + 1, lines.size()))
    }
    if (dryRun) {
      return new EditResult(
        false,
        true,
        null,
        "Would replace lines $startLine-$endLine in $filePath",
        filePath
      )
    }
    String originalText = Files.readString(path)
    Path backup = createBackup(path, originalText)
    writeLines(path, updated, resolveLineSeparator(originalText), hasTrailingNewline(originalText))
    new EditResult(
      true,
      false,
      projectRoot.relativize(backup).toString(),
      "Replaced lines $startLine-$endLine in $filePath",
      filePath
    )
  }

  EditResult revertLatestBackup(String filePath, boolean dryRun) {
    Path target = resolvePath(filePath)
    Path relative = projectRoot.relativize(target)
    Path backupDir = projectRoot.resolve(BACKUP_ROOT).resolve(relative).getParent()
    if (backupDir == null || !Files.exists(backupDir)) {
      return new EditResult(false, dryRun, null, "No backups found for $filePath", filePath)
    }
    String prefix = relative.getFileName().toString() + "."
    List<Path> backups = new ArrayList<>()
    Files.list(backupDir).withCloseable { stream ->
      stream
        .filter { Path p ->
          String name = p.getFileName().toString()
          name.startsWith(prefix) && name.endsWith(".bak")
        }
        .forEach { backups.add(it) }
    }
    if (backups.isEmpty()) {
      return new EditResult(false, dryRun, null, "No backups found for $filePath", filePath)
    }
    backups.sort { Path a, Path b ->
      try {
        def timeA = Files.getLastModifiedTime(a).toMillis()
        def timeB = Files.getLastModifiedTime(b).toMillis()
        if (timeA == timeB) {
          return b.getFileName().toString() <=> a.getFileName().toString()
        }
        return timeB <=> timeA
      } catch (IOException ignored) {
        return b.getFileName().toString() <=> a.getFileName().toString()
      }
    }
    Path latest = backups.get(0)
    String backupContent = Files.readString(latest)
    if (!dryRun) {
      if (target.getParent() != null) {
        Files.createDirectories(target.getParent())
      }
      Files.writeString(target, backupContent)
    }
    new EditResult(
      !dryRun,
      dryRun,
      projectRoot.relativize(latest).toString(),
      dryRun ? "Would restore $filePath from ${latest.getFileName()}" : "Restored $filePath from backup",
      filePath
    )
  }

  private FilePatchResult buildResultFromComputation(FilePatchComputation computation, boolean dryRun) {
    String relative = projectRoot.relativize(computation.targetPath).toString()
    String message
    if (computation.conflicted) {
      message = computation.conflicts.join("; ")
    } else if (computation.deleteFile) {
      message = "Delete file"
    } else if (computation.newFile) {
      message = "Create file (+${computation.additions})"
    } else {
      message = "Apply patch (+${computation.additions} / -${computation.deletions})"
    }
    String preview = computation.conflicted ? "" : buildPreview(computation.updatedText)
    new FilePatchResult(
      relative,
      false,
      computation.conflicted,
      computation.deleteFile,
      computation.newFile,
      dryRun,
      null,
      message,
      preview
    )
  }

  private boolean applyComputedChanges(
    List<FilePatchComputation> computations,
    Map<Path, FilePatchResult> resultsByPath
  ) {
    boolean writeFailed = false
    for (FilePatchComputation computation : computations) {
      if (computation.conflicted) {
        continue
      }
      Path target = computation.targetPath
      try {
        if (target.getParent() != null) {
          Files.createDirectories(target.getParent())
        }
        Path backup = null
        if (computation.originalExists) {
          backup = createBackup(target, computation.originalText)
        }
        if (computation.deleteFile) {
          Files.deleteIfExists(target)
        } else {
          Files.writeString(target, computation.updatedText)
        }
        FilePatchResult result = resultsByPath.get(target)
        if (backup != null) {
          result.backupPath = projectRoot.relativize(backup).toString()
        }
        result.applied = true
      } catch (IOException e) {
        log.error("Failed to apply patch to {}", target, e)
        FilePatchResult result = resultsByPath.get(target)
        result.conflicted = true
        result.message = "Failed to apply changes: ${e.message}"
        writeFailed = true
      }
    }
    writeFailed
  }

  private FilePatchComputation computeApplication(PatchFile patchFile) {
    String targetFile = resolveTargetFile(patchFile)
    Path targetPath = resolvePath(targetFile)
    boolean deleteFile = "/dev/null" == patchFile.newPath
    boolean newFile = "/dev/null" == patchFile.oldPath
    boolean originalExists = Files.exists(targetPath)
    String originalText = originalExists ? Files.readString(targetPath) : ""
    boolean hadTrailingNewline = hasTrailingNewline(originalText)
    List<String> originalLines = splitToLines(originalText)
    HunkApplication application = applyHunks(originalLines, patchFile.hunks)
    FilePatchComputation computation = new FilePatchComputation()
    computation.targetPath = targetPath
    computation.deleteFile = deleteFile
    computation.newFile = newFile
    computation.conflicted = application.conflict
    computation.conflicts.addAll(application.conflicts)
    computation.additions = application.additions
    computation.deletions = application.deletions
    computation.originalText = originalText
    computation.updatedText = joinLines(
      application.updatedLines,
      resolveLineSeparator(originalText),
      hadTrailingNewline
    )
    computation.newline = resolveLineSeparator(originalText)
    computation.originalExists = originalExists
    computation
  }

  private static String resolveTargetFile(PatchFile patchFile) {
    if (patchFile.newPath != null && patchFile.newPath != "/dev/null") {
      return patchFile.newPath
    }
    if (patchFile.oldPath != null && patchFile.oldPath != "/dev/null") {
      return patchFile.oldPath
    }
    throw new IllegalArgumentException("Patch does not specify a target file")
  }

  private HunkApplication applyHunks(List<String> originalLines, List<Hunk> hunks) {
    List<String> working = new ArrayList<>(originalLines)
    List<String> conflicts = new ArrayList<>()
    int offset = 0
    int additions = 0
    int deletions = 0
    for (Hunk hunk : hunks) {
      HunkApplyResult result = applySingleHunk(working, hunk, offset)
      if (result.conflict) {
        conflicts.add(result.message)
      } else {
        offset = result.newOffset
        additions += result.additions
        deletions += result.deletions
      }
    }
    new HunkApplication(working, !conflicts.isEmpty(), conflicts, additions, deletions)
  }

  private static HunkApplyResult applySingleHunk(List<String> working, Hunk hunk, int offset) {
    List<String> expected = new ArrayList<>()
    List<String> replacement = new ArrayList<>()
    for (HunkLine line : hunk.lines) {
      switch (line.type) {
        case ' ':
          expected.add(line.text)
          replacement.add(line.text)
          break
        case '-':
          expected.add(line.text)
          break
        case '+':
          replacement.add(line.text)
          break
        default:
          break
      }
    }
    int targetIndex = Math.max(0, hunk.oldStart - 1 + offset)
    int expectedSize = expected.size()
    if (targetIndex > working.size()) {
      return new HunkApplyResult(true, "Hunk target index ${targetIndex + 1} out of bounds", offset, 0, 0)
    }
    if (targetIndex + expectedSize > working.size()) {
      return new HunkApplyResult(true, "Hunk context does not fit at ${hunk.oldStart}", offset, 0, 0)
    }
    List<String> actualSlice = new ArrayList<>(working.subList(targetIndex, targetIndex + expectedSize))
    if (actualSlice != expected) {
      String message = "Context mismatch near line ${hunk.oldStart}; expected ${expectedSize} lines."
      return new HunkApplyResult(true, message, offset, 0, 0)
    }
    working.subList(targetIndex, targetIndex + expectedSize).clear()
    working.addAll(targetIndex, replacement)
    int additions = (int) hunk.lines.count { it.type == '+' }
    int deletions = (int) hunk.lines.count { it.type == '-' }
    int newOffset = offset + replacement.size() - expectedSize
    new HunkApplyResult(false, "", newOffset, additions, deletions)
  }

  private List<PatchFile> parseUnifiedDiff(String patchText) {
    List<PatchFile> files = new ArrayList<>()
    String normalized = patchText.replace("\r\n", "\n")
    List<String> lines = Arrays.asList(normalized.split("\n"))
    PatchFile current = null
    Hunk currentHunk = null
    String oldPath = null
    for (String line : lines) {
      if (line.startsWith("--- ")) {
        oldPath = normalizePatchPath(line.substring(4))
        currentHunk = null
      } else if (line.startsWith("+++ ")) {
        String newPath = normalizePatchPath(line.substring(4))
        current = new PatchFile(oldPath, newPath, new ArrayList<Hunk>())
        files.add(current)
      } else if (line.startsWith("@@")) {
        if (current == null) {
          continue
        }
        Matcher matcher = HUNK_HEADER.matcher(line)
        if (!matcher.find()) {
          continue
        }
        int oldStart = Integer.parseInt(matcher.group(1))
        int oldCount = parseCount(matcher.group(2))
        int newStart = Integer.parseInt(matcher.group(3))
        int newCount = parseCount(matcher.group(4))
        currentHunk = new Hunk(oldStart, oldCount, newStart, newCount, new ArrayList<HunkLine>())
        current.hunks.add(currentHunk)
      } else if (currentHunk != null && isDiffLine(line)) {
        char type = line.charAt(0)
        String text = line.length() > 1 ? line.substring(1) : ""
        currentHunk.lines.add(new HunkLine(type, text))
      }
    }
    files
  }

  private static boolean isDiffLine(String line) {
    if (line == null || line.isEmpty()) {
      return false
    }
    char first = line.charAt(0)
    if (first == '\\') {
      return false
    }
    return first == ' ' || first == '+' || first == '-'
  }

  private static int parseCount(String count) {
    if (count == null || count.isEmpty()) {
      return 1
    }
    return Integer.parseInt(count)
  }

  private static String normalizePatchPath(String rawPath) {
    String trimmed = rawPath.trim()
    String token = trimmed.split("\\s+")[0]
    if (token.startsWith("a/") || token.startsWith("b/")) {
      return token.substring(2)
    }
    token
  }

  private String buildPreview(String content) {
    if (content == null) {
      return ""
    }
    if (content.length() <= PREVIEW_LIMIT) {
      return content
    }
    return content.substring(0, PREVIEW_LIMIT) + "..."
  }

  private static List<SearchReplaceBlock> parseBlocks(String text) {
    List<SearchReplaceBlock> blocks = new ArrayList<>()
    Matcher matcher = Pattern.compile("(?s)<<<<SEARCH\\s*(.*?)>>>>").matcher(text)
    while (matcher.find()) {
      String body = matcher.group(1).trim()
      List<String> lines = Arrays.asList(body.split("\\R"))
      StringBuilder search = new StringBuilder()
      StringBuilder replacement = new StringBuilder()
      boolean inReplace = false
      for (String raw : lines) {
        String line = raw
        if (line.trim().startsWith(">")) {
          // Remove the first '>' character, but preserve all other whitespace
          int idx = line.indexOf('>')
          if (idx != -1 && idx + 1 < line.length()) {
            line = line.substring(idx + 1)
            if (line.startsWith(" ")) {
              line = line.substring(1)
            }
          } else if (idx != -1) {
            line = ""
          }
        }
        if (line.trim() == "====") {
          inReplace = true
          continue
        }
        (inReplace ? replacement : search).append(line).append("\n")
      }
      String searchText = search.toString().stripTrailing()
      String replaceText = replacement.toString().stripTrailing()
      if (searchText) {
        blocks.add(new SearchReplaceBlock(searchText, replaceText))
      }
    }
    blocks
  }

  private static BlockResult applySingleBlock(String content, SearchReplaceBlock block, int index) {
    if (block.search == null || block.search.isEmpty()) {
      return new BlockResult(index, true, "Empty search block", content)
    }
    int first = content.indexOf(block.search)
    if (first < 0) {
      return new BlockResult(index, true, "Search text not found", content)
    }
    int second = content.indexOf(block.search, first + block.search.length())
    if (second >= 0) {
      return new BlockResult(index, true, "Search text not unique", content)
    }
    String updated = content.substring(0, first) + block.replace + content.substring(first + block.search.length())
    new BlockResult(index, false, "Replaced", updated)
  }

  private Path resolvePath(String filePath) {
    Path resolvedPath = projectRoot.resolve(filePath).normalize()
    if (!resolvedPath.startsWith(projectRoot)) {
      throw new IllegalArgumentException("File path must be within the project directory")
    }
    Path existing = resolvedPath
    while (existing != null && !Files.exists(existing)) {
      existing = existing.getParent()
    }
    if (existing == null) {
      throw new IllegalArgumentException("File path must be within the project directory")
    }
    try {
      Path realAncestor = existing.toRealPath()
      if (!realAncestor.startsWith(realProjectRoot)) {
        throw new IllegalArgumentException("File path must be within the project directory")
      }
      if (Files.exists(resolvedPath)) {
        Path realTarget = resolvedPath.toRealPath()
        if (!realTarget.startsWith(realProjectRoot)) {
          throw new IllegalArgumentException("File path must be within the project directory")
        }
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("Unable to resolve file path safely", e)
    }
    if (exclusionPolicy.isExcluded(resolvedPath)) {
      throw new IllegalArgumentException("File path is excluded by .aiexclude")
    }
    resolvedPath
  }

  private static List<String> splitToLines(String content) {
    if (content == null || content.isEmpty()) {
      return new ArrayList<>()
    }
    List<String> lines = new ArrayList<>(Arrays.asList(content.split("\\R", -1)))
    if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
      lines.remove(lines.size() - 1)
    }
    lines
  }

  private static String joinLines(List<String> lines, String newline, boolean appendTerminalNewline) {
    if (lines.isEmpty()) {
      return appendTerminalNewline ? newline : ""
    }
    String joined = String.join(newline, lines)
    appendTerminalNewline ? joined + newline : joined
  }

  private static String resolveLineSeparator(String text) {
    if (text != null && text.contains("\r\n")) {
      return "\r\n"
    }
    return "\n"
  }

  private List<String> readLines(Path path) {
    String content = Files.readString(path)
    splitToLines(content)
  }

  private void writeLines(Path path, List<String> lines, String newline, boolean appendTerminalNewline) {
    if (path.getParent() != null) {
      Files.createDirectories(path.getParent())
    }
    Files.writeString(path, joinLines(lines, newline, appendTerminalNewline))
  }

  private static boolean hasTrailingNewline(String text) {
    if (text == null || text.isEmpty()) {
      return false
    }
    return text.endsWith("\r\n") || text.endsWith("\n")
  }

  private Path createBackup(Path targetPath, String content) {
    Path relative = projectRoot.relativize(targetPath)
    String timestamp = LocalDateTime.now().format(BACKUP_FORMAT)
    Path backupPath = projectRoot
      .resolve(BACKUP_ROOT)
      .resolve("${relative.toString()}.${timestamp}.bak")
      .normalize()
    if (!backupPath.startsWith(projectRoot)) {
      throw new IllegalStateException("Backup path escaped project root")
    }
    if (backupPath.getParent() != null) {
      Files.createDirectories(backupPath.getParent())
    }
    Files.writeString(backupPath, content)
    backupPath
  }

  private static String formatSnippet(List<String> lines, int start, int end) {
    StringBuilder builder = new StringBuilder()
    for (int i = start; i <= end && i <= lines.size(); i++) {
      String line = lines.get(i - 1)
      builder.append(String.format("%4d | %s%n", i, line))
    }
    builder.toString().stripTrailing()
  }

  @Canonical
  @CompileStatic
  static class PatchResult {
    boolean applied
    boolean dryRun
    boolean hasConflicts
    List<FilePatchResult> fileResults
    List<String> messages
  }

  @Canonical
  @CompileStatic
  static class FilePatchResult {
    String filePath
    boolean applied
    boolean conflicted
    boolean deleted
    boolean createdFile
    boolean dryRun
    String backupPath
    String message
    String preview
  }

  @Canonical
  @CompileStatic
  static class TargetedEditContext {
    String filePath
    int startLine
    int endLine
    int totalLines
    String snippet
  }

  @Canonical
  @CompileStatic
  static class EditResult {
    boolean applied
    boolean dryRun
    String backupPath
    String message
    String filePath
  }

  @Canonical
  @CompileStatic
  private static class HunkLine {
    char type
    String text
  }

  @Canonical
  @CompileStatic
  private static class Hunk {
    int oldStart
    int oldCount
    int newStart
    int newCount
    List<HunkLine> lines
  }

  @Canonical
  @CompileStatic
  private static class PatchFile {
    String oldPath
    String newPath
    List<Hunk> hunks
  }

  @Canonical
  @CompileStatic
  private static class HunkApplication {
    List<String> updatedLines
    boolean conflict
    List<String> conflicts
    int additions
    int deletions
  }

  @Canonical
  @CompileStatic
  private static class HunkApplyResult {
    boolean conflict
    String message
    int newOffset
    int additions
    int deletions
  }

  @CompileStatic
  private static class FilePatchComputation {
    Path targetPath
    boolean deleteFile
    boolean newFile
    boolean conflicted
    int additions
    int deletions
    String originalText
    String updatedText
    List<String> conflicts = new ArrayList<>()
    String newline
    boolean originalExists
  }
  @Canonical
  @CompileStatic
  static class SearchReplaceResult {
    boolean applied
    boolean dryRun
    boolean hasConflicts
    List<BlockResult> blocks
    String backupPath
    List<String> messages
  }

  @Canonical
  @CompileStatic
  static class BlockResult {
    int index
    boolean conflicted
    String message
    String updatedText
  }

  @Canonical
  @CompileStatic
  private static class SearchReplaceBlock {
    String search
    String replace
  }

}
