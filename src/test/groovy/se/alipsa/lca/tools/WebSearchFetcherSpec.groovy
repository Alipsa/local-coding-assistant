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
}
