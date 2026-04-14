# Improve PR Review Quality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix three issues causing empty/poor PR review output: context budget dropping all file contents, parser silently discarding non-conforming findings, and no raw fallback display.

**Architecture:** Three focused changes: (1) keep partial file contents when budget exceeded, (2) make ReviewParser more lenient with LLM output variations, (3) show raw LLM response when structured parsing yields nothing.

**Tech Stack:** Groovy 5.0.3, Spock 2.4, Spring Boot

---

### Task 1: Keep files that fit within context budget

The current `buildPrReviewPayload` in `ShellCommands` clears ALL loaded file contents when any single file exceeds the budget. Fix: just `break` out of the loop, keeping files already loaded.

**Files:**
- Modify: `src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy:1496-1516`
- Test: `src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy`

- [ ] **Step 1: Write the failing test**

Add a test that verifies partial file contents are kept when the budget is exceeded partway through. The test needs a `ShellCommands` instance with a small `prContextBudget`. Mock `gitTool.prDiff()` and `gitTool.prChangedFiles()` to return known data, and mock `fileEditingTool.readFile()` to return file contents where the first file fits but the second doesn't.

Since `buildPrReviewPayload` is private, test it indirectly through the `review` command, or use Groovy's ability to call private methods. The simplest approach: extract the method to be package-private for testability.

First, in `ShellCommands.groovy:1480`, change `private String buildPrReviewPayload` to `String buildPrReviewPayload` (package-private).

Then add the test in `ShellCommandsSpec.groovy`:

```groovy
def "buildPrReviewPayload keeps files that fit within budget"() {
  given:
  String diff = "diff content"
  GitTool.GitResult diffResult = new GitTool.GitResult(true, diff, null, true)
  gitTool.prChangedFiles(1) >> new GitTool.GitResult(true, "small.groovy\nlarge.groovy", null, true)
  fileEditingTool.readFile("small.groovy") >> "small content"
  fileEditingTool.readFile("large.groovy") >> "x" * 90000

  when:
  String payload = shellCommands.buildPrReviewPayload(1, diffResult)

  then:
  payload.contains("File: small.groovy")
  payload.contains("small content")
  !payload.contains("File: large.groovy")
  payload.contains("PR diff:")
  payload.contains("diff content")
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl . -Dtest="ShellCommandsSpec#buildPrReviewPayload keeps files that fit within budget" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — method is private, or the payload currently clears all files.

- [ ] **Step 3: Implement the fix**

In `ShellCommands.groovy`, make two changes:

1. Line 1480: Change `private String buildPrReviewPayload` to `String buildPrReviewPayload`.

2. Lines 1513-1516: Replace the budget-exceeded block:

```groovy
// BEFORE:
if (budgetExceeded) {
  println("PR is large; reviewing diff only (file contents exceeded context budget).")
  builder.setLength(0)
}

// AFTER:
if (budgetExceeded) {
  println("PR is large; some file contents excluded (context budget reached).")
}
```

This keeps the files already appended to `builder` and only skips the remaining ones.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -pl . -Dtest="ShellCommandsSpec#buildPrReviewPayload keeps files that fit within budget" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 5: Run all tests**

Run: `./mvnw test`
Expected: All 428+ tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy
git commit -m "fix: keep partial file contents when PR context budget exceeded"
```

---

### Task 2: Make ReviewParser more lenient

The `parseFinding` regex requires exactly `[High|Medium|Low] file:line - comment`. LLMs often produce variations like `**[High]**`, `[high]`, missing line numbers without the colon, or extra whitespace. Make the regex case-insensitive and tolerate markdown formatting.

**Files:**
- Modify: `src/main/groovy/se/alipsa/lca/review/ReviewParser.groovy:42-53`
- Test: `src/test/groovy/se/alipsa/lca/review/ReviewParserSpec.groovy`

- [ ] **Step 1: Write failing tests for format variations**

Add these test cases to `ReviewParserSpec.groovy`:

