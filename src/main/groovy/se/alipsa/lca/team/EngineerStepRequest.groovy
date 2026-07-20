package se.alipsa.lca.team

import groovy.transform.Canonical
import groovy.transform.CompileStatic

@Canonical
@CompileStatic
class EngineerStepRequest {
  PlanStep step
  ArchitectPlan plan
  List<EngineerStepResult> priorResults
  String contextContent
}
