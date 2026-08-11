# Review Pipeline Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix three compounding `/review` reliability bugs — PR reviews reading stale local file content with no grounding instruction, no persisted context across a follow-up ("verify these findings"), and target-less follow-ups silently reviewing `AGENTS.md` instead of reporting nothing was specified — per `docs/superpowers/specs/2026-07-20-review-pipeline-reliability-design.md`.

**Architecture:** Read PR file content at the PR's head commit instead of the local tree; add a per-session "last review" cache keyed on target (paths or PR number) plus findings text; fence background guidance away from reviewable content in both prompt builders; add a review-specific grounding check (file-citation existence only, no ratio gate) and a line-number verifier, both wired into `/review`'s output but never blocking it.

**Tech Stack:** Groovy 5.0.7, Spring Boot, Spock 2.4-groovy-5.0, Maven (`./mvnw test` or `mvn test`).

## Global Constraints

- `@CompileStatic` on all new/modified classes and methods (per `AGENTS.md`).
- 2-space indentation, 120-char line length, British English in comments/docs.
- No new exception types anywhere in this design — reuse `GitResult(success, repoPresent, exitCode, output, error)`, `IllegalArgumentException` (via `FileEditingTool.readFile`), and the existing `ReviewSummary`/`SessionState` per-session-map patterns.
- Grounding/line-verification failures must never block a review (unlike `/implement`) — worst case is an annotated/warned finding.
- Every new/changed code path must keep `./mvnw test` green; run it after each task.
- **Out of scope, explicitly — do not touch:** `JLineRepl.groovy`, `GuiTurnController.groovy`, `IntentCommandRouter.groovy`, `IntentCommandMapper.groovy`. Two earlier revisions of the design proposed wiring a real `sessionId` through intent routing to "fix" cache bypass; tracing it fully showed the cache already works via `CommandExecutor.executeReview`'s existing `parsed.session as String ?: "default"` fallback, and the proposed change would have regressed first-time `/review` accuracy and silently activated a dormant heuristic for `/edit`. See design doc §0 for the full trail — no task in this plan should reopen it.

## File Structure

| File | Responsibility |
|---|---|
| `src/main/groovy/se/alipsa/lca/tools/GitTool.groovy` | Add `prHeadCommit`, `showFileAtCommit`, `fetchPullRequestRef` (Task 1). |
| `src/main/groovy/se/alipsa/lca/review/ReviewLineNumberVerifier.groovy` (new) | Pure static verifier: annotates findings whose cited line is out of range for content actually sent (Task 2). |
| `src/main/groovy/se/alipsa/lca/validation/ImplementationGroundingCheck.groovy` | Add `checkFileReferences(List<String>, Set<String>)` — review-specific, ratio-free file-existence check (Task 3). |
| `src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy` | New `ReviewPayload` type; PR-head-commit-accurate `buildPrReviewPayload`; `fileLineCounts`-populating `buildReviewPayload`/`appendFileContent`/`appendDirectoryContents` (Task 4); `review()`'s cache-reuse/grounding/line-verification restructuring (Task 9, capstone). |
| `src/main/groovy/se/alipsa/lca/shell/SessionState.groovy` | Add `ReviewContext` + `lastReviews` map + `recordReview`/`lastReview` (Task 5). |
| `src/main/groovy/se/alipsa/lca/agent/ReviewPromptBuilder.groovy` | Fence background guidance, add "quote the exact line" instruction, thread `previousFindings` (Task 6). |
| `src/main/groovy/se/alipsa/lca/agent/CodingAssistantAgent.groovy` | Fence `buildReviewPrompt`'s background guidance; thread `previousFindings` through `reviewCode`/`buildPrReviewPrompt` (Task 7). |
| `src/main/groovy/se/alipsa/lca/agent/ReviewRequest.groovy`, `ReviewAgent.groovy` | Add `previousFindings` field and thread it to `reviewCode` (Task 8). |

Corresponding Spock specs live at the mirrored `src/test/groovy/...` path for each file above, plus new `src/test/groovy/se/alipsa/lca/review/ReviewLineNumberVerifierSpec.groovy` and `src/test/groovy/se/alipsa/lca/agent/ReviewPromptBuilderSpec.groovy`.

---

## Task 1: `GitTool` — PR head-commit accuracy primitives

**Files:**
- Modify: `src/main/groovy/se/alipsa/lca/tools/GitTool.groovy` (add methods after `prChangedFiles`, ~line 298)
- Test: `src/test/groovy/se/alipsa/lca/tools/GitToolSpec.groovy` (add after the existing `"prChangedFiles rejects non-positive PR number"` test, ~line 343)

**Interfaces:**
- Produces: `GitResult prHeadCommit(int prNumber)`, `GitResult showFileAtCommit(String sha, String path)`, `GitResult fetchPullRequestRef(int prNumber)` — consumed by Task 4's rewritten `buildPrReviewPayload`.
- Consumes: existing `GitResult(boolean success, boolean repoPresent, int exitCode, String output, String error)`, existing private `runCommand(List<String>)` (used by `prDiff`/`prChangedFiles`) and `runGit(List<String>)` (used by `stagedDiff`) — reused, not modified.

- [ ] **Step 1: Write the failing tests**

Add to `GitToolSpec.groovy`, right after `"prChangedFiles rejects non-positive PR number"`:

```groovy
  def "prHeadCommit rejects non-positive PR number"() {
    when:
    def result = gitTool.prHeadCommit(0)

    then:
    !result.success
    result.error.contains("PR number must be positive")
  }

  def "prHeadCommit returns error when gh is not available"() {
    given:
    initRepo()

    when:
    def result = gitTool.prHeadCommit(999)

    then:
    !result.success
    result.error != null && !result.error.isEmpty()
  }

  def "showFileAtCommit returns file content at a given commit"() {
    given:
    initRepo()
    Path file = tempDir.resolve("sample.txt")
    Files.writeString(file, "original content\n")
    runGit("add", "sample.txt")
    runGit("commit", "-m", "init")
    String sha = runGitCapture("rev-parse", "HEAD").trim()
    Files.writeString(file, "changed content\n")

    when:
    def result = gitTool.showFileAtCommit(sha, "sample.txt")

    then:
    result.success
    result.output.contains("original content")
    !result.output.contains("changed content")
  }

  def "showFileAtCommit fails for a path that does not exist at that commit"() {
    given:
    initRepo()
    Files.writeString(tempDir.resolve("sample.txt"), "content\n")
    runGit("add", "sample.txt")
    runGit("commit", "-m", "init")
    String sha = runGitCapture("rev-parse", "HEAD").trim()

    when:
    def result = gitTool.showFileAtCommit(sha, "missing.txt")

    then:
    !result.success
  }

  def "fetchPullRequestRef reports failure for a nonexistent PR ref"() {
    given:
    initRepo()
    Files.writeString(tempDir.resolve("a.txt"), "hello\n")
    runGit("add", "a.txt")
    runGit("commit", "-m", "init")

    when:
    def result = gitTool.fetchPullRequestRef(999)

    then:
    !result.success
  }
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `./mvnw test -Dtest=GitToolSpec`
Expected: compilation failure — `prHeadCommit`/`showFileAtCommit`/`fetchPullRequestRef` do not exist on `GitTool` yet.

- [ ] **Step 3: Implement the three methods**

Add to `GitTool.groovy`, directly after `prChangedFiles(int prNumber)`:

```groovy
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
```

- [ ] **Step 4: Run to verify the tests pass**

Run: `./mvnw test -Dtest=GitToolSpec`
Expected: PASS (all `GitToolSpec` tests, including the 5 new ones).

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/tools/GitTool.groovy src/test/groovy/se/alipsa/lca/tools/GitToolSpec.groovy
git commit -m "feat(review): add GitTool primitives for reading PR content at head commit"
```

---

## Task 2: New `ReviewLineNumberVerifier`

**Files:**
- Create: `src/main/groovy/se/alipsa/lca/review/ReviewLineNumberVerifier.groovy`
- Create: `src/test/groovy/se/alipsa/lca/review/ReviewLineNumberVerifierSpec.groovy`

**Interfaces:**
- Consumes: existing `ReviewSummary(List<ReviewFinding> findings, List<String> tests, String raw)` and `ReviewFinding(ReviewSeverity severity, String file, Integer line, String comment)` from `ReviewParser.groovy` (unchanged, confirmed field order).
- Produces: `static ReviewSummary ReviewLineNumberVerifier.verify(ReviewSummary summary, Map<String, Integer> fileLineCounts)` — consumed by Task 9.

- [ ] **Step 1: Write the failing tests**

