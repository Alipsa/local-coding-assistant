package se.alipsa.lca.team

import groovy.transform.Canonical
import groovy.transform.CompileStatic

@Canonical
@CompileStatic
class TeamReviewRequest {
  String planSummary
  String diffText
  String sessionSystemPrompt
}
