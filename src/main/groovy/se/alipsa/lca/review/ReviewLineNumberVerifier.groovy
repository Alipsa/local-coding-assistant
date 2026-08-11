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
    // Only the forward direction (a known path ending in the cited fragment) is safe: the reverse
    // direction would let a fabricated deep path piggyback on any short known path sharing its
    // filename (e.g. "src/does/not/exist/pom.xml" matching a real root-level "pom.xml").
    Set<String> matches = knownPaths.findAll { key -> key.endsWith("/" + citedFile) }
    matches.size() == 1 ? matches.iterator().next() : null
  }
}