Create `ReviewLineNumberVerifierSpec.groovy`:

```groovy
package se.alipsa.lca.review

import spock.lang.Specification

class ReviewLineNumberVerifierSpec extends Specification {

  def "finding citing a real file and in-range line is left untouched"() {
    given:
    ReviewSummary summary = new ReviewSummary(
      [new ReviewFinding(ReviewSeverity.HIGH, "src/App.groovy", 10, "issue")],
      [],
      "raw"
    )

    when:
    ReviewSummary result = ReviewLineNumberVerifier.verify(summary, ["src/App.groovy": 42])

    then:
    result.findings[0].comment == "issue"
  }

  def "finding citing an out-of-range line is annotated UNVERIFIED"() {
    given:
    ReviewSummary summary = new ReviewSummary(
      [new ReviewFinding(ReviewSeverity.HIGH, "src/App.groovy", 100, "issue")],
      [],
      "raw"
    )

    when:
    ReviewSummary result = ReviewLineNumberVerifier.verify(summary, ["src/App.groovy": 42])

    then:
    result.findings[0].comment == "[UNVERIFIED] issue"
  }

  def "AppConfig.groovy does not match a Config.groovy key"() {
    given:
    ReviewSummary summary = new ReviewSummary(
      [new ReviewFinding(ReviewSeverity.HIGH, "AppConfig.groovy", 5, "issue")],
      [],
      "raw"
    )

    when:
    ReviewSummary result = ReviewLineNumberVerifier.verify(summary, ["Config.groovy": 3])

    then: "no fileLineCounts entry matches, so the finding is left alone, not misjudged wrong"
    result.findings[0].comment == "issue"
  }

  def "a bare filename citation matches a full relative path key"() {
    given:
    ReviewSummary summary = new ReviewSummary(
      [new ReviewFinding(ReviewSeverity.HIGH, "ReviewParser.groovy", 100, "issue")],
      [],
      "raw"
    )

    when:
    ReviewSummary result = ReviewLineNumberVerifier.verify(
      summary, ["src/main/groovy/se/alipsa/lca/review/ReviewParser.groovy": 42]
    )

    then:
    result.findings[0].comment == "[UNVERIFIED] issue"
  }

  def "empty fileLineCounts returns the summary unchanged"() {
    given:
    ReviewSummary summary = new ReviewSummary(
      [new ReviewFinding(ReviewSeverity.HIGH, "src/App.groovy", 999, "issue")],
      [],
      "raw"
    )

    when:
    ReviewSummary result = ReviewLineNumberVerifier.verify(summary, [:])

    then: "nothing was sent as file content; annotating everything would be a false-positive flood"
    result.is(summary)
  }

  def "a non-empty fileLineCounts missing the cited file leaves that finding untouched"() {
    given:
    ReviewSummary summary = new ReviewSummary(
      [new ReviewFinding(ReviewSeverity.HIGH, "Other.groovy", 999, "issue")],
      [],
      "raw"
    )

    when:
    ReviewSummary result = ReviewLineNumberVerifier.verify(summary, ["src/App.groovy": 42])

    then: "not sent in full is not evidence the finding is wrong — a budget-excluded PR file or a mixed --paths/--staged review produces exactly this shape"
    result.findings[0].comment == "issue"
  }

  def "a finding with file general or a null line is left untouched"() {
    given:
    ReviewSummary summary = new ReviewSummary(
      [
        new ReviewFinding(ReviewSeverity.LOW, "general", null, "general note"),
        new ReviewFinding(ReviewSeverity.MEDIUM, "src/App.groovy", null, "no line given")
      ],
      [],
      "raw"
    )

    when:
    ReviewSummary result = ReviewLineNumberVerifier.verify(summary, ["src/App.groovy": 42])

    then:
    result.findings[0].comment == "general note"
    result.findings[1].comment == "no line given"
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw test -Dtest=ReviewLineNumberVerifierSpec`
Expected: compilation failure — `ReviewLineNumberVerifier` does not exist.

- [ ] **Step 3: Implement `ReviewLineNumberVerifier`**

Create `ReviewLineNumberVerifier.groovy`:

```groovy
package se.alipsa.lca.review

import groovy.transform.CompileStatic

@CompileStatic
class ReviewLineNumberVerifier {

  static ReviewSummary verify(ReviewSummary summary, Map<String, Integer> fileLineCounts) {
    if (fileLineCounts == null || fileLineCounts.isEmpty()) {
      return summary
    }
    List<ReviewFinding> annotated = summary.findings.collect { ReviewFinding f ->
      if (f.file == null || f.file == "general" || f.line == null) {
        return f
      }
      String matchedKey = matchPath(f.file, fileLineCounts.keySet())
      if (matchedKey == null) {
        return f
      }
      Integer lineCount = fileLineCounts.get(matchedKey)
      boolean verified = f.line >= 1 && f.line <= lineCount
      verified ? f : new ReviewFinding(f.severity, f.file, f.line, "[UNVERIFIED] " + f.comment)
    }
    new ReviewSummary(annotated, summary.tests, summary.raw)
  }

  private static String matchPath(String citedFile, Set<String> knownPaths) {
    if (knownPaths.contains(citedFile)) {
      return citedFile
    }
    knownPaths.findAll { key ->
      key.endsWith("/" + citedFile) || citedFile.endsWith("/" + key)
    }.max { it.length() }
  }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw test -Dtest=ReviewLineNumberVerifierSpec`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/review/ReviewLineNumberVerifier.groovy src/test/groovy/se/alipsa/lca/review/ReviewLineNumberVerifierSpec.groovy
git commit -m "feat(review): add ReviewLineNumberVerifier to flag out-of-range line citations"
```

---

## Task 3: `ImplementationGroundingCheck.checkFileReferences`

**Files:**
- Modify: `src/main/groovy/se/alipsa/lca/validation/ImplementationGroundingCheck.groovy` (add method + 2 private helpers after `check(...)`/`scoreFileReferences(...)`, ~line 111; add `import java.nio.file.InvalidPathException` alongside existing `java.nio.file.Files`/`Path`/`Paths` imports)
- Test: `src/test/groovy/se/alipsa/lca/validation/ImplementationGroundingCheckSpec.groovy` (add after the existing 10 tests, using the file's `checker`/`tempDir` fixtures from `setup()`)

**Interfaces:**
- Produces: `GroundingResult checkFileReferences(List<String> citedFiles, Set<String> additionalKnownPaths)` — consumed by Task 9.
- Consumes/reuses unchanged: `FILE_REF_PATTERN`, `GroundingResult`, `GroundingLevel`, `determineLevel(List<String>)`, `projectRoot` field. Does **not** touch `check()`/`scoreFileReferences`/`FileReferenceScore` at all.

- [ ] **Step 1: Write the failing tests**

Add to `ImplementationGroundingCheckSpec.groovy` (uses the class's existing `checker`/`tempDir` fixtures, which already create `src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy` and `src/main/groovy/se/alipsa/lca/tools/ToolCallParser.groovy` in `setup()`):

```groovy
  def "checkFileReferences flags a citation for a file that does not exist"() {
    when:
    def result = checker.checkFileReferences(["Missing.groovy"], [] as Set)

    then:
    result.level == GroundingLevel.UNCERTAIN
    result.issues[0].contains("Missing.groovy")
  }

  def "checkFileReferences passes when all citations exist"() {
    when:
    def result = checker.checkFileReferences(
      ["src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy"], [] as Set
    )

    then:
    result.level == GroundingLevel.GROUNDED
  }

  def "checkFileReferences matches a bare filename against a known-paths entry"() {
    when:
    def result = checker.checkFileReferences(
      ["Foo.groovy"], ["src/main/groovy/se/alipsa/lca/newpkg/Foo.groovy"] as Set
    )

    then:
    result.level == GroundingLevel.GROUNDED
  }

  def "checkFileReferences flags one fabricated citation among several real ones, not gated by a ratio"() {
    when:
    def result = checker.checkFileReferences(
      [
        "src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy",
        "src/main/groovy/se/alipsa/lca/tools/ToolCallParser.groovy",
        "Fabricated.groovy"
      ],
      [] as Set
    )

    then: "unlike check()'s existingRatio() < 0.2 gate, any non-existing citation is flagged regardless of ratio"
    result.level == GroundingLevel.UNCERTAIN
    result.issues[0].contains("Fabricated.groovy")
    !result.issues[0].contains("ShellCommands.groovy")
  }

  def "checkFileReferences names a duplicate nonexistent citation once, not per occurrence"() {
    when:
    def result = checker.checkFileReferences(["Missing.groovy", "Missing.groovy"], [] as Set)

    then:
    result.issues.size() == 1
  }

  def "checkFileReferences ignores a prose citation that isn't shaped like a file path"() {
    when:
    def result = checker.checkFileReferences(["The error handling in review()"], [] as Set)

    then:
    result.level == GroundingLevel.GROUNDED
    result.issues.isEmpty()
  }

  def "checkFileReferences excludes an extension-less absolute path"() {
    when:
    def result = checker.checkFileReferences(["/etc/passwd"], [] as Set)

    then:
    result.level == GroundingLevel.GROUNDED
  }

  def "checkFileReferences excludes an absolute path with a recognised extension even if it exists on disk"() {
    given:
    Path absoluteFile = tempDir.resolve("Foo.groovy")
    Files.writeString(absoluteFile, "class Foo {}")

    when:
    def result = checker.checkFileReferences([absoluteFile.toString()], [] as Set)

    then: "excluded because it's absolute, not treated as found or as missing"
    result.level == GroundingLevel.GROUNDED
  }

  def "check(llmResponse, toolCalls) is entirely unaffected by the new method"() {
    given:
    String response = "I'll modify src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy to add the feature."
    def calls = [
      new ToolCall("replace", [
        "src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy", "old", "new"
      ])
    ]

    when:
    def result = checker.check(response, calls)

    then:
    result.level == GroundingLevel.GROUNDED
  }
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `./mvnw test -Dtest=ImplementationGroundingCheckSpec`
Expected: compilation failure — `checkFileReferences` does not exist yet.

