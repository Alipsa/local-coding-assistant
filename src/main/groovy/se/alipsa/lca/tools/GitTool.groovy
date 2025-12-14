package se.alipsa.lca.tools

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Component
@CompileStatic
class GitTool {

  private static final Logger log = LoggerFactory.getLogger(GitTool)
  private final Path projectRoot
  private final Path realProjectRoot
  // Not thread-safe; create separate instances per thread/session.
  private final Object repoCheckLock = new Object()
  private Boolean cachedRepoStatus = null

  GitTool() {
    this(Paths.get(".").toAbsolutePath().normalize())
  }

  GitTool(Path projectRoot) {
    this.projectRoot = projectRoot.toAbsolutePath().normalize()
    try {
      this.realProjectRoot = this.projectRoot.toRealPath()
    } catch (IOException e) {
      this.realProjectRoot = this.projectRoot
    }
  }

  GitResult status(boolean shortFormat) {
    runGit(shortFormat ? List.of("status", "--short") : List.of("status"))
  }

  GitResult diff(boolean staged, List<String> paths, int context, boolean statOnly) {
    List<String> cmd = new ArrayList<>()
    cmd.add("diff")
    if (staged) {
      cmd.add("--cached")
    }
    if (statOnly) {
      cmd.add("--stat")
    }
    int ctx = context >= 0 ? context : 3
    cmd.add("-U${ctx}".toString())
    addPathArgs(cmd, paths)
    runGit(cmd)
  }

  GitResult stagedDiff() {
    runGit(List.of("diff", "--cached"))
  }

  GitResult applyPatch(String patch, boolean cached, boolean checkOnly) {
    if (patch == null || patch.trim().isEmpty()) {
      return new GitResult(false, false, 1, "", "No patch content provided.")
    }
    List<String> cmd = new ArrayList<>()
    cmd.add("apply")
    if (cached) {
      cmd.add("--cached")
    }
    if (checkOnly) {
      cmd.add("--check")
    }
    runGitWithInput(cmd, patch)
  }

  GitResult stageFiles(List<String> paths) {
    if (paths == null || paths.isEmpty()) {
      return new GitResult(false, false, 1, "", "No file paths provided to stage.")
    }
    List<String> validated = new ArrayList<>()
    paths.findAll { it != null && !it.trim().isEmpty() }
      .each { validated.add(validatePath(it.trim())) }
    if (validated.isEmpty()) {
      return new GitResult(false, false, 1, "", "No valid file paths provided.")
    }
    List<String> cmd = new ArrayList<>()
    cmd.add("add")
    cmd.addAll(validated)
    runGit(cmd)
  }

  GitResult stageHunks(String filePath, List<Integer> hunks) {
    if (filePath == null || filePath.trim().isEmpty()) {
      return new GitResult(false, false, 1, "", "File path is required.")
    }
    if (hunks == null || hunks.isEmpty()) {
      return new GitResult(false, false, 1, "", "Provide at least one hunk index to stage.")
    }
    String path = validatePath(filePath.trim())
    GitResult diff = runGit(List.of("diff", "--unified=0", "--", path))
    if (!diff.repoPresent) {
      return diff
    }
    if (!diff.success) {
      return diff
    }
    List<String> lines = diff.output?.readLines() ?: List.of()
    if (lines.isEmpty()) {
      return new GitResult(false, true, 1, "", "No diff available for ${path}.")
    }
    List<String> header = new ArrayList<>()
    List<String> selected = new ArrayList<>()
    List<String> current = new ArrayList<>()
    int hunkIndex = 0
    boolean headerAdded = false

    for (String line : lines) {
      if (line.startsWith("@@")) {
        if (!current.isEmpty()) {
          if (hunks.contains(hunkIndex) && !headerAdded) {
            selected.addAll(header)
            headerAdded = true
          }
          if (hunks.contains(hunkIndex)) {
            selected.addAll(current)
          }
          current.clear()
        }
        current.add(line)
        // hunks are treated as 1-based for user-facing indexing
        hunkIndex++
        continue
      }
      if (hunkIndex == 0) {
        header.add(line)
      } else {
        current.add(line)
      }
    }
    if (!current.isEmpty() && hunks.contains(hunkIndex)) {
      if (!headerAdded) {
        selected.addAll(header)
      }
      selected.addAll(current)
    }
    if (selected.isEmpty()) {
      return new GitResult(false, true, 1, "", "No matching hunks found to stage.")
    }
    String patch = selected.join("\n") + "\n"
    runGitWithInput(List.of("apply", "--cached", "--unidiff-zero"), patch)
  }

