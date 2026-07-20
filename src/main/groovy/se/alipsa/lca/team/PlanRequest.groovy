package se.alipsa.lca.team

import groovy.transform.Canonical
import groovy.transform.CompileStatic

@Canonical
@CompileStatic
class PlanRequest {
  String prompt
  String sessionSystemPrompt
}
