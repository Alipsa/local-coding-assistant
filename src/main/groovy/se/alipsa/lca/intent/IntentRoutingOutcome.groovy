package se.alipsa.lca.intent

import groovy.transform.Canonical
import groovy.transform.CompileStatic

@Canonical
@CompileStatic
class IntentRoutingOutcome {
  IntentRoutingPlan plan
  IntentRouterResult result
}
