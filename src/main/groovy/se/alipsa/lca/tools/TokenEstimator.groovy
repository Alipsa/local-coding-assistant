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
    if (text == null || text.isEmpty()) {
      return 0
    }
    String[] parts = text.trim().split(/\s+/)
    return parts.length
  }
}
