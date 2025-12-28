package se.alipsa.lca.intent

import groovy.json.JsonOutput
import groovy.transform.CompileStatic

import java.util.Collections
import java.util.Locale

@CompileStatic
class IntentRoutingDebugFormatter {

  static String format(IntentRoutingOutcome outcome) {
    StringBuilder builder = new StringBuilder()
    builder.append("=== Route Debug ===\n")
    builder.append("JSON:\n")
    builder.append(renderJson(outcome?.result)).append("\n")
    IntentRoutingPlan plan = outcome?.plan
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

  private static String renderJson(IntentRouterResult result) {
    if (result == null) {
      return "{}"
    }
    Map<String, Object> payload = new LinkedHashMap<>()
    List<Map<String, Object>> commands = new ArrayList<>()
    result.commands?.each { IntentCommand command ->
      Map<String, Object> entry = new LinkedHashMap<>()
      entry.put("name", command?.name)
      if (command?.args != null && !command.args.isEmpty()) {
        entry.put("args", command.args)
      } else {
        entry.put("args", Collections.emptyMap())
      }
      commands.add(entry)
    }
    payload.put("commands", commands)
    payload.put("confidence", result.confidence)
    payload.put("explanation", result.explanation)
    JsonOutput.prettyPrint(JsonOutput.toJson(payload))
  }
}
