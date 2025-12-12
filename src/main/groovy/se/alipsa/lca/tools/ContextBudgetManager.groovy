package se.alipsa.lca.tools

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
@CompileStatic
class ContextBudgetManager {

  final int maxChars
  final int maxTokens
  private final TokenEstimator tokenEstimator

  ContextBudgetManager(
    @Value('${context.max.chars:12000}') int maxChars,
    @Value('${context.max.tokens:0}') int maxTokens,
    TokenEstimator tokenEstimator
  ) {
    this.maxChars = maxChars > 0 ? maxChars : 12000
    this.maxTokens = maxTokens > 0 ? maxTokens : 0
    this.tokenEstimator = tokenEstimator
  }

  BudgetResult applyBudget(String text, List<CodeSearchTool.SearchHit> hits) {
    applyBudget(text, hits, maxChars, maxTokens)
  }

  BudgetResult applyBudget(String text, List<CodeSearchTool.SearchHit> hits, int charBudget, int tokenBudget) {
    if (text == null) {
      return new BudgetResult("", List.of(), true)
    }
    if (text.length() <= charBudget && (tokenBudget <= 0 || tokenEstimator.estimate(text) <= tokenBudget)) {
      return new BudgetResult(text, hits, false)
    }
    int allowed = Math.max(0, charBudget)
    List<CodeSearchTool.SearchHit> safeHits = hits != null ? hits : List.of()
    List<ScoredHit> scored = safeHits.collect { CodeSearchTool.SearchHit hit ->
      new ScoredHit(hit, relevanceScore(hit), blockSize(hit))
    }
    scored.sort { ScoredHit a, ScoredHit b ->
      int byScore = b.score <=> a.score
      if (byScore != 0) {
        return byScore
      }
      return a.blockSize <=> b.blockSize
    }
    List<CodeSearchTool.SearchHit> kept = new ArrayList<>()
    int budget = allowed
    int tokenCap = tokenBudget > 0 ? tokenBudget : Integer.MAX_VALUE
    for (ScoredHit scoredHit : scored) {
      if (budget - scoredHit.blockSize < 0) {
        continue
      }
      String blockText = blockString(scoredHit.hit)
      int tokens = tokenEstimator.estimate(blockText)
      if (tokens > tokenCap) {
        continue
      }
      kept.add(scoredHit.hit)
      budget -= scoredHit.blockSize
      tokenCap -= tokens
    }
    String rebuilt = buildText(kept)
    boolean truncated = kept.size() < safeHits.size() || rebuilt.length() < text.length()
    new BudgetResult(rebuilt, kept, truncated)
  }

  private static int blockSize(CodeSearchTool.SearchHit hit) {
    String snippet = hit.snippet ?: ""
    // Rough overhead for path/line formatting and newlines.
    snippet.length() + 32
  }

  private static String blockString(CodeSearchTool.SearchHit hit) {
    """
${hit.path}:${hit.line}
${hit.snippet}
""".stripIndent().trim()
  }

  private static int relevanceScore(CodeSearchTool.SearchHit hit) {
    int score = 0
    if (hit.path) {
      String lower = hit.path.toLowerCase()
      // Prefer main code over tests; configurable if needed.
      score += lower.contains("test") ? -1 : 2
      score += lower.contains("main") ? 1 : 0
    }
    int distance = (int) (hit.line / 1000)
    score += Math.max(0, 3 - distance) // crude bias toward top of file
    score
  }

  private static String buildText(List<CodeSearchTool.SearchHit> hits) {
    if (hits == null || hits.isEmpty()) {
      return ""
    }
    StringBuilder builder = new StringBuilder()
    hits.each { hit ->
      builder.append(blockString(hit)).append("\n\n")
    }
    builder.toString().stripTrailing()
  }
}

@Canonical
@CompileStatic
class BudgetResult {
  String text
  List<CodeSearchTool.SearchHit> included
  boolean truncated
}

@Canonical
@CompileStatic
class ScoredHit {
  CodeSearchTool.SearchHit hit
  int score
  int blockSize
}
