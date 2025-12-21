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

    // If there is no wildcard, keep existing semantics:
    // - exact match, or
    // - rule matches the first token and is followed by a space.
    if (!rule.contains("*")) {
      return trimmed == rule || trimmed.startsWith(rule + " ")
    }

    // For patterns containing '*', interpret them as simple globs where:
    // - '*' matches any sequence of characters (possibly empty)
    // The glob is applied to the entire command string.
    int wildcardIndex = rule.indexOf('*')
    if (wildcardIndex > 0) {
      String prefix = rule.substring(0, wildcardIndex)
      if (!trimmed.startsWith(prefix)) {
        return false
      }
      if (prefix.length() < trimmed.length()) {
        char lastPrefixChar = prefix.charAt(prefix.length() - 1)
        char boundaryChar = trimmed.charAt(prefix.length())
        if (Character.isLetterOrDigit(lastPrefixChar) && Character.isLetterOrDigit(boundaryChar)) {
          return false
        }
      }
    }
    StringBuilder regex = new StringBuilder("^")
    String specials = '''.^$+?{}[]|()\\-'''
    for (int i = 0; i < rule.length(); i++) {
      char c = rule.charAt(i)
      if (c == '*') {
        regex.append(".*")
      } else {
        if (specials.indexOf((int) c) >= 0) {
          regex.append('\\')
        }
        regex.append(c)
      }
    }
    regex.append('$')

    return trimmed.matches(regex.toString())
  }

  @Canonical
  @CompileStatic
  static class Decision {
    boolean allowed
    String message
  }
}
