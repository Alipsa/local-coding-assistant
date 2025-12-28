package se.alipsa.lca.intent

import spock.lang.Specification

class IntentRoutingDebugFormatterSpec extends Specification {

  def "format includes json and commands"() {
    given:
    IntentRouterResult result = new IntentRouterResult(
      [new IntentCommand("/review", [path: "src/main/groovy"])],
      0.92d,
      "explicit request"
    )
    IntentRoutingPlan plan = new IntentRoutingPlan(
      ["/review src/main/groovy"],
      0.92d,
      "explicit request"
    )
    IntentRoutingOutcome outcome = new IntentRoutingOutcome(plan, result)

    when:
    String output = IntentRoutingDebugFormatter.format(outcome)

    then:
    output.contains("=== Route Debug ===")
    output.contains("\"name\"")
    output.contains("/review")
    output.contains("1. /review src/main/groovy")
    output.contains("Confidence: 0.92")
  }

  def "format handles empty commands"() {
    given:
    IntentRouterResult result = new IntentRouterResult([], 0.2d, null)
    IntentRoutingPlan plan = new IntentRoutingPlan([], 0.2d, null)
    IntentRoutingOutcome outcome = new IntentRoutingOutcome(plan, result)

    when:
    String output = IntentRoutingDebugFormatter.format(outcome)

    then:
    output.contains("JSON:")
    output.contains("No commands suggested.")
  }
}
