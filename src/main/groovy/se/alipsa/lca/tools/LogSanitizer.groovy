package se.alipsa.lca.tools

import groovy.transform.CompileStatic


@CompileStatic
class LogSanitizer {

  static String sanitize(String input) {
    if (input == null || input.isEmpty()) {
      return input
    }
    String sanitized = input
    sanitized = sanitized.replaceAll(
      /(?i)(api[_-]?key|x-api-key|token|secret|password|passwd|pwd)\s*[:=]\s*([^\s"']+)/,
      "\$1=REDACTED"
    )
    sanitized = sanitized.replaceAll(
      /(?i)Authorization:\s*Bearer\s+[A-Za-z0-9\-_.]+/,
      "Authorization: Bearer REDACTED"
    )
    sanitized = sanitized.replaceAll(/(?i)Bearer\s+[A-Za-z0-9\-_.]+/, "Bearer REDACTED")
    sanitized = sanitized.replaceAll(/\bghp_[A-Za-z0-9]{16,}\b/, "REDACTED")
    sanitized = sanitized.replaceAll(/\bgithub_pat_[A-Za-z0-9_]{10,}\b/, "REDACTED")
    sanitized = sanitized.replaceAll(/\bAKIA[0-9A-Z]{16}\b/, "REDACTED")
    sanitized = sanitized.replaceAll(/\bASIA[0-9A-Z]{16}\b/, "REDACTED")
    sanitized
  }
}
