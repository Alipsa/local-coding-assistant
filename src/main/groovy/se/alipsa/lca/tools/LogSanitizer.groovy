package se.alipsa.lca.tools

import groovy.transform.CompileStatic

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64


@CompileStatic
class LogSanitizer {

  // Minimum length for Base64 strings to reduce false positives
  private static final int MIN_BASE64_LENGTH = 20

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
   * - Only checks one level of URL/Base64 encoding (not recursive)
   * - May produce false positives with legitimate base64/URL-encoded data
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
    
    // Check for URL-encoded secrets FIRST (before plain text patterns)
    sanitized = sanitizeUrlEncoded(sanitized)
    
    // Check for Base64-encoded secrets
    sanitized = sanitizeBase64Encoded(sanitized)
    
    // Apply standard patterns
    sanitized = sanitized.replaceAll(
      /(?i)(api[_-]?key|x-api-key|token|secret|password|passwd|pwd)\s*[:=]\s*([^\s"']+)/
    ) { List<String> match ->
      String key = match[1]  // Group 1
      String value = match[2]  // Group 2
      // Don't re-redact if already redacted
      if (value == "REDACTED") {
        return match[0]  // Return full match unchanged
      }
      return "${key}=REDACTED"
    }
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
  
  /**
   * Detects and redacts URL-encoded secrets.
   * Attempts to decode URL-encoded strings and check if they contain secrets.
   */
  private static String sanitizeUrlEncoded(String input) {
    // Pattern to find URL-encoded sequences (percent-encoded)
    // Look for continuous strings that contain %XX patterns
    def urlEncodedPattern = /[^\s"'<>]*%[0-9A-Fa-f]{2}[^\s"'<>]*/
    
    input.replaceAll(urlEncodedPattern) { String encoded ->
      try {
        String decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8.toString())
        // Check if decoded content contains secrets
        if (containsSecret(decoded)) {
          return "REDACTED"
        }
      } catch (Exception e) {
        // If decoding fails, leave it as-is
      }
      return encoded
    }
  }
  
  /**
   * Detects and redacts Base64-encoded secrets.
   * Attempts to decode Base64 strings and check if they contain secrets.
   */
  private static String sanitizeBase64Encoded(String input) {
    // Pattern to find potential Base64 strings (configurable minimum length to reduce false positives)
    // Base64 uses A-Z, a-z, 0-9, +, /, and = for padding
    // Use negative lookahead/lookbehind to avoid word boundaries that exclude padding
    def base64Pattern = /(?<![A-Za-z0-9+\/])[A-Za-z0-9+\/]{${MIN_BASE64_LENGTH},}={0,2}(?![A-Za-z0-9+\/])/
    
    input.replaceAll(base64Pattern) { String encoded ->
      try {
        // Java's Base64 decoder handles missing padding automatically
        byte[] decoded = Base64.decoder.decode(encoded)
        String decodedStr = new String(decoded, StandardCharsets.UTF_8)
        // Check if decoded content contains secrets
        if (containsSecret(decodedStr)) {
          return "REDACTED"
        }
      } catch (Exception e) {
        // If decoding fails or produces non-UTF8, leave it as-is
      }
      return encoded
    }
  }
  
  /**
   * Checks if a string contains patterns that match known secret formats.
   */
  private static boolean containsSecret(String text) {
    if (text == null || text.isEmpty()) {
      return false
    }
    
    // Check for key=value patterns with secret-like keys
    if (text =~ /(?i)(api[_-]?key|x-api-key|token|secret|password|passwd|pwd)\s*[:=]/) {
      return true
    }
    
    // Check for Bearer tokens
    if (text =~ /(?i)(Authorization:\s*)?Bearer\s+[A-Za-z0-9\-_.]{10,}/) {
      return true
    }
    
    // Check for GitHub tokens
    if (text =~ /\bghp_[A-Za-z0-9]{16,}\b/) {
      return true
    }
    if (text =~ /\bgithub_pat_[A-Za-z0-9_]{10,}\b/) {
      return true
    }
    
    // Check for AWS keys
    if (text =~ /\bAKIA[0-9A-Z]{16}\b/) {
      return true
    }
    if (text =~ /\bASIA[0-9A-Z]{16}\b/) {
      return true
    }
    
    return false
  }
}
