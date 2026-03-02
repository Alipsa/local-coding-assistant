package se.alipsa.lca.team

import groovy.transform.Canonical
import groovy.transform.CompileStatic

@Canonical
@CompileStatic
class EngineerStepResult {
  int stepOrder
  boolean success
  String toolResults
  String llmResponse
  List<String> filesModified
  String errorMessage
}
