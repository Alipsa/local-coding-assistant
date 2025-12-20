package se.alipsa.lca.tools

import groovy.transform.Canonical
import groovy.transform.CompileStatic

import java.util.regex.Matcher
import java.util.regex.Pattern

@CompileStatic
class SecretScanner {

  private static final List<Rule> RULES = List.of(
    new Rule("AWS Access Key", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b")),
    new Rule("AWS Secret Key", Pattern.compile("(?i)aws_secret_access_key\\s*[:=]\\s*([A-Za-z0-9/+=]{40})")),
    new Rule("GitHub Token", Pattern.compile("\\bghp_[A-Za-z0-9]{36}\\b")),
    new Rule("Private Key", Pattern.compile("-----BEGIN (?:RSA|EC|PRIVATE) KEY-----"))
  )

  List<SecretFinding> scan(String content) {
    if (content == null || content.trim().isEmpty()) {
      return List.of()
    }
    List<SecretFinding> findings = new ArrayList<>()
    String[] lines = content.split("\\R", -1)
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i]
      for (Rule rule : RULES) {
        Matcher matcher = rule.pattern.matcher(line)
        while (matcher.find()) {
          String raw = matcher.groupCount() > 0 ? matcher.group(1) : matcher.group()
          findings.add(new SecretFinding(rule.label, i + 1, mask(raw)))
        }
      }
    }
    findings
  }

  private static String mask(String value) {
    if (value == null) {
      return ""
    }
    String trimmed = value.trim()
    if (trimmed.length() <= 8) {
      return "*" * trimmed.length()
    }
    String start = trimmed.substring(0, 4)
    String end = trimmed.substring(trimmed.length() - 4)
    String stars = "*" * Math.max(4, trimmed.length() - 8)
    "${start}${stars}${end}"
  }

  @Canonical
  @CompileStatic
  static class SecretFinding {
    String label
    int line
    String maskedValue
  }

  @Canonical
  @CompileStatic
  private static class Rule {
    String label
    Pattern pattern
  }
}
