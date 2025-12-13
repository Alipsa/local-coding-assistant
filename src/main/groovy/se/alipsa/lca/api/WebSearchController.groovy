package se.alipsa.lca.api

import groovy.transform.CompileStatic
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.beans.factory.annotation.Value
import se.alipsa.lca.tools.WebSearchTool

@RestController
@RequestMapping("/api/search")
@CompileStatic
class WebSearchController {

  private final WebSearchTool webSearchAgent
  private final boolean webSearchEnabledDefault

  WebSearchController(
    WebSearchTool webSearchAgent,
    @Value('${assistant.web-search.enabled:true}') boolean webSearchEnabledDefault
  ) {
    this.webSearchAgent = webSearchAgent
    this.webSearchEnabledDefault = webSearchEnabledDefault
  }

  @GetMapping
  List<WebSearchTool.SearchResult> search(
    @RequestParam String query,
    @RequestParam(defaultValue = "5") int limit,
    @RequestParam(defaultValue = "duckduckgo") String provider,
    @RequestParam(defaultValue = "15000") long timeoutMillis,
    @RequestParam(defaultValue = "true") boolean headless,
    @RequestParam(defaultValue = "true") boolean enabled
  ) {
    WebSearchTool.SearchOptions options = WebSearchTool.withDefaults(
      new WebSearchTool.SearchOptions(
        provider: WebSearchTool.providerFrom(provider),
        limit: limit,
        headless: headless,
        timeoutMillis: timeoutMillis,
        webSearchEnabled: enabled
      ),
      webSearchEnabledDefault
    )
    webSearchAgent.search(query, options)
  }
}
