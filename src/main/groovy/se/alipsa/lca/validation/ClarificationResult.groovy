package se.alipsa.lca.validation

import groovy.transform.CompileStatic

/**
 * Result of asking clarifying questions to the user
 */
@CompileStatic
class ClarificationResult {

  final boolean cancelled
  final String enrichedPrompt
  final Map<String, String> answers

  ClarificationResult(boolean cancelled, String enrichedPrompt, Map<String, String> answers = [:]) {
    this.cancelled = cancelled
    this.enrichedPrompt = enrichedPrompt
    this.answers = answers ?: [:]
  }

  static ClarificationResult cancelled() {
    return new ClarificationResult(true, null)
  }

  static ClarificationResult success(String enrichedPrompt, Map<String, String> answers = [:]) {
    return new ClarificationResult(false, enrichedPrompt, answers)
  }

}