- [ ] **Step 3: Implement `checkFileReferences`**

Add `import java.nio.file.InvalidPathException` near the top of `ImplementationGroundingCheck.groovy` (alongside the existing `Files`/`Path`/`Paths` imports), then add after `scoreFileReferences(...)`:

```groovy
  GroundingResult checkFileReferences(List<String> citedFiles, Set<String> additionalKnownPaths) {
    List<String> issues = []
    List<String> pathShaped = citedFiles.toUnique().findAll { String ref ->
      FILE_REF_PATTERN.matcher(ref).matches() && !Paths.get(ref).isAbsolute()
    }
    List<String> nonExisting = pathShaped.findAll { String ref -> !fileReferenceExists(ref, additionalKnownPaths) }
    if (!nonExisting.isEmpty()) {
      issues.add("Referenced file(s) not found in the project: ${nonExisting.join(', ')}".toString())
    }
    new GroundingResult(determineLevel(issues), issues)
  }

  private boolean fileReferenceExists(String ref, Set<String> knownPaths) {
    try {
      Files.exists(projectRoot.resolve(ref).normalize()) || isKnownPath(ref, knownPaths)
    } catch (InvalidPathException ex) {
      false
    }
  }

  private static boolean isKnownPath(String ref, Set<String> knownPaths) {
    knownPaths.any { it == ref || it.endsWith("/" + ref) || ref.endsWith("/" + it) }
  }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw test -Dtest=ImplementationGroundingCheckSpec`
Expected: PASS (all 19 tests: 10 existing + 9 new).

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/validation/ImplementationGroundingCheck.groovy src/test/groovy/se/alipsa/lca/validation/ImplementationGroundingCheckSpec.groovy
git commit -m "feat(review): add review-specific file-citation grounding check"
```

---

## Task 4: `ShellCommands.ReviewPayload` + PR-head-accurate payload builders

**Files:**
- Modify: `src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy`:
  - Add nested `ReviewPayload` type (near other nested types).
  - Replace `buildPrReviewPayload(int, GitTool.GitResult)` (currently lines 1761–1800).
  - Replace `buildReviewPayload(String, List<String>, boolean)` (currently lines 1658–1685).
  - Change `appendFileContent`/`appendDirectoryContents` signatures (currently lines 1687–1745) to accept a `Map<String, Integer> fileLineCounts` out-param.
  - Minimal touch to `review()` (lines ~774–811): change `String reviewPayload` to `ReviewPayload reviewPayload`, and `new ReviewRequest(prompt, reviewPayload, ...)` to `new ReviewRequest(prompt, reviewPayload.text, ...)`. **Do not** touch the cache/grounding/restructuring logic here — that's Task 9.
- Test: `src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy` — update the existing `"buildPrReviewPayload keeps files that fit within budget"` test (currently lines 1887–1942) and add 2 new tests for the head-commit fallback/retry behavior.

**Interfaces:**
- Produces: `ShellCommands.ReviewPayload(String text, Map<String, Integer> fileLineCounts, List<String> changedFiles)`; `ReviewPayload buildPrReviewPayload(int prNumber, GitTool.GitResult diffResult)` (unchanged visibility/nullability contract: `null` on empty diff); `private ReviewPayload buildReviewPayload(String code, List<String> paths, boolean staged)` (this task keeps its existing "always non-null, sentinel text on empty" contract — Task 9 changes it to return `null`). Both consumed by Task 9.
- Consumes: Task 1's `GitTool.prHeadCommit`/`showFileAtCommit`/`fetchPullRequestRef`; existing `GitTool.prChangedFiles`/`prDiff`; existing `FileEditingTool.readFile` (throws `IllegalArgumentException` on any failure).

- [ ] **Step 1: Write/update the failing tests**

In `ShellCommandsSpec.groovy`, replace the existing `"buildPrReviewPayload keeps files that fit within budget"` test body (keep the same test name) with:

```groovy
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
```

- [ ] **Step 2: Run to verify the tests fail**

Run: `./mvnw test -Dtest=ShellCommandsSpec#buildPrReviewPayload*`
Expected: compilation failure — `ShellCommands.ReviewPayload` does not exist, `GitTool.prHeadCommit`/`showFileAtCommit`/`fetchPullRequestRef` stubs reference unknown methods until Task 1 lands (Task 1 must be committed first).

- [ ] **Step 3: Add the `ReviewPayload` type**

Add inside `ShellCommands` class body (near other nested types):

```groovy
  @Canonical
  @CompileStatic
  static class ReviewPayload {
    String text
    Map<String, Integer> fileLineCounts
    List<String> changedFiles
  }
```

- [ ] **Step 4: Replace `buildPrReviewPayload`**

Replace the existing method (currently lines 1761–1800) in full:

```groovy
  ReviewPayload buildPrReviewPayload(int prNumber, GitTool.GitResult diffResult) {
    String diff = diffResult.output ?: ""
    if (diff.trim().isEmpty()) {
      return null
    }

    GitTool.GitResult filesResult = gitTool.prChangedFiles(prNumber)
    List<String> changedFiles = List.of()
    if (filesResult.success && filesResult.output) {
      changedFiles = filesResult.output.split("\\R").toList().findAll { it.trim() }
    }

    GitTool.GitResult headResult = gitTool.prHeadCommit(prNumber)
    // success=true with blank output is a real, observed shape (e.g. --jq finding nothing) — guard
    // on both, not just success, or showFileAtCommit("", path) silently resolves against the index.
    String headSha = (headResult.success && headResult.output?.trim()) ? headResult.output.trim() : null

    StringBuilder builder = new StringBuilder()
    Map<String, Integer> fileLineCounts = new LinkedHashMap<>()
    int budgetUsed = diff.length()
    boolean budgetExceeded = false
    boolean refFetched = false // fetch the PR ref at most once per call, not once per failing file

    for (String rawPath : changedFiles) {
      String filePath = rawPath.trim()
      String content = null
      boolean approximate = false

      if (headSha != null) {
        GitTool.GitResult shown = gitTool.showFileAtCommit(headSha, filePath)
        if (!shown.success && !refFetched) {
          gitTool.fetchPullRequestRef(prNumber)
          refFetched = true
          shown = gitTool.showFileAtCommit(headSha, filePath)
        }
        if (shown.success) {
          content = shown.output
        }
      }

      if (content == null) {
        // Unchanged from today: a file gone in both the PR head and the local tree (e.g. deleted in
        // the PR) is skipped, not fatal to the whole review.
        try {
          content = fileEditingTool.readFile(filePath)
          approximate = true
        } catch (Exception ex) {
          continue
        }
      }

      int fileSize = filePath.length() + content.length() + 20
      if (budgetUsed + fileSize > prContextBudget) {
        budgetExceeded = true
        break
      }
      String note = approximate ? "(approximate — local copy, not verified against PR head)\n" : ""
      builder.append("File: ").append(filePath).append("\n").append(note).append("```\n")
        .append(content).append("\n```\n\n")
      budgetUsed += fileSize
      fileLineCounts.put(filePath, content.stripTrailing().split("\\R").length)
    }

    if (budgetExceeded) {
      println("PR is large; some file contents excluded (context budget reached).")
    }

    builder.append("PR diff:\n```\n").append(diff).append("\n```\n")
    new ReviewPayload(builder.toString().trim(), fileLineCounts, changedFiles)
  }
