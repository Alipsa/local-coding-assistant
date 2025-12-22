package se.alipsa.lca.tools

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.regex.Pattern
import java.util.concurrent.ConcurrentHashMap

@Component
@CompileStatic
class CommandPolicy {

  private final List<String> allowlist
  private final List<String> denylist
  private static final Map<String, Pattern> WILDCARD_PATTERN_CACHE = new ConcurrentHashMap<>()

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
      if (!prefixMatchesWithBoundary(trimmed, prefix)) {
        return false
      }
    }
    Pattern wildcardPattern = WILDCARD_PATTERN_CACHE.computeIfAbsent(
      rule,
      { String r -> Pattern.compile(globToRegex(r)) } as java.util.function.Function<String, Pattern>
    )
    return wildcardPattern.matcher(trimmed).matches()
  }

  private static boolean prefixMatchesWithBoundary(String command, String prefix) {
    if (!command.startsWith(prefix)) {
      return false
    }
    if (prefix.isEmpty()) {
      return true
    }
    char lastPrefixChar = prefix.charAt(prefix.length() - 1)
    if (!Character.isLetterOrDigit(lastPrefixChar)) {
      return true
    }
    if (command.length() == prefix.length()) {
      return true
    }
    char boundaryChar = command.charAt(prefix.length())
    !Character.isLetterOrDigit(boundaryChar)
  }

  private static String globToRegex(String rule) {
    StringBuilder regex = new StringBuilder("^")
    for (int i = 0; i < rule.length(); i++) {
      char c = rule.charAt(i)
      if (c == '*') {
        regex.append(".*")
      } else {
        regex.append(Pattern.quote(String.valueOf(c)))
      }
    }
    regex.append('$')
    regex.toString()
  }

  @Canonical
  @CompileStatic
  static class Decision {
    boolean allowed
    String message
  }
}
