package se.alipsa.lca.tools

import groovy.transform.Canonical
import com.microsoft.playwright.*
import org.springframework.stereotype.Component

@Component
class WebSearchTool {

    @Canonical
    static class SearchResult {
        String title
        String url
        String snippet
    }

    List<SearchResult> search(String query) {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch()
            Page page = browser.newPage()
            page.navigate("https://duckduckgo.com/")
            page.fill("input[name='q']", query)
            page.press("input[name='q']", "Enter")
            page.waitForLoadState()

            def results = page.querySelectorAll("div.results--mainline div.result").collect { element ->
                def titleElement = element.querySelector("h2 a")
                def snippetElement = element.querySelector("span.result__snippet")
                new SearchResult(
                        title: titleElement?.innerText(),
                        url: titleElement?.getAttribute("href"),
                        snippet: snippetElement?.innerText()
                )
            }
            browser.close()
            return results
        }
    }
}
