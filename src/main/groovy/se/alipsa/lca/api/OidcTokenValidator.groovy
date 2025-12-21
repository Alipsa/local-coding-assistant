package se.alipsa.lca.api

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.RemoteJWKSet
import com.nimbusds.jose.proc.JWSAlgorithmFamilyJWSKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jose.util.DefaultResourceRetriever
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.file.Path
import java.nio.file.Files
import java.util.Date
import java.net.MalformedURLException
import java.net.URL
import java.io.IOException
import java.util.concurrent.TimeUnit

@CompileStatic
class OidcTokenValidator {

  private static final Logger log = LoggerFactory.getLogger(OidcTokenValidator)

  private final String issuer
  private final String audience
  private final ConfigurableJWTProcessor<SecurityContext> processor

  OidcTokenValidator(String issuer, String audience, Path jwksFile, String jwksUri, long timeoutMillis) {
    this.issuer = issuer?.trim()
    this.audience = audience?.trim()
    JWKSource<SecurityContext> source = buildSource(jwksFile, jwksUri, timeoutMillis)
    this.processor = new DefaultJWTProcessor<>()
    this.processor.setJWSKeySelector(new JWSAlgorithmFamilyJWSKeySelector<>(JWSAlgorithm.Family.RSA, source))
  }

  ValidationResult validate(String token) {
    if (token == null || token.trim().isEmpty()) {
      return ValidationResult.invalid("Missing bearer token.")
    }
    try {
      JWTClaimsSet claims = processor.process(token, null)
      if (issuer && claims.issuer != issuer) {
        return ValidationResult.invalid("Issuer mismatch.")
      }
      if (audience && (claims.audience == null || !claims.audience.contains(audience))) {
        return ValidationResult.invalid("Audience mismatch.")
      }
      Date now = new Date()
      Date exp = claims.expirationTime
      if (exp != null && now.after(exp)) {
        return ValidationResult.invalid("Token expired.")
      }
      Date nbf = claims.notBeforeTime
      if (nbf != null && now.before(nbf)) {
        return ValidationResult.invalid("Token not active yet.")
      }
      Set<String> scopes = extractScopes(claims)
      return ValidationResult.valid(scopes)
    } catch (Exception e) {
      log.warn("Token validation failed: {}", e.message, e)
      return ValidationResult.invalid("Token validation failed.")
    }
  }

  private static JWKSource<SecurityContext> buildSource(Path jwksFile, String jwksUri, long timeoutMillis) {
    if (jwksFile != null) {
      try {
        String content = Files.readString(jwksFile)
        return new ImmutableJWKSet<>(JWKSet.parse(content))
      } catch (IOException e) {
        throw new IllegalStateException("Failed to read JWKS file: ${jwksFile}", e)
      }
    }
    if (jwksUri != null && jwksUri.trim()) {
      long timeout = timeoutMillis > 0 ? timeoutMillis : TimeUnit.SECONDS.toMillis(2)
      DefaultResourceRetriever retriever = new DefaultResourceRetriever((int) timeout, (int) timeout)
      try {
        URL url = new URL(jwksUri.trim())
        return new RemoteJWKSet<>(url, retriever)
      } catch (MalformedURLException e) {
        throw new IllegalStateException("Invalid JWKS URI: ${jwksUri}", e)
      }
    }
    throw new IllegalStateException("OIDC is enabled but no JWKS file or URI is configured.")
  }

  private static Set<String> extractScopes(JWTClaimsSet claims) {
    Set<String> scopes = new LinkedHashSet<>()
    Object scopeClaim = claims.getClaim("scope")
    Object scpClaim = claims.getClaim("scp")
    appendScopes(scopes, scopeClaim)
    appendScopes(scopes, scpClaim)
    scopes
  }

  private static void appendScopes(Set<String> scopes, Object claim) {
    if (claim == null) {
      return
    }
    if (claim instanceof String) {
      String raw = ((String) claim).trim()
      if (!raw.isEmpty()) {
        raw.split(/[,\s]+/).each { String entry ->
          if (entry != null && !entry.trim().isEmpty()) {
            scopes.add(entry.trim())
          }
        }
      }
      return
    }
    if (claim instanceof Collection) {
      ((Collection) claim).each { Object entry ->
        if (entry != null) {
          String value = entry.toString().trim()
          if (!value.isEmpty()) {
            scopes.add(value)
          }
        }
      }
    }
  }

  @CompileStatic
  static class ValidationResult {
    final boolean valid
    final String message
    final Set<String> scopes

    private ValidationResult(boolean valid, String message, Set<String> scopes) {
      this.valid = valid
      this.message = message
      this.scopes = scopes ?: Set.of()
    }

    static ValidationResult valid(Set<String> scopes) {
      new ValidationResult(true, null, scopes)
    }

    static ValidationResult invalid(String message) {
      new ValidationResult(false, message, Set.of())
    }
  }
}
