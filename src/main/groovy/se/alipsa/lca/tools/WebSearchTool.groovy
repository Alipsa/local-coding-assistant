package se.alipsa.lca.tools

import com.embabel.agent.domain.library.InternetResource
import com.embabel.agent.domain.library.InternetResources
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.htmlunit.BrowserVersion
import org.htmlunit.Page
import org.htmlunit.WebClient
import org.htmlunit.WebClientOptions
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.Map
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Component
@CompileStatic
class WebSearchTool {

  interface SearchFetcher {
    Document fetch(SearchProvider provider, String query, SearchOptions options)
  }

  private static final String DEFAULT_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

  private final String defaultFetcherName
  private final String defaultFallbackFetcherName
  private final SearchFetcher searchFetcher
  private final Map<String, CacheEntry> cache = Collections.synchronizedMap(
    new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
        size() > 20
      }
    }
  )

  @Autowired
  WebSearchTool(
    @Value('${assistant.web-search.fetcher:htmlunit}') String fetcherName,
    @Value('${assistant.web-search.fallback-fetcher:jsoup}') String fallbackFetcherName
  ) {
    String resolvedFetcher = normaliseFetcherName(fetcherName, "htmlunit")
    String resolvedFallback = normaliseFetcherName(fallbackFetcherName, null)
    this.defaultFetcherName = resolvedFetcher
    this.defaultFallbackFetcherName = resolvedFallback
    this.searchFetcher = buildFetcher(resolvedFetcher, resolvedFallback)
  }

  WebSearchTool(SearchFetcher searchFetcher) {
    this.defaultFetcherName = "htmlunit"
    this.defaultFallbackFetcherName = "jsoup"
    this.searchFetcher = searchFetcher
  }

  @Canonical
  @CompileStatic
  static class SearchResult {
    String title
    String url
    String snippet

    /**
     * Convert this SearchResult to Embabel's InternetResource format
     */
    InternetResource toInternetResource() {
      new SearchResultAsInternetResource(url, snippet ?: title)
    }
  }

  /**
   * Adapter class that converts our SearchResult to Embabel's InternetResource format
   */
  @CompileStatic
  static class SearchResultAsInternetResource extends InternetResource {

    SearchResultAsInternetResource(String url, String summary) {
      super(url, summary)
    }
  }

  /**
   * Wrapper class that holds a list of search results in Embabel's InternetResources format
   */
  @CompileStatic
  static class WebSearchResults implements InternetResources {
    private final List<InternetResource> links

    WebSearchResults(List<InternetResource> links) {
      this.links = List.copyOf(links ?: [])
    }

    @Override
    List<InternetResource> getLinks() {
      return links
    }
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
    String fetcherName
    String fallbackFetcherName
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
    resolved.fetcherName = incoming.fetcherName
    resolved.fallbackFetcherName = incoming.fallbackFetcherName
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

  @CompileStatic
  enum FetcherKind {
    HTMLUNIT,
    JSOUP
  }

  private static String normaliseFetcherName(String value, String fallback) {
    if (value == null) {
      return fallback
    }
    String trimmed = value.trim()
    if (!trimmed) {
      return fallback
    }
    trimmed.toLowerCase(Locale.ROOT)
  }

  private static String resolveFetcherName(String value) {
    if (value == null) {
      return null
    }
    String trimmed = value.trim()
    if (!trimmed) {
      return null
    }
    String normalised = trimmed.toLowerCase(Locale.ROOT)
    normalised == "default" ? null : normalised
  }

  private SearchFetcher resolveFetcher(SearchOptions options) {
    String overridePrimary = resolveFetcherName(options?.fetcherName)
    String overrideFallback = resolveFetcherName(options?.fallbackFetcherName)
    String primary = overridePrimary ?: defaultFetcherName
    String fallback
    if (options?.fallbackFetcherName != null) {
      fallback = overrideFallback ?: defaultFallbackFetcherName
    } else {
      fallback = defaultFallbackFetcherName
    }
    if (primary == defaultFetcherName && fallback == defaultFallbackFetcherName) {
      return searchFetcher
    }
    buildFetcher(primary, fallback)
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
    String key = cacheKey(resolved.provider, normalizedQuery, resolved.sessionId)
    CacheEntry cached = cache.get(key)
    if (cached != null) {
      int available = Math.min(cached.results.size(), resolved.limit)
      return new ArrayList<>(cached.results.subList(0, available))
    }

    try {
      SearchFetcher fetcher = resolveFetcher(resolved)
      Document document = fetcher.fetch(resolved.provider, normalizedQuery, resolved)
      List<SearchResult> results = parseResults(document, resolved.provider)
      cache.put(key, new CacheEntry(results))
      int take = Math.min(results.size(), resolved.limit)
      List<SearchResult> limited = results.subList(0, take)
      return new ArrayList<>(limited)
    } catch (Exception e) {
      return List.of(failureResult(e.message))
    }
  }

  /**
   * Search and return results as Embabel InternetResources for better integration with Embabel agents
   */
  List<InternetResource> searchAsInternetResources(String query) {
    searchAsInternetResources(query, new SearchOptions())
  }

  /**
   * Search and return results as Embabel InternetResources for better integration with Embabel agents
   */
  List<InternetResource> searchAsInternetResources(String query, SearchOptions options) {
    List<SearchResult> results = search(query, options)
    return results.collect { it.toInternetResource() }
  }

  /**
   * Search and wrap results in WebSearchResults for use with .withPromptContributor()
   */
  WebSearchResults searchAsWebSearchResults(String query) {
    searchAsWebSearchResults(query, new SearchOptions())
  }

  /**
   * Search and wrap results in WebSearchResults for use with .withPromptContributor()
   */
  WebSearchResults searchAsWebSearchResults(String query, SearchOptions options) {
    List<InternetResource> resources = searchAsInternetResources(query, options)
    new WebSearchResults(resources)
  }

  @CompileStatic
  static String summariseResults(String query, List<SearchResult> results, int limit, int maxChars) {
    int safeLimit = limit > 0 ? limit : 3
    int safeMaxChars = maxChars > 0 ? maxChars : 1200
    String cleanedQuery = query != null ? query.trim() : ""
    StringBuilder builder = new StringBuilder()
    if (cleanedQuery) {
      builder.append("Web search results for \"").append(cleanedQuery).append("\":\n")
    } else {
      builder.append("Web search results:\n")
    }
    List<SearchResult> safeResults = results ?: List.of()
    if (safeResults.isEmpty()) {
      builder.append("No results.")
      return clampSummary(builder.toString().stripTrailing(), safeMaxChars)
    }
    safeResults.take(safeLimit).eachWithIndex { SearchResult result, int idx ->
      String title = result?.title ?: "(no title)"
      String url = result?.url ?: "(no url)"
      builder.append(idx + 1).append(". ").append(title).append(" - ").append(url)
      String snippet = result?.snippet
      if (snippet != null && snippet.trim()) {
        builder.append("\n").append(snippet.trim())
      }
      builder.append("\n")
    }
    clampSummary(builder.toString().stripTrailing(), safeMaxChars)
  }

  private static String clampSummary(String summary, int maxChars) {
    if (summary == null) {
      return ""
    }
    String trimmed = summary.stripTrailing()
    if (trimmed.length() <= maxChars) {
      return trimmed
    }
    String shortened = trimmed.substring(0, Math.max(1, maxChars - 3))
    shortened + "..."
  }

  private static String providerUrl(SearchProvider provider) {
    switch (provider) {
      case SearchProvider.DUCKDUCKGO:
        return "https://duckduckgo.com/html/"
      default:
        return "https://duckduckgo.com/html/"
    }
  }

  @CompileStatic
  static List<FetcherKind> resolveFetcherKinds(String primaryName, String fallbackName) {
    FetcherKind primary = resolveFetcherKind(primaryName)
    FetcherKind fallback = resolveFetcherKind(fallbackName)
    if (primary == null) {
      primary = FetcherKind.HTMLUNIT
    }
    List<FetcherKind> kinds = new ArrayList<>()
    kinds.add(primary)
    if (fallback != null && fallback != primary) {
      kinds.add(fallback)
    }
    kinds
  }

  private static FetcherKind resolveFetcherKind(String value) {
    if (value == null) {
      return null
    }
    String trimmed = value.trim()
    if (!trimmed || trimmed.equalsIgnoreCase("none")) {
      return null
    }
    String normalised = trimmed.toLowerCase(Locale.ROOT)
    switch (normalised) {
      case "htmlunit":
        return FetcherKind.HTMLUNIT
      case "jsoup":
        return FetcherKind.JSOUP
      default:
        return null
    }
  }

  private static SearchFetcher buildFetcher(String primaryName, String fallbackName) {
    List<FetcherKind> kinds = resolveFetcherKinds(primaryName, fallbackName)
    List<SearchFetcher> fetchers = kinds.collect { FetcherKind kind ->
      createFetcher(kind)
    }
    if (fetchers.size() == 1) {
      return fetchers[0]
    }
    new CompositeSearchFetcher(fetchers)
  }

  private static SearchFetcher createFetcher(FetcherKind kind) {
    switch (kind) {
      case FetcherKind.HTMLUNIT:
        return new HtmlUnitSearchFetcher()
      case FetcherKind.JSOUP:
        return new JsoupSearchFetcher()
      default:
        return new HtmlUnitSearchFetcher()
    }
  }

  private static String describeFetcher(SearchFetcher fetcher) {
    if (fetcher instanceof HtmlUnitSearchFetcher) {
      return "htmlunit"
    }
    if (fetcher instanceof JsoupSearchFetcher) {
      return "jsoup"
    }
    return fetcher?.getClass()?.getSimpleName() ?: "unknown"
  }

  private static List<SearchResult> parseResults(Document document, SearchProvider provider) {
    if (document == null) {
      return List.of()
    }
    switch (provider) {
      case SearchProvider.DUCKDUCKGO:
        return parseDuckDuckGo(document)
      default:
        return parseDuckDuckGo(document)
    }
  }

  private static List<SearchResult> parseDuckDuckGo(Document document) {
    List<SearchResult> results = new ArrayList<>()
    document.select("div.result").each { Element element ->
      Element link = element.selectFirst("a.result__a")
      String title = sanitise(link?.text(), 120)
      String url = normaliseUrl(link?.attr("href"))
      Element snippetElement = element.selectFirst("a.result__snippet, div.result__snippet, span.result__snippet")
      String snippet = sanitise(snippetElement?.text(), 240)
      if (title || url || snippet) {
        results.add(new SearchResult(title: title, url: url, snippet: snippet))
      }
    }
    results
  }

  private static String normaliseUrl(String value) {
    if (value == null) {
      return null
    }
    String trimmed = value.trim()
    if (!trimmed) {
      return null
    }
    if (trimmed.contains("uddg=")) {
      int idx = trimmed.indexOf("uddg=")
      if (idx >= 0) {
        String encoded = trimmed.substring(idx + 5)
        int end = encoded.indexOf("&")
        String token = end > 0 ? encoded.substring(0, end) : encoded
        try {
          return URLDecoder.decode(token, StandardCharsets.UTF_8)
        } catch (IllegalArgumentException ignored) {
          return trimmed
        }
      }
    }
    if (trimmed.startsWith("/l/") || trimmed.startsWith("/")) {
      return "https://duckduckgo.com" + trimmed
    }
    trimmed
  }

  private static String sanitise(String text, int maxLength) {
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

  @CompileStatic
  static class HtmlUnitSearchFetcher implements SearchFetcher {

    @Override
    Document fetch(SearchProvider provider, String query, SearchOptions options) {
      String url = providerUrl(provider)
      String requestUrl = url + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
      String html = fetchContent(requestUrl, options)
      Jsoup.parse(html, requestUrl)
    }

    protected String fetchContent(String requestUrl, SearchOptions options) {
      WebClient webClient = new WebClient(BrowserVersion.BEST_SUPPORTED)
      try {
        WebClientOptions clientOptions = webClient.getOptions()
        clientOptions.setCssEnabled(false)
        clientOptions.setJavaScriptEnabled(false)
        clientOptions.setThrowExceptionOnFailingStatusCode(false)
        clientOptions.setThrowExceptionOnScriptError(false)
        int timeout = Math.toIntExact(Math.min(Integer.MAX_VALUE, options.timeoutMillis))
        clientOptions.setTimeout(timeout)
        webClient.addRequestHeader("User-Agent", DEFAULT_USER_AGENT)
        webClient.addRequestHeader("Accept-Language", "en-GB,en;q=0.8")
        Page page = webClient.getPage(requestUrl)
        page.getWebResponse().getContentAsString()
      } finally {
        webClient.close()
      }
    }
  }

  @CompileStatic
  static class CompositeSearchFetcher implements SearchFetcher {
    private final List<SearchFetcher> fetchers

    CompositeSearchFetcher(List<SearchFetcher> fetchers) {
      this.fetchers = fetchers != null ? new ArrayList<>(fetchers) : List.of()
    }

    @Override
    Document fetch(SearchProvider provider, String query, SearchOptions options) {
      List<String> failures = new ArrayList<>()
      for (SearchFetcher fetcher : fetchers) {
        try {
          Document document = fetcher.fetch(provider, query, options)
          if (document != null) {
            return document
          }
          failures.add(describeFetcher(fetcher) + " returned empty response")
        } catch (Exception e) {
          String reason = e.message ?: e.getClass().getSimpleName()
          failures.add(describeFetcher(fetcher) + ": " + reason)
        }
      }
      String message = failures.isEmpty()
        ? "No search fetchers configured."
        : "All search fetchers failed: " + failures.join("; ")
      throw new IllegalStateException(message)
    }
  }

  @CompileStatic
  private static class JsoupSearchFetcher implements SearchFetcher {

    @Override
    Document fetch(SearchProvider provider, String query, SearchOptions options) {
      String url = providerUrl(provider)
      int timeout = Math.toIntExact(Math.min(Integer.MAX_VALUE, options.timeoutMillis))
      Jsoup.connect(url)
        .data("q", query)
        .userAgent(DEFAULT_USER_AGENT)
        .timeout(timeout)
        .header("Accept-Language", "en-GB,en;q=0.8")
        .get()
    }
  }
}
