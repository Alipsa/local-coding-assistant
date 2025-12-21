package se.alipsa.lca.api

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

import java.nio.file.Path
import java.time.Duration
import java.util.LinkedHashSet
import java.util.Locale

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@CompileStatic
class RestSecurityFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RestSecurityFilter)
  private static final long WINDOW_MILLIS = 60_000L
  private static final int MAX_CACHE_SIZE = 10_000

  private final boolean localOnly
  private final boolean remoteEnabled
  private final boolean requireHttps
  private final String apiKey
  private final Set<String> apiKeyScopes
  private final int maxPerMinute
  private final Set<String> requiredReadScopes
  private final Set<String> requiredWriteScopes
  private final OidcTokenValidator oidcValidator
  private final Cache<String, RequestCounter> counters

  RestSecurityFilter(
    @Value('${assistant.local-only:true}') boolean localOnly,
    @Value('${assistant.rest.remote.enabled:false}') boolean remoteEnabled,
    @Value('${assistant.rest.require-https:true}') boolean requireHttps,
    @Value('${assistant.rest.api-key:}') String apiKey,
    @Value('${assistant.rest.api-key-scopes:}') String apiKeyScopes,
    @Value('${assistant.rest.rate-limit.per-minute:0}') int maxPerMinute,
    @Value('${assistant.rest.oidc.enabled:false}') boolean oidcEnabled,
    @Value('${assistant.rest.oidc.issuer:}') String oidcIssuer,
    @Value('${assistant.rest.oidc.audience:}') String oidcAudience,
    @Value('${assistant.rest.oidc.jwks-file:}') String oidcJwksFile,
    @Value('${assistant.rest.oidc.jwks-uri:}') String oidcJwksUri,
    @Value('${assistant.rest.oidc.jwks-timeout-millis:10000}') long oidcJwksTimeoutMillis,
    @Value('${assistant.rest.scope.read:}') String requiredReadScopes,
    @Value('${assistant.rest.scope.write:}') String requiredWriteScopes
  ) {
    this.localOnly = localOnly
    this.remoteEnabled = remoteEnabled
    this.requireHttps = requireHttps
    this.apiKey = apiKey != null ? apiKey.trim() : ""
    this.apiKeyScopes = parseScopes(apiKeyScopes)
    this.maxPerMinute = Math.max(0, maxPerMinute)
    this.requiredReadScopes = parseScopes(requiredReadScopes)
    this.requiredWriteScopes = parseScopes(requiredWriteScopes)
    if (oidcEnabled) {
      Path jwksPath = (oidcJwksFile != null && oidcJwksFile.trim()) ? Path.of(oidcJwksFile.trim()) : null
      this.oidcValidator = new OidcTokenValidator(oidcIssuer, oidcAudience, jwksPath, oidcJwksUri, oidcJwksTimeoutMillis)
    } else {
      this.oidcValidator = null
    }
    this.counters = Caffeine.newBuilder()
      .maximumSize(MAX_CACHE_SIZE)
      .expireAfterAccess(Duration.ofMinutes(5))
      .build()
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI()
    return path == null || !path.startsWith("/api/")
  }

  @Override
  protected void doFilterInternal(
    HttpServletRequest request,
    HttpServletResponse response,
    FilterChain filterChain
  ) {
    String addr = request.getRemoteAddr()
    boolean local = isLocal(addr)
    boolean remoteAllowed = remoteEnabled && !localOnly
    if (localOnly && !local) {
      deny(response, HttpServletResponse.SC_FORBIDDEN, "Local-only mode is enabled.")
      audit(request, response)
      return
    }
    if (!remoteAllowed && !local) {
      deny(response, HttpServletResponse.SC_FORBIDDEN, "Remote REST access is disabled.")
      audit(request, response)
      return
    }
    if (remoteAllowed && requireHttps && !request.isSecure() && !local) {
      deny(response, HttpServletResponse.SC_FORBIDDEN, "HTTPS is required for remote REST access.")
      audit(request, response)
      return
    }
    AuthContext auth = authenticate(request)
    if (auth.required && !auth.authenticated) {
      deny(response, HttpServletResponse.SC_UNAUTHORIZED, auth.message ?: "Missing or invalid credentials.")
      audit(request, response)
      return
    }
    Set<String> requiredScopes = isReadRequest(request.getMethod()) ? requiredReadScopes : requiredWriteScopes
    if (!requiredScopes.isEmpty()) {
      if (!auth.authenticated) {
        deny(response, HttpServletResponse.SC_FORBIDDEN, "Missing required scopes.")
        audit(request, response)
        return
      }
      if (!auth.scopes.containsAll(requiredScopes)) {
        deny(response, HttpServletResponse.SC_FORBIDDEN, "Missing required scopes.")
        audit(request, response)
        return
      }
    }
    if (maxPerMinute > 0 && !allowRate(addr)) {
      deny(response, 429, "Rate limit exceeded.")
      audit(request, response)
      return
    }
    try {
      filterChain.doFilter(request, response)
    } finally {
      audit(request, response)
    }
  }

  private void deny(HttpServletResponse response, int status, String message) {
    response.setStatus(status)
    response.setContentType("text/plain")
    response.getWriter().write(message)
  }

  private AuthContext authenticate(HttpServletRequest request) {
    boolean requireAuth = (apiKey != null && !apiKey.isEmpty()) || oidcValidator != null
    if (!requireAuth) {
      return AuthContext.anonymous()
    }
    String bearer = extractBearerToken(request)
    if (oidcValidator != null && bearer != null) {
      OidcTokenValidator.ValidationResult result = oidcValidator.validate(bearer)
      if (result.valid) {
        return AuthContext.authenticated(result.scopes)
      }
      return AuthContext.required(result.message)
    }
    if (!apiKey.isEmpty() && matchesApiKey(request, oidcValidator == null)) {
      return AuthContext.authenticated(apiKeyScopes)
    }
    return AuthContext.required("Missing or invalid credentials.")
  }

  private boolean matchesApiKey(HttpServletRequest request, boolean allowBearer) {
    String provided = request.getHeader("X-API-Key")
    if (allowBearer && (provided == null || provided.trim().isEmpty())) {
      String auth = request.getHeader("Authorization")
      if (auth != null && auth.startsWith("Bearer ")) {
        provided = auth.substring("Bearer ".length())
      }
    }
    provided != null && provided.trim() == apiKey
  }

  private static String extractBearerToken(HttpServletRequest request) {
    String auth = request.getHeader("Authorization")
    if (auth != null && auth.startsWith("Bearer ")) {
      return auth.substring("Bearer ".length()).trim()
    }
    null
  }

  private boolean allowRate(String addr) {
    String key = addr ?: "unknown"
    long now = System.currentTimeMillis()
    RequestCounter counter = counters.get(key, k -> new RequestCounter(now, 0))
    synchronized (counter) {
      if (now - counter.windowStart >= WINDOW_MILLIS) {
        counter.windowStart = now
        counter.count = 0
      }
      counter.count++
      return counter.count <= maxPerMinute
    }
  }

  private static boolean isLocal(String addr) {
    if (addr == null) {
      return false
    }
    return addr == "127.0.0.1" || addr == "::1" || addr == "0:0:0:0:0:0:0:1"
  }

  private static boolean isReadRequest(String method) {
    String resolved = method != null ? method.toUpperCase(Locale.ROOT) : ""
    return resolved == "GET" || resolved == "HEAD" || resolved == "OPTIONS"
  }

  private void audit(HttpServletRequest request, HttpServletResponse response) {
    String path = request.getRequestURI()
    String method = request.getMethod()
    String addr = request.getRemoteAddr()
    int status = response.getStatus()
    log.info("REST {} {} from {} -> {}", method, path, addr, status)
  }

  private static Set<String> parseScopes(String raw) {
    if (raw == null || raw.trim().isEmpty()) {
      return Set.of()
    }
    Set<String> scopes = new LinkedHashSet<>()
    raw.split(/[,\s]+/).each { String token ->
      if (token != null && !token.trim().isEmpty()) {
        scopes.add(token.trim())
      }
    }
    scopes
  }

  @CompileStatic
  private static class AuthContext {
    final boolean required
    final boolean authenticated
    final String message
    final Set<String> scopes

    private AuthContext(boolean required, boolean authenticated, String message, Set<String> scopes) {
      this.required = required
      this.authenticated = authenticated
      this.message = message
      this.scopes = scopes ?: Set.of()
    }

    static AuthContext anonymous() {
      new AuthContext(false, false, null, Set.of())
    }

    static AuthContext authenticated(Set<String> scopes) {
      new AuthContext(true, true, null, scopes)
    }

    static AuthContext required(String message) {
      new AuthContext(true, false, message, Set.of())
    }
  }

  @CompileStatic
  private static class RequestCounter {
    long windowStart
    int count

    RequestCounter(long windowStart, int count) {
      this.windowStart = windowStart
      this.count = count
    }
  }
}
