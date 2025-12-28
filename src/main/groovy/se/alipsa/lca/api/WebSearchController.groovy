package se.alipsa.lca.api

import groovy.transform.Canonical
import groovy.transform.CompileStatic
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import se.alipsa.lca.shell.SessionState
import se.alipsa.lca.tools.WebSearchTool

import java.time.Instant

@RestController
@RequestMapping("/api/search")
@Validated
@CompileStatic
class WebSearchController {

  private static final int WEB_SEARCH_SUMMARY_LIMIT = 3
  private static final int WEB_SEARCH_SUMMARY_MAX_CHARS = 1200
  private final WebSearchTool webSearchAgent
  private final SessionState sessionState
  private final boolean webSearchEnabledDefault
  private final boolean localOnly

  WebSearchController(
    WebSearchTool webSearchAgent,
    SessionState sessionState,
    @Value('${assistant.web-search.enabled:true}') boolean webSearchEnabledDefault,
    @Value('${assistant.local-only:true}') boolean localOnly
  ) {
    this.webSearchAgent = webSearchAgent
    this.sessionState = sessionState
    this.webSearchEnabledDefault = webSearchEnabledDefault
    this.localOnly = localOnly
  }

  @GetMapping
  List<WebSearchTool.SearchResult> search(@Valid @ModelAttribute SearchRequest request) {
    if (localOnly) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Request not permitted.")
    }
    String sessionId = request.session != null && request.session.trim() ? request.session.trim() : "default"
    boolean defaultEnabled = webSearchEnabledDefault
    WebSearchTool.SearchOptions options = WebSearchTool.withDefaults(
      new WebSearchTool.SearchOptions(
        provider: WebSearchTool.providerFrom(request.provider),
        limit: request.limit,
        headless: request.headless,
        timeoutMillis: request.timeoutMillis,
        sessionId: sessionId,
        webSearchEnabled: request.enabled
      ),
      defaultEnabled
    )
    List<WebSearchTool.SearchResult> results = webSearchAgent.search(request.query, options)
    String summary = WebSearchTool.summariseResults(
      request.query,
      results,
      WEB_SEARCH_SUMMARY_LIMIT,
      WEB_SEARCH_SUMMARY_MAX_CHARS
    )
    sessionState.storeToolSummary(sessionId, new SessionState.ToolSummary("web-search", summary, Instant.now()))
    results
  }

  @Canonical
  @CompileStatic
  static class SearchRequest {
    @NotBlank
    String query

    @Min(1)
    int limit = 5

    @NotBlank
    String provider = "duckduckgo"

    @Min(1)
    long timeoutMillis = 15000L

    boolean headless = true
    Boolean enabled
    String session
  }
}
