package se.alipsa.lca.intent

import groovy.transform.Canonical
import groovy.transform.CompileStatic

@Canonical
@CompileStatic
class IntentRouterResult {
  List<IntentCommand> commands = List.of()
  double confidence
  String explanation
  String modelUsed
  boolean usedSecondOpinion = false
}