```

- [ ] **Step 5: Replace `buildReviewPayload`, `appendFileContent`, `appendDirectoryContents`**

Replace the existing `buildReviewPayload` (currently lines 1658–1685):

```groovy
  private ReviewPayload buildReviewPayload(String code, List<String> paths, boolean staged) {
    StringBuilder builder = new StringBuilder()
    Map<String, Integer> fileLineCounts = new LinkedHashMap<>()
    if (code != null && code.trim()) {
      builder.append("User-provided code:\n```\n").append(code.trim()).append("\n```\n\n")
    }
    if (paths != null) {
      paths.each { String path ->
        if (path == null || path.trim().isEmpty()) {
          return
        }
        Path root = resolveProjectRoot(fileEditingTool)
        Path resolved = root.resolve(path.trim()).normalize()
        if (Files.isDirectory(resolved)) {
          appendDirectoryContents(builder, resolved, path.trim(), fileLineCounts)
        } else {
          appendFileContent(builder, path.trim(), fileLineCounts)
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
    new ReviewPayload(payload ? payload : "No additional context provided.", fileLineCounts, List.of())
  }
```

Note: this task deliberately keeps the existing sentinel-string-on-empty contract (just now wrapped in a `ReviewPayload`) — Task 9 changes this to return `null`, at the point where the caller is actually restructured to handle it.

Replace `appendFileContent` (currently lines 1687–1696ish):

```groovy
  private void appendFileContent(StringBuilder builder, String path, Map<String, Integer> fileLineCounts) {
    try {
      String content = fileEditingTool.readFile(path)
      builder.append("File: ").append(path).append("\n```\n")
        .append(content).append("\n```\n\n")
      fileLineCounts.put(path, content.stripTrailing().split("\\R").length)
    } catch (IllegalArgumentException ex) {
      builder.append("File: ").append(path).append(" (error: ").append(ex.message).append(")\n\n")
    }
  }
```

Update `appendDirectoryContents`'s signature to accept `Map<String, Integer> fileLineCounts`, and add one line inside its existing successful-read branch (keep everything else — file walking, sorting, budget check, error handling — identical to the current implementation):

```groovy
  private void appendDirectoryContents(
    StringBuilder builder, Path dirPath, String displayPath, Map<String, Integer> fileLineCounts
  ) {
    List<Path> sourceFiles = []
    try {
      Files.walkFileTree(dirPath, new java.nio.file.SimpleFileVisitor<Path>() {
        @Override
        java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
          String name = file.fileName.toString()
          for (String ext : SOURCE_EXTENSIONS) {
            if (name.endsWith(ext)) {
              sourceFiles.add(file)
              break
            }
          }
          java.nio.file.FileVisitResult.CONTINUE
        }
      })
      sourceFiles.sort { Path a, Path b ->
        int pa = filePriority(a)
        int pb = filePriority(b)
        pa != pb ? pa <=> pb : a <=> b
      }
    } catch (IOException ex) {
      builder.append("Directory: ").append(displayPath).append(" (error: ").append(ex.message).append(")\n\n")
      return
    }
    if (sourceFiles.isEmpty()) {
      builder.append("Directory: ").append(displayPath).append(" (no source files found)\n\n")
      return
    }
    int filesIncluded = 0
    for (Path file : sourceFiles) {
      if (builder.length() >= reviewContextBudget) {
        int skipped = sourceFiles.size() - filesIncluded
        builder.append("\n(${skipped} more file(s) skipped — context budget reached)\n")
        break
      }
      String relativePath = dirPath.parent != null
        ? dirPath.parent.relativize(file).toString()
        : file.fileName.toString()
      try {
        String content = Files.readString(file)
        builder.append("File: ").append(relativePath).append("\n```\n")
          .append(content).append("\n```\n\n")
        fileLineCounts.put(relativePath, content.stripTrailing().split("\\R").length)
        filesIncluded++
      } catch (IOException ex) {
        builder.append("File: ").append(relativePath).append(" (error: ").append(ex.message).append(")\n\n")
      }
    }
  }
```

- [ ] **Step 6: Minimal fix to `review()` so the file compiles**

In `review()`, change only the type declaration and the `ReviewRequest` construction — leave every other line (the `if (pr != null) {...} else {...}` branch shape, `isPrReview`, cache logic) exactly as-is for now:

```groovy
    ReviewPayload reviewPayload
    if (pr != null) {
      GitTool.GitResult diffResult = gitTool.prDiff(pr)
      if (!diffResult.success) {
        printProgressDone("Review")
        String error = diffResult.error ?: "Unknown error fetching PR diff."
        return "PR review failed: ${error}"
      }
      reviewPayload = buildPrReviewPayload(pr, diffResult)
      if (reviewPayload == null) {
        printProgressDone("Review")
        return "PR #${pr} has no diff content."
      }
    } else {
      List<String> effectivePaths = paths
      if ((effectivePaths == null || effectivePaths.isEmpty()) && prompt != null) {
        effectivePaths = extractPathsFromPrompt(prompt)
      }
      reviewPayload = buildReviewPayload(code, effectivePaths, staged)
    }
    ...
    boolean isPrReview = pr != null
    ReviewRequest request = new ReviewRequest(
      prompt, reviewPayload.text, reviewOptions, system, security, withThinking, isPrReview
    )
```

- [ ] **Step 7: Run to verify everything passes**

Run: `./mvnw test -Dtest=ShellCommandsSpec`
Expected: PASS (all existing tests continue to pass unchanged since `ReviewRequest.payload` is still a plain `String` with identical content; the 3 new/updated PR-payload tests pass).

- [ ] **Step 8: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy
git commit -m "feat(review): read PR file content at head commit, introduce ReviewPayload"
```

---

## Task 5: `SessionState.ReviewContext` + `lastReviews` cache

**Files:**
- Modify: `src/main/groovy/se/alipsa/lca/shell/SessionState.groovy`:
  - Add `private final Map<String, ReviewContext> lastReviews = new ConcurrentHashMap<>()` near `recentFilePaths` (~line 31).
  - Add `recordReview`/`lastReview` methods near `trackFilePath`/`getRecentFilePaths` (~lines 166–184).
  - Add nested `ReviewContext` static class near `ToolSummary`/`SessionSettings` (~lines 388–408).
- Test: `src/test/groovy/se/alipsa/lca/shell/SessionStateSpec.groovy` (uses the file's existing `state` fixture)

**Interfaces:**
- Produces: `SessionState.ReviewContext(List<String> paths, Integer prNumber, Map<String, Integer> fileLineCounts, String findingsText)`, `void recordReview(String sessionId, ReviewContext context)`, `ReviewContext lastReview(String sessionId)` — consumed by Task 9.

- [ ] **Step 1: Write the failing tests**

Add to `SessionStateSpec.groovy`:

```groovy
  def "lastReview returns null when nothing has been recorded"() {
    expect:
    state.lastReview("s1") == null
  }

  def "recordReview stores and lastReview retrieves the same context for a session"() {
    given:
    SessionState.ReviewContext context = new SessionState.ReviewContext(
      ["Foo.groovy"], null, ["Foo.groovy": 10], "findings text"
    )

    when:
    state.recordReview("s1", context)

    then:
    state.lastReview("s1") == context
  }

  def "recordReview overwrites the previous context for the same session"() {
    given:
    SessionState.ReviewContext first = new SessionState.ReviewContext(["Foo.groovy"], null, [:], "first")
    SessionState.ReviewContext second = new SessionState.ReviewContext([], 52, [:], "second")

    when:
    state.recordReview("s1", first)
    state.recordReview("s1", second)

    then:
    state.lastReview("s1") == second
  }

  def "recordReview and lastReview normalise a null session id to default"() {
    given:
    SessionState.ReviewContext context = new SessionState.ReviewContext([], 52, [:], "findings")

    when:
    state.recordReview(null, context)

    then:
    state.lastReview(null) == context
    state.lastReview("default") == context
  }

  def "reviews recorded under different sessions do not leak into each other"() {
    given:
    SessionState.ReviewContext a = new SessionState.ReviewContext(["A.groovy"], null, [:], "a")
    SessionState.ReviewContext b = new SessionState.ReviewContext(["B.groovy"], null, [:], "b")

    when:
    state.recordReview("session-a", a)
    state.recordReview("session-b", b)

    then:
    state.lastReview("session-a") == a
    state.lastReview("session-b") == b
  }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw test -Dtest=SessionStateSpec`
Expected: compilation failure — `SessionState.ReviewContext`/`recordReview`/`lastReview` do not exist.

- [ ] **Step 3: Implement**

Add the field near `recentFilePaths`:

```groovy
  private final Map<String, ReviewContext> lastReviews = new ConcurrentHashMap<>()
```

Add the methods near `trackFilePath`/`getRecentFilePaths`:

```groovy
  void recordReview(String sessionId, ReviewContext context) {
    lastReviews.put(sessionId ?: "default", context)
  }

  ReviewContext lastReview(String sessionId) {
    lastReviews.get(sessionId ?: "default")
  }
```

Add the nested type near `ToolSummary`/`SessionSettings`:

```groovy
  @Canonical
  @CompileStatic
  static class ReviewContext {
    List<String> paths        // empty when prNumber is set
    Integer prNumber          // null when paths is set
    Map<String, Integer> fileLineCounts
    String findingsText
  }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./mvnw test -Dtest=SessionStateSpec`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/shell/SessionState.groovy src/test/groovy/se/alipsa/lca/shell/SessionStateSpec.groovy
git commit -m "feat(review): add per-session last-review cache to SessionState"
```

---

## Task 6: `ReviewPromptBuilder` fencing + `previousFindings`

**Files:**
- Modify: `src/main/groovy/se/alipsa/lca/agent/ReviewPromptBuilder.groovy` (full rewrite — 62 lines today)
- Create: `src/test/groovy/se/alipsa/lca/agent/ReviewPromptBuilderSpec.groovy` (does not exist yet)

**Interfaces:**
- Produces: `static String buildPrReviewPrompt(String codeText, String userRequest, String systemPromptOverride, boolean securityFocus, String requestLabel, String previousFindings)` (new 6-arg overload) plus unchanged-signature 4-arg and 5-arg overloads that now delegate with `previousFindings = null` — consumed by Task 7.
- Consumes: nothing new. Must not change `TeamReviewerAgent.groovy:44-46`'s existing 5-arg call site behavior (verified in Step 4).

- [ ] **Step 1: Write the failing tests**

Create `ReviewPromptBuilderSpec.groovy`:

```groovy
package se.alipsa.lca.agent

import spock.lang.Specification

class ReviewPromptBuilderSpec extends Specification {

  def "buildPrReviewPrompt includes the quote-the-exact-line grounding instruction"() {
    when:
    String prompt = ReviewPromptBuilder.buildPrReviewPrompt("code", "request", null, false)

    then:
    prompt.contains("Quote the exact line you are citing")
  }

  def "the 4-arg overload delegates and produces the same output as the 6-arg overload"() {
    when:
    String fourArg = ReviewPromptBuilder.buildPrReviewPrompt("code", "request", null, false)
    String sixArg = ReviewPromptBuilder.buildPrReviewPrompt("code", "request", null, false, "User request", null)

    then:
    fourArg == sixArg
  }

  def "the 5-arg overload delegates with the given request label"() {
    when:
    String prompt = ReviewPromptBuilder.buildPrReviewPrompt("code", "summary", null, false, "Plan summary")

    then:
    prompt.contains("Plan summary:\nsummary")
    !prompt.contains("User request:")
  }

  def "previousFindings is included when present, directly above the request label"() {
    when:
    String prompt = ReviewPromptBuilder.buildPrReviewPrompt(
      "code", "verify these", null, false, "User request", "- [High] Foo.groovy:1 - issue"
    )

    then:
    prompt.contains("Previous findings to verify:\n- [High] Foo.groovy:1 - issue")
  }

  def "previousFindings is omitted entirely when null"() {
    when:
    String prompt = ReviewPromptBuilder.buildPrReviewPrompt("code", "request", null, false, "User request", null)

    then:
    !prompt.contains("Previous findings to verify")
  }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw test -Dtest=ReviewPromptBuilderSpec`
Expected: compilation failure — the 6-arg overload does not exist.

- [ ] **Step 3: Rewrite `ReviewPromptBuilder.groovy`**

```groovy
package se.alipsa.lca.agent

import groovy.transform.CompileStatic

@CompileStatic
class ReviewPromptBuilder {

  static String buildPrReviewPrompt(
    String codeText,
    String userRequest,
    String systemPromptOverride,
    boolean securityFocus
  ) {
    buildPrReviewPrompt(codeText, userRequest, systemPromptOverride, securityFocus, "User request", null)
  }

  static String buildPrReviewPrompt(
    String codeText,
    String userRequest,
    String systemPromptOverride,
    boolean securityFocus,
    String requestLabel
  ) {
    buildPrReviewPrompt(codeText, userRequest, systemPromptOverride, securityFocus, requestLabel, null)
  }

  static String buildPrReviewPrompt(
    String codeText,
    String userRequest,
    String systemPromptOverride,
    boolean securityFocus,
    String requestLabel,
    String previousFindings
  ) {
    String extraSystem = systemPromptOverride?.trim()
    """
You are a repository code reviewer.
Assess the proposal for correctness, repository fit, error handling, and testing strategy.
Ensure code follows project conventions (check AGENTS.md if present) and avoid deprecated APIs.
Reference likely target files or layers and call out missing test coverage.
Prioritize security flaws, unsafe file handling, missing validation, and unclear error paths.
${securityFocus ? "Focus on secrets, injection risks, auth bypasses, insecure defaults, and data exposure." : ""}
Format findings as bullet lines using: [Severity] file:line - comment (severity: High/Medium/Low; file may be 'general').
${extraSystem ? "Additional system guidance: ${extraSystem}\n" : ""}
This is a pull request review. Analyse the full diff and all changed files provided below.

Cross-file analysis:
- Trace data flow across changed files. If a method signature or column changes in one file, verify all callers/consumers are updated.
- Check constraint consistency: if a unique constraint changes, verify all queries that depend on uniqueness.
- Look for missing filters: if a table now requires a compound key, verify all lookups include all key columns.
- Identify silent failures: null returns that callers don't check, catch blocks that swallow errors, fallbacks that hide problems.

Report only issues found and suggested actions. Do not list strengths or summarise what the PR does well.
Do not limit your response length. Be thorough.

Quote the exact line you are citing (from the code or diff below) before asserting a finding against
it. If you cannot locate the line in the content provided, say so instead of guessing a line number.

Code to review:
${codeText}

${previousFindings ? "Previous findings to verify:\n${previousFindings}\n" : ""}${requestLabel}:
${userRequest}

Respond with sections:
Findings: bullet points starting with High/Medium/Low
Tests: list the specific tests or scenarios to validate
""".stripIndent().trim()
  }
}
```

- [ ] **Step 4: Run to verify it passes, and confirm the existing `TeamReviewerAgentSpec` regression**

Run: `./mvnw test -Dtest=ReviewPromptBuilderSpec`
Expected: PASS.

Run: `./mvnw test -Dtest=TeamReviewerAgentSpec`
Expected: PASS — specifically confirm `"review sends the plan summary under a Plan summary label, not User request"` still passes: it asserts `capturedPrompt.contains("Plan summary:\nRefactor plan summary text")` and `!capturedPrompt.contains("User request:")`, both still true since the 5-arg overload's `requestLabel` handling is unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/agent/ReviewPromptBuilder.groovy src/test/groovy/se/alipsa/lca/agent/ReviewPromptBuilderSpec.groovy
git commit -m "feat(review): add line-citation grounding instruction and previousFindings to ReviewPromptBuilder"
```

---

## Task 7: `CodingAssistantAgent` fencing + `previousFindings`

**Files:**
- Modify: `src/main/groovy/se/alipsa/lca/agent/CodingAssistantAgent.groovy`:
  - Rewrite `buildReviewPrompt` (currently lines 584–608).
  - Change `buildPrReviewPrompt` (currently lines 610–620) from 4 params to 5 (adds `previousFindings`).
  - Add a new 9-arg `reviewCode` overload; the existing 8-arg overload (currently lines 257–266) becomes a delegating wrapper.
- Test: `src/test/groovy/se/alipsa/lca/agent/CodingAssistantAgentSpec.groovy` — fix one existing literal-text assertion, add 5 new tests.

**Interfaces:**
- Consumes: Task 6's new 6-arg `ReviewPromptBuilder.buildPrReviewPrompt`.
- Produces: `reviewCode(UserInput, CodeSnippet, Ai, LlmOptions, String, RoleGoalBackstorySpec, boolean, boolean, String previousFindings)` (new 9-arg overload) — consumed by Task 8.

- [ ] **Step 1: Fix the existing literal-text assertion first (this will break under the new fencing regardless of TDD ordering)**

In `CodingAssistantAgentSpec.groovy`, in `"reviewCode enforces repository fit and testing considerations"` (currently lines 96–99), change:

```groovy
    1 * runner.generateText({
      it.contains("code reviewer") &&
      it.contains("Code to review:") &&
      it.contains("User request:")
    }) >> "High risk of errors in patch handling. Missing tests."
```
to:
```groovy
    1 * runner.generateText({
      it.contains("code reviewer") &&
      it.contains("===CODE TO REVIEW===") &&
      it.contains("User request:")
    }) >> "High risk of errors in patch handling. Missing tests."
```

- [ ] **Step 2: Write the new failing tests**

Add to `CodingAssistantAgentSpec.groovy`:

```groovy
  def "buildReviewPrompt fences background guidance and code separately"() {
    given:
    Ai ai = Mock(Ai)
    PromptRunner runner = Mock(PromptRunner)
    UserInput userInput = new UserInput("review this")
    def snippet = new CodingAssistantAgent.CodeSnippet("int x = 1")
    String capturedPrompt = null

    when:
    agent.reviewCode(userInput, snippet, ai, null, "project guidance", Personas.REVIEWER)

    then:
    1 * ai.withLlm(agent.reviewLlmOptions) >> runner
    1 * runner.withPromptContributor(Personas.REVIEWER) >> runner
    1 * runner.generateText(_) >> {
      String prompt -> capturedPrompt = prompt; "Findings:\n- Low general - nit\nTests:\n- test"
    }
    capturedPrompt.contains("===BACKGROUND GUIDANCE")
    capturedPrompt.contains("project guidance")
    capturedPrompt.contains("===END BACKGROUND GUIDANCE===")
    capturedPrompt.contains("===CODE TO REVIEW===")
  }

  def "buildReviewPrompt reports no review target instead of reviewing the background guidance when code is empty"() {
    given:
    Ai ai = Mock(Ai)
    PromptRunner runner = Mock(PromptRunner)
    UserInput userInput = new UserInput("review this")
    def snippet = new CodingAssistantAgent.CodeSnippet("")
    String capturedPrompt = null

    when:
    agent.reviewCode(userInput, snippet, ai, null, "project guidance", Personas.REVIEWER)

    then:
    1 * ai.withLlm(agent.reviewLlmOptions) >> runner
    1 * runner.withPromptContributor(Personas.REVIEWER) >> runner
    1 * runner.generateText(_) >> {
      String prompt -> capturedPrompt = prompt; "Findings:\n- Low general - nit\nTests:\n- test"
    }
    capturedPrompt.contains("No code was provided")
    !capturedPrompt.contains("===CODE TO REVIEW===")
  }

  def "a short but non-empty code snippet still appears inside the code section with the caution sentence"() {
    given:
    Ai ai = Mock(Ai)
    PromptRunner runner = Mock(PromptRunner)
    UserInput userInput = new UserInput("review this")
    def snippet = new CodingAssistantAgent.CodeSnippet("int x = arr[i + 1]")
    String capturedPrompt = null

    when:
    agent.reviewCode(userInput, snippet, ai)

    then:
    1 * ai.withLlm(agent.reviewLlmOptions) >> runner
    1 * runner.withPromptContributor(_) >> runner
    1 * runner.generateText(_) >> {
      String prompt -> capturedPrompt = prompt; "Findings:\n- Low general - nit\nTests:\n- test"
    }
    capturedPrompt.contains("===CODE TO REVIEW===\nint x = arr[i + 1]")
    capturedPrompt.contains("Only report findings you can verify from the code provided.")
  }

  def "reviewCode threads previousFindings into the PR-review prompt"() {
    given:
    Ai ai = Mock(Ai)
    PromptRunner runner = Mock(PromptRunner)
    UserInput userInput = new UserInput("verify these findings")
    def snippet = new CodingAssistantAgent.CodeSnippet("diff content")
    String capturedPrompt = null

    when:
    agent.reviewCode(
      userInput, snippet, ai, null, null, Personas.REVIEWER, false, true, "- [High] Foo.groovy:1 - stale finding"
    )

    then:
    1 * ai.withLlm(agent.reviewLlmOptions) >> runner
    1 * runner.withPromptContributor(_) >> runner
    1 * runner.generateText(_) >> {
      String prompt -> capturedPrompt = prompt; "Findings:\n- Low general - nit\nTests:\n- test"
    }
    capturedPrompt.contains("Previous findings to verify:\n- [High] Foo.groovy:1 - stale finding")
  }

  def "reviewCode's 8-arg overload still delegates with no previous findings"() {
    given:
    Ai ai = Mock(Ai)
    PromptRunner runner = Mock(PromptRunner)
    UserInput userInput = new UserInput("review PR")
    def snippet = new CodingAssistantAgent.CodeSnippet("diff content")
    String capturedPrompt = null

    when:
    agent.reviewCode(userInput, snippet, ai, null, null, Personas.REVIEWER, false, true)

    then:
    1 * ai.withLlm(agent.reviewLlmOptions) >> runner
    1 * runner.withPromptContributor(_) >> runner
    1 * runner.generateText(_) >> {
      String prompt -> capturedPrompt = prompt; "Findings:\n- Low general - nit\nTests:\n- test"
    }
    !capturedPrompt.contains("Previous findings to verify")
  }
```

- [ ] **Step 3: Run to verify the new tests fail**

Run: `./mvnw test -Dtest=CodingAssistantAgentSpec`
Expected: compilation failure — the 9-arg `reviewCode` overload does not exist.

- [ ] **Step 4: Rewrite `buildReviewPrompt`**

Replace the existing method (currently lines 584–608):

```groovy
  protected String buildReviewPrompt(
    UserInput userInput,
    CodeSnippet codeSnippet,
    String systemPromptOverride,
    RoleGoalBackstorySpec reviewerPersona
  ) {
    String extraSystem = systemPromptOverride?.trim()
    boolean securityFocus = reviewerPersona?.getRole() == "Security Reviewer"
    String codeText = codeSnippet?.text ?: ""
    boolean hasSpecificCode = codeText.trim().length() > 50
    boolean codeHasContent = codeText.trim().length() > 0
    """/no_think
You are a code reviewer. Review the code below and report findings directly.
${securityFocus ? "Focus on security risks: injection, auth bypasses, insecure defaults, data exposure." : ""}
Format each finding as: - [High/Medium/Low] file:line - description
${extraSystem ? "===BACKGROUND GUIDANCE (project conventions — do not review this section as code)===\n${extraSystem}\n===END BACKGROUND GUIDANCE===\n" : ""}
${codeHasContent
    ? "===CODE TO REVIEW===\n${codeText}\n===END CODE TO REVIEW===\n" +
      (!hasSpecificCode ? "Only report findings you can verify from the code provided. Do not guess about code you cannot see.\n" : "")
    : "No code was provided. Do not review the background guidance above — report that no review target was specified."}

User request:
${userInput.getContent()}

Findings:
""".stripIndent().trim()
  }
```

- [ ] **Step 5: Change `buildPrReviewPrompt` to accept `previousFindings`**

Replace the existing method (currently lines 610–620):

```groovy
  protected String buildPrReviewPrompt(
    UserInput userInput,
    CodeSnippet codeSnippet,
    String systemPromptOverride,
    RoleGoalBackstorySpec reviewerPersona,
    String previousFindings
  ) {
    boolean securityFocus = reviewerPersona?.getRole() == "Security Reviewer"
    ReviewPromptBuilder.buildPrReviewPrompt(
      codeSnippet?.text ?: "", userInput.getContent(), systemPromptOverride, securityFocus,
      "User request", previousFindings
    )
  }
```

- [ ] **Step 6: Add the new `reviewCode` overload; make the existing 8-arg one delegate**

Replace the existing 8-arg overload (currently lines 257–266, the terminal implementation) with a delegating wrapper, and add the new terminal 9-arg implementation:

```groovy
  ReviewedCodeSnippet reviewCode(
    UserInput userInput,
    CodeSnippet codeSnippet,
    Ai ai,
    LlmOptions llmOverride,
    String systemPromptOverride,
    RoleGoalBackstorySpec reviewerPersona,
    boolean withThinking,
    boolean prReview
  ) {
    reviewCode(
      userInput, codeSnippet, ai, llmOverride, systemPromptOverride, reviewerPersona, withThinking, prReview, null
    )
  }

  ReviewedCodeSnippet reviewCode(
    UserInput userInput,
    CodeSnippet codeSnippet,
    Ai ai,
    LlmOptions llmOverride,
    String systemPromptOverride,
    RoleGoalBackstorySpec reviewerPersona,
    boolean withThinking,
    boolean prReview,
    String previousFindings
  ) {
    Objects.requireNonNull(ai, "Ai must not be null")
    LlmOptions options = llmOverride ?: reviewLlmOptions
    RoleGoalBackstorySpec reviewer = reviewerPersona ?: Personas.REVIEWER
    String reviewPrompt = prReview
      ? buildPrReviewPrompt(userInput, codeSnippet, systemPromptOverride, reviewer, previousFindings)
      : buildReviewPrompt(userInput, codeSnippet, systemPromptOverride, reviewer)

    String review
    String reasoning = null

    def promptRunner = ai.withLlm(options).withPromptContributor(reviewer)

    if (withThinking && promptRunner.supportsThinking()) {
      ThinkingResponse<String> response = promptRunner
        .thinking()
        .generateText(reviewPrompt)
      review = response.getResult()
      if (response.hasThinking()) {
        reasoning = response.getThinkingContent()
      }
    } else {
      review = promptRunner.generateText(reviewPrompt)
    }

    String formattedReview = enforceReviewFormat(review, prReview)
    new ReviewedCodeSnippet(codeSnippet, formattedReview, reviewer, reasoning)
  }
```

- [ ] **Step 7: Run to verify everything passes**

Run: `./mvnw test -Dtest=CodingAssistantAgentSpec`
Expected: PASS (all existing tests, including the corrected literal-text one, plus the 5 new tests).

- [ ] **Step 8: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/agent/CodingAssistantAgent.groovy src/test/groovy/se/alipsa/lca/agent/CodingAssistantAgentSpec.groovy
git commit -m "feat(review): fence background guidance in buildReviewPrompt, thread previousFindings"
```

---

## Task 8: `ReviewRequest`/`ReviewAgent` plumbing

**Files:**
- Modify: `src/main/groovy/se/alipsa/lca/agent/ReviewRequest.groovy` (add field)
- Modify: `src/main/groovy/se/alipsa/lca/agent/ReviewAgent.groovy` (thread field through)
- Test: `src/test/groovy/se/alipsa/lca/agent/ReviewAgentSpec.groovy` (update existing mock arity, add 1 new test)

**Interfaces:**
- Produces: `ReviewRequest.previousFindings` field (default `null`); `ReviewAgent.review(ReviewRequest, Ai)` now calls the 9-arg `reviewCode` overload — consumed by Task 9 (`ShellCommands` constructs `ReviewRequest` with 8 positional args including `previousFindings`).
- Consumes: Task 7's new 9-arg `CodingAssistantAgent.reviewCode`.

- [ ] **Step 1: Write the failing test and update the existing one**

In `ReviewAgentSpec.groovy`, update the existing test's mock interaction from 8-arg to 9-arg:

```groovy
    1 * assistant.reviewCode(
      _,
      _,
      _ as Ai,
      _ as LlmOptions,
      "system",
      _,
      false,
      false,
      null
    ) >> { userInput, snippet, aiArg, options, system, persona, withThinking, prReview, previousFindings ->
      capturedOptions = options as LlmOptions
      capturedPersona = persona
      reviewed
    }
```

Add a new test to the same file:

```groovy
  def "review threads previousFindings from the request through to reviewCode"() {
    given:
    CodingAssistantAgent assistant = Mock()
    ReviewAgent agent = new ReviewAgent(assistant)
    ReviewRequest request = new ReviewRequest(
      "verify these findings", "PR diff payload", LlmOptions.withModel("m"), "system", true, false, true,
      "- [High] Foo.groovy:1 - stale finding"
    )
    def reviewed = new CodingAssistantAgent.ReviewedCodeSnippet(
      new CodingAssistantAgent.CodeSnippet("PR diff payload"),
      "Findings:\n- [High] general - issue\nTests:\n- test",
      Personas.SECURITY_REVIEWER,
      null
    )
    def capturedPreviousFindings = null
    1 * assistant.reviewCode(
      _, _, _ as Ai, _ as LlmOptions, "system", _, false, true, "- [High] Foo.groovy:1 - stale finding"
    ) >> { args -> capturedPreviousFindings = args[8]; reviewed }

    when:
    agent.review(request, Stub(Ai))

    then:
    capturedPreviousFindings == "- [High] Foo.groovy:1 - stale finding"
  }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw test -Dtest=ReviewAgentSpec`
Expected: compilation failure — `ReviewRequest` has no 8-field constructor, `ReviewAgent.review` still calls the 8-arg `reviewCode`.

- [ ] **Step 3: Add the field to `ReviewRequest`**

```groovy
package se.alipsa.lca.agent

import com.embabel.common.ai.model.LlmOptions
import groovy.transform.Canonical
import groovy.transform.CompileStatic

@Canonical
@CompileStatic
class ReviewRequest {
  String prompt
  String payload
  LlmOptions options
  String systemPrompt
  boolean security
  boolean withThinking = false
  boolean prReview = false
  String previousFindings = null
}
```

- [ ] **Step 4: Thread it through `ReviewAgent.review`**

```groovy
  @AchievesGoal(description = "Review code based on a structured review request")
  @Action(canRerun = true, trigger = ReviewRequest)
  ReviewResponse review(ReviewRequest request, Ai ai) {
    Objects.requireNonNull(request, "request must not be null")
    Objects.requireNonNull(ai, "ai must not be null")
    def persona = request.security ? Personas.SECURITY_REVIEWER : Personas.REVIEWER
    def reviewed = codingAssistantAgent.reviewCode(
      new UserInput(request.prompt),
      new CodeSnippet(request.payload),
      ai,
      request.options,
      request.systemPrompt,
      persona,
      request.withThinking,
      request.prReview,
      request.previousFindings
    )
    new ReviewResponse(reviewed.review, reviewed.reasoning)
  }
```

- [ ] **Step 5: Run to verify it passes**

Run: `./mvnw test -Dtest=ReviewAgentSpec`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/agent/ReviewRequest.groovy src/main/groovy/se/alipsa/lca/agent/ReviewAgent.groovy src/test/groovy/se/alipsa/lca/agent/ReviewAgentSpec.groovy
git commit -m "feat(review): add previousFindings to ReviewRequest and thread through ReviewAgent"
```

---

## Task 9 (capstone): `ShellCommands.review()` — cache reuse, grounding, line verification

**Files:**
- Modify: `src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy`:
  - Change `buildReviewPayload`'s empty-input contract from sentinel string to `null` (finishing what Task 4 deferred).
  - Add `PrPayloadOutcome` nested type and `resolvePrReviewPayload(int)` helper.
  - Replace the `if (pr != null) {...} else {...}` block and `boolean isPrReview = pr != null` in `review()` with the `resolvedPr`/`effectivePaths` hoist, cache-reuse fallback, and grounding/line-verification wiring.
  - Switch `buildSastBlock(sast, paths)` and `writeReviewLog(prompt, summary, paths, ...)` to use `effectivePaths`.
- Test: `src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy` — add the tests below.

**Interfaces:**
- Consumes: Task 2's `ReviewLineNumberVerifier.verify`, Task 3's `checkFileReferences`, Task 4's `ReviewPayload`, Task 5's `SessionState.ReviewContext`/`recordReview`/`lastReview`, Task 8's `ReviewRequest.previousFindings`.
- Produces: the fully working `/review` command described by the design doc.

- [ ] **Step 1: Write the failing tests**

Add to `ShellCommandsSpec.groovy`:

```groovy
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

  def "a --staged-only review does not write a cache entry, so a later target-less follow-up fails fast instead of NPEing"() {
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
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `./mvnw test -Dtest=ShellCommandsSpec`
Expected: mix of compilation failures (`sessionState.lastReview`/`recordReview` not yet wired into `review()`) and assertion failures against the current, unrestructured `review()` logic.

- [ ] **Step 3: Change `buildReviewPayload`'s empty-input contract to `null`**

Change the last line of `buildReviewPayload` (from Task 4's version):

```groovy
    String payload = builder.toString().trim()
    if (!payload) {
      return null
    }
    new ReviewPayload(payload, fileLineCounts, List.of())
```

- [ ] **Step 4: Add `PrPayloadOutcome` and `resolvePrReviewPayload`**

```groovy
  @Canonical
  @CompileStatic
  private static class PrPayloadOutcome {
    ReviewPayload payload
    String error
  }

  private PrPayloadOutcome resolvePrReviewPayload(int prNumber) {
    GitTool.GitResult diffResult = gitTool.prDiff(prNumber)
    if (!diffResult.success) {
      return new PrPayloadOutcome(null, "PR review failed: ${diffResult.error ?: 'Unknown error fetching PR diff.'}")
    }
    ReviewPayload payload = buildPrReviewPayload(prNumber, diffResult)
    if (payload == null) {
      return new PrPayloadOutcome(null, "PR #${prNumber} has no diff content.")
    }
    new PrPayloadOutcome(payload, null)
  }
```

- [ ] **Step 5: Replace the target-resolution block in `review()`**

Replace the `if (pr != null) {...} else {...}` block and the `boolean isPrReview = pr != null` line with:

```groovy
    Integer resolvedPr = pr
    List<String> effectivePaths = Collections.<String>emptyList()
    String previousFindings = null
    ReviewPayload reviewPayload

    if (resolvedPr != null) {
      PrPayloadOutcome outcome = resolvePrReviewPayload(resolvedPr)
      if (outcome.error != null) {
        printProgressDone("Review")
        return outcome.error
      }
      reviewPayload = outcome.payload
    } else {
      effectivePaths = paths ?: Collections.<String>emptyList()
      if (effectivePaths.isEmpty() && prompt != null) {
        effectivePaths = extractPathsFromPrompt(prompt)
      }
      reviewPayload = buildReviewPayload(code, effectivePaths, staged)
      if (reviewPayload == null) {
        if (staged) {
          printProgressDone("Review")
          return "Nothing staged to review — git add your changes first, or drop --staged."
        }
        if (code != null && !code.isEmpty()) {
          printProgressDone("Review")
          return "No code provided to review — pass --code with content, or drop --code."
        }
        SessionState.ReviewContext cached = sessionState.lastReview(session)
        if (cached == null) {
          printProgressDone("Review")
          return "No prior review to verify, and no file/PR specified — what would you like me to review?"
        }
        previousFindings = cached.findingsText
        if (cached.prNumber != null) {
          resolvedPr = cached.prNumber
          PrPayloadOutcome outcome = resolvePrReviewPayload(resolvedPr)
          if (outcome.error != null) {
            printProgressDone("Review")
            return "Reusing your previous review target (PR #${resolvedPr}): ${outcome.error}"
          }
          reviewPayload = outcome.payload
        } else {
          effectivePaths = cached.paths
          reviewPayload = buildReviewPayload(code, effectivePaths, staged)
        }
      }
    }
    boolean isPrReview = resolvedPr != null
```

- [ ] **Step 6: Update the `ReviewRequest` construction and add grounding + line verification**

```groovy
    Agent agent = resolveAgent(REVIEW_AGENT_NAME)
    if (agent == null) {
      printProgressDone("Review")
      return "Review agent unavailable; ensure Embabel agents are enabled."
    }
    println("Analyzing code with ${settings.model}${security ? ' (security focus)' : ''}${withThinking ? ' (with reasoning)' : ''}...")
    ReviewRequest request = new ReviewRequest(
      prompt, reviewPayload.text, reviewOptions, system, security, withThinking, isPrReview, previousFindings
    )
    ReviewResponse response = runAgent(agent, session, ReviewResponse, request)
    printProgressDone("Review")
    String reviewText = response?.review
    if (reviewText == null || reviewText.trim().isEmpty()) {
      return "No review response generated."
    }
    ReviewSummary summary = ReviewParser.parse(reviewText)
    String groundingWarningBlock = ""
    if (groundingCheck != null) {
      // Optional bean, same as /implement's existing guard — calling it unguarded NPEs whenever the
      // bean is absent. Cited files come from the already-parsed findings, not raw reviewText — the
      // Tests: section would otherwise read as a wall of nonexistent-file citations.
      List<String> citedFiles = summary.findings.collect { it.file }.findAll { it && it != "general" }
      Set<String> knownPaths = isPrReview ? new HashSet<>(reviewPayload.changedFiles) : Set.<String>of()
      def grounding = groundingCheck.checkFileReferences(citedFiles, knownPaths)
      if (grounding.level != se.alipsa.lca.validation.ImplementationGroundingCheck.GroundingLevel.GROUNDED) {
        groundingWarningBlock = "\n\n" + formatGroundingWarning(grounding)
      }
    }
    summary = ReviewLineNumberVerifier.verify(summary, reviewPayload.fileLineCounts)
    String rendered = renderReview(summary, severityThreshold, !noColor)
    String sastBlock = buildSastBlock(sast, effectivePaths)
    if (resolution.fallbackUsed) {
      rendered = "Note: using fallback model ${resolution.chosen}.\n" + rendered
    }

    boolean isGeneralReview = (code == null || code.trim().isEmpty()) && !staged
    String verificationNote = ""
    if (isGeneralReview && summary != null && !summary.findings.isEmpty()) {
      boolean hasHighSeverity = summary.findings.any { it.severity == ReviewSeverity.HIGH }
      if (hasHighSeverity) {
        verificationNote = "\n\nNote: This review analyzed the codebase conceptually. Please verify High severity findings by checking the actual code files."
      }
    }

    String reasoningSection = ""
    if (withThinking && response?.reasoning != null && !response.reasoning.trim().isEmpty()) {
      reasoningSection = "\n\n=== Reasoning Process ===\n" + response.reasoning
    }

    String output = formatSection(
      "Review", rendered + sastBlock + verificationNote + reasoningSection + groundingWarningBlock
    )
    sessionState.appendHistory(session, "User review request: ${prompt}", "Review: ${output}")
    if (isPrReview || !effectivePaths.isEmpty()) {
      sessionState.recordReview(session, new SessionState.ReviewContext(
        effectivePaths, resolvedPr, reviewPayload.fileLineCounts, summary.raw
      ))
    }
    if (logReview) {
      writeReviewLog(prompt, summary, effectivePaths, staged, severityThreshold)
    }
    output
```

- [ ] **Step 7: Run to verify everything passes**

Run: `./mvnw test -Dtest=ShellCommandsSpec`
Expected: PASS — all existing `/review` tests plus the ~11 new tests.

Then run the full suite:

Run: `./mvnw test`
Expected: PASS across the whole project.

- [ ] **Step 8: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy
git commit -m "feat(review): cache-reuse follow-ups, wire grounding check and line verification"
```

---

## Self-Review

**Spec coverage.** Design doc §0 (intent routing) → explicitly excluded, documented under Global Constraints, no task touches it. §1 (`GitTool`) → Task 1. §2 (`ShellCommands` payload builders + `review()` restructuring) → Tasks 4 and 9. §3 (`SessionState`) → Task 5. §4 (`CodingAssistantAgent`) → Task 7. §5 (`ReviewPromptBuilder`) → Task 6. §6 (`ReviewRequest`/`ReviewAgent`) → Task 8. §7 (`ReviewLineNumberVerifier`) → Task 2. §8 (`ImplementationGroundingCheck`) → Task 3. Every bullet in the design's "Testing" section maps to a concrete test above; the compile-break notes (stub extension, `ReviewPayload` type qualification, `code != null` vs `!code.isEmpty()` discrimination) are each called out at their respective task/step.

**Placeholder scan.** No "TBD"/"add appropriate error handling"/"similar to Task N" phrasing anywhere above — every step includes real, complete Groovy or Spock code, and every "before" snippet was read directly from the current source rather than assumed.

**Type/signature consistency.** `ReviewPayload(text, fileLineCounts, changedFiles)` is defined once in Task 4 and used identically (field access only, no reshaping) in Task 9. `SessionState.ReviewContext(paths, prNumber, fileLineCounts, findingsText)` is defined once in Task 5 and constructed identically in Task 9. `reviewCode`'s arity grows 8→9 in Task 7 and is called with all 9 args from Task 8's `ReviewAgent`; `ReviewRequest`'s arity grows 7→8 in Task 8 and is constructed with all 8 args in Task 9. `ReviewPromptBuilder.buildPrReviewPrompt`'s arity grows via a new 6-arg overload in Task 6, called from `CodingAssistantAgent.buildPrReviewPrompt` (Task 7) with all 6 args.

## Execution Handoff

Plan complete and saved to `docs/review-pipeline-reliability-plan.md`. Two execution options:

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration. Tasks 1, 2, 3, 5, 6 have no inter-dependencies and can run in parallel; Tasks 4, 7, 8 must follow their dependencies (4→1, 7→6, 8→7); Task 9 must run last, after 4, 5, 6, 7, 8 are all committed.

**2. Inline Execution** — execute tasks in this session using `executing-plans`, batch execution with checkpoints.

Which approach?
