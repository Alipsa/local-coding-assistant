package se.alipsa.lca.shell

import com.embabel.common.ai.model.LlmOptions
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Component
@CompileStatic
class SessionState {

  private final Map<String, SessionSettings> sessions = new ConcurrentHashMap<>()
  private final Map<String, List<String>> history = new ConcurrentHashMap<>()
  private final String defaultModel
  private final double defaultCraftTemperature
  private final double defaultReviewTemperature
  private final Integer defaultMaxTokens
  private final String defaultSystemPrompt

  SessionState(
    @Value('${assistant.llm.model:qwen3-coder:30b}') String defaultModel,
    @Value('${assistant.llm.temperature.craft:0.7}') double defaultCraftTemperature,
    @Value('${assistant.llm.temperature.review:0.35}') double defaultReviewTemperature,
    @Value('${assistant.llm.max-tokens:0}') Integer defaultMaxTokens,
    @Value('${assistant.system-prompt:}') String defaultSystemPrompt
  ) {
    this.defaultModel = defaultModel
    this.defaultCraftTemperature = defaultCraftTemperature
    this.defaultReviewTemperature = defaultReviewTemperature
    this.defaultMaxTokens = defaultMaxTokens
    this.defaultSystemPrompt = defaultSystemPrompt
  }

  SessionSettings update(
    String sessionId,
    String model,
    Double craftTemperature,
    Double reviewTemperature,
    Integer maxTokens,
    String systemPrompt
  ) {
    String key = sessionId ?: "default"
    sessions.compute(key) { _, existing ->
      SessionSettings current = existing ?: new SessionSettings(key, null, null, null, null, null)
      new SessionSettings(
        key,
        model != null ? model : current.model,
        craftTemperature != null ? craftTemperature : current.craftTemperature,
        reviewTemperature != null ? reviewTemperature : current.reviewTemperature,
        maxTokens != null ? maxTokens : current.maxTokens,
        systemPrompt != null ? systemPrompt : current.systemPrompt
      )
    }
  }

  SessionSettings getOrCreate(String sessionId) {
    String key = sessionId ?: "default"
    sessions.computeIfAbsent(key) {
      new SessionSettings(it, null, null, null, null, null)
    }
  }

  LlmOptions craftOptions(SessionSettings settings) {
    buildOptions(settings.model, settings.craftTemperature, defaultCraftTemperature, settings.maxTokens)
  }

  LlmOptions reviewOptions(SessionSettings settings) {
    buildOptions(settings.model, settings.reviewTemperature, defaultReviewTemperature, settings.maxTokens)
  }

  String systemPrompt(SessionSettings settings) {
    if (settings.systemPrompt != null && settings.systemPrompt.trim()) {
      return settings.systemPrompt
    }
    defaultSystemPrompt
  }

  void appendHistory(String sessionId, String... entries) {
    String key = sessionId ?: "default"
    List<String> sessionHistory = history.computeIfAbsent(key) { new CopyOnWriteArrayList<>() }
    entries.each { sessionHistory.add(it) }
  }

  List<String> history(String sessionId) {
    history.getOrDefault(sessionId ?: "default", List.of())
  }

  private LlmOptions buildOptions(
    String model,
    Double temperature,
    double fallbackTemperature,
    Integer maxTokens
  ) {
    String resolvedModel = model ?: defaultModel
    LlmOptions options = resolvedModel ? LlmOptions.withModel(resolvedModel) : LlmOptions.withDefaultLlm()
    if (resolvedModel) {
      options.setModel(resolvedModel)
    }
    options = options.withTemperature(temperature != null ? temperature : fallbackTemperature)
    Integer resolvedMaxTokens = (maxTokens != null && maxTokens > 0) ? maxTokens : defaultMaxTokens
    if (resolvedMaxTokens != null && resolvedMaxTokens > 0) {
      options = options.withMaxTokens(resolvedMaxTokens)
    }
    options
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
  }
}
