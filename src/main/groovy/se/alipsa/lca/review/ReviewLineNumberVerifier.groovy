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
    // Forward only (known path ends with "/" + cited): a citation shorter than or equal to the real
    // label is a legitimate paraphrase (a bare filename, or a directory review's parent-relative
    // label being cited back verbatim). The reverse direction (a cited path ending in the known
    // fragment) has no corresponding legitimate input — a model only ever sees the label actually
    // shown to it (the full repo-relative path for in-root reviews, the parent-relative label for
    // out-of-root ones) and never a "fuller" path it could correctly cite instead — so it was
    // removed: it only ever admitted a fabricated deep path piggybacking on a real short one (e.g.
    // "src/does/not/exist/reviewdir/Sample.groovy" matching "reviewdir/Sample.groovy").
    Set<String> forwardMatches = knownPaths.findAll { key -> key.endsWith("/" + citedFile) }
    forwardMatches.size() == 1 ? forwardMatches.iterator().next() : null
  }
}
