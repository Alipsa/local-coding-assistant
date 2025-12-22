package se.alipsa.lca.tools

import groovy.transform.CompileStatic

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Collection
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Matcher
import java.util.regex.Pattern


@CompileStatic
class LogSanitizer {

  // Minimum length for Base64 strings to reduce false positives
  private static final int MIN_BASE64_LENGTH = 28
  // Minimum length for URL-encoded strings to reduce false positives
  private static final int MIN_URL_ENCODED_LENGTH = 16
  // Skip decoding unusually large encoded tokens to avoid excessive work.
  private static final int MAX_ENCODED_LENGTH = 8192
  private static final int DEFAULT_MIN_CONFIDENCE = 2
  private static final Set<String> PLACEHOLDER_VALUES = Set.of(
    "example",
    "sample",
    "demo",
    "test",
    "testing",
    "value",
    "placeholder",
    "changeme",
    "your_api_key",
    "your_token"
  )
  private static final Set<String> IGNORED_VALUES = ConcurrentHashMap.newKeySet()
  private static final CopyOnWriteArrayList<Pattern> IGNORED_VALUE_PATTERNS = new CopyOnWriteArrayList<>()
  private static volatile int minSecretConfidence = DEFAULT_MIN_CONFIDENCE
  private static final Pattern KEY_VALUE_PATTERN = Pattern.compile(
    "(?i)(api[_-]?key|x-api-key|token|secret|password|passwd|pwd)\\s*([:=])\\s*([^\\s\"']+)"
  )
  private static final Pattern AUTHORIZATION_BEARER_PATTERN = Pattern.compile("(?i)Authorization:\\s*Bearer\\s+[A-Za-z0-9\\-_.]{16,}")
  private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9\\-_.]{16,}")
  private static final Pattern GITHUB_GHP_PATTERN = Pattern.compile("\\bghp_[A-Za-z0-9]{16,}\\b")
  private static final Pattern GITHUB_PAT_PATTERN = Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{10,}\\b")
  private static final Pattern AWS_ACCESS_KEY_PATTERN = Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b")
  private static final Pattern AWS_SESSION_KEY_PATTERN = Pattern.compile("\\bASIA[0-9A-Z]{16}\\b")
  private static final Pattern URL_ENCODED_VALUE_PATTERN = Pattern.compile("[^\\s\"'<>]*(?:%[0-9A-Fa-f]{2})+[^\\s\"'<>]*")
  private static final Pattern BASE64_VALUE_PATTERN = Pattern.compile("[A-Za-z0-9+/]+={0,2}")
  private static final Pattern LOWER_PATTERN = Pattern.compile("[a-z]")
  private static final Pattern UPPER_PATTERN = Pattern.compile("[A-Z]")
  private static final Pattern DIGIT_PATTERN = Pattern.compile("\\d")
  private static final Pattern SYMBOL_PATTERN = Pattern.compile("[^A-Za-z0-9]")
  private static final Pattern SEMVER_PATTERN = Pattern.compile("(?i)\\b\\d+\\.\\d+\\.\\d+(?:-[A-Za-z0-9.+]+)?\\b")
  private static final Pattern UUID_PATTERN = Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b")
  private static final Pattern GIT_HASH_PATTERN = Pattern.compile("\\b[0-9a-f]{7,40}\\b")

  /**
   * Sanitizes a string by redacting secrets and sensitive data.
   * 
   * This method attempts to detect and redact:
   * - API keys, tokens, passwords in plain text
   * - Bearer tokens and Authorization headers
   * - GitHub personal access tokens (ghp_, github_pat_)
   * - AWS access keys (AKIA*, ASIA*)
   * - URL-encoded secrets (attempts to decode and scan)
   * - Base64-encoded secrets (attempts to decode and scan)
   * 
   * Limitations:
   * - Only detects patterns matching known secret formats
   * - May miss custom or proprietary secret formats
   * - Only checks a small number of URL/Base64 decoding passes (not recursive)
   * - May produce false positives with legitimate base64/URL-encoded data
   * - Heuristic scoring (length and character variety) can flag non-secrets such as version strings or identifiers
   * - Base64 detection uses a length threshold to reduce noise and might miss shorter encoded secrets
   * - Encoded tokens above a size threshold are not decoded to avoid excessive work
   * - Does not detect secrets split across multiple lines or obfuscated in other ways
   * 
   * @param input the string to sanitize
   * @return the sanitized string with secrets replaced by "REDACTED"
   */
  static String sanitize(String input) {
    if (input == null || input.isEmpty()) {
      return input
    }
    String sanitized = input
    
    // Check for URL-encoded and Base64-encoded secrets, with a small number of passes
    // to catch common double-encoding without unbounded recursion.
    for (int i = 0; i < 2; i++) {
      sanitized = sanitizeUrlEncoded(sanitized)
      sanitized = sanitizeBase64Encoded(sanitized)
    }
    
    sanitized = sanitizeKeyValuePairs(sanitized)
    sanitized = AUTHORIZATION_BEARER_PATTERN.matcher(sanitized).replaceAll("Authorization: Bearer REDACTED")
    sanitized = BEARER_PATTERN.matcher(sanitized).replaceAll("Bearer REDACTED")
    sanitized = GITHUB_GHP_PATTERN.matcher(sanitized).replaceAll("REDACTED")
    sanitized = GITHUB_PAT_PATTERN.matcher(sanitized).replaceAll("REDACTED")
    sanitized = AWS_ACCESS_KEY_PATTERN.matcher(sanitized).replaceAll("REDACTED")
    sanitized = AWS_SESSION_KEY_PATTERN.matcher(sanitized).replaceAll("REDACTED")
    
    sanitized
  }

  static void configureMinConfidence(int minConfidence) {
    if (minConfidence < 0) {
      throw new IllegalArgumentException("Minimum confidence must be >= 0")
    }
    minSecretConfidence = minConfidence
  }

  static void configureIgnoredValues(Collection<String> values) {
    IGNORED_VALUES.clear()
    if (values != null) {
      values.each { String v ->
        if (v != null && !v.trim().isEmpty()) {
          IGNORED_VALUES.add(v.trim().toLowerCase(Locale.ROOT))
        }
      }
    }
  }

  static void configureIgnoredValuePatterns(Collection<String> patterns) {
    IGNORED_VALUE_PATTERNS.clear()
    if (patterns != null) {
      patterns.each { String p ->
        if (p != null && !p.trim().isEmpty()) {
          IGNORED_VALUE_PATTERNS.add(Pattern.compile(p))
        }
      }
    }
  }

  static void resetConfiguration() {
    minSecretConfidence = DEFAULT_MIN_CONFIDENCE
    IGNORED_VALUES.clear()
    IGNORED_VALUE_PATTERNS.clear()
  }
  
  /**
   * Detects and redacts URL-encoded secrets.
   * Attempts to decode URL-encoded strings and check if they contain secrets.
   */
  private static String sanitizeUrlEncoded(String input) {
    Matcher matcher = KEY_VALUE_PATTERN.matcher(input)
    StringBuffer sb = new StringBuffer()
    while (matcher.find()) {
      String value = matcher.group(3)
      String full = matcher.group(0)
      String replacement = full
      if (isUrlEncodedCandidate(value)) {
        try {
          String decoded = URLDecoder.decode(value, StandardCharsets.UTF_8.toString())
          if (containsSecret(decoded)) {
            replacement = full.substring(0, full.length() - value.length()) + "REDACTED"
          }
        } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
          // leave replacement as original
        }
      }
      matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement))
    }
    matcher.appendTail(sb)
    sb.toString()
  }
  
  /**
   * Detects and redacts Base64-encoded secrets.
   * Attempts to decode Base64 strings and check if they contain secrets.
   */
  private static String sanitizeBase64Encoded(String input) {
    Matcher matcher = KEY_VALUE_PATTERN.matcher(input)
    StringBuffer sb = new StringBuffer()
    while (matcher.find()) {
      String value = matcher.group(3)
      String full = matcher.group(0)
      String replacement = full
      if (isBase64Candidate(value)) {
        try {
          byte[] decoded = Base64.decoder.decode(value)
          String decodedStr = new String(decoded, StandardCharsets.UTF_8)
          if (containsSecret(decodedStr)) {
            replacement = full.substring(0, full.length() - value.length()) + "REDACTED"
          }
        } catch (IllegalArgumentException e) {
          // leave replacement as original
        }
      }
      matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement))
    }
    matcher.appendTail(sb)
    sb.toString()
  }
  
  private static boolean isUrlEncodedCandidate(String value) {
    if (value == null) {
      return false
    }
    if (value.length() < MIN_URL_ENCODED_LENGTH || value.length() > MAX_ENCODED_LENGTH) {
      return false
    }
    URL_ENCODED_VALUE_PATTERN.matcher(value).matches()
  }
  
  private static boolean isBase64Candidate(String value) {
    if (value == null) {
      return false
    }
    if (value.length() < MIN_BASE64_LENGTH || value.length() > MAX_ENCODED_LENGTH) {
      return false
    }
    if (!BASE64_VALUE_PATTERN.matcher(value).matches()) {
      return false
    }
    value.length() % 4 == 0
  }

  private static String sanitizeKeyValuePairs(String input) {
    Matcher matcher = KEY_VALUE_PATTERN.matcher(input)
    StringBuffer sb = new StringBuffer()
    while (matcher.find()) {
      String key = matcher.group(1)
      String delimiter = matcher.group(2)
      String value = matcher.group(3)
      String replacement = matcher.group(0)
      if (!"REDACTED".equals(value) && looksLikeSecretValue(value, key)) {
        replacement = key + delimiter + "REDACTED"
      }
      matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement))
    }
    matcher.appendTail(sb)
    sb.toString()
  }
  
  /**
   * Checks if a string contains patterns that match known secret formats.
   */
  private static boolean containsSecret(String text) {
    if (text == null || text.isEmpty()) {
      return false
    }
    
    // Check for key=value patterns with secret-like keys
    Matcher kvMatcher = KEY_VALUE_PATTERN.matcher(text)
    if (kvMatcher.find()) {
      String value = kvMatcher.group(3)
      String key = kvMatcher.group(1)
      if (looksLikeSecretValue(value, key)) {
        return true
      }
    }
    
    // Check for Bearer tokens
    if (BEARER_PATTERN.matcher(text).find()) {
      return true
    }
    
    // Check for GitHub tokens
    if (GITHUB_GHP_PATTERN.matcher(text).find()) {
      return true
    }
    if (GITHUB_PAT_PATTERN.matcher(text).find()) {
      return true
    }
    
    // Check for AWS keys
    if (AWS_ACCESS_KEY_PATTERN.matcher(text).find()) {
      return true
    }
    if (AWS_SESSION_KEY_PATTERN.matcher(text).find()) {
      return true
    }
    
    return false
  }

  private static boolean looksLikeSecretValue(String value, String keyHint = null) {
    if (value == null) {
      return false
    }
    String trimmed = value.trim()
    if (trimmed.isEmpty()) {
      return false
    }
    if (ignoredValue(trimmed)) {
      return false
    }
    boolean matchesCommonNonSecret = SEMVER_PATTERN.matcher(trimmed).find() ||
      UUID_PATTERN.matcher(trimmed).find() ||
      GIT_HASH_PATTERN.matcher(trimmed).find()
    if (trimmed.length() < 8) {
      return false
    }
    String normalizedKey = keyHint != null ? keyHint.toLowerCase(Locale.ROOT) : null
    boolean strongKeyHint = normalizedKey != null &&
      (normalizedKey.contains("password") || normalizedKey.contains("secret") || normalizedKey.contains("token") || normalizedKey.contains("key"))
    if (matchesCommonNonSecret && !strongKeyHint) {
      return false
    }
    int score = 0
    if (trimmed.length() >= 12) {
      score++
    }
    if (trimmed.length() >= 16) {
      score++
    }
    boolean hasLower = LOWER_PATTERN.matcher(trimmed).find()
    boolean hasUpper = UPPER_PATTERN.matcher(trimmed).find()
    boolean hasDigit = DIGIT_PATTERN.matcher(trimmed).find()
    boolean hasSymbol = SYMBOL_PATTERN.matcher(trimmed).find()
    if (strongKeyHint) {
      int hintScore = 0
      if (hasDigit || hasSymbol) {
        hintScore++
      }
      if (trimmed.length() >= 10) {
        hintScore++
      }
      if (hasLower && hasUpper) {
        hintScore++
      }
      if (hintScore >= 2) {
        return true
      }
    }
    if (hasLower && hasUpper) {
      score++
    }
    if (hasDigit) {
      score++
    }
    if (hasSymbol) {
      score++
    }
    score >= minSecretConfidence
  }

  private static boolean ignoredValue(String trimmed) {
    String lower = trimmed.toLowerCase(Locale.ROOT)
    if (PLACEHOLDER_VALUES.contains(lower) || IGNORED_VALUES.contains(lower)) {
      return true
    }
    for (Pattern pattern : IGNORED_VALUE_PATTERNS) {
      if (pattern.matcher(trimmed).find()) {
        return true
      }
    }
    false
  }
}
