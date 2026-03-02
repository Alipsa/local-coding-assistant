package se.alipsa.lca.team

import groovy.transform.Canonical
import groovy.transform.CompileStatic

@Canonical
@CompileStatic
class PlanStep {
  int order
  String description
  String targetFile
  StepAction action
  List<String> contextFiles
  List<Integer> dependsOn
  String acceptanceCriteria
}
