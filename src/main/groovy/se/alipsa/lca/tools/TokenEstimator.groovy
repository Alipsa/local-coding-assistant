package se.alipsa.lca.tools

import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

@Component
@CompileStatic
class TokenEstimator {

  /**
   * Approximate token count using whitespace splitting with a fallback heuristic.
   */
  int estimate(String text) {
    if (text == null) {
      return 0
    }
    String trimmed = text.trim()
    if (trimmed.isEmpty()) {
      return 0
    }
    String[] parts = trimmed.split(/\s+/)
    return parts.length
  }
}
