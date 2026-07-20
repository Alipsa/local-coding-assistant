package se.alipsa.lca.team

import groovy.transform.Canonical
import groovy.transform.CompileStatic

@Canonical
@CompileStatic
class ArchitectPlan {
  String summary
  List<PlanStep> steps
  List<String> readOnlyContext
  List<String> risks
  String reasoning
}
