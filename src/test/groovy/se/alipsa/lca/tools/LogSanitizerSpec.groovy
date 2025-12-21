package se.alipsa.lca.tools

import spock.lang.Specification

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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
  
  def "redacts URL-encoded secrets"() {
    given:
    String secret = "apiKey=supersecret123"
    String urlEncoded = URLEncoder.encode(secret, StandardCharsets.UTF_8.toString())
    String input = "Query: ${urlEncoded}"
    
    when:
    String result = LogSanitizer.sanitize(input)
    
    then:
    result == "Query: REDACTED"
  }
  
  def "redacts URL-encoded GitHub token"() {
    given:
    String secret = "ghp_1234567890abcdefghij"
    String urlEncoded = URLEncoder.encode(secret, StandardCharsets.UTF_8.toString())
    String input = "token=${urlEncoded}"
    
    when:
    String result = LogSanitizer.sanitize(input)
    
    then:
    result == "token=REDACTED"
  }
  
  def "redacts URL-encoded AWS key"() {
    given:
    String secret = "AKIAABCDEFGHIJKLMNOP"
    String urlEncoded = URLEncoder.encode(secret, StandardCharsets.UTF_8.toString())
    String input = "key=${urlEncoded}"
    
    when:
    String result = LogSanitizer.sanitize(input)
    
    then:
    result == "key=REDACTED"
  }
  
  def "redacts Base64-encoded secrets"() {
    given:
    String secret = "password=mysecretpassword123"
    String base64Encoded = Base64.encoder.encodeToString(secret.bytes)
    String input = "Data: ${base64Encoded}"
    
    when:
    String result = LogSanitizer.sanitize(input)
    
    then:
    result == "Data: REDACTED"
  }
  
  def "redacts Base64-encoded GitHub token"() {
    given:
    String secret = "ghp_abcdefghijklmnopqrstuvwxyz123456"
    String base64Encoded = Base64.encoder.encodeToString(secret.bytes)
    String input = "token: ${base64Encoded}"
    
    when:
    String result = LogSanitizer.sanitize(input)
    
    then:
    result == "token: REDACTED"
  }
  
  def "redacts Base64-encoded AWS access key"() {
    given:
    String secret = "AKIAIOSFODNN7EXAMPLE"
    String base64Encoded = Base64.encoder.encodeToString(secret.bytes)
    String input = "credentials: ${base64Encoded}"
    
    when:
    String result = LogSanitizer.sanitize(input)
    
    then:
    result == "credentials: REDACTED"
  }
  
  def "does not redact innocuous URL-encoded text"() {
    given:
    String innocent = "Hello World"
    String urlEncoded = URLEncoder.encode(innocent, StandardCharsets.UTF_8.toString())
    String input = "message=${urlEncoded}"
    
    when:
    String result = LogSanitizer.sanitize(input)
    
    then:
    result == input
  }
  
  def "does not redact innocuous Base64 text"() {
    given:
    String innocent = "Just some regular text"
    String base64Encoded = Base64.encoder.encodeToString(innocent.bytes)
    String input = "data: ${base64Encoded}"
    
    when:
    String result = LogSanitizer.sanitize(input)
    
    then:
    result == input
  }
  
  def "redacts secrets in query strings with URL encoding"() {
    given:
    String input = "GET /api?token=" + URLEncoder.encode("Bearer abc123token", StandardCharsets.UTF_8.toString())
    
    when:
    String result = LogSanitizer.sanitize(input)
    
    then:
    result == "GET /api?token=REDACTED"
  }
  
  def "handles mixed encoded and plain text"() {
    given:
    String plainSecret = "apiKey=plain123"
    String encodedSecret = URLEncoder.encode("password=encoded456", StandardCharsets.UTF_8.toString())
    String input = "${plainSecret} and ${encodedSecret}"
    
    when:
    String result = LogSanitizer.sanitize(input)
    
    then:
    result == "apiKey=REDACTED and REDACTED"
  }
}
