package se.alipsa.lca.tools

import org.jsoup.Jsoup
import spock.lang.Specification
import spock.lang.Unroll

class WebSearchFetcherSpec extends Specification {

  @Unroll
  def "resolve fetcher kinds for '#primary' and '#fallback'"() {
    expect:
    WebSearchTool.resolveFetcherKinds(primary, fallback) == expected

    where:
    primary     | fallback    || expected
    null        | null        || [WebSearchTool.FetcherKind.HTMLUNIT]
    "jsoup"     | "htmlunit"  || [WebSearchTool.FetcherKind.JSOUP, WebSearchTool.FetcherKind.HTMLUNIT]
    "htmlunit"  | "htmlunit"  || [WebSearchTool.FetcherKind.HTMLUNIT]
    "jsoup"     | "none"      || [WebSearchTool.FetcherKind.JSOUP]
    "unknown"   | "jsoup"     || [WebSearchTool.FetcherKind.HTMLUNIT, WebSearchTool.FetcherKind.JSOUP]
  }

  def "composite fetcher falls back on failure"() {
    given:
    String html = """
      <html>
        <body>
          <div class="results">
            <div class="result">
              <a class="result__a" href="http://example.com">Fallback Title</a>
              <a class="result__snippet">Fallback Snippet</a>
            </div>
          </div>
        </body>
      </html>
      """.stripIndent()
    int primaryCalls = 0
    int fallbackCalls = 0
    def primary = { WebSearchTool.SearchProvider provider, String query, WebSearchTool.SearchOptions options ->
      primaryCalls++
      throw new RuntimeException("Primary failed")
    } as WebSearchTool.SearchFetcher
    def fallback = { WebSearchTool.SearchProvider provider, String query, WebSearchTool.SearchOptions options ->
      fallbackCalls++
      Jsoup.parse(html)
    } as WebSearchTool.SearchFetcher
    def composite = new WebSearchTool.CompositeSearchFetcher([primary, fallback])
    def tool = new WebSearchTool(composite)

    when:
    def results = tool.search("query", new WebSearchTool.SearchOptions(webSearchEnabled: true))

    then:
    primaryCalls == 1
    fallbackCalls == 1
    results.size() == 1
    results[0].title == "Fallback Title"
  }

  def "SearchResult can be converted to InternetResource"() {
    given:
    def searchResult = new WebSearchTool.SearchResult("Example Title", "http://example.com", "Example snippet")

    when:
    def internetResource = searchResult.toInternetResource()

    then:
    internetResource != null
    internetResource.url == "http://example.com"
    internetResource.summary == "Example snippet"
  }

  def "SearchResult uses title as summary when snippet is null"() {
    given:
    def searchResult = new WebSearchTool.SearchResult("Example Title", "http://example.com", null)

    when:
    def internetResource = searchResult.toInternetResource()

    then:
    internetResource != null
    internetResource.url == "http://example.com"
    internetResource.summary == "Example Title"
  }

  def "searchAsInternetResources converts results"() {
    given:
    String html = """
      <html>
        <body>
          <div class="results">
            <div class="result">
              <a class="result__a" href="http://example1.com">Title 1</a>
              <a class="result__snippet">Snippet 1</a>
            </div>
            <div class="result">
              <a class="result__a" href="http://example2.com">Title 2</a>
              <a class="result__snippet">Snippet 2</a>
            </div>
          </div>
        </body>
      </html>
      """.stripIndent()
    def fetcher = { WebSearchTool.SearchProvider provider, String query, WebSearchTool.SearchOptions options ->
      Jsoup.parse(html)
    } as WebSearchTool.SearchFetcher
    def tool = new WebSearchTool(fetcher)

    when:
    def resources = tool.searchAsInternetResources("query", new WebSearchTool.SearchOptions(webSearchEnabled: true))

    then:
    resources.size() == 2
    resources[0].url == "http://example1.com"
    resources[0].summary == "Snippet 1"
    resources[1].url == "http://example2.com"
    resources[1].summary == "Snippet 2"
  }

  def "searchAsWebSearchResults creates wrapper"() {
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
    def fetcher = { WebSearchTool.SearchProvider provider, String query, WebSearchTool.SearchOptions options ->
      Jsoup.parse(html)
    } as WebSearchTool.SearchFetcher
    def tool = new WebSearchTool(fetcher)

    when:
    def webSearchResults = tool.searchAsWebSearchResults("query", new WebSearchTool.SearchOptions(webSearchEnabled: true))

    then:
    webSearchResults != null
    webSearchResults.links.size() == 1
    webSearchResults.links[0].url == "http://example.com"
    webSearchResults.links[0].summary == "Snippet"
  }
}
