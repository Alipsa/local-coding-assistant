package se.alipsa.lca.tools

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Enforces character/token budgets on packed context, preferring higher-relevance hits.
 * Uses simple heuristics to score main code over tests and trims context until limits are met.
 */
@Component
@CompileStatic
class ContextBudgetManager {

  final int maxChars
  final int maxTokens
  private final TokenEstimator tokenEstimator
  private final int mainBias
  private final int testPenalty

  @Autowired
  ContextBudgetManager(
    @Value('${context.max.chars:12000}') int maxChars,
    @Value('${context.max.tokens:0}') int maxTokens,
    TokenEstimator tokenEstimator,
    @Value('${context.score.main.bias:2}') int mainBias,
    @Value('${context.score.test.penalty:-1}') int testPenalty
  ) {
    this.maxChars = maxChars > 0 ? maxChars : 12000
    this.maxTokens = maxTokens > 0 ? maxTokens : 0
    this.tokenEstimator = tokenEstimator
    this.mainBias = mainBias
    this.testPenalty = testPenalty
  }

  ContextBudgetManager(int maxChars, int maxTokens, TokenEstimator tokenEstimator) {
    this(maxChars, maxTokens, tokenEstimator, 2, -1)
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
    int remainingChars = Math.max(0, charBudget)
    int remainingTokens = tokenBudget > 0 ? tokenBudget : Integer.MAX_VALUE
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
    StringBuilder builder = new StringBuilder()
    boolean truncated = false
    for (ScoredHit scoredHit : scored) {
      String blockText = blockString(scoredHit.hit)
      int blockTokens = tokenEstimator.estimate(blockText)
      if (blockTokens > remainingTokens) {
        truncated = true
        continue
      }
      int spacing = builder.length() > 0 ? 2 : 0
      if (blockText.length() + spacing <= remainingChars) {
        if (spacing > 0) {
          builder.append("\n\n")
          remainingChars = Math.max(0, remainingChars - spacing)
        }
        builder.append(blockText)
        remainingChars = Math.max(0, remainingChars - blockText.length())
        remainingTokens -= blockTokens
        kept.add(scoredHit.hit)
        continue
      }
      if (kept.isEmpty() && remainingChars > 0) {
        if (spacing > 0 && remainingChars > spacing) {
          builder.append("\n\n")
          remainingChars = Math.max(0, remainingChars - spacing)
        }
        int slice = Math.min(blockText.length(), remainingChars)
        if (slice > 0) {
          boolean needsEllipsis = slice < blockText.length()
          int adjustedSlice = needsEllipsis && slice > 3 ? slice - 3 : slice
          builder.append(blockText, 0, adjustedSlice)
          if (needsEllipsis) {
            builder.append("...")
          }
          kept.add(scoredHit.hit)
          truncated = true
          remainingTokens = Math.max(0, remainingTokens - tokenEstimator.estimate(builder.toString()))
          remainingChars = 0
        }
        continue
      }
      truncated = true
    }
    String rebuilt = builder.toString().stripTrailing()
    if (rebuilt.isEmpty() && !safeHits.isEmpty() && kept.isEmpty()) {
      truncated = true
    }
    truncated = truncated || kept.size() < safeHits.size() || rebuilt.length() < text.length()
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

  private int relevanceScore(CodeSearchTool.SearchHit hit) {
    int score = 0
    if (hit.path) {
      String lower = hit.path.toLowerCase()
      score += lower.contains("test") ? testPenalty : mainBias
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
