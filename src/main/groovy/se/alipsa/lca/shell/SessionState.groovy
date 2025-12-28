package se.alipsa.lca.shell

import com.embabel.chat.Conversation
import com.embabel.chat.support.InMemoryConversation
import com.embabel.common.ai.model.LlmOptions
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import se.alipsa.lca.tools.AgentsMdProvider
import se.alipsa.lca.tools.LocalOnlyState

import java.time.Instant
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.Objects

@Component
@CompileStatic
class SessionState {

  private final Map<String, SessionSettings> sessions = new ConcurrentHashMap<>()
  private final Map<String, List<String>> history = new ConcurrentHashMap<>()
  private final Map<String, Conversation> conversations = new ConcurrentHashMap<>()
  private final Map<String, ToolSummary> toolSummaries = new ConcurrentHashMap<>()
  private final String defaultModel
  private final double defaultCraftTemperature
  private final double defaultReviewTemperature
  private final Integer defaultMaxTokens
  private final String defaultSystemPrompt
  private final boolean defaultWebSearchEnabled
  private final String defaultWebSearchFetcher
  private final String defaultWebSearchFallbackFetcher
  private final long toolSummaryTtlSeconds
  private final String fallbackModel
  private final AgentsMdProvider agentsMdProvider
  private final LocalOnlyState localOnlyState

  SessionState(
    @Value('${assistant.llm.model:qwen3-coder:30b}') String defaultModel,
    @Value('${assistant.llm.temperature.craft:0.7}') double defaultCraftTemperature,
    @Value('${assistant.llm.temperature.review:0.35}') double defaultReviewTemperature,
    @Value('${assistant.llm.max-tokens:0}') Integer defaultMaxTokens,
    @Value('${assistant.system-prompt:}') String defaultSystemPrompt,
    @Value('${assistant.web-search.enabled:true}') boolean defaultWebSearchEnabled,
    @Value('${assistant.web-search.fetcher:htmlunit}') String defaultWebSearchFetcher,
    @Value('${assistant.web-search.fallback-fetcher:jsoup}') String defaultWebSearchFallbackFetcher,
    @Value('${assistant.tool-summary.ttl-seconds:600}') long toolSummaryTtlSeconds,
    @Value('${assistant.llm.fallback-model:${embabel.models.llms.cheapest:}}') String fallbackModel,
    AgentsMdProvider agentsMdProvider,
    LocalOnlyState localOnlyState
  ) {
    this.defaultModel = defaultModel
    this.defaultCraftTemperature = defaultCraftTemperature
    this.defaultReviewTemperature = defaultReviewTemperature
    this.defaultMaxTokens = defaultMaxTokens
    this.defaultSystemPrompt = defaultSystemPrompt
    this.defaultWebSearchEnabled = defaultWebSearchEnabled
    this.defaultWebSearchFetcher = normaliseFetcherName(defaultWebSearchFetcher, "htmlunit")
    this.defaultWebSearchFallbackFetcher = normaliseFetcherName(defaultWebSearchFallbackFetcher, "jsoup")
    this.toolSummaryTtlSeconds = toolSummaryTtlSeconds > 0 ? toolSummaryTtlSeconds : 600L
    this.fallbackModel = (fallbackModel != null && fallbackModel.trim()) ? fallbackModel.trim() : null
    this.agentsMdProvider = Objects.requireNonNull(agentsMdProvider, "agentsMdProvider must not be null")
    this.localOnlyState = Objects.requireNonNull(localOnlyState, "localOnlyState must not be null")
  }

  SessionSettings update(
    String sessionId,
    String model,
    Double craftTemperature,
    Double reviewTemperature,
    Integer maxTokens,
    String systemPrompt,
    Boolean webSearchEnabled
  ) {
    String key = sessionId ?: "default"
    sessions.compute(key) { _, existing ->
      SessionSettings current = existing ?: new SessionSettings(key, null, null, null, null, null, null, null, null)
      new SessionSettings(
        key,
        model != null ? model : current.model,
        craftTemperature != null ? craftTemperature : current.craftTemperature,
        reviewTemperature != null ? reviewTemperature : current.reviewTemperature,
        maxTokens != null ? maxTokens : current.maxTokens,
        systemPrompt != null ? systemPrompt : current.systemPrompt,
        webSearchEnabled != null ? webSearchEnabled : current.webSearchEnabled,
        current.webSearchFetcher,
        current.webSearchFallbackFetcher
      )
    }
  }

  SessionSettings getOrCreate(String sessionId) {
    String key = sessionId ?: "default"
    sessions.computeIfAbsent(key) {
      new SessionSettings(it, null, null, null, null, null, null, null, null)
    }
  }

  LlmOptions craftOptions(SessionSettings settings) {
    buildOptions(settings.model, settings.craftTemperature, defaultCraftTemperature, settings.maxTokens)
  }

  LlmOptions reviewOptions(SessionSettings settings) {
    buildOptions(settings.model, settings.reviewTemperature, defaultReviewTemperature, settings.maxTokens)
  }

  String systemPrompt(SessionSettings settings) {
    String base = settings.systemPrompt != null && settings.systemPrompt.trim()
      ? settings.systemPrompt
      : defaultSystemPrompt
    agentsMdProvider.appendToSystemPrompt(base)
  }

  String getDefaultModel() {
    defaultModel
  }

  String getFallbackModel() {
    fallbackModel
  }

  void appendHistory(String sessionId, String... entries) {
    String key = sessionId ?: "default"
    List<String> sessionHistory = history.computeIfAbsent(key) { new CopyOnWriteArrayList<>() }
    entries.each { sessionHistory.add(it) }
  }

  List<String> history(String sessionId) {
    history.getOrDefault(sessionId ?: "default", List.of())
  }

