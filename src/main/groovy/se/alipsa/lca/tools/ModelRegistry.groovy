package se.alipsa.lca.tools

import groovy.json.JsonSlurper
import groovy.transform.Canonical
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Component
@CompileStatic
class ModelRegistry {

  private static final Logger log = LoggerFactory.getLogger(ModelRegistry)
  private final URI tagsUri
  private final HttpClient client
  private final Duration timeout
  private final String baseUrl
  private final long cacheTtlMillis
  private final long healthTtlMillis
  protected volatile List<String> cachedModels = null
  protected volatile long cachedAt = 0L
  private volatile Health cachedHealth = null
  private volatile long healthCachedAt = 0L

  @Autowired
  ModelRegistry(
    @Value('${spring.ai.ollama.base-url:http://localhost:11434}') String baseUrl,
    @Value('${assistant.llm.registry-timeout-millis:4000}') long timeoutMillis,
    @Value('${assistant.llm.model-cache-ttl-millis:30000}') long cacheTtlMillis,
    @Value('${assistant.llm.health-cache-ttl-millis:5000}') long healthTtlMillis
  ) {
    this(
      baseUrl,
      timeoutMillis,
      cacheTtlMillis,
      healthTtlMillis,
      HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMillis > 0 ? timeoutMillis : 4000L)).build()
    )
  }

  ModelRegistry(String baseUrl, long timeoutMillis, long cacheTtlMillis, long healthTtlMillis, HttpClient httpClient) {
    if (baseUrl == null || baseUrl.trim().isEmpty()) {
      throw new IllegalArgumentException("Ollama baseUrl must be provided")
    }
    this.baseUrl = baseUrl
    String normalized = baseUrl?.endsWith("/") ? baseUrl[0..-2] : baseUrl
    this.tagsUri = URI.create("${normalized}/api/tags")
    this.timeout = Duration.ofMillis(timeoutMillis > 0 ? timeoutMillis : 4000L)
    this.cacheTtlMillis = cacheTtlMillis > 0 ? cacheTtlMillis : 30000L
    this.healthTtlMillis = healthTtlMillis > 0 ? healthTtlMillis : 5000L
    this.client = httpClient
  }

  List<String> listModels() {
    long now = nowMillis()
    List<String> current
    long currentAt
    synchronized (this) {
      current = cachedModels
      currentAt = cachedAt
    }
    if (current != null && (now - currentAt) < cacheTtlMillis) {
      return List.copyOf(current)
    }
    // double-check after potential fetch to avoid redundant requests
    synchronized (this) {
      if (cachedModels != null && (nowMillis() - cachedAt) < cacheTtlMillis) {
        return List.copyOf(cachedModels)
      }
    }
    try {
      HttpResponse<String> response = fetchTags()
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        Map parsed = (Map) new JsonSlurper().parseText(response.body())
        Object modelsObj = parsed != null ? parsed.get("models") : null
        if (modelsObj instanceof List) {
          List<?> rawModels = (List<?>) modelsObj
          List<String> models = rawModels.collect { Object it ->
            if (it instanceof Map && ((Map) it).containsKey("name")) {
              Object name = ((Map) it).get("name")
              return name != null ? name.toString() : null
            }
            it != null ? it.toString() : null
          }.findAll { it } as List<String>
          synchronized (this) {
            cachedModels = models
            cachedAt = now
          }
          return List.copyOf(models)
        }
      }
      log.debug("Unexpected response listing models: status {}", response.statusCode())
    } catch (Exception e) {
      log.debug("Failed to list models from {}", tagsUri, e)
    }
    if (current != null) {
      boolean stale = (now - currentAt) >= cacheTtlMillis
      if (stale) {
        log.info("Returning stale model cache due to fetch failure; cache age={}ms", now - currentAt)
      }
      return List.copyOf(current)
    }
    List.of()
  }

  boolean isModelAvailable(String modelName) {
    if (modelName == null || modelName.trim().isEmpty()) {
      return false
    }
    return listModels().any { it.equalsIgnoreCase(modelName.trim()) }
  }

  Health checkHealth() {
    long now = nowMillis()
    Health healthSnapshot
    long healthAt
    synchronized (this) {
      healthSnapshot = cachedHealth
      healthAt = healthCachedAt
    }
    if (healthSnapshot != null && (now - healthAt) < healthTtlMillis) {
      return healthSnapshot
    }
    synchronized (this) {
      if (cachedHealth != null && (nowMillis() - healthCachedAt) < healthTtlMillis) {
        return cachedHealth
      }
      try {
        HttpResponse<Void> response = fetchHealth()
        boolean ok = response.statusCode() >= 200 && response.statusCode() < 300
        Health health = new Health(ok, ok ? "reachable" : "received status ${response.statusCode()}".toString())
        cachedHealth = health
        healthCachedAt = nowMillis()
        return health
      } catch (Exception e) {
        Health health = new Health(false, e.message ?: e.class.simpleName)
        cachedHealth = health
        healthCachedAt = nowMillis()
        return health
      }
    }
  }

  protected HttpResponse<String> fetchTags() throws Exception {
    HttpRequest request = HttpRequest.newBuilder(tagsUri).timeout(timeout).GET().build()
    client.send(request, HttpResponse.BodyHandlers.ofString())
  }

  protected HttpResponse<Void> fetchHealth() throws Exception {
    HttpRequest request = HttpRequest.newBuilder(tagsUri).timeout(timeout).GET().build()
    client.send(request, HttpResponse.BodyHandlers.discarding())
  }

  protected long nowMillis() {
    System.currentTimeMillis()
  }

  @Canonical
  @CompileStatic
  static class Health {
    boolean reachable
    String message
  }

  String getBaseUrl() {
    baseUrl
  }
}
