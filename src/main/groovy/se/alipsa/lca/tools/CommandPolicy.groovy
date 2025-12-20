package se.alipsa.lca.tools

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
@CompileStatic
class CommandPolicy {

  private final List<String> allowlist
  private final List<String> denylist

  CommandPolicy(
    @Value('${assistant.command.allowlist:}') String allowlistCsv,
    @Value('${assistant.command.denylist:}') String denylistCsv
  ) {
    this.allowlist = parseList(allowlistCsv)
    this.denylist = parseList(denylistCsv)
  }

  Decision evaluate(String command) {
    String trimmed = command != null ? command.trim() : ""
    if (!trimmed) {
      return new Decision(false, "Command is empty.")
    }
    for (String denied : denylist) {
      if (matches(trimmed, denied)) {
        return new Decision(false, "Command blocked by denylist: ${denied}")
      }
    }
    if (!allowlist.isEmpty()) {
      boolean allowed = allowlist.any { matches(trimmed, it) }
      if (!allowed) {
        return new Decision(false, "Command not in allowlist.")
      }
    }
    new Decision(true, null)
  }

  private static List<String> parseList(String csv) {
    if (csv == null || csv.trim().isEmpty()) {
      return List.of()
    }
    csv.split(",").collect { it.trim() }.findAll { it }
  }

  private static boolean matches(String command, String pattern) {
    if (pattern == null || pattern.trim().isEmpty()) {
      return false
    }
    String trimmed = command.trim()
    String rule = pattern.trim()
    if (rule.endsWith("*")) {
      String prefix = rule.substring(0, rule.length() - 1)
      return trimmed.startsWith(prefix)
    }
    trimmed == rule || trimmed.startsWith(rule + " ")
  }

  @Canonical
  @CompileStatic
  static class Decision {
    boolean allowed
    String message
  }
}
