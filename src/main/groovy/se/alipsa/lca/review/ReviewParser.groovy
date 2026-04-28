package se.alipsa.lca.review

import groovy.transform.Canonical
import groovy.transform.CompileStatic

@CompileStatic
class ReviewParser {

  static ReviewSummary parse(String reviewText) {
    if (reviewText == null) {
      return new ReviewSummary(List.of(), List.of(), "")
    }
    String[] lines = reviewText.split("\\R")
    List<ReviewFinding> findings = new ArrayList<>()
    List<String> tests = new ArrayList<>()
    boolean inFindings = false
    boolean inTests = false
    for (String rawLine : lines) {
      String line = rawLine.trim()
      String lower = line.replaceFirst(/^#+\s*/, '').toLowerCase()
      if (lower.startsWith("findings")) {
        inFindings = true
        inTests = false
        continue
      }
      if (lower.startsWith("tests")) {
        inTests = true
        inFindings = false
        continue
      }
      if (inFindings && line.startsWith("-")) {
        String text = line.substring(1).trim()
        ReviewFinding finding = parseFinding(text)
        if (finding != null) {
          findings.add(finding)
        } else if (!findings.isEmpty() && rawLine.startsWith("    ")) {
          ReviewFinding last = findings.get(findings.size() - 1)
          String extra = text.startsWith("-") ? text.substring(1).trim() : text
          String merged = last.comment ? last.comment + " " + extra : extra
          findings.set(findings.size() - 1, new ReviewFinding(last.severity, last.file, last.line, merged))
        }
      } else if (inTests && line.startsWith("-")) {
        tests.add(line.substring(1).trim())
      }
    }
    new ReviewSummary(findings, tests, reviewText)
  }

  private static ReviewFinding parseFinding(String line) {
    // Strip markdown bold markers
    String cleaned = line.replaceAll(/\*\*/, '')
    // Expected format: [Severity] path:line - comment (case-insensitive), comment may be on next line
    def matcher = cleaned =~ /(?i)^\[(High|Medium|Low)\]\s+([^:\n]+?)(?::(\d+))?(?:\s*[-—]\s*(.+))?$/
    if (matcher.matches()) {
      ReviewSeverity severity = ReviewSeverity.valueOf(matcher.group(1).toUpperCase())
      String file = matcher.group(2).trim()
      Integer lineNumber = matcher.group(3) ? Integer.valueOf(matcher.group(3)) : null
      String comment = matcher.group(4) ? matcher.group(4).trim() : ""
      return new ReviewFinding(severity, file, lineNumber, comment)
    }
    // Fallback: treat unstructured bullet as Low/general (skip noise like "None", "N/A")
    String trimmed = line.trim()
    if (trimmed && !(trimmed =~ /(?i)^(none|n\/?a|no issues?|no findings?)\.?$/)) {
      return new ReviewFinding(ReviewSeverity.LOW, "general", null, trimmed)
    }
    null
  }
}

@Canonical
@CompileStatic
class ReviewSummary {
  List<ReviewFinding> findings
  List<String> tests
  String raw
}

@Canonical
@CompileStatic
class ReviewFinding {
  ReviewSeverity severity
  String file
  Integer line
  String comment
}

@CompileStatic
enum ReviewSeverity {
  HIGH,
  MEDIUM,
  LOW
}