```groovy
def "parses findings with markdown bold severity"() {
  given:
  String review = """\
Findings:
- **[High]** src/App.groovy:42 - Null check missing
Tests:
- check nulls
"""

  when:
  ReviewSummary summary = ReviewParser.parse(review)

  then:
  summary.findings.size() == 1
  summary.findings[0].severity == ReviewSeverity.HIGH
  summary.findings[0].file == "src/App.groovy"
  summary.findings[0].line == 42
  summary.findings[0].comment == "Null check missing"
}

def "parses findings with lowercase severity"() {
  given:
  String review = """\
Findings:
- [high] src/App.groovy:10 - Missing validation
Tests:
- validate inputs
"""

  when:
  ReviewSummary summary = ReviewParser.parse(review)

  then:
  summary.findings.size() == 1
  summary.findings[0].severity == ReviewSeverity.HIGH
}

def "parses findings without line number"() {
  given:
  String review = """\
Findings:
- [Medium] src/App.groovy - Consider refactoring
Tests:
- refactor test
"""

  when:
  ReviewSummary summary = ReviewParser.parse(review)

  then:
  summary.findings.size() == 1
  summary.findings[0].severity == ReviewSeverity.MEDIUM
  summary.findings[0].file == "src/App.groovy"
  summary.findings[0].line == null
  summary.findings[0].comment == "Consider refactoring"
}

def "parses unstructured finding as Low severity"() {
  given:
  String review = """\
Findings:
- The error handling in Service.groovy could be improved
Tests:
- test errors
"""

  when:
  ReviewSummary summary = ReviewParser.parse(review)

  then:
  summary.findings.size() == 1
  summary.findings[0].severity == ReviewSeverity.LOW
  summary.findings[0].file == "general"
  summary.findings[0].comment == "The error handling in Service.groovy could be improved"
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl . -Dtest="ReviewParserSpec" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — markdown bold, lowercase, and unstructured findings not parsed.

- [ ] **Step 3: Implement lenient parsing**

Replace the `parseFinding` method in `ReviewParser.groovy:42-53`:

```groovy
private static ReviewFinding parseFinding(String line) {
  // Strip markdown bold markers
  String cleaned = line.replaceAll(/\*\*/, '')
  // Expected format: [Severity] path:line - comment (case-insensitive)
  def matcher = cleaned =~ /(?i)^\[(High|Medium|Low)\]\s+([^:\n]+?)(?::(\d+))?\s*-\s*(.+)$/
  if (matcher.matches()) {
    ReviewSeverity severity = ReviewSeverity.valueOf(matcher.group(1).toUpperCase())
    String file = matcher.group(2).trim()
    Integer lineNumber = matcher.group(3) ? Integer.valueOf(matcher.group(3)) : null
    String comment = matcher.group(4).trim()
    return new ReviewFinding(severity, file, lineNumber, comment)
  }
  // Fallback: treat unstructured bullet as Low/general (skip noise like "None", "N/A")
  String trimmed = line.trim()
  if (trimmed && !(trimmed =~ /(?i)^(none|n\/?a|no issues?|no findings?)\.?$/)) {
    return new ReviewFinding(ReviewSeverity.LOW, "general", null, trimmed)
  }
  null
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl . -Dtest="ReviewParserSpec" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS — all existing and new tests pass.

- [ ] **Step 5: Run all tests**

Run: `./mvnw test`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/review/ReviewParser.groovy src/test/groovy/se/alipsa/lca/review/ReviewParserSpec.groovy
git commit -m "fix: make ReviewParser lenient with LLM output variations"
```

---

### Task 3: Show raw LLM response when structured parsing yields no findings

When the parser produces zero structured findings but the LLM did return text, show the raw response so the user sees what the model said.

**Files:**
- Modify: `src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy:1530-1555` (the `renderReview` method)
- Test: `src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy`

- [ ] **Step 1: Write the failing test**

Add a test to `ShellCommandsSpec.groovy`:

```groovy
def "renderReview shows raw response when no structured findings parsed"() {
  given:
  String rawText = "This PR looks good. No issues found. The changes correctly replace CompanySettingsService with CompanyService."
  ReviewSummary summary = new ReviewSummary([], [], rawText)

  when:
  String rendered = ShellCommands.renderReview(summary, ReviewSeverity.LOW, false)

  then:
  rendered.contains(rawText)
  !rendered.contains("Findings:\n- None")
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl . -Dtest="ShellCommandsSpec#renderReview shows raw response when no structured findings parsed" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — currently shows "Findings: None" instead of raw text.

- [ ] **Step 3: Implement raw fallback**

In `ShellCommands.groovy`, modify the `renderReview` method. Replace lines 1530-1555:

```groovy
static String renderReview(ReviewSummary summary, ReviewSeverity minSeverity, boolean colorize) {
  List<ReviewFinding> filtered = summary.findings.findAll { it.severity.ordinal() <= minSeverity.ordinal() }
  if (filtered.isEmpty() && summary.raw != null && !summary.raw.trim().isEmpty()) {
    return summary.raw.trim()
  }
  StringBuilder builder = new StringBuilder("Findings:")
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -pl . -Dtest="ShellCommandsSpec#renderReview shows raw response when no structured findings parsed" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

- [ ] **Step 5: Run all tests**

Run: `./mvnw test`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/groovy/se/alipsa/lca/shell/ShellCommands.groovy src/test/groovy/se/alipsa/lca/shell/ShellCommandsSpec.groovy
git commit -m "fix: show raw LLM response when no structured findings parsed"
```
