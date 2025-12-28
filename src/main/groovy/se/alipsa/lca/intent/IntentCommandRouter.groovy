package se.alipsa.lca.intent

import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

import java.util.Objects

@Component
@CompileStatic
class IntentCommandRouter {

  private final IntentRouterAgent routerAgent
  private final IntentCommandMapper mapper

  IntentCommandRouter(IntentRouterAgent routerAgent, IntentCommandMapper mapper) {
    this.routerAgent = Objects.requireNonNull(routerAgent, "routerAgent must not be null")
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null")
  }

  IntentRoutingPlan route(String input) {
    routeDetails(input).plan
  }

  IntentRoutingOutcome routeDetails(String input) {
    IntentRouterResult result = routerAgent.route(input)
    List<String> commands = mapper.map(input, result)
    IntentRoutingPlan plan = new IntentRoutingPlan(commands, result?.confidence ?: 0.0d, result?.explanation)
    new IntentRoutingOutcome(plan, result)
  }
}