  boolean isGitRepo() {
    if (cachedRepoStatus != null) {
      return cachedRepoStatus.booleanValue()
    }
    synchronized (repoCheckLock) {
      if (cachedRepoStatus != null) {
        return cachedRepoStatus.booleanValue()
      }
      GitResult result = runGitNoCheck(List.of("rev-parse", "--is-inside-work-tree"))
      cachedRepoStatus = result.success && result.output?.toLowerCase()?.contains("true")
      return cachedRepoStatus
    }
  }

  boolean isDirty() {
    GitResult status = runGit(List.of("status", "--porcelain"))
    status.repoPresent && status.success && status.output != null && !status.output.trim().isEmpty()
  }

  boolean hasStagedChanges() {
    GitResult diff = runGit(List.of("diff", "--cached", "--name-only"))
    diff.repoPresent && diff.success && diff.output != null && !diff.output.trim().isEmpty()
  }

  GitResult push(boolean force) {
    List<String> cmd = new ArrayList<>()
    cmd.add("push")
    if (force) {
      cmd.add("--force-with-lease")
    }
    runGit(cmd)
  }

  private void addPathArgs(List<String> cmd, List<String> paths) {
    if (paths == null || paths.isEmpty()) {
      return
    }
    List<String> validated = new ArrayList<>()
    paths.findAll { it != null && !it.trim().isEmpty() }
      .each { validated.add(validatePath(it.trim())) }
    if (validated.isEmpty()) {
      return
    }
    cmd.add("--")
    cmd.addAll(validated)
  }

  private String validatePath(String path) {
    Path resolved = projectRoot.resolve(path).normalize()
    try {
      Path realPath = resolved.toRealPath()
      if (!realPath.startsWith(realProjectRoot)) {
        throw new IllegalArgumentException("Path must be inside project root: ${path}")
      }
      return realProjectRoot.relativize(realPath).toString()
    } catch (IOException e) {
      if (!resolved.startsWith(realProjectRoot)) {
        throw new IllegalArgumentException("Path must be inside project root: ${path}")
      }
      if (!Files.exists(resolved)) {
        return realProjectRoot.relativize(resolved).toString()
      }
      throw new IllegalArgumentException("Unable to validate path ${path}: ${e.message}", e)
    }
  }

  private GitResult runGit(List<String> args) {
    runGit(args, true)
  }

  private GitResult runGit(List<String> args, boolean requireRepo) {
    runGitWithInput(args, null, requireRepo)
  }

  private GitResult runGitWithInput(List<String> args, String input) {
    runGitWithInput(args, input, true)
  }

  private GitResult runGitWithInput(List<String> args, String input, boolean requireRepo) {
    if (requireRepo && !isGitRepo()) {
      return new GitResult(false, false, 1, "", "Not a git repository.")
    }
    List<String> command = new ArrayList<>()
    command.add("git")
    command.addAll(args)
    ProcessBuilder pb = new ProcessBuilder(command)
    pb.directory(projectRoot.toFile())
    pb.redirectErrorStream(false)
    Process process
    try {
      process = pb.start()
      if (input != null) {
        process.outputStream.withCloseable { it.write(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)) }
      }
      String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      String error = new String(process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      int exit = process.waitFor()
      return new GitResult(exit == 0, true, exit, output.stripTrailing(), error.stripTrailing())
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt()
      }
      log.warn("Git command failed: {}", args.join(" "), e)
      return new GitResult(false, true, 1, "", e.message ?: e.class.simpleName)
    }
  }

  private GitResult runGitNoCheck(List<String> args) {
    List<String> command = new ArrayList<>()
    command.add("git")
    command.addAll(args)
    ProcessBuilder pb = new ProcessBuilder(command)
    pb.directory(projectRoot.toFile())
    pb.redirectErrorStream(false)
    Process process
    try {
      process = pb.start()
      String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      String error = new String(process.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      int exit = process.waitFor()
      return new GitResult(exit == 0, true, exit, output.stripTrailing(), error.stripTrailing())
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt()
      }
      log.warn("Git command failed: {}", args.join(" "), e)
      return new GitResult(false, true, 1, "", e.message ?: e.class.simpleName)
    }
  }

  @Canonical
  @CompileStatic
  static class GitResult {
    boolean success
    boolean repoPresent
    int exitCode
    String output
    String error
  }
}
