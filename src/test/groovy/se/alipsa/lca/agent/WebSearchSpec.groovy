package se.alipsa.lca.agent

import org.jsoup.Jsoup
import se.alipsa.lca.tools.WebSearchTool
import spock.lang.Specification

class WebSearchSpec extends Specification {

  def "search parses and sanitises results with options"() {
    given:
    String html = """
      <html>
        <body>
          <div class="results">
            <div class="result">
              <a class="result__a" href="http://example.com">Test    Title</a>
              <a class="result__snippet">Test   Snippet with   spacing</a>
            </div>
          </div>
        </body>
      </html>
      """.stripIndent()
    def fetcher = Mock(WebSearchTool.SearchFetcher)
    def tool = new WebSearchTool(fetcher)

    when:
    def results = tool.search(
      "test query",
      new WebSearchTool.SearchOptions(
        limit: 3,
        headless: false,
        timeoutMillis: 1000L,
        sessionId: "s1",
        provider: WebSearchTool.SearchProvider.DUCKDUCKGO,
        webSearchEnabled: true
      )
    )

    then:
    results.size() == 1
    results[0].title == "Test Title"
    results[0].url == "http://example.com"
    results[0].snippet.startsWith("Test Snippet with spacing")
    1 * fetcher.fetch(WebSearchTool.SearchProvider.DUCKDUCKGO, "test query", _) >> Jsoup.parse(html)
  }

  def "search uses cache for repeated queries in the same session"() {
    given:
    int fetchCalls = 0
    String html = """
      <html>
        <body>
          <div class="results">
            <div class="result">
              <a class="result__a" href="http://example.com">Cached</a>
            </div>
          </div>
        </body>
      </html>
      """.stripIndent()
    def fetcher = { WebSearchTool.SearchProvider provider, String query, WebSearchTool.SearchOptions opts ->
      fetchCalls++
      Jsoup.parse(html)
    } as WebSearchTool.SearchFetcher
    def tool = new WebSearchTool(fetcher)

    when:
    def first = tool.search(
      "cached query",
      new WebSearchTool.SearchOptions(limit: 3, sessionId: "s1", webSearchEnabled: true)
    )
    def second = tool.search(
      "cached query",
      new WebSearchTool.SearchOptions(limit: 2, sessionId: "s1", webSearchEnabled: true)
    )

    then:
    fetchCalls == 1
    first.size() == 1
    second.size() == 1
  }

  def "returns disabled result when web search is disabled"() {
    given:
    def fetcher = { WebSearchTool.SearchProvider provider, String query, WebSearchTool.SearchOptions opts ->
      throw new IllegalStateException("Should not be called when disabled")
    } as WebSearchTool.SearchFetcher
    def tool = new WebSearchTool(fetcher)

    when:
    def results = tool.search("query", new WebSearchTool.SearchOptions(webSearchEnabled: false))

    then:
    results.size() == 1
    results[0].title == "Web search disabled"
  }

  def "returns failure result on exception"() {
    given:
    def fetcher = { WebSearchTool.SearchProvider provider, String query, WebSearchTool.SearchOptions opts ->
      throw new RuntimeException("boom")
    } as WebSearchTool.SearchFetcher
    def tool = new WebSearchTool(fetcher)

    when:
    def results = tool.search("query", new WebSearchTool.SearchOptions(webSearchEnabled: true))

    then:
    results.size() == 1
    results[0].title == "Web search unavailable"
    results[0].snippet.contains("boom")
  }

  def "returns empty list for blank query without calling supplier"() {
    given:
    int calls = 0
    def fetcher = { WebSearchTool.SearchProvider provider, String query, WebSearchTool.SearchOptions opts ->
      calls++
      Jsoup.parse("<html></html>")
    } as WebSearchTool.SearchFetcher
    def tool = new WebSearchTool(fetcher)

    when:
    def results = tool.search("   ", new WebSearchTool.SearchOptions(webSearchEnabled: true))

    then:
    results.isEmpty()
    calls == 0
  }

  def "htmlunit fetcher builds query url and parses response"() {
    given:
    String html = """
      <html>
        <body>
          <div class="results">
            <div class="result">
              <a class="result__a" href="http://example.com">Title</a>
              <a class="result__snippet">Snippet</a>
            </div>
          </div>
        </body>
      </html>
      """.stripIndent()
    def fetcher = new WebSearchTool.HtmlUnitSearchFetcher() {
      String requestedUrl

      @Override
      protected String fetchContent(String requestUrl, WebSearchTool.SearchOptions options) {
        requestedUrl = requestUrl
        return html
      }
    }

    when:
    def document = fetcher.fetch(
      WebSearchTool.SearchProvider.DUCKDUCKGO,
      "hello world",
      new WebSearchTool.SearchOptions(timeoutMillis: 500L)
    )

    then:
    document.select("a.result__a").text() == "Title"
    fetcher.requestedUrl.startsWith("https://duckduckgo.com/html/")
    fetcher.requestedUrl.contains("q=hello+world")
  }
}
