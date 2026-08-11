package se.alipsa.lca.tools

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.transform.Immutable
import groovy.transform.PackageScope
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.lang.Nullable
import org.springframework.stereotype.Component

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Component
@CompileStatic
class GitTool {

  private static final Logger log = LoggerFactory.getLogger(GitTool)
  // When a Workspace is present the base dir is read live; otherwise fixedRoot is used (tests).
  @Nullable
  private final Workspace workspace
  private final Path fixedRoot
  // Not thread-safe; create separate instances per thread/session.
  private final Object repoCheckLock = new Object()
  // volatile: this bean is a GUI-shared singleton read from the EDT and the turn worker, and the
  // isGitRepo cache uses double-checked locking, which is only correct with volatile fields.
  private volatile Boolean cachedRepoStatus = null
  private volatile Path cachedRepoRoot = null
  private final Object realRootLock = new Object()
  private volatile Path cachedRealRoot = null
  private volatile Path cachedRealRootFor = null
  // Open-PR check hits GitHub's API via `gh`; cached briefly per branch so polling callers (e.g.
  // the GUI's header bar, refreshed after every chat turn) don't re-hit it on every single turn.
  private static final long PR_CACHE_TTL_MILLIS = 30_000L
  private final Object prCacheLock = new Object()
  private volatile GitResult cachedPrResult = null
  private volatile String cachedPrBranch = null
  private volatile long cachedPrAt = 0L

  GitTool() {
    this(Paths.get(".").toAbsolutePath().normalize())
  }

  GitTool(Path projectRoot) {
    this.fixedRoot = projectRoot.toAbsolutePath().normalize()
    this.workspace = null
  }

  @Autowired
  GitTool(Workspace workspace) {
    this.workspace = workspace
    this.fixedRoot = Paths.get(".").toAbsolutePath().normalize()
  }

  Path getProjectRoot() {
    workspace != null ? workspace.baseDir : fixedRoot
  }

