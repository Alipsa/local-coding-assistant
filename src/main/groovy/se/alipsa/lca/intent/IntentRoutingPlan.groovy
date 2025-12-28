package se.alipsa.lca.intent

import groovy.transform.Canonical
import groovy.transform.CompileStatic

@Canonical
@CompileStatic
class IntentRoutingPlan {
  List<String> commands = List.of()
  double confidence
  String explanation
}
