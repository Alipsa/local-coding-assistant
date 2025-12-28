package se.alipsa.lca.intent

import spock.lang.Specification

class IntentRoutingFormatterSpec extends Specification {

  def "format includes commands and confidence"() {
    given:
    IntentRoutingPlan plan = new IntentRoutingPlan(
      ["/review --prompt \"Check\"", "/plan --prompt \"Check\""],
      0.9d,
      "Review then plan"
    )

    when:
    String output = IntentRoutingFormatter.format(plan)

    then:
    output.contains("=== Route ===")
    output.contains("1. /review --prompt \"Check\"")
    output.contains("2. /plan --prompt \"Check\"")
    output.contains("Confidence: 0.90")
    output.contains("Explanation: Review then plan")
  }

  def "format handles empty plan"() {
    when:
    String output = IntentRoutingFormatter.format(new IntentRoutingPlan())

    then:
    output.contains("No commands suggested.")
  }
}
