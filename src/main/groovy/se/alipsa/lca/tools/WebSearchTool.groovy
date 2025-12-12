package se.alipsa.lca.tools

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

import java.util.function.Supplier

@Component
@CompileStatic
class WebSearchTool {

  private final Supplier<Playwright> playwrightSupplier

  WebSearchTool() {
    this({ Playwright.create() } as Supplier<Playwright>)
  }

  WebSearchTool(Supplier<Playwright> playwrightSupplier) {
    this.playwrightSupplier = playwrightSupplier
  }

  @Canonical
  @CompileStatic
  static class SearchResult {
    String title
    String url
    String snippet
  }

  List<SearchResult> search(String query) {
    Playwright playwright = playwrightSupplier.get()
    try (playwright) {
      Browser browser = playwright.chromium().launch()
      Page page = browser.newPage()
      page.navigate("https://duckduckgo.com/")
      page.fill("input[name='q']", query)
      page.press("input[name='q']", "Enter")
      page.waitForLoadState()

      List<SearchResult> results = page.querySelectorAll("div.results--mainline div.result").collect { element ->
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
