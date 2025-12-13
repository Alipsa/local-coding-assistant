package se.alipsa.lca.agent

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.ElementHandle
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import se.alipsa.lca.tools.WebSearchTool
import spock.lang.Specification

class WebSearchSpec extends Specification {

  def "search parses and sanitizes results with options"() {
    given:
    def mockPlaywright = Mock(Playwright)
    def mockBrowserType = Mock(BrowserType)
    def mockBrowser = Mock(Browser)
    def mockPage = Mock(Page)
    def mockElement = Mock(ElementHandle)
    def mockTitleElement = Mock(ElementHandle)
    def mockSnippetElement = Mock(ElementHandle)

    mockPlaywright.chromium() >> mockBrowserType
    mockBrowserType.launch(_ as BrowserType.LaunchOptions) >> mockBrowser
    mockBrowser.newPage() >> mockPage
    mockPage.querySelectorAll(_) >> [mockElement]
    mockElement.querySelector("h2 a") >> mockTitleElement
    mockTitleElement.innerText() >> "Test    Title"
    mockTitleElement.getAttribute("href") >> "http://example.com"
    mockElement.querySelector("span.result__snippet") >> mockSnippetElement
    mockSnippetElement.innerText() >> "Test   Snippet with   spacing"
    def tool = new WebSearchTool({ mockPlaywright } as java.util.function.Supplier<Playwright>)

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
    1 * mockPage.setDefaultTimeout(1000L)
    1 * mockPage.navigate("https://duckduckgo.com/")
    1 * mockPage.fill(_, _)
    1 * mockPage.press(_, _)
    1 * mockPage.waitForSelector("div.results--mainline", _ as com.microsoft.playwright.Page.WaitForSelectorOptions)
    1 * mockBrowser.close()
    1 * mockPlaywright.close()
  }

  def "search uses cache for repeated queries in the same session"() {
    given:
    int supplierCalls = 0
    def mockPlaywright = Mock(Playwright)
    def mockBrowserType = Mock(BrowserType)
    def mockBrowser = Mock(Browser)
    def mockPage = Mock(Page)
    def mockElement = Mock(ElementHandle)
    def mockTitleElement = Mock(ElementHandle)
    mockPlaywright.chromium() >> mockBrowserType
    mockBrowserType.launch(_ as BrowserType.LaunchOptions) >> mockBrowser
    mockBrowser.newPage() >> mockPage
    mockPage.querySelectorAll(_) >> [mockElement]
    mockElement.querySelector("h2 a") >> mockTitleElement
    mockTitleElement.innerText() >> "Cached"
    mockTitleElement.getAttribute("href") >> "http://example.com"
    def tool = new WebSearchTool({
      supplierCalls++
      mockPlaywright
    } as java.util.function.Supplier<Playwright>)

    when:
    def first = tool.search("cached query", new WebSearchTool.SearchOptions(limit: 3, sessionId: "s1", webSearchEnabled: true))
    def second = tool.search("cached query", new WebSearchTool.SearchOptions(limit: 2, sessionId: "s1", webSearchEnabled: true))

    then:
    supplierCalls == 1
    first.size() == 1
    second.size() == 1
  }
}
