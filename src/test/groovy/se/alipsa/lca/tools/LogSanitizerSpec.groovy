package se.alipsa.lca.tools

import spock.lang.Specification

class LogSanitizerSpec extends Specification {

  def "redacts common secret patterns"() {
    expect:
    LogSanitizer.sanitize("apiKey=abc123") == "apiKey=REDACTED"
    LogSanitizer.sanitize("Authorization: Bearer token-value") == "Authorization: Bearer REDACTED"
    LogSanitizer.sanitize("Bearer token-value") == "Bearer REDACTED"
    LogSanitizer.sanitize("ghp_abcdefghijklmnopqrstuvwxyz123456") == "REDACTED"
    LogSanitizer.sanitize("github_pat_abcdef1234567890") == "REDACTED"
    LogSanitizer.sanitize("AKIAABCDEFGHIJKLMNOP") == "REDACTED"
  }
}
