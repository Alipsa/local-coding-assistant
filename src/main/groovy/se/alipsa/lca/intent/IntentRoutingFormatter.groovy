package se.alipsa.lca.intent

import groovy.transform.CompileStatic

import java.util.Locale

@CompileStatic
class IntentRoutingFormatter {

  static String format(IntentRoutingPlan plan) {
    StringBuilder builder = new StringBuilder()
    builder.append("=== Route ===\n")
    List<String> commands = plan?.commands ?: List.of()
    if (commands.isEmpty()) {
      builder.append("No commands suggested.")
      return builder.toString().stripTrailing()
    }
    builder.append("Commands:\n")
    commands.eachWithIndex { String command, int index ->
      builder.append(index + 1).append(". ").append(command).append("\n")
    }
    builder.append("Confidence: ")
      .append(String.format(Locale.UK, "%.2f", plan.confidence))
    if (plan.explanation != null && plan.explanation.trim()) {
      builder.append("\nExplanation: ").append(plan.explanation.trim())
    }
    builder.toString().stripTrailing()
  }
}
