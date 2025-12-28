package se.alipsa.lca.intent

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

import java.util.Collections

@Component
@CompileStatic
class IntentRouterParser {

  IntentRouterResult parse(String raw) {
    if (raw == null || raw.trim().isEmpty()) {
      throw new IllegalArgumentException("Router response was empty.")
    }
    String json = extractJson(raw)
    if (json == null || json.trim().isEmpty()) {
      throw new IllegalArgumentException("Router response did not contain JSON.")
    }
    Object parsed
    try {
      parsed = new JsonSlurper().parseText(json)
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to parse router JSON.", e)
    }
    if (!(parsed instanceof Map)) {
      throw new IllegalArgumentException("Router response must be a JSON object.")
    }
    Map map = (Map) parsed
    IntentRouterResult result = new IntentRouterResult()
    result.confidence = parseConfidence(map.get("confidence"))
    Object explanation = map.get("explanation")
    result.explanation = explanation != null ? explanation.toString() : null
    result.commands = parseCommands(map.get("commands"))
    result
  }

  private static List<IntentCommand> parseCommands(Object value) {
    if (!(value instanceof List)) {
      return List.of()
    }
    List<?> rawList = (List<?>) value
    List<IntentCommand> commands = new ArrayList<>()
    rawList.each { Object entry ->
      if (!(entry instanceof Map)) {
        return
      }
      Map map = (Map) entry
      Object nameObj = map.get("name")
      if (nameObj == null || nameObj.toString().trim().isEmpty()) {
        return
      }
      String name = nameObj.toString().trim()
      Map<String, Object> args = parseArgs(map.get("args"))
      commands.add(new IntentCommand(name, args))
    }
    commands.isEmpty() ? List.of() : commands
  }

  private static Map<String, Object> parseArgs(Object value) {
    if (!(value instanceof Map)) {
      return Collections.emptyMap()
    }
    Map argsRaw = (Map) value
    Map<String, Object> args = new LinkedHashMap<>()
    argsRaw.each { Object key, Object val ->
      if (key == null) {
        return
      }
      args.put(key.toString(), val)
    }
    args.isEmpty() ? Collections.emptyMap() : args
  }

  private static double parseConfidence(Object value) {
    if (value instanceof Number) {
      return ((Number) value).doubleValue()
    }
    if (value instanceof CharSequence) {
      try {
        return Double.parseDouble(value.toString())
      } catch (NumberFormatException ignored) {
        return 0.0d
      }
    }
    0.0d
  }

  private static String extractJson(String raw) {
    String trimmed = raw.trim()
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
      return trimmed
    }
    int start = trimmed.indexOf("{")
    int end = trimmed.lastIndexOf("}")
    if (start >= 0 && end > start) {
      return trimmed.substring(start, end + 1)
    }
    null
  }
}
