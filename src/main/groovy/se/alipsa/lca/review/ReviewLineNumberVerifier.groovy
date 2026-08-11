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
    Set<String> forwardMatches = knownPaths.findAll { key -> key.endsWith("/" + citedFile) }
    if (!forwardMatches.isEmpty()) {
      return forwardMatches.size() == 1 ? forwardMatches.iterator().next() : null
    }
    // The reverse direction (a cited path ending in the known fragment) is only safe when the known
    // path has 2+ segments: a directory review's fileLineCounts keys are parent-relative labels
    // (e.g. "shell/ShellCommands.groovy"), so a model citing the natural full repo path needs this
    // to verify at all. Gating on "/" in the key stops a single-segment root-level entry like
    // "pom.xml" from letting a fabricated deep path (e.g. "src/does/not/exist/pom.xml") piggyback.
    Set<String> reverseMatches = knownPaths.findAll { key -> key.contains("/") && citedFile.endsWith("/" + key) }
    reverseMatches.size() == 1 ? reverseMatches.iterator().next() : null
  }
}
