package se.alipsa.lca.api

import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import org.springframework.web.server.ResponseStatusException
import se.alipsa.lca.shell.SessionState
import se.alipsa.lca.tools.WebSearchTool
import spock.lang.Specification

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class WebSearchControllerSpec extends Specification {

  WebSearchTool webSearchTool = Mock()
  SessionState sessionState = Mock()
  LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean()
  MockMvc mvc

  def setup() {
    validator.afterPropertiesSet()
    mvc = MockMvcBuilders.standaloneSetup(new WebSearchController(webSearchTool, sessionState, true, false))
      .setValidator(validator)
      .build()
  }

  def "rejects invalid requests before executing search"() {
    when:
    def response = mvc.perform(get("/api/search")
      .param("query", " ")
      .param("limit", "0"))

    then:
    response.andExpect(status().isBadRequest())
    0 * webSearchTool.search(_, _)
    0 * sessionState.storeToolSummary(_, _)
  }

  def "delegates search with validated defaults"() {
    when:
    def response = mvc.perform(get("/api/search")
      .param("query", "groovy validation")
      .param("limit", "3")
      .param("timeoutMillis", "2000")
      .param("provider", "duckduckgo"))

    then:
    response.andExpect(status().isOk())
    1 * webSearchTool.search("groovy validation", {
      WebSearchTool.SearchOptions opts ->
        opts.limit == 3 &&
        opts.timeoutMillis == 2000L &&
        opts.provider == WebSearchTool.SearchProvider.DUCKDUCKGO &&
        opts.sessionId == "default" &&
        opts.webSearchEnabled
    }) >> [
      new WebSearchTool.SearchResult("Result", "http://example.com", "Snippet")
    ]
    1 * sessionState.storeToolSummary("default", {
      SessionState.ToolSummary summary ->
        summary.source == "web-search" &&
          summary.summary.contains("groovy validation")
    })
  }

  def "rejects local-only web search with a generic message"() {
    given:
    WebSearchController controller = new WebSearchController(webSearchTool, sessionState, true, true)
    WebSearchController.SearchRequest request = new WebSearchController.SearchRequest(query: "groovy search")

    when:
    controller.search(request)

    then:
    ResponseStatusException ex = thrown()
    ex.statusCode.value() == 403
    ex.reason == "Request not permitted."
  }
}