  Conversation getOrCreateConversation(String sessionId) {
    String key = sessionId ?: "default"
    conversations.computeIfAbsent(key) {
      new InMemoryConversation(new ArrayList<>(), key)
    }
  }

  void storeToolSummary(String sessionId, ToolSummary summary) {
    if (summary == null || summary.summary == null || summary.summary.trim().isEmpty()) {
      return
    }
    toolSummaries.put(normaliseSession(sessionId), summary)
  }

  ToolSummary getLastToolSummary(String sessionId) {
    toolSummaries.get(normaliseSession(sessionId))
  }

  ToolSummary getRecentToolSummary(String sessionId) {
    ToolSummary summary = getLastToolSummary(sessionId)
    if (summary == null) {
      return null
    }
    if (summary.timestamp == null || toolSummaryTtlSeconds <= 0) {
      return summary
    }
    Instant cutoff = Instant.now().minusSeconds(toolSummaryTtlSeconds)
    summary.timestamp.isBefore(cutoff) ? null : summary
  }

  boolean isWebSearchEnabled(String sessionId) {
    SessionSettings settings = getOrCreate(sessionId)
    Boolean desired = settings.webSearchEnabled != null ? settings.webSearchEnabled : defaultWebSearchEnabled
    if (localOnlyState.isLocalOnly(sessionId) && Boolean.TRUE.equals(desired)) {
      return false
    }
    desired
  }

  boolean isWebSearchDesired(String sessionId) {
    SessionSettings settings = getOrCreate(sessionId)
    settings.webSearchEnabled != null ? settings.webSearchEnabled : defaultWebSearchEnabled
  }

  boolean isLocalOnly() {
    localOnlyState.isLocalOnly("default")
  }

  boolean isLocalOnly(String sessionId) {
    localOnlyState.isLocalOnly(sessionId)
  }

  String getWebSearchFetcher(String sessionId) {
    SessionSettings settings = getOrCreate(sessionId)
    settings.webSearchFetcher ?: defaultWebSearchFetcher
  }

  String getWebSearchFallbackFetcher(String sessionId) {
    SessionSettings settings = getOrCreate(sessionId)
    settings.webSearchFallbackFetcher ?: defaultWebSearchFallbackFetcher
  }

  void setWebSearchFetcherOverride(String sessionId, String fetcherName) {
    String key = sessionId ?: "default"
    String resolved = normaliseFetcherOverride(fetcherName)
    sessions.compute(key) { _, existing ->
      SessionSettings current = existing ?: new SessionSettings(key, null, null, null, null, null, null, null, null)
      new SessionSettings(
        key,
        current.model,
        current.craftTemperature,
        current.reviewTemperature,
        current.maxTokens,
        current.systemPrompt,
        current.webSearchEnabled,
        resolved,
        current.webSearchFallbackFetcher
      )
    }
  }

  void setWebSearchEnabledOverride(String sessionId, Boolean enabled) {
    String key = sessionId ?: "default"
    sessions.compute(key) { _, existing ->
      SessionSettings current = existing ?: new SessionSettings(key, null, null, null, null, null, null, null, null)
      new SessionSettings(
        key,
        current.model,
        current.craftTemperature,
        current.reviewTemperature,
        current.maxTokens,
        current.systemPrompt,
        enabled,
        current.webSearchFetcher,
        current.webSearchFallbackFetcher
      )
    }
  }

  void setWebSearchFallbackFetcherOverride(String sessionId, String fetcherName) {
    String key = sessionId ?: "default"
    String resolved = normaliseFetcherOverride(fetcherName)
    sessions.compute(key) { _, existing ->
      SessionSettings current = existing ?: new SessionSettings(key, null, null, null, null, null, null, null, null)
      new SessionSettings(
        key,
        current.model,
        current.craftTemperature,
        current.reviewTemperature,
        current.maxTokens,
        current.systemPrompt,
        current.webSearchEnabled,
        current.webSearchFetcher,
        resolved
      )
    }
  }

  Boolean getLocalOnlyOverride(String sessionId) {
    localOnlyState.getLocalOnlyOverride(sessionId)
  }

  void setLocalOnlyOverride(String sessionId, Boolean enabled) {
    localOnlyState.setLocalOnlyOverride(sessionId, enabled)
  }

  private LlmOptions buildOptions(
    String model,
    Double temperature,
    double fallbackTemperature,
    Integer maxTokens
  ) {
    String resolvedModel = model ?: defaultModel
    LlmOptions options = resolvedModel ? LlmOptions.withModel(resolvedModel) : LlmOptions.withDefaultLlm()
    if (resolvedModel && options.getModel() == null) {
      options.setModel(resolvedModel)
    }
    options = options.withTemperature(temperature != null ? temperature : fallbackTemperature)
    Integer resolvedMaxTokens = (maxTokens != null && maxTokens > 0) ? maxTokens : defaultMaxTokens
    if (resolvedMaxTokens != null && resolvedMaxTokens > 0) {
      options = options.withMaxTokens(resolvedMaxTokens)
    }
    options
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

  private static String normaliseFetcherOverride(String value) {
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

  private static String normaliseSession(String sessionId) {
    sessionId != null && sessionId.trim() ? sessionId.trim() : "default"
  }

  @Canonical
  @CompileStatic
  static class ToolSummary {
    String source
    String summary
    Instant timestamp
  }

  @Canonical
  @CompileStatic
  static class SessionSettings {
    String sessionId
    String model
    Double craftTemperature
    Double reviewTemperature
    Integer maxTokens
    String systemPrompt
    Boolean webSearchEnabled
    String webSearchFetcher
    String webSearchFallbackFetcher
  }
}
