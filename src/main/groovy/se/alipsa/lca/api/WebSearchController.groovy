package se.alipsa.lca.api

import groovy.transform.CompileStatic
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import se.alipsa.lca.tools.WebSearchTool

@RestController
@RequestMapping("/api/search")
@CompileStatic
class WebSearchController {

  private final WebSearchTool webSearchAgent
  private final boolean webSearchEnabledDefault
  private final boolean localOnly

  WebSearchController(
    WebSearchTool webSearchAgent,
    @Value('${assistant.web-search.enabled:true}') boolean webSearchEnabledDefault,
    @Value('${assistant.local-only:true}') boolean localOnly
  ) {
    this.webSearchAgent = webSearchAgent
    this.webSearchEnabledDefault = webSearchEnabledDefault
    this.localOnly = localOnly
  }

  @GetMapping
  List<WebSearchTool.SearchResult> search(
    @RequestParam String query,
    @RequestParam(defaultValue = "5") int limit,
    @RequestParam(defaultValue = "duckduckgo") String provider,
    @RequestParam(defaultValue = "15000") long timeoutMillis,
    @RequestParam(defaultValue = "true") boolean headless,
    @RequestParam(required = false) Boolean enabled
  ) {
    if (localOnly) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Web search is disabled in local-only mode.")
    }
    requireNonBlank(query, "query")
    requireMin(limit, 1, "limit")
    requireMin(timeoutMillis, 1, "timeoutMillis")
    boolean defaultEnabled = webSearchEnabledDefault
    WebSearchTool.SearchOptions options = WebSearchTool.withDefaults(
      new WebSearchTool.SearchOptions(
        provider: WebSearchTool.providerFrom(provider),
        limit: limit,
        headless: headless,
        timeoutMillis: timeoutMillis,
        webSearchEnabled: enabled
    ),
    defaultEnabled
  )
    webSearchAgent.search(query, options)
  }

  private static void requireMin(long value, long min, String field) {
    if (value < min) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "${field} must be >= ${min}")
    }
  }

  private static String requireNonBlank(String value, String field) {
    if (value == null || value.trim().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "${field} must not be blank")
    }
    value
  }
}
