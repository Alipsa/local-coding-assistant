package se.alipsa.lca.api

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

import java.util.concurrent.ConcurrentHashMap

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@CompileStatic
class RestSecurityFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RestSecurityFilter)
  private static final long WINDOW_MILLIS = 60_000L

  private final boolean remoteEnabled
  private final boolean requireHttps
  private final String apiKey
  private final int maxPerMinute
  private final Map<String, RequestCounter> counters = new ConcurrentHashMap<>()

  RestSecurityFilter(
    @Value('${assistant.rest.remote.enabled:false}') boolean remoteEnabled,
    @Value('${assistant.rest.require-https:true}') boolean requireHttps,
    @Value('${assistant.rest.api-key:}') String apiKey,
    @Value('${assistant.rest.rate-limit.per-minute:0}') int maxPerMinute
  ) {
    this.remoteEnabled = remoteEnabled
    this.requireHttps = requireHttps
    this.apiKey = apiKey != null ? apiKey.trim() : ""
    this.maxPerMinute = Math.max(0, maxPerMinute)
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
    if (!remoteEnabled && !isLocal(addr)) {
      deny(response, HttpServletResponse.SC_FORBIDDEN, "Remote REST access is disabled.")
      audit(request, response)
      return
    }
    if (remoteEnabled && requireHttps && !request.isSecure()) {
      deny(response, HttpServletResponse.SC_FORBIDDEN, "HTTPS is required for remote REST access.")
      audit(request, response)
      return
    }
    if (apiKey && !matchesApiKey(request)) {
      deny(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid API key.")
      audit(request, response)
      return
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

  private boolean matchesApiKey(HttpServletRequest request) {
    String provided = request.getHeader("X-API-Key")
    if (provided == null || provided.trim().isEmpty()) {
      String auth = request.getHeader("Authorization")
      if (auth != null && auth.startsWith("Bearer ")) {
        provided = auth.substring("Bearer ".length())
      }
    }
    provided != null && provided.trim() == apiKey
  }

  private boolean allowRate(String addr) {
    String key = addr ?: "unknown"
    long now = System.currentTimeMillis()
    RequestCounter counter = counters.computeIfAbsent(key) { new RequestCounter(now, 0) }
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

  private void audit(HttpServletRequest request, HttpServletResponse response) {
    String path = request.getRequestURI()
    String method = request.getMethod()
    String addr = request.getRemoteAddr()
    int status = response.getStatus()
    log.info("REST {} {} from {} -> {}", method, path, addr, status)
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
