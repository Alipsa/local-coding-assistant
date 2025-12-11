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
      if (line.toLowerCase().startsWith("findings")) {
        inFindings = true
        inTests = false
        continue
      }
      if (line.toLowerCase().startsWith("tests")) {
        inTests = true
        inFindings = false
        continue
      }
      if (inFindings && line.startsWith("-")) {
        ReviewFinding finding = parseFinding(line.substring(1).trim())
        if (finding != null) {
          findings.add(finding)
        }
      } else if (inTests && line.startsWith("-")) {
        tests.add(line.substring(1).trim())
      }
    }
    new ReviewSummary(findings, tests, reviewText)
  }

  private static ReviewFinding parseFinding(String line) {
    // Expected format: [Severity] path:line - comment
    def matcher = line =~ /^\[(High|Medium|Low)\]\s+([^:\n]+?)(?::(\d+))?\s*-\s*(.+)$/
    if (!matcher.matches()) {
      return null
    }
    ReviewSeverity severity = ReviewSeverity.valueOf(matcher.group(1).toUpperCase())
    String file = matcher.group(2).trim()
    Integer lineNumber = matcher.group(3) ? Integer.valueOf(matcher.group(3)) : null
    String comment = matcher.group(4).trim()
    new ReviewFinding(severity, file, lineNumber, comment)
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
