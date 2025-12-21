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
import se.alipsa.lca.tools.WebSearchTool

@RestController
@RequestMapping("/api/search")
@Validated
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
  List<WebSearchTool.SearchResult> search(@Valid @ModelAttribute SearchRequest request) {
    if (localOnly) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Web search is disabled in local-only mode.")
    }
    boolean defaultEnabled = webSearchEnabledDefault
    WebSearchTool.SearchOptions options = WebSearchTool.withDefaults(
      new WebSearchTool.SearchOptions(
        provider: WebSearchTool.providerFrom(request.provider),
        limit: request.limit,
        headless: request.headless,
        timeoutMillis: request.timeoutMillis,
        webSearchEnabled: request.enabled
      ),
      defaultEnabled
    )
    webSearchAgent.search(request.query, options)
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
  }
}