  /**
   * The canonicalised project root, cached against the last-observed {@link #getProjectRoot()}
   * so callers that check it more than once per operation (e.g. {@link #validatePath}) get one
   * consistent {@code toRealPath()} syscall instead of re-resolving — and potentially observing a
   * different root mid-call if the base dir changes concurrently — on every reference.
   */
  @PackageScope
  Path getRealProjectRoot() {
    Path root = getProjectRoot()
    Path cached = cachedRealRoot
    if (cached != null && root == cachedRealRootFor) {
      return cached
    }
    synchronized (realRootLock) {
      if (cachedRealRoot != null && root == cachedRealRootFor) {
        return cachedRealRoot
      }
      try {
        Path real = root.toRealPath()
        cachedRealRoot = real
        cachedRealRootFor = root
        return real
      } catch (IOException e) {
        // Canonicalisation failed (e.g. base dir removed after a runtime switch). Fall back to the
        // non-canonical path but record it rather than failing silently. Not cached: a transient
        // failure shouldn't stick once the root becomes valid again.
        log.warn("Could not canonicalise project root {}; using it uncanonicalised: {}", root, e.message)
        return root
      }
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

  /**
   * The current branch name, or {@code null} when this is not a git repository or the
   * branch cannot be determined. Returns the literal {@code "HEAD"} when the repository
   * is in a detached-HEAD state, mirroring {@code git rev-parse --abbrev-ref HEAD}.
   */
  String currentBranch() {
    if (!isGitRepo()) {
      return null
    }
    GitResult result = runGit(List.of("rev-parse", "--abbrev-ref", "HEAD"))
    if (result.success && result.output != null && !result.output.trim().isEmpty()) {
      return result.output.trim()
    }
    null
  }

  /** Local branch names (short), e.g. {@code ["main", "feature/x"]}. Empty when not a repo. */
  List<String> listLocalBranches() {
    branchList(List.of("branch", "--format=%(refname:short)"))
  }

  /**
   * Remote-tracking branch names, e.g. {@code ["origin/main", "origin/feature/x"]}, excluding
   * each remote's {@code <remote>/HEAD} pointer. Empty when not a repo.
   */
  List<String> listRemoteBranches() {
    branchList(List.of("branch", "-r", "--format=%(refname:short)")).findAll { !it.endsWith("/HEAD") }
  }

  private List<String> branchList(List<String> args) {
    if (!isGitRepo()) {
      return List.of()
    }
    GitResult result = runGit(args)
    if (!result.success || result.output == null) {
      return List.of()
    }
    result.output.readLines()
      .collect { it.trim() }
      .findAll { !it.isEmpty() }
  }

  boolean isGitRepo() {
    Path root = getProjectRoot()
    if (cachedRepoStatus != null && root == cachedRepoRoot) {
      return cachedRepoStatus.booleanValue()
    }
    synchronized (repoCheckLock) {
      if (cachedRepoStatus != null && root == cachedRepoRoot) {
        return cachedRepoStatus.booleanValue()
      }
      GitResult result = runGitNoCheck(List.of("rev-parse", "--is-inside-work-tree"))
      cachedRepoStatus = result.success && result.output?.toLowerCase()?.contains("true")
      cachedRepoRoot = root
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

  GitResult prDiff(int prNumber) {
    if (prNumber <= 0) {
      return new GitResult(false, false, 1, "", "PR number must be positive.")
    }
    runCommand(List.of("gh", "pr", "diff", String.valueOf(prNumber)))
  }

  GitResult prChangedFiles(int prNumber) {
    if (prNumber <= 0) {
      return new GitResult(false, false, 1, "", "PR number must be positive.")
    }
    runCommand(List.of("gh", "pr", "view", String.valueOf(prNumber), "--json", "files", "--jq", ".files[].path"))
  }

  GitResult prHeadCommit(int prNumber) {
    if (prNumber <= 0) {
      return new GitResult(false, false, 1, "", "PR number must be positive.")
    }
    runCommand(List.of("gh", "pr", "view", String.valueOf(prNumber), "--json", "headRefOid", "--jq", ".headRefOid"))
  }

  GitResult showFileAtCommit(String sha, String path) {
    runGit(List.of("show", "${sha}:${path}".toString()))
  }

  GitResult fetchPullRequestRef(int prNumber) {
    runGit(List.of("fetch", "origin", "refs/pull/${prNumber}/head".toString()))
  }

  /**
   * Open pull requests targeting the current branch, as raw {@code gh pr list --json} output in
   * {@link GitResult#output}. Use {@link #parsePullRequestJson} to turn that into structured data.
   *
   * <p>Cached for {@link #PR_CACHE_TTL_MILLIS} per branch — a real network call to GitHub's API,
   * and callers like the GUI header bar poll this after every chat turn, not just on a branch
   * switch, so an uncached call would hit GitHub far more often than the branch actually changes.
   */
  GitResult openPullRequestsForCurrentBranch() {
    String branch = currentBranch()
    if (branch == null || branch.trim().isEmpty()) {
      return new GitResult(false, isGitRepo(), 1, "",
        "Not a git repository or current branch could not be determined.")
    }
    synchronized (prCacheLock) {
      // Re-measure now here, inside the lock — not before acquiring it (mirrors
      // ModelRegistry.listModels()/loadedModels()): otherwise time spent waiting for a contended
      // lock wouldn't count against the TTL, and a caller could be served a result that's
      // actually already stale by the time it's returned.
      long now = System.currentTimeMillis()
      if (cachedPrResult != null && branch == cachedPrBranch && (now - cachedPrAt) < PR_CACHE_TTL_MILLIS) {
        return cachedPrResult
      }
      GitResult result = runCommand(List.of(
        "gh", "pr", "list",
        "--head", branch,
        "--state", "open",
        "--json", "number,title,url,headRefName,state"
      ))
      cachedPrResult = result
      cachedPrBranch = branch
      cachedPrAt = System.currentTimeMillis()
      result
    }
  }

  /** Parses {@code gh pr list --json ...} output into maps; empty on blank or unparsable input. */
  static List<Map> parsePullRequestJson(String output) {
    String trimmed = output != null ? output.trim() : ""
    if (trimmed.isEmpty()) {
      return List.of()
    }
    try {
      def parsed = new JsonSlurper().parseText(trimmed)
      parsed instanceof List ? (List<Map>) parsed : List.of()
    } catch (Exception e) {
      log.warn("Failed to parse gh pr list output as JSON: {}", e.message)
      List.of()
    }
  }

  GitResult listFiles() {
    runGit(List.of("ls-files", "--others", "--cached", "--exclude-standard"))
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

  private GitResult runCommand(List<String> command) {
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
    } catch (IOException e) {
      String msg = e.message ?: e.class.simpleName
      if (msg.toLowerCase().contains("no such file") || msg.toLowerCase().contains("cannot run program")) {
        String cmdName = command.isEmpty() ? "command" : command.get(0)
        if (cmdName == "gh") {
          return new GitResult(false, true, 1, "",
            "GitHub CLI (gh) is required for PR reviews. Install it from https://cli.github.com/")
        }
      }
      log.warn("Command failed: {}", command.join(" "), e)
      return new GitResult(false, true, 1, "", msg)
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt()
      log.warn("Command interrupted: {}", command.join(" "), e)
      return new GitResult(false, true, 1, "", e.message ?: e.class.simpleName)
    }
  }

  // Immutable, not just Canonical: openPullRequestsForCurrentBranch() now hands the same cached
  // instance to every caller within its TTL window, so it must not be mutable — a mutation by
  // one caller would otherwise corrupt what every other caller (and the cache itself) sees.
  @Immutable
  @CompileStatic
  static class GitResult {
    boolean success
    boolean repoPresent
    int exitCode
    String output
    String error
  }
}
