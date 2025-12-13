package se.alipsa.lca.tools

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.springframework.stereotype.Component

import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.Map
import java.util.function.Supplier

@Component
@CompileStatic
class WebSearchTool {

  private final Supplier<Playwright> playwrightSupplier
  private final Map<String, CacheEntry> cache = Collections.synchronizedMap(new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
      size() > 20
    }
  })

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

  @Canonical
  @CompileStatic
  static class SearchOptions {
    SearchProvider provider = SearchProvider.DUCKDUCKGO
    int limit = 5
    boolean headless = true
    long timeoutMillis = 15000L
    String sessionId = "default"
    Boolean webSearchEnabled
  }

  @CompileStatic
  static SearchOptions withDefaults(SearchOptions options, boolean defaultEnabled) {
    SearchOptions incoming = options ?: new SearchOptions()
    SearchOptions resolved = new SearchOptions()
    resolved.provider = incoming.provider ?: SearchProvider.DUCKDUCKGO
    resolved.limit = Math.min(Math.max(1, incoming.limit), 10)
    resolved.headless = incoming.headless
    resolved.timeoutMillis = incoming.timeoutMillis > 0 ? incoming.timeoutMillis : 15000L
    resolved.sessionId = incoming.sessionId ?: "default"
    resolved.webSearchEnabled = incoming.webSearchEnabled != null ? incoming.webSearchEnabled : defaultEnabled
    resolved
  }

  @CompileStatic
  static SearchProvider providerFrom(String value) {
    if (value == null) {
      return SearchProvider.DUCKDUCKGO
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT)
    SearchProvider.values().find { it.name() == normalized } ?: SearchProvider.DUCKDUCKGO
  }

  @CompileStatic
  enum SearchProvider {
    DUCKDUCKGO
  }

  List<SearchResult> search(String query) {
    search(query, new SearchOptions())
  }

  List<SearchResult> search(String query, SearchOptions options) {
    SearchOptions resolved = withDefaults(options, true)
    if (!resolved.webSearchEnabled) {
      return List.of(disabledResult())
    }
    String normalizedQuery = query?.trim()
    if (!normalizedQuery) {
      return List.of()
    }
    String cacheKey = cacheKey(resolved.provider, normalizedQuery, resolved.sessionId)
    CacheEntry cached = cache.get(cacheKey)
    if (cached != null) {
      int available = Math.min(cached.results.size(), resolved.limit)
      return new ArrayList<>(cached.results.subList(0, available))
    }

    try (
      Playwright playwright = playwrightSupplier.get();
      Browser browser = playwright.chromium().launch(
        new BrowserType.LaunchOptions().setHeadless(resolved.headless)
      )
    ) {
      def page = browser.newPage()
      page.setDefaultTimeout(resolved.timeoutMillis)
      page.navigate(providerUrl(resolved.provider))
      page.fill("input[name='q']", normalizedQuery)
      page.press("input[name='q']", "Enter")
      page.waitForSelector(
        "div.results--mainline",
        new com.microsoft.playwright.Page.WaitForSelectorOptions().setTimeout(resolved.timeoutMillis)
      )

      List<SearchResult> results = page.querySelectorAll("div.results--mainline div.result").collect { element ->
        def titleElement = element.querySelector("h2 a")
        def snippetElement = element.querySelector("span.result__snippet")
        new SearchResult(
          title: sanitize(titleElement?.innerText(), 120),
          url: titleElement?.getAttribute("href"),
          snippet: sanitize(snippetElement?.innerText(), 240)
        )
      }.findAll { it.title || it.url || it.snippet }

      cache.put(cacheKey, new CacheEntry(results))
      int take = Math.min(results.size(), resolved.limit)
      List<SearchResult> limited = results.subList(0, take)
      return new ArrayList<>(limited)
    } catch (Exception e) {
      return List.of(failureResult(e.message))
    }
  }

  private static String providerUrl(SearchProvider provider) {
    switch (provider) {
      case SearchProvider.DUCKDUCKGO:
        return "https://duckduckgo.com/"
      default:
        return "https://duckduckgo.com/"
    }
  }

  private static String sanitize(String text, int maxLength) {
    if (text == null) {
      return null
    }
    String cleaned = text.replaceAll("\\s+", " ").trim()
    if (cleaned.length() > maxLength) {
      int safeLength = Math.max(0, maxLength - 3)
      return cleaned.substring(0, safeLength) + "..."
    }
    cleaned
  }

  private static SearchResult disabledResult() {
    new SearchResult(
      title: "Web search disabled",
      url: null,
      snippet: "Web search is disabled for this session."
    )
  }

  private static SearchResult failureResult(String message) {
    String detail = message ?: "Unknown error while performing web search."
    new SearchResult(
      title: "Web search unavailable",
      url: null,
      snippet: detail
    )
  }

  private static String cacheKey(SearchProvider provider, String query, String sessionId) {
    (sessionId ?: "default") + "|" + provider.name() + "|" + query.toLowerCase(Locale.ROOT)
  }

  @Canonical
  @CompileStatic
  private static class CacheEntry {
    List<SearchResult> results
  }
}
