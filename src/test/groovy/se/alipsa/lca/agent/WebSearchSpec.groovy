package se.alipsa.lca.agent

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.ElementHandle
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import se.alipsa.lca.tools.WebSearchTool
import spock.lang.Specification

class WebSearchSpec extends Specification {

    def "search parses results from the page"() {
        given:
        def mockPlaywright = Mock(Playwright)
        def mockBrowserType = Mock(BrowserType)
        def mockBrowser = Mock(Browser)
        def mockPage = Mock(Page)
        def mockElement = Mock(ElementHandle)
        def mockTitleElement = Mock(ElementHandle)
        def mockSnippetElement = Mock(ElementHandle)

        Playwright.create() >> mockPlaywright
        mockPlaywright.chromium() >> mockBrowserType
        mockBrowserType.launch() >> mockBrowser
        mockBrowser.newPage() >> mockPage
        mockPage.querySelectorAll(_) >> [mockElement]
        mockElement.querySelector("h2 a") >> mockTitleElement
        mockTitleElement.innerText() >> "Test Title"
        mockTitleElement.getAttribute("href") >> "http://example.com"
        mockElement.querySelector("span.result__snippet") >> mockSnippetElement
        mockSnippetElement.innerText() >> "Test Snippet"
        def tool = new WebSearchTool({ mockPlaywright } as java.util.function.Supplier<Playwright>)

        when:
        def results = tool.search("test query")

        then:
        results.size() == 1
        results[0].title == "Test Title"
        results[0].url == "http://example.com"
        results[0].snippet == "Test Snippet"
        1 * mockPage.navigate(_)
        1 * mockPage.fill(_, _)
        1 * mockPage.press(_, _)
        1 * mockPage.waitForLoadState()
        1 * mockBrowser.close()
    }
}
